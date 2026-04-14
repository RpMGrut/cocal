package me.delyfss.cocal.menu.action

import me.delyfss.cocal.menu.context.MenuContext
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType

/**
 * Executable action attached to a menu item. Created once per config entry and
 * invoked on every matching click. Implementations must not throw — a failure
 * in one action must not prevent others from running.
 */
interface Action {
    fun run(context: ActionContext)
}

data class ActionContext(
    val player: Player,
    val clickType: ClickType,
    val menu: MenuContext
)

/**
 * Factory for a single action tag. Registered once at plugin startup and looked
 * up by [ActionParser] every time a menu is loaded.
 */
interface ActionFactory {
    val tag: String
    fun create(argument: String): Action
}
