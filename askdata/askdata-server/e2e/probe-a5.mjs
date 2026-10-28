import { createCipheriv } from 'crypto';
const BASE='http://127.0.0.1:18099';const ADMIN='admin';const ADMIN_PWD='SQLBot@123456';const UNIQ=Date.now();
function enc(p){const c=createCipheriv('aes-128-ecb',Buffer.from('SQLBot1234567890','utf8'),null);return Buffer.concat([c.update(Buffer.from(p,'utf8'),'utf8'),c.final()]).toString('base64');}
async function call(m,p,o){o=o||{};const h={'Content-Type':'application/json'};if(o.token)h['X-SQLBOT-TOKEN']='Bearer '+o.token;let u=BASE+p;if(o.query)u+=(u.indexOf('?')>=0?'&':'?')+new URLSearchParams(o.query).toString();const r=await fetch(u,{method:m,headers:h,body:o.body!==undefined?JSON.stringify(o.body):undefined});const t=await r.text();let d=null;try{d=t?JSON.parse(t):null;}catch(e){d=t;}return{status:r.status,data:d};}
const log=(...x)=>console.log(...x);
function id(r){if(!r.data||!r.data.data)return null;const x=r.data.data;return x.id||(x.data&&x.data.id)||null;}
async function main(){
  const lr=await call('POST','/api/v1/login/access-token',{query:{username:enc(ADMIN),password:enc(ADMIN_PWD)}});
  const token=lr.data.data.access_token;
  const conf=JSON.stringify({host:'127.0.0.1',port:3306,username:'root',password:(process.env.E2E_MYSQL_PASSWORD||''),database:'askdata_verify',engine:'mysql',extraJdbc:'',dbSchema:'',timeout:30,ssl:false,poolSize:5,lowVersion:false});
  const dr=await call('POST','/api/v1/datasource/add',{token,body:{name:'a5-ds-'+UNIQ,type:'mysql',configuration:conf}});
  const dsId=id(dr); const gt=await call('POST','/api/v1/datasource/getTables/'+dsId,{token});
  await call('POST','/api/v1/datasource/chooseTables/'+dsId,{token,body:(gt.data.data||[]).map(t=>({table_name:t.tableName||t.name}))});
  const sr=await call('POST','/api/v1/scheduled_task/save',{token,body:{name:'a5-'+UNIQ,question:'各订单状态的订单数量和总金额',datasource_id:dsId,interval_seconds:60,export_format:'csv'}});
  const taskId=id(sr); log('SCHED SAVE',sr.status,'task='+taskId);
  await call('POST','/api/v1/scheduled_task/run/'+taskId,{token});
  let st='?',ep=null;
  for(let i=0;i<20;i++){await new Promise(r=>setTimeout(r,3000));const rs=await call('POST','/api/v1/scheduled_task/runs/'+taskId,{token});const runs=(rs.data&&rs.data.data)||[];if(runs.length){st=String(runs[0].status||runs[0].state||'?');ep=runs[0].file_path||runs[0].export_path||runs[0].fileUrl||null;log('poll'+i,'status='+st,'export='+ep);if(/SUCCESS|成功|done|FINISHED/i.test(st)||ep)break;}}
  log('A5 RESULT', /SUCCESS|成功|done|FINISHED/i.test(st)?'RUN_SUCCESS':'RUN_'+st, ep?'EXPORT_OK':'NO_EXPORT');
  await call('POST','/api/v1/scheduled_task/delete/'+taskId,{token});
  await call('POST','/api/v1/datasource/delete/'+dsId+'/a5',{token});
  log('DONE');
}
main().catch(e=>{console.error('ERR',e);process.exit(2);});
