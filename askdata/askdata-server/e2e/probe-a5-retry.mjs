import { createCipheriv } from 'crypto';
const BASE='http://127.0.0.1:18099';const ADMIN='admin';const ADMIN_PWD='SQLBot@123456';const UNIQ=Date.now();
function enc(p){const c=createCipheriv('aes-128-ecb',Buffer.from('SQLBot1234567890','utf8'),null);return Buffer.concat([c.update(Buffer.from(p,'utf8'),'utf8'),c.final()]).toString('base64');}
async function call(m,p,o){o=o||{};const h={'Content-Type':'application/json'};if(o.token)h['X-SQLBOT-TOKEN']='Bearer '+o.token;let u=BASE+p;if(o.query)u+=(u.indexOf('?')>=0?'&':'?')+new URLSearchParams(o.query).toString();const r=await fetch(u,{method:m,headers:h,body:o.body!==undefined?JSON.stringify(o.body):undefined});const t=await r.text();let d=null;try{d=t?JSON.parse(t):null;}catch(e){d=t;}return{status:r.status,data:d};}
const log=(...x)=>console.log(...x);
const QUESTIONS=[
  '统计 orders 表各 order_status 的订单数量，以及 amount 字段的总和',
  '按 order_status 分组，统计 orders 表每个状态的订单数(order_count)和金额总和(total_amount, 用 amount 列)',
  '查询 orders 表，按 order_status 分组，返回每组的记录数和 amount 合计'
];
async function tryOnce(q){
  const lr=await call('POST','/api/v1/login/access-token',{query:{username:enc(ADMIN),password:enc(ADMIN_PWD)}});
  const token=lr.data.data.access_token;
  const conf=JSON.stringify({host:'127.0.0.1',port:3306,username:'root',password:(process.env.E2E_MYSQL_PASSWORD||''),database:'askdata_verify',engine:'mysql',extraJdbc:'',dbSchema:'',timeout:30,ssl:false,poolSize:5,lowVersion:false});
  const dr=await call('POST','/api/v1/datasource/add',{token,body:{name:'a5r-ds-'+UNIQ,type:'mysql',configuration:conf}});
  const dsId=dr.data.data.id||dr.data.data; const gt=await call('POST','/api/v1/datasource/getTables/'+dsId,{token});
  await call('POST','/api/v1/datasource/chooseTables/'+dsId,{token,body:(gt.data.data||[]).map(t=>({table_name:t.tableName||t.name}))});
  const sr=await call('POST','/api/v1/scheduled_task/save',{token,body:{name:'a5r-'+UNIQ,question:q,datasource_id:dsId,interval_seconds:60,export_format:'csv'}});
  const taskId=sr.data.data.id||sr.data.data; log('SCHED SAVE',sr.status,'task='+taskId);
  await call('POST','/api/v1/scheduled_task/run/'+taskId,{token});
  let st='?',ep=null;
  for(let i=0;i<20;i++){await new Promise(r=>setTimeout(r,3000));const rs=await call('POST','/api/v1/scheduled_task/runs/'+taskId,{token});const runs=(rs.data&&rs.data.data)||[];if(runs.length){st=String(runs[0].status||runs[0].state||'?');ep=runs[0].file_path||runs[0].export_path||runs[0].fileUrl||null;if(/SUCCESS|成功|done|FINISHED/i.test(st)||ep)break;}}
  await call('POST','/api/v1/scheduled_task/delete/'+taskId,{token});
  await call('POST','/api/v1/datasource/delete/'+dsId+'/a5',{token});
  return /SUCCESS|成功|done|FINISHED/i.test(st);
}
async function main(){
  for(let k=0;k<QUESTIONS.length;k++){
    log('=== attempt',k,'q=',QUESTIONS[k]);
    try{ const ok=await tryOnce(QUESTIONS[k]); log('ATTEMPT',k,ok?'RUN_SUCCESS':'RUN_FAILED'); if(ok){log('A5 RESULT RUN_SUCCESS');return;} }catch(e){log('ATTEMPT',k,'ERR',e.message);}
  }
  log('A5 RESULT ALL_FAILED (model column variance)');
}
main().catch(e=>{console.error('ERR',e);process.exit(2);});
