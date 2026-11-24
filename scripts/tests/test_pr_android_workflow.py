from pathlib import Path
import unittest
import os
import subprocess
import tempfile
ROOT=Path(__file__).resolve().parents[2]
class AndroidPrWorkflow(unittest.TestCase):
    def test_pr_compiles_and_tests_without_packaging_and_uses_verified_runtime(self):
        code=(ROOT/'.github/workflows/debug-apk.yml').read_text().split('  signed-candidate:',1)[0]
        self.assertIn("github.event_name == 'pull_request' ||",code)
        self.assertEqual(code.count("github.event_name != 'pull_request' &&"),2)
        self.assertIn('bash scripts/ci/fetch_robolectric_jars.sh',code)
        self.assertIn('robolectric.dependency.repo.url=https://repo.maven.apache.org/maven2',code)
        fetch=(ROOT/'scripts/ci/fetch_robolectric_jars.sh').read_text()
        for token in ['sha512sum --check --status','--retry-all-errors','15-robolectric','9-robolectric',
                      'repo1.maven.org/maven2','maven-central.storage-download.googleapis.com/maven2']:
            self.assertIn(token,fetch)
        self.assertIn('"$hash" "$jar.tmp" | sha512sum --check --status', fetch)

    def test_mirror_fallback_never_accepts_a_corrupt_runtime(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp)
            fake=root/'curl'
            fake.write_text("""#!/usr/bin/env python3
import hashlib, os, sys
from pathlib import Path
args=sys.argv[1:]
url=next(a for a in args if a.startswith('https://'))
out=Path(args[args.index('-o')+1])
with open(os.environ['CURL_LOG'],'a') as log: log.write(url+'\\n')
good=b'verified-runtime'
if url.endswith('.sha512'): out.write_text(hashlib.sha512(good).hexdigest())
elif 'repo.maven.apache.org' in url or os.environ.get('ALL_BAD')=='1': out.write_bytes(b'bad')
else: out.write_bytes(good)
""")
            fake.chmod(0o755)
            env={**os.environ,'HOME':str(root/'home'),'PATH':str(root)+os.pathsep+os.environ['PATH'],'CURL_LOG':str(root/'calls')}
            script=str(ROOT/'scripts/ci/fetch_robolectric_jars.sh')
            success=subprocess.run(['bash',script],env=env,capture_output=True,text=True)
            self.assertEqual(success.returncode,0,success.stderr+success.stdout)
            calls=(root/'calls').read_text()
            self.assertIn('repo1.maven.org/maven2',calls)
            for jar in (root/'home').rglob('*.jar'):
                self.assertEqual(jar.read_bytes(),b'verified-runtime')
            env.update(HOME=str(root/'bad-home'),ALL_BAD='1')
            failed=subprocess.run(['bash',script],env=env,capture_output=True,text=True)
            self.assertNotEqual(failed.returncode,0)
            self.assertIn('Could not obtain a verified Robolectric jar',failed.stdout)
            self.assertEqual(list((root/'bad-home').rglob('*.jar')),[])
