#!/usr/bin/env python3
# coding: utf-8
"""Anonymous GET checks only; does not certify payment settlement or launch approval."""
import argparse,ipaddress,json,urllib.request,urllib.error
from pathlib import Path
from urllib.parse import urlsplit
class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self,*args):return None
def base_origin(raw,allow_local=False):
    try:
        p=urlsplit(raw)
        if p.username or p.password or p.path or p.query or p.fragment or not p.hostname:raise ValueError()
        local=p.hostname=='localhost'
        try:local=local or ipaddress.ip_address(p.hostname).is_loopback
        except ValueError:pass
        if p.scheme!='https' and not(allow_local and local and p.scheme=='http'):raise ValueError()
        if p.port is not None and not 1<=p.port<=65535:raise ValueError()
        return raw
    except (ValueError,TypeError):raise ValueError('Use an HTTPS origin; HTTP is permitted only for an explicit local preview')
def read_json(origin,path):
    opener=urllib.request.build_opener(urllib.request.ProxyHandler({}),NoRedirect())
    req=urllib.request.Request(origin+path,headers={'Accept':'application/json','User-Agent':'CampusMarketReadOnlyPreflight/1'})
    with opener.open(req,timeout=5) as response:
        if response.status!=200 or 'application/json' not in response.headers.get('Content-Type','').lower():raise ValueError('Expected HTTP 200 JSON')
        body=response.read(65537)
        if len(body)>65536:raise ValueError('Response too large')
        return json.loads(body)
def check(origin,fetch=read_json):
    checks=[]
    for name,path in [('liveness','/api/health/liveness'),('readiness','/api/health/readiness')]:
        try:passed=fetch(origin,path).get('status')=='UP';checks.append({'name':name,'passed':passed,'message':'UP' if passed else 'Health check not UP'})
        except Exception as error:checks.append({'name':name,'passed':False,'message':'Request failed: '+type(error).__name__})
    try:
        value=fetch(origin,'/api/trade-capabilities')
        mode=value.get('paymentMode')
        passed=mode=='WECHAT_PAY' and value.get('orderCreationEnabled') is True and value.get('simulatedPaymentEnabled') is False
        checks.append({'name':'real-payment-channel','passed':passed,'observedMode':mode if mode in ['SIMULATED','UNAVAILABLE','WECHAT_PAY'] else 'UNKNOWN','message':'Payment mode reported ready; still requires real payment/refund/reconciliation acceptance' if passed else 'Real payment is unavailable or simulated; public trading is blocked'})
    except Exception as error:checks.append({'name':'real-payment-channel','passed':False,'message':'Request failed: '+type(error).__name__})
    return {'scope':'read-only operational preflight, not full launch acceptance','technicalChecksPassed':all(v['passed'] for v in checks),'checks':checks}
def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--base-url',required=True);p.add_argument('--allow-local-preview',action='store_true');p.add_argument('--output',type=Path);a=p.parse_args()
    result=check(base_origin(a.base_url,a.allow_local_preview));text=json.dumps(result,ensure_ascii=False,indent=2)
    if a.output:
        with a.output.open('x') as f:f.write(text+'\n')
        a.output.chmod(0o600)
    print(text);return 0 if result['technicalChecksPassed'] else 2
if __name__=='__main__':
    try:raise SystemExit(main())
    except (ValueError,OSError) as error:raise SystemExit(type(error).__name__+': '+str(error))
