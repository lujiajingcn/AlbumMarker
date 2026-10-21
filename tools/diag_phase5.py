# -*- coding: utf-8 -*-
"""
Phase 5/6 聚焦诊断：单步复现「自动添加标签 -> 使用启发式标签」与「视频重播」，
每秒记录界面文本与截图，回答两个问题：
  Q1: tap「使用启发式标签」后 progressOverlay / 建议对话框是否出现过、何时消失？
  Q2: 视频打开（dump 慢导致已近片尾）后，tap 播放/暂停能否让进度重置并推进？
"""
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import acceptance_ui as A  # noqa: E402

DIAG = os.path.join(os.path.dirname(os.path.abspath(__file__)), "diag")


def dump_texts(root):
    out = []
    for n in A.all_nodes(root):
        t = A.flat(A.text(n))
        d = A.flat(A.desc(n))
        if t:
            out.append(t)
        elif d:
            out.append("[%s]" % d)
    return out


def watch(label, seconds, interval=1.0):
    """持续观察界面，记录每次 dump 的关键文本。"""
    t0 = time.time()
    seen = []
    while time.time() - t0 < seconds:
        root = A.dump_ui()
        if root is not None:
            texts = dump_texts(root)
            key = [t for t in texts
                   if ("建议" in t or "分析" in t or "标签" in t or "取消" in t
                       or "确定" in t or "添加" in t or "尚未" in t or "失败" in t
                       or "错误" in t)]
            seen.append((round(time.time() - t0, 1), key))
            A.log("   [%5.1fs] %s" % (time.time() - t0, key[:8] if key else "(无关键文本)"))
        else:
            A.log("   [%5.1fs] dump 失败" % (time.time() - t0))
        time.sleep(interval)
    return seen


def main():
    os.makedirs(DIAG, exist_ok=True)
    if not A.start_emulator():
        return 1
    try:
        A.adb("logcat", "-c", timeout=30)
        root = A.phase_setup()
        if root is None:
            A.log("主界面 dump 失败")
            return 1

        # ---- Q1: 自动标签链路 ----
        A.log("==== Q1: 自动标签链路 ====")
        root, fn = A.open_grid_item(root, "test_photo")
        A.log("打开照片：%s" % fn)
        auto = A.by_rid(root, "btnAutoTag")
        if not auto:
            A.log("找不到自动添加标签按钮")
            return 1
        A.tap_node(auto[0], "自动添加标签")
        r, ok = A.dump_wait(lambda rr: len(A.by_text(rr, "尚未安装 Qwen 模型")) == 1, timeout=15)
        A.log("引导对话框出现：%s" % ok)
        if ok:
            heur = A.by_text(r, "使用启发式标签")
            A.log("「使用启发式标签」bounds=%s" % [A.bounds(h) for h in heur])
        A.shot("q1_before_heuristic.png")
        if heur:
            A.tap_node(heur[0], "使用启发式标签")
        A.log("---- tap 后持续观察 30s ----")
        watch("q1", 30)
        A.shot("q1_after_watch.png")
        A.adb("logcat", "-d", "-v", "threadtime", timeout=60)
        with open(os.path.join(DIAG, "q1_phase5.log"), "w", encoding="utf-8", errors="replace") as f:
            f.write(A.adb("logcat", "-d", "-v", "threadtime", timeout=60))

        # 收尾：若建议对话框还在，走完「添加所选」
        root = A.dump_ui()
        if root is not None:
            add = A.by_text_prefix(root, "添加所选")
            if add:
                A.tap_node(add[0], "添加所选")
                A.log("建议对话框仍在，已点击添加所选")

        # ---- Q2: 视频重播 ----
        A.log("==== Q2: 视频进度与重播 ====")
        A.adb("shell", "input", "keyevent", "4")
        time.sleep(1.0)
        root = A.dump_ui()
        if root is None:
            A.log("回网格 dump 失败")
            return 1
        video_thumb = None
        for _ in range(10):
            thumbs = A.by_rid(root, "ivThumb")
            durs = A.by_rid(root, "tvDuration")
            for d in durs:
                bd = A.bounds(d)
                if not bd:
                    continue
                for t in thumbs:
                    bt = A.bounds(t)
                    if bt and bt[0] <= bd[0] and bd[2] <= bt[2] and bt[1] <= bd[1] and bd[3] <= bt[3]:
                        video_thumb = t
                        break
                if video_thumb is not None:
                    break
            if video_thumb is not None:
                break
            time.sleep(1.0)
            r2 = A.dump_ui()
            if r2 is not None:
                root = r2
        if video_thumb is None:
            A.log("未定位到视频项")
            return 1
        A.tap_node(video_thumb, "视频网格项")
        r2, ok = A.dump_wait(lambda rr: len(A.by_rid(rr, "tvTotalTime")) == 1, timeout=60)
        A.log("视频页就绪：%s" % ok)
        if not ok or r2 is None:
            A.shot("q2_video_missing.png")
            return 1

        def read_video(rr):
            t = A.by_rid(rr, "tvCurrentTime")
            tt = A.by_rid(rr, "tvTotalTime")
            pp = A.by_rid(rr, "btnPlayPause")
            return ((A.text(t[0]).strip() if t else ""),
                    (A.text(tt[0]).strip() if tt else ""),
                    (A.flat(A.desc(pp[0])) if pp else ""))

        t1, total, pp1 = read_video(r2)
        A.log("打开视频：当前 %s / 总长 %s / 播放键「%s」" % (t1, total, pp1))
        ppb = A.by_rid(r2, "btnPlayPause")
        if ppb:
            A.log("播放键 bounds=%s" % str(A.bounds(ppb[0])))
            A.tap_node(ppb[0], "播放/暂停（第1次）")
            A.log("---- 第1次 tap 后观察 20s ----")
            watch("q2a", 20, interval=2.0)
            root = A.dump_ui()
            if root is not None:
                ppb = A.by_rid(root, "btnPlayPause")
                if ppb:
                    A.tap_node(ppb[0], "播放/暂停（第2次）")
                    A.log("---- 第2次 tap 后观察 20s ----")
                    watch("q2b", 20, interval=2.0)
        A.shot("q2_video_end.png")
        with open(os.path.join(DIAG, "q2_video.log"), "w", encoding="utf-8", errors="replace") as f:
            f.write(A.adb("logcat", "-d", "-v", "threadtime", timeout=60))
        return 0
    finally:
        A.kill_emulator()


if __name__ == "__main__":
    sys.exit(main())
