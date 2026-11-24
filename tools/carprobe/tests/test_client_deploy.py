"""Offline-only CLI tests: local TCP fixtures and a sandboxed, executable fake adb.

The fake executes the generated POSIX shell against a temporary fake /proc and
fake Android binaries. It never invokes adb, app_process, or signals a real PID.
Run: python3 -m unittest discover -s tools/carprobe/tests -p test_client_deploy.py -v
"""
import contextlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shlex
import socket
import subprocess
import sys
import tempfile
import threading
import time
import unittest

ROOT = Path(__file__).resolve().parents[1]
DEPLOY = ROOT / "deploy.py"
CLIENT = ROOT / "carprobe.py"
SPEC = importlib.util.spec_from_file_location("carprobe_client_test", CLIENT)
client = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(client)


FAKE = r'''#!/usr/bin/env python3
import json, os, pathlib, re, shlex, shutil, subprocess, sys, time
root = pathlib.Path(os.environ['FAKE_ROOT'])
config = json.loads((root / 'config.json').read_text())
name = pathlib.Path(sys.argv[0]).name
args = sys.argv[1:]
base = root / 'base'
if name == 'id':
    print(config.get('uid', 2000)); sys.exit(0)
if name == 'stat':
    if args[1] == '%h': print(pathlib.Path(args[-1]).stat().st_nlink)
    else: print(config.get('owners', {}).get(args[-1], 2000))
    sys.exit(0)
if name == 'setsid':
    pid = str(config.get('pid', 4242))
    proc = root / 'proc' / pid
    proc.mkdir()
    (proc / 'cmdline').write_bytes(b'p7-carprobe-agent\0')
    pidfile = pathlib.Path(args[args.index('--pid') + 1])
    pidfile.write_text(pid + '\n')
    (root / 'launch.json').write_text(json.dumps({'argv':args, 'classpath':os.environ.get('CLASSPATH')}))
    sys.exit(0)
if name == 'fakekill':
    pid = args[-1]
    proc = root / 'proc' / pid
    assert proc.parent == root / 'proc'
    assert (proc / 'cmdline').read_bytes().split(b'\0')[0] == b'p7-carprobe-agent'
    (root / 'signals.jsonl').open('a').write(json.dumps(args) + '\n')
    if not config.get('keep_running'):
        (proc / 'cmdline').unlink(); proc.rmdir()
    sys.exit(0)
if name == 'toybox':
    if args[-1] == 'true': sys.exit(0)
    request = sys.stdin.read()
    assert request == 'ping\nquit\n', repr(request)
    if config.get('hung'):
        print('simulated binder readiness timeout', file=sys.stderr); sys.exit(1)
    print(json.dumps(config.get('reply', {'ok':True,'service':'carprobe','protocol':1})))
    sys.exit(0)
if name == 'sleep':
    sys.exit(0)  # Only remote stop polling, never hide a host-side timeout.
if name == 'app_process':
    raise AssertionError('app_process must be intercepted by fake setsid')
assert name == 'adb', name
with (root / 'calls.jsonl').open('a') as f: f.write(json.dumps(args) + '\n')
assert args[:2] == ['-s', os.environ.get('EXPECTED_SERIAL', 'FAKE_SERIAL')], args
args = args[2:]
if args[0] == 'forward':
    assert args[1] == '--no-rebind'
    if config.get('forward_exists'):
        print('cannot rebind existing socket', file=sys.stderr); sys.exit(1)
    sys.exit(0)
if args[0] == 'push':
    assert re.fullmatch(r'/data/local/tmp/p7-carprobe/upload-[0-9a-f]{32}\.jar', args[2])
    time.sleep(config.get('push_delay', 0))
    shutil.copyfile(args[1], base / pathlib.Path(args[2]).name)
    sys.exit(0)
assert args[:3] == ['shell','sh','-c'], args[:3]
script, = shlex.split(args[3])
# Translate every remote path, so /proc inspection and signals are purely fake.
script = script.replace('/data/local/tmp/p7-carprobe', str(base))
script = script.replace('/proc/', str(root / 'proc') + '/')
script = script.replace('/system/bin/app_process', str(root / 'bin/app_process'))
script = script.replace('/system/bin/toybox', str(root / 'bin/toybox'))
script = script.replace('/system/framework/android.car.jar', str(root / 'android.car.jar'))
script = 'kill() { "$FAKE_ROOT/bin/fakekill" "$@"; }\n' + script
result = subprocess.run(['/bin/sh','-c',script], timeout=10)
sys.exit(result.returncode)
'''


@contextlib.contextmanager
def server(payloads, linger=0):
    listener = socket.socket()
    listener.bind(("127.0.0.1", 0))
    listener.listen(1)
    listener.settimeout(3)
    port = listener.getsockname()[1]
    commands = []
    errors = []

    def serve():
        try:
            with listener.accept()[0] as conn:
                conn.settimeout(2)
                request = bytearray()
                while b"\n" not in request:
                    data = conn.recv(1024)
                    if not data:
                        break
                    request.extend(data)
                commands.append(bytes(request))
                for payload in payloads:
                    conn.sendall(payload)
                if linger:
                    time.sleep(linger)
        except (BrokenPipeError, ConnectionResetError):
            pass
        except Exception as exc:
            errors.append(exc)
        finally:
            listener.close()

    thread = threading.Thread(target=serve, daemon=True)
    thread.start()
    try:
        yield port, commands
    finally:
        thread.join(4)
        listener.close()
        if thread.is_alive():
            raise AssertionError("fake server failed to terminate")
        if errors:
            raise errors[0]


class ClientTests(unittest.TestCase):
    def invoke(self, *args, env=None):
        return subprocess.run([sys.executable, str(CLIENT), *args], capture_output=True, text=True, timeout=5, env=env)

    def test_commands_and_proxy_bypass(self):
        env = dict(os.environ, HTTP_PROXY="http://unreachable.invalid:9", ALL_PROXY="socks5://unreachable.invalid:9")
        for command in (("ping",), ("list",), ("snapshot",), ("get", "speed")):
            with self.subTest(command=command), server([b'{"ok":true,"value":1}\n']) as (port, requests):
                result = self.invoke(*command, "--port", str(port), env=env)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(json.loads(result.stdout)["value"], 1)
                self.assertEqual(requests, [(" ".join(command) + "\n").encode()])

    def test_stream_count_and_fragmented_lines(self):
        with server([b'{"n":', b'1}\n{"n":2}\n{"n":3}\n']) as (port, requests):
            result = self.invoke("--port", str(port), "stream", "--count", "2")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual([json.loads(line)["n"] for line in result.stdout.splitlines()], [1, 2])
        self.assertEqual(requests, [b"stream\n"])

    def test_invalid_json_utf8_nonstandard_values_and_server_error(self):
        for payload in (b"not json\n", b"\xff\n", b'{"n":NaN}\n', b'[]\n', b'{"ok":false,"error":"unsupported"}\n'):
            with self.subTest(payload=payload), server([payload]) as (port, _):
                result = self.invoke("ping", "--port", str(port))
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("carprobe client:", result.stderr)

    def test_worst_case_escaped_property_text_fits_frame_cap(self):
        # The server bounds text to 8192 chars/property; control chars escape 6x.
        value = {"props": {str(i): {"v": ["\u0000" * 4096] * 2} for i in range(26)}}
        payload = json.dumps(value).encode() + b"\n"
        self.assertGreater(len(payload), 1024 * 1024)
        self.assertLess(len(payload), client.MAX_LINE_BYTES)
        with server([payload]) as (port, _):
            result = self.invoke("snapshot", "--port", str(port))
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(json.loads(result.stdout), value)

    def test_oversized_line_and_eof(self):
        for payload in (b"x" * (client.MAX_LINE_BYTES + 1), b'{"partial":', b""):
            with self.subTest(size=len(payload)), server([payload]) as (port, _):
                result = self.invoke("snapshot", "--port", str(port))
                self.assertNotEqual(result.returncode, 0)

    def test_connection_and_line_timeouts(self):
        with server([], linger=0.25) as (port, _):
            result = self.invoke("ping", "--port", str(port), "--timeout", "0.05")
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("timed out", result.stderr)
        listener = socket.socket()
        listener.bind(("127.0.0.1", 0))
        port = listener.getsockname()[1]
        listener.close()
        self.assertNotEqual(self.invoke("ping", "--port", str(port), "--timeout", "0.1").returncode, 0)

    def test_line_deadline_not_reset_by_trickle(self):
        class Trickle:
            def settimeout(self, timeout):
                self.timeout = timeout
            def recv(self, size):
                time.sleep(0.015)
                return b" "
        with self.assertRaisesRegex(client.ClientError, "timed out"):
            client.JsonLines(Trickle(), 0.04).read()

    def test_cli_rejects_unsafe_or_unsupported_input(self):
        cases = [("raw",), ("log",), ("applog",), ("get",), ("get", "speed\nquit"), ("ping", "speed"),
                 ("stream", "--count", "0"), ("ping", "--count", "1"), ("ping", "--port", "0"),
                 ("ping", "--port", "65536"), ("ping", "--timeout", "nan"), ("ping", "--timeout", "inf")]
        for args in cases:
            with self.subTest(args=args):
                self.assertEqual(self.invoke(*args).returncode, 2)

    def test_help_does_not_connect(self):
        self.assertEqual(self.invoke("--help").returncode, 0)


class DeploymentTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="carprobe-fake-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / "bin").mkdir()
        (self.root / "proc").mkdir()
        self.base = self.root / "base"
        self.base.mkdir()
        self.jar = self.root / "local agent.jar"
        self.jar.write_bytes(b"fake jar")
        (self.root / "android.car.jar").write_bytes(b"fake framework")
        for name in ("adb", "id", "stat", "setsid", "fakekill", "toybox", "app_process", "sleep"):
            path = self.root / "bin" / name
            path.write_text(FAKE.replace("#!/usr/bin/env python3", "#!" + sys.executable))
            path.chmod(0o755)
        self.env = dict(os.environ, FAKE_ROOT=str(self.root), PATH=str(self.root / "bin") + os.pathsep + os.environ["PATH"])
        self.config()

    def config(self, **kwargs):
        (self.root / "config.json").write_text(json.dumps(kwargs))

    def invoke(self, *args, timeout=12):
        return subprocess.run([sys.executable, str(DEPLOY), *args], env=self.env,
                              capture_output=True, text=True, timeout=timeout)

    def action(self, action, *args):
        return self.invoke(action, "--serial", "FAKE_SERIAL", *args)

    def calls(self):
        path = self.root / "calls.jsonl"
        return [json.loads(line) for line in path.read_text().splitlines()] if path.exists() else []

    def process(self, pid=4242, name="p7-carprobe-agent"):
        proc = self.root / "proc" / str(pid)
        proc.mkdir()
        (proc / "cmdline").write_bytes(name.encode() + b"\0other\0")

    def test_help_and_validation_never_invoke_adb(self):
        self.assertEqual(self.invoke("--help").returncode, 0)
        for args in (("status",), ("status", "--serial", ""), ("status", "--serial", "-bad"),
                     ("start", "--serial", "FAKE_SERIAL", "--period", "999"),
                     ("start", "--serial", "FAKE_SERIAL", "--period", "2147483648"),
                     ("status", "--serial", "FAKE_SERIAL", "--port", "70000"),
                     ("status", "--serial", "FAKE_SERIAL", "--adb-timeout", "nan")):
            with self.subTest(args=args):
                self.assertEqual(self.invoke(*args).returncode, 2)
        self.assertEqual(self.calls(), [])

    def test_start_status_stop_and_exact_launch(self):
        start = self.action("start", "--jar", str(self.jar))
        self.assertEqual(start.returncode, 0, start.stderr)
        self.assertIn("STARTED PID=4242", start.stdout)
        launch = json.loads((self.root / "launch.json").read_text())
        self.assertIn("--nice-name=p7-carprobe-agent", launch["argv"])
        self.assertIn("cp.Agent", launch["argv"])
        self.assertEqual(launch["argv"][-6:], ["--port", "17666", "--period", "1000", "--pid", str(self.base / "agent.pid")])
        self.assertEqual(launch["classpath"], str(self.root / "android.car.jar") + ":" + str(self.base / "agent.jar"))
        self.assertEqual((self.base / "agent.jar").read_bytes(), b"fake jar")
        self.assertFalse((self.base / "lifecycle.lock").exists())
        status = self.action("status")
        self.assertEqual(status.returncode, 0, status.stderr)
        self.assertIn("RUNNING PID=4242", status.stdout)
        stop = self.action("stop")
        self.assertEqual(stop.returncode, 0, stop.stderr)
        self.assertFalse((self.base / "agent.pid").exists())
        self.assertEqual(json.loads((self.root / "signals.jsonl").read_text()), ["-TERM", "4242"])
        self.assertTrue(all(call[:2] == ["-s", "FAKE_SERIAL"] for call in self.calls()))
        self.assertFalse(any(call[2] in ("forward", "connect", "kill-server", "start-server") for call in self.calls()))

    def test_explicit_forward_no_rebind(self):
        result = self.action("forward", "--port", "18000")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.calls(), [["-s", "FAKE_SERIAL", "forward", "--no-rebind", "tcp:18000", "tcp:18000"]])
        self.config(forward_exists=True)
        self.assertNotEqual(self.action("forward").returncode, 0)

    def test_wrong_shell_uid_refused(self):
        self.config(uid=0)
        result = self.action("start", "--jar", str(self.jar))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("non-shell UID", result.stderr)
        self.assertFalse(any(call[2] == "push" for call in self.calls()))

    def test_wrong_owned_paths_refused(self):
        self.config(owners={str(self.base): 0})
        self.assertIn("wrong owner", self.action("status").stderr)
        self.config(owners={str(self.base / "agent.jar"): 0})
        (self.base / "agent.jar").write_bytes(b"old")
        self.assertIn("wrong owner", self.action("start", "--jar", str(self.jar)).stderr)

    def test_symlinks_and_directories_refused_without_touching_target(self):
        target = self.root / "unrelated"
        target.write_text("do not touch")
        for name in ("agent.jar", "agent.pid", "agent.log", "lifecycle.lock"):
            path = self.base / name
            with self.subTest(name=name, kind="symlink"):
                path.symlink_to(target)
                result = self.action("start", "--jar", str(self.jar))
                self.assertNotEqual(result.returncode, 0)
                path.unlink()
            if name != "lifecycle.lock":
                with self.subTest(name=name, kind="directory"):
                    path.mkdir()
                    result = self.action("start", "--jar", str(self.jar))
                    self.assertNotEqual(result.returncode, 0)
                    path.rmdir()
        self.assertEqual(target.read_text(), "do not touch")
        self.base.rmdir()
        self.base.symlink_to(self.root)
        self.assertIn("symlink refused", self.action("status").stderr)

    def test_hardlinks_refused_without_truncating_other_files(self):
        target = self.root / "unrelated"
        target.write_text("4242\n")
        for name in ("agent.jar", "agent.pid", "agent.log"):
            with self.subTest(name=name):
                path = self.base / name
                os.link(target, path)
                result = self.action("start", "--jar", str(self.jar))
                self.assertIn("hard-linked file refused", result.stderr)
                self.assertEqual(target.read_text(), "4242\n")
                path.unlink()

    def test_wrong_agent_uid_and_stop_timeout_never_force_kill(self):
        self.process()
        self.config(owners={str(self.root / "proc" / "4242"): 0})
        self.assertIn("wrong owner", self.action("stop").stderr)
        self.assertFalse((self.root / "signals.jsonl").exists())
        self.config(keep_running=True)
        result = self.action("stop")
        self.assertIn("stop timed out; PID=4242", result.stderr)
        self.assertTrue((self.root / "proc" / "4242").exists())
        self.assertEqual(json.loads((self.root / "signals.jsonl").read_text()), ["-TERM", "4242"])

    def test_custom_launch_and_shell_metacharacters_in_serial_are_argv_only(self):
        serial = "FAKE ; quoted ' serial"
        self.env["EXPECTED_SERIAL"] = serial
        result = self.invoke("--serial", serial, "start", "--jar", str(self.jar), "--port", "18001", "--period", "2500")
        self.assertEqual(result.returncode, 0, result.stderr)
        launch = json.loads((self.root / "launch.json").read_text())["argv"]
        self.assertEqual(launch[-6:-2], ["--port", "18001", "--period", "2500"])
        self.assertTrue(all(call[1] == serial for call in self.calls()))

    def test_running_agent_and_duplicate_agent_never_replace_jar(self):
        (self.base / "agent.jar").write_bytes(b"running version")
        self.process()
        result = self.action("start", "--jar", str(self.jar))
        self.assertIn("already active", result.stderr)
        self.assertEqual((self.base / "agent.jar").read_bytes(), b"running version")
        self.process(4243)
        result = self.action("start", "--jar", str(self.jar))
        self.assertIn("duplicate agent PIDs", result.stderr)
        self.assertFalse(any(call[2] == "push" for call in self.calls()))

    def test_unrelated_live_pid_is_never_signalled_or_deleted(self):
        self.process(name="unrelated-service")
        (self.base / "agent.pid").write_text("4242\n")
        for action in ("start", "stop", "status"):
            with self.subTest(action=action):
                args = ("--jar", str(self.jar)) if action == "start" else ()
                result = self.action(action, *args)
                self.assertIn("another process", result.stderr)
        self.assertTrue((self.base / "agent.pid").exists())
        self.assertFalse((self.root / "signals.jsonl").exists())

    def test_stale_and_malformed_pid(self):
        pidfile = self.base / "agent.pid"
        for text in ("1", "0", "-1", "42; echo unsafe", "", "word"):
            pidfile.write_text(text)
            self.assertNotEqual(self.action("stop").returncode, 0)
            self.assertTrue(pidfile.exists())
        pidfile.write_text("5555\n")
        status = self.action("status")
        self.assertIn("stale PID=5555", status.stdout)
        self.assertTrue(pidfile.exists())
        result = self.action("start", "--jar", str(self.jar))
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(pidfile.read_text().strip(), "4242")

    def test_existing_lock_is_not_deleted(self):
        lock = self.base / "lifecycle.lock"
        lock.mkdir()
        (lock / "owner").write_text("another invocation")
        result = self.action("start", "--jar", str(self.jar))
        self.assertIn("lifecycle locked", result.stderr)
        self.assertEqual((lock / "owner").read_text(), "another invocation")
        self.assertFalse(any(call[2] == "push" for call in self.calls()))

    def test_parallel_starts_serialized_by_remote_lock(self):
        self.config(push_delay=0.4)
        argv = [sys.executable, str(DEPLOY), "start", "--serial", "FAKE_SERIAL", "--jar", str(self.jar)]
        first = subprocess.Popen(argv, env=self.env, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        try:
            deadline = time.monotonic() + 3
            while not (self.base / "lifecycle.lock" / "owner").exists() and time.monotonic() < deadline:
                time.sleep(0.01)
            second = self.action("start", "--jar", str(self.jar))
            output, error = first.communicate(timeout=10)
            self.assertEqual(first.returncode, 0, error)
            self.assertNotEqual(second.returncode, 0)
            self.assertIn("lifecycle locked", second.stderr)
            self.assertEqual(sum(call[2] == "push" for call in self.calls()), 1)
        finally:
            if first.poll() is None:
                first.kill(); first.communicate()

    def test_readiness_timeout_keeps_verified_process_and_reports_pid(self):
        self.config(hung=True)
        result = self.action("start", "--jar", str(self.jar), "--start-timeout", "0.05")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("readiness timed out", result.stderr)
        self.assertIn("PID=4242", result.stderr)
        self.assertTrue((self.root / "proc" / "4242").exists())
        self.assertTrue((self.base / "agent.jar").is_file())
        self.assertFalse((self.root / "signals.jsonl").exists())
        self.assertFalse((self.base / "lifecycle.lock").exists())
        self.assertEqual(list(self.base.glob("upload-*.jar")), [])

    def test_ping_requires_agent_protocol_not_just_valid_json(self):
        self.config(reply={"ok": True, "service": "unrelated"})
        result = self.action("start", "--jar", str(self.jar), "--start-timeout", "0.05")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("did not identify carprobe", result.stderr)

    def test_generated_shell_safety_contract(self):
        result = self.action("start", "--jar", str(self.jar))
        self.assertEqual(result.returncode, 0, result.stderr)
        scripts = [shlex.split(call[-1])[0] for call in self.calls() if call[2] == "shell"]
        combined = "\n".join(scripts)
        for text in ("127.0.0.1", "--nice-name=p7-carprobe-agent", "</dev/null", '>"$LOG" 2>&1', "check_lock", "regular_or_absent", 'owned "/proc/$1"'):
            self.assertIn(text, combined)
        for text in ("rm -rf", "kill -9", "0.0.0.0", "adb connect", "kill-server"):
            self.assertNotIn(text, combined)
        pushes = [call for call in self.calls() if call[2] == "push"]
        self.assertEqual(len(pushes), 1)
        self.assertRegex(pushes[0][-1], r"^/data/local/tmp/p7-carprobe/upload-[0-9a-f]{32}\.jar$")

    def test_shell_wrapper_help(self):
        result = subprocess.run(["sh", str(ROOT / "deploy.sh"), "--help"], env=self.env, capture_output=True, text=True, timeout=5)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.calls(), [])


if __name__ == "__main__":
    unittest.main()
