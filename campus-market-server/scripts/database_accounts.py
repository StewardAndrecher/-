#!/usr/bin/env python3
"""Render explicit per-environment database accounts. Never executes grants or saves passwords in source control."""
from pathlib import Path
import argparse,os,re
ENVIRONMENTS={'development','test','production'}
APPEND_ONLY={'finance_journals','payment_callback_receipts','finance_reconciliation_runs','finance_reconciliation_lines','order_events','order_refund_events','account_restriction_events','database_maintenance_runs'}
FINANCIAL={'payment_attempts','payment_refund_attempts','seller_settlements','seller_settlement_items'}
EPHEMERAL={'login_sessions','account_email_challenges','public_write_windows','notification_outbox','order_timeout_outbox','user_notifications'}

def tables(project):
    result=set()
    for p in (Path(project)/'src/main/resources/db/migration').glob('*.sql'):
        result.update(re.findall(r'CREATE TABLE(?: IF NOT EXISTS)? ([a-z_]+)',p.read_text(),re.I))
    initialization=Path(project)/'database/init.sql'
    if initialization.is_file():result.update(re.findall(r'CREATE TABLE(?: IF NOT EXISTS)? ([a-z_]+)',initialization.read_text(),re.I))
    if not {'users','products','finance_journals','database_maintenance_runs'}<=result:raise ValueError('Complete migration source required')
    return sorted(result)

def render(environment,secrets,project):
    if environment not in ENVIRONMENTS:raise ValueError('Unknown environment')
    for role in ['runtime','migrator','backup','maintenance']:
        if not re.fullmatch(r'[a-zA-Z0-9_-]{24,128}',secrets.get(role,'')):raise ValueError('Use distinct random URL-safe passwords of at least 24 characters')
    if len(set(secrets.values()))!=4:raise ValueError('Roles must not share credentials')
    schema='campus_'+environment;prefix=schema+'_';bootstrap=['CREATE DATABASE `'+schema+'` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;']
    for role in ['migrator','backup','maintenance','runtime']:
        bootstrap.append("CREATE USER '"+prefix+role+"'@'127.0.0.1' IDENTIFIED BY '"+secrets[role]+"';")
    bootstrap+=["GRANT SELECT,INSERT,UPDATE,DELETE,CREATE,ALTER,INDEX,REFERENCES ON `"+schema+"`.* TO '"+prefix+"migrator'@'127.0.0.1';",
        "GRANT SELECT,SHOW VIEW,TRIGGER ON `"+schema+"`.* TO '"+prefix+"backup'@'127.0.0.1';"]
    runtime=[]
    for table in tables(project):
        permissions='SELECT,INSERT' if table in APPEND_ONLY else 'SELECT,INSERT,UPDATE' if table in FINANCIAL else 'SELECT,INSERT,UPDATE,DELETE'
        runtime.append("GRANT "+permissions+" ON `"+schema+"`.`"+table+"` TO '"+prefix+"runtime'@'127.0.0.1';")
    runtime.append("GRANT SELECT ON `"+schema+"`.`flyway_schema_history` TO '"+prefix+"runtime'@'127.0.0.1';")
    for table in sorted(EPHEMERAL):runtime.append("GRANT SELECT,DELETE ON `"+schema+"`.`"+table+"` TO '"+prefix+"maintenance'@'127.0.0.1';")
    for table in ['market_orders','product_images','image_storage_records','product_gallery','products','users','product_publications','product_draft_backups']:
        runtime.append("GRANT SELECT ON `"+schema+"`.`"+table+"` TO '"+prefix+"maintenance'@'127.0.0.1';")
    runtime.append("GRANT SELECT,INSERT ON `"+schema+"`.`database_maintenance_runs` TO '"+prefix+"maintenance'@'127.0.0.1';")
    return {'bootstrap.sql':'\n'.join(bootstrap)+'\n','runtime-after-migration.sql':'\n'.join(runtime)+'\n'}

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--environment',choices=sorted(ENVIRONMENTS),required=True);p.add_argument('--output',required=True);a=p.parse_args()
    try:
        destination=Path(a.output).absolute();project=Path(__file__).resolve().parents[1]
        if destination.exists() or destination.is_relative_to(project):raise ValueError('Use a new private directory outside project source')
        files=render(a.environment,{role:os.environ.get('CAMPUS_'+role.upper()+'_PASSWORD','') for role in ['runtime','migrator','backup','maintenance']},project)
        destination.mkdir(mode=0o700,parents=True);destination.chmod(0o700)
        for name,text in files.items():
            with open(destination/name,'x',opener=lambda name,flags:os.open(name,flags,0o600)) as f:f.write(text)
        print('Private SQL rendered. Review bootstrap, migrate with migrator, then apply runtime grants. No database changed.');return 0
    except ValueError as error:print(str(error));return 2
if __name__=='__main__':raise SystemExit(main())
