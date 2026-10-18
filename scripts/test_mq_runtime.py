"""Private RabbitMQ process used only by the isolated backend test runner."""
from pathlib import Path
import os,secrets,shutil,socket,subprocess,time,signal

class TestRabbit:
    def __init__(self, directory):
        self.root=Path(directory);self.root.mkdir(mode=0o700)
        self.node='campus_verify_'+secrets.token_hex(6)+'@localhost'
        self.user='campus_verify';self.password=secrets.token_urlsafe(32)
        self.vhost='/campus-lesson74-verify';self.process=None
        self.sbin=Path(os.environ['RABBITMQ_SBIN']) if os.environ.get('RABBITMQ_SBIN') else None
        self.server=str(self.sbin/'rabbitmq-server') if self.sbin else shutil.which('rabbitmq-server')
        self.ctl=str(self.sbin/'rabbitmqctl') if self.sbin else shutil.which('rabbitmqctl')
        if not self.server or not self.ctl:raise RuntimeError('rabbitmq-server and rabbitmqctl must be on PATH, or set RABBITMQ_SBIN')
        sockets=[]
        try:
            for _ in range(2):
                probe=socket.socket();probe.bind(('127.0.0.1',0));sockets.append(probe)
            self.port,self.dist=[s.getsockname()[1] for s in sockets]
        finally:
            for probe in sockets:probe.close()
        config=self.root/'rabbitmq.conf'
        config.write_text(f'''listeners.tcp.1 = 127.0.0.1:{self.port}
distribution.listener.port_range.min = {self.dist}
distribution.listener.port_range.max = {self.dist}
vm_memory_high_watermark.absolute = 256MiB
disk_free_limit.absolute = 50MB
default_user = {self.user}
default_pass = {self.password}
default_vhost = {self.vhost}
default_permissions.configure = ^campus[.]order[.].*
default_permissions.write = ^campus[.]order[.].*
default_permissions.read = ^campus[.]order[.].*
''');config.chmod(0o600)
        (self.root/'enabled_plugins').write_text('[].\n')
        (self.root/'env.conf').touch()
        self.env={key:value for key,value in os.environ.items() if not key.startswith(('RABBITMQ_','ERL_','ERL_AFLAGS'))}
        self.env.update(RABBITMQ_CONF_ENV_FILE=str(self.root/'env.conf'),RABBITMQ_CONFIG_FILE=str(self.root/'rabbitmq'),RABBITMQ_MNESIA_BASE=str(self.root/'mnesia'),RABBITMQ_LOG_BASE=str(self.root/'logs'),RABBITMQ_PID_FILE=str(self.root/'rabbitmq.pid'),RABBITMQ_ENABLED_PLUGINS_FILE=str(self.root/'enabled_plugins'),RABBITMQ_NODENAME=self.node,RABBITMQ_ERLANG_COOKIE=secrets.token_hex(32),ERL_EPMD_ADDRESS='127.0.0.1',RABBITMQ_DIST_PORT=str(self.dist),RABBITMQ_SERVER_ADDITIONAL_ERL_ARGS='-kernel inet_dist_use_interface {127,0,0,1} +S 2:2 +A 4',RABBITMQ_CTL_ERL_ARGS='+S 1:1 +A 2')

    def start(self):
        with (self.root/'startup.log').open('ab') as log:
            self.process=subprocess.Popen([self.server],env=self.env,stdout=log,stderr=subprocess.STDOUT,start_new_session=True)
        deadline=time.monotonic()+45
        while time.monotonic()<deadline:
            if self.process.poll() is not None:raise RuntimeError('Private test RabbitMQ exited during startup')
            try:
                with socket.create_connection(('127.0.0.1',self.port),timeout=.2):break
            except OSError:time.sleep(.2)
        else:raise RuntimeError('Private test RabbitMQ startup timeout')
        result=subprocess.run([self.ctl,'-n',self.node,'-q','eval','os:getpid().'],env=self.env,capture_output=True,text=True,timeout=20)
        pid=self.root/'rabbitmq.pid'
        if result.returncode or not pid.exists() or result.stdout.strip().strip('"')!=pid.read_text().strip():raise RuntimeError('Private test RabbitMQ ownership check failed')
        return {'LESSON74_MQ_USER':self.user,'LESSON74_MQ_PASSWORD':self.password,'LESSON74_MQ_PORT':str(self.port),'LESSON74_MQ_VHOST':self.vhost}

    def close(self):
        if self.process is None or self.process.poll() is not None:return
        try:
            subprocess.run([self.ctl,'-n',self.node,'-q','stop'],env=self.env,capture_output=True,timeout=20)
            self.process.wait(timeout=10)
        except (subprocess.TimeoutExpired,OSError):
            # This group was created by this runner; never signal a system node.
            if self.process.poll() is None:
                os.killpg(self.process.pid,signal.SIGTERM)
                try:self.process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    os.killpg(self.process.pid,signal.SIGKILL);self.process.wait()
