# -*- coding: utf-8 -*-
"""Dump JPEG segment structure of the pulled (post-embed) file."""
import struct

PATH = r"E:\study_lujiajing\WorkSpace_AlbumMarker\AlbumMarker\tools\realdevice\embed_pulled.jpg"
data = open(PATH, "rb").read()
print("size:", len(data))
print("SOI:", data[:2].hex())

pos = 2
while pos + 4 <= len(data):
    if data[pos] != 0xFF:
        print(f"  !! non-marker byte {data[pos]:02x} at {pos}, stop")
        break
    marker = data[pos + 1]
    if marker in (0xD8,):  # SOI again
        pos += 2
        continue
    if marker == 0xD9:
        print(f"  EOI at {pos}")
        break
    if marker == 0xDA:
        print(f"  SOS at {pos} (image data follows)")
        break
    seg_len = struct.unpack(">H", data[pos + 2:pos + 4])[0]
    payload = data[pos + 4:pos + 2 + seg_len]
    desc = {
        0xE0: "APP0/JFIF",
        0xE1: "APP1",
        0xE2: "APP2/ICC",
        0xED: "APP13/IPTC",
        0xEE: "APP14/Adobe",
        0xDB: "DQT",
        0xC0: "SOF0",
        0xC2: "SOF2",
        0xC4: "DHT",
    }.get(marker, f"marker 0x{marker:02x}")
    head = payload[:20]
    printable = "".join(chr(b) if 32 <= b < 127 else "." for b in head)
    print(f"  {desc:14s} len={seg_len:6d} at {pos:7d}  payload[:20]={printable!r}")
    pos += 2 + seg_len
