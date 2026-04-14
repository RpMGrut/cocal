package me.delyfss.cocal.menu.action

import java.util.concurrent.ConcurrentHashMap

/**
 * Central registry for action factories. Thread-safe so plugins can register
 * custom actions from their own `onEnable` without coordination.
 */
object Actions {
    private val factories = ConcurrentHashMap<String, ActionFactory>()

    fun register(factory: ActionFactory) {
        factories[factory.tag.lowercase()] = factory
    }

    fun unregister(tag: String) {
        factories.remove(tag.lowercase())
    }

    fun get(tag: String): ActionFactory? = factories[tag.lowercase()]

    fun registeredTags(): Set<String> = factories.keys.toSet()

    internal fun registerBuiltins(builtins: List<ActionFactory>) {
        builtins.forEach { register(it) }
    }
}
