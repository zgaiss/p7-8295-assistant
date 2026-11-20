#!/usr/bin/env python3
"""Canonical manifest version + mandatory fresh instrument build. No signing."""
import argparse, hashlib, json, os, pathlib, re, shutil, subprocess, xml.etree.ElementTree as ET, zipfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
INSTRUMENT_GO = "go1.23.4"  # compiler recorded in the previously bundled ARM binaries
ANDROID = "{http://schemas.android.com/apk/res/android}"
def version(path):
    node = ET.parse(path).getroot()
    name, code = node.get(ANDROID+"versionName", ""), node.get(ANDROID+"versionCode", "")
    if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", name) or not code.isdigit() or not 0 < int(code) <= 2147483647:
        raise ValueError("manifest requires literal semantic versionName and positive versionCode")
    return name, int(code)

def prepare(out, go):
    out.mkdir(parents=True, exist_ok=True)
    (out/"version.json").unlink(missing_ok=True)  # failed retries cannot reuse prior attestation
    snapshot = out/"AndroidManifest.xml"
    snapshot.write_bytes((ROOT/"carpad/AndroidManifest.xml").read_bytes())
    name, code = version(snapshot)
    env = dict(os.environ, GOTOOLCHAIN="local", CGO_ENABLED="0", GOOS="linux", GOARCH="arm", GOARM="7")
    tool = subprocess.check_output([go, "version"], cwd=ROOT/"instrument", env=env, text=True)
    if not re.fullmatch(r"go version "+re.escape(INSTRUMENT_GO)+r" [^ /\s]+/[^ /\s]+\s*", tool):
        raise ValueError(f"instrument packaging requires {INSTRUMENT_GO} (bundled ARM baseline); "+
                         f"set GO to that compiler, got: {tool.strip()}")
    dest = out/"instrument"
    dest.mkdir(exist_ok=True)
    binary = dest/"p7assist"
    tmp = dest/"p7assist.tmp"
    try:
        subprocess.run([go, "build", "-trimpath", "-ldflags", "-s -w -X main.appVersion="+name,
                        "-o", str(tmp.resolve()), "."], cwd=ROOT/"instrument", env=env, check=True)
        if not tmp.is_file() or not tmp.stat().st_size:
            raise ValueError("instrument build did not produce a binary")
        os.replace(tmp, binary)
    finally:
        tmp.unlink(missing_ok=True)
    scripts = ["p7boot.sh", "p7watch.sh", "install-autostart.sh", "uninstall-autostart.sh"]
    for source in [ROOT/"instrument/ctl/p7assistctl", *[ROOT/"instrument/autostart"/name for name in scripts]]:
        target = dest/source.name
        shutil.copyfile(source, target)
        target.chmod(0o755)
    (out/"version.json").write_text(json.dumps({"name":name,"code":code,"sha256":hashlib.sha256(binary.read_bytes()).hexdigest()}))

def verify(out, apk, aapt):
    expected = json.loads((out/"version.json").read_text())
    name, code = version(out/"AndroidManifest.xml")
    if (name, code) != (expected["name"], expected["code"]):
        raise ValueError("manifest snapshot changed during build")
    with zipfile.ZipFile(apk) as z:
        if z.namelist().count("assets/p7assist") != 1 or hashlib.sha256(z.read("assets/p7assist")).hexdigest() != expected["sha256"]:
            raise ValueError("APK contains stale/missing/duplicate instrument asset")
    info = subprocess.check_output([aapt, "dump", "badging", str(apk)], text=True)
    if not re.search(r"versionCode='"+str(code)+r"'\s+versionName='"+re.escape(name)+r"'", info):
        raise ValueError("compiled APK version differs from canonical manifest")

def main():
    p=argparse.ArgumentParser()
    p.add_argument("action",choices=["prepare","verify"])
    p.add_argument("--out",type=pathlib.Path,required=True)
    p.add_argument("--go",default=os.environ.get("GO","go"))
    p.add_argument("--apk",type=pathlib.Path)
    p.add_argument("--aapt")
    a=p.parse_args()
    if a.action=="prepare": prepare(a.out,a.go)
    else:
        if not a.apk or not a.aapt:p.error("verify requires --apk and --aapt")
        verify(a.out,a.apk,a.aapt)
if __name__=="__main__":
    main()
