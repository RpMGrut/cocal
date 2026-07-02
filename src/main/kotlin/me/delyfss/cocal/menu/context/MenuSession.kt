package me.delyfss.cocal.menu.context

import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/**
 * Per-player session state for a cocal menu. One session is active at a time
 * for each player; navigation actions (openmenu / back / refresh / scroll)
 * flip flags on this object and the service reacts after the current click
 * handler returns.
 */
class MenuSession(
    val player: Player,
    val menuId: String
) {
    var page: Int = 0
    var scrollOffset: Int = 0
    var closeRequested: Boolean = false
    var refreshRequested: Boolean = false
    var openMenuRequest: String? = null
    var backRequested: Boolean = false

    /**
     * String placeholders supplied to [me.delyfss.cocal.menu.MenuService.open] for this view.
     * Persisted here so refresh / paging / clicks / navigation re-render with the SAME context
     * (e.g. a `<town>` supplied on open stays resolved) instead of an empty map.
     */
    var placeholders: Map<String, String> = emptyMap()

    /** Total page count computed by the renderer for the current view; used to clamp page navigation. */
    var pageCount: Int = 1

    /** Stack of menu ids the player has navigated through — used by [back] action. */
    val history: ArrayDeque<String> = ArrayDeque()

    /**
     * Snapshot of the player's inventory contents taken when a PLAYER-type
     * menu is opened. Restored on close / quit / service disable. Null for
     * every other menu type.
     */
    var savedContents: Array<ItemStack?>? = null
}
