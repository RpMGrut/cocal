package me.delyfss.cocal.menu.runtime

import me.delyfss.cocal.menu.MenuHolder
import me.delyfss.cocal.menu.config.MenuType
import me.delyfss.cocal.menu.context.MenuContext
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory

/**
 * Builds the actual Bukkit [Inventory] for a compiled menu + context. Called
 * both when opening a menu and when refreshing / paging / scrolling.
 */
class MenuRenderer(private val itemBuilder: ItemBuilder) {

    private val miniMessage = MiniMessage.miniMessage()

    fun render(compiled: CompiledMenu, context: MenuContext): Inventory {
        val holder = MenuHolder(compiled, context.session)
        val type = compiled.config.type
        val title = miniMessage.deserialize(compiled.config.name.ifEmpty { " " })

        val inventory = if (type.usesChestSize) {
            val requestedSize = compiled.config.size
            val size = if (MenuType.isValidChestSize(requestedSize)) requestedSize else 27
            Bukkit.createInventory(holder, size, title)
        } else {
            Bukkit.createInventory(holder, type.inventoryType, title)
        }
        holder.attach(inventory)

        fillStaticItems(compiled, context, inventory)
        fillPaginatedItems(compiled, context, inventory)

        return inventory
    }

    private fun fillStaticItems(compiled: CompiledMenu, context: MenuContext, inventory: Inventory) {
        compiled.shapeSlots.forEach { (slot, key) ->
            if (slot >= inventory.size) return@forEach
            val compiledItem = compiled.compiledItems[key] ?: return@forEach
            inventory.setItem(slot, itemBuilder.build(compiledItem.config, context))
        }
    }

    /**
     * Writes a compiled PLAYER-type menu directly into the player's real
     * inventory. Used instead of [render] when [MenuType.PLAYER] is selected,
     * because the player inventory IS the menu surface and there's no
     * separate Bukkit inventory to create. The original contents must be
     * snapshot by the caller before invoking this method.
     */
    fun renderIntoPlayer(compiled: CompiledMenu, context: MenuContext, player: Player) {
        val inventory = player.inventory
        // Clear only the storage slots we may target (0..35). Armor and offhand
        // belong to the saved snapshot and are restored on close, not here.
        for (slot in 0..35) inventory.setItem(slot, null)

        compiled.shapeSlots.forEach { (shapeSlot, symbol) ->
            val playerSlot = PlayerMenuSlots.shapeToInventory(shapeSlot)
            if (playerSlot < 0) return@forEach
            val compiledItem = compiled.compiledItems[symbol] ?: return@forEach
            inventory.setItem(playerSlot, itemBuilder.build(compiledItem.config, context))
        }

        // Pagination for PLAYER type uses the same slot list as chest menus,
        // but the ints refer to shape slots and get translated the same way.
        val pagination = compiled.config.pagination
        val source = PageSourceRegistry.get(compiled.id)
        if (pagination != null && source != null) {
            val total = source.size(context)
            val pageSize = pagination.slots.size.coerceAtLeast(1)
            val totalPages = ((total + pageSize - 1) / pageSize).coerceAtLeast(1)
            val page = context.session.page.coerceIn(0, totalPages - 1)
            val start = page * pageSize
            pagination.slots.forEachIndexed { index, shapeSlot ->
                val playerSlot = PlayerMenuSlots.shapeToInventory(shapeSlot)
                if (playerSlot < 0) return@forEachIndexed
                val itemIndex = start + index
                if (itemIndex >= total) {
                    inventory.setItem(playerSlot, null)
                    return@forEachIndexed
                }
                val itemConfig = source.itemAt(itemIndex, context)
                inventory.setItem(playerSlot, itemBuilder.build(itemConfig, context))
            }
        }

        player.updateInventory()
    }

    private fun fillPaginatedItems(compiled: CompiledMenu, context: MenuContext, inventory: Inventory) {
        val pagination = compiled.config.pagination ?: return
        val source = PageSourceRegistry.get(compiled.id) ?: return
        val totalItems = source.size(context)
        val pageSize = pagination.slots.size.coerceAtLeast(1)
        val totalPages = ((totalItems + pageSize - 1) / pageSize).coerceAtLeast(1)
        val page = context.session.page.coerceIn(0, totalPages - 1)
        val start = page * pageSize
        pagination.slots.forEachIndexed { index, slot ->
            val itemIndex = start + index
            if (itemIndex >= totalItems) {
                inventory.setItem(slot, null)
                return@forEachIndexed
            }
            val itemConfig = source.itemAt(itemIndex, context)
            inventory.setItem(slot, itemBuilder.build(itemConfig, context))
        }
    }
}
