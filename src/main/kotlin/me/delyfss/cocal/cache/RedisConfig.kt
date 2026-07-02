package me.delyfss.cocal.cache

data class RedisConfig(
    val enabled: Boolean = false,
    val uri: String = "redis://localhost:6379",
    val connectionTimeoutMillis: Long = 5_000L,
    val clientName: String = "cocal",
    /** Prepended to every key (not channels) so plugins sharing one Redis don't collide. */
    val keyPrefix: String = "",
    /** Bounds commands queued while disconnected (prevents unbounded memory growth during an outage). */
    val requestQueueSize: Int = 1_000,
    /** Retry connecting in the background if the initial connect fails. */
    val reconnect: Boolean = true,
    /** Delay between background reconnect attempts (capped backoff base). */
    val reconnectDelayMillis: Long = 5_000L
)
