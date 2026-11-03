package net.torosaba.proxy

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class PingTest {

    @Test
    fun testPingFormatterGradients() {
        val input = "%gradient#bea1a5#be5f6c%アイコンが見沼になるだけです..%gradient%"
        val applied = PingFormatter.applyGradients(input)
        println("APPLIED: $applied")
        assertFalse(applied.contains("%gradient"))
        assertTrue(applied.contains("&#bea1a5"), "Expected &#bea1a5 in $applied")
        assertTrue(applied.contains("&#be5f6c"), "Expected &#be5f6c in $applied")
        assertTrue(applied.contains("見"))
        assertTrue(applied.contains("沼"))

        val comp = PingFormatter.format(input)
        val plain = PlainTextComponentSerializer.plainText().serialize(comp)
        assertEquals("アイコンが見沼になるだけです..", plain)
    }

    @Test
    fun testPingFormatterPlaceholders() {
        val input = "&b%online%/ %max%"
        val comp = PingFormatter.format(input, mapOf("%online%" to "42", "%max%" to "100"))
        val plain = PlainTextComponentSerializer.plainText().serialize(comp)
        assertEquals("42/ 100", plain)
    }

    @Test
    fun testPingConfigMatching(@TempDir tempDir: Path) {
        val config = PingConfig.load(tempDir)

        // mc.marumasa.dev
        val mcConfig = config.findHostConfig("mc.marumasa.dev")
        assertEquals("1.0.0から99.99.99", mcConfig.versionName)
        assertEquals("-1", mcConfig.versionProtocol)
        assertEquals(listOf("server-icon-3.0.png"), mcConfig.favicon)

        // marumasa.dev
        val marumasaConfig = config.findHostConfig("marumasa.dev")
        assertEquals("1.99.9", marumasaConfig.versionName)
        assertEquals(50000, marumasaConfig.playersMax)
        assertEquals("1000..40000", marumasaConfig.playersOnline)

        // torosaba.net
        val torosabaConfig = config.findHostConfig("torosaba.net")
        assertEquals("1.12.2-26.3", torosabaConfig.versionName)
        assertTrue(torosabaConfig.favicon.contains("toro.png"))
        assertTrue(torosabaConfig.favicon.contains("toro-inverse.png"))

        // minuma.torosaba.net
        val minumaConfig = config.findHostConfig("minuma.torosaba.net")
        assertEquals(listOf("minuma-nose.png"), minumaConfig.favicon)
        assertTrue(minumaConfig.description.first().contains("MINUMA"))

        // 未知のドメイン -> default
        val unknownConfig = config.findHostConfig("unknown.domain.org")
        assertEquals("1.12.2-26.3", unknownConfig.versionName)
        assertEquals(listOf("toro.png"), unknownConfig.favicon)
    }
}
