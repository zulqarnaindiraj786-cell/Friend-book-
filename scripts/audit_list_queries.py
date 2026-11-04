"""Conservative inventory, NOT a proof of bounded queries. Document reads need no limit.
Variable-built queries/helpers require manual tracing; do not automatically patch business logic.
"""
import re,json
from pathlib import Path
root=Path('app/src/main/java/com/friendbook/social')
findings=[]
for file in root.rglob('*.java'):
    source=file.read_text()
    for match in re.finditer(r'(?:Fb\.db\(\)|FirebaseFirestore\.getInstance\(\))\.collection\([^;]+?(?:\.get\(\)|\.addSnapshotListener\()',source,re.S):
        chain=match.group(); collections=[m.start() for m in re.finditer(r'\.collection\(',chain)]; documents=[m.start() for m in re.finditer(r'\.document\(',chain)]
        if not collections or (documents and documents[-1]>collections[-1]):continue
        if '.limit(' in chain or '.limitToLast(' in chain:continue
        findings.append({'file':str(file),'line':source.count('\n',0,match.start())+1,'kind':'collection query needs manual limit/cursor review','query':chain[:280]})
report={'method':'heuristic lexical inventory; variable-built/helper queries need manual review','all_app_bounded':False,'findings':findings}
Path('docs/testing/list-query-audit.json').write_text(json.dumps(report,indent=2)+'\n')
print(f'{len(findings)} candidate collection queries still require manual review; NOT all-app bounded certification')
