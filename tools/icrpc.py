#!/usr/bin/env python3
"""仪表 30510 管理通道 RPC 客户端（协议见 carpad/.../IcRpc.java）。

用途：不经 shell 登录，直接在仪表 i.MX6 上执行只读诊断命令。
帧格式（大端）：
  uint16 service=257, uint16 method=529, uint32 len=body+8,
  uint16 magic=32369, uint16 ver=1, byte[4]={1,0,0,0}, body(protobuf)
body: field1 = varint(0x7E710001) 的嵌套消息；field2="shell"；field2=命令文本。

用法：
  python3 tools/icrpc.py '<shell 命令>'
  IC_HOST=... IC_PORT=... 可覆盖目标，默认 127.0.0.1:9000，即 adb forward 出来的
  车机中继端口（中继怎么搭见 README「九、仪表端诊断脚本」）。
"""
import socket
import struct
import sys


def varint(v: int) -> bytes:
    out = b""
    while v > 127:
        out += bytes([(v & 127) | 128])
        v >>= 7
    return out + bytes([v])


def field(num: int, data: bytes) -> bytes:
    return varint(num * 8 + 2) + varint(len(data)) + data


def read_varint(b: bytes, pos: int):
    v = 0
    shift = 0
    while pos < len(b):
        x = b[pos]
        pos += 1
        v |= (x & 127) << shift
        if not x & 128:
            return v, pos
        shift += 7
    return v, pos


def skip(b: bytes, pos: int, wt: int):
    if wt == 0:
        _, pos = read_varint(b, pos)
        return b"", pos
    if wt == 2:
        ln, pos = read_varint(b, pos)
        return b[pos:pos + ln], pos + ln
    if wt == 1:
        return b"", pos + 8
    if wt == 5:
        return b"", pos + 4
    raise ValueError("bad wiretype %d" % wt)


def decode(b: bytes) -> str:
    out = []
    pos = 0
    while pos < len(b):
        tag, pos = read_varint(b, pos)
        num, wt = tag >> 3, tag & 7
        val, pos = skip(b, pos, wt)
        if num == 2 and wt == 2:
            p2 = 0
            while p2 < len(val):
                t2, p2 = read_varint(val, p2)
                wt2 = t2 & 7
                s, p2 = skip(val, p2, wt2)
                if wt2 == 2:
                    out.append(s.decode("utf-8", "replace"))
    return "".join(out)


def exec_remote(cmd: str, host: str = "127.0.0.1", port: int = 9000, timeout: float = 20.0) -> str:
    inner = bytes([8]) + varint(0x7E710001)
    body = field(1, inner) + field(2, b"shell") + field(2, cmd.encode())
    head = struct.pack(">HHIHH", 257, 529, len(body) + 8, 32369, 1) + bytes([1, 0, 0, 0])
    s = socket.create_connection((host, port), timeout=4)
    try:
        s.settimeout(timeout)
        s.sendall(head + body)
        buf = b""
        while len(buf) < 12:
            chunk = s.recv(12 - len(buf))
            if not chunk:
                raise IOError("连接被关闭")
            buf += chunk
        s1, s2, ln, s3, s4 = struct.unpack(">HHIHH", buf[:12])
        tail = s.recv(4)
        while len(tail) < 4:
            c = s.recv(4 - len(tail))
            if not c:
                break
            tail += c
        if (s1, s2, s3, s4) != (257, 529, 32369, 1) or tail[:4] != bytes([1, 0, 128, 0]):
            raise IOError("响应帧头异常 %s %s" % ((s1, s2, s3, s4), tail))
        need = ln - 8
        payload = b""
        while len(payload) < need:
            chunk = s.recv(need - len(payload))
            if not chunk:
                break
            payload += chunk
        return decode(payload)
    finally:
        s.close()


if __name__ == "__main__":
    import os
    args = sys.argv[1:]
    if not args:
        print("用法: icrpc.py '<shell 命令>'  (环境变量 IC_HOST / IC_PORT 可覆盖目标，"
              "默认 127.0.0.1:9000)", file=sys.stderr)
        sys.exit(2)
    try:
        sys.stdout.write(exec_remote(args[0],
                                     host=os.environ.get("IC_HOST", "127.0.0.1"),
                                     port=int(os.environ.get("IC_PORT", "9000"))))
    except Exception as e:
        print("RPC 失败: %s" % e, file=sys.stderr)
        sys.exit(1)
