"""Ensure the retired user-facing trace cannot silently return."""
from pathlib import Path
import unittest
ROOT=Path(__file__).resolve().parents[2]
JAVA=ROOT/'app/src/main/java/com/friendbook/social'
class TraceRemoval(unittest.TestCase):
    def test_feature_and_hooks_removed_and_old_local_report_deleted(self):
        self.assertFalse((JAVA/'core/LoopTrace.java').exists())
        self.assertFalse((JAVA/'core/LoopTraceBuffer.java').exists())
        for rel in ['settings/SettingsActivity.java','core/CloudflareHttp.java']:
            self.assertNotIn('LoopTrace',(JAVA/rel).read_text())
        app=(JAVA/'App.java').read_text()
        self.assertNotIn('LoopTrace.screen',app)
        self.assertIn('deleteSharedPreferences("local_loop_trace")',app)
    def test_http_still_disables_redirect_and_retry_after_replay(self):
        s=(JAVA/'core/CloudflareHttp.java').read_text()
        for x in ['followRedirects(false)','followSslRedirects(false)','retryOnConnectionFailure(false)','chain.proceed(chain.request())','removeHeader("Retry-After")']:
            self.assertIn(x,s)
