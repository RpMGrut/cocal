import me.delyfss.cocal.Config
import me.delyfss.cocal.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Regression test for the Kotlin 2.3 legacy-loader file-wipe bug.
 *
 * Background: `@Path` declared on a plain `var` Kotlin property without
 * `@field:` or `@JvmField` lands on a synthetic `getX$annotations()` method
 * on modern Kotlin compilers, not on the backing Java field. The old
 * `buildLegacyBlueprint()` / `assignLegacyFields()` used `Field.getDeclaredAnnotation`
 * and therefore missed every property. The resulting empty blueprint caused
 * `writeOrderedConfig` to truncate the existing config file to 0 bytes on the
 * next load.
 *
 * This test loads a legacy model that matches the shape reported in the bug
 * and asserts (a) values are actually read from the file, and (b) the file
 * isn't wiped on load.
 */
class ConfigLegacyKotlinPropertyTest {

    class PluginConfigModel {
        @Path("database.host")
        var databaseHost: String = "localhost"

        @Path("database.port")
        var databasePort: Int = 3306

        @Path("debug.enabled")
        var debugEnabled: Boolean = false
    }

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `legacy var properties with Path are loaded via Kotlin reflection`() {
        val file = File(tempDir, "plugin.conf")
        file.writeText(
            """
            database {
              host = "db.example.com"
              port = 5432
            }
            debug {
              enabled = true
            }
            """.trimIndent()
        )
        val originalSize = file.length()
        assertTrue(originalSize > 0L)

        val loader = Config(tempDir, "plugin.conf", PluginConfigModel())
        val loaded = loader.load()

        assertEquals("db.example.com", loaded.databaseHost)
        assertEquals(5432, loaded.databasePort)
        assertEquals(true, loaded.debugEnabled)

        // And the on-disk file must still carry the user values — the bug
        // symptom was a 0-byte rewrite.
        assertTrue(file.length() > 0L, "plugin.conf was truncated to ${file.length()} bytes")
    }

    class EmptyModel

    @Test
    fun `empty blueprint refuses to truncate an existing non-empty file`() {
        val file = File(tempDir, "state.conf")
        val original = """
            # hand-written config
            some = "value"
        """.trimIndent()
        file.writeText(original)

        Config(tempDir, "state.conf", EmptyModel()).load()

        // Safety guard in writeOrderedConfig must prevent the 0-byte symptom
        // even when the model has zero @Path-annotated properties.
        assertNotEquals(0L, file.length())
        assertEquals(original, file.readText())
    }
}
