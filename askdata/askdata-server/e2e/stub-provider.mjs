import http from 'node:http'

/**
 * 本地 stub provider：不依赖真实 Key，用于验证上游格式分支与模型列表拉取。
 * 监听 127.0.0.1 随机端口，用例结束即关闭。
 *
 * 行为由内存中的 mode 控制（默认 'ok'），可用控制接口切换：
 *   POST /__set_mode  body: { mode: 'ok' | 'unauthorized' | 'notfound' | 'empty' }
 * 各上游端点据此返回对应状态：
 *   GET  /v1/models            -> 200 固定清单 / 401 / 404 / 空列表
 *   POST /v1/chat/completions  -> 200 SSE 固定帧序列 / 401
 *   POST /v1/embeddings        -> 200 固定维度向量 / 404（模拟非向量端点）
 *   POST /v1/messages          -> 200 固定报文（Anthropic Messages）
 *   POST /v1/responses         -> 200 固定报文（Responses）
 * 命中记录：
 *   GET  /__hits               -> { hits: string[] }（仅记录 /v1/ 业务路径）
 *   POST /__reset_hits         -> 清空命中记录
 */

const MODEL = 'stub-llm-model'
const DIM = 1536
const FIXED_EMBEDDING = Array.from({ length: DIM }, (_, i) => (i % 2 ? 0.1 : -0.1))

const SSE_FRAMES = [
  'data: {"choices":[{"delta":{"content":"hello"}}]}\n\n',
  'data: {"choices":[{"delta":{"content":" world"}}]}\n\n',
  'data: [DONE]\n\n',
].join('')

// Anthropic Messages 需返回 SSE：content_block_delta 携带 delta.text 才会被后端识别为有效（见 ChatModelClient.readAnthropicSse）。
const ANTHROPIC_SSE = [
  'data: {"type":"message_start","message":{"usage":{"input_tokens":5}}}\n\n',
  'data: {"type":"content_block_delta","delta":{"text":"hello from anthropic"}}\n\n',
  'data: {"type":"message_delta","usage":{"output_tokens":3}}\n\n',
  'data: [DONE]\n\n',
].join('')

// OpenAI Responses 需返回 SSE：response.output_text.delta 携带 delta 文本才被识别（见 ChatModelClient.readResponsesSse）。
const RESPONSES_SSE = [
  'data: {"type":"response.output_text.delta","delta":"hello from responses"}\n\n',
  'data: {"type":"response.completed","response":{"usage":{"input_tokens":5,"output_tokens":3}}}\n\n',
  'data: [DONE]\n\n',
].join('')

let mode = 'ok'
const hits = []

function json(res, status, body) {
  const data = typeof body === 'string' ? body : JSON.stringify(body)
  res.writeHead(status, { 'Content-Type': 'application/json' })
  res.end(data)
}

function handle(req, res) {
  const url = new URL(req.url, 'http://localhost')
  const path = url.pathname
  if (req.method === 'POST' && path === '/__set_mode') {
    let buf = ''
    req.on('data', (c) => (buf += c))
    req.on('end', () => {
      try {
        const body = JSON.parse(buf || '{}')
        if (body.mode) mode = body.mode
      } catch {}
      json(res, 200, { ok: true, mode })
    })
    return
  }
  if (path === '/__hits') {
    return json(res, 200, { hits: [...hits] })
  }
  if (req.method === 'POST' && path === '/__reset_hits') {
    hits.length = 0
    return json(res, 200, { ok: true })
  }

  // 记录所有业务路径命中，供用例断言上游格式分支
  if (path.startsWith('/v1/')) hits.push(path)

  if (path === '/v1/models') {
    if (mode === 'unauthorized') return json(res, 401, { error: 'unauthorized' })
    if (mode === 'notfound') return json(res, 404, { error: 'not found' })
    if (mode === 'empty') return json(res, 200, { data: [] })
    return json(res, 200, { data: [{ id: MODEL, name: MODEL }] })
  }
  if (path === '/v1/chat/completions') {
    if (mode === 'unauthorized') return json(res, 401, { error: 'unauthorized' })
    res.writeHead(200, { 'Content-Type': 'text/event-stream' })
    res.end(SSE_FRAMES)
    return
  }
  if (path === '/v1/embeddings') {
    if (mode === 'notfound') return json(res, 404, { error: 'not found' })
    return json(res, 200, { data: [{ embedding: FIXED_EMBEDDING }], model: MODEL })
  }
  if (path === '/v1/messages') {
    res.writeHead(200, { 'Content-Type': 'text/event-stream' })
    res.end(ANTHROPIC_SSE)
    return
  }
  if (path === '/v1/responses') {
    res.writeHead(200, { 'Content-Type': 'text/event-stream' })
    res.end(RESPONSES_SSE)
    return
  }
  json(res, 404, { error: 'unknown path ' + path })
}

export function startStub() {
  return new Promise((resolve) => {
    const server = http.createServer(handle)
    server.listen(0, '127.0.0.1', () => {
      const { port } = server.address()
      resolve({
        url: `http://127.0.0.1:${port}`,
        port,
        async setMode(m) {
          await fetch(`http://127.0.0.1:${port}/__set_mode`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ mode: m }),
          })
        },
        async resetHits() {
          await fetch(`http://127.0.0.1:${port}/__reset_hits`, { method: 'POST' })
        },
        async hits() {
          const r = await fetch(`http://127.0.0.1:${port}/__hits`)
          const j = await r.json()
          return j.hits || []
        },
        close() {
          server.close()
        },
      })
    })
  })
}

export const STUB_MODEL = MODEL
