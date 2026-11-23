import unittest
from pathlib import Path

JAVA = Path(__file__).resolve().parents[2] / 'app/src/main/java/com/friendbook/social'

class ReactionRetry(unittest.TestCase):
    def test_retry_reuses_absolute_target_and_reconciles_before_rollback(self):
        reactions=(JAVA/'post/D1Reactions.java').read_text()
        recovery=reactions.split('void recover(',1)[1].split('void failed(',1)[0]
        self.assertIn('sendTarget(target,1)',recovery)
        self.assertIn('Objects.equals(target.type,mine(data))',recovery)
        self.assertNotIn('queue.add(',recovery)
        self.assertIn('D1Client.isRetryableReactionFailure(e)',reactions)

    def test_ad_no_fill_has_one_visible_recovery_at_ten_seconds(self):
        ads=(JAVA/'core/AdsConfig.java').read_text()
        recovery=ads.split('void recoverWaitingSlot(',1)[1].split('void preloadMediatedAd(',1)[0]
        for token in ['feedRecoveryUsed', 'App.isForeground()', 'ConsentManager.canRequestAds(app)', 'REQUEST_BUDGET.allowSingleRecovery(feed)', 'FAIL_COOLDOWN_MS -']:
            self.assertIn(token,recovery)
        self.assertIn('FAIL_COOLDOWN_MS = 10_000L',ads)
