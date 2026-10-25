#!/usr/bin/env python3
"""Import an operator-reviewed normalized bill; report both missing sides. Never changes balances, order status or settlement eligibility."""
from pathlib import Path
from datetime import date,timedelta
import argparse,hashlib,json,re,uuid
from database_common import Database,sha

def token(value,maximum):
    if not isinstance(value,str) or not re.fullmatch(r'[a-zA-Z0-9_-]{1,'+str(maximum)+'}',value):raise ValueError('Invalid bill identifier')
    return value

def validate_bill(bill):
    if not isinstance(bill,dict) or set(bill)!={'channel','merchantScope','billDate','entries'} or bill['channel'] not in {'SIMULATED','WECHAT','ALIPAY'}:raise ValueError('Invalid bill envelope')
    token(bill['merchantScope'],64)
    if date.fromisoformat(bill['billDate']).isoformat()!=bill['billDate']:raise ValueError('Invalid bill date')
    entries=bill['entries']
    if not isinstance(entries,list) or len(entries)>10000:raise ValueError('Bill limit is 10000 entries')
    seen=set()
    for row in entries:
        if not isinstance(row,dict) or set(row)!={'kind','merchantReference','channelReference','amountInCents','currency'}:raise ValueError('Unexpected bill fields')
        if row['kind'] not in {'PAYMENT','REFUND'} or row['currency']!='CNY' or type(row['amountInCents']) is not int or not 1<=row['amountInCents']<=99999999:raise ValueError('Bill currency or amount invalid')
        if row['channelReference'] is not None:token(row['channelReference'],128)
        elif bill['channel']!='SIMULATED':raise ValueError('Real channel bill requires provider transaction reference')
        token(row['merchantReference'],64);key=(row['kind'],row['merchantReference'])
        if key in seen:raise ValueError('Duplicate bill reference')
        seen.add(key)
    return bill

def compare(provider,local):
    p={(row['kind'],row['merchantReference']):(row['amountInCents'],row['channelReference']) for row in provider}
    result=[]
    for key in sorted(set(p)|set(local)):
        provider_value,local_value=p.get(key),local.get(key)
        a,b=None if provider_value is None else provider_value[0],None if local_value is None else local_value[0]
        pr,lr=None if provider_value is None else provider_value[1],None if local_value is None else local_value[1]
        outcome='MISSING_LOCAL' if b is None else 'MISSING_PROVIDER' if a is None else 'AMOUNT_MISMATCH' if a!=b else 'TRANSACTION_MISMATCH' if pr!=lr else 'MATCHED'
        result.append({'kind':key[0],'reference':key[1],'provider':a,'local':b,'providerReference':pr,'localReference':lr,'outcome':outcome})
    return result

def reconcile(db,path):
    path=Path(path)
    if path.is_symlink() or not path.is_file() or path.stat().st_size>10*1024*1024:raise ValueError('Use a regular normalized bill <=10MB')
    bill=validate_bill(json.loads(path.read_text()));channel=bill['channel'];merchant=bill['merchantScope'];day=bill['billDate'];end=(date.fromisoformat(day)+timedelta(days=1)).isoformat();checksum=sha(path)
    # ASCII validation above makes literals non-executable; no untrusted SQL fragments are accepted.
    existing=db.rows("SELECT id,status FROM finance_reconciliation_runs WHERE channel='"+channel+"' AND merchant_scope='"+merchant+"' AND bill_date='"+day+"' AND file_sha256='"+checksum+"'")
    if existing:return {'status':'passed','runId':existing[0][0],'replayed':True,'result':existing[0][1],'fundsModified':False}
    selection="channel='"+channel+"' AND merchant_scope='"+merchant+"' AND status='SUCCEEDED' AND succeeded_at>='"+day+"' AND succeeded_at<'"+end+"'"
    # One transaction gives payment/refund reads the same consistent snapshot.
    sql="SET TRANSACTION ISOLATION LEVEL REPEATABLE READ; START TRANSACTION WITH CONSISTENT SNAPSHOT; SELECT 'PAYMENT',merchant_order_no,amount_in_cents,COALESCE(channel_trade_no,'NULL') FROM payment_attempts WHERE "+selection+" UNION ALL SELECT 'REFUND',merchant_refund_no,amount_in_cents,COALESCE(channel_refund_no,'NULL') FROM payment_refund_attempts WHERE "+selection+"; COMMIT;"
    rows=db.run(sql);local={}
    for line in rows.splitlines():
        kind,ref,amount,transaction=line.split('\t');local[kind,ref]=(int(amount),None if transaction=='NULL' else transaction)
    results=compare(bill['entries'],local);run=str(uuid.uuid4());state='MATCHED' if all(row['outcome']=='MATCHED' for row in results) else 'REVIEW_REQUIRED'
    statements=['START TRANSACTION',"INSERT INTO finance_reconciliation_runs(id,channel,merchant_scope,bill_date,file_sha256,status) VALUES('"+run+"','"+channel+"','"+merchant+"','"+day+"','"+checksum+"','"+state+"')"]
    for number,row in enumerate(results,1):
        provider_ref='NULL' if row['providerReference'] is None else "'"+token(row['providerReference'],128)+"'"
        local_ref='NULL' if row['localReference'] is None else "'"+token(row['localReference'],128)+"'"
        statements.append("INSERT INTO finance_reconciliation_lines(run_id,line_no,merchant_reference,kind,provider_reference,local_reference,provider_amount_in_cents,local_amount_in_cents,outcome) VALUES('"+run+"',"+str(number)+",'"+token(row['reference'],64)+"','"+row['kind']+"',"+provider_ref+","+local_ref+","+('NULL' if row['provider'] is None else str(row['provider']))+","+('NULL' if row['local'] is None else str(row['local']))+",'"+row['outcome']+"')")
    statements+=['COMMIT'];db.run(';\n'.join(statements)+';')
    return {'status':'passed','runId':run,'replayed':False,'result':state,'counts':{name:sum(row['outcome']==name for row in results) for name in ['MATCHED','AMOUNT_MISMATCH','TRANSACTION_MISMATCH','MISSING_LOCAL','MISSING_PROVIDER']},'fundsModified':False,'providerBillAuthenticityVerifiedByTool':False}

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--mysql',default='/usr/bin/mysql');p.add_argument('--defaults-file',required=True);p.add_argument('--schema',required=True);p.add_argument('--bill',required=True);a=p.parse_args()
    try:print(json.dumps(reconcile(Database(a.mysql,a.defaults_file,a.schema),a.bill)));return 0
    except Exception as error:print(json.dumps({'status':'failed','reason':str(error) if isinstance(error,ValueError) else type(error).__name__}));return 2
if __name__=='__main__':raise SystemExit(main())
