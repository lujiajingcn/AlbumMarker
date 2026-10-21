# -*- coding: utf-8 -*-
"""诊断：为什么 com.albummarker 启动后不在前台。"""
import os
import re
import subprocess
import sys
import time

ADB = r"C:\Users\lujiajing\AppData\Local\Android\Sdk\platform-tools\adb.exe"
EMU = r"C:\Users\lujiajing\AppData\Local\Android\Sdk\emulator\emulator.exe"
AVD = "Pixel_9"
PKG = "com.albummarker"
SERIAL = None
EMU_PROC = None
OUT = r"E:\study_lujiajing\WorkSpace_AlbumMarker\AlbumMarker\tools\diag"
os.makedirs(OUT, exist_ok=True)


def log(m):
    print("[%6.1fs] %s" % (time.time() - T0, m), flush=True)


T0 = time.time()


def adb(*a, timeout=120):
    cmd = [ADB] + (["-s", SERIAL] if SERIAL else []) + list(a)
    r = subprocess.run(cmd, capture_output=True, timeout=timeout)
    return ((r.stdout or b"") + (r.stderr or b"")).decode("utf-8", "replace").strip()


def emulator_running():
    out = subprocess.run([ADB, "devices"], capture_output=True, timeout=30).stdout.decode()
    return [l.split("\t")[0] for l in out.splitlines() if "\tdevice" in l and l.startswith("emulator-")]


def main():
    global SERIAL, EMU_PROC
    ex = emulator_running()
    if not ex:
        logf = open(os.path.join(OUT, "emulator.log"), "w")
        EMU_PROC = subprocess.Popen([EMU, "-avd", AVD, "-no-boot-anim"],
                                    stdout=logf, stderr=subprocess.STDOUT, close_fds=True)
        for _ in range(60):
            time.sleep(3)
            ex = emulator_running()
            if ex:
                break
    SERIAL = ex[0] if ex else None
    log("serial=%s" % SERIAL)
    if not SERIAL:
        return 1
    for _ in range(80):
        if adb("shell", "getprop", "sys.boot_completed", timeout=30).strip() == "1":
            break
        time.sleep(3)
    time.sleep(8)
    adb("shell", "svc", "power", "stayon", "true")
    adb("shell", "wm", "dismiss-keyguard")
    adb("shell", "input", "keyevent", "224")

    top = adb("shell", "dumpsys", "activity", "activities", timeout=60)
    m = re.search(r"mResumedActivity:.*?(\S+/\S+)", top)
    log("启动前前台：%s" % (m.group(1) if m else "?"))

    log("--- 清 logcat ---")
    adb("logcat", "-c", timeout=30)
    log("--- am start -W ---")
    out = adb("shell", "am", "start", "-W", "-n", PKG + "/.MainActivity", timeout=60)
    log("am start 输出：\n" + out)
    time.sleep(4)

    top = adb("shell", "dumpsys", "activity", "activities", timeout=60)
    m = re.search(r"mResumedActivity:.*?(\S+/\S+)", top)
    log("启动后前台：%s" % (m.group(1) if m else "?"))

    crash = adb("logcat", "-d", timeout=60)
    with open(os.path.join(OUT, "logcat_after_launch.txt"), "w", encoding="utf-8") as f:
        f.write(crash)
    hits = [l for l in crash.splitlines()
            if PKG in l or "AndroidRuntime" in l or "FATAL" in l or "died" in l]
    log("logcat 关键行 %d 条：" % len(hits))
    for l in hits[:40]:
        log("   " + l[:220])

    # 进程是否存活
    ps = adb("shell", "ps", "-A", timeout=30)
    alive = [l for l in ps.splitlines() if PKG in l]
    log("进程：%s" % (alive if alive else "不在运行"))

    # 包信息
    pi = adb("shell", "dumpsys", "package", PKG, timeout=60)
    with open(os.path.join(OUT, "pkg.txt"), "w", encoding="utf-8") as f:
        f.write(pi)
    ver = re.search(r"versionCode=(\d+)", pi)
    log("versionCode=%s" % (ver.group(1) if ver else "?"))

    with open(os.path.join(OUT, "ui_after_launch.xml"), "wb") as f:
        adb("shell", "uiautomator", "dump", "/sdcard/diag.xml", timeout=60)
        f.write(adb("shell", "cat", "/sdcard/diag.xml", timeout=30).encode("utf-8"))
    log("UI dump 已保存")

    if EMU_PROC is not None:
        log("关闭模拟器")
        adb("emu", "kill", timeout=30)
        try:
            EMU_PROC.wait(timeout=40)
        except Exception:
            pass
    return 0


if __name__ == "__main__":
    sys.exit(main())
