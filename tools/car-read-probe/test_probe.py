#!/usr/bin/env python3
"""Host fake Android contract tests; never adb or a vehicle. All outputs are temporary."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

FAKES = {
    'android/os/Process.java': '''package android.os; public class Process {
        public static int myUid() { return Integer.getInteger("uid", 2000); } }''',
    'android/os/IBinder.java': '''package android.os; public interface IBinder {
        String getInterfaceDescriptor(); }''',
    'android/os/ServiceManager.java': '''package android.os; public class ServiceManager {
        public static IBinder getService(String name) {
            if (!name.equals("car_service")) throw new AssertionError(name);
            return "absent".equals(System.getProperty("mode")) ? null : () -> "car";
        } }''',
    'android/car/ICar.java': '''package android.car; import android.os.IBinder;
        public interface ICar { IBinder getCarService(String name);
        class Stub { public static ICar asInterface(IBinder binder) { return name -> {
            if (!name.equals("property")) throw new AssertionError(name);
            if ("no_property".equals(System.getProperty("mode"))) return null;
            return () -> "descriptor".equals(System.getProperty("mode")) ? "unknown"
                : "android.car.hardware.property.ICarProperty";
        }; } } }''',
    'android/car/hardware/CarPropertyValue.java': '''package android.car.hardware;
        public class CarPropertyValue {
            private final int status; private final Object value;
            public CarPropertyValue() { this(0, 0.0f); }
            public CarPropertyValue(int status, Object value) { this.status = status; this.value = value; }
            public int getStatus() { return "status".equals(System.getProperty("mode")) ? 2 : status; }
            public long getTimestamp() { return 123456789L; }
            public Object getValue() {
                if (getStatus() != 0) throw new AssertionError("must not read unavailable value");
                return value;
            }
        }''',
    'android/car/hardware/CarPropertyConfig.java': '''package android.car.hardware;
        public class CarPropertyConfig {
            private final int id, access; private final int[] areas; private final boolean global;
            public CarPropertyConfig(int id, int access, boolean global, int... areas) {
                this.id = id; this.access = access; this.global = global; this.areas = areas;
            }
            public int getPropertyId() { return id; }
            public int getAccess() { return access; }
            public int[] getAreaIds() { return areas; }
            public boolean isGlobalProperty() { return global; }
        }''',
    'android/car/hardware/property/ICarProperty.java': r'''package android.car.hardware.property;
        import android.os.IBinder; import android.car.hardware.CarPropertyValue;
        import android.car.hardware.CarPropertyConfig; import java.util.*;
        public interface ICarProperty {
            CarPropertyValue getProperty(int id, int area) throws Exception;
            List<CarPropertyConfig> getPropertyList();
        class Stub { static int connects;
            public static ICarProperty asInterface(IBinder binder) {
                if (++connects != 1) throw new AssertionError("reconnected");
                return new ICarProperty() {
                    final String mode = System.getProperty("mode", "ok");
                    public List<CarPropertyConfig> getPropertyList() {
                        if (mode.equals("all_empty")) return Collections.emptyList();
                        if (mode.equals("all_limit")) return Collections.nCopies(4097,
                            new CarPropertyConfig(1, 1, true));
                        if (mode.equals("all_mixed")) return Arrays.asList(
                            new CarPropertyConfig(1, 1, true),
                            new CarPropertyConfig(2, 3, false, 1, 2),
                            new CarPropertyConfig(3, 2, true),
                            new CarPropertyConfig(4, 0, true),
                            new CarPropertyConfig(5, 1, false));
                        if (mode.equals("all_duplicate")) return Arrays.asList(
                            new CarPropertyConfig(2, 1, false, 1, 1, 2),
                            new CarPropertyConfig(2, 3, false, 2, 1));
                        if (mode.equals("all_continue")) return Arrays.asList(
                            new CarPropertyConfig(1, 1, true), new CarPropertyConfig(2, 1, true),
                            new CarPropertyConfig(3, 1, true), new CarPropertyConfig(4, 1, true));
                        if (mode.equals("all_preview")) return Arrays.asList(
                            new CarPropertyConfig(1, 1, true), new CarPropertyConfig(2, 1, true),
                            new CarPropertyConfig(3, 1, true), new CarPropertyConfig(4, 1, true));
                        throw new AssertionError("unexpected getPropertyList");
                    }
                    public CarPropertyValue getProperty(int id, int area) throws Exception {
                        if (mode.equals("all_mixed") || mode.equals("all_duplicate")) {
                            if (!((id == 1 && area == 0 && mode.equals("all_mixed"))
                                  || (id == 2 && (area == 1 || area == 2))))
                                throw new AssertionError("read non-readable or guessed area");
                        } else if (mode.equals("all_continue")) {
                            if (area != 0) throw new AssertionError("global area must be zero");
                            if (id == 1) throw new SecurityException("test denied");
                            if (id == 2) return new CarPropertyValue(2, "unavailable");
                            if (id == 4) return null;
                        } else if (mode.equals("all_preview")) {
                            if (id == 1) {
                                char[] chars = new char[1200]; Arrays.fill(chars, 'x');
                                return new CarPropertyValue(0, "\n\t\r\"\\\u001b\ud800" + new String(chars));
                            }
                            if (id == 2) return new CarPropertyValue(0, new int[70]);
                            if (id == 3) return new CarPropertyValue(0, new Object[]{new Object[]{new int[]{1, 2}}});
                            char[] chars = new char[512]; Arrays.fill(chars, 'x');
                            String[] leaves = new String[64]; Arrays.fill(leaves, new String(chars));
                            Object[] tree = new Object[64]; Arrays.fill(tree, leaves);
                            return new CarPropertyValue(0, tree);
                        } else if (id != 0x21601225 || area != 0) {
                            throw new AssertionError("wrong property");
                        }
                        if (mode.equals("denied")) throw new SecurityException("test denied");
                        if (mode.equals("hang")) Thread.sleep(30000);
                        return mode.equals("null") ? null : new CarPropertyValue();
                    }
                };
            }
        } }''',
}


class ProbeTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix='car-read-probe-test-')
        cls.addClassCleanup(cls.temp.cleanup)
        root = Path(cls.temp.name)
        cls.env = os.environ.copy()
        if cls.env.get('JAVA_HOME'):
            cls.env['PATH'] = str(Path(cls.env['JAVA_HOME']) / 'bin') + os.pathsep + cls.env.get('PATH', '')
        cls.classes = root / 'classes'; cls.classes.mkdir()
        sources = [str(Path(__file__).with_name('CarReadProbe.java'))]
        for name, code in FAKES.items():
            p = root / name; p.parent.mkdir(parents=True, exist_ok=True); p.write_text(code)
            sources.append(str(p))
        subprocess.run(['javac', '-source', '8', '-target', '8', '-d', str(cls.classes)] + sources,
                       env=cls.env, check=True)
        cls.plain = root / 'plain'; cls.plain.mkdir()
        shutil.copyfile(cls.classes / 'CarReadProbe.class', cls.plain / 'CarReadProbe.class')

    def run_probe(self, *options, args=(), plain=False):
        return subprocess.run(['java', *options, '-cp', str(self.plain if plain else self.classes),
                               'CarReadProbe', *args], env=self.env, capture_output=True, text=True, timeout=22)

    def test_help_and_arguments_without_android(self):
        self.assertEqual(self.run_probe(args=('--help',), plain=True).returncode, 0)
        self.assertEqual(self.run_probe(args=('unexpected',), plain=True).returncode, 2)

    def test_missing_android_and_non_shell_uid(self):
        self.assertIn('FAIL stage=uid', self.run_probe(plain=True).stderr)
        result = self.run_probe('-Duid=0')
        self.assertEqual(result.returncode, 1)
        self.assertIn('FAIL stage=uid', result.stderr)
        self.assertNotIn('BEGIN classes', result.stdout)

    def test_service_interface_and_read_failures(self):
        for mode, stage in [('absent', 'car_service'), ('no_property', 'property_service'),
                            ('descriptor', 'property_interface'), ('denied', 'read_1'), ('null', 'read_1')]:
            with self.subTest(mode=mode):
                result = self.run_probe('-Dmode=' + mode)
                self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
                self.assertIn('FAIL stage=' + stage, result.stderr)
                self.assertNotIn('PASS', result.stdout)

    def test_five_valid_reads_one_connection(self):
        result = self.run_probe()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.count('SAMPLE '), 5)
        self.assertEqual(result.stdout.count('OK connected_once'), 1)
        self.assertIn('raw=0.0', result.stdout)
        self.assertIn('PASS reads=5 connections=1', result.stdout)

    def test_unavailable_status_is_not_success(self):
        result = self.run_probe('-Dmode=status')
        self.assertEqual(result.returncode, 3, result.stderr)
        self.assertEqual(result.stdout.count('status=2'), 5)
        self.assertNotIn('AssertionError', result.stderr)
        self.assertNotIn('PASS', result.stdout)

    def test_stalled_read_exits_by_deadline(self):
        result = self.run_probe('-Dmode=hang')
        self.assertEqual(result.returncode, 124, result.stderr)
        self.assertIn('FAIL timeout stage=read_1', result.stderr)

    def run_all(self, mode):
        return self.run_probe('-Dmode=' + mode, args=('--all',))

    @staticmethod
    def items(result):
        return [line for line in result.stdout.splitlines() if line.startswith('ITEM ')]

    def test_all_read_access_and_declared_areas_only(self):
        result = self.run_all('all_mixed')
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual({line.split(' status=')[0] for line in self.items(result)}, {
            'ITEM id=0x00000001 area=0x0', 'ITEM id=0x00000002 area=0x1',
            'ITEM id=0x00000002 area=0x2'})
        self.assertIn('attempted=3 ok=3 unavailable=0 failed=0', result.stdout)
        self.assertIn('skipped_non_read=2 skipped_no_areas=1', result.stdout)
        self.assertEqual(result.stdout.count('OK connected_once'), 1)

    def test_all_single_failures_continue_and_status_skips_value(self):
        result = self.run_all('all_continue')
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn('ERROR id=0x00000001 area=0x0 error=java.lang.SecurityException', result.stdout)
        self.assertIn('ERROR id=0x00000004 area=0x0 error=java.lang.IllegalStateException', result.stdout)
        self.assertEqual(len(self.items(result)), 2)
        self.assertIn('id=0x00000002 area=0x0 status=2', result.stdout)
        self.assertIn('id=0x00000003 area=0x0 status=0', result.stdout)
        self.assertIn('attempted=4 ok=1 unavailable=1 failed=2', result.stdout)
        self.assertNotIn('AssertionError', result.stdout + result.stderr)

    def test_all_empty_and_excessive_configurations_fail(self):
        for mode, code in [('all_empty', 3), ('all_limit', 1)]:
            with self.subTest(mode=mode):
                result = self.run_all(mode)
                self.assertEqual(result.returncode, code, result.stdout + result.stderr)
                self.assertEqual(self.items(result), [])
                self.assertNotIn('PASS', result.stdout)
                if mode == 'all_limit':
                    self.assertIn('FAIL stage=property_list', result.stderr)
                    self.assertIn('CONFIGS count=4097', result.stdout)
                    self.assertIn('config limit 4096 exceeded', result.stderr)
                else:
                    self.assertIn('attempted=0 ok=0', result.stdout)

    def test_all_duplicate_property_areas_are_read_once(self):
        result = self.run_all('all_duplicate')
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(len(self.items(result)), 2)
        self.assertEqual({line.split(' status=')[0] for line in self.items(result)}, {
            'ITEM id=0x00000002 area=0x1', 'ITEM id=0x00000002 area=0x2'})
        self.assertIn('attempted=2 ok=2 unavailable=0 failed=0', result.stdout)

    def test_all_string_and_array_previews_are_bounded_and_escaped(self):
        result = self.run_all('all_preview')
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        items = self.items(result)
        self.assertEqual(len(items), 4)
        previews = [line.split(' raw_preview=', 1)[1].rsplit(' read_us=', 1)[0] for line in items]
        text, array, nested, large_tree = map(json.loads, previews)
        self.assertTrue(text.startswith('\n\t\r"\\\x1b\ud800'))
        self.assertTrue(text.endswith('[truncated]'))
        self.assertLessEqual(len(text), 523)
        for escaped in ('\\u000a', '\\u0009', '\\u000d', '\\u001b', '\\ud800', '\\"', '\\\\'):
            self.assertIn(escaped, previews[0])
        self.assertEqual(array, [0] * 64 + ['[truncated]'])
        self.assertIsInstance(nested[0][0], str)
        self.assertLess(len(previews[0]), 4096)
        self.assertLess(len(previews[1]), 1024)
        self.assertLess(len(previews[2]), 1024)
        self.assertIsInstance(large_tree, list)
        self.assertIn('[truncated]', previews[3])
        self.assertLessEqual(len(previews[3]), 4096)
        self.assertIn('attempted=4 ok=4 unavailable=0 failed=0', result.stdout)


if __name__ == '__main__':
    unittest.main(verbosity=2)
