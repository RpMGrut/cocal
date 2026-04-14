package me.delyfss.cocal.menu.config

/**
 * Parses a DeluxeMenus-style rectangular shape into a map of slot index → item key.
 *
 * Each character in each row corresponds to one inventory slot.
 * '#' and ' ' are treated as empty (no item placed).
 * All other characters must appear in the menu's `items` map.
 *
 * Example:
 *   shape = [
 *     "AAAAAAAAA",
 *     "A#A#A#A#A",
 *     "A       A"
 *   ]
 * yields slots 0..8 = A, slot 9 = A, slot 11 = A, etc.
 */
object MenuShape {

    data class Layout(val slotCount: Int, val assignments: Map<Int, Char>)

    fun parse(shape: List<String>, slotsPerRow: Int = 9): Layout {
        if (shape.isEmpty()) return Layout(0, emptyMap())
        val assignments = LinkedHashMap<Int, Char>()
        shape.forEachIndexed { rowIndex, row ->
            row.forEachIndexed { column, character ->
                if (column >= slotsPerRow) return@forEachIndexed
                if (character == '#' || character == ' ') return@forEachIndexed
                val slot = rowIndex * slotsPerRow + column
                assignments[slot] = character
            }
        }
        val slotCount = shape.size * slotsPerRow
        return Layout(slotCount, assignments)
    }
}
