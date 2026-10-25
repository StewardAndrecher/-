#!/usr/bin/env python3
"""验证Prometheus规则的时间/故障语义及Grafana配置，不连接业务数据库。"""
from pathlib import Path
import json,os,shutil,subprocess,sys
root=Path(__file__).resolve().parents[1];folder=root/'monitoring'
tool=os.environ.get('PROMTOOL_BIN') or shutil.which('promtool') or '/opt/homebrew/opt/prometheus/bin/promtool'
for args in [['check','rules','prometheus-alerts.yml','notification-alerts.yml','registration-alerts.yml'],['test','rules','prometheus-alerts.test.json'],['test','rules','notification-alerts.test.json'],['test','rules','registration-alerts.test.json']]:
 result=subprocess.run([tool,*args],cwd=folder,text=True,capture_output=True)
 print(result.stdout,end='')
 if result.returncode:print(result.stderr,file=sys.stderr);raise SystemExit(result.returncode)
dashboard=json.loads((folder/'grafana-mq-dashboard.json').read_text());assert dashboard['uid']=='campus-mq-overview' and dashboard['editable'] is False
assert len(dashboard['panels'])==15 and len({p['id'] for p in dashboard['panels']})==15
for panel in dashboard['panels']:
 for target in panel.get('targets',[]):
  assert target['datasource']['uid']=='campus-prometheus'
  assert 'job="campus-market"' in target['expr']
  assert 'or vector(0)' not in target['expr'],'Do not hide unknown data as zero'
tests=json.loads((folder/'prometheus-alerts.test.json').read_text())
print(json.dumps({'scenarios':len(tests['tests']),'ruleAssertions':sum(len(t['alert_rule_test']) for t in tests['tests']),'expressionAssertions':sum(len(t.get('promql_expr_test',[])) for t in tests['tests']),'dashboardPanels':len(dashboard['panels']),'businessDatabaseTouched':False},ensure_ascii=False))

notification=json.loads((folder/'grafana-notification-dashboard.json').read_text())
assert notification['uid']=='campus-notification-overview' and notification['editable'] is False
assert len(notification['panels'])==10 and len({p['id'] for p in notification['panels']})==10
for panel in notification['panels']:
 for target in panel['targets']:
  assert target['datasource']['uid']=='campus-prometheus' and 'job="campus-market"' in target['expr']
  assert 'or vector(0)' not in target['expr']
notification_tests=json.loads((folder/'notification-alerts.test.json').read_text())
print(json.dumps({'notificationScenarios':len(notification_tests['tests']),'notificationRuleAssertions':sum(len(t['alert_rule_test']) for t in notification_tests['tests']),'notificationDashboardPanels':len(notification['panels']),'businessDatabaseTouched':False}))

registration=json.loads((folder/'grafana-registration-dashboard.json').read_text())
assert registration['uid']=='campus-registration-protection' and registration['editable'] is False
assert len(registration['panels'])==8 and len({p['id'] for p in registration['panels']})==8
for panel in registration['panels']:
 for target in panel['targets']:
  assert target['datasource']['uid']=='campus-prometheus' and 'job="campus-market"' in target['expr']
  assert 'or vector(0)' not in target['expr']
  assert not any(tag in target['expr'] for tag in ['username=','ip=','password=','token='])
registration_tests=json.loads((folder/'registration-alerts.test.json').read_text())
print(json.dumps({'registrationScenarios':len(registration_tests['tests']),'registrationRuleAssertions':sum(len(t['alert_rule_test']) for t in registration_tests['tests']),'registrationDashboardPanels':len(registration['panels']),'businessDatabaseTouched':False}))
