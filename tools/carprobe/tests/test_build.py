"""Offline build-helper tests; fake d8 is not an Android runtime test."""
import importlib.util
from pathlib import Path
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location('carprobe_build', Path(__file__).resolve().parents[1] / 'build.py')
build = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(build)


class BuildTests(unittest.TestCase):
    def test_numeric_sdk_order_and_ignore_preview(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for version in ('9.0.0', '36.0.0', '37.0.0-rc1'):
                p = root / 'build-tools' / version / 'd8'
                p.parent.mkdir(parents=True)
                p.touch()
            for version in ('9', '28', 'Baklava'):
                p = root / 'platforms' / ('android-' + version) / 'android.jar'
                p.parent.mkdir(parents=True)
                p.touch()
            d8, jar = build.sdk_tools(root)
            self.assertEqual(d8.parent.name, '36.0.0')
            self.assertEqual(jar.parent.name, 'android-28')

    def test_missing_sdk_fails_cleanly(self):
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaisesRegex(ValueError, 'Android SDK'):
                build.sdk_tools(Path(tmp))


if __name__ == '__main__':
    unittest.main()
