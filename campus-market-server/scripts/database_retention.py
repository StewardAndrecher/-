#!/usr/bin/env python3
"""Preview or apply at most 500 eligible ephemeral rows per table. Never deletes images, financial or audit history."""
from pathlib import Path
import argparse,hashlib,json,re,time,uuid
from database_common import Database,write_json
POLICY_VERSION=1
# Fixed intervals, not user-provided SQL. Published timeout records also require a terminal order.
RULES={
 'login_sessions':('token_hash',"expires_at<DATE_SUB(UTC_TIMESTAMP(),INTERVAL 30 DAY) OR revoked_at<DATE_SUB(UTC_TIMESTAMP(),INTERVAL 30 DAY)"),
 'account_email_challenges':('id',"expires_at<DATE_SUB(UTC_TIMESTAMP(),INTERVAL 7 DAY)"),
 'public_write_windows':('user_id,action,bucket',"bucket<FLOOR(UNIX_TIMESTAMP()/600)-1008"),
 'notification_outbox':('event_id',"delivered_at<DATE_SUB(UTC_TIMESTAMP(),INTERVAL 90 DAY) AND (lease_until IS NULL OR lease_until<UTC_TIMESTAMP()) AND EXISTS(SELECT 1 FROM user_notifications n WHERE n.event_id=notification_outbox.event_id AND n.read_at<DATE_SUB(UTC_TIMESTAMP(),INTERVAL 90 DAY))"),
 'order_timeout_outbox':('event_id',"published_at<DATE_SUB(UTC_TIMESTAMP(),INTERVAL 90 DAY) AND (lease_until IS NULL OR lease_until<UTC_TIMESTAMP()) AND EXISTS(SELECT 1 FROM market_orders o WHERE o.id=order_timeout_outbox.order_id AND o.status<>'PENDING_PAYMENT')")}

def plan(db):
    selected={}
    for table,(keys,condition) in RULES.items():
        selected[table]=db.rows('SELECT '+keys+' FROM '+table+' WHERE ('+condition+') ORDER BY '+keys+' LIMIT 500')
    result={'policyVersion':POLICY_VERSION,'schema':db.schema,'createdAtEpoch':int(time.time()),'rows':selected,'counts':{key:len(value) for key,value in selected.items()},'imagePolicy':'Unused images: manual reference review after 90 days; trash: 30 days before admin-confirmed purge','preserved':['orders','payment_attempts','payment_callback_receipts','payment_refund_attempts','finance_journals','finance_reconciliation_runs','finance_reconciliation_lines','seller_settlements','seller_settlement_items','order_events','order_refund_events','account_restriction_events','database_maintenance_runs','unread notifications','pending/leased/undelivered outbox']}
    result['fingerprint']=hashlib.sha256(json.dumps(result,sort_keys=True,separators=(',',':')).encode()).hexdigest();return result

def execute(db,reviewed,fingerprint):
    copy=dict(reviewed);saved=copy.pop('fingerprint',None)
    if saved!=fingerprint or saved!=hashlib.sha256(json.dumps(copy,sort_keys=True,separators=(',',':')).encode()).hexdigest() or reviewed['schema']!=db.schema or reviewed['policyVersion']!=POLICY_VERSION or not 0<=time.time()-reviewed['createdAtEpoch']<=600:raise ValueError('Plan changed or expired; preview again')
    if set(reviewed['rows'])!=set(RULES):raise ValueError('Unknown retention target')
    statements=['START TRANSACTION'];counts={}
    for table,(keys,condition) in RULES.items():
        rows=reviewed['rows'][table]
        if len(rows)>500 or any(len(row)!=len(keys.split(',')) for row in rows):raise ValueError('Invalid candidate batch')
        predicates=[]
        for row in rows:
            if any(not isinstance(v,str) or not re.fullmatch('[a-zA-Z0-9_-]{1,64}',v) for v in row):raise ValueError('Unsafe candidate key')
            predicates.append('('+ ' AND '.join(k+"='"+v+"'" for k,v in zip(keys.split(','),row))+')')
        if predicates:
            statements+=['DELETE FROM '+table+' WHERE ('+condition+') AND ('+' OR '.join(predicates)+')','SELECT '+"'"+table+"'"+',ROW_COUNT()'];counts[table]=len(rows)
    summary=json.dumps({'planned':reviewed['counts'],'fingerprint':fingerprint},separators=(',',':'))
    statements+=["INSERT INTO database_maintenance_runs(id,policy_version,cutoff_at,status,summary_json) VALUES('"+str(uuid.uuid4())+"',1,UTC_TIMESTAMP(),'"+('COMPLETED' if counts else 'NO_CHANGES')+"',CAST('"+summary+"' AS JSON))",'COMMIT']
    actual=db.run(';\n'.join(statements)+';')
    return {'status':'passed','planned':reviewed['counts'],'affected':[line.split('\t') for line in actual.splitlines()],'financialAndAuditRowsDeleted':False,'imagesDeleted':False}

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('action',choices=['preview','execute']);p.add_argument('--defaults-file',required=True);p.add_argument('--mysql',default='/usr/bin/mysql');p.add_argument('--schema',required=True);p.add_argument('--plan',required=True);p.add_argument('--fingerprint');a=p.parse_args()
    try:
        db=Database(a.mysql,a.defaults_file,a.schema)
        if a.action=='preview':result=plan(db);write_json(a.plan,result)
        else:result=execute(db,json.loads(Path(a.plan).read_text()),a.fingerprint)
        print(json.dumps(result));return 0
    except Exception as error:print(json.dumps({'status':'failed','reason':str(error) if isinstance(error,ValueError) else type(error).__name__}));return 2
if __name__=='__main__':raise SystemExit(main())
