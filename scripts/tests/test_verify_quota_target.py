import datetime as dt
import importlib.util
from pathlib import Path
import sys
import unittest

ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'scripts/loadtest'))
try:
    spec=importlib.util.spec_from_file_location('quota_verification',ROOT/'scripts/loadtest/verify_quota_target.py')
    p=importlib.util.module_from_spec(spec)
    spec.loader.exec_module(p)
finally:
    sys.path.pop(0)

UTC=dt.timezone.utc
NOW=dt.datetime(2026,11,23,12,tzinfo=UTC)
FIXES=dt.datetime(2026,11,23,5,19,12,tzinfo=UTC)


class StrictQuotaVerification(unittest.TestCase):
    def test_over_budget_is_fail_even_in_incomplete_day(self):
        r=p.evaluate_day(NOW.date(),{'rowsRead':469779,'rowsWritten':6379},NOW,FIXES)
        self.assertEqual(r['status'],'FAIL')
        self.assertEqual(r['over_target'],{'rowsRead':369779,'rowsWritten':1379})
        self.assertEqual(p.verdict([r]),'FAIL')

    def test_low_partial_day_never_passes(self):
        r=p.evaluate_day(NOW.date(),{'rowsRead':100,'rowsWritten':2},NOW,FIXES)
        self.assertEqual(r['status'],'UNVERIFIED')
        self.assertEqual(p.verdict([r]),'UNVERIFIED')

    def test_completed_day_straddling_fix_never_passes(self):
        now=NOW+dt.timedelta(days=1)
        r=p.evaluate_day(NOW.date(),{'rowsRead':100,'rowsWritten':2},now,FIXES)
        self.assertEqual(r['reason'],'day_predates_or_straddles_fixes')
        self.assertEqual(r['status'],'UNVERIFIED')

    def test_completed_post_fix_day_at_target_is_observed_not_guaranteed(self):
        day=NOW.date()+dt.timedelta(days=1)
        r=p.evaluate_day(day,{'rowsRead':100000,'rowsWritten':5000},NOW+dt.timedelta(days=2),FIXES)
        self.assertEqual(r['status'],'OBSERVED_WITHIN_TARGET')
        self.assertEqual(p.verdict([r]),'OBSERVED_WITHIN_TARGET')
        over=p.evaluate_day(day,{'rowsRead':100001,'rowsWritten':5000},NOW+dt.timedelta(days=2),FIXES)
        self.assertEqual(over['status'],'FAIL')

    def test_unavailable_is_not_zero_and_invalid_values_fail(self):
        r=p.evaluate_day(NOW.date(),None,NOW,FIXES)
        self.assertEqual(r['reason'],'analytics_unavailable_not_zero')
        self.assertEqual(p.verdict([r]),'UNVERIFIED')
        for usage in ({'rowsRead':True,'rowsWritten':0},{'rowsRead':-1,'rowsWritten':0},{}):
            with self.assertRaises(ValueError): p.evaluate_day(NOW.date(),usage,NOW,FIXES)
        with self.assertRaises(ValueError): p.evaluate_day(NOW.date()+dt.timedelta(days=1),None,NOW,FIXES)

    def test_pre_fix_failures_are_not_evidence_of_current_fix_acceptance(self):
        old=p.evaluate_day(NOW.date()-dt.timedelta(days=1),{'rowsRead':100001,'rowsWritten':0},NOW,FIXES)
        self.assertEqual(old['status'],'FAIL')
        self.assertEqual(p.verdict([old]),'UNVERIFIED')
