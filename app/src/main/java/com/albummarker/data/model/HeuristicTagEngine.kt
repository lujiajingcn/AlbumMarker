package com.albummarker.data.model

import android.graphics.Bitmap
import android.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 启发式打标引擎：不依赖任何模型，基于本地特征（颜色 / 亮度 / 饱和度 /
 * 横竖版 / EXIF / 视频时长）给出粗粒度标签。
 * 用于模型未安装时的降级路径，UI 中会明确标注「启发式」。
 */
class HeuristicTagEngine : AutoTagEngine {

    override val id: String = AutoTagEngine.ID_HEURISTIC
    override val displayName: String = "启发式标签（无需模型）"

    override suspend fun suggestTags(input: TagInput): List<String> =
        withContext(Dispatchers.Default) {
            val tags = mutableListOf<String>()

            input.bitmaps.firstOrNull()?.let { bmp ->
                val stats = analyzeBitmap(bmp)
                tags += colorName(stats.avgR, stats.avgG, stats.avgB)
                tags += brightnessTag(stats.luminance)
                tags += saturationTag(stats.saturation)
                tags += aspectTag(bmp.width, bmp.height)
            }

            input.exif?.let { exif ->
                exif.dateTime?.let { raw ->
                    // EXIF 形如 "2024:05:17 10:30:00"
                    val year = raw.take(4)
                    if (year.all { it.isDigit() }) tags += year
                }
                val camera = listOfNotNull(exif.make, exif.model)
                    .joinToString(" ")
                    .trim()
                    .replace(Regex("\\s+"), " ")
                if (camera.isNotEmpty()) tags += "相机:$camera"
                if (exif.hasGps) tags += "含位置信息"
            }

            input.video?.let { video ->
                tags += durationTag(video.durationMs)
                if (video.width > 0 && video.height > 0) {
                    tags += aspectTag(video.width, video.height)
                }
            }

            AutoTagEngine.normalize(tags)
        }

    data class BitmapStats(
        val avgR: Int,
        val avgG: Int,
        val avgB: Int,
        val luminance: Double,
        val saturation: Double,
    )

    companion object {
        fun analyzeBitmap(bmp: Bitmap): BitmapStats {
            val w = 32
            val h = 32
            val small = if (bmp.width <= w && bmp.height <= h) bmp
            else Bitmap.createScaledBitmap(bmp, w, h, true)
            try {
                var rSum = 0L
                var gSum = 0L
                var bSum = 0L
                var sSum = 0.0
                val pixels = IntArray(w * h)
                small.getPixels(pixels, 0, w, 0, 0, w, h)
                for (p in pixels) {
                    rSum += Color.red(p)
                    gSum += Color.green(p)
                    bSum += Color.blue(p)
                    sSum += saturationOf(Color.red(p), Color.green(p), Color.blue(p))
                }
                val n = (w * h).toDouble()
                return BitmapStats(
                    avgR = (rSum / n).toInt(),
                    avgG = (gSum / n).toInt(),
                    avgB = (bSum / n).toInt(),
                    luminance = luminanceOf(rSum / n, gSum / n, bSum / n),
                    saturation = sSum / n,
                )
            } finally {
                if (small !== bmp) runCatching { small.recycle() }
            }
        }

        /** HSV 饱和度（0..1）。 */
        fun saturationOf(r: Int, g: Int, b: Int): Double {
            val max = maxOf(r, g, b).toDouble()
            val min = minOf(r, g, b).toDouble()
            return if (max <= 0.0) 0.0 else (max - min) / max
        }

        /** 相对亮度（0..1），ITU-R BT.601 系数。 */
        fun luminanceOf(r: Double, g: Double, b: Double): Double =
            (0.299 * r + 0.587 * g + 0.114 * b) / 255.0

        /** 主色命名（中文）。低饱和度走灰阶，否则按色相分区。 */
        fun colorName(r: Int, g: Int, b: Int): String {
            val saturation = saturationOf(r, g, b)
            if (saturation < 0.12) {
                val lum = luminanceOf(r.toDouble(), g.toDouble(), b.toDouble())
                return when {
                    lum < 0.12 -> "黑色"
                    lum < 0.3 -> "深色"
                    lum < 0.5 -> "暗灰色"
                    lum < 0.72 -> "灰色"
                    lum < 0.9 -> "浅色"
                    else -> "白色"
                }
            }
            val hue = hueOf(r, g, b)
            return when {
                hue < 15.0 -> "红色"
                hue < 40.0 -> "橙色"
                hue < 65.0 -> "黄色"
                hue < 95.0 -> "黄绿色"
                hue < 150.0 -> "绿色"
                hue < 180.0 -> "青绿色"
                hue < 200.0 -> "青色"
                hue < 250.0 -> "蓝色"
                hue < 285.0 -> "紫色"
                hue < 330.0 -> "粉色"
                else -> "红色"
            }
        }

        /** 色相（0..360）。 */
        fun hueOf(r: Int, g: Int, b: Int): Double {
            val rd = r / 255.0
            val gd = g / 255.0
            val bd = b / 255.0
            val max = maxOf(rd, gd, bd)
            val min = minOf(rd, gd, bd)
            val delta = max - min
            if (delta <= 0.0) return 0.0
            val hue = when (max) {
                rd -> 60.0 * (((gd - bd) / delta) % 6.0)
                gd -> 60.0 * (((bd - rd) / delta) + 2.0)
                else -> 60.0 * (((rd - gd) / delta) + 4.0)
            }
            return (hue + 360.0) % 360.0
        }

        fun brightnessTag(luminance: Double): String = when {
            luminance < 0.15 -> "很暗"
            luminance < 0.35 -> "偏暗"
            luminance < 0.7 -> "亮度适中"
            luminance < 0.88 -> "明亮"
            else -> "过曝/极亮"
        }

        fun saturationTag(saturation: Double): String = when {
            saturation < 0.1 -> "素雅"
            saturation < 0.35 -> "色彩柔和"
            saturation < 0.6 -> "色彩鲜明"
            else -> "色彩浓烈"
        }

        fun aspectTag(width: Int, height: Int): String {
            if (width <= 0 || height <= 0) return "未知尺寸"
            val ratio = width.toDouble() / height
            return when {
                ratio > 1.15 -> "横版"
                ratio < 0.87 -> "竖版"
                else -> "方形"
            }
        }

        fun durationTag(durationMs: Long): String = when {
            durationMs <= 0 -> "时长未知"
            durationMs < 15_000 -> "短视频"
            durationMs < 60_000 -> "短片段"
            durationMs < 300_000 -> "中等时长视频"
            durationMs < 900_000 -> "较长视频"
            else -> "长视频"
        }
    }
}
