package me.delyfss.cocal.menu.runtime

import me.delyfss.cocal.menu.action.Action
import me.delyfss.cocal.menu.config.MenuConfig
import me.delyfss.cocal.menu.config.MenuItemConfig
import me.delyfss.cocal.menu.requirement.Requirement
import org.bukkit.event.inventory.ClickType

/**
 * A [MenuConfig] with all string-encoded actions and requirements already
 * resolved into executables. Built once per menu file (or whenever the file
 * is reloaded) and reused for every player view.
 */
class CompiledMenu(
    val id: String,
    val config: MenuConfig,
    val shapeSlots: Map<Int, Char>,
    val compiledItems: Map<Char, CompiledItem>,
    val openActions: List<Action>,
    val closeActions: List<Action>
)

class CompiledItem(
    val key: Char,
    val config: MenuItemConfig,
    /** General fallback — runs when a more specific list is empty. */
    val actions: List<Action>,
    val leftActions: List<Action>,
    val rightActions: List<Action>,
    val shiftLeftActions: List<Action>,
    val shiftRightActions: List<Action>,
    val middleActions: List<Action>,
    val denyActions: List<Action>,
    val requirements: List<Requirement>
) {
    /**
     * Returns the action list that should run for [clickType], following
     * DeluxeMenus semantics: specific list wins if non-empty, otherwise the
     * general [actions] fallback.
     */
    fun actionsFor(clickType: ClickType): List<Action> {
        val specific = when (clickType) {
            ClickType.LEFT -> leftActions
            ClickType.RIGHT -> rightActions
            ClickType.SHIFT_LEFT -> shiftLeftActions
            ClickType.SHIFT_RIGHT -> shiftRightActions
            ClickType.MIDDLE -> middleActions
            else -> emptyList()
        }
        return if (specific.isNotEmpty()) specific else actions
    }
}
