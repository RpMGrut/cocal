package me.delyfss.cocal.menu.config

import org.bukkit.event.inventory.InventoryType

/**
 * Menu inventory types supported by cocal.
 *
 * CHEST (default) and ENDER_CHEST / BARREL / SHULKER_BOX accept a variable size
 * (multiple of 9, up to 54). All other types have a fixed slot count dictated
 * by vanilla Bukkit and ignore the config `size` field.
 */
enum class MenuType(val inventoryType: InventoryType, val fixedSize: Int?) {
    CHEST(InventoryType.CHEST, null),
    ENDER_CHEST(InventoryType.ENDER_CHEST, 27),
    BARREL(InventoryType.BARREL, 27),
    SHULKER_BOX(InventoryType.SHULKER_BOX, 27),

    ANVIL(InventoryType.ANVIL, 3),
    BEACON(InventoryType.BEACON, 1),
    BLAST_FURNACE(InventoryType.BLAST_FURNACE, 3),
    BREWING(InventoryType.BREWING, 5),
    CARTOGRAPHY(InventoryType.CARTOGRAPHY, 3),
    DISPENSER(InventoryType.DISPENSER, 9),
    DROPPER(InventoryType.DROPPER, 9),
    ENCHANTING(InventoryType.ENCHANTING, 2),
    FURNACE(InventoryType.FURNACE, 3),
    GRINDSTONE(InventoryType.GRINDSTONE, 3),
    HOPPER(InventoryType.HOPPER, 5),
    LOOM(InventoryType.LOOM, 4),
    PLAYER(InventoryType.PLAYER, 41),
    SMOKER(InventoryType.SMOKER, 3),
    WORKBENCH(InventoryType.WORKBENCH, 10);

    val usesChestSize: Boolean get() = fixedSize == null

    companion object {
        private val VALID_CHEST_SIZES = setOf(9, 18, 27, 36, 45, 54)
        fun isValidChestSize(size: Int): Boolean = size in VALID_CHEST_SIZES
    }
}
