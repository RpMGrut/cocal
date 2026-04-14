package me.delyfss.cocal.menu.listener

import me.delyfss.cocal.menu.MenuHolder
import me.delyfss.cocal.menu.MenuService
import me.delyfss.cocal.menu.config.MenuType
import me.delyfss.cocal.menu.runtime.PlayerMenuSlots
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerSwapHandItemsEvent
import java.util.UUID

/**
 * Single source of click / drag / drop protection for every cocal menu. The
 * listener has two detection paths:
 *
 *   1. The "chest" path — any inventory whose holder is a [MenuHolder]. This
 *      covers CHEST, HOPPER, ANVIL, WORKBENCH, and every other non-player
 *      type. Non-cocal inventories are never touched.
 *   2. The "player" path — any player that has an active [MenuType.PLAYER]
 *      session in [MenuService]. This covers the case where the menu lives
 *      inside the player's own inventory and there is no top-inventory
 *      [MenuHolder] to key off of.
 *
 * A short debounce prevents double-click spam from executing the same click
 * handler twice within 75 ms — the same window DeluxeMenus uses.
 */
class MenuProtectionListener(private val service: MenuService) : Listener {

    private val lastClickAt = HashMap<UUID, Long>()
    private val clickDebounceMillis = 75L

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    fun onClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        val holder = event.inventory.holder

        if (holder is MenuHolder) {
            handleChestClick(event, player, holder)
            return
        }

        val session = service.sessionOf(player) ?: return
        val compiled = service.menu(session.menuId) ?: return
        if (compiled.config.type != MenuType.PLAYER) return

        // Cancel every click while a PLAYER menu is active — items belong to
        // the menu, not the player, and must not move. Dispatch the action
        // only when the click targeted the player's own inventory (the menu
        // surface), not a pulled-up external inventory.
        event.isCancelled = true
        if (event.clickedInventory !== player.inventory) return

        if (!debounce(player)) return

        val shapeSlot = PlayerMenuSlots.inventoryToShape(event.slot)
        if (shapeSlot < 0) return
        val symbol = compiled.shapeSlots[shapeSlot] ?: return
        val item = compiled.compiledItems[symbol] ?: return

        service.dispatchItemClick(player, event.click, compiled, session, item)
    }

    private fun handleChestClick(event: InventoryClickEvent, player: Player, holder: MenuHolder) {
        event.isCancelled = true
        if (!debounce(player)) return

        val clickedSlot = event.rawSlot
        if (clickedSlot !in 0 until event.inventory.size) return

        val symbol = holder.compiled.shapeSlots[clickedSlot] ?: return
        val item = holder.compiled.compiledItems[symbol] ?: return

        service.dispatchItemClick(player, event.click, holder.compiled, holder.session, item)
    }

    private fun debounce(player: Player): Boolean {
        val now = System.currentTimeMillis()
        val previous = lastClickAt[player.uniqueId]
        if (previous != null && now - previous < clickDebounceMillis) return false
        lastClickAt[player.uniqueId] = now
        return true
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    fun onDrag(event: InventoryDragEvent) {
        val player = event.whoClicked as? Player ?: return
        val holder = event.inventory.holder
        if (holder is MenuHolder) {
            event.isCancelled = true
            return
        }
        if (isInPlayerMenu(player)) {
            event.isCancelled = true
        }
    }

    @EventHandler
    fun onClose(event: InventoryCloseEvent) {
        val holder = event.inventory.holder
        if (holder !is MenuHolder) return
        val player = event.player as? Player ?: return
        service.handleClose(player, holder)
    }

    @EventHandler
    fun onDrop(event: PlayerDropItemEvent) {
        val player = event.player
        val open = player.openInventory.topInventory.holder
        if (open is MenuHolder) {
            event.isCancelled = true
            return
        }
        if (isInPlayerMenu(player)) {
            event.isCancelled = true
        }
    }

    @EventHandler
    fun onSwapHands(event: PlayerSwapHandItemsEvent) {
        // Prevent the F key from moving menu items into the offhand slot.
        if (isInPlayerMenu(event.player)) {
            event.isCancelled = true
        }
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        lastClickAt.remove(event.player.uniqueId)
        service.handleQuit(event.player)
    }

    private fun isInPlayerMenu(player: Player): Boolean {
        val session = service.sessionOf(player) ?: return false
        val compiled = service.menu(session.menuId) ?: return false
        return compiled.config.type == MenuType.PLAYER
    }
}
