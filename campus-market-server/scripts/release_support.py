"""Release bundle structure, schema assembly and integrity checks; standard library only."""
from pathlib import Path
import hashlib,json,re,zipfile

SCHEMA_FILES=['00-create-database-and-products.sql','lesson15-users.sql','lesson19-login-sessions.sql','lesson21-product-seller.sql','lesson23-product-status.sql','lesson24-product-cover.sql','lesson25-product-version.sql','lesson26-product-favorites.sql','lesson27-product-comments.sql','lesson28-comment-idempotency.sql','lesson31-product-category.sql','lesson38-product-publications.sql','lesson43-product-sold.sql','lesson49-product-draft-backups.sql','lesson53-purchase-intents.sql','lesson54-intent-cancellation.sql','lesson55-intent-acceptance.sql','lesson59-intent-messages.sql','lesson60-intent-message-withdrawal.sql','lesson61-intent-message-read-state.sql','lesson69-market-orders.sql','lesson70-order-lifecycle.sql','lesson71-order-fulfillment.sql','lesson73-order-events.sql','lesson74-order-timeout-outbox.sql','lesson87-product-newest-indexes.sql','lesson89-admin-roles.sql','lesson90-product-moderation.sql','lesson91-product-reports.sql','lesson92-notifications.sql','lesson101-order-reviews.sql','lesson104-order-issues.sql','lesson105-order-issue-resolutions.sql','lesson128-product-details-and-profiles.sql','lesson129-order-refund-applications.sql','lesson130-verified-email-binding.sql','lesson131-content-and-abuse-governance.sql','lesson132-image-storage-management.sql','lesson133-payment-financial-evidence.sql','lesson134-external-identity-and-media-indexes.sql','lesson135-bounded-data-retention.sql','lesson136-order-query-indexes.sql']
DEPLOYMENT_FILES={'render_deployment.py','check_readiness.py','PUBLIC-LAUNCH.md'}
DATABASE_FILES={'campus-backup.service','campus-backup.timer','DATABASE.md'}
OPERATION_FILES={'database_common.py','database_backup.py','database_retention.py','database_accounts.py','database_reconcile.py','database_daily.py'}
PAYLOAD={'app.jar','database/init.sql','config/application.properties.example','RUN.md'}|{'deployment/'+name for name in DEPLOYMENT_FILES}|{'database/'+name for name in DATABASE_FILES}|{'scripts/'+name for name in OPERATION_FILES}
START_CLASS='com.campus.market.CampusMarketServerApplication'

def sha256(path):
    digest=hashlib.sha256()
    with Path(path).open('rb') as source:
        for block in iter(lambda:source.read(1024*1024),b''):digest.update(block)
    return digest.hexdigest()

def assemble_schema(project):
    parts=['-- Campus Market: ONLY for a new empty campus_market database.\n-- Never execute this initialization on an existing business database.\n']
    for name in SCHEMA_FILES:
        p=Path(project)/'sql'/name
        if p.is_symlink() or not p.is_file():raise RuntimeError('Missing or unsafe schema file: '+name)
        parts.extend(['\n-- ===== '+name+' =====\n',p.read_text(),'\n'])
    return ''.join(parts)

def inspect_jar(path):
    with zipfile.ZipFile(path) as z:
        names=z.namelist()
        if len(names)!=len(set(names)):raise RuntimeError('Duplicate JAR entries')
        if z.testzip() is not None:raise RuntimeError('Corrupted JAR entry')
        headers={};key=None
        for line in z.read('META-INF/MANIFEST.MF').decode().replace('\r\n','\n').splitlines():
            if line.startswith(' ') and key:headers[key]+=line[1:]
            elif ': ' in line:key,value=line.split(': ',1);headers[key]=value
        if headers.get('Main-Class')!='org.springframework.boot.loader.launch.JarLauncher' or headers.get('Start-Class')!=START_CLASS:raise RuntimeError('Not the expected executable Spring Boot JAR')
        required={'org/springframework/boot/loader/launch/JarLauncher.class','BOOT-INF/classes/'+START_CLASS.replace('.','/')+'.class','BOOT-INF/classes/application.properties','BOOT-INF/classes/application-production.properties','META-INF/spring.factories','BOOT-INF/classes/com/campus/market/config/ProductionEnvironmentGuard.class','BOOT-INF/classes/com/campus/market/order/TradeCapabilitiesController.class'}
        if not required.issubset(names):raise RuntimeError('Executable JAR is missing launcher, application or configuration')
        if b'com.campus.market.config.ProductionEnvironmentGuard' not in z.read('META-INF/spring.factories'):raise RuntimeError('Production guard registration is missing')
        libraries=[n for n in names if n.startswith('BOOT-INF/lib/') and n.endswith('.jar')]
        if not libraries:raise RuntimeError('Runtime dependencies are missing')
        if any(re.match(r'(junit|mockito|spring-test|spring-boot-starter-.*test)',Path(n).name) for n in libraries):raise RuntimeError('Test dependencies must not enter the release JAR')
        if any(n.startswith('BOOT-INF/classes/') and n.endswith(('Test.class','Tests.class')) for n in names):raise RuntimeError('Test classes must not enter the release JAR')
        return {'mainClass':headers['Main-Class'],'startClass':headers['Start-Class'],'runtimeLibraries':len(libraries),'jarEntries':len(names)}

def verify_bundle(folder):
    folder=Path(folder)
    if folder.is_symlink() or not folder.is_dir():raise RuntimeError('Bundle must be a real directory')
    all_paths=list(folder.rglob('*'))
    if any(p.is_symlink() for p in all_paths):raise RuntimeError('Bundle symlinks are not accepted')
    files={p.relative_to(folder).as_posix() for p in all_paths if p.is_file()}
    if files!=PAYLOAD|{'manifest.json','SHA256SUMS'}:raise RuntimeError('Bundle file set does not match the release contract')
    manifest=json.loads((folder/'manifest.json').read_text())
    if manifest.get('schemaVersion')!=1 or set(manifest.get('files',{}))!=PAYLOAD:raise RuntimeError('Invalid release manifest')
    digests={name:sha256(folder/name) for name in sorted(PAYLOAD|{'manifest.json'})}
    for name in PAYLOAD:
        if manifest['files'][name]!=digests[name]:raise RuntimeError('Release file checksum mismatch: '+name)
    checks={}
    for line in (folder/'SHA256SUMS').read_text().splitlines():
        match=re.fullmatch(r'([0-9a-f]{64})  (.+)',line)
        if not match or match[2] in checks:raise RuntimeError('Invalid SHA256SUMS')
        checks[match[2]]=match[1]
    if checks!=digests:raise RuntimeError('SHA256SUMS coverage or checksum mismatch')
    jar=inspect_jar(folder/'app.jar')
    return {'status':'verified','files':len(files),'appJarSha256':digests['app.jar'],'jar':jar}
