import me.delyfss.cocal.menu.runtime.PlayerMenuSlots
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PlayerMenuSlotsTest {

    @Test
    fun `shape row 0 maps to top main inventory row`() {
        assertEquals(9, PlayerMenuSlots.shapeToInventory(0))
        assertEquals(17, PlayerMenuSlots.shapeToInventory(8))
    }

    @Test
    fun `shape row 3 maps to hotbar`() {
        assertEquals(0, PlayerMenuSlots.shapeToInventory(27))
        assertEquals(8, PlayerMenuSlots.shapeToInventory(35))
    }

    @Test
    fun `shape rows beyond 3 are rejected`() {
        assertEquals(-1, PlayerMenuSlots.shapeToInventory(36))
        assertEquals(-1, PlayerMenuSlots.shapeToInventory(999))
    }

    @Test
    fun `inventoryToShape is the inverse for storage slots`() {
        (0..35).forEach { shapeSlot ->
            val inventorySlot = PlayerMenuSlots.shapeToInventory(shapeSlot)
            assertEquals(shapeSlot, PlayerMenuSlots.inventoryToShape(inventorySlot))
        }
    }

    @Test
    fun `inventoryToShape rejects armor and offhand`() {
        assertEquals(-1, PlayerMenuSlots.inventoryToShape(36))
        assertEquals(-1, PlayerMenuSlots.inventoryToShape(40))
    }
}
