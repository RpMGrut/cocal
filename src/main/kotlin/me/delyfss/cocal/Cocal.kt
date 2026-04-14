package me.delyfss.cocal

import me.delyfss.cocal.cache.RedisService
import me.delyfss.cocal.database.DatabaseService
import me.delyfss.cocal.menu.MenuService
import org.bukkit.Bukkit

/**
 * Facade for consuming cocal's shared services from downstream plugins.
 * Every accessor looks up the service via Bukkit's ServicesManager, which is
 * populated by [CocalPlugin.onEnable] when the runnable cocal plugin is
 * installed on the server. Downstream plugins add a `depend` or `softdepend`
 * on `cocal` in their own plugin.yml and then call [menus], [database], or
 * [redis] during their own onEnable.
 */
object Cocal {

    val menus: MenuService
        get() = required(MenuService::class.java)

    val database: DatabaseService
        get() = required(DatabaseService::class.java)

    val redis: RedisService
        get() = required(RedisService::class.java)

    fun menusOrNull(): MenuService? = optional(MenuService::class.java)
    fun databaseOrNull(): DatabaseService? = optional(DatabaseService::class.java)
    fun redisOrNull(): RedisService? = optional(RedisService::class.java)

    private fun <T : Any> required(type: Class<T>): T {
        return optional(type) ?: error(
            "cocal ${type.simpleName} is not registered — install the cocal plugin or construct the service manually."
        )
    }

    private fun <T : Any> optional(type: Class<T>): T? {
        val manager = Bukkit.getServicesManager()
        return manager.load(type)
    }
}
