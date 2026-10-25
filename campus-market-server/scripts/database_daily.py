#!/usr/bin/env python3
"""Scheduled backup entry point; writes latest health evidence and exits nonzero on failure. No automatic deletion."""
from pathlib import Path
from datetime import datetime,timezone
import json,sys
from database_common import Database,write_json
from database_backup import backup
root=Path('/srv/campus-market/backups')
def main():
    try:
        db=Database('/usr/bin/mysql','/etc/campus-market/backup.cnf','campus_production')
        result=backup(db,'/srv/campus-market/uploads',root/datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ'),'/usr/bin/mysqldump')
        write_json(root/'latest.json',result);return 0
    except Exception as error:
        write_json(root/'latest.json',{'status':'failed','errorType':type(error).__name__});return 2
if __name__=='__main__':sys.exit(main())
