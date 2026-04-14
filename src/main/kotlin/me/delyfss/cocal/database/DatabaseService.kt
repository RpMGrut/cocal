package me.delyfss.cocal.database

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.sql.Connection
import java.util.logging.Logger
import javax.sql.DataSource

/**
 * Owns a HikariCP connection pool and provides small helpers for running
 * queries and transactions. One [DatabaseService] per data source — for a
 * shared-per-server pool, construct a single instance in the cocal core
 * plugin and expose it via Bukkit's services manager.
 */
class DatabaseService(
    val config: DatabaseConfig,
    private val logger: Logger
) {
    private var hikari: HikariDataSource? = null

    val isStarted: Boolean get() = hikari != null

    val dataSource: DataSource
        get() = hikari ?: error("DatabaseService has not been started yet")

    fun start() {
        if (hikari != null) {
            logger.warning("DatabaseService.start() called twice — ignoring")
            return
        }
        val hikariConfig = HikariConfig().apply {
            jdbcUrl = config.driver.buildUrl(config.url)
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
    }

    fun <T> withConnection(block: (Connection) -> T): T {
        val source = hikari ?: error("DatabaseService not started")
        return source.connection.use(block)
    }

    fun <T> transaction(block: (Connection) -> T): T {
        val source = hikari ?: error("DatabaseService not started")
        val connection = source.connection
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
            runCatching { connection.close() }
        }
    }
}
