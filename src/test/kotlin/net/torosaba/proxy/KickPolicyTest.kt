package net.torosaba.proxy

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Properties

class KickPolicyTest {
    private val restartMessages = setOf("Server closed", "Server is restarting")

    @Test
    fun `再起動メッセージは limbo に送る`() {
        assertTrue(KickPolicy.isRestart("Server closed", restartMessages))
        assertTrue(KickPolicy.isRestart("Server is restarting", restartMessages))
    }

    @Test
    fun `理由なしは limbo に送る`() {
        assertTrue(KickPolicy.isRestart(null, restartMessages))
        assertTrue(KickPolicy.isRestart("", restartMessages))
        assertTrue(KickPolicy.isRestart("   ", restartMessages))
    }

    @Test
    fun `BAN や kick はそのまま切る`() {
        assertFalse(KickPolicy.isRestart("You are banned from this server", restartMessages))
        assertFalse(KickPolicy.isRestart("Kicked by an operator", restartMessages))
        assertFalse(KickPolicy.isRestart("Server is full!", restartMessages))
        assertFalse(KickPolicy.isRestart("You are not whitelisted on this server!", restartMessages))
        assertFalse(KickPolicy.isRestart("[Paper] Server closed", restartMessages))
    }

    @Test
    fun `設定の既定値と区切り`() {
        val config = ProxyConfig.from(Properties().apply {
            setProperty("main-servers", "toro | other")
            setProperty("restart-kick-messages", "Server closed|サーバーを再起動しています")
            setProperty("check-interval-seconds", "0")
        })
        assertEquals(listOf("toro", "other"), config.mainServers)
        assertEquals("limbo", config.limboServer)
        assertEquals(setOf("Server closed", "サーバーを再起動しています"), config.restartKickMessages)
        assertEquals(1, config.checkIntervalSeconds)
        assertEquals(0, config.clientsIntervalSeconds)
    }

    @Test
    fun `クライアント通知間隔の設定`() {
        val configWithZero = ProxyConfig.from(Properties().apply {
            setProperty("main-servers", "toro")
            setProperty("clients-interval-seconds", "0")
        })
        assertEquals(0, configWithZero.clientsIntervalSeconds)

        val configWithVal = ProxyConfig.from(Properties().apply {
            setProperty("main-servers", "toro")
            setProperty("clients-interval-seconds", "60")
        })
        assertEquals(60, configWithVal.clientsIntervalSeconds)
    }
}
