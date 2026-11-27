from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / 'app/src/main/java/com/friendbook/social'


class UserBatchProfileHydration(unittest.TestCase):
    def test_shared_lookup_is_bounded_cached_and_account_scoped(self):
        source = (JAVA / 'core/UserBatchLookup.java').read_text()
        self.assertIn('i+=10', source)
        self.assertIn('FieldPath.documentId(),chunk', source)
        self.assertIn('.limit(10)', source)
        self.assertIn('.getCachedOrFetch(false,300_000L)', source)
        self.assertIn('actor.equals(Fb.uid())', source)
        self.assertIn('FastCache.getUser(uid)', source)
        self.assertIn('FastCache.putUser(user)', source)

    def test_large_profile_lists_do_not_issue_one_point_read_per_user(self):
        checks = {
            'friends/BirthdaysActivity.java': 'loadUsers()',
            'settings/BlockedUsersActivity.java': 'UserBatchLookup.load(',
            'core/FriendPicker.java': 'UserBatchLookup.load(',
            'admin/frag/AdminReferralsFragment.java': 'UserBatchLookup.load(',
        }
        for relative, marker in checks.items():
            source = (JAVA / relative).read_text()
            self.assertIn(marker, source)
            self.assertNotIn('Fb.getUser(', source)
