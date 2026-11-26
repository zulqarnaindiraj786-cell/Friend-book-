import unittest
from scripts.loadtest.canonical_mirror_archive_preflight import inspect, REQUIRED, ARCHIVE, MAX_ROWS


class MirrorArchivePreflightTests(unittest.TestCase):
    def test_accepts_bounded_mirror_with_both_retained_snapshots(self):
        calls=[]
        def query(sql, params):
            calls.append((sql,params))
            if 'sqlite_master' in sql:
                return ([{'name':name} for name in REQUIRED],{'rows_read':5})
            return ([{'n':336}],{'rows_read':336})
        result=inspect(query)
        self.assertEqual(result['live_mirror_rows'],336)
        self.assertEqual(result['rows_read'],341)
        self.assertEqual(calls[0][1],(*REQUIRED,ARCHIVE))
        self.assertLessEqual(result['max_rows'],MAX_ROWS)

    def test_refuses_large_table_or_existing_archive(self):
        for rows, archive in ((MAX_ROWS+1,False),(1,True)):
            def query(sql, params):
                if 'sqlite_master' in sql:
                    names=list(REQUIRED)+([ARCHIVE] if archive else [])
                    return ([{'name':name} for name in names],{'rows_read':5})
                return ([{'n':rows}],{'rows_read':rows})
            with self.assertRaises(ValueError): inspect(query)


if __name__=='__main__': unittest.main()
