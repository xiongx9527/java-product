import { createCipheriv } from 'crypto';
// MCP 集成探针：走 SqlbotMcpCompatController HTTP 兼容层（/api/v1/mcp/*）。
// 注意 MCP access_token 用明文账号/口令（MD5 比对），与主页登录的 AES-ECB 不同。
const BASE = process.env.BASE || 'http://127.0.0.1:18099';
const ADMIN = process.env.ADMIN_ACCOUNT || 'admin';
const ADMIN_PWD = process.env.ADMIN_PASSWORD || 'SQLBot@123456';
const Q = process.env.E2E_QUESTION || '各订单状态的订单数量和总金额分别是多少？';
const NL = String.fromCharCode(10);
const UNIQ = Date.now();
function enc(plain){const c=createCipheriv('aes-128-ecb',Buffer.from('SQLBot1234567890','utf8'),null);return Buffer.concat([c.update(Buffer.from(plain,'utf8'),'utf8'),c.final()]).toString('base64');}
async function call(method,path,opts){opts=opts||{};const headers={'Content-Type':'application/json'};if(opts.token)headers['X-SQLBOT-TOKEN']='Bearer '+opts.token;let url=BASE+path;if(opts.query)url+=(path.indexOf('?')>=0?'&':'?')+new URLSearchParams(opts.query).toString();const res=await fetch(url,{method,headers,body:opts.body!==undefined?JSON.stringify(opts.body):undefined});const text=await res.text();let data=null;try{data=text?JSON.parse(text):null;}catch(e){data=text;}return{status:res.status,data,text};}
const log=(...a)=>console.log(...a);
async function main(){
  const lr=await call('POST','/api/v1/login/access-token',{query:{username:enc(ADMIN),password:enc(ADMIN_PWD)}});
  if(lr.status!==200||!lr.data||!lr.data.data||!lr.data.data.access_token){log('MAIN LOGIN FAIL',lr.status,JSON.stringify(lr.data));return;}
  const adminToken=lr.data.data.access_token; log('MAIN LOGIN OK');
  const conf=JSON.stringify({host:'127.0.0.1',port:3306,username:'root',password:(process.env.E2E_MYSQL_PASSWORD||''),database:'askdata_verify',engine:'mysql',extraJdbc:'',dbSchema:'',timeout:30,ssl:false,poolSize:5,lowVersion:false});
  const dr=await call('POST','/api/v1/datasource/add',{token:adminToken,body:{name:'mcp-verify-'+UNIQ,description:'mcp-probe',type:'mysql',configuration:conf}});
  let dsId=dr.data&&dr.data.data?(dr.data.data.id||dr.data.data):null; if(typeof dsId==='object')dsId=dsId.id; log('DATASOURCE ADD',dr.status,'dsId='+dsId);
  const gt=await call('POST','/api/v1/datasource/getTables/'+dsId,{token:adminToken});
  await call('POST','/api/v1/datasource/chooseTables/'+dsId,{token:adminToken,body:(gt.data.data||[]).map(t=>({table_name:t.tableName||t.name}))});
  log('CHOOSE TABLES',(gt.data.data||[]).length,'tables');
  const ar=await call('POST','/api/v1/mcp/access_token',{body:{username:ADMIN,password:ADMIN_PWD}});
  log('MCP access_token',ar.status,JSON.stringify(ar.data).slice(0,200));
  const mcpToken=ar.data&&(ar.data.access_token||ar.data.token); if(!mcpToken){log('NO MCP TOKEN');return;}
  log('MCP TOKEN OK');
  const dl=await call('POST','/api/v1/mcp/mcp_ds_list',{body:{token:mcpToken}});
  log('MCP mcp_ds_list',dl.status,JSON.stringify(dl.data).slice(0,250));
  const dsList=dl.data&&(dl.data.data||dl.data)||[]; const targetDs=dsId||(dsList[0]&&(dsList[0].id||dsList[0].datasourceId));
  const ml=await call('POST','/api/v1/mcp/mcp_model_list',{body:{token:mcpToken,oid:1}});
  log('MCP mcp_model_list',ml.status,JSON.stringify(ml.data).slice(0,250));
  const st=await call('POST','/api/v1/mcp/mcp_start',{body:{token:mcpToken,datasource_id:targetDs,question:Q}});
  log('MCP mcp_start',st.status,JSON.stringify(st.data).slice(0,250));
  const chatId=st.data&&(st.data.chat_id||(st.data.data&&st.data.data.chat_id)); log('MCP chatId',chatId);
  const qr=await call('POST','/api/v1/mcp/mcp_question',{body:{token:mcpToken,chat_id:chatId,question:Q,datasource_id:targetDs,stream:true}});
  const frames=qr.text.split(NL).map(l=>l.trim()).filter(l=>l.indexOf('data:')===0).map(l=>l.slice(5).trim()).map(s=>{try{return JSON.parse(s);}catch(e){return null;}}).filter(Boolean);
  const types=new Set(frames.map(f=>f.type));
  log('MCP mcp_question status',qr.status,'FRAME TYPES',Array.from(types).join(','));
  const sqlFrame=frames.find(f=>f.type==='sql'); log('MCP SQL',sqlFrame?JSON.stringify(sqlFrame).slice(0,200):'NONE');
  const hasError=types.has('error'); log('MCP RESULT',hasError?'ERROR':(types.has('finish')&&types.has('sql')?'MCP_OK':'MCP_PARTIAL'));
  if(dsId)await call('POST','/api/v1/datasource/delete/'+dsId+'/mcp',{token:adminToken});
  log('DONE');
}
main().catch(e=>{console.error('ERR',e);process.exit(2);});
