import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {createQuotaMonitor} from '../quota-monitor.js';
const env={CF_ANALYTICS_TOKEN:'private-analytics-credential',REPORT_TOKEN:'private-report-credential-which-is-long-enough'};
const request=(path='/report',token=env.REPORT_TOKEN)=>new Request('https://monitor.test'+path,{headers:{Authorization:'Bearer '+token}});
const payload=(daily={rowsRead:100001,rowsWritten:1},recent={rowsRead:20,rowsWritten:0})=>({data:{viewer:{accounts:[{daily:daily?[{sum:daily}]:[],recent:recent?[{sum:recent,count:3}]:[]}]}}});

test('monitor fetches only GraphQL, sanitizes data, coalesces and caches authenticated reports',async()=>{
 let calls=0,now=Date.parse('2026-11-23T05:19:12Z');
 const monitor=createQuotaMonitor(async(url,init)=>{
  calls++;assert.equal(url,'https://api.cloudflare.com/client/v4/graphql');assert.equal(init.method,'POST');
  const body=JSON.parse(init.body);assert.ok(!body.query.includes('dimensions'));assert.ok(!body.query.includes('mutation'));
  assert.equal(init.headers.Authorization,'Bearer '+env.CF_ANALYTICS_TOKEN);
  const p=payload();p.data.viewer.accounts[0].private_id='must-not-leak';return Response.json(p);
 },()=>now);
 const responses=await Promise.all([monitor.fetch(request(),env),monitor.fetch(request(),env)]);
 assert.equal(calls,1);
 const body=await responses[0].json();assert.equal(body.status,'TARGET_EXCEEDED');assert.equal(body.future_quota_guarantee,false);
 assert.equal(body.role,'observe_only');assert.deepEqual(body.automatic_fix_actions,[]);
 assert.ok(!JSON.stringify(body).includes('must-not-leak'));assert.ok(!JSON.stringify(body).includes(env.CF_ANALYTICS_TOKEN));
 assert.equal((await monitor.fetch(request(),env)).status,200);assert.equal(calls,1);
 now+=61000;await monitor.fetch(request(),env);assert.equal(calls,2);
});

test('auth, methods, unknown paths and unconfigured secrets never fetch analytics',async()=>{
 let calls=0;const monitor=createQuotaMonitor(async()=>{calls++;throw Error('must not fetch');});
 assert.equal((await monitor.fetch(request('/report','wrong'),env)).status,401);
 assert.equal((await monitor.fetch(new Request('https://monitor.test/report'),env)).status,401);
 assert.equal((await monitor.fetch(new Request('https://monitor.test/report',{method:'POST'}),env)).status,405);
 assert.equal((await monitor.fetch(request('/not-found'),env)).status,404);
 assert.equal((await monitor.fetch(request(),{})).status,401);
 assert.equal((await monitor.fetch(request('/health'),{})).status,503);
 assert.equal((await monitor.fetch(request('/health'),env)).status,200);
 assert.equal(calls,0);
});

test('missing, invalid and rejected analytics are never treated as a successful zero',async()=>{
 for(const p of [payload(null,null),payload({rowsRead:1,rowsWritten:0})]){
  const monitor=createQuotaMonitor(async()=>Response.json(p));const body=await(await monitor.fetch(request(),env)).json();
  assert.match(body.status,/UNVERIFIED/);assert.equal(body.future_quota_guarantee,false);
 }
 for(const p of [{errors:[{message:'private-error'}]},payload({rowsRead:-1,rowsWritten:0}),payload({rowsRead:true,rowsWritten:0})]){
  const monitor=createQuotaMonitor(async()=>Response.json(p));const res=await monitor.fetch(request(),env);
  assert.equal(res.status,503);assert.ok(!JSON.stringify(await res.json()).includes('private-error'));
 }
});

test('scheduled reporting logs aggregate report and failure safely without app mutations',async t=>{
 const logs=[],errors=[];t.mock.method(console,'log',s=>logs.push(JSON.parse(s)));t.mock.method(console,'error',s=>errors.push(JSON.parse(s)));
 const good=createQuotaMonitor(async()=>Response.json(payload()));await good.scheduled({},env);
 assert.equal(logs[0].event,'quota_monitor_report');assert.equal(logs[0].report.status,'TARGET_EXCEEDED');
 const bad=createQuotaMonitor(async()=>{throw Error(env.CF_ANALYTICS_TOKEN);});await bad.scheduled({},env);
 assert.equal(errors[0].status,'UNVERIFIED_ANALYTICS_UNAVAILABLE');assert.ok(!JSON.stringify(errors).includes(env.CF_ANALYTICS_TOKEN));
});

test('isolated config has no application storage or service bindings and leaves main cron intact',()=>{
 const cfg=readFileSync(new URL('../../wrangler.quota-monitor.toml',import.meta.url),'utf8');
 assert.match(cfg,/name = "friendbook-quota-monitor"/);assert.match(cfg,/crons = \["\*\/15 \* \* \* \*"\]/);
 assert.ok(!/\[\[?(?:d1_databases|r2_buckets|kv_namespaces|services|durable_objects|migrations|assets)/.test(cfg));
 const production=readFileSync(new URL('../../wrangler.toml',import.meta.url),'utf8');
 assert.match(production,/crons = \["\*\/15 \* \* \* \*", "0 \* \* \* \*"\]/);assert.match(production,/D1_QUOTA_HARD_STOP = "false"/);
});
