#!/usr/bin/env python
"""把文件/目录追加进 zip（APK）。用法：
   python addzip.py <zip> file1 [file2 ...]
   python addzip.py <zip> --dir <dir> [--prefix assets/]
重复条目会被替换。Git Bash 自带的 Windows 没有 zip 命令，故用 Python。
"""
import os
import sys
import zipfile

def main():
    args = sys.argv[1:]
    if len(args) < 2:
        print("usage: addzip.py <zip> <file...> | --dir <dir> [--prefix p]")
        return 2
    zpath = args[0]
    entries = []
    i = 1
    prefix = ""
    while i < len(args):
        a = args[i]
        if a == "--dir":
            base = args[i + 1]
            i += 2
            for root, _, files in os.walk(base):
                for f in files:
                    full = os.path.join(root, f)
                    rel = os.path.relpath(full, base).replace("\\", "/")
                    entries.append((full, prefix + rel))
            continue
        if a == "--prefix":
            prefix = args[i + 1]
            i += 2
            continue
        entries.append((a, prefix + os.path.basename(a)))
        i += 1

    # 读出旧内容，重写（避免 duplicate entry）
    old = {}
    if os.path.exists(zpath):
        with zipfile.ZipFile(zpath) as z:
            for info in z.infolist():
                old[info.filename] = z.read(info.filename)
    for full, arc in entries:
        with open(full, "rb") as f:
            old[arc] = f.read()
    tmp = zpath + ".tmp"
    with zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED) as z:
        for name, data in old.items():
            zi = zipfile.ZipInfo(name, (2026, 1, 1, 0, 0, 0))
            zi.compress_type = zipfile.ZIP_DEFLATED
            zi.external_attr = 0o644 << 16
            z.writestr(zi, data)
    os.replace(tmp, zpath)
    print("added %d entries -> %s" % (len(entries), zpath))
    return 0

if __name__ == "__main__":
    sys.exit(main())
