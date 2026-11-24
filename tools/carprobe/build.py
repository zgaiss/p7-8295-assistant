#!/usr/bin/env python3
"""Build the standalone shell-UID agent; never install or contact a device."""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[2]


def sdk_tools(sdk):
    def version_key(path):
        try:
            return tuple(int(n) for n in path.name.split('.'))
        except ValueError:
            return ()
    builds = sorted((sdk / 'build-tools').glob('*'), key=version_key, reverse=True)
    platforms = sorted((sdk / 'platforms').glob('android-*'),
                       key=lambda p: int(p.name[8:]) if p.name[8:].isdigit() else -1,
                       reverse=True)
    d8 = next((p / 'd8' for p in builds if (p / 'd8').is_file()), None)
    jar = next((p / 'android.jar' for p in platforms if (p / 'android.jar').is_file()), None)
    if d8 is None or jar is None:
        raise ValueError('Android SDK needs build-tools/d8 and platforms/android-*/android.jar')
    return d8, jar


def build(sdk):
    env = os.environ.copy()
    if env.get('JAVA_HOME'):
        env['PATH'] = str(Path(env['JAVA_HOME']) / 'bin') + os.pathsep + env.get('PATH', '')
    javac = shutil.which('javac', path=env.get('PATH'))
    if javac is None:
        raise ValueError('javac missing: configure JAVA_HOME or PATH')
    d8, android_jar = sdk_tools(sdk)
    sources = sorted((ROOT / 'tools/carprobe/src').rglob('*.java'))
    if not sources:
        raise ValueError('No Java agent sources')
    out = (ROOT / 'dist/carprobe').resolve()
    out.mkdir(parents=True, exist_ok=True)
    # TemporaryDirectory owns only its newly created directory, never a user path.
    with tempfile.TemporaryDirectory(prefix='build-', dir=str(out)) as tmp:
        temp = Path(tmp).resolve()
        classes, dex = temp / 'classes', temp / 'dex'
        classes.mkdir()
        dex.mkdir()
        subprocess.run([javac, '-encoding', 'UTF-8', '-source', '8', '-target', '8',
                        '-d', str(classes)] + [str(p) for p in sources], env=env, check=True)
        subprocess.run([str(d8), '--min-api', '28', '--lib', str(android_jar),
                        '--output', str(dex)] + [str(p) for p in sorted(classes.rglob('*.class'))],
                       env=env, check=True)
        if not (dex / 'classes.dex').is_file():
            raise ValueError('d8 produced no classes.dex')
        archive = temp / 'carprobe.jar'
        with zipfile.ZipFile(archive, 'w', compression=zipfile.ZIP_DEFLATED) as jar:
            for p in sorted(dex.glob('*.dex')):
                jar.write(p, p.name)
        target = (out / 'carprobe.jar').resolve()
        if target.parent != out:
            raise ValueError('Unexpected output path')
        os.replace(str(archive), str(target))
    print('Built ' + str(target))
    return target


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sdk', type=Path,
                        default=Path(os.environ.get('ANDROID_SDK_ROOT',
                                                    os.environ.get('ANDROID_HOME',
                                                                   str(Path.home() / 'Library/Android/sdk')))),
                        help='Android SDK directory (default ANDROID_SDK_ROOT/ANDROID_HOME or macOS SDK)')
    args = parser.parse_args()
    try:
        build(args.sdk.expanduser().resolve())
    except (ValueError, OSError, subprocess.CalledProcessError) as exc:
        parser.exit(1, 'carprobe build failed: ' + str(exc) + '\n')


if __name__ == '__main__':
    main()
