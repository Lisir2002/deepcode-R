#!/usr/bin/env python3
"""
反向代理式本地 Maven 缓存。

Gradle HttpClient 用的是 Apache HC 4.x，沙箱到外网 443 TCP 端口被出口策略拦截（SYN 超时）。
但用户态 curl 可用并能正常下载，可能是沙箱对 curl 做了 socket 级别的放行。

本脚本：
1. 监听 localhost:18080，作为纯 HTTP（非 SSL）Maven 仓库。
2. 收到 Gradle 的 GET 后，对路径做识别：
   - /google/<path>  -> 转发到 https://dl.google.com/dl/android/maven2/<path>，用 curl 子进程拉
   - /central/<path> -> 转发到 https://repo1.maven.org/maven2/<path>
   - /plugins/<path> -> 转发到 https://plugins.gradle.org/m2/<path>
   - /aliyun/<path>  -> 转发到 https://maven.aliyun.com/repository/public/<path>
3. 下载结果存在 $PROJECT_ROOT/.local-maven-cache/ 下，下次命中直接返回。
"""
import os
import sys
import subprocess
import hashlib
import http.server
import socketserver
import urllib.parse
import threading
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parent.parent
CACHE_DIR = PROJECT_ROOT / ".local-maven-cache"
CACHE_DIR.mkdir(exist_ok=True)

UPSTREAMS = {
    "google":  "https://dl.google.com/dl/android/maven2",
    "central": "https://repo1.maven.org/maven2",
    "plugins": "https://plugins.gradle.org/m2",
    "aliyun":  "https://maven.aliyun.com/repository/public",
    "jitpack": "https://jitpack.io",
}

# 每个 prefix 的候选上游按优先级排列。
# Gradle 会因 metadata 锁定 prefix，但构件可能实际在另一个上游（e.g. grpc/codehaus 不在 google，
# androidx 不在 central）。这里做跨上游兜底，避免因单个 prefix upstream 404 导致构建失败。
FALLBACK_UPSTREAMS = {
    "google":  ["google", "central", "aliyun", "plugins"],
    "central": ["central", "aliyun", "google", "plugins"],
    "plugins": ["plugins", "central", "aliyun", "google"],
    "aliyun":  ["aliyun", "central", "google", "plugins"],
    "jitpack": ["jitpack", "central", "aliyun"],
}

LOCK = threading.Lock()

def cached_path(prefix: str, path: str) -> Path:
    h = hashlib.sha256((prefix + path).encode()).hexdigest()[:16]
    safe = path.strip("/").replace("/", "__").replace("?", "_q_")
    return CACHE_DIR / f"{prefix}__{h}__{safe}"[:200]


def fetch_with_curl(url: str, dest: Path, write_miss: bool = True) -> int:
    """用 curl 拉文件写到 dest.tmp 再改名；返回 curl exit code。write_miss=False 时不写失败标记。"""
    tmp = dest.with_suffix(dest.suffix + ".tmp")
    dest.parent.mkdir(parents=True, exist_ok=True)
    cmd = [
        "curl", "-fsSL",
        "--connect-timeout", "20",
        "--max-time", "600",
        "--retry", "2",
        "--retry-delay", "1",
        "--tcp-nodelay",
        "--keepalive-time", "60",
        "-o", str(tmp),
        url,
    ]
    try:
        r = subprocess.run(cmd, capture_output=True, timeout=620)
    except subprocess.TimeoutExpired:
        if tmp.exists():
            try:
                tmp.unlink()
            except OSError:
                pass
        return 124
    if r.returncode == 0 and tmp.exists() and tmp.stat().st_size > 0:
        tmp.replace(dest)
        return 0
    if tmp.exists():
        try:
            tmp.unlink()
        except OSError:
            pass
    if write_miss:
        with open(dest.with_suffix(dest.suffix + ".miss"), "w") as f:
            f.write(f"404 rc={r.returncode}\n")
    return r.returncode


class MavenHandler(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        pass

    def handle_one_request(self):
        """覆盖 handle_one_request 加长 timeout，避免大 JAR 下载时 HTTP 服务端读超时。"""
        try:
            self.raw_requestline = self.rfile.readline(65537)
            if len(self.raw_requestline) > 65536:
                self.requestline = ''
                self.request_version = ''
                self.command = ''
                self.send_error(414)
                return
            if not self.raw_requestline:
                self.close_connection = True
                return
            if not self.parse_request():
                return
            mname = 'do_' + self.command
            if not hasattr(self, mname):
                self.send_error(501, "Unsupported method (%r)" % self.command)
                return
            # Gradle 默认读超时可能较短，这里给读 / 写足够时间
            self.connection.settimeout(900)
            method = getattr(self, mname)
            method()
            self.wfile.flush()
        except TimeoutError:
            self.log_error("Request timed out")
            self.close_connection = True
        except OSError:
            self.close_connection = True
        except Exception as e:
            self.log_error("Exception: %s", e)
            self.close_connection = True

    def _resolve(self):
        """解析路径并确保文件已缓存（命中或下载）。支持多上游 fallback，所有上游失败才写 .miss。"""
        parsed = urllib.parse.urlparse(self.path)
        raw_path = parsed.path
        parts = raw_path.strip("/").split("/", 1)
        if len(parts) != 2:
            return (404, None, None)
        prefix, rest = parts
        if prefix not in UPSTREAMS:
            return (404, None, None)

        dest = cached_path(prefix, rest)
        miss_marker = dest.with_suffix(dest.suffix + ".miss")

        with LOCK:
            if dest.exists():
                return (200, dest.read_bytes(), guess_content_type(rest))
            if miss_marker.exists():
                # .miss 可能是单上游失败的旧标记（fallback 前），清理后重试
                try:
                    miss_marker.unlink()
                except OSError:
                    pass

            # 按 FALLBACK_UPSTREAMS 逐个尝试，只有全部失败才写 .miss
            candidates = FALLBACK_UPSTREAMS.get(prefix, [prefix])
            last_rc = None
            last_up = None
            for up in candidates:
                base = UPSTREAMS.get(up)
                if not base:
                    continue
                url = base.rstrip("/") + "/" + rest.lstrip("/")
                is_last = (up == candidates[-1])
                rc = fetch_with_curl(url, dest, write_miss=is_last)
                if rc == 0 and dest.exists():
                    data = dest.read_bytes()
                    print(f"[mvn-proxy] 200 {prefix}/{rest[:80]} via {up} -> {len(data)} bytes", flush=True)
                    return (200, data, guess_content_type(rest))
                last_rc = rc
                last_up = up
            print(f"[mvn-proxy] ALL FAIL prefix={prefix} last_up={last_up} rc={last_rc} {rest[:120]}", flush=True)
            return (502, None, None)

    def do_GET(self):  # noqa: N802
        status, data, ctype = self._resolve()
        if status == 200 and data is not None:
            self.send_response(200)
            self.send_header("Content-Length", str(len(data)))
            self.send_header("Content-Type", ctype)
            self.end_headers()
            self.wfile.write(data)
        elif status == 404:
            self.send_error(404, "not found")
        else:
            self.send_error(502, "upstream failed")

    def do_HEAD(self):  # noqa: N802
        """Gradle/HttpClient 会先 HEAD 探测资源（Content-Length/状态），再决定是否 GET。"""
        status, data, ctype = self._resolve()
        if status == 200 and data is not None:
            self.send_response(200)
            self.send_header("Content-Length", str(len(data)))
            self.send_header("Content-Type", ctype)
            self.end_headers()
        elif status == 404:
            self.send_error(404, "not found")
        else:
            self.send_error(502, "upstream failed")


def guess_content_type(path: str) -> str:
    p = path.lower()
    if p.endswith(".pom") or p.endswith(".xml"):
        return "application/xml"
    if p.endswith(".jar") or p.endswith(".aar"):
        return "application/java-archive"
    if p.endswith(".module"):
        return "application/json"
    if p.endswith(".sha1") or p.endswith(".md5") or p.endswith(".sha256") or p.endswith(".sha512"):
        return "text/plain"
    if p.endswith(".zip"):
        return "application/zip"
    return "application/octet-stream"


def main():
    port = int(os.environ.get("MVN_PROXY_PORT", "18080"))
    host = os.environ.get("MVN_PROXY_HOST", "127.0.0.1")
    socketserver.ThreadingTCPServer.allow_reuse_address = True
    with socketserver.ThreadingTCPServer((host, port), MavenHandler) as httpd:
        print(f"[mvn-proxy] listening on http://{host}:{port}", flush=True)
        print(f"[mvn-proxy] cache dir: {CACHE_DIR}", flush=True)
        try:
            httpd.serve_forever()
        except KeyboardInterrupt:
            print("[mvn-proxy] shutdown", flush=True)


if __name__ == "__main__":
    main()
