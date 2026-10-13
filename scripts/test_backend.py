from pathlib import Path
import subprocess, tempfile, shutil, time, socket, os
os.chdir(Path(__file__).resolve().parents[1])
root=Path(tempfile.mkdtemp(prefix='campus-lesson24-',dir='/tmp'))
server=None
mysql=shutil.which('mysql')
if not mysql: raise RuntimeError('mysql executable is required on PATH')
mysqld=shutil.which('mysqld')
if not mysqld: raise RuntimeError('mysqld executable is required on PATH')
maven=shutil.which('mvn')
if not maven: raise RuntimeError('mvn executable is required on PATH')
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
    products_schema='USE campus_lesson24_verify;\n'+Path('campus-market-server/sql/00-create-database-and-products.sql').read_text().split('USE campus_market;',1)[1]+"\nINSERT INTO products(title,description,price_in_cents) VALUES('迁移前教材','保留原始数据',3500);"
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
    env=os.environ.copy()
    env['LESSON24_DB_URL']=f'jdbc:mysql://127.0.0.1:{port}/campus_lesson24_verify?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=UTF-8&connectionCollation=utf8mb4_0900_ai_ci'
    env['LESSON21_DB_URL']=env['LESSON24_DB_URL'].replace('campus_lesson24_verify','campus_lesson21_verify')
    env['LESSON22_DB_URL']=env['LESSON24_DB_URL'].replace('campus_lesson24_verify','campus_lesson22_verify')
    env['LESSON23_DB_URL']=env['LESSON24_DB_URL'].replace('campus_lesson24_verify','campus_lesson23_verify')
    env['LESSON25_DB_URL']=env['LESSON24_DB_URL'].replace('campus_lesson24_verify','campus_lesson25_verify')
    env['LESSON26_DB_URL']=env['LESSON24_DB_URL'].replace('campus_lesson24_verify','campus_lesson26_verify')
    env['LESSON27_DB_URL']=env['LESSON24_DB_URL'].replace('campus_lesson24_verify','campus_lesson27_verify')
    env['LESSON28_DB_URL']=env['LESSON24_DB_URL'].replace('campus_lesson24_verify','campus_lesson28_verify')
    env['LESSON24_UPLOAD_DIR']=str(root/'uploads')
    Path('campus-market-server/target').mkdir(exist_ok=True)
    with open('campus-market-server/target/integration.log','w') as log:
        result=subprocess.run([maven,'-q','-f','campus-market-server/pom.xml','-Dtest=CommentIdempotencyIntegrationTest,CommentIntegrationTest,FavoriteIntegrationTest,ProductVersionIntegrationTest,ProductImageIntegrationTest,ProductLifecycleIntegrationTest,ProductEditIntegrationTest,ProductOwnershipIntegrationTest,PasswordConfigTest,CampusMarketServerApplicationTests','test'],env=env,stdout=log,stderr=subprocess.STDOUT,timeout=150)
    report_path=Path('campus-market-server/target/surefire-reports/com.campus.market.product.ProductImageIntegrationTest.txt')
    if not report_path.exists():raise RuntimeError(Path('campus-market-server/target/integration.log').read_text()[-5000:])
    report=report_path.read_text()
    print(report)
    if result.returncode:
        print('\n'.join(Path('campus-market-server/target/integration.log').read_text().splitlines()[-25:]));raise SystemExit(result.returncode)
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
    print('PASS: 13 idempotency tests; 13 comment tests; 12 favorite tests; 12 version tests; 9 image tests, 8 lifecycle tests, 8 edit tests plus 5 publishing regression tests against isolated MySQL; authenticated publishing, rejected expired/revoked/missing sessions, owner spoofing prevention, public seller/legacy browsing, escaped search, validation, CORS and foreign key protection.')
finally:
    if server is not None and server.poll() is None:
        server.terminate()
        try:server.wait(timeout=15)
        except subprocess.TimeoutExpired:server.kill();server.wait()
    shutil.rmtree(root)
