import com.typesafe.config.ConfigException
import com.typesafe.config.ConfigFactory
import me.delyfss.cocal.Config
import me.delyfss.cocal.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

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

    data class PenetrationRule(
        val groups: List<String> = emptyList(),
        @Path("include-materials")
        val includeMaterials: List<String> = emptyList(),
        @Path("material-name-contains")
        val materialNameContains: List<String> = emptyList(),
        @Path("block-behavior")
        val blockBehavior: String = "break"
    )

    data class PenetrationProfile(
        val enabled: Boolean = true,
        val rules: List<PenetrationRule> = emptyList()
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
        assertTrue(backupFiles("examplesave-").isNotEmpty())
    }

    @Test
    fun `type mismatch rolls back only invalid path and keeps overrides`() {
        val file = File(tempDir, "example.conf")
        file.writeText(
            """
            enabled = "nope"
            tool {
              material = "DIAMOND_AXE"
            }
            """.trimIndent()
        )

        val loaded = Config(tempDir, "example.conf", ExampleConfig()).load()

        assertEquals(false, loaded.enabled)
        assertEquals("DIAMOND_AXE", loaded.tool.material)
        assertTrue(file.readText().contains("material = \"DIAMOND_AXE\""))
        assertTrue(backupFiles("examplesave-").isEmpty())
    }

    @Test
    fun `multiple invalid values are fixed without global backup`() {
        val file = File(tempDir, "example.conf")
        file.writeText(
            """
            enabled = "nope"
            countdown-seconds = ["a", "b"]
            tool {
              material = "DIAMOND_AXE"
              display-name = "Custom"
              lore = [{ bad = 1 }]
            }
            """.trimIndent()
        )

        val loaded = Config(tempDir, "example.conf", ExampleConfig()).load()

        assertEquals(false, loaded.enabled)
        assertEquals(listOf(5, 4, 3, 2, 1), loaded.countdownSeconds)
        assertEquals("DIAMOND_AXE", loaded.tool.material)
        assertEquals("Custom", loaded.tool.displayName)
        assertEquals(listOf("<gray>Line 1", "<gray>Line 2"), loaded.tool.lore)

        val text = file.readText()
        assertTrue(text.contains("material = \"DIAMOND_AXE\""))
        assertTrue(text.contains("display-name = \"Custom\""))
        assertTrue(backupFiles("examplesave-").isEmpty())
    }

    @Test
    fun `warning includes file line path and bad value`() {
        val file = File(tempDir, "example.conf")
        file.writeText("enabled = \"nope\"")

        val warnings = captureConfigWarnings {
            Config(tempDir, "example.conf", ExampleConfig()).load()
        }

        val relevant = warnings.firstOrNull { it.contains("path='enabled'") }
        assertTrue(relevant != null)
        assertTrue(relevant!!.contains("line=1"))
        assertTrue(relevant.contains("value='nope'"))
        assertTrue(relevant.contains("action='roll back to default'"))
    }

    @Test
    fun `alwaysWriteFile false still writes after selective rollback`() {
        val file = File(tempDir, "example.conf")
        val original = """
            enabled = "nope"
            tool {
              material = "DIAMOND_AXE"
            }
        """.trimIndent()
        file.writeText(original)

        val loaded = Config(
            tempDir,
            "example.conf",
            ExampleConfig(),
            Config.Options(alwaysWriteFile = false)
        ).load()

        assertEquals(false, loaded.enabled)
        assertEquals("DIAMOND_AXE", loaded.tool.material)
        val written = file.readText()
        assertTrue(written != original)
        assertTrue(written.contains("enabled = false"))
        assertTrue(written.contains("material = \"DIAMOND_AXE\""))
        assertTrue(backupFiles("examplesave-").isEmpty())
    }

    @Test
    fun `dynamic map key without default triggers global backup reset`() {
        val file = File(tempDir, "example.conf")
        file.writeText(
            """
            enabled = true
            tool {
              material = "DIAMOND_AXE"
            }
            boss-bars {
              custom {
                enabled = "nope"
                text = "Custom"
              }
            }
            """.trimIndent()
        )

        val loaded = Config(tempDir, "example.conf", ExampleConfig()).load()

        assertEquals(false, loaded.enabled)
        assertEquals("STONE_AXE", loaded.tool.material)
        assertFalse(loaded.bossBars.containsKey("custom"))
        assertFalse(file.readText().contains("custom"))
        assertTrue(backupFiles("examplesave-").isNotEmpty())
    }

    @Test
    fun `legacy mutable config still loads`() {
        val loader = Config(tempDir, "legacy.conf", LegacyConfig())
        val loaded = loader.load()
        assertEquals("default", loaded.value)
        assertTrue(File(tempDir, "legacy.conf").exists())
    }

    @Test
    fun `missing optional fields inside list data objects use constructor defaults`() {
        val file = File(tempDir, "profile.conf")
        val original = """
            enabled = true
            rules = [
              {
                groups = ["GLASS"]
                include-materials = []
              }
            ]
        """.trimIndent()
        file.writeText(original)

        val loader = Config(
            tempDir,
            "profile.conf",
            PenetrationProfile(),
            Config.Options(alwaysWriteFile = false)
        )
        val loaded = loader.load()

        assertTrue(loaded.enabled)
        assertEquals(1, loaded.rules.size)
        assertEquals(listOf("GLASS"), loaded.rules.first().groups)
        assertEquals(emptyList<String>(), loaded.rules.first().materialNameContains)
        assertEquals("break", loaded.rules.first().blockBehavior)
        assertEquals(original, file.readText())
        val backupExists = tempDir.listFiles()?.any { it.name.startsWith("profilesave-") } ?: false
        assertFalse(backupExists)
    }

    enum class ExperienceWay {
        MINING,
        KILLING,
        FARMING
    }

    data class AdvancedConfig(
        @Path("level-benefits")
        val levelBenefits: Map<Int, Double> = mapOf(
            1 to 1.1,
            5 to 1.5
        ),
        @Path("farm-ways")
        val farmWays: Map<ExperienceWay, Int> = mapOf(
            ExperienceWay.MINING to 10
        )
    )

    @Test
    fun `map with int, double and enum keys loads from defaults`() {
        val loader = Config(tempDir, "advanced.conf", AdvancedConfig())
        val loaded = loader.load()

        assertTrue(loaded.levelBenefits.containsKey(1))
        assertTrue(loaded.levelBenefits.containsKey(5))
        assertEquals(1.5, loaded.levelBenefits[5])

        assertTrue(loaded.farmWays.containsKey(ExperienceWay.MINING))

        val file = File(tempDir, "advanced.conf")
        val text = file.readText()

        assertTrue(text.contains("\"1\"") || text.contains("1"))
        assertTrue(text.contains("MINING"))
    }

    @Test
    fun `map parses string keys into int and enum objects`() {
        val file = File(tempDir, "advanced.conf")
        file.writeText(
            """
            level-benefits {
              "10" = 2.0
              "20" = 3.5
            }
            farm-ways {
              killing = 50
              FARMING = 100
            }
            """.trimIndent()
        )

        val loader = Config(tempDir, "advanced.conf", AdvancedConfig())
        val loaded = loader.load()

        assertEquals(2.0, loaded.levelBenefits[10])
        assertEquals(3.5, loaded.levelBenefits[20])

        assertEquals(50, loaded.farmWays[ExperienceWay.KILLING])
        assertEquals(100, loaded.farmWays[ExperienceWay.FARMING])
    }

    @Test
    fun `invalid map keys trigger recovery`() {
        val file = File(tempDir, "advanced.conf")
        file.writeText(
            """
            level-benefits {
              not_a_number = 1.0
            }
            farm-ways {
              INVALID_ENUM = 10
            }
            """.trimIndent()
        )

        val loader = Config(tempDir, "advanced.conf", AdvancedConfig())
        val loaded = loader.load()

        // Должно откатиться к дефолтам из-за невалидных ключей
        assertEquals(1.1, loaded.levelBenefits[1])
        assertEquals(10, loaded.farmWays[ExperienceWay.MINING])

        // Проверяем, что создался бэкап, так как это была критическая ошибка структуры ключа
        val backups = tempDir.listFiles()?.filter { it.name.contains("save-") } ?: emptyList()
        assertTrue(backups.isNotEmpty(), "Should create backup because of invalid map keys")
    }

    private fun backupFiles(prefix: String): List<File> {
        return tempDir.listFiles()
            ?.filter { it.name.startsWith(prefix) }
            ?: emptyList()
    }

    private fun captureConfigWarnings(block: () -> Unit): List<String> {
        val logger = Logger.getLogger(Config::class.java.name)
        val messages = mutableListOf<String>()
        val handler = object : Handler() {
            override fun publish(record: LogRecord) {
                messages += record.message
            }

            override fun flush() = Unit

            override fun close() = Unit
        }

        val previousLevel = logger.level
        val previousUseParentHandlers = logger.useParentHandlers

        logger.level = Level.ALL
        logger.useParentHandlers = false
        logger.addHandler(handler)

        return try {
            block()
            messages
        } finally {
            logger.removeHandler(handler)
            logger.level = previousLevel
            logger.useParentHandlers = previousUseParentHandlers
        }
    }
}
