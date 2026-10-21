# -*- coding: utf-8 -*-
"""把 AlbumMarker 的标签数据库导出为 CSV 清单（Excel 可直接打开）。

背景：标签只存在手机 app 私有 SQLite（album_marker.db，SHA-256 主键），
照片文件本身不带标签。直接 USB/MTP 拷照片到电脑会丢失标签，本脚本把
库导出成 CSV，与照片按文件名/哈希对应。

用法：
  # 1) 已通过 adb 导出 db 文件时：
  python tools/export_tags.py path/to/album_marker.db [-o out.csv]

  # 2) 直接从连接的真机拉取再导出（仅 debug 包可用，run-as 限制）：
  python tools/export_tags.py --device <serial>

adb 手动导出等价命令（Git Bash 需 export MSYS_NO_PATHCONV=1）：
  adb -s <serial> exec-out run-as com.albummarker cat databases/album_marker.db > album_marker.db
"""
import argparse
import csv
import os
import sqlite3
import subprocess
import sys
import tempfile

ADB = r"C:\Users\lujiajing\AppData\Local\Android\Sdk\platform-tools\adb.exe"
PKG = "com.albummarker"

SOURCE_CN = {
    "manual": "手动",
    "auto": "自动",
    "heuristic": "启发式",
}


def pull_from_device(serial, dest):
    cmd = [ADB]
    if serial:
        cmd += ["-s", serial]
    cmd += ["exec-out", "run-as", PKG, "cat", "databases/album_marker.db"]
    with open(dest, "wb") as f:
        r = subprocess.run(cmd, stdout=f, stderr=subprocess.PIPE)
    if r.returncode != 0 or os.path.getsize(dest) == 0:
        sys.exit("从设备导出数据库失败（仅 debuggable 包可用 run-as）：%s"
                 % (r.stderr or b"").decode("utf-8", "replace"))


def export(db_path, out_csv):
    db = sqlite3.connect(db_path)
    cur = db.cursor()
    rows = cur.execute(
        """
        SELECT m.display_name, m.hash, m.size, m.mime,
               group_concat(t.tag, '; ')  AS tags,
               group_concat(t.source, ';') AS sources,
               m.updated_at
        FROM media_tags m
        JOIN tags t ON t.hash = m.hash
        GROUP BY m.hash
        ORDER BY m.display_name
        """
    ).fetchall()
    # utf-8-sig：带 BOM，Excel 双击打开中文不乱码
    with open(out_csv, "w", newline="", encoding="utf-8-sig") as f:
        w = csv.writer(f)
        w.writerow(["文件名", "SHA-256", "大小(字节)", "类型", "标签", "来源", "更新时间"])
        for name, h, size, mime, tags, sources, updated in rows:
            src_cn = ";".join(SOURCE_CN.get(s, s) for s in (sources or "").split(";"))
            w.writerow([name, h, size, mime, tags, src_cn, updated])
    db.close()
    print("导出 %d 个文件的标签 -> %s" % (len(rows), out_csv))
    for name, h, size, mime, tags, sources, updated in rows:
        print("  %s  [%s]" % (name, tags))
    if not rows:
        print("（库中暂无标签记录）")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("db", nargs="?", help="album_marker.db 路径")
    ap.add_argument("-o", "--out", default="tags_export.csv", help="输出 CSV 路径")
    ap.add_argument("--device", help="adb 序列号，从真机直接拉取（debug 包）")
    args = ap.parse_args()

    if args.device:
        tmp = os.path.join(tempfile.gettempdir(), "album_marker.db")
        pull_from_device(args.device, tmp)
        export(tmp, args.out)
    elif args.db:
        export(args.db, args.out)
    else:
        ap.print_help()
        sys.exit(1)


if __name__ == "__main__":
    main()
