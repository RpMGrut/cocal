package me.delyfss.cocal.database

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.io.File
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.logging.Logger
import javax.sql.DataSource

/**
 * Owns a HikariCP connection pool and provides helpers for running queries and
 * transactions (sync + async). One [DatabaseService] per data source — for a
 * shared-per-server pool, construct a single instance in the cocal core plugin
 * and expose it via Bukkit's services manager.
 */
class DatabaseService(
    val config: DatabaseConfig,
    private val logger: Logger
) {
    @Volatile private var hikari: HikariDataSource? = null

    // Reuses the connection already open on this thread so nested transaction/withConnection calls
    // don't grab a second pool connection (which deadlocks under SQLite's forced pool size of 1).
    private val activeConnection = ThreadLocal<Connection?>()

    private val asyncExecutor by lazy {
        val threads = config.maximumPoolSize.coerceAtLeast(2)
        Executors.newFixedThreadPool(threads) { run ->
            Thread(run, "cocal-db-${config.poolName}").apply { isDaemon = true }
        }
    }

    val isStarted: Boolean get() = hikari != null

    val dataSource: DataSource
        get() = hikari ?: error("DatabaseService has not been started yet")

    fun start() {
        if (hikari != null) {
            logger.warning("DatabaseService.start() called twice — ignoring")
            return
        }
        if (config.driver == DatabaseDriver.SQLITE) ensureSqliteParentDir()
        val hikariConfig = HikariConfig().apply {
            jdbcUrl = config.driver.buildUrl(config.url)
            config.driver.driverClassName?.let { driverClassName = it }
            if (config.user.isNotEmpty()) username = config.user
            if (config.password.isNotEmpty()) password = config.password
            maximumPoolSize = config.maximumPoolSize
            minimumIdle = config.minimumIdle
            connectionTimeout = config.connectionTimeoutMillis
            idleTimeout = config.idleTimeoutMillis
            maxLifetime = config.maxLifetimeMillis
            poolName = config.poolName
            // SQLite doesn't support multiple concurrent write connections; cap it.
            if (config.driver == DatabaseDriver.SQLITE) {
                maximumPoolSize = 1
                minimumIdle = 1
            }
        }
        hikari = HikariDataSource(hikariConfig)
        logger.info("cocal database pool '${config.poolName}' started (${config.driver})")
    }

    fun stop() {
        val active = hikari ?: return
        active.close()
        hikari = null
        runCatching { asyncExecutor.shutdown() }
    }

    private fun ensureSqliteParentDir() {
        val raw = config.url.removePrefix("jdbc:sqlite:")
        if (raw.isBlank() || raw == ":memory:") return
        File(raw).parentFile?.mkdirs()
    }

    // ---- core ----

    fun <T> withConnection(block: (Connection) -> T): T {
        activeConnection.get()?.let { return block(it) }
        val source = hikari ?: error("DatabaseService not started")
        return source.connection.use { conn ->
            activeConnection.set(conn)
            try {
                block(conn)
            } finally {
                activeConnection.remove()
            }
        }
    }

    fun <T> transaction(block: (Connection) -> T): T {
        // Already inside a connection/transaction on this thread → join it (outermost commits).
        activeConnection.get()?.let { return block(it) }
        val source = hikari ?: error("DatabaseService not started")
        val connection = source.connection
        activeConnection.set(connection)
        val previousAutoCommit = connection.autoCommit
        connection.autoCommit = false
        try {
            val result = block(connection)
            connection.commit()
            return result
        } catch (ex: Exception) {
            runCatching { connection.rollback() }
            throw ex
        } finally {
            runCatching { connection.autoCommit = previousAutoCommit }
            activeConnection.remove()
            runCatching { connection.close() }
        }
    }

    // ---- async (offloads blocking JDBC to a dedicated pool; never call from the main thread) ----

    fun <T> withConnectionAsync(block: (Connection) -> T): CompletableFuture<T> =
        CompletableFuture.supplyAsync({ withConnection(block) }, asyncExecutor)

    fun <T> transactionAsync(block: (Connection) -> T): CompletableFuture<T> =
        CompletableFuture.supplyAsync({ transaction(block) }, asyncExecutor)

    // ---- query / update helpers ----

    fun <T> query(sql: String, binder: (PreparedStatement) -> Unit = {}, mapper: (ResultSet) -> T): List<T> =
        withConnection { conn ->
            conn.prepareStatement(sql).use { ps ->
                binder(ps)
                ps.executeQuery().use { rs ->
                    val out = ArrayList<T>()
                    while (rs.next()) out.add(mapper(rs))
                    out
                }
            }
        }

    fun <T> queryOne(sql: String, binder: (PreparedStatement) -> Unit = {}, mapper: (ResultSet) -> T): T? =
        withConnection { conn ->
            conn.prepareStatement(sql).use { ps ->
                binder(ps)
                ps.executeQuery().use { rs -> if (rs.next()) mapper(rs) else null }
            }
        }

    fun update(sql: String, binder: (PreparedStatement) -> Unit = {}): Int =
        withConnection { conn ->
            conn.prepareStatement(sql).use { ps ->
                binder(ps)
                ps.executeUpdate()
            }
        }

    fun executeBatch(sql: String, rows: List<(PreparedStatement) -> Unit>): IntArray =
        transaction { conn ->
            conn.prepareStatement(sql).use { ps ->
                rows.forEach { bind -> bind(ps); ps.addBatch() }
                ps.executeBatch()
            }
        }

    fun <T> queryAsync(sql: String, binder: (PreparedStatement) -> Unit = {}, mapper: (ResultSet) -> T): CompletableFuture<List<T>> =
        CompletableFuture.supplyAsync({ query(sql, binder, mapper) }, asyncExecutor)

    fun updateAsync(sql: String, binder: (PreparedStatement) -> Unit = {}): CompletableFuture<Int> =
        CompletableFuture.supplyAsync({ update(sql, binder) }, asyncExecutor)

    // ---- migrations ----

    /** One ordered schema step for [migrate]; [version] must be strictly increasing per namespace. */
    class Migration(val version: Int, val apply: (Connection) -> Unit)

    /**
     * Applies pending [migrations] for [namespace] in a single transaction, tracking the current
     * version in a shared `cocal_schema_version` table so each step runs exactly once. Safe to call
     * on every startup. Returns the schema version after migrating.
     */
    fun migrate(namespace: String, migrations: List<Migration>): Int = transaction { conn ->
        conn.createStatement().use {
            it.executeUpdate(
                "CREATE TABLE IF NOT EXISTS cocal_schema_version " +
                    "(namespace VARCHAR(191) PRIMARY KEY, version INT NOT NULL)"
            )
        }
        var current = conn.prepareStatement("SELECT version FROM cocal_schema_version WHERE namespace = ?").use { ps ->
            ps.setString(1, namespace)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
        }
        migrations.sortedBy { it.version }.forEach { migration ->
            if (migration.version <= current) return@forEach
            migration.apply(conn)
            current = migration.version
        }
        val upsert = if (config.driver == DatabaseDriver.SQLITE) {
            "INSERT INTO cocal_schema_version (namespace, version) VALUES (?, ?) " +
                "ON CONFLICT(namespace) DO UPDATE SET version = excluded.version"
        } else {
            "INSERT INTO cocal_schema_version (namespace, version) VALUES (?, ?) " +
                "ON DUPLICATE KEY UPDATE version = VALUES(version)"
        }
        conn.prepareStatement(upsert).use { ps ->
            ps.setString(1, namespace)
            ps.setInt(2, current)
            ps.executeUpdate()
        }
        current
    }
}
