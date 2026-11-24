#!/usr/bin/env python3
"""Read newline JSON from the explicitly forwarded loopback carprobe agent.

Uses a direct socket (never HTTP proxies). Run deploy.sh forward --serial SERIAL
separately before connecting to a device agent. No adb/device actions here.
"""
import argparse
import json
import math
import socket
import sys
import time

HOST = "127.0.0.1"
# 26 properties * 8192 text characters * worst-case 6-byte JSON escaping,
# plus keys/metadata and bounded numeric arrays, fits below this frame cap.
MAX_LINE_BYTES = 2 * 1024 * 1024


class ClientError(Exception):
    pass


class JsonLines:
    def __init__(self, sock, timeout, max_bytes=MAX_LINE_BYTES):
        self.sock = sock
        self.timeout = timeout
        self.max_bytes = max_bytes
        self.buffer = bytearray()

    def read(self):
        deadline = time.monotonic() + self.timeout
        while True:
            newline = self.buffer.find(b"\n")
            if newline >= 0:
                if newline > self.max_bytes:
                    raise ClientError("JSON line exceeds byte limit")
                line = bytes(self.buffer[:newline])
                del self.buffer[:newline + 1]
                try:
                    result = json.loads(line.decode("utf-8"), parse_constant=self._invalid_constant)
                except (UnicodeError, ValueError, RecursionError) as exc:
                    raise ClientError("invalid JSON line: " + str(exc)) from exc
                if not isinstance(result, dict):
                    raise ClientError("expected a JSON object")
                return result
            if len(self.buffer) > self.max_bytes:
                raise ClientError("JSON line exceeds byte limit")
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise ClientError("JSON line read timed out")
            self.sock.settimeout(remaining)
            data = self.sock.recv(min(4096, self.max_bytes + 1 - len(self.buffer)))
            if not data:
                raise ClientError("connection closed before a complete JSON line")
            self.buffer.extend(data)

    @staticmethod
    def _invalid_constant(value):
        raise ValueError("nonstandard JSON constant: " + value)


def port_number(value):
    try:
        number = int(value)
    except ValueError as exc:
        raise argparse.ArgumentTypeError("port must be an integer") from exc
    if not 1 <= number <= 65535:
        raise argparse.ArgumentTypeError("port must be 1..65535")
    return number


def timeout_value(value):
    try:
        number = float(value)
    except ValueError as exc:
        raise argparse.ArgumentTypeError("timeout must be a number") from exc
    if not math.isfinite(number) or not 0 < number <= 300:
        raise argparse.ArgumentTypeError("timeout must be finite, >0 and <=300 seconds")
    return number


def parser():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("command", choices=("ping", "list", "snapshot", "get", "stream"))
    p.add_argument("name", nargs="?", help="field name (required only for get)")
    p.add_argument("--port", type=port_number, default=17666)
    p.add_argument("--timeout", type=timeout_value, default=5, help="connection and each complete JSON-line timeout, seconds")
    p.add_argument("--count", type=int, help="stop stream after this many JSON lines (positive; default streams until interrupted)")
    return p


def main(argv=None):
    p = parser()
    args = p.parse_args(argv)
    if args.command == "get":
        if not args.name or any(c.isspace() or ord(c) < 32 for c in args.name) or len(args.name.encode("utf-8")) > 256:
            p.error("get requires one nonempty field name without whitespace/control characters (at most 256 bytes)")
    elif args.name is not None:
        p.error("only get accepts a field name")
    if args.count is not None and (args.command != "stream" or args.count < 1):
        p.error("--count must be positive and is only valid for stream")
    command = args.command + (" " + args.name if args.command == "get" else "")
    try:
        with socket.create_connection((HOST, args.port), timeout=args.timeout) as sock:
            sock.settimeout(args.timeout)
            sock.sendall((command + "\n").encode("utf-8"))
            reader = JsonLines(sock, args.timeout)
            count = 0
            while True:
                reply = reader.read()
                print(json.dumps(reply, ensure_ascii=False, allow_nan=False, separators=(",", ":")), flush=True)
                if "error" in reply or reply.get("ok") is False:
                    raise ClientError("agent returned an error (see JSON response)")
                count += 1
                if args.command != "stream" or (args.count is not None and count >= args.count):
                    return 0
    except KeyboardInterrupt:
        return 130
    except (ClientError, OSError, ValueError) as exc:
        print("carprobe client: " + str(exc), file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
