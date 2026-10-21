# -*- coding: utf-8 -*-
"""List the Screenshots dir and stat the file via adb shell (script wrapper)."""
import subprocess

ADB = r"C:\Users\lujiajing\AppData\Local\Android\Sdk\platform-tools\adb.exe"
SERIAL = "b8e58d6c"

for cmd in [
    [ADB, "-s", SERIAL, "shell", "ls", "-la",
     "/storage/emulated/0/DCIM/Screenshots/"],
    [ADB, "-s", SERIAL, "shell", "ls", "-la",
     "/sdcard/DCIM/Screenshots/"],
    [ADB, "-s", SERIAL, "shell", "ls", "/storage/emulated/0/DCIM/"],
]:
    r = subprocess.run(cmd, capture_output=True, text=True)
    print("$", " ".join(cmd[3:]))
    out = r.stdout
    # only show lines mentioning our file or head of listing
    lines = out.splitlines()
    hit = [l for l in lines if "22-36-53" in l]
    print("match:", hit if hit else "(none)")
    print("total lines:", len(lines))
    print("---")
