import { createCipheriv } from 'crypto';
const BASE = process.env.BASE || 'http://127.0.0.1:18099';
const ADMIN = 'admin'; const ADMIN_PWD = 'SQLBot@123456';
const NL = String.fromCharCode(10); const UNIQ = Date.now();
function enc(plain){const c=createCipheriv('aes-128-ecb',Buffer.from('SQLBot1234567890','utf8'),null);return Buffer.concat([c.update(Buffer.from(plain,'utf8'),'utf8'),c.final()]).toString('base64');}
async function call(method,path,opts){opts=opts||{};const h={'Content-Type':'application/json'};if(opts.token)h['X-SQLBOT-TOKEN']='Bearer '+opts.token;let u=BASE+path;if(opts.query)u+=(u.indexOf('?')>=0?'&':'?')+new URLSearchParams(opts.query).toString();const r=await fetch(u,{method,headers:h,body:opts.body!==undefined?JSON.stringify(opts.body):undefined});const t=await r.text();let d=null;try{d=t?JSON.parse(t):null;}catch(e){d=t;}return{status:r.status,data:d,text:t};}
const log=(...x)=>console.log(...x);
function getId(r){if(!r.data||!r.data.data)return null;const x=r.data.data;return x.id||(x.data&&x.data.id)||null;}

async function main(){
  const lr=await call('POST','/api/v1/login/access-token',{query:{username:enc(ADMIN),password:enc(ADMIN_PWD)}});
  const token=lr.data.data.access_token; log('ADMIN LOGIN OK');

  const ua='e2e_b_'+UNIQ;
  const cr=await call('POST','/api/v1/user',{token,body:{account:ua,name:'E2E User B',password:ADMIN_PWD,role:'user',email:ua+'@example.com'}});
  const userBId=getId(cr); log('CREATE USER B',cr.status,'id='+userBId);
  if(cr.status!==200){log('fail');return;}
  // bind to workspace 1
  const wb=await call('PUT','/api/v1/user',{token,body:{id:userBId,oid_list:[1]}});
  log('BIND WS',wb.status,JSON.stringify(wb.data).slice(0,80));

  const lb=await call('POST','/api/v1/login/access-token',{query:{username:enc(ua),password:enc(ADMIN_PWD)}});
  const tokenB=lb.data&&lb.data.data?lb.data.data.access_token:null;
  if(!tokenB){log('USER B LOGIN FAIL',lb.status,JSON.stringify(lb.data));return;}
  log('USER B LOGIN OK');

  const conf=JSON.stringify({host:'127.0.0.1',port:3306,username:'root',password:(process.env.E2E_MYSQL_PASSWORD||''),database:'askdata_verify',engine:'mysql',extraJdbc:'',dbSchema:'',timeout:30,ssl:false,poolSize:5,lowVersion:false});
  const dr=await call('POST','/api/v1/datasource/add',{token,body:{name:'iso-mysql-'+UNIQ,description:'iso',type:'mysql',configuration:conf}});
  const dsId=getId(dr); log('ADMIN DS ADD',dr.status,'dsId='+dsId);
  const gt=await call('POST','/api/v1/datasource/getTables/'+dsId,{token});
  const chosen=(gt.data.data||[]).map(t=>({table_name:t.tableName||t.name}));
  await call('POST','/api/v1/datasource/chooseTables/'+dsId,{token,body:chosen});

  // 授权白名单：只授权 admin(uid=1) → 普通用户 B 必须看不到该数据源（空授权=工作空间共享）
  const gs=await call('POST','/api/v1/ds_grant/save',{token,body:{ds_id:dsId,user_ids:[1]}});
  log('DS GRANT ADMIN-ONLY',gs.status);
  const lB=await call('GET','/api/v1/datasource/list',{token:tokenB});
  const listB=(lB.data&&lB.data.data)||[];
  const seesAdmin=listB.some(d=>String(d.id)===String(dsId));
  if(seesAdmin) log('B LIST',JSON.stringify(listB.map(d=>d.name+':'+d.id)).slice(0,400));
  log('A4 DATASOURCE ISOLATION', seesAdmin?'FAIL(leak)':'PASS(隔离)');

  const cs=await call('POST','/api/v1/chat/start',{token,body:{datasourceId:dsId}});
  const chatId=getId(cs);
  await call('POST','/api/v1/chat/question',{token,body:{chat_id:chatId,question:'各订单状态的订单数量',datasource_id:dsId}});
  await new Promise(r=>setTimeout(r,8000));
  const detail=await call('GET','/api/v1/chat/'+chatId+'/with_data',{token});
  const recs=(detail.data&&detail.data.data&&detail.data.data.records)||[];
  const rid=recs.length?(recs[recs.length-1].id||null):null;
  let isoChat='SKIP';
  if(rid){const rb=await call('GET','/api/v1/chat/record/'+rid+'/data',{token:tokenB});isoChat=([403,404].indexOf(rb.status)>=0)?'PASS(隔离)':'FAIL(status='+rb.status+')';}
  log('A4 CHAT ISOLATION',isoChat);

  const sr=await call('POST','/api/v1/scheduled_task/save',{token,body:{name:'e2e-task-'+UNIQ,question:'各订单状态的订单数量和总金额',datasource_id:dsId,interval_seconds:60,export_format:'csv'}});
  const taskId=getId(sr); log('SCHED SAVE',sr.status,'taskId='+taskId);
  if(taskId){
    const rn=await call('POST','/api/v1/scheduled_task/run/'+taskId,{token});
    log('SCHED RUN',rn.status,JSON.stringify(rn.data).slice(0,120));
    let status='?',exportPath=null;
    for(let i=0;i<12;i++){
      await new Promise(r=>setTimeout(r,3000));
      const rs=await call('POST','/api/v1/scheduled_task/runs/'+taskId,{token});
      const runs=(rs.data&&rs.data.data)||[];
      if(runs.length){status=runs[0].status||runs[0].state||'?';exportPath=runs[0].file_path||runs[0].export_path||runs[0].fileUrl||null;if(/SUCCESS|成功|done/i.test(String(status))||exportPath)break;}
    }
    log('A5 SCHED STATUS',status,'EXPORT',exportPath?'有导出文件':'无');
    await call('POST','/api/v1/scheduled_task/delete/'+taskId,{token});
  }
  await call('POST','/api/v1/datasource/delete/'+dsId+'/iso',{token});
  log('DONE');
}
main().catch(e=>{console.error('ERR',e);process.exit(2);});
