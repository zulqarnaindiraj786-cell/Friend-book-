"""Cooperative, in-memory pagination workload. NO actual app/Firestore/FCM/media traffic."""
import asyncio, json, time
from pathlib import Path
async def session(number):
    # Models SDK cursor boundaries and dedup with deterministic timestamps/IDs.
    data=list(range(97)); queries=0
    for size in (7,10,10,50,30,20,40):
        cursor=0; seen=[]
        while cursor<len(data):
            page=data[cursor:cursor+size]; queries+=1
            assert len(page)<=size
            seen.extend(page); cursor+=len(page)
            await asyncio.sleep(0)
        assert seen==data and len(set(seen))==len(data)
    return queries
async def main():
    started=time.perf_counter(); results=await asyncio.gather(*(session(i) for i in range(5000)))
    report={'type':'IN_MEMORY_MODEL_ONLY_NOT_PRODUCTION_LOAD_TEST','virtual_sessions':len(results),'page_sizes':[7,10,10,50,30,20,40],
            'simulated_pages':sum(results),'duplicate_or_missing_records':0,'elapsed_model_seconds':round(time.perf_counter()-started,3),
            'real_backend_requests':0,'real_devices':0,'certified_5000_users':False}
    path=Path('docs/testing/paging-model-5000.json'); path.write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report))
if __name__=='__main__': asyncio.run(main())
