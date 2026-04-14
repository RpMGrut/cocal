package me.delyfss.cocal.menu.context

import net.kyori.adventure.text.Component
import org.bukkit.entity.Player

/**
 * Data passed to action handlers and item renderers. Represents the live state
 * of a single menu view — never store this across ticks, always fetch fresh
 * from the service.
 */
data class MenuContext(
    val player: Player,
    val menuId: String,
    val session: MenuSession,
    val stringPlaceholders: Map<String, String> = emptyMap(),
    val componentPlaceholders: Map<String, Component> = emptyMap()
)
