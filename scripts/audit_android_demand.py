"""Lexical whole-source demand inventory; findings are review leads, never runtime counts."""
import argparse
import json
import re
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
PATTERNS={
 'document_store_entry':r'Fb\.db\(|D1DocumentStore\.getInstance\(',
 'engagement_transport':r'D1Client\.(?:request|requestInteractive)\(',
 'snapshot_listener':r'\.addSnapshotListener\(',
 'explicit_server_read':r'\.get\((?:Source|AggregateSource)\.SERVER\)',
 'count_helper':r'CountReads\.once\(',
 'cache_first_list':r'\.getCachedOrFetch\(',
 'delayed_or_periodic_candidate':r'postDelayed\(|scheduleAtFixedRate\(|scheduleWithFixedDelay\(|TimerTask',
 'durable_work':r'enqueueUniqueWork\(|PeriodicWorkRequest',
 'raw_http_candidate':r'new URL\(|new OkHttpClient|\.openConnection\(',
}
def inventory(root):
 files=sorted(root.rglob('*.java'));findings=[]
 for file in files:
  for line,text in enumerate(file.read_text().splitlines(),1):
   if text.lstrip().startswith(('//','*','/*')):continue
   for category,pattern in PATTERNS.items():
    if re.search(pattern,text):findings.append({'file':str(file.relative_to(root)),'line':line,'category':category})
 return {'method':'whole Java tree lexical candidates, not AST/callgraph or observed HTTP/billing counts',
   'java_files_scanned':len(files),'all_app_fixed':False,
   'category_line_counts':{category:sum(f['category']==category for f in findings) for category in PATTERNS},
   'findings':findings}
if __name__=='__main__':
 parser=argparse.ArgumentParser();parser.add_argument('--output',type=Path,required=True);args=parser.parse_args()
 result=inventory(ROOT/'app/src/main/java/com/friendbook/social');args.output.parent.mkdir(parents=True,exist_ok=True)
 args.output.write_text(json.dumps(result,indent=2)+'\n');print(json.dumps({k:v for k,v in result.items() if k!='findings'}))
