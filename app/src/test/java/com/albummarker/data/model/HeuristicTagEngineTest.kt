package com.albummarker.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class HeuristicTagEngineTest {

    @Test
    fun ` color names by hue `() {
        assertEquals("红色", HeuristicTagEngine.colorName(220, 20, 20))
        assertEquals("橙色", HeuristicTagEngine.colorName(230, 130, 20))
        assertEquals("黄色", HeuristicTagEngine.colorName(230, 220, 30))
        assertEquals("绿色", HeuristicTagEngine.colorName(30, 160, 40))
        assertEquals("蓝色", HeuristicTagEngine.colorName(30, 60, 200))
        assertEquals("紫色", HeuristicTagEngine.colorName(140, 40, 200))
    }

    @Test
    fun ` gray scale names for low saturation `() {
        assertEquals("黑色", HeuristicTagEngine.colorName(10, 10, 10))
        assertEquals("白色", HeuristicTagEngine.colorName(245, 245, 245))
        assertEquals("灰色", HeuristicTagEngine.colorName(130, 130, 130))
    }

    @Test
    fun ` brightness thresholds `() {
        assertEquals("很暗", HeuristicTagEngine.brightnessTag(0.05))
        assertEquals("偏暗", HeuristicTagEngine.brightnessTag(0.25))
        assertEquals("亮度适中", HeuristicTagEngine.brightnessTag(0.5))
        assertEquals("明亮", HeuristicTagEngine.brightnessTag(0.8))
        assertEquals("过曝/极亮", HeuristicTagEngine.brightnessTag(0.95))
    }

    @Test
    fun ` saturation thresholds `() {
        assertEquals("素雅", HeuristicTagEngine.saturationTag(0.05))
        assertEquals("色彩柔和", HeuristicTagEngine.saturationTag(0.2))
        assertEquals("色彩鲜明", HeuristicTagEngine.saturationTag(0.45))
        assertEquals("色彩浓烈", HeuristicTagEngine.saturationTag(0.8))
    }

    @Test
    fun ` aspect thresholds `() {
        assertEquals("横版", HeuristicTagEngine.aspectTag(1600, 900))
        assertEquals("竖版", HeuristicTagEngine.aspectTag(900, 1600))
        assertEquals("方形", HeuristicTagEngine.aspectTag(1000, 1000))
    }

    @Test
    fun ` duration thresholds `() {
        assertEquals("短视频", HeuristicTagEngine.durationTag(8_000))
        assertEquals("中等时长视频", HeuristicTagEngine.durationTag(120_000))
        assertEquals("长视频", HeuristicTagEngine.durationTag(1_200_000))
        assertEquals("时长未知", HeuristicTagEngine.durationTag(0))
    }
}
