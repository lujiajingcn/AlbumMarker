# -*- coding: utf-8 -*-
"""探针：把同一批中文标签以不同元数据机制写入 JPEG，供 Windows 侧验证哪种能被识别。

机制：
  xmp    : 插入 XMP APP1（dc:subject / dc:description，UTF-8）
  iptc   : 插入 IPTC APP13（Photoshop IRB 0x0404 -> dataset 2:25 Keywords / 2:120 Caption）
  xp     : EXIF IFD 手术插入 XPKeywords(0x9C9E, UTF-16LE ";" 分隔)
  all    : xmp + iptc + xp
  base   : 原图（对照）

用法：python tools/diag/meta_probe.py <工作目录>
输出：<工作目录>/probe_{base,xmp,iptc,xp,all}.jpg
"""
import os
import struct
import subprocess
import sys

FFMPEG = r"D:\ffmpeg\ffmpeg.exe"
TAGS = ["中文标签", "旅行", "2026"]


# ---------- JPEG 基础 ----------

def split_jpeg(data):
    """返回 (segments, sos_index)。segment = (marker_byte1, marker_byte2, payload_bytes)。"""
    assert data[0:2] == b"\xff\xd8", "not jpeg"
    i = 2
    segs = []
    while i < len(data):
        if data[i] != 0xFF:
            raise ValueError("expected marker at %d" % i)
        marker = data[i + 1]
        if marker == 0xD9:  # EOI
            segs.append((0xFF, 0xD9, b""))
            break
        if marker in (0xD8, 0x01) or 0xD0 <= marker <= 0xD7:
            segs.append((data[i], data[i + 1], b""))
            i += 2
            continue
        length = struct.unpack(">H", data[i + 2:i + 4])[0]
        payload = data[i + 4:i + 2 + length]
        segs.append((data[i], data[i + 1], payload))
        i += 2 + length
        if marker == 0xDA:  # SOS：后面是熵编码数据，直到 EOI
            rest = data[i:]
            segs.append((0x00, 0x00, rest))  # 伪段：熵数据+EOI
            break
    return segs


def join_jpeg(segs):
    out = bytearray(b"\xff\xd8")
    for m1, m2, payload in segs:
        if m1 == 0xFF and m2 == 0xD9:
            out += b"\xff\xd9"
            continue
        if m1 == 0x00 and m2 == 0x00:  # 熵数据原样
            out += payload
            continue
        if (m1, m2) in ((0xFF, 0xD8),) or m2 in (0x01,) or 0xD0 <= m2 <= 0xD7:
            out += bytes([m1, m2])
            continue
        out += bytes([m1, m2]) + struct.pack(">H", len(payload) + 2) + payload
    return bytes(out)


def find_app1_exif(segs):
    for idx, (m1, m2, payload) in enumerate(segs):
        if m1 == 0xFF and m2 == 0xE1 and payload.startswith(b"Exif\x00\x00"):
            return idx
    return None


def insert_after_soi(segs, new_seg):
    """new_seg = (0xFF, 0xE1, payload)。插到 EXIF APP1 之后（没有则 SOI 之后）。"""
    out = [segs[0]]
    exif_idx = find_app1_exif(segs)
    if exif_idx is not None:
        out += segs[1:exif_idx + 1]
        out.append(new_seg)
        out += segs[exif_idx + 1:]
    else:
        out.append(new_seg)
        out += segs[1:]
    return out


# ---------- XMP ----------

def build_xmp_app1(tags):
    items = "".join("<rdf:li>%s</rdf:li>" % t for t in tags)
    desc = "、".join(tags)
    xmp = (
        '<?xpacket begin="\ufeff" id="W5M0MpCehiHzreSzNTczkc9d"?>'
        '<x:xmpmeta xmlns:x="adobe:ns:meta/">'
        '<rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">'
        '<rdf:Description rdf:about=""'
        ' xmlns:dc="http://purl.org/dc/elements/1.1/">'
        "<dc:subject><rdf:Bag>%s</rdf:Bag></dc:subject>"
        "<dc:description><rdf:Alt><rdf:li xml:lang=\"x-default\">%s</rdf:li></rdf:Alt></dc:description>"
        "</rdf:Description></rdf:RDF></x:xmpmeta>"
        '<?xpacket end="w"?>'
    ) % (items, desc)
    return (0xFF, 0xE1, b"http://ns.adobe.com/xap/1.0/\x00" + xmp.encode("utf-8"))


# ---------- IPTC ----------

def build_iptc_app13(keywords, caption):
    def dataset(tag, value):
        payload = value.encode("utf-8")
        # IPTC dataset: 0x1C, record(1), dataset(1), len(2), data
        return b"\x1c\x02" + bytes([tag]) + struct.pack(">H", len(payload)) + payload

    iptc = b""
    iptc += b"\x1c\x02\x00" + struct.pack(">H", 2) + b"\x00\x00"  # 2:00 record version
    for kw in keywords:
        iptc += dataset(0x19, kw)  # 2:25 Keywords
    iptc += dataset(0x78, caption)  # 2:120 Caption/Description
    resource = b"Photoshop 3.0\x00"
    resource += b"8BIM" + struct.pack(">H", 0x0404) + b"\x00\x00"
    name = b"\x00\x00"
    body = iptc
    # resource: 4 signature + 2 id + 2 name_len(=0 -> padded to even) + 4 data_size + data(+pad)
    resource += name  # pascal string empty + pad byte
    resource += struct.pack(">I", len(body)) + body
    if len(body) % 2 == 1:
        resource += b"\x00"
    return (0xFF, 0xED, resource)


# ---------- XPKeywords（EXIF IFD 手术） ----------

TYPE_SIZE = {1: 1, 2: 1, 3: 2, 4: 4, 5: 8, 6: 1, 7: 1, 8: 2, 9: 4, 10: 8, 11: 4, 12: 8}

EXIF_IFD_POINTER = 0x8769
GPS_IFD_POINTER = 0x8825
INTEROP_IFD_POINTER = 0xA005


def set_xp_keywords(data, keywords):
    """写入 XPKeywords(0x9C9E, UTF-16LE ";" 分隔)。无 EXIF 段时从零构建最小 EXIF 块。"""
    segs = split_jpeg(data)
    idx = find_app1_exif(segs)
    raw = ";".join(keywords).encode("utf-16-le") + b"\x00\x00"

    if idx is None:
        # 从零构建最小 EXIF：II / 0x2A / IFD0@8 -> 0x8769 -> EXIF IFD@26 -> XPKeywords@48
        tiff = bytearray()
        tiff += b"II" + struct.pack("<H", 0x2A) + struct.pack("<I", 8)
        ifd0 = struct.pack("<H", 1) + struct.pack("<HHI", 0x8769, 4, 1) + struct.pack("<I", 26) + struct.pack("<I", 0)
        assert len(tiff) + len(ifd0) == 26, len(tiff) + len(ifd0)
        tiff += ifd0
        exif_ifd = struct.pack("<H", 1) + struct.pack("<HHI", 0x9C9E, 1, len(raw)) + struct.pack("<I", 44) + struct.pack("<I", 0)
        assert len(tiff) + len(exif_ifd) == 44, len(tiff) + len(exif_ifd)
        tiff += exif_ifd
        tiff += raw
        new_seg = (0xFF, 0xE1, b"Exif\x00\x00" + bytes(tiff))
        # 插在 APP0（JFIF）之后，没有 APP0 则 SOI 之后
        out = [segs[0]]
        app0 = None
        for i, (m1, m2, _) in enumerate(segs):
            if m1 == 0xFF and m2 == 0xE0:
                app0 = i
                break
        if app0 is not None:
            out += segs[1:app0 + 1] + [new_seg] + segs[app0 + 1:]
        else:
            out.append(new_seg)
            out += segs[1:]
        return join_jpeg(out)

    payload = bytearray(segs[idx][2])
    tiff = payload[6:]  # 跳过 "Exif\0\0"
    tiff = bytearray(tiff)

    def u16(off):
        return struct.unpack_from("<H" if tiff[0:2] == b"II" else ">H", tiff, off)[0]

    def u32(off):
        return struct.unpack_from("<I" if tiff[0:2] == b"II" else ">I", tiff, off)[0]

    little = tiff[0:2] == b"II"

    def pack_u16(v):
        return struct.pack("<H" if little else ">H", v)

    def pack_u32(v):
        return struct.pack("<I" if little else ">I", v)

    ifd0_off = u32(4)

    def read_ifd(off):
        n = u16(off)
        entries = []
        for i in range(n):
            e = off + 2 + i * 12
            tag = u16(e)
            typ = u16(e + 2)
            cnt = u32(e + 4)
            val = tiff[e + 8:e + 12]
            entries.append({"tag": tag, "type": typ, "count": cnt, "val": bytes(val), "off": e})
        nxt = u32(off + 2 + n * 12)
        return entries, nxt, off + 2 + n * 12 + 4  # entries, next_ifd, ifd_end

    def ifd_list():
        """所有 IFD 偏移（IFD0 -> EXIF/GPS/Interop -> IFD1）。"""
        offsets = [ifd0_off]
        e0, nxt0, _ = read_ifd(ifd0_off)
        for e in e0:
            if e["tag"] in (EXIF_IFD_POINTER, GPS_IFD_POINTER, INTEROP_IFD_POINTER):
                offsets.append(struct.unpack("<I" if little else ">I", e["val"])[0])
        seen = 0
        cur = nxt0
        while cur != 0 and seen < 8:
            offsets.append(cur)
            _, cur, _ = read_ifd(cur)
            seen += 1
        return offsets

    def fix_offsets(insert_at, delta):
        """把 IFD 链里所有 >= insert_at 的偏移值 += delta。"""
        for off in ifd_list():
            entries, nxt, _ = read_ifd(off)
            for e in entries:
                size = TYPE_SIZE.get(e["type"], 1) * e["count"]
                if size > 4:
                    old = struct.unpack("<I" if little else ">I", e["val"])[0]
                    if old >= insert_at:
                        tiff[e["off"] + 8:e["off"] + 12] = pack_u32(old + delta)
                if e["tag"] in (EXIF_IFD_POINTER, GPS_IFD_POINTER, INTEROP_IFD_POINTER):
                    old = struct.unpack("<I" if little else ">I", e["val"])[0]
                    if old >= insert_at:
                        tiff[e["off"] + 8:e["off"] + 12] = pack_u32(old + delta)
            if nxt >= insert_at:
                tiff[off + 2 + len(entries) * 12: off + 2 + len(entries) * 12 + 4] = pack_u32(nxt + delta)

    # 找 EXIF IFD
    e0, _, _ = read_ifd(ifd0_off)
    exif_off = None
    for e in e0:
        if e["tag"] == EXIF_IFD_POINTER:
            exif_off = struct.unpack("<I" if little else ">I", e["val"])[0]
    if exif_off is None:
        raise ValueError("no EXIF IFD (camera jpeg expected)")

    entries, _, ifd_end = read_ifd(exif_off)
    value = ";".join(keywords)
    raw = value.encode("utf-16-le") + b"\x00\x00"
    tag = 0x9C9E
    typ = 1  # BYTE
    cnt = len(raw)

    # 已存在同名 tag：原地更新（外部数据区够大就覆盖，否则追加新区）
    existing = [e for e in entries if e["tag"] == tag]
    if existing:
        e = existing[0]
        old_off = struct.unpack("<I" if little else ">I", e["val"])[0]
        old_size = TYPE_SIZE.get(e["type"], 1) * e["count"]
        if old_size >= cnt and old_off + cnt <= len(tiff):
            tiff[old_off:old_off + cnt] = raw
        else:
            new_off = len(tiff)
            tiff += raw
            tiff[e["off"] + 8:e["off"] + 12] = pack_u32(new_off)
            tiff[e["off"] + 4:e["off"] + 8] = pack_u32(cnt)
    else:
        insert_at = ifd_end - 4  # 新条目插在 entries 末尾（next_ifd 字段之前）
        # 先给外部数据腾位置：追加到 TIFF 末尾
        new_off = len(tiff)
        tiff += raw
        fix_offsets(insert_at, 12)
        # 重建 IFD：插入排序后的新条目
        new_entry = pack_u16(tag) + pack_u16(typ) + pack_u32(cnt) + pack_u32(new_off + 12)
        all_entries = sorted(entries + [{"tag": tag, "type": typ, "count": cnt, "val": new_entry[8:12], "off": None}],
                             key=lambda x: x["tag"])
        rebuilt = pack_u16(len(all_entries))
        for e in all_entries:
            if e["off"] is None:
                rebuilt += new_entry
            else:
                rebuilt += tiff[e["off"]:e["off"] + 12]
        # next_ifd 原值（fix_offsets 已改过 tiff 里的，但这里按新位置重写）
        old_next = struct.unpack("<I" if little else ">I",
                                 tiff[exif_off + 2 + len(entries) * 12: exif_off + 2 + len(entries) * 12 + 4])[0]
        rebuilt += pack_u32(old_next)
        tiff[exif_off:ifd_end] = rebuilt

    new_payload = bytes(payload[:6]) + bytes(tiff)
    segs[idx] = (0xFF, 0xE1, new_payload)
    return join_jpeg(segs)


# ---------- 主流程 ----------

def main():
    workdir = sys.argv[1]
    os.makedirs(workdir, exist_ok=True)
    base = os.path.join(workdir, "probe_base.jpg")
    if len(sys.argv) > 2:
        base = sys.argv[2]  # 直接指定底图（如真机截图，自带 EXIF）
    else:
        subprocess.run(
            [FFMPEG, "-y", "-f", "lavfi", "-i", "testsrc=duration=1:size=320x240:rate=1",
             "-frames:v", "1", base],
            capture_output=True, check=True)
    with open(base, "rb") as f:
        data = f.read()
    print("base jpeg: %s (%d bytes)" % (base, len(data)))

    def save(name, segs_fn):
        p = os.path.join(workdir, name)
        with open(p, "wb") as f:
            f.write(join_jpeg(segs_fn(split_jpeg(data))))
        print("wrote %s (%d bytes)" % (p, os.path.getsize(p)))

    save("probe_xmp.jpg", lambda segs: insert_after_soi(segs, build_xmp_app1(TAGS)))
    save("probe_iptc.jpg", lambda segs: insert_after_soi(segs, build_iptc_app13(TAGS, "、".join(TAGS))))
    save("probe_xp.jpg", lambda segs: split_jpeg(set_xp_keywords(data, TAGS)))

    # all：先 xp 手术，再插入 xmp + iptc
    xp_data = set_xp_keywords(data, TAGS)
    xp_segs = split_jpeg(xp_data)
    xp_segs = insert_after_soi(xp_segs, build_xmp_app1(TAGS))
    xp_segs = insert_after_soi(xp_segs, build_iptc_app13(TAGS, "、".join(TAGS)))
    with open(os.path.join(workdir, "probe_all.jpg"), "wb") as f:
        f.write(join_jpeg(xp_segs))
    print("wrote probe_all.jpg (%d bytes)" % len(join_jpeg(xp_segs)))


if __name__ == "__main__":
    main()
