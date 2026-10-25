#!/usr/bin/env python3
"""Consistent database + immutable image backups and restore-to-new-schema rehearsal. Never overwrites a database."""
from pathlib import Path
import argparse,contextlib,hashlib,json,os,re,select,shutil,subprocess,tarfile,time,uuid
from database_common import Database,identifier,sha,write_json

@contextlib.contextmanager
def image_guard(db):
    name='campus-images-'+db.schema
    process=subprocess.Popen(db.command()+['--unbuffered'],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,text=True,env=db.env,start_new_session=True)
    acquired=False
    def query(text):
        if process.poll() is not None:raise RuntimeError('Backup image lock connection was lost')
        process.stdin.write(text+';\n');process.stdin.flush()
        if not select.select([process.stdout],[],[],5)[0]:raise RuntimeError('Backup image lock response timed out')
        return process.stdout.readline().strip()
    try:
        if query("SELECT GET_LOCK('"+name+"',0)")!='1':raise RuntimeError('Another backup or image cleanup holds the lock')
        acquired=True
        yield process
        if process.poll() is not None:raise RuntimeError('Backup image lock was lost; backup refused')
    finally:
        try:
            if acquired and query("SELECT RELEASE_LOCK('"+name+"')")!='1':raise RuntimeError('Backup image lock release could not be confirmed')
        finally:
            if process.stdin:process.stdin.close()
            try:process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.terminate()
                try:process.wait(timeout=5)
                except subprocess.TimeoutExpired:process.kill();process.wait(timeout=5)

def safe_root(path):
    p=Path(path).absolute()
    if p.is_symlink() or not p.is_dir() or p.resolve()!=p:raise ValueError('Use an existing real directory without symlink ancestors')
    return p

def backup(db,uploads,destination,mysqldump):
    uploads=safe_root(uploads);destination=Path(destination).absolute()
    if destination.exists():raise ValueError('Backup destination must be new')
    if uploads==destination or uploads in destination.parents:raise ValueError('Backups must not be inside uploads')
    destination.mkdir(parents=True,mode=0o700);destination.chmod(0o700)
    try:
        tables=db.rows("SELECT TABLE_NAME,ENGINE FROM information_schema.tables WHERE TABLE_SCHEMA=DATABASE() AND TABLE_TYPE='BASE TABLE' ORDER BY TABLE_NAME")
        if not tables or any(engine!='InnoDB' for _,engine in tables):raise ValueError('Only an all-InnoDB project database is supported')
        for query in ["SELECT COUNT(*) FROM information_schema.triggers WHERE TRIGGER_SCHEMA=DATABASE()","SELECT COUNT(*) FROM information_schema.routines WHERE ROUTINE_SCHEMA=DATABASE()","SELECT COUNT(*) FROM information_schema.events WHERE EVENT_SCHEMA=DATABASE()"]:
            if db.run(query)!='0':raise ValueError('Custom database objects require a separately reviewed backup strategy')
        with image_guard(db) as lock:
            db.dump(mysqldump,destination/'database.sql')
            # Objects are immutable; the same database lock prevents physical purge throughout snapshot + copy.
            images={}
            with tarfile.open(destination/'images.tar','w') as archive:
                for p in sorted(uploads.iterdir()):
                    if p.is_symlink() or not p.is_file() or not re.fullmatch(r'[0-9a-f-]{36}[.]png',p.name):raise ValueError('Unknown upload object; backup refused instead of silently omitting it')
                    before=p.stat();digest=sha(p);archive.add(p,arcname=p.name,recursive=False);after=p.stat()
                    if (before.st_size,before.st_mtime_ns)!=(after.st_size,after.st_mtime_ns):raise ValueError('Image changed during backup')
                    images[p.name]={'bytes':before.st_size,'sha256':digest}
            (destination/'images.tar').chmod(0o600)
            if lock.poll() is not None:raise RuntimeError('Image lock lost')
        manifest={'format':1,'status':'complete','schema':db.schema,'createdAtEpoch':int(time.time()),'databaseSha256':sha(destination/'database.sql'),'imagesArchiveSha256':sha(destination/'images.tar'),'images':images,'tables':[name for name,_ in tables],'consistency':'InnoDB single transaction + immutable images + shared physical-purge advisory lock','restored':False}
        write_json(destination/'manifest.json',manifest)
        return {'status':'passed','backup':str(destination),'tables':len(tables),'images':len(images),'restored':False}
    except BaseException:
        write_json(destination/'failed.json',{'status':'failed','usable':False});raise

def verify_bundle(root):
    root=safe_root(root);manifest=json.loads((root/'manifest.json').read_text())
    if manifest.get('format')!=1 or manifest.get('status')!='complete':raise ValueError('Incomplete backup')
    for name,key in [('database.sql','databaseSha256'),('images.tar','imagesArchiveSha256')]:
        p=root/name
        if p.is_symlink() or sha(p)!=manifest[key]:raise ValueError('Backup checksum mismatch')
    return manifest

def restore(db,bundle,uploads,mysqldump):
    if not re.fullmatch(r'campus_restore_[a-f0-9]{12}',db.schema):raise ValueError('Restore only into a fresh campus_restore_<12 hex> schema')
    manifest=verify_bundle(bundle);bundle=Path(bundle);uploads=Path(uploads).absolute()
    if uploads.exists():raise ValueError('Restore images directory must be new')
    sql=(bundle/'database.sql').read_bytes()
    if re.search(rb'(?im)^\s*(?:USE\s|CREATE\s+DATABASE|SOURCE\s|SYSTEM\s|\\!)',sql) or re.search(rb'(?i)\b(?:OUTFILE|INFILE|DEFINER)\b',sql):raise ValueError('Unexpected database switch, external IO or definer in dump')
    # CREATE DATABASE without IF NOT EXISTS guarantees that an existing target is never overwritten.
    db.run('CREATE DATABASE `'+db.schema+'` CHARACTER SET utf8mb4',schema=False)
    uploads.mkdir(parents=True,mode=0o700);uploads.chmod(0o700)
    p=subprocess.run(db.command(),input=sql,stdout=subprocess.DEVNULL,stderr=subprocess.PIPE,env=db.env,timeout=600)
    if p.returncode:raise RuntimeError('Restore failed; fresh target retained for inspection')
    with tarfile.open(bundle/'images.tar') as archive:
        members=archive.getmembers()
        if len(members)!=len(manifest['images']) or len({m.name for m in members})!=len(members):raise ValueError('Unexpected archive members')
        for member in members:
            if not member.isfile() or member.name not in manifest['images'] or '/' in member.name or '\\' in member.name:raise ValueError('Unsafe image archive')
            if member.size!=manifest['images'][member.name]['bytes']:raise ValueError('Image size mismatch')
            with archive.extractfile(member) as source,(uploads/member.name).open('xb') as target:shutil.copyfileobj(source,target)
            (uploads/member.name).chmod(0o600)
            if sha(uploads/member.name)!=manifest['images'][member.name]['sha256']:raise ValueError('Restored image mismatch')
    check=uploads.parent/(uploads.name+'-roundtrip.sql');db.dump(mysqldump,check)
    try:
        if sha(check)!=manifest['databaseSha256']:raise ValueError('Restored database roundtrip differs from snapshot')
    finally:check.unlink(missing_ok=True)
    tables={row[0] for row in db.rows("SELECT TABLE_NAME FROM information_schema.tables WHERE TABLE_SCHEMA=DATABASE() AND TABLE_TYPE='BASE TABLE'")}
    if tables!=set(manifest['tables']):raise ValueError('Restored table set differs')
    if 'product_images' in tables:
        # A deleted tombstone need not have a physical file; every live/retained object must exist.
        for image_id,size,digest in db.rows("SELECT i.id,COALESCE(s.byte_size,-1),COALESCE(s.content_sha256,'UNKNOWN') FROM product_images i LEFT JOIN image_storage_records s ON s.image_id=i.id WHERE COALESCE(s.physical_deleted,FALSE)=FALSE"):
            p=uploads/(image_id+'.png')
            if not p.is_file() or (size!='-1' and p.stat().st_size!=int(size)) or (digest!='UNKNOWN' and sha(p)!=digest):raise ValueError('Live image metadata does not match restored object')
    result={'status':'passed','sourceSchema':manifest['schema'],'targetSchema':db.schema,'tables':len(tables),'images':len(manifest['images']),'databaseRoundtripEqual':True,'imageHashesEqual':True,'sourceDatabaseModified':False}
    write_json(uploads.parent/(uploads.name+'-restore-result.json'),result);return result

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('action',choices=['backup','restore']);p.add_argument('--mysql',default='/usr/bin/mysql');p.add_argument('--mysqldump',default='/usr/bin/mysqldump');p.add_argument('--defaults-file',required=True);p.add_argument('--schema',required=True);p.add_argument('--uploads',required=True);p.add_argument('--backup',required=True);a=p.parse_args()
    try:
        db=Database(a.mysql,a.defaults_file,a.schema)
        result=backup(db,a.uploads,a.backup,a.mysqldump) if a.action=='backup' else restore(db,a.backup,a.uploads,a.mysqldump)
        print(json.dumps(result));return 0
    except Exception as error:print(json.dumps({'status':'failed','reason':str(error) if isinstance(error,ValueError) else type(error).__name__}));return 2
if __name__=='__main__':raise SystemExit(main())
