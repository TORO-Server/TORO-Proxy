package net.torosaba.proxy

/** キックの理由から、サーバーの再起動 (または落ちた) によるものかを決める */
object KickPolicy {
    /**
     * 理由がないのは、接続が切れた・繋がらなかったとき。理由があるときは、Paper の停止時のメッセージと一致したときだけ再起動とみなす。
     * BAN や /kick の理由に同じ言葉が含まれていても、完全一致でなければ再起動とはみなさない
     */
    fun isRestart(reason: String?, restartMessages: Set<String>): Boolean {
        if (reason.isNullOrBlank()) return true
        return reason.trim() in restartMessages
    }
}
