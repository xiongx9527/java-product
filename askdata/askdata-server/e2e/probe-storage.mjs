import { createCipheriv } from 'crypto';
const BASE = process.env.BASE || 'http://127.0.0.1:18099';
const ADMIN = 'admin'; const ADMIN_PWD = 'SQLBot@123456';
const PLATFORM = process.env.FILE_STORAGE_PLATFORM || 'local';
const UNIQ = Date.now();
function enc(plain){const c=createCipheriv('aes-128-ecb',Buffer.from('SQLBot1234567890','utf8'),null);return Buffer.concat([c.update(Buffer.from(plain,'utf8'),'utf8'),c.final()]).toString('base64');}
async function call(method,path,opts){opts=opts||{};const h={'Content-Type':'application/json'};if(opts.token)h['X-SQLBOT-TOKEN']='Bearer '+opts.token;let u=BASE+path;if(opts.query)u+=(u.indexOf('?')>=0?'&':'?')+new URLSearchParams(opts.query).toString();const r=await fetch(u,{method,headers:h,body:opts.body!==undefined?JSON.stringify(opts.body):undefined});const t=await r.text();let d=null;try{d=t?JSON.parse(t):null;}catch(e){d=t;}return{status:r.status,data:d,text:t};}
const log=(...x)=>console.log(...x);

// 1x1 PNG（固定字节，用于内容一致性比对）
const PNG = Buffer.from('89504e470d0a1a0a0000000d49484452000000010000000108060000001f15c4890000000a49444154789c6360000002000100ffff03000006000557bfabd40000000049454e44ae426082','hex');

async function main(){
  const lr=await call('POST','/api/v1/login/access-token',{query:{username:enc(ADMIN),password:enc(ADMIN_PWD)}});
  const token=lr.data&&lr.data.data?lr.data.data.access_token:null;
  if(!token){log('LOGIN FAIL',lr.status,lr.text.slice(0,200));process.exit(2);}
  log('LOGIN OK platform='+PLATFORM);

  const cr=await call('POST','/api/v1/system/assistant',{token,body:{name:'storage-probe-'+UNIQ,domain:'storage-'+UNIQ+'.example.com',type:0,description:'probe'}});
  const id=cr.data&&cr.data.data?cr.data.data.id:null;
  if(!id){log('ASSISTANT CREATE FAIL',cr.status,cr.text.slice(0,200));process.exit(2);}
  log('ASSISTANT',id);

  const fd=new FormData();
  fd.append('data', JSON.stringify({id}));
  fd.append('files', new Blob([PNG],{type:'image/png'}), 'logo.png,logo');
  const up=await fetch(BASE+'/api/v1/system/assistant/ui',{method:'PATCH',headers:{'X-SQLBOT-TOKEN':'Bearer '+token},body:fd});
  const upText=await up.text();
  log('UPLOAD',up.status,upText.slice(0,120));
  if(up.status!==200){log('STORAGE_FAIL upload');process.exit(1);}

  const list=await call('GET','/api/v1/system/assistant',{token});
  const rows=(list.data&&list.data.data)||[];
  const mine=rows.find(r=>String(r.id)===String(id));
  let fileId=null;
  if(mine&&mine.configuration){
    try{const cfg=typeof mine.configuration==='string'?JSON.parse(mine.configuration):mine.configuration;fileId=cfg.logo||null;}catch(e){}
  }
  log('FILE_ID',fileId);
  if(!fileId){log('STORAGE_FAIL file id missing');process.exit(1);}

  const pic=await fetch(BASE+'/api/v1/system/assistant/picture/'+fileId,{headers:{'X-SQLBOT-TOKEN':'Bearer '+token}});
  const buf=Buffer.from(await pic.arrayBuffer());
  const same=pic.status===200&&buf.equals(PNG);
  log('READBACK',pic.status,'bytes='+buf.length,'contentMatch='+same);

  // 替换 logo → 旧文件应被删除（存储层 delete 生效）
  const PNG2 = Buffer.concat([PNG, Buffer.from([0])]);
  const fd2=new FormData();
  fd2.append('data', JSON.stringify({id}));
  fd2.append('files', new Blob([PNG2],{type:'image/png'}), 'logo2.png,logo');
  const up2=await fetch(BASE+'/api/v1/system/assistant/ui',{method:'PATCH',headers:{'X-SQLBOT-TOKEN':'Bearer '+token},body:fd2});
  log('UPLOAD2',up2.status);
  const gone=await fetch(BASE+'/api/v1/system/assistant/picture/'+fileId,{headers:{'X-SQLBOT-TOKEN':'Bearer '+token}});
  log('OLD_FILE_AFTER_REPLACE',gone.status,'(期望 404)');

  const del=await call('DELETE','/api/v1/system/assistant/'+id,{token});
  log('ASSISTANT DELETE',del.status);

  if(same && gone.status===404){log('RESULT STORAGE_OK platform='+PLATFORM);}
  else{log('RESULT STORAGE_FAIL platform='+PLATFORM);process.exit(1);}
}
main().catch(e=>{console.error('ERR',e);process.exit(2);});
