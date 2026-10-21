# -*- coding: utf-8 -*-
"""Dump exact filename bytes in Screenshots dir, then pull by exact name."""
import hashlib
import re
import subprocess

ADB = r"C:\Users\lujiajing\AppData\Local\Android\Sdk\platform-tools\adb.exe"
SERIAL = "b8e58d6c"
DIR = "/sdcard/DCIM/Screenshots"

# list one-per-line, raw bytes preserved through exec-out
r = subprocess.run([ADB, "-s", SERIAL, "exec-out", "ls", DIR],
                   capture_output=True)
names = r.stdout.split(b"\n")
print("entries:")
for n in names:
    if n:
        print(" ", n)

target = None
for n in names:
    if b"22-36-53" in n:
        target = n
        break
print("target raw:", target)
if target is None:
    raise SystemExit("target not found")

path = DIR + "/" + target.decode("utf-8", "surrogateescape")
print("pulling:", path)

r2 = subprocess.run([ADB, "-s", SERIAL, "exec-out", "cat", path],
                    capture_output=True)
print("stream bytes:", len(r2.stdout))
assert r2.stdout[:2] == b"\xff\xd8", "not JPEG"

OUT = r"E:\study_lujiajing\WorkSpace_AlbumMarker\AlbumMarker\tools\realdevice\embed_pulled.jpg"
with open(OUT, "wb") as f:
    f.write(r2.stdout)

data = r2.stdout
print("size:", len(data))
print("sha256:", hashlib.sha256(data).hexdigest())

pos = 2
xmp = None
segments = []
while pos + 4 <= len(data):
    if data[pos] != 0xFF:
        break
    marker = data[pos + 1]
    if marker in (0xD8, 0xD9):
        pos += 2
        continue
    if marker == 0xDA:
        break
    seg_len = int.from_bytes(data[pos + 2:pos + 4], "big")
    payload = data[pos + 4:pos + 2 + seg_len]
    segments.append((hex(marker), seg_len))
    if marker == 0xE1 and payload.startswith(b"http://ns.adobe.com/xap/1.0/\x00"):
        xmp = payload.decode("utf-8", "replace")
        break
    pos += 2 + seg_len

print("== leading segments (marker, len) ==")
print(segments[:8])
print("== XMP found:", xmp is not None)
if xmp:
    m = re.search(r"<dc:subject>.*?</dc:subject>", xmp, re.S)
    print("dc:subject block:", m.group(0) if m else "(none)")
    m2 = re.search(r"<dc:description>.*?</dc:description>", xmp, re.S)
    print("dc:description block:", (m2.group(0)[:300]) if m2 else "(none)")
    print("xmp payload length:", len(xmp))
    if len(xmp) < 3000:
        print("---- full XMP ----")
        print(xmp)
