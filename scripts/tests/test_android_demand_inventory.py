import importlib.util
from pathlib import Path
import tempfile
import unittest
ROOT=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('demand_audit',ROOT/'scripts/audit_android_demand.py')
p=importlib.util.module_from_spec(spec);spec.loader.exec_module(p)
class DemandInventory(unittest.TestCase):
 def test_scans_nested_java_and_marks_only_review_candidates(self):
  with tempfile.TemporaryDirectory() as temp:
   root=Path(temp);(root/'nested').mkdir()
   (root/'nested/A.java').write_text('''// Fb.db().collection("not real");
class A {
 void f(){ D1Client.request("posts","p","reactions","GET",null,null); }
 void g(){ h.postDelayed(mediaPlaybackTimer,500); }
}
''')
   (root/'B.java').write_text('class B {}')
   result=p.inventory(root)
   self.assertEqual(result['java_files_scanned'],2)
   self.assertFalse(result['all_app_fixed'])
   self.assertEqual(result['category_line_counts']['document_store_entry'],0)
   self.assertEqual(result['category_line_counts']['engagement_transport'],1)
   self.assertEqual(result['category_line_counts']['delayed_or_periodic_candidate'],1)
   self.assertNotIn('quota',result['category_line_counts'])
   self.assertEqual(result['findings'][0]['file'],'nested/A.java')
