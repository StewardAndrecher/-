"""Private MySQL CLI access. Credentials stay in a mode-0600 defaults file, never argv or reports."""
from pathlib import Path
import hashlib,json,os,re,subprocess,uuid

def identifier(value):
    if not isinstance(value,str) or not re.fullmatch(r'campus_[a-z0-9_]{1,48}',value):raise ValueError('Explicit campus schema required')
    return value

def private_file(path):
    p=Path(path).absolute()
    if p.is_symlink() or not p.is_file() or p.stat().st_mode&0o077:raise ValueError('Credentials require a regular private file (0600)')
    if p.stat().st_uid!=os.getuid():raise ValueError('Credentials must belong to the current operating-system user')
    return p

def sha(path):
    h=hashlib.sha256()
    with Path(path).open('rb') as f:
        for block in iter(lambda:f.read(1024*1024),b''):h.update(block)
    return h.hexdigest()

def write_json(path,value):
    p=Path(path);p.parent.mkdir(parents=True,exist_ok=True);temp=p.with_name(p.name+'.partial-'+uuid.uuid4().hex)
    with open(temp,'x',opener=lambda name,flags:os.open(name,flags,0o600)) as f:json.dump(value,f,indent=2,ensure_ascii=False)
    os.replace(temp,p)

class Database:
    def __init__(self,mysql,defaults,schema):
        self.mysql=str(Path(mysql).absolute());self.defaults=private_file(defaults);self.schema=identifier(schema)
        self.env={k:v for k,v in os.environ.items() if k in {'PATH','HOME','LANG','LC_ALL','TMPDIR'}}
    def command(self,schema=True):
        return [self.mysql,'--defaults-file='+str(self.defaults),'--batch','--raw','--skip-column-names','--default-character-set=utf8mb4','--binary-mode',*([self.schema] if schema else [])]
    def run(self,sql,schema=True,timeout=90):
        p=subprocess.run(self.command(schema),input=sql,text=True,capture_output=True,env=self.env,timeout=timeout)
        if p.returncode:raise RuntimeError('Database operation failed; inspect private configuration (server messages and credentials withheld)')
        return p.stdout.rstrip('\n')
    def rows(self,sql):
        text=self.run(sql);return [line.split('\t') for line in text.splitlines()] if text else []
    def dump(self,binary,path):
        command=[str(binary),'--defaults-file='+str(self.defaults),'--default-character-set=utf8mb4','--single-transaction','--skip-lock-tables','--no-tablespaces','--set-gtid-purged=OFF','--skip-comments','--skip-dump-date','--skip-add-locks','--skip-disable-keys','--hex-blob','--order-by-primary','--skip-extended-insert','--complete-insert',self.schema]
        with open(path,'wb',opener=lambda name,flags:os.open(name,flags,0o600)) as output:
            p=subprocess.run(command,stdout=output,stderr=subprocess.PIPE,env=self.env,timeout=600)
        if p.returncode:raise RuntimeError('Database dump failed; incomplete backup must not be accepted')
