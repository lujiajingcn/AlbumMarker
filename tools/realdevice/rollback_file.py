# -*- coding: utf-8 -*-
"""Rollback: strip the XMP APP1 segment from embed_pulled.jpg to restore the
pre-embed bytes, verify SHA-256 == bd5bd654... (the old hash still in the DB),
and stage the file for push-back to the device."""
import hashlib
import struct

SRC = r"E:\study_lujiajing\WorkSpace_AlbumMarker\AlbumMarker\tools\realdevice\embed_pulled.jpg"
OUT = r"E:\study_lujiajing\WorkSpace_AlbumMarker\AlbumMarker\tools\realdevice\embed_rolledback.jpg"
EXPECTED_OLD_HASH_PREFIX = "bd5bd654b4796cc2"

data = open(SRC, "rb").read()
print("post-embed size:", len(data), "sha256:", hashlib.sha256(data).hexdigest()[:16])

assert data[:2] == b"\xff\xd8"
pos = 2
xmp_start = xmp_end = None
while pos + 4 <= len(data):
    if data[pos] != 0xFF:
        break
    marker = data[pos + 1]
    if marker in (0xD8, 0xD9):
        pos += 2
        continue
    if marker == 0xDA:
        break
    seg_len = struct.unpack(">H", data[pos + 2:pos + 4])[0]
    payload_start = pos + 4
    payload_end = pos + 2 + seg_len
    if marker == 0xE1 and data[payload_start:payload_start + 29] == b"http://ns.adobe.com/xap/1.0/\x00":
        xmp_start, xmp_end = pos, payload_end
        break
    pos = payload_end

assert xmp_start is not None, "XMP APP1 not found"
print("XMP segment at", xmp_start, "-", xmp_end, "len:", xmp_end - xmp_start)

out = data[:xmp_start] + data[xmp_end:]
open(OUT, "wb").write(out)
h = hashlib.sha256(out).hexdigest()
print("rolled-back size:", len(out))
print("rolled-back sha256:", h)
print("matches DB old hash prefix:", h.startswith(EXPECTED_OLD_HASH_PREFIX))
assert len(out) == 567773, f"expected 567773 bytes, got {len(out)}"
assert h.startswith(EXPECTED_OLD_HASH_PREFIX), "hash mismatch with DB!"

# also verify no XMP left and structure intact
assert b"http://ns.adobe.com/xap/1.0/" not in out
print("OK: rolled-back file staged at", OUT)
