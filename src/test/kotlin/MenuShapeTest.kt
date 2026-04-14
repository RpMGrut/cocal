import me.delyfss.cocal.menu.config.MenuShape
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MenuShapeTest {

    @Test
    fun `parses a simple shape into slot assignments`() {
        val shape = listOf(
            "AAAAAAAAA",
            "A#######A",
            "AAAAAAAAA"
        )
        val layout = MenuShape.parse(shape)
        assertEquals(27, layout.slotCount)
        assertEquals('A', layout.assignments[0])
        assertEquals('A', layout.assignments[8])
        assertEquals('A', layout.assignments[9])
        assertFalse(layout.assignments.containsKey(10))
        assertFalse(layout.assignments.containsKey(16))
        assertEquals('A', layout.assignments[17])
    }

    @Test
    fun `hash and space characters are treated as empty slots`() {
        val shape = listOf("C   C")
        val layout = MenuShape.parse(shape)
        assertTrue(layout.assignments.containsKey(0))
        assertFalse(layout.assignments.containsKey(1))
        assertFalse(layout.assignments.containsKey(2))
        assertFalse(layout.assignments.containsKey(3))
        assertTrue(layout.assignments.containsKey(4))
    }

    @Test
    fun `row beyond slots per row is truncated`() {
        val shape = listOf("AAAAAAAAAA") // 10 characters, slotsPerRow=9
        val layout = MenuShape.parse(shape)
        assertEquals(9, layout.assignments.size)
    }

    @Test
    fun `TZ example shape parses all four marker characters`() {
        val shape = listOf(
            "AAAAAAAAA",
            "A#A#A#A#A",
            "A#A#A#A#A",
            "AAAAAAAAA",
            "A   C   A",
            "AAAAAAAAA"
        )
        val layout = MenuShape.parse(shape)
        assertEquals(54, layout.slotCount)
        assertEquals('C', layout.assignments[40])
        assertEquals('A', layout.assignments[36])
        assertEquals('A', layout.assignments[44])
    }
}
