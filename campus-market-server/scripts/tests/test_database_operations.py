from pathlib import Path
from unittest import TestCase
import io,json,os,sys,tarfile,tempfile,time,unittest
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from database_accounts import render
from database_common import Database,private_file,identifier,sha,write_json
from database_backup import verify_bundle,restore,backup,safe_root
from database_reconcile import validate_bill,compare
from database_retention import plan,execute,RULES
ROOT=Path(__file__).resolve().parents[2]
class DatabaseOperationsTest(TestCase):
 def test_private_json_writes_are_atomic_and_private(self):
  with tempfile.TemporaryDirectory() as d:
   p=Path(d)/'status.json';write_json(p,{'state':'one'});write_json(p,{'state':'two'});self.assertEqual({'state':'two'},json.loads(p.read_text()));self.assertEqual(0,p.stat().st_mode&0o077);self.assertEqual([p],list(Path(d).iterdir()))
 def secrets(self):return {role:(role+'_'+'a'*30) for role in ['runtime','migrator','backup','maintenance']}
 def bill(self):return {'channel':'WECHAT','merchantScope':'fixture','billDate':'2026-10-24','entries':[{'kind':'PAYMENT','merchantReference':'order1','channelReference':'channel1','amountInCents':100,'currency':'CNY'}]}
 def test_credentials_need_private_regular_file(self):
  with tempfile.TemporaryDirectory() as d:
   p=Path(d)/'client.cnf';p.write_text('[client]\nuser=test');p.chmod(0o644)
   with self.assertRaises(ValueError):private_file(p)
   p.chmod(0o600);self.assertEqual(p,private_file(p));(Path(d)/'link').symlink_to(p)
   with self.assertRaises(ValueError):private_file(Path(d)/'link')
 def test_schema_cannot_inject_or_target_system(self):
  for value in ['mysql','campus_x;DROP DATABASE x','campus_x`','campus_']:
   with self.subTest(value=value),self.assertRaises(ValueError):identifier(value)
 def test_account_environment_and_credentials_separated(self):
  files=render('production',self.secrets(),ROOT);text=files['bootstrap.sql'];self.assertIn('campus_production_migrator',text);self.assertNotIn('GRANT ALL',text);self.assertNotIn('WITH GRANT OPTION',text);self.assertNotIn("'%'",text)
  for value in self.secrets().values():self.assertNotIn(value,files['runtime-after-migration.sql'])
 def test_runtime_cannot_mutate_ledger_and_has_no_ddl(self):
  sql=render('production',self.secrets(),ROOT)['runtime-after-migration.sql'];row=next(line for line in sql.splitlines() if '`finance_journals`' in line);self.assertIn('GRANT SELECT,INSERT ON',row);self.assertNotIn('UPDATE',row);self.assertNotIn('DELETE',row);self.assertNotIn('CREATE',sql);self.assertNotIn('ALTER',sql)
 def test_roles_cannot_share_password_or_use_sql(self):
  for values in [dict.fromkeys(self.secrets(),'same_'+'a'*30),dict(self.secrets(),runtime="bad'password")]:
   with self.assertRaises(ValueError):render('production',values,ROOT)
 def test_backup_refuses_bad_manifest_or_corruption(self):
  with tempfile.TemporaryDirectory() as d:
   root=Path(d).resolve();(root/'database.sql').write_bytes(b'fixture');(root/'images.tar').write_bytes(b'archive');m={'format':1,'status':'complete','databaseSha256':sha(root/'database.sql'),'imagesArchiveSha256':sha(root/'images.tar')};(root/'manifest.json').write_text(json.dumps(m));self.assertEqual(m,verify_bundle(root));(root/'database.sql').write_bytes(b'changed')
   with self.assertRaises(ValueError):verify_bundle(root)
 def test_restore_never_uses_existing_business_schema(self):
  class DB:schema='campus_production'
  with self.assertRaises(ValueError):restore(DB(),'/no','/no','/no')
 def test_restore_rejects_archive_traversal_before_writing_member(self):
  with tempfile.TemporaryDirectory() as d:
   root=Path(d).resolve();bundle=root/'backup';bundle.mkdir();(bundle/'database.sql').write_text('SELECT 1;')
   with tarfile.open(bundle/'images.tar','w') as t:
    entry=tarfile.TarInfo('../escape');entry.size=1;t.addfile(entry,io.BytesIO(b'x'))
   (bundle/'manifest.json').write_text(json.dumps({'format':1,'status':'complete','databaseSha256':sha(bundle/'database.sql'),'imagesArchiveSha256':sha(bundle/'images.tar'),'images':{'../escape':{'bytes':1,'sha256':'bad'}},'tables':[],'schema':'campus_test'}))
   class DB:
    schema='campus_restore_123456abcdef';env={}
    def run(self,*args,**kwargs):return ''
    def command(self):return ['/usr/bin/true']
   with self.assertRaises(ValueError):restore(DB(),bundle,root/'new','unused')
   self.assertFalse((root/'escape').exists())
 def test_bill_rejects_float_money_and_wrong_currency(self):
  for key,value in [('amountInCents',1.5),('amountInCents',True),('amountInCents',0),('currency','USD')]:
   b=self.bill();b['entries'][0][key]=value
   with self.assertRaises(ValueError):validate_bill(b)
 def test_bill_rejects_duplicate_and_unknown_fields(self):
  b=self.bill();b['entries']*=2
  with self.assertRaises(ValueError):validate_bill(b)
  b=self.bill();b['entries'][0]['password']='forbidden'
  with self.assertRaises(ValueError):validate_bill(b)
 def test_reconciliation_checks_missing_both_sides_and_amount(self):
  rows=[{'kind':'PAYMENT','merchantReference':'a','channelReference':None,'amountInCents':10},{'kind':'PAYMENT','merchantReference':'b','channelReference':None,'amountInCents':20},{'kind':'PAYMENT','merchantReference':'c','channelReference':None,'amountInCents':30}];local={('PAYMENT','a'):(10,None),('PAYMENT','b'):(99,None),('PAYMENT','d'):(40,None)};self.assertEqual(['MATCHED','AMOUNT_MISMATCH','MISSING_LOCAL','MISSING_PROVIDER'],[r['outcome'] for r in compare(rows,local)])
 def test_reconciliation_does_not_match_different_channel_transaction(self):
  row=self.bill()['entries'][0];result=compare([row],{('PAYMENT','order1'):(100,'different-channel-transaction')});self.assertEqual('TRANSACTION_MISMATCH',result[0]['outcome'])
 def test_real_bill_requires_channel_transaction_reference(self):
  b=self.bill();b['entries'][0]['channelReference']=None
  with self.assertRaises(ValueError):validate_bill(b)
 def test_retention_cannot_expand_to_financial_or_audit(self):
  self.assertEqual({'login_sessions','account_email_challenges','public_write_windows','notification_outbox','order_timeout_outbox'},set(RULES))
  for table in ['finance_journals','payment_attempts','order_events','account_restriction_events','product_images']:self.assertNotIn(table,RULES)
 def test_expired_or_tampered_plan_rejected_before_database_access(self):
  class DB:
   schema='campus_test'
   def rows(self,sql):return []
   def run(self,*args):raise AssertionError('Must not execute SQL')
  d=DB();p=plan(d);p['createdAtEpoch']-=10000
  with self.assertRaises(ValueError):execute(d,p,p['fingerprint'])
  p=plan(d);p['rows']['finance_journals']=[['1']]
  with self.assertRaises(ValueError):execute(d,p,p['fingerprint'])
 def test_plan_is_bounded_and_unread_notifications_preserved(self):
  class DB:
   schema='campus_test'
   def rows(self,sql):
    assert 'LIMIT 500' in sql
    if 'FROM notification_outbox' in sql:assert 'n.read_at<' in sql
    return []
  p=plan(DB());self.assertEqual(0,sum(p['counts'].values()));self.assertIn('unread notifications',p['preserved'])
if __name__=='__main__':unittest.main()
