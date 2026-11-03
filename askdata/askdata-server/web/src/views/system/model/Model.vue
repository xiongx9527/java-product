<script lang="ts" setup>
import { ref, computed, shallowRef, reactive, nextTick, onUnmounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus-secondary'
import icon_searchOutline_outlined from '@/assets/svg/icon_search-outline_outlined.svg'
import icon_admin_outlined from '@/assets/svg/icon_admin_outlined.svg'
import icon_add_outlined from '@/assets/svg/icon_add_outlined.svg'
import EmptyBackground from '@/views/dashboard/common/EmptyBackground.vue'
import icon_done_outlined from '@/assets/svg/icon_done_outlined.svg'
import icon_close_outlined from '@/assets/svg/operate/ope-close.svg'
import ModelList from './ModelList.vue'
import ModelListSide from './ModelListSide.vue'
import ModelForm from './ModelForm.vue'
import { modelApi } from '@/api/system'
import Card from './Card.vue'
import { getModelTypeName } from '@/entity/CommonEntity.ts'
import { useI18n } from 'vue-i18n'
import { get_supplier_safe } from '@/entity/supplier'
import { highlightKeyword } from '@/utils/xss'
import AuthorizedWorkspaceDialogForModel from '@/views/system/workspace/AuthorizedWorkspaceDialogForModel.vue'
import AuthorizedWorkspaceDraw from '@/views/system/workspace/AuthorizedWorkspaceDraw.vue'

interface Model {
  ws_mapping_count: number | undefined
  name: string
  model_type: string
  base_model: string
  id?: string
  default_model: boolean
  supplier: number
  enable?: boolean
}

const { t } = useI18n()
const keywords = ref('')
const defaultModelKeywords = ref('')
const modelConfigvVisible = ref(false)
const searchLoading = ref(false)
const editModel = ref(false)
const activeStep = ref(0)
const activeName = ref('')
const activeNameI18nKey = ref('')
const activeType = ref('')
const modelFormRef = ref()
const cardRefs = ref<any[]>([])
const showCardError = ref(false) // if you don`t want card mask error, just change this to false
reactive({
  form: {
    id: '',
    name: '',
    model_type: 0,
    api_key: '',
    api_domain: '',
  },
  selectedIds: [],
})
const modelList = shallowRef([] as Model[])

const modelListWithSearch = computed(() => {
  if (!keywords.value) return modelList.value
  return modelList.value.filter((ele) =>
    ele.name.toLowerCase().includes(keywords.value.toLowerCase())
  )
})
const beforeClose = () => {
  modelConfigvVisible.value = false
}
const defaultModelListWithSearch = computed(() => {
  let tempModelList = modelList.value
  if (defaultModelKeywords.value) {
    tempModelList = tempModelList.filter((ele) =>
      ele.name.toLowerCase().includes(defaultModelKeywords.value.toLowerCase())
    )
  }
  return tempModelList.map((item: any) => {
    item['supplier_item'] = get_supplier_safe(item.supplier)
    return item
  })
})

const modelCheckHandler = async (item: any) => {
  const response = await modelApi.check(item)
  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let checkTimeout = false
  setTimeout(() => {
    checkTimeout = true
  }, 9000)
  let checkMsg = ''
  while (true) {
    if (checkTimeout) {
      break
    }
    const { done, value } = await reader.read()
    if (done) break
    const lines = decoder.decode(value).trim().split('\n')
    for (const line of lines) {
      const data = JSON.parse(line)
      if (data.error) {
        checkMsg += data.error
      } else if (data.content) {
        console.debug(data.content)
      }
    }
  }
  if (!checkMsg) {
    return
  }
  console.error(checkMsg)
  if (!showCardError.value) {
    ElMessage.error(checkMsg)
    return
  }
  nextTick(() => {
    const index = modelListWithSearch.value.findIndex((el: any) => el.id === item.id)
    if (index > -1) {
      const currentRef = cardRefs.value[index]
      currentRef?.showErrorMask(checkMsg)
    }
  })
}
const duplicateName = async (item: any) => {
  const res = await modelApi.queryAll()
  // 编辑时 item.id 来自表单（字符串）、ele.id 来自接口（数字），统一转字符串比较，
  // 避免「自己和自己」误判重名（否则改名保存会因包含自身而一直报重名）
  const names = res.filter((ele: any) => String(ele.id) !== String(item.id)).map((ele: any) => ele.name)
  if (names.includes(item.name)) {
    ElMessage.error(t('embedded.duplicate_name'))
    return
  }
  const param = {
    ...item,
  }
  if (!item.id) {
    modelApi.add(param).then(() => {
      beforeClose()
      search()
      ElMessage({
        type: 'success',
        message: t('workspace.add_successfully'),
      })
    })
    return
  }
  modelApi.edit(param).then(() => {
    beforeClose()
    search()
    ElMessage({
      type: 'success',
      message: t('common.save_success'),
    })
  })
}

const handleDefaultModelChange = (item: any) => {
  const current_default_node = modelList.value.find((ele: Model) => ele.default_model)
  if (current_default_node?.id === item.id) {
    return
  }
  // 仅对 enable=1 的模型可设默认
  if (!item.enable) {
    ElMessage.warning(t('modelCfg.need_valid'))
    return
  }
  // 13.4 切换向量默认模型需提示「需重新向量化且影响所有工作空间」
  const isVector = Number(item.model_type) === 1
  const msg = isVector
    ? t('model.vector_default_confirm', { msg: item.name })
    : t('model.system_default_model', { msg: item.name })
  ElMessageBox.confirm(msg, {
    confirmButtonType: 'primary',
    tip: t('model.operate_with_caution'),
    confirmButtonText: t('datasource.confirm'),
    cancelButtonText: t('common.cancel'),
    customClass: 'confirm-no_icon',
    autofocus: false,
    callback: (val: string) => {
      if (val === 'confirm') {
        modelApi.setDefault(item.id).then(() => {
          ElMessage.success(t('model.set_successfully'))
          search()
          // 切换默认向量模型后立即刷新索引状态，让「待重建」提示条即时出现
          if (isVector) loadEmbeddingStatus()
        }).catch((e: any) => {
          ElMessage.error(e?.message || String(e))
        })
      }
    },
  })
}

const formatKeywords = (item: string) => {
  // Use XSS-safe highlight function
  return highlightKeyword(item, defaultModelKeywords.value, 'isSearch')
}
const handleAddModel = () => {
  activeStep.value = 0
  editModel.value = false
  modelConfigvVisible.value = true
}
const handleEditModel = (row: any) => {
  activeStep.value = 1
  editModel.value = true
  activeType.value = row.supplier
  // supplier_item 只在「系统默认模型」弹层渲染时补到列表项上，直接点编辑时可能不存在；
  // 用供应商表兜底（supplier=0 表示列表里没有的自定义供应商），避免 row.supplier_item.name
  // 抛 TypeError 导致编辑抽屉打不开。
  const supplier = row.supplier_item || get_supplier_safe(row.supplier)
  activeName.value = supplier?.name || row.name || ''
  activeNameI18nKey.value = supplier?.i18nKey || ''
  modelApi.query(row.id).then((res: any) => {
    modelConfigvVisible.value = true
    nextTick(() => {
      modelFormRef.value.initForm({ ...res })
    })
  })
}

const handleDefault = (row: any) => {
  if (row.default_model) return
  // 仅对 enable=1 的模型可设默认
  if (!row.enable) {
    ElMessage.warning(t('modelCfg.need_valid'))
    return
  }
  // 13.4 切换向量默认模型需提示「需重新向量化且影响所有工作空间」
  const isVector = Number(row.model_type) === 1
  const msg = isVector
    ? t('model.vector_default_confirm', { msg: row.name })
    : t('model.system_default_model', { msg: row.name })
  ElMessageBox.confirm(msg, {
    confirmButtonType: 'primary',
    tip: t('model.operate_with_caution'),
    confirmButtonText: t('datasource.confirm'),
    cancelButtonText: t('common.cancel'),
    customClass: 'confirm-no_icon',
    autofocus: false,
    callback: (val: string) => {
      if (val === 'confirm') {
        modelApi.setDefault(row.id).then(() => {
          ElMessage.success(t('model.set_successfully'))
          search()
          // 切换默认向量模型后立即刷新索引状态，让「待重建」提示条即时出现
          if (isVector) loadEmbeddingStatus()
        }).catch((e: any) => {
          ElMessage.error(e?.message || String(e))
        })
      }
    },
  })
}

const deleteHandler = (item: any) => {
  if (item.default_model) {
    ElMessageBox.confirm(t('model.del_default_tip', { msg: item.name }), {
      confirmButtonType: 'primary',
      tip: t('model.del_default_warn'),
      showConfirmButton: false,
      confirmButtonText: t('datasource.confirm'),
      cancelButtonText: t('datasource.got_it'),
      customClass: 'confirm-no_icon',
      autofocus: false,
      callback: (val: string) => {
        console.info(val)
      },
    })
    return
  }
  ElMessageBox.confirm(t('model.del_warn_tip', { msg: item.name }), {
    confirmButtonType: 'danger',
    confirmButtonText: t('dashboard.delete'),
    cancelButtonText: t('common.cancel'),
    customClass: 'confirm-no_icon',
    autofocus: false,
    callback: (value: string) => {
      if (value === 'confirm') {
        modelApi.delete(item.id).then(() => {
          ElMessage({
            type: 'success',
            message: t('dashboard.delete_success'),
          })
          search()
        })
      }
    },
  })
}

const AuthorizedWorkspaceDialogForModelRef = ref()
const AuthorizedWorkspaceDrawRef = ref()

const handleAuthorizedSpace = (item: any) => {
  AuthorizedWorkspaceDialogForModelRef.value.open(item.id)
}
const handleEditWorkspaceList = (item: any) => {
  AuthorizedWorkspaceDrawRef.value.open(item.id)
}

const clickModel = (ele: any) => {
  activeStep.value = 1
  supplierChang(ele)
}

const supplierChang = (ele: any) => {
  activeName.value = ele.name
  activeNameI18nKey.value = ele.i18nKey
  nextTick(() => {
    modelFormRef.value.supplierChang({ ...ele })
  })
}

const cancel = () => {
  beforeClose()
}

const preStep = () => {
  activeStep.value = 0
}

const saveModel = () => {
  modelFormRef.value.submitModel()
}
const setCardRef = (el: any, index: number) => {
  if (el) {
    cardRefs.value[index] = el
  }
}
const search = () => {
  searchLoading.value = true
  modelApi
    .queryAll()
    .then((res: any) => {
      modelList.value = res
    })
    .finally(() => {
      searchLoading.value = false
    })
}

// 列表内「启用」开关：切换后回写本地列表，失败提示由后端校验抛出
const handleEnableChange = async (payload: any) => {
  const id = Number(payload.id)
  // 列表 id 可能是字符串（接口返回），统一转字符串比较，否则找不到 item 导致本地状态不回写、开关回弹
  const item = modelList.value.find((m: any) => String(m.id) === String(payload.id))
  // 启动（启用）必须经连通校验驱动：手动打开开关直接拦截，避免「未校验即可用」
  if (payload.enable === true) {
    ElMessage.warning(t('model.enable_requires_validate'))
    return
  }
  // 默认模型不可停用：前端直接拦截，不请求后端
  if (item?.default_model && payload.enable === false) {
    ElMessage.warning(t('model.disable_default_error'))
    return
  }
  try {
    await modelApi.enable(id, payload.enable)
    // modelList 是 shallowRef，直接改 item.enable 不会触发重渲染；用新数组替换该元素以刷新开关状态
    const idx = modelList.value.findIndex((m: any) => String(m.id) === String(id))
    if (idx >= 0) {
      modelList.value = modelList.value.map((m: any, i: number) =>
        i === idx ? { ...m, enable: payload.enable } : m)
    }
    ElMessage.success(payload.enable ? t('modelCfg.enable_on') : t('modelCfg.enable_off'))
  } catch (e: any) {
    ElMessage.error(e?.message || String(e))
  }
}

// 列表内「校验」：拉取模型完整配置后发起连通校验（与表单内校验同一后端），回写 enable 并刷新列表
const handleValidate = async (payload: any) => {
  const id = Number(payload.id)
  try {
    const detail: any = await modelApi.query(id)
    const response: any = await modelApi.check({
      id,
      api_domain: detail.api_domain,
      api_key: detail.api_key,
      upstream_format: detail.upstream_format,
      supplier: detail.supplier,
      model_type: detail.model_type,
      base_model: detail.base_model,
      protocol: detail.protocol,
      config_list: detail.config_list || [],
    })
    const reader = response.body.getReader()
    const decoder = new TextDecoder()
    let checkMsg = ''
    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      const lines = decoder.decode(value).trim().split('\n')
      for (const line of lines) {
        if (!line) continue
        const data = JSON.parse(line)
        if (data.error) checkMsg += data.error
        else if (data.content) console.debug(data.content)
      }
    }
    if (checkMsg) {
      ElMessage.error(checkMsg)
    } else {
      ElMessage.success(t('modelCfg.test_success'))
    }
    await search()
  } catch (e: any) {
    ElMessage.error(String(e?.message || e))
  }
}

// 13.5/13.6 向量索引待重建提示与一键重建
const embeddingState = ref<any>({})
const embeddingRebuilding = ref(false)
let embeddingTimer: any = null

const loadEmbeddingStatus = () => {
  modelApi
    .embeddingStatus()
    .then((res: any) => {
      embeddingState.value = res || {}
      if (embeddingState.value.running) {
        if (!embeddingTimer) embeddingTimer = setInterval(loadEmbeddingStatus, 2000)
      } else if (embeddingTimer) {
        clearInterval(embeddingTimer)
        embeddingTimer = null
      }
    })
    .catch(() => {})
}

const startRebuild = () => {
  embeddingRebuilding.value = true
  modelApi
    .rebuildEmbedding()
    .then((res: any) => {
      if (res && res.started === false) {
        ElMessage.warning(t('modelCfg.fetch_failed'))
        return
      }
      ElMessage.success(t('modelCfg.rebuild_running'))
      if (!embeddingTimer) embeddingTimer = setInterval(loadEmbeddingStatus, 2000)
    })
    .catch(() => {})
    .finally(() => {
      embeddingRebuilding.value = false
    })
}

const embeddingProgress = computed(() => {
  const total = embeddingState.value.total || 0
  const done = embeddingState.value.done || 0
  return total > 0 ? Math.round((done / total) * 100) : 0
})

search()
loadEmbeddingStatus()

onUnmounted(() => {
  if (embeddingTimer) {
    clearInterval(embeddingTimer)
    embeddingTimer = null
  }
})


const submit = (item: any) => {
  duplicateName(item)
}
</script>

<template>
  <div class="model-config no-padding">
    <!-- 13.5/13.6 向量索引待重建提示条 + 一键重建 + 进度 -->
    <div v-if="embeddingState.pending" class="embedding-banner">
      <div class="banner-row">
        <span class="banner-title">{{ t('modelCfg.rebuild_title') }}</span>
        <span v-if="embeddingState.running" class="banner-status">
          {{ embeddingState.done }}/{{ embeddingState.total }}
          <span v-if="embeddingState.current_scope"> · {{ embeddingState.current_scope }}</span>
        </span>
        <span v-else-if="embeddingState.failed > 0" class="banner-status failed">
          {{ t('modelCfg.rebuild_failed') }}
        </span>
        <el-button size="small" type="primary" :loading="embeddingRebuilding" @click="startRebuild">
          {{ t('modelCfg.rebuild_action') }}
        </el-button>
      </div>
      <el-progress
        v-if="embeddingState.running"
        :percentage="embeddingProgress"
        :stroke-width="6"
        class="banner-progress"
      />
      <div v-else-if="embeddingState.failed > 0" class="banner-retry">
        <el-button size="small" link type="primary" :loading="embeddingRebuilding" @click="startRebuild">
          {{ t('modelCfg.retry') }}
        </el-button>
      </div>
    </div>
    <div class="model-methods">
      <span class="title">{{ t('model.ai_model_configuration') }}</span>
      <div class="button-input">
        <el-input
          v-model="keywords"
          clearable
          style="width: 240px; margin-right: 12px"
          :placeholder="$t('datasource.search')"
        >
          <template #prefix>
            <el-icon>
              <icon_searchOutline_outlined class="svg-icon" />
            </el-icon>
          </template>
        </el-input>

        <el-popover popper-class="system-default_model" placement="bottom-end">
          <template #reference>
            <el-button secondary>
              <template #icon>
                <icon_admin_outlined></icon_admin_outlined>
              </template>
              {{ t('model.system_default_model_de') }}
            </el-button></template
          >
          <div class="popover">
            <el-input
              v-model="defaultModelKeywords"
              clearable
              style="width: 100%; margin-right: 12px"
              :placeholder="t('datasource.search_by_name')"
            >
              <template #prefix>
                <el-icon>
                  <icon_searchOutline_outlined class="svg-icon" />
                </el-icon>
              </template>
            </el-input>
            <div class="popover-content">
              <div
                v-for="ele in defaultModelListWithSearch"
                :key="ele.name"
                class="popover-item"
                :class="ele.default_model && 'isActive'"
                @click="handleDefaultModelChange(ele)"
              >
                <img :src="ele.supplier_item?.icon" width="24px" height="24px" />
                <div class="model-name ellipsis" v-html="formatKeywords(ele.name)"></div>
                <el-icon size="16" class="done">
                  <icon_done_outlined></icon_done_outlined>
                </el-icon>
              </div>
              <div v-if="!defaultModelListWithSearch.length" class="popover-item empty">
                {{ t('model.relevant_results_found') }}
              </div>
            </div>
          </div>
        </el-popover>

        <el-button type="primary" @click="handleAddModel">
          <template #icon>
            <icon_add_outlined></icon_add_outlined>
          </template>
          {{ t('model.add_model') }}
        </el-button>
      </div>
    </div>
    <EmptyBackground
      v-if="!!keywords && !modelListWithSearch.length"
      :description="$t('datasource.relevant_content_found')"
      img-type="tree"
    />
    <div v-else class="card-content">
      <el-row :gutter="16" class="w-full">
        <el-col
          v-for="(ele, index) in modelListWithSearch"
          :key="ele.id"
          :xs="24"
          :sm="12"
          :md="12"
          :lg="8"
          :xl="6"
          class="mb-20"
        >
          <card
            :id="ele.id"
            :ref="(el: any) => setCardRef(el, index)"
            :key="ele.id"
            :name="ele.name"
            :num="ele.ws_mapping_count"
            :supplier="ele.supplier"
            :model-type="getModelTypeName(ele['model_type'])"
            :base-model="ele['base_model']"
            :is-default="ele['default_model']"
            :enable="ele['enable']"
            :model-type-raw="Number(ele['model_type'])"
            @edit="handleEditModel(ele)"
            @del="deleteHandler"
            @default="handleDefault(ele)"
            @authorized-space="handleAuthorizedSpace(ele)"
            @edit-workspace-list="handleEditWorkspaceList(ele)"
            @enable-change="handleEnableChange"
            @validate="handleValidate"
          ></card>
        </el-col>
      </el-row>
    </div>
    <template v-if="!keywords && !modelListWithSearch.length && !searchLoading">
      <EmptyBackground
        class="datasource-yet"
        :description="$t('common.no_model_yet')"
        img-type="noneWhite"
      />

      <div style="text-align: center; margin-top: -10px">
        <el-button type="primary" @click="handleAddModel">
          <template #icon>
            <icon_add_outlined></icon_add_outlined>
          </template>
          {{ t('model.add_model') }}
        </el-button>
      </div>
    </template>
    <el-drawer
      v-model="modelConfigvVisible"
      :close-on-click-modal="false"
      size="calc(100% - 100px)"
      modal-class="model-drawer-fullscreen"
      direction="btt"
      destroy-on-close
      :before-close="beforeClose"
      :show-close="false"
    >
      <template #header="{ close }">
        <span style="white-space: nowrap">{{
          editModel
            ? $t('dashboard.edit') +
              $t('common.empty') +
              (activeNameI18nKey ? $t(activeNameI18nKey) : activeName)
            : t('model.add_model')
        }}</span>
        <div v-if="!editModel" class="flex-center" style="width: 100%">
          <el-steps custom style="max-width: 500px; flex: 1" :active="activeStep" align-center>
            <el-step>
              <template #title> {{ t('model.select_supplier') }} </template>
            </el-step>
            <el-step>
              <template #title> {{ t('model.add_model') }} </template>
            </el-step>
          </el-steps>
        </div>
        <el-icon class="ed-dialog__headerbtn mrt" style="cursor: pointer" @click="close">
          <icon_close_outlined></icon_close_outlined>
        </el-icon>
      </template>
      <ModelList v-if="activeStep === 0" @click-model="clickModel"></ModelList>
      <ModelListSide
        v-if="activeStep === 1 && !editModel"
        :active-name="activeName"
        :active-type="activeType"
        @click-model="supplierChang"
      ></ModelListSide>
      <ModelForm
        v-if="activeStep === 1 && modelConfigvVisible"
        ref="modelFormRef"
        :active-name="activeName"
        :active-type="activeType"
        :edit-model="editModel"
        @submit="submit"
      ></ModelForm>
      <template v-if="activeStep !== 0" #footer>
        <el-button secondary @click="cancel"> {{ $t('common.cancel') }} </el-button>
        <el-button :loading="modelFormRef?.testing" @click="modelFormRef?.testConnection?.()">
          {{ $t('modelCfg.validate_connection') }}
        </el-button>
        <el-button v-if="!editModel" secondary @click="preStep">
          {{ $t('ds.previous') }}
        </el-button>
        <el-button type="primary" @click="saveModel"> {{ $t('common.save') }} </el-button>
      </template>
    </el-drawer>
  </div>
  <AuthorizedWorkspaceDialogForModel ref="AuthorizedWorkspaceDialogForModelRef" @refresh="search" />
  <AuthorizedWorkspaceDraw ref="AuthorizedWorkspaceDrawRef" @refresh="search" />
</template>

<style lang="less" scoped>
.model-config {
  height: calc(100% - 16px);
  padding: 16px 0 16px 0;

  .datasource-yet {
    padding-bottom: 0;
    height: auto;
    padding-top: 200px;
  }
  .model-methods {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-bottom: 16px;
    padding: 0 24px 0 24px;
    .title {
      font-weight: 500;
      font-size: 20px;
      line-height: 28px;
    }
  }

  .card-content {
    max-height: calc(100% - 40px);
    overflow-y: auto;
    padding: 0 8px 0 24px;

    .w-full {
      width: 100%;
    }

    .mb-20 {
      margin-bottom: 20px;
    }
  }
}
</style>

<style lang="less">
.system-default_model.system-default_model {
  padding: 4px 0;
  width: 325px !important;
  box-shadow: 0px 4px 8px 0px #1f23291a;
  border: 1px solid #dee0e3;
  .ed-input {
    .ed-input__wrapper {
      box-shadow: none;
    }

    border-bottom: 1px solid #1f232926;
  }

  .popover {
    .popover-content {
      padding: 4px;
      max-height: 300px;
      overflow-y: auto;
    }
    .popover-item {
      height: 32px;
      display: flex;
      align-items: center;
      padding-left: 12px;
      padding-right: 8px;
      margin-bottom: 2px;
      position: relative;
      border-radius: 6px;
      cursor: pointer;
      &:not(.empty):hover {
        background: #1f23291a;
      }

      &.empty {
        font-weight: 400;
        font-size: 14px;
        line-height: 22px;
        color: #8f959e;
        cursor: default;
      }

      .model-name {
        margin-left: 8px;
        font-weight: 400;
        font-size: 14px;
        line-height: 22px;
        max-width: 220px;
      }

      .done {
        margin-left: auto;
        display: none;
      }

      .isSearch {
        color: var(--ed-color-primary);
      }

      &.isActive {
        color: var(--ed-color-primary);

        .done {
          display: block;
        }
      }
    }
  }
}

.model-drawer-fullscreen {
  .ed-drawer__body {
    padding: 0;
  }
  .is-process .ed-step__line {
    background-color: var(--ed-color-primary);
  }
}
.confirm-no_icon {
  border-radius: 12px;
  padding: 24px;
  .tip {
    margin-top: 24px;
  }
}
.embedding-banner {
  margin: 16px 16px 0 16px;
  padding: 12px 16px;
  border: 1px solid #f5d99a;
  background: #fff8ec;
  border-radius: 8px;
  .banner-row {
    display: flex;
    align-items: center;
    gap: 12px;
  }
  .banner-title {
    font-weight: 600;
    font-size: 14px;
  }
  .banner-status {
    color: #8f959e;
    font-size: 13px;
    &.failed {
      color: var(--ed-color-danger);
    }
  }
  .banner-progress {
    margin-top: 8px;
  }
  .banner-retry {
    margin-top: 4px;
  }
}
</style>
