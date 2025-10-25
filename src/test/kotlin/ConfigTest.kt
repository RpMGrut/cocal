import com.typesafe.config.ConfigException
import com.typesafe.config.ConfigFactory
import me.delyfss.cocal.Config
import me.delyfss.cocal.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ConfigTest {

    data class BossBar(
        val enabled: Boolean = true,
        val text: String = "<green>Default"
    )

    data class Tool(
        val material: String = "STONE_AXE",
        @Path("display-name")
        val displayName: String = "Builder",
        val lore: List<String> = listOf("<gray>Line 1", "<gray>Line 2")
    )

    data class ExampleConfig(
        val enabled: Boolean = false,
        @Path("countdown-seconds")
        val countdownSeconds: List<Int> = listOf(5, 4, 3, 2, 1),
        val tool: Tool = Tool(),
        @Path("boss-bars")
        val bossBars: Map<String, BossBar> = mapOf(
            "pvp" to BossBar(text = "<red>PvP soon"),
            "nether" to BossBar(enabled = false, text = "<blue>Nether locked")
        )
    )

    class LegacyConfig {
        @Path("legacy.value")
        var value: String = "default"
    }

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `data class config is created from defaults`() {
        val loader = Config(tempDir, "example.conf", ExampleConfig())
        val loaded = loader.load()

        val targetFile = File(tempDir, "example.conf")
        assertTrue(targetFile.exists(), "Config file should be created")
        assertEquals(listOf(5, 4, 3, 2, 1), loaded.countdownSeconds)
        assertEquals("STONE_AXE", loaded.tool.material)
        assertTrue(targetFile.readText().contains("countdown-seconds"))
    }

    @Test
    fun `data class config merges overrides`() {
        val file = File(tempDir, "example.conf")
        file.writeText(
            """
            enabled = true
            countdown-seconds = [10, 5]
            tool {
              material = "DIAMOND_AXE"
              display-name = "Custom"
              lore = ["one"]
            }
            boss-bars {
              pvp {
                text = "<gold>Custom"
              }
            }
            """.trimIndent()
        )

        val loader = Config(tempDir, "example.conf", ExampleConfig())
        val loaded = loader.load()

        assertTrue(loaded.enabled)
        assertEquals(listOf(10, 5), loaded.countdownSeconds)
        assertEquals("DIAMOND_AXE", loaded.tool.material)
        assertEquals("<gold>Custom", loaded.bossBars.getValue("pvp").text)
        assertEquals("<blue>Nether locked", loaded.bossBars.getValue("nether").text)
    }

    @Test
    fun `loader appends new keys and removes deprecated ones`() {
        val file = File(tempDir, "example.conf")
        file.writeText(
            """
            enabled = true
            countdown-seconds = [1, 2]
            deprecated-setting = true
            """.trimIndent()
        )

        Config(tempDir, "example.conf", ExampleConfig()).load()

        val newText = file.readText()
        assertTrue("deprecated-setting" !in newText)
        assertTrue(newText.contains("tool"))
        assertTrue(newText.contains("boss-bars"))
    }

    @Test
    fun `invalid syntax restores defaults and keeps backup`() {
        val file = File(tempDir, "example.conf")
        file.writeText(
            """
            enabled = true
            broken = {
            """.trimIndent()
        )

        assertThrows<ConfigException> {
            ConfigFactory.parseString(file.readText())
        }

        Config(tempDir, "example.conf", ExampleConfig()).load()

        val updated = file.readText()
        assertTrue(updated.contains("countdown-seconds"))
        val backupExists = tempDir.listFiles()?.any { it.name.startsWith("examplesave-") } ?: false
        assertTrue(backupExists)
    }

    @Test
    fun `type mismatch regenerates config`() {
        val file = File(tempDir, "example.conf")
        file.writeText(
            """
            enabled = "nope"
            """.trimIndent()
        )

        val loaded = Config(tempDir, "example.conf", ExampleConfig()).load()

        assertEquals(false, loaded.enabled)
        val resetText = file.readText()
        assertTrue(resetText.contains("boss-bars"))
        val backups = tempDir.listFiles()?.filter { it.name.startsWith("examplesave-") } ?: emptyList()
        assertTrue(backups.isNotEmpty())
    }

    @Test
    fun `legacy mutable config still loads`() {
        val loader = Config(tempDir, "legacy.conf", LegacyConfig())
        val loaded = loader.load()
        assertEquals("default", loaded.value)
        assertTrue(File(tempDir, "legacy.conf").exists())
    }
}
