#!/usr/bin/env python3
"""Explicit, shell-UID-only lifecycle for the loopback carprobe agent (no watcher)."""
import argparse
import json
import math
from pathlib import Path
import shlex
import subprocess
import sys
import time
import uuid

BASE = "/data/local/tmp/p7-carprobe"
NAME = "p7-carprobe-agent"

# All remote mutations are restricted to these literal paths or a UUID upload.
# A lock spans prepare, adb push, launch and readiness; no running jar is replaced.
REMOTE = r'''
set -eu
umask 077
BASE=/data/local/tmp/p7-carprobe
JAR=$BASE/agent.jar
PIDFILE=$BASE/agent.pid
LOG=$BASE/agent.log
LOCK=$BASE/lifecycle.lock
fail() { echo "carprobe: $*" >&2; exit 1; }
[ "$(id -u)" = 2000 ] || fail 'refusing non-shell UID (expected 2000; do not use adb root)'
owned() { [ "$(stat -c %u "$1")" = 2000 ] || fail "wrong owner: $1"; }
regular_or_absent() {
    [ ! -L "$1" ] || fail "symlink refused: $1"
    if [ -e "$1" ]; then
        [ -f "$1" ] || fail "not a regular file: $1"
        owned "$1"
        [ "$(stat -c %h "$1")" = 1 ] || fail "hard-linked file refused: $1"
    fi
}
[ ! -L "$BASE" ] || fail "symlink refused: $BASE"
if [ ! -e "$BASE" ]; then mkdir "$BASE" || fail 'cannot create base'; fi
[ -d "$BASE" ] || fail 'base is not a directory'
owned "$BASE"
chmod 700 "$BASE"
check_files() {
    regular_or_absent "$JAR"
    regular_or_absent "$PIDFILE"
    regular_or_absent "$LOG"
    regular_or_absent "$UPLOAD"
}
check_lock() {
    [ ! -L "$LOCK" ] && [ -d "$LOCK" ] || fail 'invalid lifecycle lock'
    owned "$LOCK"
    regular_or_absent "$LOCK/owner"
    [ -f "$LOCK/owner" ] && [ "$(cat "$LOCK/owner")" = "$TOKEN" ] || fail 'lifecycle lock not owned by this invocation'
}
release_lock() {
    check_lock
    # Both targets have just been checked; never recursively remove a lock.
    rm "$LOCK/owner"
    rmdir "$LOCK"
}
acquire_lock() {
    [ ! -L "$LOCK" ] || fail 'symlink lifecycle lock refused'
    mkdir "$LOCK" 2>/dev/null || fail 'lifecycle locked; another invocation or an interrupted operation requires inspection (no automatic lock deletion)'
    printf '%s\n' "$TOKEN" > "$LOCK/owner"
}
identity() {
    [ -d "/proc/$1" ] || return 1
    [ -r "/proc/$1/cmdline" ] || fail "cannot verify cmdline for PID $1"
    first=$(tr '\000' '\n' < "/proc/$1/cmdline" | sed -n '1p')
    [ "$first" = p7-carprobe-agent ] || return 1
    owned "/proc/$1"
}
inspect() {
    check_files
    stored=''
    if [ -f "$PIDFILE" ]; then
        stored=$(cat "$PIDFILE")
        case "$stored" in ''|*[!0-9]*) fail 'invalid PID file';; esac
        [ "$stored" -gt 1 ] 2>/dev/null || fail 'unsafe PID value'
        if [ -d "/proc/$stored" ]; then
            identity "$stored" || fail "PID $stored belongs to another process; refusing to kill or remove PID file"
        fi
    fi
    active=''
    for proc in /proc/[0-9]*; do
        [ -d "$proc" ] || continue
        candidate=${proc##*/}
        # Unrelated UIDs may hide their cmdline; only an actual matching argv0 is selected.
        [ -r "$proc/cmdline" ] || continue
        first=$(tr '\000' '\n' < "$proc/cmdline" 2>/dev/null | sed -n '1p')
        if [ "$first" = p7-carprobe-agent ]; then
            owned "$proc"
            [ -z "$active" ] || fail "duplicate agent PIDs $active $candidate; manual inspection required"
            active=$candidate
        fi
    done
    if [ -n "$stored" ] && [ -d "/proc/$stored" ] && [ "$stored" != "$active" ]; then
        fail "PID $stored changed during inspection; retry safely"
    fi
}
remove_stale_pid() {
    [ -z "$active" ] || fail 'cannot remove PID file with an active agent'
    regular_or_absent "$PIDFILE"
    if [ -f "$PIDFILE" ]; then
        last=$(cat "$PIDFILE")
        [ "$last" = "$stored" ] || fail 'PID file changed'
        [ ! -e "/proc/$last" ] || fail "PID $last still exists; cannot recover stale PID"
        rm "$PIDFILE"
    fi
}
'''

PREPARE = r'''
command -v setsid >/dev/null 2>&1 || fail 'setsid is required (no fallback watcher)'
[ -x /system/bin/app_process ] || fail 'app_process is required'
[ -r /system/framework/android.car.jar ] || fail 'android.car.jar is required'
[ -x /system/bin/toybox ] || fail 'toybox timeout/nc are required for bounded loopback readiness'
/system/bin/toybox timeout 1 /system/bin/toybox true >/dev/null || fail 'toybox timeout is required for readiness'
acquire_lock
keep=0
trap 'if [ "$keep" = 0 ]; then release_lock; fi' EXIT
inspect
[ -z "$active" ] || fail "agent already active with PID $active; stop it before replacing its jar"
remove_stale_pid
keep=1
printf 'PREPARED\n'
'''
LAUNCH = r'''
check_lock
inspect
[ -z "$active" ] || fail "agent already active with PID $active"
[ -f "$UPLOAD" ] || fail 'missing uploaded jar'
# Source and destination are verified regular, shell-owned paths, not symlinks/directories.
regular_or_absent "$UPLOAD"
regular_or_absent "$JAR"
mv "$UPLOAD" "$JAR"
CLASSPATH=/system/framework/android.car.jar:$JAR setsid /system/bin/app_process /system/bin --nice-name=p7-carprobe-agent cp.Agent --port "$PORT" --period "$PERIOD" --pid "$PIDFILE" </dev/null >"$LOG" 2>&1 &
printf 'LAUNCHED\n'
'''
STATUS = r'''
acquire_lock
trap release_lock EXIT
inspect
if [ -n "$active" ]; then
    printf 'RUNNING PID=%s (process identity verified; readiness not implied)\n' "$active"
elif [ -n "$stored" ]; then
    printf 'STOPPED stale PID=%s (no process; start/stop may safely recover it)\n' "$stored"
else
    printf 'STOPPED\n'
fi
'''
STOP = r'''
acquire_lock
trap release_lock EXIT
inspect
if [ -z "$active" ]; then remove_stale_pid; printf 'STOPPED\n'; exit 0; fi
target=$active
# Verify exact argv0 AND shell UID immediately before signalling, never trust a bare PID.
identity "$target" || fail "PID $target identity changed; not signalling"
kill -TERM "$target" || fail "cannot signal PID $target"
i=0
while [ -d "/proc/$target" ] && [ "$i" -lt 5 ]; do
    identity "$target" || fail "PID $target changed; no further signals sent"
    sleep 1
    i=$((i+1))
done
[ ! -d "/proc/$target" ] || fail "stop timed out; PID=$target still present, no SIGKILL sent; inspect status/log"
inspect
[ -z "$active" ] || fail "another agent appeared: PID=$active"
remove_stale_pid
printf 'STOPPED PID=%s\n' "$target"
'''
PROBE = r'''
check_lock
inspect
[ -n "$active" ] || fail 'agent has no verified process yet'
printf 'PID=%s\n' "$active"
# timeout bounds both connection and reads; address is deliberately loopback only.
printf 'ping\nquit\n' | /system/bin/toybox timeout 2 /system/bin/toybox nc -w 1 127.0.0.1 "$PORT"
'''
CLEANUP = r'''
check_lock
regular_or_absent "$UPLOAD"
if [ -f "$UPLOAD" ]; then rm "$UPLOAD"; fi
release_lock
'''


class DeployError(Exception):
    pass


class Deployment:
    def __init__(self, args):
        self.args = args
        self.token = uuid.uuid4().hex
        self.upload = BASE + "/upload-" + self.token + ".jar"

    def adb(self, *argv, timeout=None):
        if not self.args.serial:
            raise DeployError("--serial is required for every actual adb action")
        try:
            result = subprocess.run(
                ["adb", "-s", self.args.serial, *argv], text=True,
                stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                timeout=timeout or self.args.adb_timeout, check=False,
            )
        except subprocess.TimeoutExpired as exc:
            raise DeployError("adb command timed out; remote state may persist; use status (never blindly retry/kill)") from exc
        except OSError as exc:
            raise DeployError("cannot execute adb: " + str(exc)) from exc
        if result.returncode:
            raise DeployError((result.stderr or result.stdout or "adb failed").strip())
        return result.stdout.strip()

    def remote(self, body, timeout=None):
        values = {"TOKEN": self.token, "UPLOAD": self.upload,
                  "PORT": str(self.args.port), "PERIOD": str(self.args.period)}
        script = "\n".join(k + "=" + shlex.quote(v) for k, v in values.items()) + "\n" + REMOTE + body
        # adb joins shell arguments remotely; quote the *whole* sh -c argument.
        return self.adb("shell", "sh", "-c", shlex.quote(script), timeout=timeout)

    def run(self):
        action = self.args.action
        if action == "forward":
            print(self.adb("forward", "--no-rebind", "tcp:" + str(self.args.port), "tcp:" + str(self.args.port)))
            print("Forward configured explicitly; agent lifecycle unchanged.")
            return 0
        if action == "status":
            print(self.remote(STATUS))
            return 0
        if action == "stop":
            print(self.remote(STOP))
            return 0
        jar = Path(self.args.jar).expanduser().resolve()
        if not jar.is_file():
            raise DeployError("jar is not a regular local file: " + str(jar))
        # Cleanup only after this invocation successfully acquired the lock.
        self.remote(PREPARE)
        try:
            self.adb("push", str(jar), self.upload)
            self.remote(LAUNCH)
            deadline = time.monotonic() + self.args.start_timeout
            pid = "unknown"
            last_error = "no ping response"
            while time.monotonic() < deadline:
                try:
                    output = self.remote(PROBE, timeout=min(self.args.adb_timeout, 5))
                    for line in output.splitlines():
                        if line.startswith("PID="):
                            pid = line[4:]
                        else:
                            try:
                                reply = json.loads(line)
                            except (ValueError, TypeError):
                                continue
                            if not (isinstance(reply, dict) and reply.get("ok") is True
                                    and reply.get("service") == "carprobe" and reply.get("protocol") == 1):
                                last_error = "ping did not identify carprobe protocol 1"
                                continue
                            print("STARTED PID=" + pid + " (loopback ping succeeded; source health not implied)")
                            return 0
                except DeployError as exc:
                    last_error = str(exc)
                time.sleep(min(0.2, max(0, deadline - time.monotonic())))
            # Inspection under the held lock reports a PID even if Binder startup is hung.
            try:
                state = self.remote("check_lock\ninspect\nprintf 'PID=%s\\n' \"${active:-none}\"\n")
            except DeployError as exc:
                state = "PID=" + pid + "; inspection failed: " + str(exc)
            raise DeployError("start readiness timed out; " + state + ". Process/jar kept (Binder may be hung); use status/stop and inspect " + BASE + "/agent.log. " + last_error)
        finally:
            try:
                self.remote(CLEANUP)
            except DeployError as exc:
                print("WARNING: safe cleanup failed; inspect lifecycle lock/upload manually: " + str(exc), file=sys.stderr)


def port_number(value):
    number = int(value)
    if not 1 <= number <= 65535:
        raise argparse.ArgumentTypeError("port must be 1..65535")
    return number


def positive(value):
    number = float(value)
    if not 0 < number <= 300 or not math.isfinite(number):
        raise argparse.ArgumentTypeError("timeout must be finite, >0 and <=300 seconds")
    return number


def parser():
    p = argparse.ArgumentParser(description=__doc__, epilog="Examples: deploy.sh start --serial SERIAL; deploy.sh status --serial SERIAL; deploy.sh forward --serial SERIAL; deploy.sh stop --serial SERIAL. No auto-connect, forward, watcher or device access without an explicit action.")
    p.add_argument("action", choices=("start", "stop", "status", "forward"))
    p.add_argument("--serial", required=True, help="explicit adb serial (required; never selects a default device)")
    p.add_argument("--port", type=port_number, default=17666)
    p.add_argument("--period", type=int, default=1000, help="agent sample period in ms (minimum 1000)")
    p.add_argument("--jar", default="dist/carprobe/carprobe.jar")
    p.add_argument("--adb-timeout", type=positive, default=15, help="finite per-adb-call timeout, seconds")
    p.add_argument("--start-timeout", type=positive, default=10, help="readiness wait, seconds; timed-out agents are not killed")
    return p


def main(argv=None):
    p = parser()
    args = p.parse_args(argv)
    if not args.serial.strip() or args.serial.startswith("-"):
        p.error("--serial must be a nonempty explicit serial, not an option")
    if not 1000 <= args.period <= 2147483647:
        p.error("--period must be 1000..2147483647 ms (Java int range)")
    try:
        return Deployment(args).run()
    except DeployError as exc:
        print("carprobe deploy: " + str(exc), file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
