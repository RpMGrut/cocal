package me.delyfss.cocal

import me.delyfss.cocal.cache.RedisConfig
import me.delyfss.cocal.cache.RedisService
import me.delyfss.cocal.database.DatabaseConfig
import me.delyfss.cocal.database.DatabaseService
import me.delyfss.cocal.menu.MenuService
import org.bukkit.plugin.ServicePriority
import org.bukkit.plugin.java.JavaPlugin

/**
 * Optional runnable plugin shell that wires up cocal's shared services and
 * exposes them via Bukkit's services manager. Downstream plugins can keep
 * depending on cocal as a plain library (no plugin.yml needed on their side);
 * this entry point exists so servers can drop one cocal.jar into `plugins/`
 * and every plugin on the server shares the same menu listener, DB pool, and
 * Redis client.
 */
class CocalPlugin : JavaPlugin() {

    private var menuService: MenuService? = null
    private var databaseService: DatabaseService? = null
    private var redisService: RedisService? = null

    override fun onEnable() {
        saveDefaultConfigIfNeeded()
        val coreConfig = loadCoreConfig()

        val menus = MenuService(this).also { it.enable() }
        menuService = menus
        server.servicesManager.register(MenuService::class.java, menus, this, ServicePriority.Normal)

        if (coreConfig.database.enabled) {
            val database = DatabaseService(coreConfig.database.toConfig(), logger)
            runCatching { database.start() }
                .onFailure { logger.severe("Failed to start database pool: ${it.message}") }
            if (database.isStarted) {
                databaseService = database
                server.servicesManager.register(DatabaseService::class.java, database, this, ServicePriority.Normal)
            }
        }

        val redis = RedisService(coreConfig.redis.toConfig(), logger)
        redis.start()
        redisService = redis
        server.servicesManager.register(RedisService::class.java, redis, this, ServicePriority.Normal)

        @Suppress("DEPRECATION")
        val version = description.version
        logger.info("cocal v$version enabled")
    }

    override fun onDisable() {
        menuService?.disable()
        databaseService?.stop()
        redisService?.stop()
        menuService = null
        databaseService = null
        redisService = null
    }

    private fun saveDefaultConfigIfNeeded() {
        val file = java.io.File(dataFolder, "core.conf")
        if (!file.exists()) {
            dataFolder.mkdirs()
            file.writeText(DEFAULT_CORE_CONFIG.trimIndent())
            logger.info("Created default cocal core config at ${file.path}")
        }
    }

    private fun loadCoreConfig(): CocalCoreConfig {
        return runCatching {
            Config(
                folder = dataFolder,
                fileName = "core.conf",
                prototype = CocalCoreConfig()
            ).load()
        }.getOrElse { ex ->
            logger.warning("Failed to load core config, falling back to defaults: ${ex.message}")
            CocalCoreConfig()
        }
    }

    companion object {
        private val DEFAULT_CORE_CONFIG = """
            database {
              enabled = false
              driver = "SQLITE"
              url = "plugins/cocal/cocal.db"
              user = ""
              password = ""
              maximum-pool-size = 10
              minimum-idle = 2
            }

            redis {
              enabled = false
              uri = "redis://localhost:6379"
              connection-timeout-millis = 5000
              client-name = "cocal"
            }
        """
    }
}

data class CocalCoreConfig(
    val database: DatabaseSection = DatabaseSection(),
    val redis: RedisSection = RedisSection()
)

data class DatabaseSection(
    val enabled: Boolean = false,
    val driver: me.delyfss.cocal.database.DatabaseDriver = me.delyfss.cocal.database.DatabaseDriver.SQLITE,
    val url: String = "plugins/cocal/cocal.db",
    val user: String = "",
    val password: String = "",
    val maximumPoolSize: Int = 10,
    val minimumIdle: Int = 2
) {
    fun toConfig(): DatabaseConfig = DatabaseConfig(
        driver = driver,
        url = url,
        user = user,
        password = password,
        maximumPoolSize = maximumPoolSize,
        minimumIdle = minimumIdle
    )
}

data class RedisSection(
    val enabled: Boolean = false,
    val uri: String = "redis://localhost:6379",
    val connectionTimeoutMillis: Long = 5_000L,
    val clientName: String = "cocal"
) {
    fun toConfig(): RedisConfig = RedisConfig(
        enabled = enabled,
        uri = uri,
        connectionTimeoutMillis = connectionTimeoutMillis,
        clientName = clientName
    )
}
