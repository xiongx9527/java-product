/**
 * [askdata-xpack-patch] 闭源 xpack（sqlbot_xpack license-generator.umd.js）的本地替代 stub。
 *
 * 说明（Decisions D1，详见 web/XPackPatchNotes.md）：
 * - 原实现由后端 `/xpack_static/license-generator.umd.js` 加载的全局脚本提供；
 *   本拷贝不引入闭源代码，改用本文件在应用启动时挂载的 stub。
 * - `sqlbotEncrypt` 保持与 SQLBot 前端 CryptoJS 惯例一致（AES-ECB/PKCS7，
 *   key=`SQLBot1234567890`，Base64 输出），Java 侧同 key 解密互通。
 * - `getLicense()` 返回启动时从后端 `GET /system/license` 拉取并缓存的真实状态
 *   （A29；未上传/请求失败时为社区版 `status: 'invalid'`，相关页面走免费兜底分支），
 *   `refreshLicense()` 由 `main.ts` 在挂载前调用一次。
 * - `generateRouters()` / `init()` 为空实现，不注入付费路由与远程配置。
 */
import CryptoJS from 'crypto-js'

const SQLBOT_AES_KEY = CryptoJS.enc.Utf8.parse('SQLBot1234567890') // [askdata-xpack-patch] 与数据源密码前端惯例同 key

export const sqlbotEncrypt = (str: string) => {
  return CryptoJS.AES.encrypt(String(str), SQLBOT_AES_KEY, {
    mode: CryptoJS.mode.ECB,
    padding: CryptoJS.pad.Pkcs7,
  }).toString()
}

// [askdata-xpack-patch] 解密同 key 用于本地排查/测试页面
export const sqlbotDecrypt = (str: string) => {
  const bytes = CryptoJS.AES.decrypt(String(str), SQLBOT_AES_KEY, {
    mode: CryptoJS.mode.ECB,
    padding: CryptoJS.pad.Pkcs7,
  })
  return bytes.toString(CryptoJS.enc.Utf8)
}

// [askdata-xpack-patch] A29：许可证状态缓存。原 xpack 的 getLicense() 是同步的
// 本地闭源计算；askdata 版改为启动时从后端 GET /system/license 拉取一次并缓存，
// 保持同步读签名不变（所有页面 onMounted 里 LicenseGenerator.getLicense() 立即取到真实状态）。
type LicenseState = { status: string; license?: any; version?: string }
let licenseState: LicenseState = { status: 'invalid' }
let licenseLoading: Promise<LicenseState> | null = null

const refreshLicense = async (): Promise<LicenseState> => {
  if (!licenseLoading) {
    licenseLoading = (async () => {
      try {
        const { licenseApi } = await import('@/api/license')
        const res: any = await licenseApi.validate()
        let version: string | undefined
        try {
          const v: any = await licenseApi.version()
          version = typeof v === 'string' ? v : v?.version
        } catch {
          version = undefined
        }
        licenseState = {
          status: res?.status ?? 'invalid',
          license: res?.license,
          version,
        }
      } catch {
        // 拉取失败一律按社区版处理（与 SQLBot 未授权降级一致）
        licenseState = { status: 'invalid' }
      }
      return licenseState
    })()
  }
  return licenseLoading
}

const getLicense = () => licenseState

const LicenseGenerator = {
  // 原 xpack 静态脚本初始化：本地无注入需求
  init: (_base?: string) => Promise.resolve(true),
  // 原付费路由注入：空实现
  generateRouters: (_router?: unknown) => undefined,
  // A29：返回启动时从 GET /system/license 拉取的真实许可证状态
  getLicense,
  refreshLicense,
  sqlbotEncrypt,
  sqlbotDecrypt,
  // 原 X-SQLBOT-KEY 请求签名（request.ts 中相关调用已注释）
  generate: () => '',
}

// 装到全局，替代原 UMD 脚本的 window.LicenseGenerator
;(globalThis as any).LicenseGenerator = LicenseGenerator
;(globalThis as any).sqlbotEncrypt = sqlbotEncrypt
;(globalThis as any).sqlbotDecrypt = sqlbotDecrypt

export default LicenseGenerator
