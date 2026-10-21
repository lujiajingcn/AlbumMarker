# -*- coding: utf-8 -*-
"""Crop the top bar of embed_04_after_allow.png and enlarge for reading."""
from PIL import Image

src = r"E:\study_lujiajing\WorkSpace_AlbumMarker\AlbumMarker\tools\realdevice\embed_04_after_allow.png"
dst = r"E:\study_lujiajing\WorkSpace_AlbumMarker\AlbumMarker\tools\realdevice\embed_04_topbar_zoom.png"

im = Image.open(src)
print("full size:", im.size)
# top bar region (title + meta line)
crop = im.crop((0, 80, im.size[0], 260))
crop = crop.resize((crop.size[0] * 3, crop.size[1] * 3), Image.LANCZOS)
crop.save(dst)
print("saved:", dst, crop.size)
