#!/usr/bin/env python3
"""把 d8 产出的 dex 写进 APK。

用法: adddex.py <apk> <dexdir>

注意：构建环境若内存紧张，不要用 zipfile 一次性读入整个 APK，
这里逐个 entry 复制，保持低内存占用。
"""
import sys, os, zipfile, shutil

apk, dexdir = sys.argv[1], sys.argv[2]
tmp = apk + ".tmp"
with zipfile.ZipFile(apk, 'r') as zin, zipfile.ZipFile(tmp, 'w', zipfile.ZIP_DEFLATED) as zout:
    for it in zin.infolist():
        zout.writestr(it, zin.read(it.filename))
    for name in sorted(os.listdir(dexdir)):
        if name.endswith('.dex'):
            zout.write(os.path.join(dexdir, name), name)
shutil.move(tmp, apk)
print("   dex 已写入:", ", ".join(sorted(n for n in os.listdir(dexdir) if n.endswith('.dex'))))
