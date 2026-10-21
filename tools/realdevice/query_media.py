# -*- coding: utf-8 -*-
"""Query MediaStore for the current path/size of media id 4935 on the device."""
import subprocess

ADB = r"C:\Users\lujiajing\AppData\Local\Android\Sdk\platform-tools\adb.exe"
SERIAL = "b8e58d6c"

cmd = (ADB, "-s", SERIAL, "shell",
       "content query --uri content://media/external/images/media/4935 "
       "--projection _data:_size:date_modified:mime_type:title")
r = subprocess.run(cmd, capture_output=True, text=True)
print(r.stdout)
print(r.stderr)

# also search Screenshots dir listing via content provider (no raw fs path)
cmd2 = (ADB, "-s", SERIAL, "shell",
        "content query --uri content://media/external/images/media "
        "--projection _id:_data:_size --where \"_data LIKE '%22-36-53%'\"")
r2 = subprocess.run(cmd2, capture_output=True, text=True)
print("== by path pattern ==")
print(r2.stdout)
print(r2.stderr)
