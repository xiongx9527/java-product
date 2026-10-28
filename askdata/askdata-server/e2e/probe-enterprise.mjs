import { createCipheriv } from 'crypto';
const BASE = process.env.BASE || 'http://127.0.0.1:18099';
const ADMIN = 'admin'; const ADMIN_PWD = 'SQLBot@123456';
const UNIQ = Date.now();
function enc(plain){const c=createCipheriv('aes-128-ecb',Buffer.from('SQLBot1234567890','utf8'),null);return Buffer.concat([c.update(Buffer.from(plain,'utf8'),'utf8'),c.final()]).toString('base64');}
async function call(method,path,opts){opts=opts||{};const h={'Content-Type':'application/json'};if(opts.token)h['X-SQLBOT-TOKEN']='Bearer '+opts.token;let u=BASE+path;if(opts.query)u+=(u.indexOf('?')>=0?'&':'?')+new URLSearchParams(opts.query).toString();const r=await fetch(u,{method,headers:h,body:opts.body!==undefined?JSON.stringify(opts.body):undefined});const t=await r.text();let d=null;try{d=t?JSON.parse(t):null;}catch(e){d=t;}return{status:r.status,data:d,text:t};}
async function upload(path,token,fields){const fd=new FormData();for(const k of Object.keys(fields))fd.append(k,fields[k]);const r=await fetch(BASE+path,{method:'POST',headers:{'X-SQLBOT-TOKEN':'Bearer '+token},body:fd});const t=await r.text();let d=null;try{d=t?JSON.parse(t):null;}catch(e){d=t;}return{status:r.status,data:d,text:t};}
const results=[];
const ok=(name,cond,detail)=>results.push({name,pass:!!cond,detail:String(detail||'').slice(0,180)});
const bodyOf=r=>JSON.stringify(r.data).slice(0,180);

async function main(){
  const lr=await call('POST','/api/v1/login/access-token',{query:{username:enc(ADMIN),password:enc(ADMIN_PWD)}});
  const token=lr.data&&lr.data.data?lr.data.data.access_token:null;
  if(!token){console.log('ADMIN LOGIN FAIL',lr.status,lr.text.slice(0,200));process.exit(2);}
  console.log('ADMIN LOGIN OK');

  // ---------- 用户 / 工作空间 ----------
  const info=await call('GET','/api/v1/user/info',{token});
  ok('用户信息 /user/info', info.status===200&&info.data&&info.data.data, bodyOf(info));
  const pager=await call('GET','/api/v1/user/pager/1/10',{token});
  ok('用户分页 /user/pager', pager.status===200, bodyOf(pager));

  const acc='e2e_ent_'+UNIQ;
  const cr=await call('POST','/api/v1/user',{token,body:{account:acc,name:'E2E Enterprise',email:acc+'@example.com',password:ADMIN_PWD,oid_list:[1]}});
  const uidB=cr.data&&cr.data.data?(cr.data.data.id||(cr.data.data.data&&cr.data.data.data.id)):null;
  ok('创建普通用户', cr.status===200&&uidB, bodyOf(cr));
  const lb=await call('POST','/api/v1/login/access-token',{query:{username:enc(acc),password:enc(ADMIN_PWD)}});
  const tokenB=lb.data&&lb.data.data?lb.data.data.access_token:null;
  ok('普通用户登录', !!tokenB, lb.status+' '+lb.text.slice(0,120));
  if(tokenB){
    const forbidden=await call('GET','/api/v1/system/parameter',{token:tokenB});
    ok('普通用户越权访问管理员接口返回 403', forbidden.status===403, 'status='+forbidden.status);
  }

  const wsList=await call('GET','/api/v1/system/workspace',{token});
  ok('工作空间列表', wsList.status===200&&Array.isArray(wsList.data&&wsList.data.data), bodyOf(wsList));
  const wsCr=await call('POST','/api/v1/system/workspace',{token,body:{name:'E2E WS '+UNIQ}});
  const wsId=wsCr.data&&wsCr.data.data?wsCr.data.data.id:null;
  ok('创建工作空间', wsCr.status===200&&wsId, bodyOf(wsCr));
  if(wsId){
    const wsGet=await call('GET','/api/v1/system/workspace/'+wsId,{token});
    ok('工作空间详情', wsGet.status===200, bodyOf(wsGet));
    const wsDel=await call('DELETE','/api/v1/system/workspace/'+wsId,{token});
    ok('删除工作空间', wsDel.status===200, bodyOf(wsDel));
  }

  // ---------- API Key ----------
  const akList=await call('GET','/api/v1/system/apikey',{token});
  ok('API Key 列表', akList.status===200, bodyOf(akList));
  const akCr=await call('POST','/api/v1/system/apikey',{token});
  const akId=akCr.data?akCr.data.data:null;
  ok('API Key 创建', akCr.status===200&&akId, bodyOf(akCr));
  if(akId){
    const akOff=await call('PUT','/api/v1/system/apikey/status',{token,body:{id:akId,status:false}});
    ok('API Key 停用', akOff.status===200, bodyOf(akOff));
    const akDel=await call('DELETE','/api/v1/system/apikey/'+akId,{token});
    ok('API Key 删除', akDel.status===200, bodyOf(akDel));
  }

  // ---------- 系统参数（multipart 往返） ----------
  const prm=await call('GET','/api/v1/system/parameter',{token});
  const items=prm.data&&Array.isArray(prm.data.data)?prm.data.data:[];
  ok('系统参数读取', prm.status===200&&items.length>0, 'count='+items.length);
  const chatPrm=await call('GET','/api/v1/system/parameter/chat',{token});
  ok('系统参数 chat 分组', chatPrm.status===200, bodyOf(chatPrm));
  if(items.length){
    const pkey=items[0].pkey; const pval=items[0].pval;
    const sv=await upload('/api/v1/system/parameter',token,{data:JSON.stringify([{pkey,pval}])});
    const after=await call('GET','/api/v1/system/parameter',{token});
    const same=(after.data.data||[]).find(x=>x.pkey===pkey);
    ok('系统参数保存往返', sv.status===200&&same&&String(same.pval)===String(pval), 'pkey='+pkey+' status='+sv.status);
  }

  // ---------- 审计 ----------
  const audit=await call('GET','/api/v1/system/audit/page/1/10',{token});
  ok('审计分页', audit.status===200, bodyOf(audit));
  const auditOpt=await call('GET','/api/v1/system/audit/get_options',{token});
  ok('审计筛选项', auditOpt.status===200, bodyOf(auditOpt));
  const auditExp=await call('GET','/api/v1/system/audit/export',{token});
  ok('审计导出', auditExp.status===200&&auditExp.text.length>0, 'bytes='+auditExp.text.length);

  // ---------- License / 认证 / 平台 ----------
  const lic=await call('GET','/api/v1/system/license',{token});
  ok('License 信息', lic.status===200, bodyOf(lic));
  const licVer=await call('GET','/api/v1/system/license/version',{});
  ok('License 版本（白名单免登录）', licVer.status===200, bodyOf(licVer));
  const authList=await call('GET','/api/v1/system/authentication',{token});
  ok('认证方式列表', authList.status===200, bodyOf(authList));
  const authStatus=await call('GET','/api/v1/system/authentication/status',{token});
  ok('认证状态', authStatus.status===200, bodyOf(authStatus));
  const authPlat=await call('GET','/api/v1/system/authentication/platform/status',{token});
  ok('认证平台状态', authPlat.status===200, bodyOf(authPlat));
  const saml=await call('GET','/api/v1/setting/authentication/info/saml',{token});
  ok('SAML 配置读取', saml.status===200, bodyOf(saml));
  const plat=await call('GET','/api/v1/system/platform',{token});
  ok('平台设置', plat.status===200, bodyOf(plat));
  const platCli=await call('GET','/api/v1/system/platform/client/1',{token});
  ok('平台客户端配置', platCli.status===200, bodyOf(platCli));

  // ---------- 嵌入应用 / 小助手 ----------
  const embCr=await call('POST','/api/v1/system/embedded',{token,body:{name:'E2E EMB '+UNIQ,domain:'e2e-'+UNIQ+'.example.com',description:'probe'}});
  const embId=embCr.data&&embCr.data.data?embCr.data.data.id:null;
  ok('嵌入应用创建', embCr.status===200&&embId, bodyOf(embCr));
  if(embId){
    const embPage=await call('GET','/api/v1/system/embedded/1/10',{token});
    ok('嵌入应用分页', embPage.status===200, bodyOf(embPage));
    const embGet=await call('GET','/api/v1/system/embedded/'+embId,{token});
    ok('嵌入应用详情', embGet.status===200, bodyOf(embGet));
    const embSec=await call('PATCH','/api/v1/system/embedded/secret/'+embId,{token});
    ok('嵌入应用密钥重置', embSec.status===200&&!!(embSec.data&&embSec.data.data), bodyOf(embSec));
    const embUp=await call('PUT','/api/v1/system/embedded',{token,body:{id:embId,name:'E2E EMB '+UNIQ+' v2',type:4,domain:'e2e-'+UNIQ+'.example.com'}});
    ok('嵌入应用更新', embUp.status===200, bodyOf(embUp));
    const embDel=await call('DELETE','/api/v1/system/embedded',{token,body:[embId]});
    ok('嵌入应用删除', embDel.status===200, bodyOf(embDel));
  }
  const asList=await call('GET','/api/v1/system/assistant',{token});
  ok('小助手列表', asList.status===200, bodyOf(asList));
  const asCr=await call('POST','/api/v1/system/assistant',{token,body:{name:'E2E AS '+UNIQ,domain:'as-'+UNIQ+'.example.com',type:0,description:'probe'}});
  const asId=asCr.data&&asCr.data.data?asCr.data.data.id:null;
  ok('小助手创建', asCr.status===200&&asId, bodyOf(asCr));
  if(asId){
    const asDs=await call('GET','/api/v1/system/assistant/ds',{token,query:{id:asId}});
    ok('小助手可用数据源', asDs.status===200, bodyOf(asDs));
    const asUp=await call('PUT','/api/v1/system/assistant',{token,body:{id:asId,name:'E2E AS '+UNIQ+' v2',type:0,domain:'as-'+UNIQ+'.example.com'}});
    ok('小助手更新', asUp.status===200, bodyOf(asUp));
    const asDel=await call('DELETE','/api/v1/system/assistant/'+asId,{token});
    ok('小助手删除', asDel.status===200, bodyOf(asDel));
  }

  // ---------- 数据源授权 / 行列权限 ----------
  const dsList=await call('GET','/api/v1/datasource/list',{token});
  const dss=(dsList.data&&dsList.data.data)||[];
  const ds=dss.find(x=>x.id&&Number(x.id)!==1)||dss[0];
  if(!ds){ok('数据源授权（前置）',false,'无可用数据源');}
  else{
    const dsId=ds.id;
    const grantSave=await call('POST','/api/v1/ds_grant/save',{token,body:{ds_id:dsId,user_ids:uidB?[uidB]:[]}});
    ok('数据源授权保存', grantSave.status===200, bodyOf(grantSave));
    const grantList=await call('POST','/api/v1/ds_grant/list',{token,body:{ds_id:dsId}});
    const grantRows=(grantList.data&&grantList.data.data)||[];
    ok('数据源授权列表含新用户', grantList.status===200&&(!uidB||grantRows.length>0), 'rows='+grantRows.length);
    const grantClear=await call('POST','/api/v1/ds_grant/save',{token,body:{ds_id:dsId,user_ids:[]}});
    ok('数据源授权清空', grantClear.status===200, bodyOf(grantClear));

    const tables=await call('POST','/api/v1/datasource/getTables/'+dsId,{token});
    const firstTable=(tables.data&&tables.data.data&&tables.data.data[0])||null;
    const permBody={name:'E2E RULE '+UNIQ,permissions:[{name:'e2e-row',type:'row',ds_id:dsId,ds_name:ds.name,table_id:firstTable?firstTable.id:null,table_name:firstTable?firstTable.tableName:null,permissions:'[]',expression_tree:'{}'}],users:uidB?[uidB]:[]};
    const permSave=await call('POST','/api/v1/ds_permission/save',{token,body:permBody});
    const ruleId=permSave.data?permSave.data.data:null;
    ok('行列权限规则保存', permSave.status===200&&ruleId, bodyOf(permSave));
    const permList=await call('POST','/api/v1/ds_permission/list',{token,body:{ds_id:dsId}});
    ok('行列权限规则列表', permList.status===200, bodyOf(permList));
    if(ruleId){
      const permDel=await call('POST','/api/v1/ds_permission/delete/'+ruleId,{token});
      ok('行列权限规则删除', permDel.status===200, bodyOf(permDel));
    }
  }

  if(uidB){const uDel=await call('DELETE','/api/v1/user/'+uidB,{token}); ok('删除测试用户', uDel.status===200, bodyOf(uDel));}

  console.log('\n===== ENTERPRISE RESULT =====');
  let fail=0;
  for(const r of results){ if(!r.pass) fail++; console.log((r.pass?'PASS':'FAIL')+'  '+r.name+(r.pass?'':'  <= '+r.detail)); }
  console.log('TOTAL '+results.length+'  FAIL '+fail);
  if(fail>0) process.exitCode=1;
}
main().catch(e=>{console.error('ERR',e);process.exit(2);});
