#!/usr/bin/env python3
"""Offline only. Builds into a temporary directory and tests real loopback sockets."""
import concurrent.futures
import json
import os
from pathlib import Path
import socket
import subprocess
import tempfile
import time
import unittest

ROOT = Path(__file__).resolve().parents[1]
PORT = None
JAVA = None
CLASSES = None


def connect():
    return socket.create_connection(('127.0.0.1', PORT), timeout=8)


def command(line):
    with connect() as s:
        s.sendall((line + '\nquit\n').encode('ascii'))
        with s.makefile('rb') as f:
            result = json.loads(f.readline())
            json.loads(f.readline())
            assert f.read() == b''
            return result


def http(request):
    with connect() as s:
        s.sendall(request)
        chunks = []
        while True:
            try:
                data = s.recv(65536)
            except ConnectionResetError:
                # Rejected over-limit requests can leave unread bytes at close.
                # Require a complete framed HTTP response below, never hide truncation.
                if chunks:
                    break
                raise
            if not data:
                break
            chunks.append(data)
    raw = b''.join(chunks)
    head, body = raw.split(b'\r\n\r\n', 1)
    headers = dict(line.split(b': ', 1) for line in head.split(b'\r\n')[1:])
    assert len(body) == int(headers[b'Content-Length'])
    assert body.endswith(b'\n') and body.count(b'\n') == 1
    return int(head.split(b' ')[1]), json.loads(body), headers


class ProtocolTests(unittest.TestCase):
    def test_commands_and_http(self):
        self.assertTrue(command('ping')['ok'])
        props = command('list')['props']
        self.assertEqual(len(props), 26)
        self.assertEqual(props['speed'], 0x21601225)
        self.assertNotIn('trip_a', props)
        self.assertEqual(command('get unknown')['error'], 'unknown_property')
        for cmd in ['raw 0x1234', 'log', 'applog', 'log since=1']:
            self.assertEqual(command(cmd)['error'], 'unsupported_in_poc')
        self.assertEqual(command('nope')['error'], 'unknown_command')
        for path in ['/ping', '/snapshot', '/list', '/speed']:
            status, obj, headers = http(('GET ' + path + ' HTTP/1.1\r\nHost: localhost\r\n\r\n').encode())
            self.assertEqual(status, 200)
            self.assertEqual(headers[b'Connection'], b'close')
            if path == '/speed':
                self.assertEqual(list(obj['props']), ['speed'])
        for path in ['/raw?ids=0x123', '/log?since=1', '/applog']:
            self.assertEqual(http(('GET ' + path + ' HTTP/1.0\r\n\r\n').encode())[0], 501)
        self.assertEqual(http(b'GET /missing HTTP/1.0\r\n\r\n')[0], 404)

    def test_multiple_commands_one_connection(self):
        with connect() as s:
            s.sendall(b'ping\r\nget speed\nsnapshot\nquit\n')
            f = s.makefile('rb')
            self.assertTrue(json.loads(f.readline())['ok'])
            self.assertEqual(list(json.loads(f.readline())['props']), ['speed'])
            self.assertEqual(len(json.loads(f.readline())['props']), 26)
            self.assertTrue(json.loads(f.readline())['bye'])
            self.assertEqual(f.readline(), b'')

    def test_cache_under_concurrent_readers(self):
        def reader(_):
            with connect() as s, s.makefile('rb') as f:
                values = []
                for _ in range(16):
                    s.sendall(b'get speed\n')
                    values.append(json.loads(f.readline()))
                s.sendall(b'quit\n'); json.loads(f.readline()); self.assertEqual(f.read(), b'')
                return values
        with concurrent.futures.ThreadPoolExecutor(max_workers=5) as pool:
            results = [r for batch in pool.map(reader, range(5)) for r in batch]
        for r in results:
            p = r['props']['speed']
            self.assertEqual(p['v'], r['seq'])
            self.assertEqual(p['ok'], r['seq'])
            self.assertEqual(p['err'], 0)
            self.assertEqual(r['conn'], 1)
        # Counts are per sampler, never per client request.
        self.assertLessEqual(max(r['seq'] for r in results) - min(r['seq'] for r in results), 2)

    def test_http_errors_and_limits(self):
        requests = [
            b'POST /snapshot HTTP/1.1\r\n\r\n',
            b'GET /snapshot HTTP/2\r\n\r\n',
            b'GET /snapshot HTTP/1.1\r\nBad Header: x\r\n\r\n',
            b'GET /snapshot HTTP/1.1\r\nHost: x\r\nHost: y\r\n\r\n',
            b'GET /snapshot HTTP/1.1\r\nContent-Length: 2\r\n\r\n{}',
            b'GET /snapshot HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n',
            b'GET /snapshot HTTP/1.1\r\n' + b'X: ' + b'x' * 1100 + b'\r\n\r\n',
            b'GET /snapshot HTTP/1.1\r\n' + b''.join(('X%d: x\r\n' % i).encode() for i in range(33)) + b'\r\n',
            b'GET /snapshot HTTP/1.1\r\n' + b''.join(('X%d: ' % i).encode() + b'x' * 900 + b'\r\n' for i in range(10)) + b'\r\n',
        ]
        for request in requests:
            self.assertEqual(http(request)[0], 400, request[:100])
        with connect() as s:
            s.sendall(b'x' * 1100 + b'\n')
            self.assertEqual(json.loads(s.makefile('rb').readline())['error'], 'line_too_long')
        with connect() as s:
            s.sendall(b'ping\x00\n')
            self.assertEqual(json.loads(s.makefile('rb').readline())['error'], 'invalid_line')
        with connect() as s:
            s.sendall(b'incomplete')
            s.shutdown(socket.SHUT_WR)
            self.assertEqual(json.loads(s.makefile('rb').readline())['error'], 'incomplete_line')
        self.assertTrue(command('ping')['ok'])

    def test_stream_and_admission_bound(self):
        sockets = []
        try:
            for _ in range(8):
                s = connect(); s.sendall(b'stream\n'); sockets.append(s)
                obj = json.loads(s.makefile('rb').readline())
                self.assertIn('seq', obj)
            with connect() as extra:
                self.assertEqual(extra.recv(100), b'')
        finally:
            for s in sockets:
                s.close()
        # Stream workers notice a closed receiver at latest at their next write.
        deadline = time.monotonic() + 4
        while True:
            try:
                self.assertTrue(command('ping')['ok']); break
            except (ConnectionError, json.JSONDecodeError):
                if time.monotonic() > deadline:
                    raise
                time.sleep(0.1)
        with connect() as s:
            s.sendall(b'stream\n'); f = s.makefile('rb')
            a = json.loads(f.readline()); b = json.loads(f.readline())
            self.assertGreaterEqual(b['seq'], a['seq'])
            for _ in range(3):
                if b['seq'] > a['seq']:
                    break
                b = json.loads(f.readline())
                self.assertGreaterEqual(b['seq'], a['seq'])
            self.assertGreater(b['seq'], a['seq'], 'healthy sampler must eventually advance beyond heartbeat')

    def test_hung_sampler_stream_heartbeats_and_disconnect_cleanup(self):
        server = subprocess.Popen([JAVA, '-cp', CLASSES, 'cp.HostServer', 'hung'],
                                  stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        sockets, files = [], []
        try:
            import select
            self.assertTrue(select.select([server.stdout], [], [], 10)[0])
            port = int(server.stdout.readline())
            # First successful cycle is followed by a deliberately uninterruptible source.
            time.sleep(1.2)
            for _ in range(8):
                s = socket.create_connection(('127.0.0.1', port), timeout=5)
                s.sendall(b'stream\n'); sockets.append(s)
                f = s.makefile('rb'); files.append(f)
                initial = json.loads(f.readline())
                self.assertEqual(initial['seq'], 1)
                self.assertTrue(initial['sampling'])
            later = json.loads(files[0].readline())
            self.assertEqual(later['seq'], initial['seq'])
            self.assertGreater(later['props']['speed']['read_age_ms'], initial['props']['speed']['read_age_ms'])
            self.assertFalse(later['seq'] > 1, 'heartbeats must never poll or publish a fake cycle')
            for f in files:
                f.close()
            files.clear()
            for s in sockets:
                s.close()
            sockets.clear()
            # A FIN can take two writes to produce EPIPE. Never wait for sampler progress.
            time.sleep(3.5)
            with socket.create_connection(('127.0.0.1', port), timeout=3) as probe:
                probe.sendall(b'ping\n')
                self.assertTrue(json.loads(probe.makefile('rb').readline())['ok'])
        finally:
            for f in files:
                f.close()
            for s in sockets:
                s.close()
            server.stdin.close()
            try:
                self.assertEqual(server.wait(timeout=5), 0)
            except subprocess.TimeoutExpired:
                server.kill(); server.wait(); raise
            server.stdout.close(); server.stderr.close()

    def test_slow_stream_writer_deadline(self):
        # Large but valid bounded payload + tiny receive window forces blocked writes.
        # All eight clients stop reading; the independent watchdog must free workers.
        server = subprocess.Popen([JAVA, '-cp', CLASSES, 'cp.HostServer', 'large'],
                                  stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        sockets = []
        try:
            import select
            self.assertTrue(select.select([server.stdout], [], [], 10)[0])
            port = int(server.stdout.readline())
            time.sleep(0.2)
            for _ in range(8):
                s = socket.socket()
                s.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 1024)
                s.settimeout(5)
                s.connect(('127.0.0.1', port)); s.sendall(b'stream\n'); sockets.append(s)
            time.sleep(4.5)
            with socket.create_connection(('127.0.0.1', port), timeout=3) as probe:
                probe.sendall(b'ping\n')
                self.assertTrue(json.loads(probe.makefile('rb').readline())['ok'])
        finally:
            for s in sockets:
                s.close()
            server.stdin.close()
            try:
                self.assertEqual(server.wait(timeout=5), 0)
            except subprocess.TimeoutExpired:
                server.kill(); server.wait(); raise
            server.stdout.close(); server.stderr.close()

    def test_request_deadline(self):
        # Drip-feed attempts cannot retain a worker beyond the whole-request deadline.
        with connect() as s:
            s.sendall(b'p')
            time.sleep(5.5)
            self.assertEqual(s.recv(100), b'')
        self.assertTrue(command('ping')['ok'])


def main():
    global PORT, JAVA, CLASSES
    java, javac = os.environ.get('JAVA', 'java'), os.environ.get('JAVAC', 'javac')
    JAVA = java
    with tempfile.TemporaryDirectory(prefix='carprobe-host-') as out:
        CLASSES = out
        sources = sorted(ROOT.glob('src/cp/*.java')) + sorted(ROOT.glob('tests/**/*.java'))
        subprocess.run([javac, '-encoding', 'UTF-8', '-source', '8', '-target', '8', '-d', out] + [str(p) for p in sources], check=True)
        subprocess.run([java, '-cp', out, 'cp.CoreTest'], check=True, timeout=15)
        subprocess.run([java, '-cp', out, 'cp.ReflectionTest'], check=True, timeout=15)
        server = subprocess.Popen([java, '-cp', out, 'cp.HostServer'], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        try:
            import select
            if not select.select([server.stdout], [], [], 10)[0]:
                raise RuntimeError('host server startup timeout')
            PORT = int(server.stdout.readline())
            deadline = time.monotonic() + 3
            while command('snapshot')['seq'] == 0:
                if time.monotonic() > deadline:
                    raise RuntimeError('sampler did not start')
                time.sleep(0.02)
            suite = unittest.defaultTestLoader.loadTestsFromTestCase(ProtocolTests)
            result = unittest.TextTestRunner(verbosity=2).run(suite)
        finally:
            server.stdin.close()
            try:
                server.wait(timeout=5)
            except subprocess.TimeoutExpired:
                server.kill(); server.wait(); raise
            stderr = server.stderr.read().decode()
            if stderr:
                print(stderr)
        if server.returncode != 0:
            raise RuntimeError('host server shutdown failed: %s' % server.returncode)
        if not result.wasSuccessful():
            raise SystemExit(1)
        print('Host loopback integration + bounded shutdown: PASS (not Android/vehicle validation)')


if __name__ == '__main__':
    main()
