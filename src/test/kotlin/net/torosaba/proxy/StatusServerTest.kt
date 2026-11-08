package net.torosaba.proxy

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Properties

class StatusServerTest {
    @Test
    fun `ステータス JSON の形式`() {
        assertEquals("{\"online\":0,\"epoch\":1794155059}", StatusJson.build(0, 1794155059))
        assertEquals("{\"online\":3,\"epoch\":1}", StatusJson.build(3, 1))
    }

    @Test
    fun `ステータス設定の既定値`() {
        val config = ProxyConfig.from(Properties().apply {
            setProperty("main-servers", "toro")
        })
        assertTrue(config.statusEnabled)
        assertEquals("0.0.0.0", config.statusBind)
        assertEquals(18089, config.statusPort)
        assertTrue(config.onlineFileEnabled)
    }

    @Test
    fun `ステータス設定のパースとポートの clamp`() {
        val config = ProxyConfig.from(Properties().apply {
            setProperty("main-servers", "toro")
            setProperty("status-enabled", "false")
            setProperty("status-bind", "127.0.0.1")
            setProperty("status-port", "70000")
            setProperty("online-file-enabled", "false")
        })
        assertFalse(config.statusEnabled)
        assertEquals("127.0.0.1", config.statusBind)
        assertEquals(65535, config.statusPort)
        assertFalse(config.onlineFileEnabled)

        val low = ProxyConfig.from(Properties().apply {
            setProperty("main-servers", "toro")
            setProperty("status-port", "0")
        })
        assertEquals(1, low.statusPort)

        val invalid = ProxyConfig.from(Properties().apply {
            setProperty("main-servers", "toro")
            setProperty("status-enabled", "yes")
            setProperty("status-port", "abc")
        })
        assertTrue(invalid.statusEnabled)
        assertEquals(18089, invalid.statusPort)
    }
}
