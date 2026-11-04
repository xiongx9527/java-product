import { request } from '@/utils/request'

export const modelApi = {
  queryAll: (keyword?: string) =>
    request.get('/system/aimodel', { params: keyword ? { keyword } : {} }),
  add: (data: any) => {
    const param = data
    if (param.api_key) {
      param.api_key = LicenseGenerator.sqlbotEncrypt(data.api_key)
    }
    if (param.api_domain) {
      param.api_domain = LicenseGenerator.sqlbotEncrypt(data.api_domain)
    }
    return request.post('/system/aimodel', param)
  },
  edit: (data: any) => {
    const param = data
    if (param.api_key) {
      param.api_key = LicenseGenerator.sqlbotEncrypt(data.api_key)
    }
    if (param.api_domain) {
      param.api_domain = LicenseGenerator.sqlbotEncrypt(data.api_domain)
    }
    return request.put('/system/aimodel', param)
  },
  delete: (id: number) => request.delete(`/system/aimodel/${id}`),
  query: (id: number) => request.get(`/system/aimodel/${id}`),
  setDefault: (id: number) => request.put(`/system/aimodel/default/${id}`),
  // 列表内「启用」开关：仅切换 enable
  enable: (id: number, enabled: boolean) =>
    request.put(`/system/aimodel/${id}/enable?enable=${enabled}`),
  check: (data: any) => request.fetchStream('/system/aimodel/status', data),
  platform: (id: number, lazy?: number, pid?: string) =>
    request.post(`/system/platform/org/${id}`, { lazy, pid }),
  userSync: (data: any) => request.post(`/system/platform/user/sync`, data),
  list_by_ws: () => request.get(`/system/aimodel/list/by_ws`),
  // 上游模型列表（服务端代理，返回 {source, models[], error?}）
  models: (data: any) => request.post('/system/aimodel/models', data),
  // 向量索引状态与一键重建
  embeddingStatus: () => request.get('/system/aimodel/embedding/status'),
  rebuildEmbedding: (mode?: string) =>
    request.post('/system/aimodel/embedding/rebuild', mode ? { mode } : {}),
  stopRebuild: () => request.post('/system/aimodel/embedding/rebuild/stop'),
}
