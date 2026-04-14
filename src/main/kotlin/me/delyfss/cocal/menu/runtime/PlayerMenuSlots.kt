package me.delyfss.cocal.menu.runtime

/**
 * Slot translation between a 4×9 menu shape and the 36 storage slots of a
 * player inventory (slots 0..35 in Bukkit's [org.bukkit.inventory.PlayerInventory]).
 *
 * Layout (visually, as the player sees it when pressing E):
 *   shape row 0 → main inventory top row    → player slots 9..17
 *   shape row 1 → main inventory middle row → player slots 18..26
 *   shape row 2 → main inventory bottom row → player slots 27..35
 *   shape row 3 → hotbar                    → player slots 0..8
 *
 * Slots beyond row 3 are ignored (armor and offhand are preserved untouched).
 */
object PlayerMenuSlots {

    /** Converts a shape slot (0..35) to a [PlayerInventory] slot (0..35). */
    fun shapeToInventory(shapeSlot: Int): Int {
        val row = shapeSlot / 9
        val col = shapeSlot % 9
        if (col !in 0..8) return -1
        return when (row) {
            0 -> 9 + col
            1 -> 18 + col
            2 -> 27 + col
            3 -> col
            else -> -1
        }
    }

    /** Inverse mapping — used by the click listener to look up a shape item. */
    fun inventoryToShape(playerSlot: Int): Int {
        return when (playerSlot) {
            in 0..8 -> 27 + playerSlot           // hotbar → shape row 3
            in 9..17 -> playerSlot - 9           // top main → shape row 0
            in 18..26 -> playerSlot - 9          // mid main → shape row 1
            in 27..35 -> playerSlot - 9          // bot main → shape row 2
            else -> -1
        }
    }
}
