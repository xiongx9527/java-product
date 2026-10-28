import { createCipheriv } from 'crypto';
const BASE='http://127.0.0.1:18099';const ADMIN='admin';const ADMIN_PWD='SQLBot@123456';const UNIQ=Date.now();
function enc(p){const c=createCipheriv('aes-128-ecb',Buffer.from('SQLBot1234567890','utf8'),null);return Buffer.concat([c.update(Buffer.from(p,'utf8'),'utf8'),c.final()]).toString('base64');}
async function call(m,p,o){o=o||{};const h={'Content-Type':'application/json'};if(o.token)h['X-SQLBOT-TOKEN']='Bearer '+o.token;let u=BASE+p;if(o.query)u+=(u.indexOf('?')>=0?'&':'?')+new URLSearchParams(o.query).toString();const r=await fetch(u,{method:m,headers:h,body:o.body!==undefined?JSON.stringify(o.body):undefined,signal:AbortSignal.timeout(o.timeout||600000)});const t=await r.text();let d=null;try{d=t?JSON.parse(t):null;}catch(e){d=t;}return{status:r.status,data:d,text:t};}
const log=(...x)=>console.log(...x);const NL=String.fromCharCode(10);
async function main(){
  const lr=await call('POST','/api/v1/login/access-token',{query:{username:enc(ADMIN),password:enc(ADMIN_PWD)}});
  const token=lr.data.data.access_token; log('LOGIN OK');
  const conf=JSON.stringify({host:'127.0.0.1',port:3306,username:'root',password:(process.env.E2E_MYSQL_PASSWORD||''),database:'askdata_verify',engine:'mysql',extraJdbc:'',dbSchema:'',timeout:30,ssl:false,poolSize:5,lowVersion:false});
  const dr=await call('POST','/api/v1/datasource/add',{token,body:{name:'big-'+UNIQ,description:'100k',type:'mysql',configuration:conf}});
  const dsId=dr.data.data.id||dr.data.data; log('DS ADD',dr.status,'dsId='+dsId);
  const gt=await call('POST','/api/v1/datasource/getTables/'+dsId,{token});
  const tables=(gt.data.data||[]).map(t=>({table_name:t.tableName||t.name}));
  log('TABLES', JSON.stringify(tables));
  await call('POST','/api/v1/datasource/chooseTables/'+dsId,{token,body:tables});
  const cs=await call('POST','/api/v1/chat/start',{token,body:{datasourceId:dsId}});
  const chatId=cs.data.data.id; log('CHAT START',chatId);
  const q='统计 big_orders 表各 order_status 的订单数量，以及 amount 的总和，并按 region 分组';
  const t0=Date.now();
  const qr=await call('POST','/api/v1/chat/question',{token,timeout:600000,body:{chat_id:chatId,question:q,datasource_id:dsId}});
  const dt=Date.now()-t0;
  const frames=qr.text.split(NL).map(l=>l.trim()).filter(l=>l.indexOf('data:')===0).map(l=>l.slice(5).trim()).map(s=>{try{return JSON.parse(s);}catch(e){return null;}}).filter(Boolean);
  const types=new Set(frames.map(f=>f.type));
  log('A6 Q elapsed(ms)',dt,'FRAME TYPES',Array.from(types).join(','));
  const sqlF=frames.find(f=>f.type==='sql'); log('A6 SQL',sqlF?JSON.stringify(sqlF).slice(0,200):'NONE');
  log('A6 RESULT', types.has('error')?'ERROR':(types.has('finish')&&types.has('sql')?'BIGTABLE_OK':'PARTIAL'));
  await call('POST','/api/v1/datasource/delete/'+dsId+'/big',{token});
  log('DONE');
}
main().catch(e=>{console.error('ERR',e);process.exit(2);});
