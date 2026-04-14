package me.delyfss.cocal.cache

import io.lettuce.core.ClientOptions
import io.lettuce.core.RedisClient
import io.lettuce.core.RedisURI
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.async.RedisAsyncCommands
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection
import io.lettuce.core.pubsub.RedisPubSubAdapter
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Lettuce-backed cache service that talks to any Redis-compatible server —
 * Redis, Dragonfly, KeyDB, etc. No vendor lock-in: we only use the Redis
 * protocol. When [RedisConfig.enabled] is false or the initial connection
 * fails, the service transitions to a degraded "unavailable" state and all
 * operations become no-ops so downstream plugins can keep running.
 */
class RedisService(
    val config: RedisConfig,
    private val logger: Logger
) {
    @Volatile private var client: RedisClient? = null
    @Volatile private var mainConnection: StatefulRedisConnection<String, String>? = null
    @Volatile private var pubSubConnection: StatefulRedisPubSubConnection<String, String>? = null
    @Volatile private var available: Boolean = false

    val isAvailable: Boolean get() = available

    fun start() {
        if (!config.enabled) {
            logger.info("cocal Redis layer disabled in config — running in no-op mode")
            available = false
            return
        }
        try {
            val uri = RedisURI.create(config.uri).apply {
                timeout = Duration.ofMillis(config.connectionTimeoutMillis)
                clientName = config.clientName
            }
            val created = RedisClient.create(uri).apply {
                options = ClientOptions.builder()
                    .autoReconnect(true)
                    .build()
            }
            mainConnection = created.connect()
            pubSubConnection = created.connectPubSub()
            client = created
            available = true
            logger.info("cocal Redis pool connected to ${config.uri}")
        } catch (ex: Exception) {
            logger.log(Level.SEVERE, "Failed to connect to Redis at ${config.uri}: ${ex.message}", ex)
            available = false
        }
    }

    fun stop() {
        runCatching { mainConnection?.close() }
        runCatching { pubSubConnection?.close() }
        runCatching { client?.shutdown() }
        mainConnection = null
        pubSubConnection = null
        client = null
        available = false
    }

    // ---- key/value ----

    fun set(key: String, value: String): CompletableFuture<Boolean> {
        val commands = asyncCommands() ?: return CompletableFuture.completedFuture(false)
        return commands.set(key, value).toCompletableFuture().thenApply { it == "OK" }
    }

    fun setWithTtl(key: String, value: String, ttlSeconds: Long): CompletableFuture<Boolean> {
        val commands = asyncCommands() ?: return CompletableFuture.completedFuture(false)
        return commands.setex(key, ttlSeconds, value).toCompletableFuture().thenApply { it == "OK" }
    }

    fun get(key: String): CompletableFuture<String?> {
        val commands = asyncCommands() ?: return CompletableFuture.completedFuture(null)
        return commands.get(key).toCompletableFuture().thenApply { it }
    }

    fun delete(key: String): CompletableFuture<Long> {
        val commands = asyncCommands() ?: return CompletableFuture.completedFuture(0L)
        return commands.del(key).toCompletableFuture().thenApply { it ?: 0L }
    }

    fun expire(key: String, ttlSeconds: Long): CompletableFuture<Boolean> {
        val commands = asyncCommands() ?: return CompletableFuture.completedFuture(false)
        return commands.expire(key, ttlSeconds).toCompletableFuture().thenApply { it ?: false }
    }

    // ---- hash ----

    fun hset(key: String, field: String, value: String): CompletableFuture<Boolean> {
        val commands = asyncCommands() ?: return CompletableFuture.completedFuture(false)
        return commands.hset(key, field, value).toCompletableFuture().thenApply { it ?: false }
    }

    fun hget(key: String, field: String): CompletableFuture<String?> {
        val commands = asyncCommands() ?: return CompletableFuture.completedFuture(null)
        return commands.hget(key, field).toCompletableFuture().thenApply { it }
    }

    fun hdel(key: String, field: String): CompletableFuture<Long> {
        val commands = asyncCommands() ?: return CompletableFuture.completedFuture(0L)
        return commands.hdel(key, field).toCompletableFuture().thenApply { it ?: 0L }
    }

    // ---- set ----

    fun sadd(key: String, vararg members: String): CompletableFuture<Long> {
        val commands = asyncCommands() ?: return CompletableFuture.completedFuture(0L)
        return commands.sadd(key, *members).toCompletableFuture().thenApply { it ?: 0L }
    }

    fun srem(key: String, vararg members: String): CompletableFuture<Long> {
        val commands = asyncCommands() ?: return CompletableFuture.completedFuture(0L)
        return commands.srem(key, *members).toCompletableFuture().thenApply { it ?: 0L }
    }

    fun smembers(key: String): CompletableFuture<Set<String>> {
        val commands = asyncCommands() ?: return CompletableFuture.completedFuture(emptySet())
        return commands.smembers(key).toCompletableFuture().thenApply { it ?: emptySet() }
    }

    // ---- pub/sub ----

    fun publish(channel: String, message: String): CompletableFuture<Long> {
        val commands = asyncCommands() ?: return CompletableFuture.completedFuture(0L)
        return commands.publish(channel, message).toCompletableFuture().thenApply { it ?: 0L }
    }

    /**
     * Subscribes to [channel] and invokes [handler] for every message received
     * on the cocal connection. The returned token can be passed to [unsubscribe]
     * to stop receiving events.
     */
    fun subscribe(channel: String, handler: (String) -> Unit): SubscriptionToken? {
        val connection = pubSubConnection ?: return null
        val adapter = object : RedisPubSubAdapter<String, String>() {
            override fun message(ch: String?, msg: String?) {
                if (ch == channel && msg != null) {
                    runCatching { handler(msg) }
                        .onFailure { logger.warning("Redis subscriber for '$channel' failed: ${it.message}") }
                }
            }
        }
        connection.addListener(adapter)
        connection.async().subscribe(channel)
        return SubscriptionToken(channel, adapter)
    }

    fun unsubscribe(token: SubscriptionToken) {
        val connection = pubSubConnection ?: return
        connection.removeListener(token.listener)
        connection.async().unsubscribe(token.channel)
    }

    class SubscriptionToken internal constructor(
        val channel: String,
        internal val listener: RedisPubSubAdapter<String, String>
    )

    // ---- internals ----

    private fun asyncCommands(): RedisAsyncCommands<String, String>? {
        if (!available) return null
        return mainConnection?.async()
    }
}
