package net.torosaba.proxy

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import java.util.regex.Pattern
import kotlin.math.floor
import kotlin.math.roundToInt

object PingFormatter {
    private val GRADIENT_PATTERN = Pattern.compile("%gradient((?:#[0-9a-fA-F]{6})+)%(.*?)%gradient%", Pattern.DOTALL)
    private val HEX_COLOR_PATTERN = Pattern.compile("#([0-9a-fA-F]{6})")
    private val LEGACY_SERIALIZER = LegacyComponentSerializer.builder()
        .character('&')
        .hexColors()
        .useUnusualXRepeatedCharacterHexFormat()
        .build()

    fun format(
        text: String,
        placeholders: Map<String, String> = emptyMap()
    ): Component {
        var processed = text
        for ((key, value) in placeholders) {
            processed = processed.replace(key, value)
        }
        processed = applyGradients(processed)
        return LEGACY_SERIALIZER.deserialize(processed)
    }

    fun applyGradients(text: String): String {
        val matcher = GRADIENT_PATTERN.matcher(text)
        if (!matcher.find()) return text

        val sb = StringBuffer()
        do {
            val colorHexesStr = matcher.group(1)
            val content = matcher.group(2)

            val colorHexes = mutableListOf<String>()
            val colorMatcher = HEX_COLOR_PATTERN.matcher(colorHexesStr)
            while (colorMatcher.find()) {
                colorHexes.add("#" + colorMatcher.group(1))
            }

            val interpolated = interpolateGradient(content, colorHexes)
            matcher.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(interpolated))
        } while (matcher.find())
        matcher.appendTail(sb)
        return sb.toString()
    }

    private fun interpolateGradient(text: String, hexColors: List<String>): String {
        if (hexColors.isEmpty() || text.isEmpty()) return text
        if (hexColors.size == 1) {
            return "&#" + hexColors[0].removePrefix("#") + text
        }

        val colors = hexColors.mapNotNull { parseRgb(it) }
        if (colors.isEmpty()) return text
        if (colors.size == 1) {
            val c = colors[0]
            return String.format("&#%02x%02x%02x%s", c.r, c.g, c.b, text)
        }

        val sb = StringBuilder()
        val numChars = text.length
        val numSegments = colors.size - 1

        for (i in 0 until numChars) {
            val ch = text[i]
            val progress = if (numChars == 1) 0.0 else i.toDouble() / (numChars - 1)
            val scaled = progress * numSegments
            var seg = floor(scaled).toInt()
            if (seg >= numSegments) seg = numSegments - 1
            val segProgress = scaled - seg

            val c1 = colors[seg]
            val c2 = colors[seg + 1]

            val r = (c1.r + (c2.r - c1.r) * segProgress).roundToInt().coerceIn(0, 255)
            val g = (c1.g + (c2.g - c1.g) * segProgress).roundToInt().coerceIn(0, 255)
            val b = (c1.b + (c2.b - c1.b) * segProgress).roundToInt().coerceIn(0, 255)

            sb.append(String.format("&#%02x%02x%02x%c", r, g, b, ch))
        }
        return sb.toString()
    }

    private data class Rgb(val r: Int, val g: Int, val b: Int)

    private fun parseRgb(hex: String): Rgb? {
        val clean = hex.removePrefix("#")
        if (clean.length != 6) return null
        return try {
            val rgb = clean.toInt(16)
            Rgb((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF)
        } catch (_: Exception) {
            null
        }
    }
}
