import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import zipfile
from unittest.mock import patch

REPO = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("build_version", REPO/"tools/build_version.py")
v = importlib.util.module_from_spec(spec)
spec.loader.exec_module(v)

class VersionTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        (self.root/"carpad").mkdir()
        (self.root/"instrument").mkdir()
        (self.root/"instrument/ctl").mkdir()
        (self.root/"instrument/ctl/p7assistctl").write_text("current ctl")
        (self.root/"instrument/autostart").mkdir()
        for name in ["p7boot.sh", "p7watch.sh", "install-autostart.sh", "uninstall-autostart.sh"]:
            (self.root/"instrument/autostart"/name).write_text("current boot")
        self.manifest = self.root/"carpad/AndroidManifest.xml"
        # Fixed historical regression is synthetic: a future manifest-only bump
        # must not require editing this test or the fake AAPT defaults.
        self.manifest.write_text('<manifest xmlns:android="http://schemas.android.com/apk/res/android" android:versionName="2.13.4" android:versionCode="112"/>')
        self.out = self.root/"out"
        self.go = self.root/"go"
        self.go.write_text("#!"+sys.executable+"""
import json, os, pathlib, sys
assert os.environ['GOTOOLCHAIN']=='local'
if sys.argv[1:] == ['version']:
 print('go version '+os.environ.get('FAKE_GO_VERSION','go1.23.4')+' linux/amd64')
else:
 assert os.environ['GOARCH']=='arm' and os.environ['GOARM']=='7'
 flags=sys.argv[sys.argv.index('-ldflags')+1]
 version=flags.split('-X main.appVersion=')[1]
 pathlib.Path(sys.argv[sys.argv.index('-o')+1]).write_text(json.dumps({'reported_version':version}))
""")
        self.go.chmod(0o700)
        self.aapt=self.root/"aapt2"
        self.aapt.write_text("#!"+sys.executable+"""
import os
print("package: name='example' versionCode='"+os.environ.get('APK_CODE','112')+"' versionName='"+os.environ.get('APK_NAME','2.13.4')+"'")
""")
        self.aapt.chmod(0o700)
        self.scope=patch.object(v,"ROOT",self.root)
        self.scope.start()
        self.addCleanup(self.scope.stop)

    def prepare(self):
        v.prepare(self.out,str(self.go))

    def apk(self,data=None):
        p=self.out/"fixture.apk"
        with zipfile.ZipFile(p,"w") as z:
            z.writestr("assets/p7assist", data if data is not None else (self.out/"instrument/p7assist").read_bytes())
        return p

    def test_real_canonical_manifest_is_valid(self):
        name,code=v.version(REPO/"carpad/AndroidManifest.xml")
        self.assertTrue(name)
        self.assertGreater(code,0)

    def test_canonical_preserved_and_update_equality(self):
        self.assertEqual(v.version(self.manifest),("2.13.4",112))
        self.prepare()
        reported=json.loads((self.out/"instrument/p7assist").read_text())["reported_version"]
        name,code=v.version(self.out/"AndroidManifest.xml")
        # Mirrors checkClusterVersion's versionName equality, not Android execution.
        self.assertNotEqual("2.12.4",name)  # old daemon is eligible when parked
        self.assertEqual(reported,name)   # rebuilt daemon stops repeated upload
        self.assertEqual(code,112)
        self.assertEqual((self.out/"instrument/p7boot.sh").read_text(),"current boot")
        self.assertEqual((self.out/"instrument/p7boot.sh").stat().st_mode & 0o777,0o755)
        v.verify(self.out,self.apk(),str(self.aapt))

    def test_stale_prebuilt_is_rejected(self):
        self.prepare()
        with self.assertRaisesRegex(ValueError,"stale"):
            v.verify(self.out,self.apk(b"old tracked binary"),str(self.aapt))

    def test_compiled_apk_mismatch_is_rejected(self):
        self.prepare()
        with patch.dict(os.environ,{"APK_NAME":"2.12.4","APK_CODE":"102"}):
            with self.assertRaisesRegex(ValueError,"compiled APK"):
                v.verify(self.out,self.apk(),str(self.aapt))

    def test_bad_manifest_rejected_before_build(self):
        self.manifest.write_text('<manifest/>')
        with self.assertRaises(ValueError):
            self.prepare()
        self.assertFalse((self.out/"instrument/p7assist").exists())

    def test_go_failure_never_falls_back(self):
        self.prepare()
        self.go.write_text("#!/bin/sh\nexit 7\n")
        with self.assertRaises(subprocess.CalledProcessError):
            self.prepare()
        self.assertFalse((self.out/"version.json").exists())

    def test_unknown_go_version_rejected(self):
        self.prepare()
        for tool in ["go1.22.12", "go1.23.40", "go1.24.4", "go1.23.4rc1"]:
            with self.subTest(tool=tool), patch.dict(os.environ,{"FAKE_GO_VERSION":tool, "GOTOOLCHAIN":"auto"}):
                with self.assertRaisesRegex(ValueError,"requires go1.23.4"):
                    self.prepare()
                self.assertFalse((self.out/"version.json").exists())

    def test_snapshot_changed_rejected(self):
        self.prepare()
        p=self.out/"AndroidManifest.xml"
        p.write_text(p.read_text().replace("2.13.4","2.13.5"))
        with self.assertRaisesRegex(ValueError,"snapshot changed"):
            v.verify(self.out,self.apk(),str(self.aapt))

    def test_missing_current_script_never_uses_old_asset(self):
        (self.root/"instrument/autostart/p7watch.sh").unlink()
        with self.assertRaises(FileNotFoundError):
            self.prepare()
        self.assertFalse((self.out/"version.json").exists())

    def test_entrypoint_contract(self):
        for name in ["build.sh","build-mac.sh"]:
            s=(REPO/"carpad"/name).read_text()
            self.assertNotIn("--version-name",s)
            self.assertNotIn("--version-code",s)
            self.assertIn("--manifest build/AndroidManifest.xml",s)
            self.assertLess(s.index('prepare --out build'),s.index('compile --dir'))
            self.assertIn('--prefix assets/ --dir build/instrument',s)
            self.assertLess(s.index('verify --out build'),s.index('# 6)'))
            self.assertIn('--apk build/apk/signed.apk',s)
            self.assertLess(s.index('--apk build/apk/signed.apk'),s.index('mv -f'))

        s=(REPO/"build-all.sh").read_text()
        self.assertNotIn('cp "$HERE/dist/仪表端/p7assist" "$HERE/carpad/assets/p7assist"',s)
        source=(REPO/"instrument/config.go").read_text()
        self.assertIn('var appVersion = "dev-unversioned"',source)
        service=(REPO/"carpad/src/com/xiaopeng/clusterassist/PushService.java").read_text()
        self.assertIn('if (at.isEmpty() || at.equals(my)) return;',service)
        self.assertIn('if (!"park".equals(mode))',service)

if __name__=="__main__":
    unittest.main()
