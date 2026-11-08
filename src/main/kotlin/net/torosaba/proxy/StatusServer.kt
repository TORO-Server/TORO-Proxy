package net.torosaba.proxy

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.slf4j.Logger
import java.net.InetSocketAddress
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

/** ステータスエンドポイントの応答 JSON を作る。純粋関数なので単体テストで確かめる */
object StatusJson {
    fun build(online: Int, epoch: Long): String = "{\"online\":$online,\"epoch\":$epoch}"
}

/**
 * サーバー側のスクリプトが人数を聞くための HTTP サーバー (JDK 標準のみ、追加依存なし)。
 * 返すのは人数と時刻のみで、プレイヤー名等の情報は含めない。
 * 開始に失敗してもプラグイン自体は継続する (呼び出し側は応答が無いことを「不明」として扱う)。
 */
class StatusServer(
    private val logger: Logger,
    private val bind: String,
    private val port: Int,
    private val playerCount: () -> Int,
) {
    private var server: HttpServer? = null
    private val executor = Executors.newCachedThreadPool(ThreadFactory { runnable ->
        Thread(runnable, "toro-proxy-status-${counter.incrementAndGet()}").apply { isDaemon = true }
    })

    fun start() {
        try {
            val created = HttpServer.create(InetSocketAddress(bind, port), 0)
            created.createContext("/") { exchange -> handle(exchange) }
            created.executor = executor
            created.start()
            server = created
            logger.info("ステータスエンドポイントを開始しました: {}:{}", bind, port)
        } catch (e: Exception) {
            logger.error("ステータスエンドポイントを開始できませんでした ({}:{}): {}", bind, port, e.message)
        }
    }

    fun stop() {
        server?.stop(0)
        server = null
        executor.shutdownNow()
    }

    private fun handle(exchange: HttpExchange) {
        try {
            when {
                exchange.requestURI.path != "/status" -> exchange.sendResponseHeaders(404, -1)
                exchange.requestMethod != "GET" -> exchange.sendResponseHeaders(405, -1)
                else -> {
                    val body = StatusJson.build(playerCount(), Instant.now().epochSecond).toByteArray(Charsets.UTF_8)
                    exchange.responseHeaders.set("Content-Type", "application/json")
                    exchange.sendResponseHeaders(200, body.size.toLong())
                    exchange.responseBody.use { it.write(body) }
                }
            }
        } finally {
            exchange.close()
        }
    }

    private companion object {
        val counter = AtomicInteger(0)
    }
}
