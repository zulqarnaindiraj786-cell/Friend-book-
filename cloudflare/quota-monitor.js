// Isolated observer: deliberately no D1 binding, SQL, mutations or auto-deployer.
const API='https://api.cloudflare.com/client/v4/graphql';
const ACCOUNT='92f3d04598822de2e49e14561218d090';
const DATABASE='e1c605ca-a50b-46c6-9deb-56dd1f70d163';
const json=(body,status=200)=>Response.json(body,{status,headers:{'Cache-Control':'no-store','X-Content-Type-Options':'nosniff'}});
function aggregate(payload,key,withCount=false) {
  if(payload?.errors?.length) throw Error('Analytics unavailable');
  const accounts=payload?.data?.viewer?.accounts;
  if(!Array.isArray(accounts)||accounts.length!==1) throw Error('Analytics unavailable');
  const groups=accounts[0][key];
  if(!Array.isArray(groups)||groups.length>1) throw Error('Invalid analytics');
  if(!groups.length)return null; // Missing data must never become zero usage.
  const row=groups[0];
  for(const k of ['rowsRead','rowsWritten'])if(!Number.isSafeInteger(row?.sum?.[k])||row.sum[k]<0)throw Error('Invalid analytics');
  if(withCount&&(!Number.isSafeInteger(row.count)||row.count<0))throw Error('Invalid analytics');
  return {rowsRead:row.sum.rowsRead,rowsWritten:row.sum.rowsWritten,...(withCount?{executions:row.count}:{})};
}
export function createQuotaMonitor(fetcher=fetch,clock=()=>Date.now()) {
  let cached=null,pending=null;
  async function collect(env,force=false) {
    if(!env.CF_ANALYTICS_TOKEN)throw Error('Analytics credential unavailable');
    if(!force&&cached&&clock()-cached.time>=0&&clock()-cached.time<60_000)return cached.report;
    if(pending)return pending;
    pending=(async()=>{
      const now=new Date(clock()),end=now.toISOString(),start=new Date(now.getTime()-15*60_000).toISOString(),day=end.slice(0,10);
      const query=`{ viewer { accounts(filter:{accountTag:"${ACCOUNT}"}) {
        daily:d1AnalyticsAdaptiveGroups(limit:1,filter:{date_geq:"${day}",date_leq:"${day}"}) { sum { rowsRead rowsWritten } }
        recent:d1QueriesAdaptiveGroups(limit:1,filter:{databaseId:"${DATABASE}",datetime_geq:"${start}",datetime_leq:"${end}"}) { sum { rowsRead rowsWritten } count }
      } } }`;
      const response=await fetcher(API,{method:'POST',headers:{Authorization:`Bearer ${env.CF_ANALYTICS_TOKEN}`,'Content-Type':'application/json'},body:JSON.stringify({query}),signal:AbortSignal.timeout(15_000)});
      if(!response.ok)throw Error('Analytics unavailable');
      const payload=await response.json();
      // Parse aliased GraphQL fields with the same strict whitelist.
      const daily=aggregate(payload,'daily'),recent=aggregate(payload,'recent',true);
      const exceeded=daily&&(daily.rowsRead>100_000||daily.rowsWritten>5_000);
      const report={schema_version:1,role:'observe_only',queried_at:end,
        status:daily?(exceeded?'TARGET_EXCEEDED':'UNVERIFIED_CURRENT_DAY'):'UNVERIFIED_NO_DAILY_DATA',
        acceptance_target_not_provider_limit:{rowsRead:100_000,rowsWritten:5_000},
        account_daily:{utc_date:day,usage:daily},database_recent:{start,end,usage:recent},
        automatic_fix_actions:[],future_quota_guarantee:false,
        caveat:'Delayed/adaptive analytics. Daily account and recent database query datasets differ. No device attribution or guaranteed quota balance. No app traffic is blocked.'};
      cached={time:clock(),report};return report;
    })();
    try{return await pending;}finally{pending=null;}
  }
  async function authorized(request,env) {
    const header=request.headers.get('Authorization')||'';
    if(typeof env.REPORT_TOKEN!=='string'||env.REPORT_TOKEN.length<32||header.length>512)return false;
    const encode=s=>new TextEncoder().encode(s);
    const [a,b]=await Promise.all([crypto.subtle.digest('SHA-256',encode(header)),crypto.subtle.digest('SHA-256',encode(`Bearer ${env.REPORT_TOKEN}`))]);
    let diff=0;const x=new Uint8Array(a),y=new Uint8Array(b);for(let i=0;i<x.length;i++)diff|=x[i]^y[i];return diff===0;
  }
  return {
    async fetch(request,env) {
      const path=new URL(request.url).pathname;
      if(path==='/health')return request.method==='GET'?json({ok:Boolean(env.CF_ANALYTICS_TOKEN&&env.REPORT_TOKEN),role:'observe_only',d1_bound:false},env.CF_ANALYTICS_TOKEN&&env.REPORT_TOKEN?200:503):json({error:'Method not allowed'},405);
      if(path!=='/report')return json({error:'Not found'},404);
      if(request.method!=='GET')return json({error:'Method not allowed'},405);
      if(!await authorized(request,env))return json({error:'Unauthorized'},401);
      try{return json(await collect(env));}catch{return json({status:'UNVERIFIED_ANALYTICS_UNAVAILABLE',future_quota_guarantee:false},503);}
    },
    async scheduled(event,env) {
      try{const report=await collect(env,true);console.log(JSON.stringify({event:'quota_monitor_report',report}));}
      catch{console.error(JSON.stringify({event:'quota_monitor_report',status:'UNVERIFIED_ANALYTICS_UNAVAILABLE',future_quota_guarantee:false}));}
    },
  };
}
export default createQuotaMonitor();
