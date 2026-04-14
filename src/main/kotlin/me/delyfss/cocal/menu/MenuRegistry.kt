package me.delyfss.cocal.menu

import me.delyfss.cocal.menu.runtime.CompiledMenu
import java.util.concurrent.ConcurrentHashMap

class MenuRegistry {
    private val menus = ConcurrentHashMap<String, CompiledMenu>()

    fun register(compiled: CompiledMenu) {
        menus[compiled.id] = compiled
    }

    fun unregister(id: String) {
        menus.remove(id)
    }

    fun get(id: String): CompiledMenu? = menus[id]

    fun ids(): Set<String> = menus.keys.toSet()

    fun clear() = menus.clear()
}
