"""BlenderAR 局域网服务端入口。

    python3 server/main.py [--port 8000] [--assets-dir ../assets]

启动后打印:局域网 IPv4、控制台地址、WS 地址、配对码。
Android 端输入 IP + 端口(默认 8000)+ 配对码即可连接。
"""
from __future__ import annotations

import argparse
import socket
import sys
from pathlib import Path

SERVER_DIR = Path(__file__).resolve().parent
REPO_ROOT = SERVER_DIR.parent
for p in (str(SERVER_DIR), str(REPO_ROOT)):
    if p not in sys.path:
        sys.path.insert(0, p)

from app import REPO_ROOT, create_app, generate_pairing_code  # noqa: E402
from websocket.protocol import SERVER_VERSION  # noqa: E402


def lan_ipv4s() -> list:
    """枚举本机局域网 IPv4(不发出真实网络流量)。"""
    ips: set = set()
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.settimeout(0.5)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        if not ip.startswith("127."):
            ips.add(ip)
    except OSError:
        pass
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ip = info[4][0]
            if not ip.startswith(("127.", "169.254.")):
                ips.add(ip)
    except OSError:
        pass
    return sorted(ips)


def main() -> int:
    parser = argparse.ArgumentParser(description="BlenderAR 局域网服务端")
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=8000)
    parser.add_argument("--assets-dir", default=str(REPO_ROOT / "assets"))
    parser.add_argument("--pairing-code", default=None, help="固定配对码(缺省每次启动随机生成)")
    args = parser.parse_args()

    code = args.pairing_code or generate_pairing_code()
    app = create_app(args.assets_dir, pairing_code=code)
    app.state.ctx.host = args.host
    app.state.ctx.port = args.port

    ips = lan_ipv4s()
    print("=" * 64)
    print(f"  BlenderAR 局域网服务端 v{SERVER_VERSION}")
    print("=" * 64)
    print(f"  控制台:   http://localhost:{args.port}/")
    for ip in ips:
        print(f"  控制台:   http://{ip}:{args.port}/   (手机浏览器也可打开)")
    print(f"  WebSocket: ws://<以上任一IP>:{args.port}/ws")
    print(f"  配对码:    {code}   ← Android 端输入此码")
    print(f"  资源目录:  {Path(args.assets_dir).resolve()}")
    print("=" * 64)

    import uvicorn
    uvicorn.run(app, host=args.host, port=args.port, log_level="info")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
