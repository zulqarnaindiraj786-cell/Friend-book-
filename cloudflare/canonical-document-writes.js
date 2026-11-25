const LEGACY_TABLE = 'friendbook_cf_documents';
const MIRROR_DML = /^\s*(INSERT(?:\s+OR\s+\w+)?\s+INTO|REPLACE\s+INTO|UPDATE(?:\s+OR\s+\w+)?\s+)\s*["`\[]?friendbook_cf_documents\b/i;

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
 * Wrap a D1 binding for an explicitly staged canonical-write rollout.
 * Reads and deletes are unchanged; only legacy mirror INSERT/UPDATE statements
 * are omitted. Canonical writes in the same D1 batch remain atomic. This does
 * not make old mirror-only data safe to discard and is opt-in by design.
 */
export function canonicalDocumentWriteDb(db) {
  if (!db || typeof db.prepare!=='function') throw new TypeError('D1 binding required');
  return new Proxy(db,{
    get(target,key){
      if (key==='__canonicalDocumentWrites') return true;
      if (key==='prepare') return sql=>MIRROR_DML.test(String(sql))?skippedStatement():target.prepare.call(target,sql);
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
