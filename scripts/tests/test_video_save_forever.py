import copy
from pathlib import Path
import unittest
from scripts.admin.configure_video_r2 import retained_rules
ROOT=Path(__file__).resolve().parents[2]

class VideoRetentionTest(unittest.TestCase):
    def test_remove_active_expiry_preserve_other_actions_and_original_config(self):
        original={'rules':[
            {'id':'old-expiry','enabled':True,'conditions':{'prefix':'videos/'},'deleteObjectsTransition':{'condition':{'type':'Age','maxAge':2592000}}},
            {'id':'mixed','enabled':True,'conditions':{'prefix':''},'deleteObjectsTransition':{},'abortMultipartUploadsTransition':{'condition':{'type':'Age','maxAge':604800}}},
            {'id':'disabled','enabled':False,'conditions':{},'deleteObjectsTransition':{}},
        ]}
        before=copy.deepcopy(original)
        updated,count=retained_rules(original)
        self.assertEqual(count,2);self.assertEqual(original,before)
        self.assertEqual(len(updated['rules']),2)
        self.assertIn('abortMultipartUploadsTransition',updated['rules'][0])
        self.assertNotIn('deleteObjectsTransition',updated['rules'][0])
        self.assertFalse(updated['rules'][1]['enabled'])
        self.assertEqual(retained_rules(updated)[1],0)

    def test_unknown_lifecycle_schema_cannot_wipe_rules(self):
        for data in ({}, {'rules':None}, {'rules':[{'id':'unknown'}]}):
            with self.assertRaises(ValueError):retained_rules(data)

    def test_video_bytes_have_no_age_deletion_or_total_storage_cap(self):
        source=(ROOT/'cloudflare/video-r2.js').read_text()
        self.assertNotIn('VIDEO_BUCKET.delete',source)
        self.assertNotIn('10_000_000_000',source)
        self.assertIn("onlyIf:{etagDoesNotMatch:'*'}",source)
        self.assertIn("retention:'forever'",source)
        self.assertIn("if(!ownTemporary(publicId))",source)
        self.assertIn('Cloudinary playback fallback',source)
        self.assertIn('R2VideoClient.uploadBlocking',(ROOT/'app/src/main/java/com/friendbook/social/core/Cloudinary.java').read_text())
