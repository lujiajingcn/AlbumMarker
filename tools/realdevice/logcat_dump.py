# -*- coding: utf-8 -*-
"""Dump full logcat to file; grep app pid 837 window 13:50-14:06 and FATAL/crash."""
import subprocess

ADB = r"C:\Users\lujiajing\AppData\Local\Android\Sdk\platform-tools\adb.exe"
SERIAL = "b8e58d6c"
OUT = r"E:\study_lujiajing\WorkSpace_AlbumMarker\AlbumMarker\tools\realdevice\logcat_full.txt"

r = subprocess.run([ADB, "-s", SERIAL, "logcat", "-d", "-v", "time"],
                   capture_output=True, text=True, errors="replace")
open(OUT, "w", encoding="utf-8").write(r.stdout)
print("logcat lines:", len(r.stdout.splitlines()))

lines = r.stdout.splitlines()

print("== FATAL / crash / ANR ==")
for l in lines:
    if "FATAL" in l or "ANR in" in l or "CRASH" in l:
        print(l[:300])

print("== app pid 837 lines ==")
hits = [l for l in lines if " 837 " in l or " 837," in l]
print("count:", len(hits))
for l in hits[-40:]:
    print(l[:250])
