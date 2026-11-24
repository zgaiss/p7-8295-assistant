#!/usr/bin/env python3
"""Build only: no adb, no device connection, no dependency on the larger agent."""
import argparse
import os
from pathlib import Path
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[2]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sdk', type=Path, default=Path(os.environ.get(
        'ANDROID_SDK_ROOT', os.environ.get('ANDROID_HOME', str(Path.home() / 'Library/Android/sdk')))))
    args = parser.parse_args()
    env = os.environ.copy()
    if env.get('JAVA_HOME'):
        env['PATH'] = str(Path(env['JAVA_HOME']) / 'bin') + os.pathsep + env.get('PATH', '')
    try:
        sdk = args.sdk.expanduser().resolve()
        builds = [p for p in (sdk / 'build-tools').glob('*')
                  if all(n.isdigit() for n in p.name.split('.')) and (p / 'd8').is_file()]
        platforms = [p for p in (sdk / 'platforms').glob('android-*')
                     if all(n.isdigit() for n in p.name[8:].split('.')) and (p / 'android.jar').is_file()]
        if not builds or not platforms:
            raise ValueError('SDK needs stable build-tools/d8 and platforms/android-*/android.jar')
        d8 = max(builds, key=lambda p: tuple(map(int, p.name.split('.')))) / 'd8'
        android = max(platforms, key=lambda p: tuple(map(int, p.name[8:].split('.')))) / 'android.jar'
        out = ROOT / 'dist/car-read-probe'
        if out.resolve() != out:
            raise ValueError('Unexpected output directory symlink')
        out.mkdir(parents=True, exist_ok=True)
        target = (out / 'car-read-probe.jar').resolve()
        if target != out / 'car-read-probe.jar':
            raise ValueError('Unexpected jar destination')
        with tempfile.TemporaryDirectory(dir=str(out), prefix='build-') as tmp:
            temp = Path(tmp)
            classes, dex = temp / 'classes', temp / 'dex'
            classes.mkdir(); dex.mkdir()
            subprocess.run(['javac', '-encoding', 'UTF-8', '-source', '8', '-target', '8',
                            '-d', str(classes), str(Path(__file__).with_name('CarReadProbe.java'))],
                           env=env, check=True)
            subprocess.run([str(d8), '--min-api', '28', '--lib', str(android), '--output', str(dex)]
                           + [str(p) for p in classes.glob('*.class')], env=env, check=True)
            if not (dex / 'classes.dex').is_file():
                raise ValueError('d8 produced no classes.dex')
            jar = temp / 'probe.jar'
            with zipfile.ZipFile(jar, 'w', zipfile.ZIP_DEFLATED) as archive:
                for p in sorted(dex.glob('*.dex')):
                    archive.write(p, p.name)
            os.replace(str(jar), str(target))
        print('Built ' + str(target))
    except (OSError, ValueError, subprocess.CalledProcessError) as exc:
        parser.exit(1, 'Build failed: ' + str(exc) + '\n')


if __name__ == '__main__':
    main()
