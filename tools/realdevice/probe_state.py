# -*- coding: utf-8 -*-
"""Probe: hidden .pending files, content read capability, current file state."""
import subprocess

ADB = r"C:\Users\lujiajing\AppData\Local\Android\Sdk\platform-tools\adb.exe"
SERIAL = "b8e58d6c"

print("== ls -la with hidden files ==")
r = subprocess.run([ADB, "-s", SERIAL, "exec-out", "ls", "-la",
                    "/sdcard/DCIM/Screenshots/"], capture_output=True)
print(r.stdout.decode("utf-8", "replace"))

print("== content read (first 300 bytes) ==")
r = subprocess.run([ADB, "-s", SERIAL, "shell", "content", "read",
                    "--uri", "content://media/external/images/media/4935"],
                   capture_output=True)
out = r.stdout
print("bytes:", len(out), "head:", out[:60])
print("stderr:", r.stderr.decode("utf-8", "replace")[:300])

print("== content query again ==")
r = subprocess.run([ADB, "-s", SERIAL, "shell", "content", "query",
                    "--uri", "content://media/external/images/media/4935",
                    "--projection", "_data:_size:date_modified:is_pending:is_trashed"],
                   capture_output=True, text=True)
print(r.stdout)
