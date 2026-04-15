package me.delyfss.cocal.menu.config

import org.bukkit.Material

/**
 * Declarative description of one menu item. Matches the DeluxeMenus item
 * options set: a general action list (fallback) plus five per-click-type
 * lists for LEFT / RIGHT / SHIFT_LEFT / SHIFT_RIGHT / MIDDLE.
 *
 * Semantics on click (identical to DeluxeMenus):
 * 1. If the click type has its own list and it is non-empty, **only** that
 *    list runs.
 * 2. Otherwise, [actions] runs as the fallback.
 * 3. [denyActions] runs instead of the chosen list when a click requirement
 *    fails.
 *
 * SHIFT_LEFT does NOT fall back to leftActions — it falls back directly to
 * [actions]. Same for SHIFT_RIGHT.
 */
data class MenuItemConfig(
    val type: Material = Material.AIR,
    val name: String = "",
    val lore: List<String> = emptyList(),
    val amount: Int = 1,
    val customModelData: Int = 0,
    val glow: Boolean = false,
    val actions: List<String> = emptyList(),
    val leftActions: List<String> = emptyList(),
    val rightActions: List<String> = emptyList(),
    val shiftLeftActions: List<String> = emptyList(),
    val shiftRightActions: List<String> = emptyList(),
    val middleActions: List<String> = emptyList(),
    val clickRequirements: List<String> = emptyList(),
    val denyActions: List<String> = emptyList()
)
