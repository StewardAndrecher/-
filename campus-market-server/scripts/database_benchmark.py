#!/usr/bin/env python3
"""Synthetic SQL benchmark; refuses shared databases. Captures EXPLAIN ANALYZE before/after candidate indexes."""
from pathlib import Path
import argparse,concurrent.futures,json,re,statistics,time,uuid
from database_common import Database,write_json

def measure(db,query):
 tree=db.run('EXPLAIN ANALYZE '+query);match=re.search(r'actual time=[0-9.]+[.][.]([0-9.]+) rows=([0-9]+)',tree)
 if not match:raise ValueError('EXPLAIN ANALYZE did not execute')
 return {'serverMilliseconds':float(match[1]),'resultRows':int(match[2]),'plan':tree}

def run(db,project):
 if not re.fullmatch('campus_benchmark_[0-9a-f]{12}',db.schema) or not any(mark in db.run('SELECT @@datadir',False) for mark in ['campus-database-drill-','campus-lesson24-']):raise ValueError('Only an owned private benchmark runtime is allowed')
 db.run('CREATE DATABASE '+db.schema+' CHARACTER SET utf8mb4',False)
 migrations=sorted((Path(project)/'src/main/resources/db/migration').glob('V*.sql'),key=lambda p:int(p.name[1:].split('__')[0]))
 for p in migrations:
  if int(p.name[1:].split('__')[0])<136:db.run(p.read_text())
 digits='(SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9)'
 sql='CREATE TABLE benchmark_sequence(n INT PRIMARY KEY); INSERT INTO benchmark_sequence SELECT a.n+10*b.n+100*c.n+1000*d.n+10000*e.n+1 FROM '+digits+' a CROSS JOIN '+digits+' b CROSS JOIN '+digits+' c CROSS JOIN '+digits+' d CROSS JOIN '+digits+' e;'
 sql+="INSERT INTO users(id,username,password_hash,nickname) SELECT n,CONCAT('bench_',n),'not-a-real-login-hash',CONCAT('样本',n) FROM benchmark_sequence WHERE n<=10000;"
 sql+="INSERT INTO products(id,title,description,price_in_cents,seller_id,created_at,category_code) SELECT n,CONCAT(IF(MOD(n,20)=0,'高数教材','校园样本'),n),REPEAT('说明使用情况和瑕疵。',8),100+MOD(n,9900),IF(MOD(n,5)=0,10000,9001+MOD(n,999)),DATE_ADD('2026-01-01',INTERVAL n SECOND),'BOOK' FROM benchmark_sequence;"
 sql+="INSERT INTO market_orders(id,product_id,buyer_id,seller_id,request_id,product_title_snapshot,price_in_cents_snapshot,product_version_snapshot,buyer_nickname_snapshot,seller_nickname_snapshot,status,created_at,paid_at,payment_kind,delivered_at,completed_at,cancelled_at) SELECT n,n,IF(MOD(n,4)=0,1,2+MOD(n,8998)),IF(MOD(n,5)=0,10000,9001+MOD(n,999)),UUID(),CONCAT('样本',n),100+MOD(n,9900),0,'买家','卖家',IF(((MOD(n,4)=0 OR MOD(n,5)=0) AND MOD(n,400)=0 OR (MOD(n,4)<>0 AND MOD(n,5)<>0 AND MOD(n,3)=0)),'COMPLETED','CANCELLED'),DATE_ADD('2026-01-01',INTERVAL n SECOND),IF(((MOD(n,4)=0 OR MOD(n,5)=0) AND MOD(n,400)=0 OR (MOD(n,4)<>0 AND MOD(n,5)<>0 AND MOD(n,3)=0)),DATE_ADD('2026-01-01',INTERVAL n+60 SECOND),NULL),IF(((MOD(n,4)=0 OR MOD(n,5)=0) AND MOD(n,400)=0 OR (MOD(n,4)<>0 AND MOD(n,5)<>0 AND MOD(n,3)=0)),'SIMULATED',NULL),IF(((MOD(n,4)=0 OR MOD(n,5)=0) AND MOD(n,400)=0 OR (MOD(n,4)<>0 AND MOD(n,5)<>0 AND MOD(n,3)=0)),DATE_ADD('2026-01-01',INTERVAL n+120 SECOND),NULL),IF(((MOD(n,4)=0 OR MOD(n,5)=0) AND MOD(n,400)=0 OR (MOD(n,4)<>0 AND MOD(n,5)<>0 AND MOD(n,3)=0)),DATE_ADD('2026-01-01',INTERVAL n+180 SECOND),NULL),IF(NOT(((MOD(n,4)=0 OR MOD(n,5)=0) AND MOD(n,400)=0 OR (MOD(n,4)<>0 AND MOD(n,5)<>0 AND MOD(n,3)=0))),DATE_ADD('2026-01-01',INTERVAL n+60 SECOND),NULL) FROM benchmark_sequence; ANALYZE TABLE products,market_orders;"
 db.run(sql,timeout=600)
 queries={'buyerStatus':"SELECT * FROM market_orders WHERE buyer_id=1 AND status='COMPLETED' ORDER BY created_at DESC,id DESC LIMIT 20",'sellerStatus':"SELECT * FROM market_orders WHERE seller_id=10000 AND status='COMPLETED' ORDER BY created_at DESC,id DESC LIMIT 20",'newestProducts':"SELECT id,title FROM products WHERE status='ON_SALE' ORDER BY created_at DESC,id DESC LIMIT 20",'keywordCount':"SELECT COUNT(*) FROM products WHERE status='ON_SALE' AND title LIKE '%高数%'",'buyerStatistics':"SELECT status,COUNT(*) FROM market_orders WHERE buyer_id=1 GROUP BY status"}
 before={k:measure(db,q) for k,q in queries.items()};db.run((Path(project)/'src/main/resources/db/migration/V136__order_query_indexes.sql').read_text());db.run('ANALYZE TABLE market_orders')
 after={}
 for key,query in queries.items():
  samples=[measure(db,query) for _ in range(7)];after[key]=samples[-1];values=sorted(r['serverMilliseconds'] for r in samples);after[key].update(p50Milliseconds=statistics.median(values),p95Milliseconds=values[-1])
  if before[key]['resultRows']!=after[key]['resultRows']:raise AssertionError('Index changed result count')
 for key,name in [('buyerStatus','idx_order_buyer_status_created'),('sellerStatus','idx_order_seller_status_created')]:
  after[key]['candidateIndexUsed']=name in after[key]['plan']
 def request(index):
  start=time.monotonic();db.run(queries['buyerStatus' if index%2 else 'sellerStatus']);return (time.monotonic()-start)*1000
 with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
  start=time.monotonic();latencies=sorted(pool.map(request,range(80)));elapsed=time.monotonic()-start
 sizes=db.rows("SELECT TABLE_NAME,DATA_LENGTH,INDEX_LENGTH,TABLE_ROWS FROM information_schema.tables WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME IN ('products','market_orders') ORDER BY TABLE_NAME")
 return {'status':'passed','syntheticUsers':10000,'syntheticProducts':100000,'syntheticOrders':100000,'before':before,'after':after,'concurrentReadProbe':{'workers':8,'queries':80,'elapsedSeconds':round(elapsed,3),'p50MillisecondsIncludingClientStartup':round(statistics.median(latencies),3),'p95MillisecondsIncludingClientStartup':round(latencies[75],3)},'capacity':sizes,'limits':['Synthetic warm local SQL benchmark; not a production QPS/SLA claim','Contains-keyword count still scans matching rows; BTREE does not solve leading-wildcard search','Plan for search engine or separately validated Chinese fulltext semantics when real volume warrants it','Use existing cursor feed for long browsing; numbered deep OFFSET still costs proportional scanning'],'realDatabaseTouched':False}

def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--mysql',required=True);p.add_argument('--defaults-file',required=True);p.add_argument('--schema',required=True);p.add_argument('--output',required=True);a=p.parse_args()
 try:result=run(Database(a.mysql,a.defaults_file,a.schema),Path(__file__).resolve().parents[1]);write_json(a.output,result);print(json.dumps({'status':result['status'],'products':100000,'orders':100000}));return 0
 except Exception as error:print(type(error).__name__);return 2
if __name__=='__main__':raise SystemExit(main())
