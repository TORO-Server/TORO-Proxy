package net.torosaba.proxy

import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.connection.DisconnectEvent
import com.velocitypowered.api.event.connection.PostLoginEvent
import com.velocitypowered.api.proxy.ProxyServer
import org.slf4j.Logger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * いまつながっているプレイヤーの IP を toro-map-web に知らせる (assets.torosaba.net が、MarumaSign の Mod の読み込みかを確かめるのに使う)。
 * 入口の Velocity が見ている接続元なので、Paper の再起動中に limbo で待っている人も含む。
 * 人の出入りがあったとき (イベント駆動) に差分を検知して送信する。接続内容に変化がない場合は無駄な送信を行わない。
 * IP は内部のネットワークで送るだけで、ここではログにも残さない。
 */
class ClientReporter(
    private val proxy: ProxyServer,
    private val logger: Logger,
    private val config: ProxyConfig,
    private val plugin: Any,
) {
    // HTTP/2 だと http:// に Upgrade: h2c を付けて送り、map-web (Nitro) が WebSocket への切り替えとして扱って断るので、HTTP/1.1 にする
    private val http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).build()
    private val pending = AtomicBoolean(false)
    @Volatile private var lastSentIps: Set<String>? = null
    @Volatile private var lastWarn = 0L

    fun start() {
        // 起動時に初回同期を実施
        sendSoon()
        // clientsIntervalSeconds が指定されている場合のみ、フェイルセーフ用の定期同期をスケジュール
        if (config.clientsIntervalSeconds > 0) {
            proxy.scheduler.buildTask(plugin, Runnable { send(force = true) })
                .repeat(config.clientsIntervalSeconds, TimeUnit.SECONDS)
                .schedule()
        }
    }

    @Subscribe
    fun onLogin(event: PostLoginEvent) = sendSoon()

    @Subscribe
    fun onDisconnect(event: DisconnectEvent) = sendSoon()

    /** 続けて出入りがあっても、指定時間まとめて 1 回送る */
    private fun sendSoon(delay: Long = 1, unit: TimeUnit = TimeUnit.SECONDS) {
        if (pending.compareAndSet(false, true)) {
            proxy.scheduler.buildTask(plugin, Runnable { pending.set(false); send() }).delay(delay, unit).schedule()
        }
    }

    private fun send(force: Boolean = false) {
        val currentIps = proxy.allPlayers.mapNotNull { it.remoteAddress?.address?.hostAddress }.toSet()
        // 前回の送信成功時から IP の変化がなければ送信しない (無駄なポーリング通信を排除)
        if (!force && lastSentIps != null && lastSentIps == currentIps) {
            return
        }

        val body = currentIps.joinToString(",", "{\"ips\":[", "]}") { "\"" + it.replace("\\", "").replace("\"", "") + "\"" }
        val request = HttpRequest.newBuilder(URI.create(config.clientsEndpoint))
            .timeout(Duration.ofSeconds(5))
            .header("Content-Type", "application/json")
            .header("X-Toro-Token", config.clientsToken)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        http.sendAsync(request, HttpResponse.BodyHandlers.discarding()).whenComplete { res, e ->
            if (e != null || res.statusCode() != 204) {
                lastSentIps = null // 失敗時はキャッシュを破棄して次回確実に再試行
                warn(e?.message ?: "HTTP ${res.statusCode()}")
                // 一時的なエラーの場合、10秒後に再同期を試みる
                sendSoon(10, TimeUnit.SECONDS)
            } else {
                lastSentIps = currentIps
            }
        }
    }

    /** 地図のサーバーが止まっているあいだ、ログを埋めないよう 10 分に 1 回だけ */
    private fun warn(message: String) {
        val now = System.currentTimeMillis()
        if (now - lastWarn < 600_000) return
        lastWarn = now
        logger.warn("プレイヤーの IP を {} に知らせられませんでした: {}", config.clientsEndpoint, message)
    }
}
