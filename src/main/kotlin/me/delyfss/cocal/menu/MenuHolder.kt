package me.delyfss.cocal.menu

import me.delyfss.cocal.menu.context.MenuSession
import me.delyfss.cocal.menu.runtime.CompiledMenu
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder

/**
 * Sentinel holder used to identify cocal-owned inventories. Every protection
 * listener short-circuits on `inventory.holder !is MenuHolder`, so regular
 * player chests / workbenches / etc. are never touched.
 */
class MenuHolder(
    val compiled: CompiledMenu,
    val session: MenuSession
) : InventoryHolder {
    private var backing: Inventory? = null

    fun attach(inventory: Inventory) {
        backing = inventory
    }

    override fun getInventory(): Inventory =
        backing ?: error("MenuHolder inventory accessed before assignment")
}
