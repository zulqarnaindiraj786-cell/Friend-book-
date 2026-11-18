"""Surface bounded synthetic-test/build failures without printing env or credentials."""
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

def escaped(value):
    return str(value).replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A')

def report(root, log):
    emitted = 0
    for path in sorted(Path(root).rglob('TEST-*.xml')):
        try:
            suite = ET.parse(path).getroot()
        except (ET.ParseError, OSError):
            continue
        for case in suite.iter('testcase'):
            for failure in list(case.findall('failure')) + list(case.findall('error')):
                title = case.get('classname', '') + '.' + case.get('name', '')
                detail = (failure.get('message', '') + '\n' + (failure.text or ''))[:1600]
                print(f'::error title={escaped(title)}::{escaped(detail)}')
                emitted += 1
                if emitted == 8:
                    return
    if not emitted and Path(log).exists():
        lines = Path(log).read_text(errors='replace').splitlines()
        # Gradle AAR/dependency failures often put the actual cause on lines
        # without the words "error"/"Exception". Preserve bounded causal context.
        for index, line in enumerate(lines):
            if '* What went wrong:' in line:
                context = []
                for detail in lines[index + 1:index + 26]:
                    if detail.startswith(('* Try:', '* Exception is:')):
                        break
                    if detail.strip():
                        context.append(detail)
                if context:
                    print('::error title=Gradle failure cause::' + escaped('\n'.join(context)[:6000]))
                    return
        # Fallback: emit the last 25 non-empty lines so the true cause is always visible.
        for line in [l for l in lines if l.strip()][-25:]:
            print('::error title=Gradle log tail::' + escaped(line[:500]))
    else:
        print('::error::Build failed without a parsed test failure; inspect the build log in GitHub.')

if __name__ == '__main__':
    log = sys.argv[2] if len(sys.argv) > 2 else 'gradle.log'
    report(sys.argv[1] if len(sys.argv) > 1 else 'app/build/test-results', log)
