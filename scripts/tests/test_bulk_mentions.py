import unittest
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
JAVA=ROOT/'app/src/main/java/com/friendbook/social'
class BulkMentionGuards(unittest.TestCase):
    def test_picker_and_persistence(self):
        source=(JAVA/'core/Mentions.java').read_text()
        for token in ['"followers","highlight"','might receive notifications','44','isVerified','selectedUid','widthPixels','hasBulk']:
            self.assertIn(token,source)
        self.assertIn('uid.startsWith("bulk:")',source)
    def test_outbox_uses_separate_endpoint_and_no_recipient_list(self):
        push=(JAVA/'push/FCMPush.java').read_text()
        bulk=push[push.index('void bulkMention'):push.index('void friendPost')]
        self.assertIn('PushDeliveryWorker.enqueue',bulk)
        self.assertNotIn('target_user_id',bulk)
        self.assertIn('/functions/v1/bulk-mentions',(JAVA/'push/PushGateway.java').read_text())
    def test_server_and_atomic_daily_limit(self):
        source=(ROOT/'supabase/functions/bulk-mentions/index.ts').read_text()
        for token in ['jwtVerify','NO_PERSISTED_BULK_MENTION','FOLLOWERS_REQUIRES_POST_OWNER','BULK_PUBLIC_POST_REQUIRED','reserve_bulk_mention','putD1Document(`notifications/${id}`,values,token,true)','recipient.bulkMentionsEnabled===false','eligible.length>=20','highlightIds','recipient.following.includes(actor)']:
            self.assertIn(token,source)
        sql=(ROOT/'supabase/migrations/202611020001_bulk_mentions.sql').read_text()
        for token in ['pg_advisory_xact_lock','24 hours','enable row level security','to service_role']:
            self.assertIn(token,sql)
if __name__=='__main__':unittest.main()
