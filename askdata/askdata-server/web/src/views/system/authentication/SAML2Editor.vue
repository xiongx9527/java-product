<script lang="ts" setup>
import { ref, reactive } from 'vue'
import { ElMessage, ElLoading } from 'element-plus-secondary'
import { useI18n } from 'vue-i18n'
import type { FormInstance, FormRules } from 'element-plus-secondary'
import { request } from '@/utils/request'
import { getSQLBotAddr } from '@/utils/utils'
const { t } = useI18n()
const dialogVisible = ref(false)
const loadingInstance = ref<ReturnType<typeof ElLoading.service> | null>(null)
const samlForm = ref<FormInstance>()
interface SamlForm {
  idpMetaUrl?: string
  privateKey?: string
  certificate?: string
  wantAuthnRequestsSigned: boolean
  wantAssertionsSigned: boolean
  mapping?: string
}
const state = reactive({
  form: reactive<SamlForm>({
    idpMetaUrl: '',
    privateKey: '',
    certificate: '',
    wantAuthnRequestsSigned: false,
    wantAssertionsSigned: false,
    mapping: '',
  }),
})

/* const spMetaDataUrl = ref('')
spMetaDataUrl.value = getSQLBotAddr() + 'saml/metadata' */

const rule = reactive<FormRules>({
  /* idpUri: [
    {
      required: true,
      message: t('common.require'),
      trigger: 'blur',
    },
    {
      min: 10,
      max: 255,
      message: t('common.input_limit', [10, 255]),
      trigger: 'blur',
    },
    { required: true, validator: validateUrl, trigger: 'blur' },
  ], */
})

const edit = () => {
  showLoading()
  request
    .get('/setting/authentication/info/saml')
    .then((res) => {
      const resData = res as Partial<SamlForm>
      ;(Object.keys(resData) as (keyof SamlForm)[]).forEach((key) => {
        const value = resData[key]
        if (value !== undefined) {
          ;(state.form as any)[key] = value as any
        }
      })
    })
    .finally(() => {
      closeLoading()
    })
  dialogVisible.value = true
}

const emits = defineEmits(['saved'])
const submitForm = async (formEl: FormInstance | undefined) => {
  if (!formEl) return
  await formEl.validate((valid) => {
    if (valid) {
      const param = { ...state.form } as any
      param['spEntityId'] = getSQLBotAddr() + 'saml/metadata'
      param['spAcs'] = getSQLBotAddr() + 'saml/sso'
      const method = request.post('/setting/authentication/save/saml', param)
      showLoading()
      method
        .then((res) => {
          if (!res.msg) {
            ElMessage.success(t('common.save_success'))
            emits('saved')
            reset()
          }
          closeLoading()
        })
        .catch(() => {
          closeLoading()
        })
    }
  })
}

const resetForm = (formEl: FormInstance | undefined) => {
  if (!formEl) return
  formEl.resetFields()
  dialogVisible.value = false
}

const reset = () => {
  resetForm(samlForm.value)
}

const showLoading = () => {
  loadingInstance.value = ElLoading.service({
    target: '.platform-info-drawer',
  })
}
const closeLoading = () => {
  loadingInstance.value?.close()
}

const validate = () => {
  const url = '/setting/authentication/validate/saml2'
  const data = { ...state.form } as any
  data['spEntityId'] = getSQLBotAddr() + 'saml/metadata'
  data['spAcs'] = getSQLBotAddr() + 'saml/sso'
  showLoading()
  request
    .post(url, data)
    .then((res) => {
      if (res?.data === 'true') {
        ElMessage.success(t('ds.test_connection') + t('ds.connection_success'))
      } else {
        ElMessage.error(t('ds.test_connection') + t('ds.connection_failed'))
      }
    })
    .finally(() => {
      closeLoading()
      emits('saved')
    })
}

defineExpose({
  edit,
})
</script>

<template>
  <el-drawer
    v-model="dialogVisible"
    :title="t('authentication.saml.title')"
    modal-class="platform-info-drawer"
    size="600px"
    direction="rtl"
  >
    <el-form
      ref="samlForm"
      require-asterisk-position="right"
      :model="state.form"
      :rules="rule"
      label-width="80px"
      label-position="top"
    >
      <el-form-item :label="t('authentication.saml.idp_meta_url')" prop="idpMetaUrl">
        <el-input v-model="state.form.idpMetaUrl" :placeholder="t('common.please_input')" />
      </el-form-item>

      <el-form-item :label="t('authentication.saml.enable_request_sign')" prop="wantAuthnRequestsSigned">
        <el-switch v-model="state.form.wantAuthnRequestsSigned" />
      </el-form-item>

      <el-form-item :label="t('authentication.saml.enable_assertion_sign')" prop="wantAssertionsSigned">
        <el-switch v-model="state.form.wantAssertionsSigned" />
      </el-form-item>

      <el-form-item
        v-if="state.form.wantAuthnRequestsSigned || state.form.wantAssertionsSigned"
        :label="t('authentication.saml.sp_private_key')"
        prop="privateKey"
      >
        <el-input
          v-model="state.form.privateKey"
          type="password"
          show-password
          :placeholder="t('common.please_input')"
        />
      </el-form-item>

      <el-form-item
        v-if="state.form.wantAuthnRequestsSigned || state.form.wantAssertionsSigned"
        :label="t('authentication.saml.sp_certificate')"
        prop="certificate"
      >
        <el-input
          v-model="state.form.certificate"
          type="password"
          show-password
          :placeholder="t('common.please_input')"
        />
      </el-form-item>

      <el-form-item :label="t('authentication.field_mapping')" prop="mapping">
        <el-input v-model="state.form.mapping" :placeholder="t('authentication.saml2name')" />
      </el-form-item>

      <!-- <el-form-item label="SP MetaData Url">
        <el-input
          v-model="spMetaDataUrl"
          readonly
          :placeholder="t('authentication.saml2name')"
        />
      </el-form-item> -->
    </el-form>
    <template #footer>
      <span class="dialog-footer">
        <el-button secondary @click="resetForm(samlForm)">{{ t('common.cancel') }}</el-button>
        <el-button secondary :disabled="!state.form.idpMetaUrl" @click="validate">
          {{ t('ds.test_connection') }}
        </el-button>
        <el-button type="primary" @click="submitForm(samlForm)">
          {{ t('common.save') }}
        </el-button>
      </span>
    </template>
  </el-drawer>
</template>

<style lang="less">
.platform-info-drawer {
  .ed-drawer__footer {
    height: 64px !important;
    padding: 16px 24px !important;
    .dialog-footer {
      height: 32px;
      line-height: 32px;
    }
  }
  .ed-form-item__label {
    line-height: 22px !important;
    height: 22px !important;
  }
}
</style>
<style lang="less" scoped>
.platform-info-drawer {
  .ed-form-item {
    margin-bottom: 16px;
  }
  .is-error {
    margin-bottom: 40px !important;
  }
  .input-with-select {
    .ed-input-group__prepend {
      width: 72px;
      background-color: #fff;
      padding: 0 20px;
      color: #1f2329;
      text-align: center;
      font-family: var(--de-custom_font, 'PingFang');
      font-size: 14px;
      font-style: normal;
      font-weight: 400;
      line-height: 22px;
    }
  }
}
</style>
