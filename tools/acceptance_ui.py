# -*- coding: utf-8 -*-
"""
AlbumMarker 模拟器 UI 端到端验收（Pixel_9 AVD / Android 17 / x86_64）。

脚本自托管模拟器（启动 -> 验收 -> emu kill），不依赖外部已开设备，
避免与日常用机（Redmi K30 Pro）互相抢焦。

覆盖《相册标签工具.txt》需求：
  #2 打开并播放照片/视频      -> 网格开图、视频进度推进
  #3 底部常驻菜单栏          -> 查看器底部两按钮 + 开面板后仍常驻
  #4 手动标签面板            -> 输入框 + 最右确认图标 + 其左语音图标 + 落库成 chip
  #5 文件哈希标记            -> 文件信息对话框 64 位哈希 == 本地 sha256
  #6 自动添加标签（无模型）  -> 无模型引导对话框 -> 启发式兜底 -> chips 落库
      模型管理页 UI 可见性（导入 / 下载 / 状态 / 删除）

未覆盖（如实标注）：
  - 语音输入真实识别：模拟器无可脚本化麦克风，SpeechRecognizer 无法驱动；
    仅验证图标存在与 intent 拉起路径（代码级）。
  - Qwen 模型真实推理：模型文件 0.5-1GB 未在验收环境安装；已覆盖
    「无模型引导 + 启发式兜底 + 模型管理页」全链路。
"""
import hashlib
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ADB = r"C:\Users\lujiajing\AppData\Local\Android\Sdk\platform-tools\adb.exe"
EMU = r"C:\Users\lujiajing\AppData\Local\Android\Sdk\emulator\emulator.exe"
AVD = "Pixel_9"
PKG = "com.albummarker"
APK = r"E:\study_lujiajing\WorkSpace_AlbumMarker\AlbumMarker\app\build\outputs\apk\debug\app-debug.apk"
MEDIA = r"C:\Users\lujiajing\AppData\Local\Temp\albummedia"
SHOTS = r"E:\study_lujiajing\WorkSpace_AlbumMarker\AlbumMarker\tools\acceptance_shots"
REPORT = r"E:\study_lujiajing\WorkSpace_AlbumMarker\AlbumMarker\tools\acceptance_report.txt"

T0 = time.time()
LINES = []
FAILS = []
SERIAL = None
EMU_PROC = None


def log(m):
    print("[%6.1fs] %s" % (time.time() - T0, m), flush=True)


def check(cond, msg):
    log(("   [OK]   " if cond else "   [FAIL] ") + msg)
    LINES.append(("   [OK]   " if cond else "   [FAIL] ") + msg)
    if not cond:
        FAILS.append(msg)


def note(msg):
    log("   [note] " + msg)
    LINES.append("   [note] " + msg)


def adb(*args, timeout=180):
    cmd = [ADB]
    if SERIAL:
        cmd += ["-s", SERIAL]
    cmd += list(args)
    r = subprocess.run(cmd, capture_output=True, timeout=timeout)
    return ((r.stdout or b"") + (r.stderr or b"")).decode("utf-8", "replace").strip()


def adb_bin(*args, timeout=180):
    cmd = [ADB]
    if SERIAL:
        cmd += ["-s", SERIAL]
    cmd += list(args)
    r = subprocess.run(cmd, capture_output=True, timeout=timeout)
    return r.stdout or b""


def shot(name):
    os.makedirs(SHOTS, exist_ok=True)
    p = os.path.join(SHOTS, name)
    with open(p, "wb") as f:
        f.write(adb_bin("exec-out", "screencap", "-p", timeout=60))
    log("   shot -> %s (%d bytes)" % (name, os.path.getsize(p)))


def logcat_snapshot(tag):
    """失败现场取证：把当前 logcat 落盘，供根因分析。"""
    d = os.path.join(os.path.dirname(REPORT), "diag")
    os.makedirs(d, exist_ok=True)
    p = os.path.join(d, "%s.log" % tag)
    try:
        out = adb("logcat", "-d", "-v", "threadtime", timeout=60)
        with open(p, "w", encoding="utf-8", errors="replace") as f:
            f.write(out)
        # 应用崩溃与 ANR 是最常见的两类根因，直接在控制台高亮
        hot = [l for l in out.splitlines()
               if "AndroidRuntime" in l or "ANR in" in l or "FATAL" in l]
        if hot:
            log("   logcat 高危行（%s）：" % tag)
            for l in hot[-15:]:
                log("      " + l[:200])
        else:
            log("   logcat 无崩溃/ANR 记录 -> %s" % p)
    except Exception as e:
        log("   logcat 抓取失败：%s" % e)


# ---------- UI dump ----------

def dump_ui(retries=3):
    """返回 ElementTree root；失败返回 None（调用方必须重试，不得当成空界面）。"""
    for _ in range(retries):
        out = adb("shell", "uiautomator", "dump", "/sdcard/ui_dump.xml", timeout=60)
        if "ERROR" in out.upper() or "failed" in out.lower():
            time.sleep(2)
            continue
        xml = adb("shell", "cat", "/sdcard/ui_dump.xml", timeout=30)
        if xml.startswith("<?xml") and "<hierarchy" in xml:
            try:
                return ET.fromstring(xml)
            except ET.ParseError:
                time.sleep(2)
    return None


def dump_wait(pred, timeout=30, interval=1.0):
    deadline = time.time() + timeout
    last = None
    while time.time() < deadline:
        root = dump_ui()
        if root is None:
            time.sleep(interval)
            continue
        last = root
        try:
            if pred(root):
                return root, True
        except Exception:
            pass
        time.sleep(interval)
    return last, False


def wait_foreground(activity, timeout=20, interval=0.8):
    """轮询 dumpsys 直到 activity 成为前台 ResumedActivity。

    不依赖 uiautomator dump —— 播放中的窗口持续重绘会让 dump 每次 ~10s
    超时（实测），而 dumpsys 是纯进程状态查询，秒回。用于「页面是否已打开」
    这类二元判定，避免把 dump 失败误判成页面没打开。
    """
    deadline = time.time() + timeout
    while time.time() < deadline:
        out = adb("shell", "dumpsys", "activity", "activities", timeout=30)
        for l in out.splitlines():
            if ("mResumedActivity" in l or "topResumedActivity" in l) and activity in l:
                return True
        time.sleep(interval)
    return False


def all_nodes(root):
    return list(root.iter())


def rid(n):
    return n.get("resource-id") or ""


def text(n):
    return n.get("text") or ""


def desc(n):
    return n.get("content-desc") or ""


def flat(s):
    """比对前统一去空白（MaterialButton autoInsertSpace 会在汉字间插空格）。"""
    return re.sub(r"\s+", "", s or "")


def by_rid(root, suffix):
    out = []
    for n in all_nodes(root):
        r = rid(n)
        if r == suffix or r.endswith(":" + suffix) or r.endswith("/" + suffix):
            out.append(n)
    return out


def by_text(root, s):
    want = flat(s)
    return [n for n in all_nodes(root) if flat(text(n)) == want]


def by_text_prefix(root, s):
    want = flat(s)
    return [n for n in all_nodes(root) if flat(text(n)).startswith(want)]


def by_desc(root, s):
    want = flat(s)
    return [n for n in all_nodes(root) if flat(desc(n)) == want]


def bounds(n):
    b = n.get("bounds") or ""
    m = re.match(r"^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$", b)
    if not m:
        return None
    x1, y1, x2, y2 = (int(v) for v in m.groups())
    if x2 <= x1 or y2 <= y1:
        return None
    return (x1, y1, x2, y2)


def center(b):
    return ((b[0] + b[2]) // 2, (b[1] + b[3]) // 2)


def tap_node(n, why=""):
    b = bounds(n)
    if not b:
        log("   [tap-fail] no bounds %s %s" % (why, rid(n)))
        return False
    x, y = center(b)
    adb("shell", "input", "tap", str(x), str(y))
    if why:
        log("   tap %s @ (%d,%d)" % (why, x, y))
    return True


def tap_text(root, s, why=""):
    hits = by_text(root, s)
    if not hits:
        log("   [tap-fail] text not found: %s" % s)
        return False
    return tap_node(hits[0], why or s)


def screen_h(root):
    ys = [bounds(n)[3] for n in all_nodes(root) if bounds(n)]
    return max(ys) if ys else 2424


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def media_path(name):
    return os.path.join(MEDIA, name)


def parse_time(s):
    m = re.match(r"^(\d+):(\d{2})$", (s or "").strip())
    if not m:
        return None
    return int(m.group(1)) * 60 + int(m.group(2))


# ---------- 模拟器托管 ----------

def emulator_running():
    out = subprocess.run([ADB, "devices"], capture_output=True, timeout=30).stdout.decode()
    return [l.split("\t")[0] for l in out.splitlines()
            if "\tdevice" in l and l.startswith("emulator-")]


def start_emulator():
    global SERIAL, EMU_PROC
    exist = emulator_running()
    if exist:
        SERIAL = exist[0]
        log("复用已运行的模拟器 %s" % SERIAL)
        return True
    log("启动模拟器 %s ..." % AVD)
    logf = open(os.path.join(os.path.dirname(REPORT), "emulator.log"), "w")
    EMU_PROC = subprocess.Popen([EMU, "-avd", AVD, "-no-boot-anim", "-no-snapshot"],
                                stdout=logf, stderr=subprocess.STDOUT, close_fds=True)
    # 等 adb 上线
    for _ in range(60):
        time.sleep(3)
        exist = emulator_running()
        if exist:
            SERIAL = exist[0]
            break
    if not SERIAL:
        log("模拟器 180s 未上线")
        return False
    log("模拟器已上线：%s，等待系统启动完成..." % SERIAL)
    # 两段判据：adbd 上线 + sys.boot_completed
    ok = False
    for _ in range(80):
        time.sleep(3)
        if adb("shell", "getprop", "sys.boot_completed", timeout=30).strip() == "1":
            ok = True
            break
    if not ok:
        log("boot_completed 等待超时")
        return False
    time.sleep(10)  # boot 完不等于就绪
    adb("shell", "svc", "power", "stayon", "true")
    adb("shell", "wm", "dismiss-keyguard")
    adb("shell", "input", "keyevent", "224")  # wake
    log("模拟器就绪")
    return True


def kill_emulator():
    if EMU_PROC is not None:
        log("关闭模拟器（脚本托管生命周期）")
        adb("emu", "kill", timeout=30)
        try:
            EMU_PROC.wait(timeout=40)
        except Exception:
            pass


# ---------- 各阶段 ----------

def phase_setup():
    log("== Phase 0: 安装 / 授权 / 推媒体 / 扫描 ==")
    adb("logcat", "-c", timeout=30)  # 清空历史日志，失败现场只含本轮记录
    out = adb("install", "-r", "-t", APK, timeout=300)
    check("Success" in out, "APK 安装成功（install -r）：%s" % (out.splitlines()[-1] if out else out))
    # 清空应用数据，保证从「新用户首次启动」状态验收（会同时撤销已授权限，故必须先清后授）
    adb("shell", "pm", "clear", PKG, timeout=60)

    sdk = int(adb("shell", "getprop", "ro.build.version.sdk", timeout=30).strip() or "0")
    log("   设备 SDK=%d model=%s abi=%s"
        % (sdk, adb("shell", "getprop", "ro.product.model", timeout=30),
           adb("shell", "getprop", "ro.product.cpu.abi", timeout=30)))
    perms = []
    if sdk >= 33:
        perms = ["android.permission.READ_MEDIA_IMAGES", "android.permission.READ_MEDIA_VIDEO"]
        if sdk >= 34:
            perms.append("android.permission.READ_MEDIA_VISUAL_USER_SELECTED")
    else:
        perms = ["android.permission.READ_EXTERNAL_STORAGE"]
    bad = []
    for p in perms:
        r = adb("shell", "pm", "grant", PKG, p)
        if r:
            bad.append(p)
    check(not bad, "授予媒体读取权限（SDK %d）：%s%s"
          % (sdk, ",".join(perms), (" 失败:" + ",".join(bad)) if bad else ""))

    for f in ("test_photo_1.png", "test_photo_2.png", "test_photo_3.png"):
        adb("push", media_path(f), "/sdcard/Pictures/", timeout=120)
    adb("push", media_path("test_video_1.mp4"), "/sdcard/Movies/", timeout=120)
    ls = adb("shell", "ls", "/sdcard/Pictures/test_photo_1.png")
    check("test_photo_1.png" in ls, "测试媒体已推入设备：%s" % ls.replace("\n", " "))

    for f in ("test_photo_1.png", "test_photo_2.png", "test_photo_3.png"):
        adb("shell", "am", "broadcast", "-a", "android.intent.action.MEDIA_SCANNER_SCAN_FILE",
            "-d", "file:///sdcard/Pictures/" + f)
    adb("shell", "am", "broadcast", "-a", "android.intent.action.MEDIA_SCANNER_SCAN_FILE",
        "-d", "file:///sdcard/Movies/test_video_1.mp4")
    adb("shell", "am", "broadcast", "-a", "android.intent.action.MEDIA_MOUNTED",
        "-d", "file:///sdcard")

    adb("shell", "am", "start", "-n", PKG + "/.MainActivity")
    root, ok = dump_wait(lambda r: len(by_rid(r, "ivThumb")) >= 4, timeout=90)
    if not ok:
        r2 = dump_ui()
        if r2 is not None:
            for n in by_desc(r2, "刷新"):
                tap_node(n, "刷新")
                break
        root, ok = dump_wait(lambda r: len(by_rid(r, "ivThumb")) >= 4, timeout=60)
    # 前台校验：确认被测应用真的在前台（防串台）
    top = adb("shell", "dumpsys", "activity", "activities", timeout=30)
    check(PKG in top, "被测应用位于前台（mResumedActivity 属于 %s）" % PKG)
    check(ok, "主界面网格出现 >=4 个媒体项（3 图 + 1 视频）")
    return root


def open_grid_item(root, name_prefix):
    """依次点开网格项，直到查看器 tvFileName 以 name_prefix 开头。

    注意 dump_wait 的 timeout 必须大于最坏单次 dump 时长（本机慢时 35-60s）：
    否则单次慢 dump 抓到活动切换中的过渡态即判失败，还会把刚打开的查看器
    用返回键关掉（实测 5 个网格项全被这样毁掉）。
    """
    thumbs = by_rid(root, "ivThumb")
    for i, t in enumerate(thumbs):
        if not tap_node(t, "网格项#%d" % i):
            continue
        r, ok = dump_wait(lambda rr: len(by_rid(rr, "tvFileName")) == 1
                          and (text(by_rid(rr, "tvFileName")[0]) or "").strip() != "", timeout=60)
        if not ok or r is None:
            continue
        fn = text(by_rid(r, "tvFileName")[0]).strip()
        if fn.startswith(name_prefix):
            return r, fn
        adb("shell", "input", "keyevent", "4")
        time.sleep(0.8)
        r2 = dump_ui()
        if r2 is not None:
            root = r2
    # 全部失败：留证（截图 + logcat + 当前界面文本），供根因分析
    shot("open_grid_failed.png")
    logcat_snapshot("open_grid_failed")
    rd = dump_ui()
    if rd is not None:
        note("当前界面文本：%s" % [flat(text(n)) for n in all_nodes(rd) if flat(text(n))][:12])
    return root, None


def phase_grid(root):
    log("== Phase 1: 媒体网格 ==")
    n = len(by_rid(root, "ivThumb"))
    check(n >= 4, "网格渲染 %d 个媒体项（需求 2：浏览照片/视频）" % n)
    shot("01_grid.png")
    return root


def phase_photo_viewer(root):
    log("== Phase 2: 打开照片 -> 查看器 ==")
    root, fn = open_grid_item(root, "test_photo")
    check(fn is not None, "从网格打开测试照片：%s" % (fn or "未找到"))
    if fn is None:
        return root, None

    check(len(by_rid(root, "ivPhoto")) == 1, "照片页 ZoomableImageView 可见（需求 2：打开照片）")

    h = screen_h(root)
    manual = by_rid(root, "btnManualTag")
    auto = by_rid(root, "btnAutoTag")
    check(len(manual) == 1 and len(auto) == 1,
          "底部菜单两按钮存在：手动添加标签 / 自动添加标签（需求 3）")
    if manual:
        b = bounds(manual[0])
        check(b is not None and b[3] > h * 0.85,
              "「手动添加标签」位于屏幕最下方 15%%（y2=%s, 屏高=%d）" % (b[3] if b else "?", h))

    def meta_hashed(rr):
        t = by_rid(rr, "tvMeta")
        return len(t) == 1 and "SHA-256:" in flat(text(t[0]))

    r2, ok = dump_wait(meta_hashed, timeout=45)
    check(ok, "查看器显示文件哈希（需求 5）：%s"
          % (flat(text(by_rid(r2, "tvMeta")[0]))[:60] if ok and r2 is not None else "20s 超时"))
    if ok and r2 is not None:
        root = r2
    shot("02_viewer_photo.png")
    return root, fn


def phase_manual_tags(root):
    log("== Phase 3: 手动添加标签（需求 4）==")
    btn = by_rid(root, "btnManualTag")
    if not btn:
        check(False, "找不到「手动添加标签」按钮")
        return root
    tap_node(btn[0], "手动添加标签")

    r, ok = dump_wait(lambda rr: len(by_rid(rr, "etTagInput")) == 1
                      and len(by_desc(rr, "语音输入")) == 1
                      and len(by_desc(rr, "确认添加标签")) == 1, timeout=30)
    check(ok, "菜单栏上方弹出输入面板：输入框 + 语音图标 + 确认图标")
    if not ok or r is None:
        return root
    root = r

    et = by_rid(root, "etTagInput")[0]
    voice = by_desc(root, "语音输入")[0]
    confirm = by_desc(root, "确认添加标签")[0]
    be, bv, bc = bounds(et), bounds(voice), bounds(confirm)
    check(bool(be and bv and bc and be[0] < bv[0] < bc[0]),
          "输入框 -> 语音图标 -> 确认图标 从左到右排列（确认图标在最右侧）")

    check(len(by_rid(root, "btnManualTag")) == 1 and len(by_rid(root, "btnAutoTag")) == 1,
          "输入面板弹出时底部菜单栏仍常驻可见")

    panel = by_rid(root, "panelManualTag")
    menu = by_rid(root, "bottomMenu")
    if panel and menu:
        bp, bm = bounds(panel[0]), bounds(menu[0])
        check(bool(bp and bm and bp[3] <= bm[1] + 8),
              "输入面板位于底部菜单栏上方（panel.y2=%s, menu.y1=%s）" % (bp[3] if bp else "?", bm[1] if bm else "?"))

    shot("03_manual_panel.png")

    # 输入并确认。注意：不再用坐标点输入框——软键盘弹起后布局会位移，
    # 旧坐标可能点中键盘按键（实测因此多出一个 'c'）。应用在展开面板时已
    # requestFocus，直接等键盘稳定后注入文本，并以 dump 校验输入框内容。
    for attempt in range(2):
        time.sleep(1.2)  # 等键盘完全弹出
        adb("shell", "input", "text", "beach,sunset")
        time.sleep(0.5)
        rv = dump_ui()
        typed = ""
        if rv is not None:
            ets = by_rid(rv, "etTagInput")
            if ets:
                typed = (text(ets[0]) or "").strip()
        if typed == "beach,sunset":
            break
        note("输入框内容不符（第 %d 次）：%r，清空重试" % (attempt + 1, typed))
        adb("shell", "input", "keyevent", "111")  # 收键盘
        time.sleep(0.5)
        for _ in range(30):
            adb("shell", "input", "keyevent", "67")  # DEL
        time.sleep(0.4)

    r2 = dump_ui()
    if r2 is not None:
        root = r2
    conf = by_desc(root, "确认添加标签")
    if not conf:
        check(False, "确认图标不可见")
        return root
    tap_node(conf[0], "确认添加标签")

    def chips_added(rr):
        if not by_rid(rr, "chipGroupTags"):
            return []
        return [flat(text(c)) for c in by_rid(rr, "chipGroupTags")[0].iter() if flat(text(c))]

    r3, ok = dump_wait(lambda rr: set(["beach", "sunset"]).issubset(set(chips_added(rr))), timeout=30)
    check(ok, "确认后标签写入当前媒体并显示为 chip：%s" % chips_added(r3 if r3 is not None else root))
    if r3 is not None:
        root = r3
    shot("04_manual_tags.png")

    r4 = dump_ui()
    if r4 is not None:
        snack = [flat(text(n)) for n in all_nodes(r4) if "已添加" in flat(text(n))]
        if snack:
            note("Snackbar 反馈：%s" % snack[0][:40])
        else:
            note("Snackbar 已消失或未捕获（chip 为硬证据，不计缺陷）")
    return root


def phase_hash_end_to_end(root, filename):
    log("== Phase 4: 哈希端到端核对（需求 5）==")
    if not filename:
        check(False, "无文件名，跳过哈希核对")
        return
    ov = by_desc(root, "更多")
    if not ov:
        check(False, "找不到溢出菜单按钮（更多）")
        return
    tap_node(ov[0], "溢出菜单")
    time.sleep(0.8)
    r = dump_ui()
    if r is None:
        check(False, "溢出菜单 dump 失败")
        return
    if not tap_text(r, "文件信息", "文件信息"):
        check(False, "菜单项「文件信息」未找到")
        return

    def has_hash(rr):
        return any(re.search(r"SHA-256[：:][^0-9a-fA-F]*([0-9a-fA-F]{64})", text(n) or "")
                   for n in all_nodes(rr))

    r2, ok = dump_wait(has_hash, timeout=30)
    check(ok, "文件信息对话框展示完整 SHA-256")
    if not ok or r2 is None:
        return
    dev_hash = None
    for n in all_nodes(r2):
        m = re.search(r"SHA-256[：:][^0-9a-fA-F]*([0-9a-fA-F]{64})", text(n) or "")
        if m:
            dev_hash = m.group(1).lower()
            break
    local_hash = sha256_file(media_path(filename))
    check(dev_hash == local_hash, "设备端哈希 == 本地 sha256（%s...）" % local_hash[:16])
    if dev_hash != local_hash:
        note("设备=%s 本地=%s" % (dev_hash, local_hash))
    shot("05_file_info.png")
    tap_text(r2, "确定", "关闭文件信息")
    time.sleep(0.5)


def phase_auto_tag(root):
    log("== Phase 5: 自动添加标签 - 无模型引导 + 启发式兜底（需求 6）==")
    # Phase 3 手动标签输入后软键盘可能仍弹出：键盘会把底部菜单顶上去，
    # 用旧 root 的坐标点按钮会点空。先收键盘 + 重新 dump，再定位按钮。
    adb("shell", "input", "keyevent", "111")
    time.sleep(1.0)
    r0 = dump_ui()
    if r0 is not None:
        root = r0
    btn = by_rid(root, "btnAutoTag")
    if not btn:
        check(False, "找不到「自动添加标签」按钮")
        return root
    tap_node(btn[0], "自动添加标签")

    r, ok = dump_wait(lambda rr: len(by_text(rr, "尚未安装 Qwen 模型")) == 1, timeout=30)
    check(ok, "未安装模型时弹出引导对话框（去安装模型 / 使用启发式标签 / 取消）")
    if not ok or r is None:
        return root
    shot("06_no_model_dialog.png")

    if not tap_text(r, "使用启发式标签", "使用启发式标签"):
        check(False, "「使用启发式标签」按钮未找到")
        return root

    def suggest_ready(rr):
        # 标题完整文本是「建议标签（可勾选）」，必须用前缀匹配
        return (len(by_text_prefix(rr, "建议标签")) >= 1
                and len(by_rid(rr, "chipGroupSuggestions")) == 1)

    r2, ok = dump_wait(suggest_ready, timeout=45)
    check(ok, "启发式分析完成并弹出建议标签对话框")
    if not ok or r2 is None:
        shot("07b_auto_suggestions_missing.png")
        logcat_snapshot("auto_suggestions_missing")
        top = adb("shell", "dumpsys", "activity", "activities", timeout=30)
        for l in top.splitlines():
            if "mResumedActivity" in l or "ResumedActivity" in l:
                note("当前前台：%s" % l.strip()[:160])
                break
        rd = dump_ui()
        if rd is not None:
            errs = [flat(text(n)) for n in all_nodes(rd)
                    if "失败" in flat(text(n)) or "错误" in flat(text(n))]
            note("界面错误线索：%s" % (errs[:3] if errs else "无"))
        return root
    src = [flat(text(n)) for n in all_nodes(r2) if "来源" in flat(text(n))]
    note("建议来源标注：%s" % (src[0][:40] if src else "未捕获"))
    check(any("启发式" in s for s in src), "建议对话框标注来源为启发式分析（未安装模型）")
    sug = [flat(text(c)) for c in by_rid(r2, "chipGroupSuggestions")[0].iter() if flat(text(c))]
    check(len(sug) >= 1, "启发式给出 >=1 个建议标签：%s" % sug)
    shot("07_auto_suggestions.png")

    pos = by_text_prefix(r2, "添加所选")
    if not pos:
        check(False, "「添加所选」按钮未找到")
        return root
    before = len([c for c in by_rid(r2, "chipGroupTags")[0].iter() if flat(text(c))]) \
        if by_rid(r2, "chipGroupTags") else 0
    tap_node(pos[0], "添加所选")

    def chips_grew(rr):
        if not by_rid(rr, "chipGroupTags"):
            return False
        return len([c for c in by_rid(rr, "chipGroupTags")[0].iter() if flat(text(c))]) > before

    r3, ok = dump_wait(chips_grew, timeout=30)
    after = len([c for c in by_rid(r3, "chipGroupTags")[0].iter() if flat(text(c))]) \
        if (ok and r3 is not None and by_rid(r3, "chipGroupTags")) else -1
    check(ok, "所选建议标签写入当前媒体（chip 数 %d -> %d）" % (before, after))
    if r3 is not None:
        root = r3
    shot("08_auto_tags_applied.png")
    return root


def phase_video(root):
    log("== Phase 6: 视频播放（需求 2）==")
    adb("shell", "input", "keyevent", "4")  # 回网格
    time.sleep(1.0)
    r = dump_ui()
    if r is not None:
        root = r

    # 从网格直接定位视频项：视频角标 tvDuration 只出现在视频项里
    video_thumb = None
    for _ in range(10):
        thumbs = by_rid(root, "ivThumb")
        durs = by_rid(root, "tvDuration")
        for d in durs:
            bd = bounds(d)
            if not bd:
                continue
            for t in thumbs:
                bt = bounds(t)
                if bt and bt[0] <= bd[0] and bd[2] <= bt[2] and bt[1] <= bd[1] and bd[3] <= bt[3]:
                    video_thumb = t
                    break
            if video_thumb is not None:
                break
        if video_thumb is not None:
            break
        time.sleep(1.0)
        r2 = dump_ui()
        if r2 is not None:
            root = r2
    check(video_thumb is not None, "网格中定位到视频项（视频角标可见）")
    if video_thumb is None:
        return root
    tap_node(video_thumb, "视频网格项")

    # 关键认知（第 7 轮实测）：播放中的视频窗口持续重绘，uiautomator dump
    # 每次 ~10s 必超时失败，但截图证明播放完全正常。因此：
    #   1) 「查看器已打开」用 dumpsys 前台轮询判定，不依赖 dump；
    #   2) 所有 dump 交互一律「先暂停、后 dump」——暂停后窗口空闲，
    #      dump 稳定成功。
    fg = wait_foreground(PKG + "/.MediaViewerActivity", timeout=20)
    check(fg, "点击视频网格项后 MediaViewerActivity 进入前台")
    if not fg:
        shot("09b_video_page_missing.png")
        logcat_snapshot("video_page_missing")
        return root

    def video_ready(rr):
        # tvFileName（Activity 级）+ btnPlayPause（controls 进入页面即常显，
        # 不等 prepare）足以判定「视频查看器已打开」。
        return (len(by_rid(rr, "tvFileName")) == 1
                and len(by_rid(rr, "btnPlayPause")) == 1)

    # 目标：拿到一份「暂停态」dump（播放键 content-desc == 「播放」）。
    # 播放键中心由 layout_gravity=center 固定（实测 (540,1251)），在拿到
    # 第一份 dump 之前只能凭该坐标暂停；拿到 dump 后改用节点精确 bounds。
    # 循环自纠正：若 tap 后仍在播放，下一轮 dump 失败 -> 再 tap 一次。
    paused = None
    for attempt in range(3):
        r2, ok = dump_wait(video_ready, timeout=(8 if attempt == 0 else 45))
        if ok and r2 is not None:
            pp = by_rid(r2, "btnPlayPause")
            if pp and flat(desc(pp[0])) == "播放":
                paused = r2  # 暂停态（或尚未开播），窗口空闲，dump 有效
                break
            if pp:
                note("dump 就绪但视频仍在播放，用精确坐标 tap 播放键暂停")
                tap_node(pp[0], "播放/暂停（暂停）")
                time.sleep(1.5)
                continue
        note("dump 未就绪（第 %d 次尝试），tap 播放键中心 (540,1251) 暂停" % (attempt + 1))
        adb("shell", "input", "tap", "540", "1251")
        time.sleep(1.5)

    check(paused is not None,
          "视频页控件就绪（暂停态 dump：文件名 + 播放/暂停 + 进度条 + 时间）")
    if paused is None:
        shot("09b_video_page_missing.png")
        logcat_snapshot("video_page_missing")
        return root
    root = paused
    shot("09_video_open.png")

    def read_video(rr):
        t = by_rid(rr, "tvCurrentTime")
        tt = by_rid(rr, "tvTotalTime")
        pp = by_rid(rr, "btnPlayPause")
        fn = by_rid(rr, "tvFileName")
        total = (text(tt[0]).strip() if tt else "")
        if not total:
            # 兜底：从 tvMeta 末尾解析时长（"SHA-256: … · 4.0 MB · 3:00"）
            for n in by_rid(rr, "tvMeta"):
                m = re.search(r"(\d+:\d{2})$", flat(text(n)))
                if m:
                    total = m.group(1)
                    break
        return (
            (text(t[0]).strip() if t else ""),
            total,
            (flat(desc(pp[0])) if pp else ""),
            (text(fn[0]).strip() if fn else ""),
        )

    t1, total, pp1, fn = read_video(root)
    p1, pt = parse_time(t1), parse_time(total)
    note("暂停态：%s，当前 %s / 总长 %s / 播放键状态「%s」" % (fn or "?", t1, total, pp1))
    check(bool(fn), "查看器显示视频文件名：%s" % (fn or "未捕获"))

    # 测量「打开并播放 + 进度推进」：恢复播放 -> 静默等 3s（播放中严禁
    # dump，必败）-> 再暂停 -> dump 读 t2。产品侧已修复两个关联 bug：
    # 播完后点播放不重头播（显式 seekTo(0)）、播放中图标不更新
    # （updatePlayIcon 移到 start() 之后）。
    ppb = by_rid(root, "btnPlayPause")
    if not ppb:
        check(False, "暂停态未找到播放键，无法恢复播放")
        return root
    tap_node(ppb[0], "播放/暂停（恢复播放）")
    time.sleep(3.0)
    tap_node(ppb[0], "播放/暂停（再次暂停）")
    time.sleep(1.5)
    r4 = dump_ui()
    if r4 is None:
        # 可能第二次 tap 没落在播放键上（仍处于播放态导致 dump 败），再试一次
        tap_node(ppb[0], "播放/暂停（重试暂停）")
        time.sleep(1.5)
        r4 = dump_ui()
    if r4 is None:
        check(False, "再次暂停后 dump 仍失败")
        shot("10b_video_dump_fail.png")
        logcat_snapshot("video_dump_fail")
        return root
    t2, total2, pp2, _ = read_video(r4)
    p1b, p2 = parse_time(t1), parse_time(t2)
    pt2 = parse_time(total2)
    note("恢复播放 3s 后暂停：当前 %s / 播放键状态「%s」" % (t2, pp2))
    check(p1b is not None and p2 is not None and p2 > p1b,
          "视频播放且进度推进：%s -> %s（需求 2：打开并播放）" % (t1, t2))
    check(pt2 is not None and pt2 >= 175,
          "视频总时长解析为 180s 量级（实测 %s）" % total2)
    shot("10_video_playing.png")
    return r4


def phase_model_manage(root):
    log("== Phase 7: 模型管理页（需求 6 UI）==")
    adb("shell", "input", "keyevent", "4")  # 回网格
    time.sleep(1.0)
    r = dump_ui()
    if r is not None:
        root = r

    entry = by_desc(root, "模型管理")
    if not entry:
        root2, fn = open_grid_item(root, "test_photo")
        ov = by_desc(root2, "更多") if root2 is not None else []
        if ov:
            tap_node(ov[0], "溢出菜单")
            time.sleep(0.8)
            r = dump_ui()
            entry = by_text(r, "模型管理") if r is not None else []
    if not entry:
        check(False, "找不到「模型管理」入口")
        return root
    tap_node(entry[0], "模型管理")

    def page_ready(rr):
        return (len(by_rid(rr, "tvModelStatus")) == 1
                and len(by_rid(rr, "btnImportFile")) == 1
                and len(by_rid(rr, "etModelUrl")) == 1
                and len(by_rid(rr, "btnDownloadModel")) == 1)

    r2, ok = dump_wait(page_ready, timeout=30)
    check(ok, "模型管理页打开：状态 / 从文件导入 / 从网址下载 / 删除")
    if ok and r2 is not None:
        status = flat(text(by_rid(r2, "tvModelStatus")[0]))
        note("模型状态：%s" % status)
        shot("11_model_manage.png")
        root = r2
    adb("shell", "input", "keyevent", "4")  # 返回
    time.sleep(0.8)


def finish():
    log("========== 结论 ==========")
    for l in LINES:
        log(l)
    log("")
    log("未覆盖项：")
    log("  1) 语音输入真实识别：模拟器无可脚本化麦克风；已验证图标存在/排列与代码路径。")
    log("  2) Qwen 模型真实推理：模型文件 0.5-1GB 未安装；已覆盖无模型引导 + 启发式兜底 + 模型管理页。")
    verdict = "PASS" if not FAILS else "FAIL（%d 项）" % len(FAILS)
    log("结论：%s" % verdict)
    with open(REPORT, "w", encoding="utf-8") as f:
        f.write("\n".join(LINES) + "\n\n未覆盖项：\n"
                "  1) 语音输入真实识别：模拟器无可脚本化麦克风；已验证图标存在/排列与代码路径。\n"
                "  2) Qwen 模型真实推理：模型文件 0.5-1GB 未安装；已覆盖无模型引导 + 启发式兜底 + 模型管理页。\n"
                "\n结论：%s\n" % verdict)
    return 0 if not FAILS else 1


def main():
    os.makedirs(SHOTS, exist_ok=True)
    if not start_emulator():
        return finish()
    try:
        log("验收目标设备：%s（%s / Android %s）"
            % (SERIAL, adb("shell", "getprop", "ro.product.model", timeout=30),
               adb("shell", "getprop", "ro.build.version.release", timeout=30)))
        root = phase_setup()
        if root is None:
            check(False, "主界面 dump 失败，验收中止")
            return finish()
        root = phase_grid(root)
        root, fn = phase_photo_viewer(root)
        if fn:
            root = phase_manual_tags(root)
            phase_hash_end_to_end(root, fn)
            root = phase_auto_tag(root)
        root = phase_video(root)
        phase_model_manage(root)
        return finish()
    finally:
        kill_emulator()


if __name__ == "__main__":
    sys.exit(main())
