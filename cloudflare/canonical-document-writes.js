const MIRROR_DML = /^\s*(INSERT(?:\s+OR\s+\w+)?\s+INTO|REPLACE\s+INTO|UPDATE(?:\s+OR\s+\w+)?\s+|DELETE\s+FROM)\s*["`\[]?friendbook_cf_documents\b/i;
const MIRROR_READ = /^\s*(?:SELECT|WITH)\b[\s\S]*?\bfriendbook_cf_documents\b/i;

function skippedStatement() {
  const result = Object.freeze({success:true,meta:Object.freeze({changes:0,rows_written:0})});
  const bound = Object.freeze({
    __canonicalMirrorSkipped:true,
    run: async()=>result,
    all: async()=>({results:[],success:true,meta:result.meta}),
    first: async()=>null,
    raw: async()=>[],
  });
  return Object.freeze({__canonicalMirrorSkipped:true,bind:()=>bound,run:bound.run,all:bound.all,first:bound.first,raw:bound.raw});
}

/**
 * Wrap a D1 binding for the canonical-only Worker rollout. Legacy mirror reads
 * fail closed and legacy mirror INSERT/REPLACE/UPDATE/DELETE statements are
 * omitted. Canonical writes in the same D1 batch remain atomic. The physical
 * table is retained until a separate, verified retirement migration.
 */
export function canonicalDocumentWriteDb(db) {
  if (!db || typeof db.prepare!=='function') throw new TypeError('D1 binding required');
  return new Proxy(db,{
    get(target,key){
      if (key==='__canonicalDocumentWrites') return true;
      if (key==='prepare') return sql=>{
        const text=String(sql);
        if (MIRROR_READ.test(text)) throw new Error('Legacy document mirror reads are disabled in canonical mode');
        return MIRROR_DML.test(text)?skippedStatement():target.prepare.call(target,sql);
      };
      if (key==='batch') return async input=>{
        if (!Array.isArray(input)) return target.batch.call(target,input);
        const active=[], slots=[];
        for (const statement of input) {
          if (statement?.__canonicalMirrorSkipped) slots.push(-1);
          else {slots.push(active.length);active.push(statement);}
        }
        const results=active.length?(await target.batch.call(target,active))||[]:[];
        let ri=0;
        return slots.map(slot=>slot<0?{success:true,meta:{changes:0,rows_written:0}}:results[ri++]);
      };
      if (key==='withSession') return typeof target.withSession==='function'
        ?(...args)=>canonicalDocumentWriteDb(target.withSession.apply(target,args))
        :undefined;
      const value=Reflect.get(target,key,target);
      return typeof value==='function'?value.bind(target):value;
    },
  });
}

/** Enable only after compatibility/backfill gates are independently verified. */
export function canonicalDocumentWriteEnv(env) {
  if (env?.DOCUMENT_MIRROR_MODE!=='canonical') return env;
  let db;
  return new Proxy(env,{get(target,key,receiver){
    if (key==='DB') return db ||= canonicalDocumentWriteDb(Reflect.get(target,key,receiver));
    return Reflect.get(target,key,receiver);
  }});
}
