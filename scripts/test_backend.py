# -*- coding: utf-8 -*-
from pathlib import Path
import subprocess, tempfile, shutil, time, socket, os
os.chdir(Path(__file__).resolve().parents[1])
result_dir=Path('.test-results');result_dir.mkdir(exist_ok=True)
root=Path(tempfile.mkdtemp(prefix='campus-lesson24-',dir=None))
server=None
if os.name!='posix':raise RuntimeError('Run this isolated MySQL workflow on macOS or Linux')
mysql=shutil.which('mysql')
mysqldump=shutil.which('mysqldump')
maven=shutil.which('mvn')
if not all([mysql,mysqldump,maven]):raise RuntimeError('mysql, mysqldump and mvn must be on PATH')
mysqld=shutil.which('mysqld')
if not mysqld:raise RuntimeError('mysqld must be on PATH')
try:
    init=subprocess.run([mysqld,'--no-defaults','--initialize-insecure',f'--datadir={root}/data'],capture_output=True,text=True,timeout=40)
    if init.returncode: raise RuntimeError(init.stderr[-2000:])
    with socket.socket() as probe:
        probe.bind(('127.0.0.1',0));port=probe.getsockname()[1]
    server=subprocess.Popen([mysqld,'--no-defaults',f'--datadir={root}/data',f'--socket={root}/mysql.sock',f'--pid-file={root}/mysql.pid',f'--log-error={root}/mysql.log',f'--port={port}','--bind-address=127.0.0.1','--mysqlx=OFF'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
    base=[mysql,'--no-defaults','--protocol=SOCKET',f'--socket={root}/mysql.sock','--user=root','--default-character-set=utf8mb4','--batch','--skip-column-names']
    def sql(text):return subprocess.run(base,input=text,capture_output=True,text=True,timeout=5)
    deadline=time.monotonic()+20
    while time.monotonic()<deadline:
        if sql('SELECT 1;').returncode==0:break
        if server.poll() is not None:raise RuntimeError((root/'mysql.log').read_text()[-2000:])
        time.sleep(.25)
    else:raise RuntimeError('Isolated database did not start')
    products_schema="""
USE campus_lesson24_verify;
CREATE TABLE products (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 title VARCHAR(50) NOT NULL,
 description VARCHAR(500) NOT NULL DEFAULT '',
 price_in_cents BIGINT NOT NULL,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 CONSTRAINT chk_products_price CHECK (price_in_cents BETWEEN 1 AND 99999999)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT INTO products(title,description,price_in_cents) VALUES('迁移前教材','保留原始数据',3500);
"""
    schema=Path('campus-market-server/sql/lesson15-users.sql').read_text()+Path('campus-market-server/sql/lesson19-login-sessions.sql').read_text()
    schema=schema.replace('USE campus_market;','USE campus_lesson24_verify;')
    r=sql('CREATE DATABASE campus_lesson24_verify CHARACTER SET utf8mb4;\n'+schema+products_schema)
    if r.returncode:raise RuntimeError(r.stderr)
    before=sql('SELECT id,title,description,price_in_cents FROM campus_lesson24_verify.products;').stdout
    migration=Path('campus-market-server/sql/lesson21-product-seller.sql').read_text().replace('USE campus_market;','USE campus_lesson24_verify;')
    r=sql(migration)
    if r.returncode:raise RuntimeError(r.stderr)
    assert sql('SELECT id,title,description,price_in_cents FROM campus_lesson24_verify.products;').stdout==before
    assert sql('SELECT COUNT(*) FROM campus_lesson24_verify.products WHERE seller_id IS NULL;').stdout.strip()=='1'
    print('PASS: schema migration preserves original product data and NULL legacy ownership',flush=True)
    r=sql('CREATE DATABASE campus_lesson21_verify CHARACTER SET utf8mb4;\n'+schema.replace('campus_lesson24_verify','campus_lesson21_verify')+products_schema.replace('campus_lesson24_verify','campus_lesson21_verify')+migration.replace('campus_lesson24_verify','campus_lesson21_verify'))
    if r.returncode:raise RuntimeError(r.stderr)
    r=sql('CREATE DATABASE campus_lesson22_verify CHARACTER SET utf8mb4;\n'+schema.replace('campus_lesson24_verify','campus_lesson22_verify')+products_schema.replace('campus_lesson24_verify','campus_lesson22_verify')+migration.replace('campus_lesson24_verify','campus_lesson22_verify'))
    if r.returncode:raise RuntimeError(r.stderr)
    status_migration=Path('campus-market-server/sql/lesson23-product-status.sql').read_text()
    for name in ['campus_lesson24_verify','campus_lesson21_verify','campus_lesson22_verify']:
        r=sql(status_migration.replace('USE campus_market;','USE '+name+';'))
        if r.returncode:raise RuntimeError(r.stderr)
    r=sql('CREATE DATABASE campus_lesson23_verify CHARACTER SET utf8mb4;\n'+schema.replace('campus_lesson24_verify','campus_lesson23_verify')+products_schema.replace('campus_lesson24_verify','campus_lesson23_verify')+migration.replace('campus_lesson24_verify','campus_lesson23_verify')+status_migration.replace('USE campus_market;','USE campus_lesson23_verify;'))
    if r.returncode:raise RuntimeError(r.stderr)
    image_migration=Path('campus-market-server/sql/lesson24-product-cover.sql').read_text()
    for name in ['campus_lesson24_verify','campus_lesson21_verify','campus_lesson22_verify','campus_lesson23_verify']:
        r=sql(image_migration.replace('USE campus_market;','USE '+name+';'))
        if r.returncode:raise RuntimeError(r.stderr)
    version_migration=Path('campus-market-server/sql/lesson25-product-version.sql').read_text()
    r=sql('CREATE DATABASE campus_lesson25_verify CHARACTER SET utf8mb4;\n'+schema.replace('campus_lesson24_verify','campus_lesson25_verify')+products_schema.replace('campus_lesson24_verify','campus_lesson25_verify')+migration.replace('campus_lesson24_verify','campus_lesson25_verify')+status_migration.replace('USE campus_market;','USE campus_lesson25_verify;')+image_migration.replace('USE campus_market;','USE campus_lesson25_verify;'))
    if r.returncode:raise RuntimeError(r.stderr)
    for name in ['campus_lesson21_verify','campus_lesson22_verify','campus_lesson23_verify','campus_lesson24_verify','campus_lesson25_verify']:
        r=sql(version_migration.replace('USE campus_market;','USE '+name+';'))
        if r.returncode:raise RuntimeError(r.stderr)
    assert sql('SELECT id,title,description,price_in_cents FROM campus_lesson24_verify.products;').stdout==before
    assert sql('SELECT version FROM campus_lesson24_verify.products;').stdout.strip()=='0'
    print('PASS: version migration retains data and initializes existing versions to zero',flush=True)
    r=sql('CREATE DATABASE campus_lesson26_verify CHARACTER SET utf8mb4;\n'+schema.replace('campus_lesson24_verify','campus_lesson26_verify')+products_schema.replace('campus_lesson24_verify','campus_lesson26_verify')+migration.replace('campus_lesson24_verify','campus_lesson26_verify')+status_migration.replace('USE campus_market;','USE campus_lesson26_verify;')+image_migration.replace('USE campus_market;','USE campus_lesson26_verify;')+version_migration.replace('USE campus_market;','USE campus_lesson26_verify;'))
    if r.returncode:raise RuntimeError(r.stderr)
    favorite_migration=Path('campus-market-server/sql/lesson26-product-favorites.sql').read_text()
    for number in range(21,27):
        r=sql(favorite_migration.replace('USE campus_market;',f'USE campus_lesson{number}_verify;'))
        if r.returncode:raise RuntimeError(r.stderr)
    r=sql('CREATE DATABASE campus_lesson27_verify CHARACTER SET utf8mb4;\n'+schema.replace('campus_lesson24_verify','campus_lesson27_verify')+products_schema.replace('campus_lesson24_verify','campus_lesson27_verify')+migration.replace('campus_lesson24_verify','campus_lesson27_verify')+status_migration.replace('USE campus_market;','USE campus_lesson27_verify;')+image_migration.replace('USE campus_market;','USE campus_lesson27_verify;')+version_migration.replace('USE campus_market;','USE campus_lesson27_verify;')+favorite_migration.replace('USE campus_market;','USE campus_lesson27_verify;'))
    if r.returncode:raise RuntimeError(r.stderr)
    comment_migration=Path('campus-market-server/sql/lesson27-product-comments.sql').read_text()
    for number in range(21,28):
        r=sql(comment_migration.replace('USE campus_market;',f'USE campus_lesson{number}_verify;'))
        if r.returncode:raise RuntimeError(r.stderr)
    r=sql('CREATE DATABASE campus_lesson28_verify CHARACTER SET utf8mb4;\n'+schema.replace('campus_lesson24_verify','campus_lesson28_verify')+products_schema.replace('campus_lesson24_verify','campus_lesson28_verify')+migration.replace('campus_lesson24_verify','campus_lesson28_verify')+status_migration.replace('USE campus_market;','USE campus_lesson28_verify;')+image_migration.replace('USE campus_market;','USE campus_lesson28_verify;')+version_migration.replace('USE campus_market;','USE campus_lesson28_verify;')+favorite_migration.replace('USE campus_market;','USE campus_lesson28_verify;')+comment_migration.replace('USE campus_market;','USE campus_lesson28_verify;'))
    if r.returncode:raise RuntimeError(r.stderr)
    r=sql("USE campus_lesson28_verify; INSERT INTO users(username,password_hash,nickname) VALUES('migration_01','fixture-only','迁移测试'); INSERT INTO product_comments(product_id,author_id,content) VALUES(1,1,'历史留言甲'),(1,1,'历史留言乙'); UPDATE product_comments SET is_deleted=TRUE,deleted_at=CURRENT_TIMESTAMP(6) WHERE content='历史留言乙';")
    if r.returncode:raise RuntimeError(r.stderr)
    comments_before=sql('SELECT id,product_id,author_id,content,is_deleted,created_at,deleted_at FROM campus_lesson28_verify.product_comments ORDER BY id;').stdout
    idempotency_migration=Path('campus-market-server/sql/lesson28-comment-idempotency.sql').read_text()
    for number in range(21,29):
        r=sql(idempotency_migration.replace('USE campus_market;',f'USE campus_lesson{number}_verify;'))
        if r.returncode:raise RuntimeError(r.stderr)
    assert sql('SELECT id,product_id,author_id,content,is_deleted,created_at,deleted_at FROM campus_lesson28_verify.product_comments ORDER BY id;').stdout==comments_before
    assert sql('SELECT COUNT(DISTINCT request_id) FROM campus_lesson28_verify.product_comments;').stdout.strip()=='2'
    print('PASS: idempotency migration preserves historical/withdrawn rows and backfills distinct request IDs',flush=True)
    category_migration=Path('campus-market-server/sql/lesson31-product-category.sql').read_text()
    old_rows=sql('SELECT id,title,description,price_in_cents,seller_id,status,cover_image_id,version FROM campus_lesson24_verify.products;').stdout
    for number in range(21,29):
        r=sql(category_migration.replace('USE campus_market;',f'USE campus_lesson{number}_verify;'))
        if r.returncode:raise RuntimeError(r.stderr)
    assert sql('SELECT id,title,description,price_in_cents,seller_id,status,cover_image_id,version FROM campus_lesson24_verify.products;').stdout==old_rows
    assert sql('SELECT category_code FROM campus_lesson24_verify.products;').stdout.strip()=='UNCLASSIFIED'
    print('PASS: category migration preserves all original fields and uses UNCLASSIFIED',flush=True)
    publication_migration=Path('campus-market-server/sql/lesson38-product-publications.sql').read_text()
    for number in range(21,29):
        r=sql(publication_migration.replace('USE campus_market;',f'USE campus_lesson{number}_verify;'))
        if r.returncode:raise RuntimeError(r.stderr)
    sold_migration=Path('campus-market-server/sql/lesson43-product-sold.sql').read_text()
    before_sold=sql('SELECT id,title,status,version FROM campus_lesson24_verify.products;').stdout
    for number in range(21,29):
        r=sql(sold_migration.replace('USE campus_market;',f'USE campus_lesson{number}_verify;'))
        if r.returncode:raise RuntimeError(r.stderr)
    assert sql('SELECT id,title,status,version FROM campus_lesson24_verify.products;').stdout==before_sold
    print('PASS: SOLD migration preserves original rows and versions',flush=True)
    isolated_schema=subprocess.run([mysqldump,'--no-defaults','--protocol=SOCKET',f'--socket={root}/mysql.sock','--user=root','--no-data','--no-tablespaces','--set-gtid-purged=OFF','campus_lesson25_verify'],capture_output=True,text=True,check=True).stdout
    r=sql('CREATE DATABASE campus_lesson43_verify CHARACTER SET utf8mb4; USE campus_lesson43_verify;\n'+isolated_schema)
    if r.returncode:raise RuntimeError(r.stderr)
    r=sql('CREATE DATABASE campus_lesson44_verify CHARACTER SET utf8mb4; USE campus_lesson44_verify;\n'+isolated_schema)
    if r.returncode:raise RuntimeError(r.stderr)
    r=sql('CREATE DATABASE campus_lesson45_verify CHARACTER SET utf8mb4; USE campus_lesson45_verify;\n'+isolated_schema)
    if r.returncode:raise RuntimeError(r.stderr)
    r=sql('CREATE DATABASE campus_lesson46_verify CHARACTER SET utf8mb4; USE campus_lesson46_verify;\n'+isolated_schema)
    if r.returncode:raise RuntimeError(r.stderr)
    r=sql('CREATE DATABASE campus_lesson49_verify CHARACTER SET utf8mb4; USE campus_lesson49_verify;\n'+isolated_schema)
    if r.returncode:raise RuntimeError(r.stderr)
    r=sql('CREATE DATABASE campus_lesson47_verify CHARACTER SET utf8mb4; USE campus_lesson47_verify;\n'+isolated_schema)
    if r.returncode:raise RuntimeError(r.stderr)
    draft_migration=Path('campus-market-server/sql/lesson49-product-draft-backups.sql').read_text()
    r=sql(draft_migration.replace('USE campus_market;','USE campus_lesson49_verify;'))
    if r.returncode:raise RuntimeError(r.stderr)
    r=sql('CREATE DATABASE campus_lesson50_verify CHARACTER SET utf8mb4; USE campus_lesson50_verify;\n'+isolated_schema+'\n'+draft_migration.replace('USE campus_market;','USE campus_lesson50_verify;'))
    if r.returncode:raise RuntimeError(r.stderr)
    r=sql('CREATE DATABASE campus_lesson51_verify CHARACTER SET utf8mb4; USE campus_lesson51_verify;\n'+isolated_schema+'\n'+draft_migration.replace('USE campus_market;','USE campus_lesson51_verify;'))
    if r.returncode:raise RuntimeError(r.stderr)
    r=sql('CREATE DATABASE campus_lesson52_verify CHARACTER SET utf8mb4; USE campus_lesson52_verify;\n'+isolated_schema+'\n'+draft_migration.replace('USE campus_market;','USE campus_lesson52_verify;'))
    if r.returncode:raise RuntimeError(r.stderr)
    intent_migration=Path('campus-market-server/sql/lesson53-purchase-intents.sql').read_text()
    r=sql('CREATE DATABASE campus_lesson53_verify CHARACTER SET utf8mb4; USE campus_lesson53_verify;\n'+isolated_schema+'\n'+draft_migration.replace('USE campus_market;','USE campus_lesson53_verify;')+'\n'+intent_migration.replace('USE campus_market;','USE campus_lesson53_verify;'))
    if r.returncode:raise RuntimeError(r.stderr)
    cancellation_migration=Path('campus-market-server/sql/lesson54-intent-cancellation.sql').read_text()
    r=sql(cancellation_migration.replace('USE campus_market;','USE campus_lesson53_verify;'))
    if r.returncode:raise RuntimeError(r.stderr)
    r=sql('CREATE DATABASE campus_lesson54_verify CHARACTER SET utf8mb4; USE campus_lesson54_verify;\n'+isolated_schema+'\n'+draft_migration.replace('USE campus_market;','USE campus_lesson54_verify;')+'\n'+intent_migration.replace('USE campus_market;','USE campus_lesson54_verify;')+'\n'+cancellation_migration.replace('USE campus_market;','USE campus_lesson54_verify;'))
    if r.returncode:raise RuntimeError(r.stderr)
    acceptance_migration=Path('campus-market-server/sql/lesson55-intent-acceptance.sql').read_text()
    for name in ['campus_lesson53_verify','campus_lesson54_verify']:
        r=sql(acceptance_migration.replace('USE campus_market;','USE '+name+';'))
        if r.returncode:raise RuntimeError(r.stderr)
    r=sql('CREATE DATABASE campus_lesson55_verify CHARACTER SET utf8mb4; USE campus_lesson55_verify;\n'+isolated_schema+'\n'+draft_migration.replace('USE campus_market;','USE campus_lesson55_verify;')+'\n'+intent_migration.replace('USE campus_market;','USE campus_lesson55_verify;')+'\n'+cancellation_migration.replace('USE campus_market;','USE campus_lesson55_verify;')+'\n'+acceptance_migration.replace('USE campus_market;','USE campus_lesson55_verify;'))
    if r.returncode:raise RuntimeError(r.stderr)
    r=sql('CREATE DATABASE campus_lesson56_verify CHARACTER SET utf8mb4; USE campus_lesson56_verify;\n'+isolated_schema+'\n'+draft_migration.replace('USE campus_market;','USE campus_lesson56_verify;')+'\n'+intent_migration.replace('USE campus_market;','USE campus_lesson56_verify;')+'\n'+cancellation_migration.replace('USE campus_market;','USE campus_lesson56_verify;')+'\n'+acceptance_migration.replace('USE campus_market;','USE campus_lesson56_verify;'))
    if r.returncode:raise RuntimeError(r.stderr)
    r=sql('CREATE DATABASE campus_lesson57_verify CHARACTER SET utf8mb4; USE campus_lesson57_verify;\n'+isolated_schema+'\n'+draft_migration.replace('USE campus_market;','USE campus_lesson57_verify;')+'\n'+intent_migration.replace('USE campus_market;','USE campus_lesson57_verify;')+'\n'+cancellation_migration.replace('USE campus_market;','USE campus_lesson57_verify;')+'\n'+acceptance_migration.replace('USE campus_market;','USE campus_lesson57_verify;'))
    if r.returncode:raise RuntimeError(r.stderr)
    r=sql('CREATE DATABASE campus_lesson58_verify CHARACTER SET utf8mb4; USE campus_lesson58_verify;\n'+isolated_schema+'\n'+draft_migration.replace('USE campus_market;','USE campus_lesson58_verify;')+'\n'+intent_migration.replace('USE campus_market;','USE campus_lesson58_verify;')+'\n'+cancellation_migration.replace('USE campus_market;','USE campus_lesson58_verify;')+'\n'+acceptance_migration.replace('USE campus_market;','USE campus_lesson58_verify;'))
    if r.returncode:raise RuntimeError(r.stderr)
    r=sql('CREATE DATABASE campus_lesson59_verify CHARACTER SET utf8mb4; USE campus_lesson59_verify;\n'+isolated_schema+'\n'+draft_migration.replace('USE campus_market;','USE campus_lesson59_verify;')+'\n'+intent_migration.replace('USE campus_market;','USE campus_lesson59_verify;')+'\n'+cancellation_migration.replace('USE campus_market;','USE campus_lesson59_verify;')+'\n'+acceptance_migration.replace('USE campus_market;','USE campus_lesson59_verify;'))
    if r.returncode:raise RuntimeError(r.stderr)
    r=sql(Path('campus-market-server/sql/lesson59-intent-messages.sql').read_text().replace('USE campus_market;','USE campus_lesson59_verify;'))
    if r.returncode:raise RuntimeError(r.stderr)
    r=sql('CREATE DATABASE campus_lesson60_verify CHARACTER SET utf8mb4; USE campus_lesson60_verify;\n'+isolated_schema+'\n'+draft_migration.replace('USE campus_market;','USE campus_lesson60_verify;')+'\n'+intent_migration.replace('USE campus_market;','USE campus_lesson60_verify;')+'\n'+cancellation_migration.replace('USE campus_market;','USE campus_lesson60_verify;')+'\n'+acceptance_migration.replace('USE campus_market;','USE campus_lesson60_verify;'))
    if r.returncode:raise RuntimeError(r.stderr)
    r=sql(Path('campus-market-server/sql/lesson59-intent-messages.sql').read_text().replace('USE campus_market;','USE campus_lesson60_verify;'))
    if r.returncode:raise RuntimeError(r.stderr)
    withdrawal_migration=Path('campus-market-server/sql/lesson60-intent-message-withdrawal.sql').read_text()
    for name in ['campus_lesson59_verify','campus_lesson60_verify']:
        r=sql(withdrawal_migration.replace('USE campus_market;','USE '+name+';'))
        if r.returncode:raise RuntimeError(r.stderr)
    final_schema=subprocess.run([mysqldump,'--no-defaults','--protocol=SOCKET',f'--socket={root}/mysql.sock','--user=root','--no-data','--no-tablespaces','--set-gtid-purged=OFF','campus_lesson60_verify'],capture_output=True,text=True,check=True).stdout
    r=sql('CREATE DATABASE campus_lesson61_verify CHARACTER SET utf8mb4; USE campus_lesson61_verify;\n'+final_schema+'\n'+Path('campus-market-server/sql/lesson61-intent-message-read-state.sql').read_text().replace('USE campus_market;','USE campus_lesson61_verify;'))
    if r.returncode:raise RuntimeError(r.stderr)
    final62_schema=subprocess.run([mysqldump,'--no-defaults','--protocol=SOCKET',f'--socket={root}/mysql.sock','--user=root','--no-data','--no-tablespaces','--set-gtid-purged=OFF','campus_lesson61_verify'],capture_output=True,text=True,check=True).stdout
    r=sql('CREATE DATABASE campus_lesson62_verify CHARACTER SET utf8mb4; USE campus_lesson62_verify;\n'+final62_schema)
    if r.returncode:raise RuntimeError(r.stderr)
    final63_schema=subprocess.run([mysqldump,'--no-defaults','--protocol=SOCKET',f'--socket={root}/mysql.sock','--user=root','--no-data','--no-tablespaces','--set-gtid-purged=OFF','campus_lesson62_verify'],capture_output=True,text=True,check=True).stdout
    r=sql('CREATE DATABASE campus_lesson63_verify CHARACTER SET utf8mb4; USE campus_lesson63_verify;\n'+final63_schema)
    if r.returncode:raise RuntimeError(r.stderr)
    final64_schema=subprocess.run([mysqldump,'--no-defaults','--protocol=SOCKET',f'--socket={root}/mysql.sock','--user=root','--no-data','--no-tablespaces','--set-gtid-purged=OFF','campus_lesson63_verify'],capture_output=True,text=True,check=True).stdout
    r=sql('CREATE DATABASE campus_lesson64_verify CHARACTER SET utf8mb4; USE campus_lesson64_verify;\n'+final64_schema)
    if r.returncode:raise RuntimeError(r.stderr)
    r=sql('CREATE DATABASE campus_lesson65_verify CHARACTER SET utf8mb4; USE campus_lesson65_verify;\n'+final64_schema)
    if r.returncode:raise RuntimeError(r.stderr)
    r=sql('CREATE DATABASE campus_lesson66_verify CHARACTER SET utf8mb4; USE campus_lesson66_verify;\n'+final64_schema)
    if r.returncode:raise RuntimeError(r.stderr)
    # Old intention regression fixtures now exercise the current read model as well.
    for number in range(53,59):
        r=sql(Path('campus-market-server/sql/lesson59-intent-messages.sql').read_text().replace('USE campus_market;',f'USE campus_lesson{number}_verify;')+'\n'+withdrawal_migration.replace('USE campus_market;',f'USE campus_lesson{number}_verify;'))
        if r.returncode:raise RuntimeError(r.stderr)
    env=os.environ.copy()
    # Each test context gets its own pool; cap only the isolated test runner.
    env['SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE']='4'
    env['SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE']='0'
    env['LESSON24_DB_URL']=f'jdbc:mysql://127.0.0.1:{port}/campus_lesson24_verify?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=UTF-8&connectionCollation=utf8mb4_0900_ai_ci'
    env['LESSON21_DB_URL']=env['LESSON24_DB_URL'].replace('campus_lesson24_verify','campus_lesson21_verify')
    env['LESSON22_DB_URL']=env['LESSON24_DB_URL'].replace('campus_lesson24_verify','campus_lesson22_verify')
    env['LESSON23_DB_URL']=env['LESSON24_DB_URL'].replace('campus_lesson24_verify','campus_lesson23_verify')
    env['LESSON25_DB_URL']=env['LESSON24_DB_URL'].replace('campus_lesson24_verify','campus_lesson25_verify')
    env['LESSON26_DB_URL']=env['LESSON24_DB_URL'].replace('campus_lesson24_verify','campus_lesson26_verify')
    env['LESSON27_DB_URL']=env['LESSON24_DB_URL'].replace('campus_lesson24_verify','campus_lesson27_verify')
    env['LESSON28_DB_URL']=env['LESSON24_DB_URL'].replace('campus_lesson24_verify','campus_lesson28_verify')
    env['LESSON43_DB_URL']=env['LESSON24_DB_URL'].replace('campus_lesson24_verify','campus_lesson43_verify')
    env['LESSON44_DB_URL']=env['LESSON24_DB_URL'].replace('campus_lesson24_verify','campus_lesson44_verify')
    env['LESSON45_DB_URL']=env['LESSON24_DB_URL'].replace('campus_lesson24_verify','campus_lesson45_verify')
    env['LESSON46_DB_URL']=env['LESSON24_DB_URL'].replace('campus_lesson24_verify','campus_lesson46_verify')
    env['LESSON47_DB_URL']=env['LESSON24_DB_URL'].replace('campus_lesson24_verify','campus_lesson47_verify')
    env['LESSON49_DB_URL']=env['LESSON24_DB_URL'].replace('campus_lesson24_verify','campus_lesson49_verify')
    env['LESSON50_DB_URL']=env['LESSON24_DB_URL'].replace('campus_lesson24_verify','campus_lesson50_verify')
    env['LESSON51_DB_URL']=env['LESSON24_DB_URL'].replace('campus_lesson24_verify','campus_lesson51_verify')
    env['LESSON52_DB_URL']=env['LESSON24_DB_URL'].replace('campus_lesson24_verify','campus_lesson52_verify')
    env['LESSON53_DB_URL']=env['LESSON24_DB_URL'].replace('campus_lesson24_verify','campus_lesson53_verify')
    env['LESSON54_DB_URL']=env['LESSON24_DB_URL'].replace('campus_lesson24_verify','campus_lesson54_verify')
    env['LESSON55_DB_URL']=env['LESSON24_DB_URL'].replace('campus_lesson24_verify','campus_lesson55_verify')
    env['LESSON56_DB_URL']=env['LESSON24_DB_URL'].replace('campus_lesson24_verify','campus_lesson56_verify')
    env['LESSON57_DB_URL']=env['LESSON24_DB_URL'].replace('campus_lesson24_verify','campus_lesson57_verify')
    env['LESSON58_DB_URL']=env['LESSON24_DB_URL'].replace('campus_lesson24_verify','campus_lesson58_verify')
    env['LESSON59_DB_URL']=env['LESSON24_DB_URL'].replace('campus_lesson24_verify','campus_lesson59_verify')
    env['LESSON60_DB_URL']=env['LESSON24_DB_URL'].replace('campus_lesson24_verify','campus_lesson60_verify')
    env['LESSON61_DB_URL']=env['LESSON24_DB_URL'].replace('campus_lesson24_verify','campus_lesson61_verify')
    env['LESSON62_DB_URL']=env['LESSON24_DB_URL'].replace('campus_lesson24_verify','campus_lesson62_verify')
    env['LESSON63_DB_URL']=env['LESSON24_DB_URL'].replace('campus_lesson24_verify','campus_lesson63_verify')
    env['LESSON66_DB_URL']=env['LESSON24_DB_URL'].replace('campus_lesson24_verify','campus_lesson66_verify')
    env['LESSON65_DB_URL']=env['LESSON24_DB_URL'].replace('campus_lesson24_verify','campus_lesson65_verify')
    env['LESSON64_DB_URL']=env['LESSON24_DB_URL'].replace('campus_lesson24_verify','campus_lesson64_verify')
    env['LESSON24_UPLOAD_DIR']=str(root/'uploads')
    with open('.test-results/backend.log','w') as log:
        result=subprocess.run([maven,*(['-o'] if os.environ.get('MAVEN_OFFLINE')=='1' else []),'-q','-f','campus-market-server/pom.xml','-Dtest=PurchaseIntentReceivedTimeIntegrationTest,PurchaseIntentSortIntegrationTest,PurchaseIntentUnreadSummaryIntegrationTest,PurchaseIntentUnreadFilterIntegrationTest,PurchaseIntentUnreadIntegrationTest,PurchaseIntentMessageReadStateIntegrationTest,PurchaseIntentMessageWithdrawalIntegrationTest,PurchaseIntentMessageIntegrationTest,PurchaseIntentDetailIntegrationTest,PurchaseIntentSummaryIntegrationTest,PurchaseIntentStatusFilterIntegrationTest,PurchaseIntentAcceptanceIntegrationTest,PurchaseIntentCancellationIntegrationTest,PurchaseIntentIntegrationTest,DraftBackupSortIntegrationTest,DraftBackupCategoryIntegrationTest,DraftBackupQueryIntegrationTest,DraftBackupIntegrationTest,ProductOwnerSummaryIntegrationTest,ProductMineSortIntegrationTest,ProductMineSearchIntegrationTest,ProductMineFilterIntegrationTest,ProductSoldIntegrationTest,PublicationIntegrationTest,SellerStoreIntegrationTest,ProductPopularityIntegrationTest,UserProfileIntegrationTest,ProductSortIntegrationTest,CategoryIntegrationTest,HealthEndpointIntegrationTest,DatabaseUnavailableHealthTest,CommentIdempotencyIntegrationTest,CommentIntegrationTest,FavoriteIntegrationTest,ProductVersionIntegrationTest,ProductImageIntegrationTest,ProductLifecycleIntegrationTest,ProductEditIntegrationTest,ProductOwnershipIntegrationTest,PasswordConfigTest,CampusMarketServerApplicationTests','clean','test'],env=env,stdout=log,stderr=subprocess.STDOUT,timeout=660)
    report_path=Path('campus-market-server/target/surefire-reports/com.campus.market.product.ProductImageIntegrationTest.txt')
    if not report_path.exists():raise RuntimeError(Path('.test-results/backend.log').read_text()[-5000:])
    report=report_path.read_text()
    print(report)
    if result.returncode:
        print('\n'.join(Path('.test-results/backend.log').read_text().splitlines()[-25:]));raise SystemExit(result.returncode)
    assert 'Skipped: 0' in report
    regression=Path('campus-market-server/target/surefire-reports/com.campus.market.product.ProductOwnershipIntegrationTest.txt').read_text()
    print(regression)
    assert 'Skipped: 0' in regression
    print(Path('campus-market-server/target/surefire-reports/com.campus.market.product.ProductEditIntegrationTest.txt').read_text())
    print(Path('campus-market-server/target/surefire-reports/com.campus.market.product.ProductLifecycleIntegrationTest.txt').read_text())
    print(Path('campus-market-server/target/surefire-reports/com.campus.market.product.ProductVersionIntegrationTest.txt').read_text())
    print(Path('campus-market-server/target/surefire-reports/com.campus.market.product.FavoriteIntegrationTest.txt').read_text())
    print(Path('campus-market-server/target/surefire-reports/com.campus.market.product.CommentIntegrationTest.txt').read_text())
    print(Path('campus-market-server/target/surefire-reports/com.campus.market.product.CommentIdempotencyIntegrationTest.txt').read_text())
    print(Path('campus-market-server/target/surefire-reports/com.campus.market.health.HealthEndpointIntegrationTest.txt').read_text())
    print(Path('campus-market-server/target/surefire-reports/com.campus.market.health.DatabaseUnavailableHealthTest.txt').read_text())
    print(Path('campus-market-server/target/surefire-reports/com.campus.market.product.CategoryIntegrationTest.txt').read_text())
    print(Path('campus-market-server/target/surefire-reports/com.campus.market.product.ProductSortIntegrationTest.txt').read_text())
    print(Path('campus-market-server/target/surefire-reports/com.campus.market.user.UserProfileIntegrationTest.txt').read_text())
    import xml.etree.ElementTree as ET
    suites=[ET.parse(p).getroot().attrib for p in Path('campus-market-server/target/surefire-reports').glob('TEST-*.xml')]
    assert sum(int(s['tests']) for s in suites)==504
    assert all(int(s['failures'])==int(s['errors'])==int(s['skipped'])==0 for s in suites)
    print('PASS: all 504 backend tests, including 20 received-time tests and 484 regression tests; zero failures/errors/skips.')

finally:
    if server is not None and server.poll() is None:
        server.terminate()
        try:server.wait(timeout=15)
        except subprocess.TimeoutExpired:server.kill();server.wait()
    shutil.rmtree(root)
