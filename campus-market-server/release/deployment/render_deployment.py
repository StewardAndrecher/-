#!/usr/bin/env python3
# coding: utf-8
"""Render reviewable Linux deployment files; never installs or starts a service."""
import argparse,re
from pathlib import Path
from urllib.parse import urlsplit

def public_origin(value):
    try:
        parsed=urlsplit(value)
        if parsed.scheme!='https' or parsed.username or parsed.password or parsed.path or parsed.query or parsed.fragment or parsed.port not in (None,443):raise ValueError()
        host=parsed.hostname or ''
        if not re.fullmatch(r'(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z](?:[a-z0-9-]{0,61}[a-z0-9])?',host):raise ValueError()
        if host.rsplit('.',1)[-1] in {'localhost','local','invalid','example','test'}:raise ValueError()
        return 'https://'+host,host
    except (ValueError,TypeError):raise ValueError('Use a real HTTPS origin without credentials, path, query or fragment')

def render(origin,backend_port=8080):
    origin,host=public_origin(origin)
    if type(backend_port) is not int or not 1024<=backend_port<=65535:raise ValueError('Backend port must be 1024..65535')
    nginx=r'''# Include inside nginx's http block, after obtaining a real certificate.
server {
    listen 80;
    server_name HOST_VALUE;
    return 308 https://HOST_VALUE$request_uri;
}
server {
    listen 443 ssl;
    server_name HOST_VALUE;
    ssl_certificate /etc/letsencrypt/live/HOST_VALUE/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/HOST_VALUE/privkey.pem;
    ssl_protocols TLSv1.2 TLSv1.3;
    root /srv/campus-market/current/web;
    index index.html;
    client_max_body_size 6m;
    add_header X-Content-Type-Options nosniff always;
    add_header X-Frame-Options DENY always;
    add_header Referrer-Policy strict-origin-when-cross-origin always;
    add_header Strict-Transport-Security "max-age=15552000" always;
    if ($host != HOST_VALUE) { return 444; }
    location ^~ /api/ops/ { return 404; }
    location ~ ^/(api-docs|docs[.]html|swagger-ui|v3/api-docs)(/|$) { return 404; }
    location ^~ /api/ {
        proxy_pass http://127.0.0.1:PORT_VALUE;
        proxy_http_version 1.1;
        proxy_set_header Host HOST_VALUE;
        proxy_set_header X-Forwarded-For $remote_addr;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-Proto https;
        proxy_set_header X-Forwarded-Host HOST_VALUE;
        proxy_set_header X-Forwarded-Port 443;
        proxy_set_header Forwarded "";
        proxy_set_header Connection "";
        proxy_connect_timeout 3s;
        proxy_read_timeout 20s;
        proxy_send_timeout 20s;
    }
    location = /index.html {
        SECURITY_HEADERS
        add_header Cache-Control "no-store" always;
        try_files $uri =404;
    }
    location /assets/ {
        SECURITY_HEADERS
        add_header Cache-Control "public, max-age=31536000, immutable";
        try_files $uri =404;
    }
    location /static/ {
        SECURITY_HEADERS
        add_header Cache-Control "public, max-age=3600";
        try_files $uri =404;
    }
    location / {
        SECURITY_HEADERS
        add_header Cache-Control "no-store" always;
        try_files $uri $uri/ /index.html;
    }
}
'''.replace('HOST_VALUE',host).replace('PORT_VALUE',str(backend_port)).replace('SECURITY_HEADERS', 'add_header X-Content-Type-Options nosniff always;\n        add_header X-Frame-Options DENY always;\n        add_header Referrer-Policy strict-origin-when-cross-origin always;\n        add_header Strict-Transport-Security \"max-age=15552000\" always;')
    service='''[Unit]
Description=Campus Market backend
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=campus-market
Group=campus-market
WorkingDirectory=/srv/campus-market
EnvironmentFile=/etc/campus-market/runtime.env
ExecStart=/usr/bin/java -jar /srv/campus-market/current/server/app.jar --spring.profiles.active=production
Restart=on-failure
RestartSec=5
TimeoutStopSec=30
KillSignal=SIGTERM
UMask=0077
NoNewPrivileges=true
PrivateTmp=true
ProtectHome=true
ProtectSystem=strict
ReadWritePaths=/srv/campus-market/uploads /var/log/campus-market
RestrictAddressFamilies=AF_UNIX AF_INET AF_INET6

[Install]
WantedBy=multi-user.target
'''
    env='''# Example only. Fill locally; do not commit or paste secrets into chat.
PUBLIC_ORIGIN=ORIGIN_VALUE
SERVER_PORT=PORT_VALUE
DB_URL=jdbc:mysql://127.0.0.1:3306/campus_production
DB_USERNAME=campus_production_runtime
DB_PASSWORD=
UPLOAD_DIR=/srv/campus-market/uploads
APP_LOG_FILE=/var/log/campus-market/app.log
DB_MIGRATION_ENABLED=false
LOGIN_REDIS_HOST=127.0.0.1
LOGIN_REDIS_PORT=6380
LOGIN_REDIS_PASSWORD=
REDIS_HOST=127.0.0.1
REDIS_PORT=6379
REDIS_PASSWORD=
ORDER_MQ_ENABLED=false
NOTIFICATION_MQ_ENABLED=false
RABBITMQ_HOST=127.0.0.1
RABBITMQ_PORT=5672
RABBITMQ_USERNAME=campus_mq
RABBITMQ_PASSWORD=
RABBITMQ_VHOST=/campus-market
'''.replace('ORIGIN_VALUE',origin).replace('PORT_VALUE',str(backend_port))
    return {'nginx.site.conf':nginx,'campus-market.service':service,'runtime.env.example':env}

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--origin',required=True);p.add_argument('--backend-port',type=int,default=8080);p.add_argument('--output',type=Path,required=True);a=p.parse_args()
    files=render(a.origin,a.backend_port)
    if a.output.is_symlink():raise ValueError('Output must not be a symlink')
    a.output.mkdir(mode=0o700,parents=False,exist_ok=False)
    for name,value in files.items():
        target=a.output/name
        with target.open('x') as f:f.write(value)
        target.chmod(0o600)
    print('Generated '+str(len(files))+' review files in '+str(a.output)+'; no service was installed or started')
if __name__=='__main__':
    try:main()
    except (ValueError,OSError) as e:raise SystemExit(str(e))
