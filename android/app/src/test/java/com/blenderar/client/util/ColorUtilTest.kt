package com.blenderar.client.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorUtilTest {

    @Test
    fun `normalize hex accepts common forms`() {
        assertEquals("#A83CFF", ColorUtil.normalizeHex("#A83CFF", "x"))
        assertEquals("#A83CFF", ColorUtil.normalizeHex("a83cff", "x"))
        assertEquals("#AA33FF", ColorUtil.normalizeHex("#A3F", "x"))
        assertEquals("#FFFFFF", ColorUtil.normalizeHex("FFF", "x"))
    }

    @Test
    fun `normalize hex falls back on invalid`() {
        assertEquals("x", ColorUtil.normalizeHex("zzzzzz", "x"))
        assertEquals("x", ColorUtil.normalizeHex("", "x"))
        assertEquals("x", ColorUtil.normalizeHex("#12", "x"))
    }

    @Test
    fun `srgb to linear black and white`() {
        assertEquals(0f, ColorUtil.srgbToLinear(0f), 1e-6f)
        assertEquals(1f, ColorUtil.srgbToLinear(1f), 1e-6f)
    }

    @Test
    fun `parse linear known value`() {
        // sRGB 0.5 ≈ 线性 0.214
        val rgb = ColorUtil.parseLinear("#808080")!!
        assertEquals(0.214f, rgb[0], 1e-2f)
    }

    @Test
    fun `round trip hex`() {
        val rgb = ColorUtil.parseLinear("#A83CFF")!!
        val back = ColorUtil.linearToHex(rgb)
        // 经历一次量化,允许 ±1
        val a = ColorUtil.parseSrgb("#A83CFF")!!
        val b = ColorUtil.parseSrgb(back)!!
        for (i in 0..2) {
            assertTrue(Math.abs(a[i] - b[i]) < 0.02f)
        }
    }

    @Test
    fun `invalid parse returns null`() {
        assertEquals(null, ColorUtil.parseLinear("xyzxyz"))
    }
}
