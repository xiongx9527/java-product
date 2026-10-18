/**
 * [askdata-xpack-patch] 闭源 xpack（sqlbot_xpack license-generator.umd.js）的本地替代 stub。
 *
 * 说明（Decisions D1，详见 web/XPackPatchNotes.md）：
 * - 原实现由后端 `/xpack_static/license-generator.umd.js` 加载的全局脚本提供；
 *   本拷贝不引入闭源代码，改用本文件在应用启动时挂载的 stub。
 * - `sqlbotEncrypt` 保持与 SQLBot 前端 CryptoJS 惯例一致（AES-ECB/PKCS7，
 *   key=`SQLBot1234567890`，Base64 输出），Java 侧同 key 解密互通。
 * - `getLicense()` 固定返回社区版（status !== 'valid'），相关页面走免费兜底分支。
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

const LicenseGenerator = {
  // 原 xpack 静态脚本初始化：本地无注入需求
  init: (_base?: string) => Promise.resolve(true),
  // 原付费路由注入：空实现
  generateRouters: (_router?: unknown) => undefined,
  // 社区版许可证信息兜底：所有依赖它判断 valid 的页面按"无效"分支降级
  getLicense: () => ({ status: 'invalid' }),
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
