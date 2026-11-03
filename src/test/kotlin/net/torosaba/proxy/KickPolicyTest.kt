package net.torosaba.proxy

import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KickPolicyTest {
    private val messages = setOf("Server closed", "Server is restarting")

    @Test
    fun `理由なしの切断は再起動とみなす`() {
        assertTrue(KickPolicy.isRestart(null, messages))
        assertTrue(KickPolicy.isRestart("  ", messages))
    }

    @Test
    fun `Paper の停止メッセージは再起動とみなす`() {
        assertTrue(KickPolicy.isRestart("Server closed", messages))
        assertTrue(KickPolicy.isRestart("Server is restarting\n", messages))
    }

    @Test
    fun `BAN や kick は再起動とみなさない`() {
        assertFalse(KickPolicy.isRestart("You are banned from this server.", messages))
        assertFalse(KickPolicy.isRestart("Kicked by an operator.", messages))
        assertFalse(KickPolicy.isRestart("Server closed for you: griefing", messages))
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
