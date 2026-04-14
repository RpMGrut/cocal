import me.delyfss.cocal.internal.BukkitEnumNormalizer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ConfigBukkitEnumTest {

    @Test
    fun `lowercase with namespace resolves to enum constant`() {
        assertEquals(
            "GRAY_STAINED_GLASS_PANE",
            BukkitEnumNormalizer.normalize("minecraft:gray_stained_glass_pane")
        )
    }

    @Test
    fun `dashed form is normalized to underscores`() {
        assertEquals(
            "GRAY_STAINED_GLASS_PANE",
            BukkitEnumNormalizer.normalize("gray-stained-glass-pane")
        )
    }

    @Test
    fun `space-separated form is normalized`() {
        assertEquals(
            "GRAY_STAINED_GLASS_PANE",
            BukkitEnumNormalizer.normalize("Gray Stained Glass Pane")
        )
    }

    @Test
    fun `already-canonical input passes through unchanged`() {
        assertEquals(
            "GRAY_STAINED_GLASS_PANE",
            BukkitEnumNormalizer.normalize("GRAY_STAINED_GLASS_PANE")
        )
    }
}
