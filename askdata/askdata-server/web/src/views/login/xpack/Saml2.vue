<template>
  <div class="item SAML2" @click="execute">
    <el-icon>
      <Icon name="logo_saml"><logo_saml class="svg-icon" /></Icon>
    </el-icon>
    <span class="name"> SAML2 </span>
  </div>
</template>

<script lang="ts" setup>
import logo_saml from '@/assets/svg/logo_saml.svg'
import { Icon } from '@/components/icon-custom'
import { request } from '@/utils/request'
const emits = defineEmits(['switch-category'])
const execute = () => {
  emits('switch-category', { category: 'saml2', proxy: '/#' })
}
/**
 * 跳转 IdP 登录页。SAML2 是 HTTP-POST 绑定：IdP 认证完会把 SAMLResponse 表单 POST 到
 * 配置里的 ACS（{@code <访问地址>/saml/sso}），由后端换用户后 302 回登录页，
 * 所以这里只负责拿到授权地址并整页跳转。
 */
const toLoginPage = () => {
  request.get('/system/authentication/login/5').then((res: any) => {
    window.location.href = res
  })
}
defineExpose({ toLoginPage })
</script>
<style lang="less" scoped>
.item {
  width: 32px;
  cursor: pointer;
  &:hover {
    filter: drop-shadow(0 4px 8px rgba(0, 0, 0, 0.15));
  }
  &.qrcode,
  &.account {
    .ed-icon {
      padding: 5px;
    }
  }

  .ed-icon {
    font-size: 32px;
    border: 1px solid #dee0e3;
    border-radius: 50%;
    color: var(--ed-color-primary);
  }
  display: flex;
  align-items: center;
  flex-direction: column;
  justify-content: space-between;

  .name {
    margin-top: 8px;
    color: #000;
    text-align: center;
    font-family: var(--ed-color-primary, 'PingFang');
    font-size: 12px;
    font-style: normal;
    font-weight: 400;
    line-height: 20px; /* 166.667% */
    display: none;
  }
}
</style>
