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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Lettuce-backed cache service for any Redis-compatible server (Redis, Dragonfly,
 * KeyDB, …). Connects in the background (never blocks the caller/main thread),
 * retries on failure, and degrades to a no-op "unavailable" state so downstream
 * plugins keep running when Redis is down or disabled.
 */
class RedisService(
    val config: RedisConfig,
    private val logger: Logger
) {
    @Volatile private var client: RedisClient? = null
    @Volatile private var mainConnection: StatefulRedisConnection<String, String>? = null
    @Volatile private var pubSubConnection: StatefulRedisPubSubConnection<String, String>? = null
    @Volatile private var available: Boolean = false
    @Volatile private var starting: Boolean = false
    @Volatile private var stopped: Boolean = false

    // Per-channel listener refcount so one plugin's unsubscribe doesn't kill the channel for others.
    private val exactListeners = ConcurrentHashMap<String, MutableSet<RedisPubSubAdapter<String, String>>>()
    private val patternListeners = ConcurrentHashMap<String, MutableSet<RedisPubSubAdapter<String, String>>>()

    private val scheduler = Executors.newSingleThreadScheduledExecutor { run ->
        Thread(run, "cocal-redis-${config.clientName}").apply { isDaemon = true }
    }

    val isAvailable: Boolean get() = available

    fun start() {
        if (!config.enabled) {
            logger.info("cocal Redis layer disabled in config — running in no-op mode")
            available = false
            return
        }
        if (client != null || starting) {
            logger.warning("RedisService.start() called twice — ignoring")
            return
        }
        starting = true
        scheduleConnect(0)
    }

    private fun scheduleConnect(attempt: Int) {
        if (stopped) return
        val delay = if (attempt == 0) 0L else minOf(config.reconnectDelayMillis * attempt, 60_000L)
        scheduler.schedule({ tryConnect(attempt) }, delay, TimeUnit.MILLISECONDS)
    }

    private fun tryConnect(attempt: Int) {
        if (stopped) return
        try {
            val uri = RedisURI.create(config.uri).apply {
                timeout = Duration.ofMillis(config.connectionTimeoutMillis)
                clientName = config.clientName
            }
            val created = RedisClient.create(uri).apply {
                options = ClientOptions.builder()
                    .autoReconnect(true)
                    .requestQueueSize(config.requestQueueSize.coerceAtLeast(1))
                    .build()
            }
            mainConnection = created.connect()
            pubSubConnection = created.connectPubSub()
            client = created
            available = true
            starting = false
            logger.info("cocal Redis pool connected to ${config.uri}")
        } catch (ex: Exception) {
            available = false
            if (config.reconnect && !stopped) {
                logger.warning("Redis connect attempt ${attempt + 1} to ${config.uri} failed (${ex.message}); retrying")
                scheduleConnect(attempt + 1)
            } else {
                logger.log(Level.SEVERE, "Failed to connect to Redis at ${config.uri}: ${ex.message}", ex)
                starting = false
            }
        }
    }

    fun stop() {
        stopped = true
        runCatching { mainConnection?.close() }
        runCatching { pubSubConnection?.close() }
        runCatching { client?.shutdown() }
        runCatching { scheduler.shutdownNow() }
        mainConnection = null
        pubSubConnection = null
        client = null
        available = false
        exactListeners.clear()
        patternListeners.clear()
    }

    private fun key(raw: String): String = if (config.keyPrefix.isEmpty()) raw else config.keyPrefix + raw

    // ---- key/value ----

    fun set(key: String, value: String): CompletableFuture<Boolean> {
        val commands = asyncCommands() ?: return CompletableFuture.completedFuture(false)
        return commands.set(key(key), value).toCompletableFuture().thenApply { it == "OK" }
    }

    fun setWithTtl(key: String, value: String, ttlSeconds: Long): CompletableFuture<Boolean> {
        val commands = asyncCommands() ?: return CompletableFuture.completedFuture(false)
        return commands.setex(key(key), ttlSeconds, value).toCompletableFuture().thenApply { it == "OK" }
    }

    fun get(key: String): CompletableFuture<String?> {
        val commands = asyncCommands() ?: return CompletableFuture.completedFuture(null)
        return commands.get(key(key)).toCompletableFuture().thenApply { it }
    }

    fun delete(key: String): CompletableFuture<Long> {
        val commands = asyncCommands() ?: return CompletableFuture.completedFuture(0L)
        return commands.del(key(key)).toCompletableFuture().thenApply { it ?: 0L }
    }

    fun expire(key: String, ttlSeconds: Long): CompletableFuture<Boolean> {
        val commands = asyncCommands() ?: return CompletableFuture.completedFuture(false)
        return commands.expire(key(key), ttlSeconds).toCompletableFuture().thenApply { it ?: false }
    }

    // ---- typed value (caller supplies (de)serializer; JSON, protobuf, whatever) ----

    fun <T> setObject(key: String, value: T, serializer: (T) -> String): CompletableFuture<Boolean> =
        set(key, serializer(value))

    fun <T> getObject(key: String, deserializer: (String) -> T): CompletableFuture<T?> =
        get(key).thenApply { raw -> raw?.let { runCatching { deserializer(it) }.getOrNull() } }

    // ---- hash ----

    fun hset(key: String, field: String, value: String): CompletableFuture<Boolean> {
        val commands = asyncCommands() ?: return CompletableFuture.completedFuture(false)
        return commands.hset(key(key), field, value).toCompletableFuture().thenApply { it ?: false }
    }

    fun hget(key: String, field: String): CompletableFuture<String?> {
        val commands = asyncCommands() ?: return CompletableFuture.completedFuture(null)
        return commands.hget(key(key), field).toCompletableFuture().thenApply { it }
    }

    fun hdel(key: String, field: String): CompletableFuture<Long> {
        val commands = asyncCommands() ?: return CompletableFuture.completedFuture(0L)
        return commands.hdel(key(key), field).toCompletableFuture().thenApply { it ?: 0L }
    }

    // ---- set ----

    fun sadd(key: String, vararg members: String): CompletableFuture<Long> {
        val commands = asyncCommands() ?: return CompletableFuture.completedFuture(0L)
        return commands.sadd(key(key), *members).toCompletableFuture().thenApply { it ?: 0L }
    }

    fun srem(key: String, vararg members: String): CompletableFuture<Long> {
        val commands = asyncCommands() ?: return CompletableFuture.completedFuture(0L)
        return commands.srem(key(key), *members).toCompletableFuture().thenApply { it ?: 0L }
    }

    fun smembers(key: String): CompletableFuture<Set<String>> {
        val commands = asyncCommands() ?: return CompletableFuture.completedFuture(emptySet())
        return commands.smembers(key(key)).toCompletableFuture().thenApply { it ?: emptySet() }
    }

    // ---- pub/sub (channels are NOT key-prefixed; namespace them explicitly if needed) ----

    fun publish(channel: String, message: String): CompletableFuture<Long> {
        val commands = asyncCommands() ?: return CompletableFuture.completedFuture(0L)
        return commands.publish(channel, message).toCompletableFuture().thenApply { it ?: 0L }
    }

    /** Subscribes to an exact [channel]. The returned token stops only THIS listener on [unsubscribe]. */
    fun subscribe(channel: String, handler: (String) -> Unit): SubscriptionToken? {
        val connection = pubSubConnection ?: return null
        val adapter = object : RedisPubSubAdapter<String, String>() {
            override fun message(ch: String?, msg: String?) {
                if (ch == channel && msg != null) invokeSafely(channel, handler, msg)
            }
        }
        connection.addListener(adapter)
        val set = exactListeners.computeIfAbsent(channel) { ConcurrentHashMap.newKeySet() }
        val firstForChannel = set.isEmpty()
        set.add(adapter)
        if (firstForChannel) connection.async().subscribe(channel)
        return SubscriptionToken(channel, adapter, pattern = false)
    }

    /** Pattern subscribe (PSUBSCRIBE), e.g. `server:*:events`. */
    fun psubscribe(pattern: String, handler: (channel: String, message: String) -> Unit): SubscriptionToken? {
        val connection = pubSubConnection ?: return null
        val adapter = object : RedisPubSubAdapter<String, String>() {
            override fun message(pat: String?, ch: String?, msg: String?) {
                if (pat == pattern && ch != null && msg != null) {
                    runCatching { handler(ch, msg) }
                        .onFailure { logger.warning("Redis pattern subscriber '$pattern' failed: ${it.message}") }
                }
            }
        }
        connection.addListener(adapter)
        val set = patternListeners.computeIfAbsent(pattern) { ConcurrentHashMap.newKeySet() }
        val firstForPattern = set.isEmpty()
        set.add(adapter)
        if (firstForPattern) connection.async().psubscribe(pattern)
        return SubscriptionToken(pattern, adapter, pattern = true)
    }

    private fun invokeSafely(channel: String, handler: (String) -> Unit, msg: String) {
        runCatching { handler(msg) }
            .onFailure { logger.warning("Redis subscriber for '$channel' failed: ${it.message}") }
    }

    fun unsubscribe(token: SubscriptionToken) {
        val connection = pubSubConnection ?: return
        connection.removeListener(token.listener)
        val registry = if (token.pattern) patternListeners else exactListeners
        val set = registry[token.channel] ?: return
        set.remove(token.listener)
        if (set.isEmpty()) {
            registry.remove(token.channel)
            // Only unsubscribe from Redis when NO local listener remains for this channel/pattern.
            if (token.pattern) connection.async().punsubscribe(token.channel)
            else connection.async().unsubscribe(token.channel)
        }
    }

    class SubscriptionToken internal constructor(
        val channel: String,
        internal val listener: RedisPubSubAdapter<String, String>,
        internal val pattern: Boolean
    )

    // ---- internals ----

    private fun asyncCommands(): RedisAsyncCommands<String, String>? {
        if (!available) return null
        return mainConnection?.async()
    }
}
