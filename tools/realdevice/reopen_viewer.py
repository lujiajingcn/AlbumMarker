# -*- coding: utf-8 -*-
"""Open viewer for 4935 now and screenshot; check crash / hash / chips."""
import subprocess
import time

ADB = r"C:\Users\lujiajing\AppData\Local\Android\Sdk\platform-tools\adb.exe"
SERIAL = "b8e58d6c"
BASE = r"E:\study_lujiajing\WorkSpace_AlbumMarker\AlbumMarker\tools\realdevice"

subprocess.run([ADB, "-s", SERIAL, "shell", "am", "start", "-W",
                "-a", "android.intent.action.VIEW",
                "-d", "content://media/external/images/media/4935",
                "-t", "image/jpeg",
                "-n", "com.albummarker/.MainActivity"],
               capture_output=True, text=True)
time.sleep(5)

r = subprocess.run([ADB, "-s", SERIAL, "shell", "dumpsys", "activity", "activities"],
                   capture_output=True, text=True)
for l in r.stdout.splitlines():
    if "ResumedActivity" in l:
        print("FOREGROUND:", l.strip()[:150])

r = subprocess.run([ADB, "-s", SERIAL, "exec-out", "screencap", "-p"],
                   capture_output=True)
open(BASE + r"\embed_06_reopen2.png", "wb").write(r.stdout)
print("screenshot bytes:", len(r.stdout))

# fresh logcat for crashes
r = subprocess.run([ADB, "-s", SERIAL, "logcat", "-d"], capture_output=True, text=True, errors="replace")
for l in r.stdout.splitlines():
    if "FATAL" in l or "AndroidRuntime" in l and "Exception" in l:
        print("CRASH?:", l[:200])
