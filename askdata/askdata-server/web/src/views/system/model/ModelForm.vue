<script lang="ts" setup>
import { ref, reactive, onMounted, nextTick, computed } from 'vue'
import dashboard_info from '@/assets/svg/dashboard-info.svg'
import icon_edit_outlined from '@/assets/svg/icon_edit_outlined.svg'
import icon_delete from '@/assets/svg/icon_delete.svg'
import icon_add_outlined from '@/assets/svg/icon_add_outlined.svg'
import ParamsForm from './ParamsForm.vue'
import { modelTypeOptions } from '@/entity/CommonEntity.ts'
import { get_supplier } from '@/entity/supplier'
import { useI18n } from 'vue-i18n'
import { modelApi } from '@/api/system'

withDefaults(
  defineProps<{
    activeName: string
    editModel: boolean
  }>(),
  {
    activeName: '',
    editModel: false,
  }
)

interface ParamsFormData {
  key?: string
  val?: string
  name?: string
  id?: string
}
const { t } = useI18n()

const modelForm = reactive({
  id: '',
  supplier: 0,
  name: '',
  model_type: 0,
  base_model: '',
  api_key: '',
  api_domain: '',
  config_list: [],
  protocol: 1,
  upstream_format: null, // 未选择（后端按 URL 自动识别）；向量/重排类型不使用时选择器禁用
  full_url: false,
  enable: 0, // 1 生效 / 0 停用
  dimension: null as number | null, // 向量维度
})
const isCreate = ref(false)
const modelRef = ref()
const paramsFormRef = ref()
const advancedSetting = ref([] as ParamsFormData[])
const paramsFormDrawer = ref(false)
let tempConfigMap = new Map<string, Array<any>>()

// 12.12 「连通校验」：对当前填写的接入参数做校验（URL + api_key + model_type + base_model），保存前即可验证。
// 校验按钮已移至父级 Model.vue 底部 footer（取消｜校验｜保存 之间）。
const testing = ref(false)
const testConnection = async () => {
  if (!modelForm.api_domain) {
    ElMessage.warning(t('datasource.please_enter') + t('model.api_domain_name'))
    return
  }
  testing.value = true
  try {
    const response: any = await modelApi.check({
      api_domain: modelForm.api_domain,
      api_key: modelForm.api_key,
      full_url: modelForm.full_url,
      upstream_format: modelForm.upstream_format,
      supplier: modelForm.supplier,
      model_type: modelForm.model_type,
      base_model: modelForm.base_model,
      dimension: modelForm.dimension,
    })
    const reader = response.body.getReader()
    const decoder = new TextDecoder()
    let checkMsg = ''
    let timeout = false
    const timer = setTimeout(() => {
      timeout = true
    }, 9000)
    while (true) {
      if (timeout) break
      const { done, value } = await reader.read()
      if (done) break
      const lines = decoder.decode(value).trim().split('\n')
      for (const line of lines) {
        if (!line) continue
        const data = JSON.parse(line)
        if (data.error) checkMsg += data.error
        else if (data.dimension != null) modelForm.dimension = data.dimension
        else if (data.content) console.debug(data.content)
      }
    }
    clearTimeout(timer)
    if (checkMsg) {
      ElMessage.error(checkMsg)
      return
    }
    ElMessage.success(t('modelCfg.test_success'))
  } catch (e: any) {
    ElMessage.error(String(e?.message || e))
  } finally {
    testing.value = false
  }
}

const modelSelected = computed(() => {
  return !!modelForm.base_model
})
const currentSupplier = computed(() => {
  if (!modelForm.supplier) {
    return null
  }
  return get_supplier(modelForm.supplier)
})

// 12.5 向量/重排类型不使用上游格式（其上游格式选择器已禁用），故类型选择不再受上游格式约束
const typeOptions = computed(() => modelTypeOptions.map((o) => ({ ...o })))

// 12.7 远程拉取的模型清单（基础模型必须是真实拉取到的，禁止回退供应商静态预设）
const pulling = ref(false)
const pullState = ref<'idle' | 'loading' | 'success' | 'failed'>('idle')
const pullSource = ref<'remote' | 'preset' | 'none' | ''>('')
const pullError = ref('')
const remoteModels = ref<Array<{ id: string; name: string }>>([])
const pullSeq = ref(0)

const mergedBaseModels = computed<any[]>(() => {
  // 仅允许选择「已拉取」的模型；未拉取（或上游不可达仅回退预设）时下拉为空，必须先点「拉取模型」
  if (pullSource.value === 'remote' && remoteModels.value.length) {
    return remoteModels.value
  }
  // 编辑已存在模型时保留当前值以便展示，但不提供任何可选项（仍须拉取才能更换）
  if (!isCreate.value && modelForm.base_model) {
    return [{ id: modelForm.base_model, name: modelForm.base_model }]
  }
  return []
})

const pullModels = async () => {
  if (!modelForm.api_domain) {
    ElMessage.warning(t('datasource.please_enter') + t('model.api_domain_name'))
    return
  }
  pulling.value = true
  const seq = ++pullSeq.value
  try {
    const res: any = await modelApi.models({
      api_domain: modelForm.api_domain,
      api_key: modelForm.api_key,
      full_url: modelForm.full_url,
      upstream_format: modelForm.upstream_format,
      supplier: modelForm.supplier,
    })
    // 用请求序号丢弃过期响应（12.7）
    if (seq !== pullSeq.value) return
    pullSource.value = res.source || ''
    pullError.value = res.error || ''
    remoteModels.value = (res.models || []).map((m: any) => ({ id: m.id, name: m.name }))
    if (res.source === 'remote' && remoteModels.value.length) {
      pullState.value = 'success'
      // 唯一结果自动选中（12.7）
      if (remoteModels.value.length === 1 && !modelForm.base_model) {
        modelForm.base_model = remoteModels.value[0].name
      }
      ElMessage.success(t('modelCfg.source_remote') + ' ' + remoteModels.value.map((m: any) => m.name).join('、'))
    } else if (res.source === 'preset') {
      pullState.value = 'failed'
      ElMessage.info(t('modelCfg.source_preset'))
    } else {
      pullState.value = 'failed'
      ElMessage.warning(res.error || t('modelCfg.fetch_failed'))
    }
  } catch (e: any) {
    if (seq !== pullSeq.value) return
    pullState.value = 'failed'
    pullError.value = String(e?.message || e)
    ElMessage.error(pullError.value || t('modelCfg.fetch_failed'))
  } finally {
    if (seq === pullSeq.value) pulling.value = false
  }
}

const handleParamsEdite = (ele?: any) => {
  isCreate.value = false
  paramsFormDrawer.value = true
  nextTick(() => {
    paramsFormRef.value.initForm(ele)
  })
}

const handleParamsCreate = () => {
  isCreate.value = true
  paramsFormDrawer.value = true
  nextTick(() => {
    paramsFormRef.value.initForm()
  })
}

const handleParamsDel = (item: any) => {
  advancedSetting.value = advancedSetting.value.filter((ele) => ele.id !== item.id)
  // 12.11 删除参数后回写缓存，切换模型再切回仍为删除后的状态
  tempConfigMap.set(`${modelForm.supplier}-${modelForm.base_model}`, [...advancedSetting.value])
}
const currentPage = ref(1)
const advancedSettingPagination = computed(() => {
  return advancedSetting.value.slice(currentPage.value * 5 - 5, currentPage.value * 5)
})

const handleCurrentChange = (val: any) => {
  currentPage.value = val
}

const urlValidator = (_rule: any, value: any, callback: any) => {
  if (!value) {
    return callback(new Error(t('datasource.please_enter') + t('model.api_domain_name')))
  }
  if (!/^https?:\/\//i.test(value)) {
    return callback(new Error(t('model.api_domain_http')))
  }
  callback()
}

const rules = computed(() => ({
  model_type: [
    {
      required: true,
      message: t('model.model_type'),
      trigger: 'change',
    },
  ],
  api_domain: [{ validator: urlValidator, trigger: 'blur', required: true }],
  base_model: [{ required: true, message: t('model.the_basic_model_de'), trigger: 'change' }],
  name: [
    { required: true, message: t('model.the_basic_model'), trigger: 'blur' },
    {
      max: 100,
      message: t('model.length_max_error', { msg: t('model.model_name'), max: 100 }),
      trigger: 'blur',
    },
  ],
  api_key: [
    {
      // 供应商不在内置列表里（supplier=0，例如自定义 / 本机 Ollama）时不强制填 Key
      required: !!currentSupplier.value && !currentSupplier.value.is_private,
      message: t('datasource.please_enter') + t('common.empty') + 'API Key',
      trigger: 'blur',
    },
  ],
}))

onMounted(() => {
  setTimeout(() => {
    modelRef.value.clearValidate()
  }, 100)
})

const addParams = () => {
  paramsFormRef.value.submit()
}

const duplicateName = async (item: any) => {
  const arr = advancedSetting.value.filter((ele: any) => ele.id !== item.id)
  const names = arr.map((ele: any) => ele.name)
  const keys = arr.map((ele: any) => ele.key)
  if (names.includes(item.name)) {
    ElMessage.error(t('embedded.duplicate_name'))
    return
  }

  if (keys.includes(item.key)) {
    ElMessage.error(t('embedded.repeating_parameters'))
    return
  }

  if (isCreate.value) {
    advancedSetting.value.push({ ...item, id: +new Date() })
    beforeClose()
    tempConfigMap.set(`${modelForm.supplier}-${modelForm.base_model}`, [...advancedSetting.value])
    return
  }
  for (const key in advancedSetting.value) {
    const element = advancedSetting.value[key]
    if (element.id === item.id) {
      Object.assign(element, { ...item })
    }
  }
  tempConfigMap.set(`${modelForm.supplier}-${modelForm.base_model}`, [...advancedSetting.value])
  beforeClose()
}

const submit = (item: any) => {
  duplicateName(item)
}

const beforeClose = () => {
  paramsFormRef.value.close()
  paramsFormDrawer.value = false
}
const supplierChang = (supplier: any) => {
  modelForm.supplier = supplier.id
  const config = supplier.model_config?.[modelForm.model_type || 0] || {}
  modelForm.api_domain = config.api_domain || ''
  modelForm.base_model = ''
  modelForm.protocol = supplier.type === 'vllm' ? 2 : 1
  advancedSetting.value = []
  // 切换供应商后清空远程拉取结果
  remoteModels.value = []
  pullSource.value = ''
  pullState.value = 'idle'
}
let curId = +new Date()
const initForm = (item?: any) => {
  modelForm.id = ''
  modelRef.value.clearValidate()
  tempConfigMap = new Map<string, Array<any>>()
  // 重置远程拉取状态
  remoteModels.value = []
  pullSource.value = ''
  pullState.value = 'idle'
  pullError.value = ''
  Object.assign(modelForm, {
    id: '',
    supplier: 0,
    name: '',
    model_type: 0,
    base_model: '',
    api_key: '',
    api_domain: '',
    config_list: [],
    protocol: 1,
    upstream_format: null,
    full_url: false,
    enable: 0,
    dimension: null,
  })
  if (item) {
    Object.assign(modelForm, {
      ...item,
      upstream_format: item.upstream_format ? item.upstream_format : null,
      full_url: item.full_url ?? false,
      enable: item.enable ?? 0,
      dimension: item.dimension ?? null,
    })
    if (item?.config_list?.length) {
      advancedSetting.value = item.config_list
      advancedSetting.value.forEach((ele: any) => {
        if (!ele.id) {
          ele.id = curId
          curId += 1
        }
      })
    } else {
      advancedSetting.value = []
    }
    tempConfigMap.set(`${modelForm.supplier}-${modelForm.base_model}`, [...advancedSetting.value])
  }
}
const formatAdvancedSetting = (list: Array<any>) => {
  const setting_list = [
    ...list.map((item) => {
      return { id: ++curId, name: item.name, key: item.key, val: item.val } as any
    }),
  ]
  advancedSetting.value = setting_list
}
const baseModelChange = (val: string) => {
  if (!val || !modelForm.supplier) {
    return
  }
  const current_model = mergedBaseModels.value?.find((model: any) => model.name == val)
  if (current_model) {
    const suggested = current_model.api_domain || getSupplierDomain()
    // 切换基础模型时，仅当用户尚未填写 base_url 才用模型/供应商默认域名自动填充；
    // 远端模型本身不带 api_domain，盲目覆盖会把用户已填的 base_url 清空
    if (suggested && !modelForm.api_domain) {
      modelForm.api_domain = suggested
    }
  }
  const current_config_list = tempConfigMap.get(`${modelForm.supplier}-${modelForm.base_model}`)
  if (current_config_list) {
    formatAdvancedSetting(current_config_list)
    return
  }
  const defaultArgs = getModelDefaultArgs()
  if (defaultArgs?.size) {
    const defaultArgsList = [...defaultArgs.values()]
    formatAdvancedSetting(defaultArgsList)
    tempConfigMap.set(`${modelForm.supplier}-${modelForm.base_model}`, [...advancedSetting.value])
  }
}
const getSupplierDomain = () => {
  return currentSupplier.value?.model_config?.[modelForm.model_type || 0]?.api_domain
}
const getModelDefaultArgs = () => {
  if (!modelForm.supplier || !modelForm.base_model) {
    return null
  }
  const model_config = currentSupplier.value?.model_config?.[modelForm.model_type || 0]
  const common_args = model_config?.common_args || []
  const current_model = mergedBaseModels.value?.find((model: any) => model.name == modelForm.base_model)

  if (current_model?.args?.length) {
    const modelArgs = current_model.args
    common_args.push(...modelArgs)
  }
  const argMap = common_args.reduce((acc: any, item: any) => {
    acc.set(item.key, { ...item, name: item.key })
    return acc
  }, new Map())
  return argMap
}
const emits = defineEmits(['submit'])


const submitModel = () => {
  modelRef.value.validate((res: any) => {
    if (res) {
      emits('submit', {
        ...modelForm,
        config_list: [
          ...advancedSetting.value.map((item) => {
            return { key: item.key, name: item.name, val: item.val }
          }),
        ],
      })
    }
  })
}

defineExpose({
  initForm,
  submitModel,
  supplierChang,
  testConnection,
  testing,
})
</script>

<template>
  <div class="model-form" :class="editModel && 'is-edit_model'">
    <div v-if="!editModel" class="model-name">{{ activeName }}</div>
    <el-scrollbar>
      <div class="form-content">
        <el-form
          ref="modelRef"
          :rules="rules"
          label-position="top"
          :model="modelForm"
          style="width: 100%"
          @submit.prevent
        >
          <!-- 12.2 名称字段不参与任何联动 -->
          <el-form-item class="custom-require flex-inline" prop="name">
            <template #label
              ><span class="custom-require_danger">{{ t('model.model_name') }}</span>
              <el-tooltip effect="dark" :content="t('model.custom_model_name')" placement="right">
                <el-icon style="margin-left: 4px" size="16">
                  <dashboard_info></dashboard_info>
                </el-icon>
              </el-tooltip>
            </template>
            <el-input
              v-model="modelForm.name"
              clearable
              :placeholder="
                $t('datasource.please_enter') + $t('common.empty') + $t('model.model_name')
              "
            />
          </el-form-item>
          <el-form-item prop="api_domain" :label="t('model.api_domain_name')">
            <el-input
              v-model="modelForm.api_domain"
              clearable
              :placeholder="
                $t('datasource.please_enter') + $t('common.empty') + $t('model.api_domain_name')
              "
            />
          </el-form-item>
          <!-- full url -->
          <el-form-item :label="t('modelCfg.full_url')">
            <div class="full-url-row">
              <el-switch v-model="modelForm.full_url" />
              <span class="full-url-tip">{{ t('modelCfg.full_url_tip') }}</span>
            </div>
          </el-form-item>
          <!-- API Key -->
          <el-form-item prop="api_key" label="API Key">
            <el-input
              v-model="modelForm.api_key"
              class="api-key-input"
              clearable
              :placeholder="$t('datasource.please_enter') + $t('common.empty') + 'API Key'"
              type="password"
              show-password
            />
          </el-form-item>
          <!-- 模型类型：新建默认 LLM；编辑态只读；按上游格式置灰向量 -->
          <el-form-item prop="type" :label="t('model.model_type')">
            <el-select
              v-model="modelForm.model_type"
              style="width: 100%"
              :disabled="editModel"
            >
              <el-option
                v-for="item in typeOptions"
                :key="item.value"
                :label="item.i18nKey ? $t(item.i18nKey) : item.label"
                :value="item.value"
                :disabled="item.disabled"
              />
            </el-select>
          </el-form-item>
          <!-- 向量维度（仅向量模型）：位于基础模型之上；校验时比对模型实际输出维度 -->
          <el-form-item v-if="modelForm.model_type === 1" prop="dimension" :label="t('modelCfg.dimension')">
            <el-input v-model="modelForm.dimension" type="number" :placeholder="t('model.dimension_tip')" />
          </el-form-item>
          <!-- 基础模型：支持远程拉取，标注来源 -->
          <el-form-item class="custom-require" prop="base_model">
            <template #label>
              <span class="custom-require_danger">{{ t('model.basic_model') }}</span>
              <span class="enter-label-hint">{{ t('model.enter_to_add') }}</span>
            </template>
            <div class="base-model-row">
              <el-select
                v-model="modelForm['base_model']"
                class="base-model-select"
                filterable
                :reserve-keyword="false"
                :teleported="false"
                popper-class="base-model-dropdown"
                :placeholder="t('model.enter_to_add')"
                @change="baseModelChange"
              >
                <el-option
                  v-for="item in mergedBaseModels"
                  :key="item.name"
                  :label="item.name"
                  :value="item.name"
                />
              </el-select>
              <el-button
                :loading="pulling"
                @click="pullModels"
              >
                {{ t('model.pull_models') }}
              </el-button>
              <el-button :loading="testing" @click="testConnection">
                {{ t('modelCfg.validate_connection') }}
              </el-button>
            </div>
          </el-form-item>
        </el-form>
        <!-- 高级配置：常驻展示，不再折叠（12.6）-->
        <div v-if="modelSelected" class="advance-setting">
          {{ t('model.advanced_settings') }}
        </div>
        <div v-if="modelSelected" class="advanced-body">
          <el-form label-position="top">
          <!-- 上游格式：仅 LLM 展示；向量/重排类型不参与上游格式选择，故隐藏 -->
          <el-form-item v-if="modelForm.model_type === 0" :label="t('modelCfg.format')">
            <el-select
              v-model="modelForm.upstream_format"
              style="width: 100%"
              clearable
              :placeholder="t('modelCfg.format_placeholder')"
            >
              <el-option label="Chat Completions" :value="1" />
              <el-option label="Anthropic Messages" :value="2" />
              <el-option label="Responses" :value="3" />
            </el-select>
          </el-form-item>
          </el-form>
          <!-- 模型参数 -->
          <div class="model-params">
            <span class="params-title">
              {{ t('model.model_parameters') }}
              <span v-if="advancedSetting.length" class="param-count">({{ advancedSetting.length }})</span>
            </span>
            <span class="btn" @click="handleParamsCreate">
              <el-icon size="16">
                <icon_add_outlined></icon_add_outlined>
              </el-icon>
              {{ t('model.add') }}
            </span>
          </div>
          <div
            class="params-table"
            :class="!advancedSettingPagination.length && 'bottom-border'"
          >
            <el-table :data="advancedSettingPagination" style="width: 100%">
              <el-table-column prop="key" :label="t('model.parameters')" width="280" />
              <el-table-column prop="name" :label="t('model.display_name')" width="280" />
              <el-table-column prop="val" show-overflow-tooltip :label="t('model.parameter_value')" />
              <el-table-column
                fixed="right"
                width="80"
                class-name="operation-column_text"
                :label="$t('ds.actions')"
              >
                <template #default="scope">
                  <el-button text type="primary" @click="handleParamsEdite(scope.row)">
                    <el-icon size="16">
                      <icon_edit_outlined></icon_edit_outlined>
                    </el-icon>
                  </el-button>
                  <el-button text type="primary" @click="handleParamsDel(scope.row)">
                    <el-icon size="16">
                      <icon_delete></icon_delete>
                    </el-icon>
                  </el-button>
                </template>
              </el-table-column>
            </el-table>
          </div>
          <div
            v-if="advancedSetting.length > 5"
            class="params-table_pagination"
          >
            <el-pagination
              :default-page-size="5"
              layout="prev, pager, next"
              :total="advancedSetting.length"
              @current-change="handleCurrentChange"
            />
          </div>
        </div>
      </div>
    </el-scrollbar>
    <el-drawer
      v-model="paramsFormDrawer"
      :size="600"
      :before-close="beforeClose"
      :title="
        isCreate
          ? $t('model.add') + $t('common.empty') + $t('model.parameters')
          : $t('datasource.edit') + $t('common.empty') + $t('model.parameters')
      "
    >
      <ParamsForm ref="paramsFormRef" @submit="submit"></ParamsForm>
      <template #footer>
        <el-button secondary @click="beforeClose"> {{ $t('common.cancel') }} </el-button>
        <el-button type="primary" @click="addParams">
          {{ isCreate ? t('model.add') : t('common.save') }}
        </el-button>
      </template>
    </el-drawer>
  </div>
</template>

<style lang="less" scoped>
.model-form {
  width: calc(100% - 280px);
  position: absolute;
  right: 0;
  top: 56px;
  height: calc(100% - 120px);
  .model-name {
    height: 56px;
    width: 100%;
    padding-left: 24px;
    border-bottom: 1px solid #1f232926;
    font-weight: 500;
    font-size: 16px;
    line-height: 24px;
    display: flex;
    align-items: center;
  }
  & > .ed-scrollbar {
      .form-content {
        width: 960px;
        margin: 0 auto;
      padding-top: 24px;
      height: 100%;
      padding-bottom: 80px;

      .ed-form-item--default {
        margin-bottom: 16px;

        &.is-error {
          margin-bottom: 40px;
        }
      }

      :deep(
        .custom-require.ed-form-item.is-required:not(.is-no-asterisk).asterisk-right
          > .ed-form-item__label:after
      ) {
        display: none;
      }

      :deep(.flex-inline .ed-form-item__label) {
        display: inline-flex;
        align-items: center;
      }

      .enter {
        font-weight: 400;
        font-size: 14px;
        line-height: 22px;
        color: #ff8800;
        margin-left: 8px;
      }

      .custom-require_danger::after {
        color: var(--ed-color-danger);
        content: '*';
        margin-left: 2px;
      }

      .base-model-row {
        display: flex;
        align-items: center;
        gap: 8px;
        .base-model-select {
          flex: 1;
          min-width: 0;
        }

        // 下拉面板稍微高些，便于一次看到更多拉取的模型
        :deep(.base-model-dropdown) .el-select-dropdown__wrap {
          max-height: 340px;
        }
      }
      .enter-label-hint {
        margin-left: 8px;
        font-weight: 400;
        font-size: 12px;
        color: #8f959e;
      }
      .base-model-actions {
        display: flex;
        align-items: center;
        gap: 8px;
        margin-top: 8px;
        .pull-error {
          color: var(--ed-color-danger);
          font-size: 12px;
        }
      }

      .advance-setting {
        display: flex;
        align-items: center;
        font-weight: 500;
        font-size: 14px;
        line-height: 22px;
        margin-top: 8px;
      }

      .api-key-row {
        display: flex;
        align-items: center;
        gap: 8px;
        width: 100%;
        .api-key-input {
          flex: 1;
        }
      }

      .advanced-body {
        .ed-form-item {
          margin-bottom: 16px;
        }
        .full-url-row {
          display: flex;
          align-items: center;
          flex-wrap: wrap;
          gap: 8px;
        }
        .full-url-tip {
          color: #8f959e;
          font-size: 12px;
          line-height: 1.4;
        }
        .param-count {
          margin-left: 4px;
          color: #8f959e;
        }
      }

      .model-params {
        display: flex;
        align-items: center;
        justify-content: space-between;
        font-weight: 400;
        font-size: 14px;
        line-height: 22px;
        margin: 16px 0 8px 0;

        .params-title {
          display: inline-flex;
          align-items: baseline;
        }

        .btn {
          height: 26px;
          display: flex;
          align-items: center;
          justify-content: center;
          padding: 0 4px;
          border-radius: 6px;
          cursor: pointer;

          &:hover {
            background-color: #1f23291a;
          }
        }

        .ed-icon {
          margin-right: 4px;
        }
      }

      .params-table {
        border-radius: 6px;
        border: 1px solid #dee0e3;
        border-top: none;
        border-bottom: none;
        overflow-y: auto;

        &.bottom-border {
          border-bottom: 1px solid #dee0e3;
        }
        :deep(.ed-table .ed-table__cell) {
          padding: 7px 0;
        }

        :deep(.ed-table .cell) {
          line-height: 24px;
        }
      }

      .params-table_pagination {
        margin-top: 8px;

        .ed-pagination {
          justify-content: flex-end;
        }

        :deep(.ed-pager li.number:hover) {
          background-color: var(--ed-color-primary-1a, #1cba901a);
        }
      }

      .operation-column_text {
        .ed-button {
          color: #646a73;
          height: 24px;
        }
        .ed-button:not(.is-disabled):hover {
          background: #1f23291a;
        }
        .ed-button + .ed-button {
          margin-left: 8px;
        }
      }
    }
  }

  &.is-edit_model {
    width: 100%;
    .form-content {
      padding-bottom: 24px;
    }
  }
}
</style>
