from pathlib import Path
import unittest
ROOT=Path(__file__).resolve().parents[2]
class ArchiveQueryTests(unittest.TestCase):
 def test_archive_and_trash_have_explicit_owner_scoped_queries(self):
  s=(ROOT/'app/src/main/java/com/friendbook/social/settings/ArchiveTrashActivity.java').read_text()
  self.assertIn('whereEqualTo("authorId",me)',s)
  self.assertIn('whereEqualTo("archived",true)',s)
  self.assertIn('whereGreaterThan("trashedAt",0L)',s)
  self.assertIn('Tasks.whenAllComplete(tasks)',s)
if __name__=='__main__': unittest.main()
