package com.blenderar.client.util

import kotlin.math.pow

/**
 * 颜色工具:hex(#RRGGBB)解析 + sRGB→线性 RGB(Filament 材质参数使用线性空间)。
 */
object ColorUtil {

    /** 规范化 hex 颜色(#RRGGBB 大写);支持 #RRGGBB / RRGGBB / #RRGGBBAA / #RGB,非法时返回默认 */
    fun normalizeHex(input: String, default: String): String {
        val s = input.trim().removePrefix("#")
        val hex = when (s.length) {
            6, 8 -> s.take(6)
            3 -> s.map { c -> "$c$c" }.joinToString("")
            else -> return default
        }
        return if (hex.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) "#${hex.uppercase()}" else default
    }

    /** #RRGGBB → sRGB FloatArray(0..1),非法返回 null */
    fun parseSrgb(hex: String): FloatArray? {
        val s = normalizeHex(hex, "").removePrefix("#")
        if (s.length != 6) return null
        return try {
            FloatArray(3) { i ->
                s.substring(i * 2, i * 2 + 2).toInt(16) / 255f
            }
        } catch (e: NumberFormatException) {
            null
        }
    }

    /** sRGB → 线性 RGB(近似 sRGB EOTF,与 Filament 一致的做法) */
    fun srgbToLinear(c: Float): Float =
        if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)

    /** #RRGGBB → 线性 RGB FloatArray,非法返回 null */
    fun parseLinear(hex: String): FloatArray? =
        parseSrgb(hex)?.let { FloatArray(3) { i -> srgbToLinear(it[i]) } }

    /** 线性 RGB → #RRGGBB(UI 回显用) */
    fun linearToHex(rgb: FloatArray): String {
        fun ch(c: Float): Int {
            val s = if (c <= 0.0031308f) c * 12.92f else 1.055f * c.pow(1f / 2.4f) - 0.055f
            return (s.coerceIn(0f, 1f) * 255f).toInt()
        }
        return "#%02X%02X%02X".format(ch(rgb[0]), ch(rgb[1]), ch(rgb[2]))
    }
}
