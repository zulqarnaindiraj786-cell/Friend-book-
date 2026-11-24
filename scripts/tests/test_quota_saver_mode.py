from pathlib import Path
import unittest

ROOT=Path(__file__).resolve().parents[2]
JAVA=ROOT/'app/src/main/java/com/friendbook/social'

class QuotaSaverMode(unittest.TestCase):
 def test_global_config_is_admin_only_and_opt_in(self):
  server=(ROOT/'cloudflare/firestore-documents.js').read_text()
  self.assertIn("'quota_saver_v1'",server)
  self.assertIn("data.quota_saver_v1!==undefined && typeof data.quota_saver_v1!=='boolean'",server)
  panel=(JAVA/'admin/AdminPanelActivity.java').read_text()
  self.assertIn('if (!Admin.isAdmin())',panel)
  self.assertIn('AdsConfig.saveQuotaSaver(on,this)',panel)
  ads=(JAVA/'core/AdsConfig.java').read_text()
  self.assertIn('QuotaSaver.setEnabled(quotaSaverEnabled)',ads)
  self.assertIn('if(!Admin.isAdmin())return;',ads)
 def test_only_automatic_foreground_pages_use_longer_cache(self):
  saver=(JAVA/'core/QuotaSaver.java').read_text()
  self.assertIn('15L*60L*1000L',saver)
  pager=(JAVA/'core/CursorPager.java').read_text()
  method=pager.split('public void startCachedForForeground',1)[1].split('private void startLocal',1)[0]
  self.assertIn('QuotaSaver.automaticPageMaxAge',method)
  self.assertNotIn('QuotaSaver',pager.split('public void refreshOnDemand()',1)[1].split('public void startCachedDay()',1)[0])
  self.assertIn('public void refreshOnDemand() { reuseExactPages=true; startFullRefresh(); }',pager)
  self.assertIn('void next()',pager)
