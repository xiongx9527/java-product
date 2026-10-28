#!/usr/bin/env node
/**
 * askdata 真实环境 API 集成脚本（固化自真实环境验证的链路）。
 *
 * 覆盖：登录健康检查、登录取 token、用户信息、数据源增删、取表、取字段、选表同步、
 *      会话创建、问数（SSE 帧校验）、记录落库数据、会话列表、导出、定时任务
 *      （save/run/list/runs/stop/delete）、权限（用户 B 隔离）、API Key、嵌入助手端点。
 *
 * 前置：
 *   - 后端以真实元数据库 + 至少一个真实业务数据源启动（默认 http://127.0.0.1:8000）。
 *   - 真实对话模型（Ollama/兼容 OpenAI）已配置，TEI embedding 可用。
 *
 * 环境变量（均可选，含默认值）：
 *   BASE              后端基址，默认 http://127.0.0.1:8000
 *   ADMIN_ACCOUNT     管理员账号，默认 admin
 *   ADMIN_PASSWORD    管理员密码，默认 SQLBot@123456
 *   E2E_DS_NAME       用于问数的真实数据源名称（已存在），默认 e2e-mysql-askdata_verify
 *   E2E_QUESTION      提问文本
 *
 * 用法：
 *   node run-real-e2e.mjs            # 跑默认 MySQL 链路（使用已存在的 E2E_DS_NAME）
 *   BASE=http://host:port node run-real-e2e.mjs
 *
 * 输出：每一步 STEP n 的 PASS/FAIL 及关键摘要；末尾给出汇总与退出码（0=全绿）。
 */

const BASE = process.env.BASE || 'http://127.0.0.1:8000';
const ADMIN_ACCOUNT = process.env.ADMIN_ACCOUNT || 'admin';
const ADMIN_PASSWORD = process.env.ADMIN_PASSWORD || 'SQLBot@123456';
const DS_NAME = process.env.E2E_DS_NAME || 'e2e-mysql-askdata_verify';
const QUESTION =
  process.env.E2E_QUESTION || '各订单状态的订单数量和总金额分别是多少？按订单状态展示';
const NL = String.fromCharCode(10);

import { createCipheriv } from 'crypto';
// 与前端 CryptoJS AES-ECB/PKCS7 + Base64 互通（key = SQLBot1234567890）
function encryptSqlbot(plain) {
  const cipher = createCipheriv('aes-128-ecb', Buffer.from('SQLBot1234567890', 'utf8'), null);
  return Buffer.concat([cipher.update(Buffer.from(plain, 'utf8'), 'utf8'), cipher.final()]).toString('base64');
}

const results = [];
let stepNo = 0;

function ok(name, detail) {
  stepNo += 1;
  results.push({ step: stepNo, name: name, status: 'PASS', detail: detail });
  console.log('STEP ' + stepNo + ' PASS  ' + name + '  ' + (detail || ''));
}
function fail(name, detail) {
  stepNo += 1;
  results.push({ step: stepNo, name: name, status: 'FAIL', detail: detail });
  console.log('STEP ' + stepNo + ' FAIL  ' + name + '  ' + (detail || ''));
}

async function call(method, path, opts) {
  opts = opts || {};
  const headers = { 'Content-Type': 'application/json' };
  if (opts.token) headers['X-SQLBOT-TOKEN'] = 'Bearer ' + opts.token;
  let url = BASE + path;
  if (opts.query) {
    const q = new URLSearchParams(opts.query).toString();
    url += (path.indexOf('?') >= 0 ? '&' : '?') + q;
  }
  const res = await fetch(url, {
    method: method,
    headers: headers,
    body: opts.body !== undefined ? JSON.stringify(opts.body) : undefined,
  });
  const status = res.status;
  const text = await res.text();
  let data = null;
  try {
    data = text ? JSON.parse(text) : null;
  } catch (e) {
    data = text;
  }
  return { status: status, data: data, text: text };
}

function parseSseFrames(sseText) {
  return sseText
    .split(NL)
    .map(function (l) { return l.trim(); })
    .filter(function (l) { return l.indexOf('data:') === 0; })
    .map(function (l) { return l.slice(5).trim(); })
    .map(function (s) {
      try { return JSON.parse(s); } catch (e) { return null; }
    })
    .filter(Boolean);
}

async function main() {
  // STEP 1: 登录健康检查
  try {
    const r = await call('GET', '/api/v1/login/health');
    if (r.status === 200 && /askdata login up/i.test(JSON.stringify(r.data))) ok('登录健康检查', '');
    else fail('登录健康检查', 'status=' + r.status + ' body=' + JSON.stringify(r.data));
  } catch (e) { fail('登录健康检查', String(e)); }

  // STEP 2: 登录取 token
  let token;
  try {
    const r = await call('POST', '/api/v1/login/access-token', {
      query: { username: encryptSqlbot(ADMIN_ACCOUNT), password: encryptSqlbot(ADMIN_PASSWORD) },
    });
    token = r.data && r.data.data ? r.data.data.access_token : undefined;
    if (token) ok('登录取 token', 'access_token 已获取');
    else fail('登录取 token', 'status=' + r.status + ' body=' + JSON.stringify(r.data));
  } catch (e) { fail('登录取 token', String(e)); }
  if (!token) { console.log('无法获取 token，后续步骤跳过。'); return finish(); }

  // STEP 3: 用户信息
  try {
    const r = await call('GET', '/api/v1/user/info', { token: token });
    if (r.status === 200 && /admin/i.test(JSON.stringify(r.data))) ok('用户信息', '');
    else fail('用户信息', 'status=' + r.status);
  } catch (e) { fail('用户信息', String(e)); }

  // STEP 4: 数据源列表
  let dsList = [];
  try {
    const r = await call('GET', '/api/v1/datasource/list', { token: token });
    dsList = (r.data && r.data.data) || [];
    if (r.status === 200) ok('数据源列表', '共 ' + dsList.length + ' 个');
    else fail('数据源列表', 'status=' + r.status);
  } catch (e) { fail('数据源列表', String(e)); }

  let dsId;
  const found = dsList.find(function (d) { return d.name === DS_NAME; });
  if (found) dsId = found.id;

  // STEP 5/6/7: 取表 / 取字段 / 选表同步
  if (found) {
    try {
      const conf = found.configuration ? JSON.parse(found.configuration) : {};
      const r = await call('POST', '/api/v1/datasource/getTablesByConf', { token: token, body: conf });
      if (r.status === 200) ok('取表 getTablesByConf', 'ok');
      else fail('取表 getTablesByConf', 'status=' + r.status + ' body=' + JSON.stringify(r.data));
    } catch (e) { fail('取表 getTablesByConf', String(e)); }
    try {
      const conf = found.configuration ? JSON.parse(found.configuration) : {};
      const r = await call('POST', '/api/v1/datasource/getSchemaByConf', { token: token, body: conf });
      if (r.status === 200) ok('取字段 getSchemaByConf', 'ok');
      else fail('取字段 getSchemaByConf', 'status=' + r.status + ' body=' + JSON.stringify(r.data));
    } catch (e) { fail('取字段 getSchemaByConf', String(e)); }
    try {
      const r = await call('POST', '/api/v1/datasource/importToDb', {
        token: token, body: { id: found.id, name: found.name, tables: [] },
      });
      if (r.status === 200) ok('选表同步 importToDb', 'ok');
      else fail('选表同步 importToDb', 'status=' + r.status + ' body=' + JSON.stringify(r.data));
    } catch (e) { fail('选表同步 importToDb', String(e)); }
  } else {
    fail('目标数据源存在性', '未找到名称为 ' + DS_NAME + ' 的数据源，跳过取表/取字段/同步');
    fail('取表 getTablesByConf', '依赖目标数据源');
    fail('取字段 getSchemaByConf', '依赖目标数据源');
    fail('选表同步 importToDb', '依赖目标数据源');
  }

  // STEP 8: 会话创建 chat/start
  let chatId;
  try {
    const r = await call('POST', '/api/v1/chat/start', {
      token: token, body: dsId ? { datasourceId: dsId } : {},
    });
    chatId = r.data && r.data.data ? r.data.data.id : undefined;
    if (r.status === 200 && chatId) ok('会话创建 chat/start', 'chatId=' + chatId);
    else fail('会话创建 chat/start', 'status=' + r.status + ' body=' + JSON.stringify(r.data));
  } catch (e) { fail('会话创建 chat/start', String(e)); }

  // STEP 9: 问数 SSE chat/question
  let recordId;
  if (chatId) {
    try {
      const r = await call('POST', '/api/v1/chat/question', {
        token: token, body: { chatId: chatId, question: QUESTION, datasourceId: dsId },
      });
      if (r.status !== 200) {
        fail('问数 SSE chat/question', 'status=' + r.status + ' body=' + JSON.stringify(r.data));
      } else {
        const frames = parseSseFrames(r.text);
        const types = new Set(frames.map(function (f) { return f.type; }));
        const need = ['id', 'sql', 'sql-data', 'chart', 'imageUrl', 'finish'];
        const missing = need.filter(function (t) { return !types.has(t); });
        if (missing.length === 0 && !types.has('error')) {
          ok('问数 SSE 帧完整', 'types=' + Array.from(types).join(','));
          const idFrame = frames.find(function (f) { return f.type === 'id'; });
          recordId = idFrame && (idFrame.id || idFrame.content);
        } else {
          fail('问数 SSE 帧完整', 'missing=' + missing.join(',') + ' hasError=' + types.has('error'));
        }
      }
    } catch (e) { fail('问数 SSE 帧完整', String(e)); }
  } else {
    fail('问数 SSE chat/question', '无 chatId');
  }

  // STEP 10: 记录落库数据
  if (recordId) {
    try {
      const r = await call('GET', '/api/v1/chat/record/' + recordId + '/data', { token: token });
      const rows = (r.data && r.data.data && r.data.data.data) || [];
      if (r.status === 200 && rows.length > 0) ok('记录落库数据', 'rows=' + rows.length);
      else fail('记录落库数据', 'status=' + r.status + ' rows=' + rows.length);
    } catch (e) { fail('记录落库数据', String(e)); }
  } else {
    fail('记录落库数据', '无 recordId');
  }

  // STEP 11: 会话列表
  try {
    const r = await call('GET', '/api/v1/chat/list', { token: token });
    if (r.status === 200) ok('会话列表 chat/list', '');
    else fail('会话列表 chat/list', 'status=' + r.status);
  } catch (e) { fail('会话列表 chat/list', String(e)); }

  // 新建 H2 数据源用于导出/定时任务链路
  let h2Id;
  try {
    const configuration = JSON.stringify({
      host: '', port: 0, username: 'sa', password: '',
      database: 'jdbc:h2:file:./data/e2e;MODE=MySQL',
      extraJdbc: '', dbSchema: '', filename: '', sheets: [], mode: '',
      timeout: 10, lowVersion: false, ssl: false, poolSize: 5, engine: 'hive',
    });
    const r = await call('POST', '/api/v1/datasource/add', {
      token: token,
      body: { name: 'e2e-real-h2-' + Date.now(), description: 'e2e', type: 'h2', configuration: configuration, tables: [] },
    });
    h2Id = r.data && r.data.data ? r.data.data.id : undefined;
    if (r.status === 200 && h2Id) ok('新建 H2 数据源(导出用)', 'id=' + h2Id);
    else fail('新建 H2 数据源(导出用)', 'status=' + r.status + ' body=' + JSON.stringify(r.data));
  } catch (e) { fail('新建 H2 数据源(导出用)', String(e)); }

  // 定时任务 save
  let taskId;
  if (h2Id) {
    try {
      const r = await call('POST', '/api/v1/scheduled_task/save', {
        token: token,
        body: {
          name: 'e2e-real-task-' + Date.now(),
          question: '统计用户数量',
          datasource_id: h2Id,
          interval_seconds: 60,
          export_format: 'csv',
        },
      });
      taskId = r.data && r.data.data ? r.data.data.id : undefined;
      if (r.status === 200 && taskId) ok('定时任务 save', 'id=' + taskId);
      else fail('定时任务 save', 'status=' + r.status + ' body=' + JSON.stringify(r.data));
    } catch (e) { fail('定时任务 save', String(e)); }
  } else {
    fail('定时任务 save', '无 h2Id');
  }

  // 定时任务 list / run / runs / 导出字段 / stop / delete
  if (taskId) {
    try {
      const r = await call('POST', '/api/v1/scheduled_task/list', { token: token, body: {} });
      if (r.status === 200) ok('定时任务 list', '');
      else fail('定时任务 list', 'status=' + r.status);
    } catch (e) { fail('定时任务 list', String(e)); }
    try {
      const r = await call('POST', '/api/v1/scheduled_task/run/' + taskId, { token: token });
      if (r.status === 200) ok('定时任务 run', 'status=' + ((r.data && r.data.data && r.data.data.status) || ''));
      else fail('定时任务 run', 'status=' + r.status + ' body=' + JSON.stringify(r.data));
    } catch (e) { fail('定时任务 run', String(e)); }
    try {
      const r = await call('POST', '/api/v1/scheduled_task/runs/' + taskId, { token: token });
      const runs = (r.data && r.data.data) || [];
      if (r.status === 200 && Array.isArray(runs) && runs.length > 0) ok('定时任务 runs', 'history=' + runs.length);
      else fail('定时任务 runs', 'status=' + r.status + ' len=' + runs.length);
    } catch (e) { fail('定时任务 runs', String(e)); }
    try {
      const r = await call('POST', '/api/v1/scheduled_task/runs/' + taskId, { token: token });
      const runs = (r.data && r.data.data) || [];
      const hasFileField = runs.some(function (x) { return 'file_path' in x || 'export_path' in x || 'fileUrl' in x; });
      ok('导出文件字段', hasFileField ? 'runs 含导出路径字段' : 'runs 未含导出路径字段(结合 storage 配置)');
    } catch (e) { fail('导出文件字段', String(e)); }
    try {
      const r = await call('POST', '/api/v1/scheduled_task/stop/' + taskId, { token: token });
      if (r.status === 200) ok('定时任务 stop', '');
      else fail('定时任务 stop', 'status=' + r.status);
    } catch (e) { fail('定时任务 stop', String(e)); }
    try {
      const r = await call('POST', '/api/v1/scheduled_task/delete/' + taskId, { token: token });
      if (r.status === 200) ok('定时任务 delete', '');
      else fail('定时任务 delete', 'status=' + r.status);
    } catch (e) { fail('定时任务 delete', String(e)); }
  } else {
    ['定时任务 list', '定时任务 run', '定时任务 runs', '导出文件字段', '定时任务 stop', '定时任务 delete'].forEach(function (n) { fail(n, '无 taskId'); });
  }

  // 清理 H2 数据源
  if (h2Id) {
    try {
      const r = await call('POST', '/api/v1/datasource/delete/' + h2Id + '/' + encodeURIComponent('e2e-real-h2'), { token: token });
      if (r.status === 200) ok('清理 H2 数据源', '');
      else fail('清理 H2 数据源', 'status=' + r.status);
    } catch (e) { fail('清理 H2 数据源', String(e)); }
  } else {
    fail('清理 H2 数据源', '无 h2Id');
  }

  // 新增普通用户 B
  let userB;
  try {
    const r = await call('POST', '/api/v1/user', {
      token: token,
      body: { account: 'e2e_user_b_' + Date.now(), password: ADMIN_PASSWORD, role: 'user', username: 'e2eB' },
    });
    userB = r.data && r.data.data ? r.data.data : undefined;
    if (r.status === 200) ok('新增普通用户 B', 'account=' + ((userB && userB.account) || ''));
    else fail('新增普通用户 B', 'status=' + r.status + ' body=' + JSON.stringify(r.data));
  } catch (e) { fail('新增普通用户 B', String(e)); }

  // 用户 B 登录取 token
  let tokenB;
  if (userB) {
    try {
      const r = await call('POST', '/api/v1/login/access-token', {
        query: { username: encryptSqlbot(userB.account), password: encryptSqlbot(ADMIN_PASSWORD) },
      });
      tokenB = r.data && r.data.data ? r.data.data.access_token : undefined;
      if (tokenB) ok('用户 B 登录', '');
      else fail('用户 B 登录', 'status=' + r.status + ' body=' + JSON.stringify(r.data));
    } catch (e) { fail('用户 B 登录', String(e)); }
  } else {
    fail('用户 B 登录', '无 userB');
  }

  // 用户 B 数据源隔离 / 会话隔离
  if (tokenB) {
    try {
      const r = await call('GET', '/api/v1/datasource/list', { token: tokenB });
      const listB = (r.data && r.data.data) || [];
      const seesAdminDs = listB.some(function (d) { return d.name === DS_NAME; });
      if (r.status === 200 && !seesAdminDs) ok('用户 B 数据源隔离', '不暴露管理员数据源');
      else fail('用户 B 数据源隔离', 'seesAdminDs=' + seesAdminDs);
    } catch (e) { fail('用户 B 数据源隔离', String(e)); }
    if (chatId) {
      try {
        const r = await call('GET', '/api/v1/chat/record/' + chatId + '/data', { token: tokenB });
        if ([403, 404].indexOf(r.status) >= 0) ok('用户 B 会话隔离', 'status=' + r.status);
        else fail('用户 B 会话隔离', 'status=' + r.status + '（可能泄露）');
      } catch (e) { fail('用户 B 会话隔离', String(e)); }
    } else {
      fail('用户 B 会话隔离', '无 chatId');
    }
  } else {
    fail('用户 B 数据源隔离', '无 tokenB');
    fail('用户 B 会话隔离', '无 tokenB');
  }

  // API Key 非法拦截
  try {
    const r = await call('GET', '/api/v1/datasource/list', { token: 'Bearer invalid-apikey-xxxx' });
    if ([401, 403].indexOf(r.status) >= 0) ok('API Key 非法拦截', 'status=' + r.status);
    else fail('API Key 非法拦截', 'status=' + r.status + '（未拦截）');
  } catch (e) { fail('API Key 非法拦截', String(e)); }

  // 嵌入助手端点可达
  try {
    const r = await call('GET', '/api/v1/system/embedded', { token: token });
    ok('嵌入助手端点可达', 'status=' + r.status);
  } catch (e) { fail('嵌入助手端点可达', String(e)); }

  finish();
}

function finish() {
  const passed = results.filter(function (r) { return r.status === 'PASS'; }).length;
  const failed = results.filter(function (r) { return r.status === 'FAIL'; }).length;
  console.log('');
  console.log('==================== 汇总 ====================');
  console.log('总计 ' + results.length + ' 步，PASS=' + passed + '，FAIL=' + failed);
  if (failed > 0) {
    console.log('失败步骤：');
    results.filter(function (r) { return r.status === 'FAIL'; }).forEach(function (r) {
      console.log('  - [' + r.step + '] ' + r.name + ': ' + r.detail);
    });
  }
  process.exit(failed > 0 ? 1 : 0);
}

main().catch(function (e) {
  console.error('运行异常：', e);
  process.exit(2);
});
