# -*- coding: utf-8 -*-
"""Inspect exported DB: which hash do the tags live under now?"""
import sqlite3

DB = r"E:\study_lujiajing\WorkSpace_AlbumMarker\AlbumMarker\tools\realdevice\album_marker_after_embed.db"

OLD = "bd5bd654b4796cc2"
NEW = "285cec919ae2d126361c700f59a018e11c8239e15226f6903689a5b00b9f0ac4"

conn = sqlite3.connect(DB)
c = conn.cursor()

print("== tables ==")
for row in c.execute("SELECT name FROM sqlite_master WHERE type='table'"):
    print(" ", row[0])

print("== tags ==")
for row in c.execute("SELECT hash, tag FROM tags"):
    mark = ""
    if row[0].startswith(OLD):
        mark = "  <-- OLD HASH"
    if row[0] == NEW:
        mark = "  <-- NEW HASH (migrated)"
    print(" ", row[0][:20], row[1], mark)

print("== media_tags ==")
for row in c.execute("SELECT * FROM media_tags"):
    print(" ", [str(x)[:24] for x in row])

print("== hash_cache ==")
for row in c.execute("SELECT * FROM hash_cache"):
    print(" ", [str(x)[:30] for x in row])

conn.close()
