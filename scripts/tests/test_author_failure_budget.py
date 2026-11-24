from pathlib import Path
import unittest
JAVA=Path(__file__).resolve().parents[2]/'app/src/main/java/com/friendbook/social'
class AuthorFailureBudget(unittest.TestCase):
    def test_failed_author_is_not_retried_by_rebind(self):
        s=(JAVA/'post/FeedAdapter.java').read_text()
        self.assertIn('requestedAuthors.add(String.valueOf(actor) + ":" + p.authorId)',s)
        self.assertNotIn('requestedAuthors.remove(',s)
        self.assertIn('hydrateMissingAuthors(list)',s)
        bind=s.split('private void bindPost',1)[1].split('String photo =',1)[0]
        self.assertNotIn('Fb.getUser',bind)
        self.assertIn('FieldPath.documentId(),chunk)',s)
    def test_explicit_refresh_allows_recovery(self):
        s=(JAVA/'main/HomeFragment.java').read_text()
        self.assertIn('if (explicitUserRefresh && adapter!=null) adapter.resetAuthorLookupAttempts()',s)
        s=(JAVA/'post/FeedAdapter.java').read_text()
        self.assertIn('resetAuthorLookupAttempts() { authorReadGeneration++; requestedAuthors.clear(); }',s)
