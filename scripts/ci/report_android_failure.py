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
        for line in Path(log).read_text(errors='replace').splitlines():
            if re.search(r'FAILED|FAILURE:|Exception|[Ee]rror|daemon disappeared|What went wrong|OutOfMemory', line):
                print('::error::' + escaped(line[:500]))
                emitted += 1
                if emitted == 8:
                    break
    if not emitted:
        print('::error::Build failed without a parsed test failure; inspect the build log in GitHub.')

if __name__ == '__main__':
    report('app/build/test-results', 'build-release.log')
