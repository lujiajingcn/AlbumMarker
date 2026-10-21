# -*- coding: utf-8 -*-
"""Ground truth: re-open viewer, screenshot, export DB, inspect tag hash mapping."""
import subprocess

ADB = r"C:\Users\lujiajing\AppData\Local\Android\Sdk\platform-tools\adb.exe"
SERIAL = "b8e58d6c"
BASE = r"E:\study_lujiajing\WorkSpace_AlbumMarker\AlbumMarker\tools\realdevice"

# 1) re-open the same media in viewer
subprocess.run([ADB, "-s", SERIAL, "shell", "am", "start", "-W",
                "-a", "android.intent.action.VIEW",
                "-d", "content://media/external/images/media/4935",
                "-t", "image/jpeg",
                "-n", "com.albummarker/.MainActivity"],
               capture_output=True, text=True)

import time
time.sleep(4)

# 2) screenshot
r = subprocess.run([ADB, "-s", SERIAL, "exec-out", "screencap", "-p"],
                   capture_output=True)
open(BASE + r"\embed_05_reopen.png", "wb").write(r.stdout)
print("screenshot bytes:", len(r.stdout))

# 3) export DB via run-as + exec-out cat
r = subprocess.run([ADB, "-s", SERIAL, "exec-out", "run-as",
                    "com.albummarker", "cat",
                    "databases/album_marker.db"],
                   capture_output=True)
print("db bytes:", len(r.stdout))
open(BASE + r"\album_marker_after_embed.db", "wb").write(r.stdout)

# 4) dump logcat around the embed (all tags, app pid filter not needed; grep by pkg)
r = subprocess.run([ADB, "-s", SERIAL, "logcat", "-d"], capture_output=True, text=True, errors="replace")
lines = [l for l in r.stdout.splitlines() if "albummarker" in l.lower()]
print("== logcat lines mentioning albummarker (last 30) ==")
for l in lines[-30:]:
    print(l)
