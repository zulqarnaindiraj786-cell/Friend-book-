import importlib.util
from pathlib import Path
import sqlite3
import sys
import unittest
from datetime import datetime, timezone

ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'scripts/admin'))
spec=importlib.util.spec_from_file_location('quota_copy',ROOT/'scripts/admin/quota_firestore_copy.py')
q=importlib.util.module_from_spec(spec);spec.loader.exec_module(q)

class LocalD1:
    def __init__(self):
        self.db=sqlite3.connect(':memory:');self.db.row_factory=sqlite3.Row
        for schema in q.SCHEMAS:self.db.execute(schema)
        self.db.commit()
    def query(self,sql,params=()):
        cur=self.db.execute(sql,params);result=[dict(row) for row in cur.fetchall()];self.db.commit();return result

class Response:
    status_code=200
    def __init__(self,body): self.body=body
    def json(self): return self.body
class Session:
    def __init__(self):self.calls=[]
    def get(self,url,**kwargs):self.calls.append(('get',url));return Response({'documents':[]})
    def post(self,url,**kwargs):self.calls.append(('post',url));return Response({'collectionIds':[]})

class FakeSource:
    def __init__(self):self.calls=[]
    def child_collections(self,path):
        self.calls.append(('children',path))
        if path==q.PREFIX:return ['users']
        if path==q.PREFIX+'/users/a':return ['posts']
        return []
    def page(self,path,token):
        self.calls.append(('page',path,token))
        if path.endswith('/users'):
            return ([{'name':path+'/a','createTime':'2026-01-01T00:00:00Z','fields':{'uid':{'stringValue':'a'}}}],None)
        return ([{'name':path+'/p','createTime':'2026-01-01T00:00:00Z','fields':{'x':{'integerValue':'1'}}}],None)

class Tests(unittest.TestCase):
    def test_durable_budget_is_shared_and_does_not_increase(self):
        db=LocalD1();now=lambda:datetime(2026,11,15,12,tzinfo=timezone.utc)
        b=q.Budget(db,20,now);self.assertEqual(b.reserve(10)['reserved'],10)
        self.assertEqual(q.Budget(db,40,now).reserve(10)['cap'],20)
        with self.assertRaises(q.BudgetExhausted):b.reserve(1)
        self.assertEqual(db.query('SELECT reserved FROM friendbook_cf_copy_read_budget')[0]['reserved'],20)
    def test_page_and_metadata_reserve_before_network_and_forbid_aggregates(self):
        db=LocalD1();session=Session();source=q.CappedSource(session,q.Budget(db,11))
        source.child_collections(q.PREFIX);source.page(q.PREFIX+'/users',None)
        with self.assertRaises(q.BudgetExhausted):source.page(q.PREFIX+'/posts',None)
        with self.assertRaises(q.CopyError):source.count(q.PREFIX+'/users')
        self.assertEqual(len(session.calls),2)
    def test_archive_copy_resumes_and_copies_nested_without_application_writes(self):
        db=LocalD1();source=FakeSource();result=q.copy_queue(source,db)
        self.assertEqual(result['pending'],0)
        self.assertEqual(db.query('SELECT count(*) n FROM friendbook_cf_fullcopy_archive')[0]['n'],2)
        self.assertEqual(db.query('SELECT count(*) n FROM friendbook_cf_fullcopy_queue')[0]['n'],2)
        self.assertFalse(db.query("SELECT name FROM sqlite_master WHERE name='friendbook_cf_firestore_documents'"))
        source.calls=[];q.copy_queue(source,db)
        self.assertEqual([c for c in source.calls if c[0]=='page'],[])
    def test_changed_source_fails_without_overwriting_archive(self):
        db=LocalD1();doc={'name':q.PREFIX+'/users/a','createTime':'x','fields':{'v':{'integerValue':'1'}}}
        q.archive(db,doc)
        with self.assertRaises(q.CopyError):q.archive(db,{**doc,'fields':{'v':{'integerValue':'2'}}})
        self.assertIn('integerValue":"1',db.query('SELECT document_json FROM friendbook_cf_fullcopy_archive')[0]['document_json'])
    def test_budget_uses_pacific_midnight(self):
        db=LocalD1()
        q.Budget(db,1,lambda:datetime(2026,11,15,7,59,tzinfo=timezone.utc)).reserve(1)
        q.Budget(db,1,lambda:datetime(2026,11,15,8,0,tzinfo=timezone.utc)).reserve(1)
        self.assertEqual(db.query('SELECT count(*) n FROM friendbook_cf_copy_read_budget')[0]['n'],2)

    def test_interruption_keeps_page_checkpoint_and_archived_rows(self):
        db=LocalD1()
        class Interrupted(FakeSource):
            def child_collections(self,path):
                if path.endswith('/users/a'):raise q.BudgetExhausted('limit')
                return super().child_collections(path)
        with self.assertRaises(q.BudgetExhausted):q.copy_queue(Interrupted(),db)
        self.assertEqual(db.query("SELECT count(*) n FROM friendbook_cf_fullcopy_archive")[0]['n'],1)
        self.assertEqual(db.query("SELECT status FROM friendbook_cf_fullcopy_queue WHERE path=?",(q.PREFIX+'/users',))[0]['status'],'pending')
        self.assertEqual(q.copy_queue(FakeSource(),db)['pending'],0)
        self.assertEqual(db.query('SELECT count(*) n FROM friendbook_cf_fullcopy_archive')[0]['n'],2)

class ExtensionTests(unittest.TestCase):
    def test_one_time_extension_only_for_exhausted_current_pacific_day(self):
        db=LocalD1();now=lambda:datetime(2026,11,18,12,tzinfo=timezone.utc)
        base=q.Budget(db,10000,now);base.reserve(10000)
        day=q.extend_exhausted_budget_today(db,now)
        self.assertEqual(day,'2026-11-18')
        self.assertEqual(q.extend_exhausted_budget_today(db,now),day)  # idempotent, never 20k
        self.assertEqual(db.query('SELECT cap FROM friendbook_cf_copy_read_budget')[0]['cap'],15000)
        with self.assertRaises(q.CopyError):q.Budget(db,15000,now)
        ext=q.Budget(db,15000,now,allowed_day=day)
        self.assertEqual(ext.reserve(5000)['reserved'],15000)
        with self.assertRaises(q.BudgetExhausted):ext.reserve(1)
        next_day=q.Budget(db,15000,lambda:datetime(2026,11,19,12,tzinfo=timezone.utc),allowed_day=day)
        with self.assertRaises(q.BudgetExhausted):next_day.reserve(1)
        self.assertEqual(len(db.query('SELECT * FROM friendbook_cf_copy_read_budget')),1)
    def test_extension_rejects_nonexhausted_or_another_day_without_network(self):
        db=LocalD1();now=lambda:datetime(2026,11,18,12,tzinfo=timezone.utc)
        q.Budget(db,10000,now).reserve(9999)
        with self.assertRaises(q.CopyError):q.extend_exhausted_budget_today(db,now)
        with self.assertRaises(q.CopyError):q.extend_exhausted_budget_today(db,lambda:datetime(2026,11,19,12,tzinfo=timezone.utc))
        self.assertEqual(db.query('SELECT cap FROM friendbook_cf_copy_read_budget')[0]['cap'],10000)

    def test_second_extension_preserves_usage_is_idempotent_and_limited_to_authorized_day(self):
        db=LocalD1(); now=lambda:datetime(2026,11,18,12,tzinfo=timezone.utc)
        q.Budget(db,10000,now).reserve(10000)
        day=q.extend_exhausted_budget_today(db,now)
        q.Budget(db,15000,now,allowed_day=day).reserve(4995)
        self.assertEqual(q.extend_15k_budget_on_day(db,'2026-11-18',now),day)
        self.assertEqual(q.extend_15k_budget_on_day(db,'2026-11-18',now),day)
        budget=q.Budget(db,20000,now,allowed_day=day)
        self.assertEqual(budget.reserve(5005)['reserved'],20000)
        with self.assertRaises(q.BudgetExhausted):budget.reserve(1)
        with self.assertRaises(q.CopyError):q.extend_15k_budget_on_day(db,'2026-11-18',lambda:datetime(2026,11,19,12,tzinfo=timezone.utc))
        with self.assertRaises(q.BudgetExhausted):q.Budget(db,20000,lambda:datetime(2026,11,19,12,tzinfo=timezone.utc),allowed_day=day).reserve(1)
        self.assertEqual(len(db.query('SELECT * FROM friendbook_cf_copy_read_budget')),1)

    def test_second_extension_rejects_wrong_budget_and_date_without_source_requests(self):
        db=LocalD1(); now=lambda:datetime(2026,11,18,12,tzinfo=timezone.utc)
        q.Budget(db,10000,now).reserve(10000)
        with self.assertRaises(q.CopyError):q.extend_15k_budget_on_day(db,'2026-11-18',now)
        with self.assertRaises(q.CopyError):q.extend_15k_budget_on_day(db,'2026-11-17',now)
        self.assertEqual(db.query('SELECT cap FROM friendbook_cf_copy_read_budget')[0]['cap'],10000)

    def test_third_extension_once_only_and_strict_pacific_date(self):
        db=LocalD1(); now=lambda:datetime(2026,11,18,12,tzinfo=timezone.utc)
        db.query('INSERT INTO friendbook_cf_copy_read_budget VALUES (?,?,?)',('2026-11-18',19999,20000))
        day=q.extend_20k_budget_on_day(db,'2026-11-18',now)
        self.assertEqual(q.extend_20k_budget_on_day(db,'2026-11-18',now),day)
        b=q.Budget(db,30000,now,allowed_day=day)
        self.assertEqual(b.reserve(10001)['reserved'],30000)
        with self.assertRaises(q.BudgetExhausted): b.reserve(1)
        with self.assertRaises(q.CopyError): q.extend_20k_budget_on_day(db,'2026-11-18',lambda:datetime(2026,11,19,12,tzinfo=timezone.utc))
        with self.assertRaises(q.BudgetExhausted): q.Budget(db,30000,lambda:datetime(2026,11,19,12,tzinfo=timezone.utc),allowed_day=day).reserve(1)
        self.assertEqual(len(db.query('SELECT * FROM friendbook_cf_copy_read_budget')),1)

    def test_third_extension_rejects_wrong_cap_day_or_unexhausted(self):
        db=LocalD1();now=lambda:datetime(2026,11,18,12,tzinfo=timezone.utc)
        db.query('INSERT INTO friendbook_cf_copy_read_budget VALUES (?,?,?)',('2026-11-18',18000,20000))
        with self.assertRaises(q.CopyError): q.extend_20k_budget_on_day(db,'2026-11-18',now)
        with self.assertRaises(q.CopyError): q.extend_20k_budget_on_day(db,'2026-11-17',now)
        db.query('UPDATE friendbook_cf_copy_read_budget SET cap=15000,reserved=14999')
        with self.assertRaises(q.CopyError): q.extend_20k_budget_on_day(db,'2026-11-18',now)
