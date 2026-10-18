#!/usr/bin/env python3
"""仅管理本项目拥有的本机Redis；不修改Homebrew安装、不清空其他Redis实例。"""
from pathlib import Path
import argparse, hashlib, os, shutil, socket, subprocess, sys, time

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("action", choices=["start", "status", "stop", "inspect"])
parser.add_argument("--role", choices=["cache", "login"], default="cache")
args = parser.parse_args()
login_role = args.role == "login"
runtime = root / "data/redis" / ("login" if login_role else "")
runtime.mkdir(parents=True, exist_ok=True)
port = int(os.environ.get("LOGIN_REDIS_PORT" if login_role else "REDIS_PORT", "6380" if login_role else "6379"))
if not 1024 <= port <= 65535: raise SystemExit("REDIS_PORT必须在1024～65535之间")
server = runtime / "redis-server"
cli = runtime / "redis-cli"
pidfile = runtime / "redis.pid"

# 部分Homebrew安装保留了构建机占位符。只修复项目自己的副本，保留系统原文件。
for name in ["redis-server", "redis-cli"]:
    source = shutil.which(name)
    if not source: raise SystemExit("未找到Redis，请先安装Redis后重试")
    source = Path(source).resolve()
    destination = runtime / name
    stamp = runtime / (name + ".sha256")
    digest = hashlib.sha256(source.read_bytes()).hexdigest()
    if destination.exists() and stamp.exists() and stamp.read_text() == digest: continue
    shutil.copy2(source, destination)
    if sys.platform == "darwin":
        deps = subprocess.check_output(["/usr/bin/otool", "-L", str(destination)], text=True)
        for line in deps.splitlines()[1:]:
            old = line.strip().split(" (", 1)[0]
            if "@@HOMEBREW_PREFIX@@" in old:
                prefix = Path(*source.parts[:source.parts.index("Cellar")]) if "Cellar" in source.parts else Path("/opt/homebrew")
                new = old.replace("@@HOMEBREW_PREFIX@@", str(prefix))
                if not Path(new).is_file(): raise SystemExit("Redis依赖库不存在：" + new)
                subprocess.run(["/usr/bin/install_name_tool", "-change", old, new, str(destination)], check=True)
        subprocess.run(["/usr/bin/codesign", "--force", "--sign", "-", str(destination)], check=True, capture_output=True)
    stamp.write_text(digest)

def command(*parts):
    return subprocess.run([str(cli), "-h", "127.0.0.1", "-p", str(port), *parts], text=True, capture_output=True, timeout=3)
def owns_server():
    if not pidfile.exists(): return False
    result = command("--raw", "INFO", "server")
    return result.returncode == 0 and ("process_id:" + pidfile.read_text().strip()) in result.stdout.splitlines()

if args.action == "start":
    if command("PING").returncode == 0:
        if owns_server(): print("本项目Redis已启动：127.0.0.1:" + str(port)); sys.exit(0)
        raise SystemExit("端口已有其他Redis实例，未改动。可设置REDIS_PORT后启动独立实例，并同步后端环境变量。")
    with socket.socket() as probe:
        probe.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try: probe.bind(("127.0.0.1", port))
        except OSError: raise SystemExit("端口被其他程序占用，未改动。")
    subprocess.run([str(server), "--bind", "127.0.0.1", "--port", str(port),
        "--protected-mode", "yes", "--daemonize", "yes", "--pidfile", str(pidfile),
        "--logfile", str(runtime / "redis.log"), "--dir", str(runtime),
        "--save", "", "--appendonly", "no", "--maxmemory", "32mb" if login_role else "128mb",
        "--maxmemory-policy", "noeviction" if login_role else "allkeys-lru"], check=True)
    for _ in range(20):
        if command("PING").stdout.strip() == "PONG": break
        time.sleep(.1)
    else: raise SystemExit("Redis启动失败，请查看data/redis/redis.log")
    print("登录保护Redis已启动，127.0.0.1:" + str(port) + "，32MB/noeviction。" if login_role else "商品缓存Redis已启动，127.0.0.1:" + str(port) + "，128MB/allkeys-lru。")
elif args.action == "stop":
    if not owns_server(): raise SystemExit("未确认是本项目启动的Redis，未执行停止。")
    result = command("SHUTDOWN", "NOSAVE")
    if result.returncode: raise SystemExit(result.stderr)
    print("登录保护Redis已停止，新登录暂不可用，已有会话继续有效。" if login_role else "商品缓存Redis已停止，商品接口会自动回退数据库。")
elif args.action == "status":
    result = command("PING"); print(result.stdout.strip() or result.stderr.strip()); sys.exit(result.returncode)
else:
    prefix = os.environ.get("LOGIN_PROTECTION_PREFIX" if login_role else "PRODUCT_CACHE_PREFIX", "campus:login:v1:" if login_role else "campus:product-detail:v1:")
    import re
    if not re.fullmatch(r"[a-zA-Z0-9:_-]{1,80}", prefix): raise SystemExit("缓存前缀无效")
    result = command("--scan", "--pattern", prefix + "*")
    if result.returncode: raise SystemExit(result.stderr)
    keys = result.stdout.splitlines()
    if not keys: print("当前没有限流记录，发起登录后可查看。" if login_role else "当前没有商品缓存，请先打开一个在售商品详情。")
    for key in keys:
        print(key + " | TTL=" + command("TTL", key).stdout.strip() + "秒")
        if not login_role: print(command("GET", key).stdout.strip())
        else:
            kind = command("TYPE", key).stdout.strip()
            print("占位/失败记录数=" + command("HLEN", key).stdout.strip() if kind == "hash" else "本来源尝试数=" + command("GET", key).stdout.strip())
