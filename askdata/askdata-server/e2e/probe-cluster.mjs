// 集群双实例探针：验证「任意请求落到任意实例结果一致」。
//
// 用法：node e2e/probe-cluster.mjs
//   BASE_A / BASE_B 分别指向两个实例（默认 18099 / 18100），不需要负载均衡；
//   探针直接交替访问两个实例，比经 LB 轮询更能证明跨实例语义。
//
// 覆盖：会话跨实例、助手文件跨实例、Excel 上传→导入跨实例、定时任务导出端点可用。
// 图表 token 跨实例与任务租约回收由单元测试覆盖（ChartImageClusterTest / ScheduledTaskLeaseTest）。
import { createCipheriv } from 'crypto';

const A = process.env.BASE_A || 'http://127.0.0.1:18099';
const B = process.env.BASE_B || 'http://127.0.0.1:18100';
const ADMIN = process.env.ADMIN_USER || 'admin';
const ADMIN_PWD = process.env.ADMIN_PASSWORD || 'SQLBot@123456';
const UNIQ = Date.now();

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

const log = (...x) => console.log(...x);
let failures = 0;
function check(ok, name, detail) {
  log((ok ? 'PASS ' : 'FAIL ') + name + (detail ? ' :: ' + detail : ''));
  if (!ok) failures++;
}

async function main() {
  // 1) 会话跨实例：在 A 登录，用同一 token 连续访问 B
  const login = await call(A, 'POST', '/api/v1/login/access-token', {
    query: { username: enc(ADMIN), password: enc(ADMIN_PWD) },
  });
  const token = login.data && login.data.data ? login.data.data.access_token : null;
  check(!!token, 'A 登录', 'status=' + login.status);
  if (!token) process.exit(2);

  let sessionOk = true;
  for (let i = 0; i < 6; i++) {
    const r = await call(B, 'GET', '/api/v1/system/assistant', { token });
    if (r.status !== 200) { sessionOk = false; log('  B 第 ' + (i + 1) + ' 次请求 status=' + r.status); }
  }
  check(sessionOk, '会话跨实例（A 登录，B 连续 6 次 200）');

  // 2) 助手文件跨实例：A 建助手，B 能读到
  const created = await call(A, 'POST', '/api/v1/system/assistant', {
    token,
    body: { name: 'cluster-probe-' + UNIQ, domain: 'cluster-' + UNIQ + '.example.com', type: 0, description: 'probe' },
  });
  const assistantId = created.data && created.data.data ? created.data.data.id : null;
  check(!!assistantId, 'A 创建助手', 'status=' + created.status);

  const listedOnB = await call(B, 'GET', '/api/v1/system/assistant', { token });
  const rows = (listedOnB.data && listedOnB.data.data) || [];
  const seenOnB = rows.some((r) => String(r.id) === String(assistantId));
  check(seenOnB, '助手跨实例可见（A 建，B 列表可见）');

  // 3) Excel 上传→导入跨实例：A 解析，B 导入
  const csv = 'id,name\n1,alpha\n2,beta\n';
  const form = new FormData();
  form.append('file', new Blob([csv], { type: 'text/csv' }), 'cluster_' + UNIQ + '.csv');
  const parseRes = await fetch(A + '/api/v1/datasource/parseExcel', {
    method: 'POST',
    headers: { 'X-SQLBOT-TOKEN': 'Bearer ' + token },
    body: form,
  });
  const parseText = await parseRes.text();
  let parseJson = null;
  try { parseJson = JSON.parse(parseText); } catch (e) { /* 非 JSON 直接判失败 */ }
  const filePath = parseJson && parseJson.data ? parseJson.data.filePath : null;
  check(parseRes.status === 200 && !!filePath, 'A 解析 Excel', 'status=' + parseRes.status);

  if (filePath) {
    const sheets = ((parseJson.data.data) || []).map((s) => ({
      sheetName: s.sheetName,
      fields: (s.fields || []).map((f) => ({ fieldName: f.fieldName, fieldType: f.fieldType })),
    }));
    const imported = await call(B, 'POST', '/api/v1/datasource/importToDb', {
      token,
      body: { filePath, sheets },
    });
    const okImport = imported.status === 200 && imported.data && imported.data.data;
    check(okImport, 'B 用 A 上传的文件导入建表', 'status=' + imported.status + ' ' + String(imported.text).slice(0, 160));
  }

  // 4) 定时任务导出端点：不存在的运行记录应返回可读错误（端点已装配）
  const exportRes = await call(B, 'GET', '/api/v1/scheduled_task/run/999999999/export', { token });
  check(exportRes.status === 400, '定时任务导出端点可用（不存在记录返回 400）',
    'status=' + exportRes.status);

  // 清理
  if (assistantId) await call(A, 'DELETE', '/api/v1/system/assistant/' + assistantId, { token });

  if (failures === 0) {
    log('RESULT CLUSTER_OK failures=0');
  } else {
    log('RESULT CLUSTER_FAIL failures=' + failures);
    process.exit(1);
  }
}

main().catch((e) => { log('ERROR', e && e.message ? e.message : e); process.exit(1); });
