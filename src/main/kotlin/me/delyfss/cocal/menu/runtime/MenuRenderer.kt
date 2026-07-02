package me.delyfss.cocal.menu.runtime

import me.delyfss.cocal.menu.MenuHolder
import me.delyfss.cocal.menu.action.ActionContext
import me.delyfss.cocal.menu.config.MenuType
import me.delyfss.cocal.menu.context.MenuContext
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.inventory.Inventory

/**
 * Builds the actual Bukkit [Inventory] for a compiled menu + context. Called
 * both when opening a menu and when refreshing / paging / scrolling.
 */
class MenuRenderer(private val itemBuilder: ItemBuilder) {

    private val miniMessage = MiniMessage.miniMessage()

    /** Resolved pagination state for one render pass. */
    private data class PageState(val total: Int, val pageSize: Int, val totalPages: Int, val page: Int, val start: Int)

    fun render(compiled: CompiledMenu, context: MenuContext): Inventory {
        val holder = MenuHolder(compiled, context.session)
        val type = compiled.config.type
        val title = miniMessage.deserialize(resolveTitle(compiled, context))

        val inventory = if (type.usesChestSize) {
            val requestedSize = compiled.config.size
            val size = if (MenuType.isValidChestSize(requestedSize)) requestedSize else 27
            Bukkit.createInventory(holder, size, title)
        } else {
            Bukkit.createInventory(holder, type.inventoryType, title)
        }
        holder.attach(inventory)

        fillAll(inventory, compiled, context)
        return inventory
    }

    /**
     * Re-renders a compiled menu into an ALREADY-open inventory (the session's [MenuHolder]
     * inventory), avoiding a close+open cycle. Used by refresh / page nav / scroll / auto-update.
     */
    fun renderInto(inventory: Inventory, compiled: CompiledMenu, context: MenuContext) {
        inventory.clear()
        fillAll(inventory, compiled, context)
    }

    private fun fillAll(inventory: Inventory, compiled: CompiledMenu, context: MenuContext) {
        val state = pageStateFor(compiled, context)
        val itemContext = contextWithPage(context, compiled, state)
        fillStaticItems(compiled, itemContext, inventory)
        if (state != null) fillPaginatedItems(compiled, itemContext, inventory, state)
    }

    private fun fillStaticItems(compiled: CompiledMenu, context: MenuContext, inventory: Inventory) {
        compiled.shapeSlots.forEach { (slot, key) ->
            if (slot >= inventory.size) return@forEach
            val compiledItem = compiled.compiledItems[key] ?: return@forEach
            if (!passesView(compiledItem, context)) {
                inventory.setItem(slot, null)
                return@forEach
            }
            inventory.setItem(slot, itemBuilder.build(compiledItem.config, context))
        }
    }

    /**
     * Writes a compiled PLAYER-type menu directly into the player's real inventory. The original
     * contents must be snapshot by the caller before invoking this method.
     */
    fun renderIntoPlayer(compiled: CompiledMenu, context: MenuContext, player: Player) {
        val inventory = player.inventory
        for (slot in 0..35) inventory.setItem(slot, null)

        val state = pageStateFor(compiled, context)
        val itemContext = contextWithPage(context, compiled, state)

        compiled.shapeSlots.forEach { (shapeSlot, symbol) ->
            val playerSlot = PlayerMenuSlots.shapeToInventory(shapeSlot)
            if (playerSlot < 0) return@forEach
            val compiledItem = compiled.compiledItems[symbol] ?: return@forEach
            if (!passesView(compiledItem, itemContext)) return@forEach
            inventory.setItem(playerSlot, itemBuilder.build(compiledItem.config, itemContext))
        }

        val pagination = compiled.config.pagination
        val source = PageSourceRegistry.get(compiled.id)
        if (state != null && pagination != null && source != null) {
            pagination.slots.forEachIndexed { index, shapeSlot ->
                val playerSlot = PlayerMenuSlots.shapeToInventory(shapeSlot)
                if (playerSlot < 0) return@forEachIndexed
                val itemIndex = state.start + index
                if (itemIndex >= state.total) {
                    inventory.setItem(playerSlot, null)
                    return@forEachIndexed
                }
                val itemConfig = source.itemAt(itemIndex, itemContext)
                inventory.setItem(playerSlot, itemBuilder.build(itemConfig, itemContext))
            }
        }

        player.updateInventory()
    }

    private fun fillPaginatedItems(compiled: CompiledMenu, context: MenuContext, inventory: Inventory, state: PageState) {
        val pagination = compiled.config.pagination ?: return
        val source = PageSourceRegistry.get(compiled.id) ?: return
        pagination.slots.forEachIndexed { index, slot ->
            if (slot < 0 || slot >= inventory.size) return@forEachIndexed
            val itemIndex = state.start + index
            if (itemIndex >= state.total) {
                inventory.setItem(slot, null)
                return@forEachIndexed
            }
            val itemConfig = source.itemAt(itemIndex, context)
            inventory.setItem(slot, itemBuilder.build(itemConfig, context))
        }
    }

    /** Evaluates a static item's [CompiledItem.viewRequirements]; failing items are not rendered. */
    private fun passesView(item: CompiledItem, context: MenuContext): Boolean {
        if (item.viewRequirements.isEmpty()) return true
        val actionContext = ActionContext(context.player, ClickType.LEFT, context)
        return item.viewRequirements.all { runCatching { it.test(actionContext) }.getOrElse { false } }
    }

    /** Menu title resolved through the same string-placeholder + page-placeholder pass as items. */
    private fun resolveTitle(compiled: CompiledMenu, context: MenuContext): String {
        val raw = compiled.config.name.ifEmpty { " " }
        val state = runCatching { peekPageState(compiled, context) }.getOrNull()
        val placeholders = if (state != null) {
            val pagination = compiled.config.pagination!!
            context.stringPlaceholders + mapOf(
                pagination.currentPlaceholder to (state.page + 1).toString(),
                pagination.totalPlaceholder to state.totalPages.toString()
            )
        } else context.stringPlaceholders
        if (placeholders.isEmpty()) return raw
        var result = raw
        placeholders.forEach { (k, v) -> result = result.replace("<$k>", v).replace("%$k%", v) }
        return result
    }

    private fun pageStateFor(compiled: CompiledMenu, context: MenuContext): PageState? {
        val state = peekPageState(compiled, context) ?: run {
            context.session.pageCount = 1
            return null
        }
        context.session.page = state.page      // clamp stored page so navigation never drifts out of range
        context.session.pageCount = state.totalPages
        return state
    }

    /** Computes pagination state WITHOUT mutating the session (used by the title pass too). */
    private fun peekPageState(compiled: CompiledMenu, context: MenuContext): PageState? {
        val pagination = compiled.config.pagination ?: return null
        val source = PageSourceRegistry.get(compiled.id) ?: return null
        val total = source.size(context)
        val pageSize = pagination.slots.size.coerceAtLeast(1)
        val totalPages = ((total + pageSize - 1) / pageSize).coerceAtLeast(1)
        val page = context.session.page.coerceIn(0, totalPages - 1)
        return PageState(total, pageSize, totalPages, page, page * pageSize)
    }

    private fun contextWithPage(context: MenuContext, compiled: CompiledMenu, state: PageState?): MenuContext {
        val pagination = compiled.config.pagination ?: return context
        if (state == null) return context
        return context.copy(
            stringPlaceholders = context.stringPlaceholders + mapOf(
                pagination.currentPlaceholder to (state.page + 1).toString(),
                pagination.totalPlaceholder to state.totalPages.toString()
            )
        )
    }
}
