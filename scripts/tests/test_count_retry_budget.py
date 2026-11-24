from pathlib import Path
import unittest
JAVA=Path(__file__).resolve().parents[2]/'app/src/main/java/com/friendbook/social'
class CountRetryBudget(unittest.TestCase):
    def test_retry_is_bounded_and_actor_guarded(self):
        s=(JAVA/'core/CountReads.java').read_text()
        self.assertIn('CountReadRetryPolicy.delayAfterFailure(attempts[0],error)',s)
        self.assertIn('if(delay<0L) { closed[0]=true;failed.accept(error);return; }',s)
        self.assertIn('actor==null || !Objects.equals(actor,Fb.uid())',s)
        self.assertIn('handler.removeCallbacks(retry)',s)
        self.assertNotIn('300_000L',s)
    def test_account_summary_cancels_and_deduplicates(self):
        s=(JAVA/'settings/SettingsActivity.java').read_text()
        self.assertIn('me == null || summaryLoading',s)
        self.assertIn('summaryCountReg=com.friendbook.social.core.CountReads.once',s)
        stop=s.split('@Override protected void onStop()',1)[1].split('private void storageDialog()',1)[0]
        self.assertIn('summaryCountReg.remove()',stop)
        self.assertIn('summaryLoading=false',stop)
        self.assertIn("Couldn't load account summary. Tap to retry.",s)

    def test_referral_reconciliation_is_only_started_by_verification_screen(self):
        main=(JAVA/'main/MainActivity.java').read_text()
        screen=(JAVA/'bluetick/BlueTickActivity.java').read_text()
        self.assertNotIn('watchReferralsForFreeTick',main)
        self.assertNotIn('referralHandler',main)
        self.assertIn('Fb.db().referralStatus()',screen)
        self.assertIn('generation!=screenGeneration',screen)
        self.assertIn('screenGeneration++',screen)
        self.assertNotIn('autoGrantByReferrals',screen)
