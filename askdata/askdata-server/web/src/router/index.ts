import { createRouter, createWebHashHistory } from 'vue-router'
// @ts-expect-error eslint-disable-next-line @typescript-eslint/ban-ts-comment
import Layout from '@/components/layout/index.vue'
import LayoutDsl from '@/components/layout/LayoutDsl.vue'
import SinglePage from '@/components/layout/SinglePage.vue'
import login from '@/views/login/index.vue'
// import Embedded from '@/views/system/embedded/index.vue'
// import SetAssistant from '@/views/system/embedded/iframe.vue'


import { i18n } from '@/i18n'
import { watchRouter } from './watch'

const t = i18n.global.t
export const routes = [
  {
    path: '/login',
    name: 'login',
    component: login,
  },
  {
    path: '/chat',
    component: LayoutDsl,
    redirect: '/chat/index',
    children: [
      {
        path: 'index',
        name: 'chat',
        component: () => import('@/views/chat/index.vue'),
        props: (route: any) => {
          return { startChatDsId: route.query.start_chat }
        },
        meta: { title: t('menu.Data Q&A'), iconActive: 'chat', iconDeActive: 'noChat' },
      },
    ],
  },
  {
    path: '/dsTable',
    component: SinglePage,
    children: [
      {
        path: ':dsId/:dsName',
        name: 'dsTable',
        component: () => import('@/views/ds/TableList.vue'),
        props: true,
      },
    ],
  },
  /* {
    path: '/ds',
    component: LayoutDsl,
    name: 'ds-menu',
    redirect: '/ds/index',
    children: [
      {
        path: 'index',
        name: 'ds',
        component: Datasource,
        meta: { title: t('menu.Data Connections'), iconActive: 'ds', iconDeActive: 'noDs' },
      },
    ],
  }, */
  {
    path: '/dashboard',
    component: LayoutDsl,
    redirect: '/dashboard/index',
    children: [
      {
        path: 'index',
        name: 'dashboard',
        component: () => import('@/views/dashboard/index.vue'),
        meta: {
          title: t('dashboard.dashboard'),
          iconActive: 'dashboard',
          iconDeActive: 'noDashboard',
        },
      },
    ],
  },
  {
    path: '/set',
    name: 'set',
    component: LayoutDsl,
    redirect: '/set/member',
    meta: { title: t('workspace.set'), iconActive: 'set', iconDeActive: 'noSet' },
    children: [
      {
        path: '/set/member',
        name: 'member',
        component: () => import('@/views/system/member/index.vue'),
        meta: { title: t('workspace.member_management') },
      },
      {
        path: '/set/permission',
        name: 'permission',
        component: () => import('@/views/system/permission/index.vue'),
        meta: { title: t('workspace.permission_configuration') },
      },
      /* {
        path: '/set/assistant',
        name: 'setAssistant',
        component: SetAssistant,
        meta: { title: t('embedded.assistant_app') },
      }, */
      {
        path: '/set/professional',
        name: 'professional',
        component: () => import('@/views/system/professional/index.vue'),
        meta: { title: t('professional.professional_terminology') },
      },
      {
        path: '/set/training',
        name: 'training',
        component: () => import('@/views/system/training/index.vue'),
        meta: { title: t('training.data_training') },
      },
      {
        path: '/set/prompt',
        name: 'prompt',
        component: () => import('@/views/system/prompt/index.vue'),
        meta: { title: t('prompt.customize_prompt_words') },
      },
    ],
  },
  {
    path: '/canvas',
    name: 'canvas',
    component: () => import('@/views/dashboard/editor/index.vue'),
    meta: { title: 'canvas', icon: 'dashboard' },
  },
  {
    path: '/dashboard-preview',
    name: 'preview',
    component: () => import('@/views/dashboard/preview/SQPreviewSingle.vue'),
    meta: { title: 'DashboardPreview', icon: 'dashboard' },
  },
  {
    path: '/system',
    name: 'system',
    component: LayoutDsl,
    redirect: '/system/user',
    meta: { hidden: true },
    children: [
      {
        path: 'user',
        name: 'user',
        component: () => import('@/views/system/user/User.vue'),
        meta: { title: t('user.user_management'), iconActive: 'user', iconDeActive: 'noUser' },
      },
      {
        path: 'workspace',
        name: 'workspace',
        component: () => import('@/views/system/workspace/index.vue'),
        meta: {
          title: t('user.workspace'),
          iconActive: 'workspace',
          iconDeActive: 'noWorkspace',
        },
      },
      {
        path: 'model',
        name: 'model',
        component: () => import('@/views/system/model/Model.vue'),
        meta: {
          title: t('model.ai_model_configuration'),
          iconActive: 'model',
          iconDeActive: 'noModel',
        },
      },
      {
        path: 'embedded',
        name: 'embedded',
        component: () => import('@/views/system/embedded/Page.vue'),
        meta: {
          title: t('embedded.embedded_management'),
          iconActive: 'embedded',
          iconDeActive: 'noEmbedded',
        },
      },
      {
        path: 'setting',
        meta: { title: t('system.system_settings'), iconActive: 'set', iconDeActive: 'noSet' },
        redirect: 'system_/appearance',
        name: 'setting',
        children: [
          {
            path: 'appearance',
            name: 'appearance',
            component: () => import('@/views/system/appearance/index.vue'),
            meta: { title: t('system.appearance_settings') },
          },
          {
            path: 'parameter',
            name: 'parameter',
            component: () => import('@/views/system/parameter/index.vue'),
            meta: { title: t('parameter.parameter_configuration') },
          },
          {
            path: 'variables',
            name: 'variables',
            component: () => import('@/views/system/variables/index.vue'),
            meta: { title: t('variables.system_variables') },
          },
          {
            path: 'authentication',
            name: 'authentication',
            component: () => import('@/views/system/authentication/index.vue'),
            meta: { title: t('system.authentication_settings'), enterpriseOnly: true },
          },
          {
            path: 'platform',
            name: 'platform',
            component: () => import('@/views/system/platform/index.vue'),
            meta: { title: t('platform.title'), enterpriseOnly: true },
          },
        ],
      },
      {
        path: 'audit',
        name: 'audit',
        component: () => import('@/views/system/audit/index.vue'),
        meta: { title: t('audit.system_log'), iconActive: 'log', iconDeActive: 'noLog' },
      },
    ],
  },

  {
    path: '/assistant',
    name: 'assistant',
    component: () => import('@/views/embedded/index.vue'),
  },
  {
    path: '/embeddedPage',
    name: 'embeddedPage',
    component: () => import('@/views/embedded/page.vue'),
  },
  {
    path: '/embeddedCommon',
    name: 'embeddedCommon',
    component: () => import('@/views/embedded/common.vue'),
  },
  {
    path: '/assistantTest',
    name: 'assistantTest',
    component: () => import('@/views/system/embedded/Test.vue'),
  },
  {
    path: '/chatPreview',
    name: 'chatPreview',
    component: () => import('@/views/chat/preview.vue'),
  },
  {
    path: '/admin-login',
    name: 'admin-login',
    component: login,
  },
  {
    path: '/401',
    name: '401',
    hidden: true,
    meta: {},
    component: () => import('@/views/error/index.vue'),
  },
]
const router = createRouter({
  history: createWebHashHistory(),
  routes,
})
watchRouter(router)
export default router
