import { createCipheriv } from 'crypto';

const BASE = process.env.BASE || 'http://127.0.0.1:18099';
const ADMIN = process.env.ADMIN_ACCOUNT || 'admin';
const ADMIN_PWD = process.env.ADMIN_PASSWORD || 'SQLBot@123456';
const Q = process.env.E2E_QUESTION || '各订单状态的订单数量和总金额分别是多少？按订单状态展示';
const NL = String.fromCharCode(10);
const UNIQ = Date.now();

function encryptSqlbot(plain) {
  const cipher = createCipheriv('aes-128-ecb', Buffer.from('SQLBot1234567890', 'utf8'), null);
  return Buffer.concat([cipher.update(Buffer.from(plain, 'utf8'), 'utf8'), cipher.final()]).toString('base64');
}

async function call(method, path, opts) {
  opts = opts || {};
  const headers = { 'Content-Type': 'application/json' };
  if (opts.token) headers['X-SQLBOT-TOKEN'] = 'Bearer ' + opts.token;
  let url = BASE + path;
  if (opts.query) url += (path.indexOf('?') >= 0 ? '&' : '?') + new URLSearchParams(opts.query).toString();
  const res = await fetch(url, { method, headers, body: opts.body !== undefined ? JSON.stringify(opts.body) : undefined });
  const text = await res.text();
  let data = null; try { data = text ? JSON.parse(text) : null; } catch (e) { data = text; }
  return { status: res.status, data, text };
}

function log(...a) { console.log(...a); }

async function main() {
  const lr = await call('POST', '/api/v1/login/access-token', { query: { username: encryptSqlbot(ADMIN), password: encryptSqlbot(ADMIN_PWD) } });
  if (lr.status !== 200 || !lr.data || !lr.data.data || !lr.data.data.access_token) {
    log('LOGIN FAIL', lr.status, JSON.stringify(lr.data)); return;
  }
  const token = lr.data.data.access_token;
  log('LOGIN OK');

  if (process.env.E2E_CREATE_MODEL) {
    const mr = await call('POST', '/api/v1/system/aimodel', { token, body: {
      name: 'ollama-gptoss-' + UNIQ, base_model: 'gpt-oss:120b-cloud', api_domain: 'http://127.0.0.1:11434',
      protocol: 1, supplier: 0, model_type: 0, api_key: '', default_model: true,
    } });
    log('AIMODEL CREATE', mr.status, mr.data && mr.data.data ? JSON.stringify(mr.data.data).slice(0,120) : JSON.stringify(mr.data));
    let modelId = mr.data && mr.data.data ? (mr.data.data.id || (mr.data.data.data && mr.data.data.data.id)) : null;
    if (modelId) {
      const sd = await call('PUT', '/api/v1/system/aimodel/default/' + modelId, { token });
      log('SET DEFAULT', sd.status);
    }
  } else {
    log('USE EXISTING DEFAULT MODEL (E2E_CREATE_MODEL not set)');
  }
  const dv = await call('GET', '/api/v1/system/aimodel/default', { token });
  log('DEFAULT CHECK', dv.status);

  const conf = JSON.stringify({
    host: '127.0.0.1', port: 3306, username: 'root', password: (process.env.E2E_MYSQL_PASSWORD || ''),
    database: 'askdata_verify', engine: 'mysql', extraJdbc: '', dbSchema: '',
    timeout: 30, ssl: false, poolSize: 5, lowVersion: false,
  });
  const dr = await call('POST', '/api/v1/datasource/add', { token, body: {
    name: 'verify-mysql-' + UNIQ, description: 'real-validation', type: 'mysql', configuration: conf,
  } });
  log('DATASOURCE ADD', dr.status, dr.data && dr.data.data ? 'id=' + (dr.data.data.id||dr.data.data) : JSON.stringify(dr.data));
  let dsId = dr.data && dr.data.data ? (dr.data.data.id || dr.data.data) : null;
  if (typeof dsId === 'object') dsId = dsId.id;
  if (!dsId) { log('NO DSID, stop'); return; }
  log('DSID', dsId);

  const gt = await call('POST', '/api/v1/datasource/getTables/' + dsId, { token });
  log('GET TABLES', gt.status, JSON.stringify(gt.data).slice(0, 300));
  const tables = (gt.data && gt.data.data) || [];
  const chosen = tables.map((t) => ({ table_name: t.tableName || t.name || t.table_name || t.TABLE_NAME }));
  log('CHOSEN', JSON.stringify(chosen));

  const ct = await call('POST', '/api/v1/datasource/chooseTables/' + dsId, { token, body: chosen });
  log('CHOOSE TABLES', ct.status, ct.data ? JSON.stringify(ct.data).slice(0,120) : '');

  const cs = await call('POST', '/api/v1/chat/start', { token, body: { datasourceId: dsId } });
  log('CHAT START', cs.status, cs.data && cs.data.data ? 'chatId=' + cs.data.data.id : JSON.stringify(cs.data).slice(0,150));
  const chatId = cs.data && cs.data.data ? cs.data.data.id : null;
  if (!chatId) { log('NO CHATID'); return; }

  const qr = await call('POST', '/api/v1/chat/question', { token, body: { chat_id: chatId, question: Q, datasource_id: dsId } });
  if (qr.status !== 200) { log('QUESTION FAIL', qr.status, qr.text.slice(0, 500)); return; }
  const frames = qr.text.split(NL).map((l) => l.trim()).filter((l) => l.indexOf('data:') === 0)
    .map((l) => l.slice(5).trim()).map((s) => { try { return JSON.parse(s); } catch (e) { return null; } }).filter(Boolean);
  const types = new Set(frames.map((f) => f.type));
  log('FRAME TYPES', Array.from(types).join(','));
  const sqlFrame = frames.find((f) => f.type === 'sql');
  const dataFrame = frames.find((f) => f.type === 'sql-data');
  const chartFrame = frames.find((f) => f.type === 'chart');
  log('SQL', sqlFrame ? JSON.stringify(sqlFrame).slice(0, 300) : 'NONE');
  log('DATA rows', dataFrame && dataFrame.data ? (Array.isArray(dataFrame.data) ? dataFrame.data.length : JSON.stringify(dataFrame.data).slice(0,200)) : 'NONE');
  log('CHART', chartFrame ? JSON.stringify(chartFrame).slice(0, 200) : 'NONE');
  const hasError = types.has('error');
  log('RESULT', hasError ? 'ERROR' : (types.has('finish') && types.has('sql') && types.has('sql-data') ? 'CHAT_OK' : 'PARTIAL'));
}

main().catch((e) => { console.error('ERR', e); process.exit(2); });
