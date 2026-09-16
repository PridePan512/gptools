package com.example.gptest.data

import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

class HistoryAccessBlockedException(val retryAt: Long) : IOException(
    "历史行情接口限流或安全拦截，已暂停请求。请在 " +
        DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(retryAt)) + " 后手动重试；服务端可能尚未解除限制。"
)

/** Shared by scanning, details and MA monitoring; persisted before reporting a block. */
class HistoryAccessGate(private val now: () -> Long = System::currentTimeMillis) {
    private var until = 0L
    private var persist: (Long) -> Unit = {}

    @Synchronized fun restore(value: Long, save: (Long) -> Unit) {
        until = maxOf(until, value)
        persist = save
    }

    @Synchronized fun blocked(): HistoryAccessBlockedException? =
        if (until > now()) HistoryAccessBlockedException(until) else null

    @Synchronized fun checkResponse(status: Int, body: String, retryAfter: String?) {
        if (status != 429 && status != 403 && !body.contains("waf.tencent.com", true)) return
        val time = now()
        val requested = retryAfter?.trim()?.let { raw ->
            raw.toLongOrNull()?.takeIf { it >= 0 && it <= (Long.MAX_VALUE - time) / 1000 }
                ?.let { time + it * 1000 }
                ?: runCatching { ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull()
        } ?: 0L
        // Concurrent responses from the same incident do not keep extending the default cooldown.
        until = maxOf(if (until > time) until else time + 15 * 60_000L, requested)
        persist(until)
        throw HistoryAccessBlockedException(until)
    }
}

object HistoryAccess {
    val gate = HistoryAccessGate()
}
