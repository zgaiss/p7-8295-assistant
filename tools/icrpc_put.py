#!/usr/bin/env python3
"""经仪表 30510 管理通道上传文件（base64 分块，只依赖 icrpc.py）。

用法：
  python3 tools/icrpc_put.py <本地文件> <远端路径> [chunk]

  目标默认 127.0.0.1:9000（adb forward 出来的车机中继端口），
  用 IC_HOST / IC_PORT 覆盖，中继怎么搭见 README「九、仪表端诊断脚本」。

为什么不用 FTP：Mac 直连不到仪表，只能经车机中继；而 30510 只提供
shell，没有文件传输 opcode，所以用 base64 拼文件。
"""
import base64
import hashlib
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from icrpc import exec_remote  # noqa: E402

HOST = os.environ.get("IC_HOST", "127.0.0.1")
PORT = int(os.environ.get("IC_PORT", "9000"))


def run(cmd, timeout=30.0):
    return exec_remote(cmd, host=HOST, port=PORT, timeout=timeout)


def main():
    if len(sys.argv) < 3:
        print("用法: icrpc_put.py <本地文件> <远端路径> [chunk]", file=sys.stderr)
        return 2
    src, dst = sys.argv[1], sys.argv[2]
    chunk = int(sys.argv[3]) if len(sys.argv) > 3 else 60000

    with open(src, "rb") as f:
        raw = f.read()
    md5 = hashlib.md5(raw).hexdigest()
    b64 = base64.b64encode(raw).decode()
    tmp = dst + ".b64"

    print("源文件 %d 字节  md5=%s  base64=%d 分块=%d (%d 块)" % (
        len(raw), md5, len(b64), chunk, (len(b64) + chunk - 1) // chunk))

    run("rm -f %s %s" % (tmp, dst + ".part"))
    t0 = time.time()
    n = (len(b64) + chunk - 1) // chunk
    for i in range(n):
        seg = b64[i * chunk:(i + 1) * chunk]
        run("printf '%%s' '%s' >> %s" % (seg, tmp), timeout=40.0)
        if i % 10 == 0 or i == n - 1:
            print("  %d/%d  %.1fs" % (i + 1, n, time.time() - t0), flush=True)

    out = run("ls -l %s; base64 -d < %s > %s.part && chmod 755 %s.part && "
              "md5sum %s.part && rm -f %s" % (tmp, tmp, dst, dst, dst, tmp), timeout=120.0)
    print(out.strip())
    got = ""
    for line in out.splitlines():
        if line.startswith(md5):
            got = md5
    if got != md5:
        print("校验失败：期望 %s" % md5, file=sys.stderr)
        return 1
    # 校验通过才落位：先写 .part 再 mv，热升级时守护进程不会读到半个二进制
    print(run("mv -f %s.part %s && ls -l %s && md5sum %s" % (dst, dst, dst, dst)).strip())
    print("上传完成 %.1fs" % (time.time() - t0))
    return 0


if __name__ == "__main__":
    sys.exit(main())
