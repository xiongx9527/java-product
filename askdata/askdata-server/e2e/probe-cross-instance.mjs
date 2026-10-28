// 验收 A5 / A11 / A13 的跨实例探针（不依赖粘性会话）。
//
// 用法：node e2e/probe-cross-instance.mjs
//   BASE_A / BASE_B 默认 18099 / 18100；两个实例共享 MySQL + Redis + MinIO。
//
// 覆盖：
//   A13：在 A 建立 /mcp/sse 流，把 initialize / notifications/initialized / tools/call
//        全部 POST 到 B（MCP 的 messages 端点），断言响应仍经 A 的 SSE 流返回。
//   A11：同一 token 并发向 A、B 提交同一任务执行，断言恰好一个被防抖拦截（429）。
//   A5 ：在 B 按展示名下载术语导入失败明细（文件由 A/旧实例生成，B 本机无文件）。
import { createCipheriv } from 'crypto';

const A = process.env.BASE_A || 'http://127.0.0.1:18099';
const B = process.env.BASE_B || 'http://127.0.0.1:18100';
const ADMIN = process.env.ADMIN_USER || 'admin';
const ADMIN_PWD = process.env.ADMIN_PASSWORD || 'SQLBot@123456';
const A5_FILE = process.env.A5_FILE || 'verifier_terms_2026_22e7c936-f_error.xlsx';

let failures = 0;
const log = (...x) => console.log(...x);
function check(ok, name, detail) {
  log((ok ? 'PASS ' : 'FAIL ') + name + (detail ? ' :: ' + detail : ''));
  if (!ok) failures++;
}

function enc(plain) {
  const c = createCipheriv('aes-128-ecb', Buffer.from('SQLBot1234567890', 'utf8'), null);
  return Buffer.concat([c.update(Buffer.from(plain, 'utf8')), c.final()]).toString('base64');
}

async function call(base, method, path, opts) {
  opts = opts || {};
  const headers = { 'Content-Type': 'application/json' };
  if (opts.token) headers['X-SQLBOT-TOKEN'] = 'Bearer ' + opts.token;
  let url = base + path;
  if (opts.query) url += (url.indexOf('?') >= 0 ? '&' : '?') + new URLSearchParams(opts.query).toString();
  const r = await fetch(url, {
    method,
    headers,
    body: opts.body !== undefined ? JSON.stringify(opts.body) : undefined,
  });
  const text = await r.text();
  let data = null;
  try { data = text ? JSON.parse(text) : null; } catch (e) { data = text; }
  return { status: r.status, data, text };
}

/** 极简 SSE 客户端：按帧累积，支持等待满足条件的帧。 */
class SseClient {
  constructor(response) {
    this.frames = [];
    this.pending = [];
    this.buffer = '';
    this.decoder = new TextDecoder();
    this.reader = response.body.getReader();
    this.readLoop();
  }

  async readLoop() {
    try {
      for (;;) {
        const { done, value } = await this.reader.read();
        if (done) break;
        this.buffer += this.decoder.decode(value, { stream: true });
        let idx;
        while ((idx = this.buffer.indexOf('\n\n')) >= 0) {
          const raw = this.buffer.slice(0, idx);
          this.buffer = this.buffer.slice(idx + 2);
          const frame = { event: 'message', data: '' };
          for (const line of raw.split('\n')) {
            if (line.startsWith(':')) continue;
            if (line.startsWith('event:')) frame.event = line.slice(6).trim();
            else if (line.startsWith('data:')) frame.data += (frame.data ? '\n' : '') + line.slice(5).replace(/^ /, '');
          }
          if (!frame.data && frame.event === 'message') continue;
          this.frames.push(frame);
          this.dispatch(frame);
        }
      }
    } catch (e) {
      // 流被 abort/关闭属正常退出
    }
  }

  dispatch(frame) {
    for (const entry of [...this.pending]) {
      if (entry.predicate(frame)) {
        clearTimeout(entry.timer);
        this.pending.splice(this.pending.indexOf(entry), 1);
        entry.resolve(frame);
      }
    }
  }

  waitFor(predicate, label, timeoutMs) {
    const existing = this.frames.find(predicate);
    if (existing) return Promise.resolve(existing);
    return new Promise((resolve, reject) => {
      const entry = { predicate, resolve };
      entry.timer = setTimeout(() => {
        const i = this.pending.indexOf(entry);
        if (i >= 0) this.pending.splice(i, 1);
        reject(new Error('等待 ' + label + ' 超时；已收到帧: ' + JSON.stringify(this.frames).slice(0, 800)));
      }, timeoutMs || 15000);
      this.pending.push(entry);
    });
  }

  close() {
    try { this.reader.cancel(); } catch (e) { /* ignore */ }
  }
}

function jsonOf(frame) {
  try { return JSON.parse(frame.data); } catch (e) { return null; }
}

async function openSse(base, token) {
  const res = await fetch(base + '/mcp/sse', {
    // MCP 的 sa-token-login 直接读 token-name 头的原值（不剥 Bearer 前缀），故这里传裸 token
    headers: { 'X-SQLBOT-TOKEN': token, Accept: 'text/event-stream' },
  });
  return { res, client: res.body ? new SseClient(res) : null };
}

async function postMessage(base, token, path, payload) {
  const r = await fetch(base + path, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'X-SQLBOT-TOKEN': token,
    },
    body: JSON.stringify(payload),
  });
  const text = await r.text();
  return { status: r.status, text };
}

async function main() {
  const login = await call(A, 'POST', '/api/v1/login/access-token', {
    query: { username: enc(ADMIN), password: enc(ADMIN_PWD) },
  });
  const token = login.data && login.data.data ? login.data.data.access_token : null;
  check(!!token, 'A 登录', 'status=' + login.status);
  if (!token) process.exit(2);

  // ---------- A13：SSE 在 A，messages 全打到 B ----------
  let sse = null;
  try {
    const opened = await openSse(A, token);
    sse = opened.client;
    check(opened.res.status === 200 && !!sse, 'A 建立 /mcp/sse 流', 'status=' + opened.res.status);
    if (!sse) throw new Error('no SSE body');

    const endpointFrame = await sse.waitFor(
      (f) => f.data.startsWith('/mcp/messages'), 'endpoint 事件', 10000);
    const endpoint = endpointFrame.data.trim();
    log('  endpoint=' + endpoint);

    // initialize 发到 B
    const init = await postMessage(B, token, endpoint, {
      jsonrpc: '2.0', id: 1, method: 'initialize',
      params: {
        protocolVersion: '2024-11-05',
        capabilities: {},
        clientInfo: { name: 'cross-instance-probe', version: '1.0.0' },
      },
    });
    check(init.status === 200 || init.status === 202, 'initialize POST 到非持有实例 B 被接受',
      'status=' + init.status + ' ' + String(init.text).slice(0, 160));
    const initFrame = await sse.waitFor((f) => {
      const j = jsonOf(f); return j && j.id === 1;
    }, 'initialize 响应', 15000);
    const initJson = jsonOf(initFrame);
    check(!!(initJson && initJson.result && initJson.result.serverInfo),
      'initialize 响应经 A 的 SSE 流返回', JSON.stringify(initJson).slice(0, 200));

    // initialized 通知（无 id）发到 B
    const initialized = await postMessage(B, token, endpoint, {
      jsonrpc: '2.0', method: 'notifications/initialized',
    });
    check(initialized.status === 200 || initialized.status === 202,
      'notifications/initialized POST 到 B 被接受', 'status=' + initialized.status);

    // tools/list 发到 B
    const list = await postMessage(B, token, endpoint, {
      jsonrpc: '2.0', id: 2, method: 'tools/list',
    });
    check(list.status === 200 || list.status === 202, 'tools/list POST 到 B 被接受', 'status=' + list.status);
    const listJson = jsonOf(await sse.waitFor((f) => {
      const j = jsonOf(f); return j && j.id === 2;
    }, 'tools/list 响应', 15000));
    const tools = listJson && listJson.result && listJson.result.tools;
    check(Array.isArray(tools) && tools.length > 0, 'tools/list 经 A 返回工具清单',
      'tools=' + (Array.isArray(tools) ? tools.length : 0));

    // tools/call（server_info，无参数）发到 B
    const callRes = await postMessage(B, token, endpoint, {
      jsonrpc: '2.0', id: 3, method: 'tools/call',
      params: { name: 'server_info', arguments: {} },
    });
    check(callRes.status === 200 || callRes.status === 202, 'tools/call POST 到 B 被接受',
      'status=' + callRes.status);
    const callJson = jsonOf(await sse.waitFor((f) => {
      const j = jsonOf(f); return j && j.id === 3;
    }, 'tools/call 响应', 20000));
    check(!!(callJson && callJson.result && !callJson.error), 'tools/call 经 A 返回结果',
      JSON.stringify(callJson).slice(0, 240));
  } finally {
    if (sse) sse.close();
  }

  // ---------- A13 反向：SSE 在 B，initialize POST 到 A ----------
  let reverseSse = null;
  try {
    const opened = await openSse(B, token);
    reverseSse = opened.client;
    check(opened.res.status === 200 && !!reverseSse, 'B 建立 /mcp/sse 流', 'status=' + opened.res.status);
    if (reverseSse) {
      const endpointFrame = await reverseSse.waitFor(
        (f) => f.data.startsWith('/mcp/messages'), '反向 endpoint 事件', 10000);
      const reverseInit = await postMessage(A, token, endpointFrame.data.trim(), {
        jsonrpc: '2.0', id: 11, method: 'initialize',
        params: {
          protocolVersion: '2024-11-05',
          capabilities: {},
          clientInfo: { name: 'cross-instance-probe-reverse', version: '1.0.0' },
        },
      });
      check(reverseInit.status === 200 || reverseInit.status === 202,
        '反向 initialize POST 到非持有实例 A 被接受', 'status=' + reverseInit.status);
      const reverseFrame = await reverseSse.waitFor((f) => {
        const j = jsonOf(f); return j && j.id === 11;
      }, '反向 initialize 响应', 15000);
      const reverseJson = jsonOf(reverseFrame);
      check(!!(reverseJson && reverseJson.result && reverseJson.result.serverInfo),
        '反向 initialize 响应经 B 的 SSE 流返回', JSON.stringify(reverseJson).slice(0, 160));
    }
  } finally {
    if (reverseSse) reverseSse.close();
  }

  // ---------- A11：同一 token 并发向 A、B 提交同一任务 ----------
  // 每次探测用不同的任务 id，避免上一轮留下的防抖占位（1s 窗口）影响结果。
  const probeTaskId = 900000000 + (Date.now() % 100000000);
  const [runA, runB] = await Promise.all([
    call(A, 'POST', '/api/v1/scheduled_task/run/' + probeTaskId, { token }),
    call(B, 'POST', '/api/v1/scheduled_task/run/' + probeTaskId, { token }),
  ]);
  const statuses = [runA.status, runB.status];
  const blocked = statuses.filter((s) => s === 429).length;
  check(blocked === 1, '防抖跨实例：并发重复提交只执行一次（另一侧 429）',
    'statuses=' + statuses.join(','));

  // ---------- A5：B 按展示名下载失败明细 ----------
  const downloadRes = await fetch(B + '/api/v1/system/download-fail-info', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-SQLBOT-TOKEN': 'Bearer ' + token },
    body: JSON.stringify({ file: A5_FILE }),
  });
  const downloaded = Buffer.from(await downloadRes.arrayBuffer());
  check(downloadRes.status === 200 && downloaded.length > 0,
    'B 按展示名下载失败明细（original_filename 回源）',
    'status=' + downloadRes.status + ' file=' + A5_FILE + ' bytes=' + downloaded.length
    + (downloadRes.status === 200 ? '' : ' body=' + downloaded.toString('utf8').slice(0, 200)));

  if (failures === 0) {
    log('RESULT CROSS_INSTANCE_OK failures=0');
  } else {
    log('RESULT CROSS_INSTANCE_FAIL failures=' + failures);
    process.exit(1);
  }
}

main().catch((e) => {
  log('ERROR', e && e.message ? e.message : e);
  process.exit(1);
});
