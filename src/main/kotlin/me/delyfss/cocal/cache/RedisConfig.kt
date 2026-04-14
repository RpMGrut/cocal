package me.delyfss.cocal.cache

data class RedisConfig(
    val enabled: Boolean = false,
    val uri: String = "redis://localhost:6379",
    val connectionTimeoutMillis: Long = 5_000L,
    val clientName: String = "cocal"
)
