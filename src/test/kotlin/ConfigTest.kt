import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import me.delyfss.cocal.Path
import me.delyfss.cocal.Config
import java.io.File

class ConfigTest {

    class TestConfig {
        @Path("test.bool")
        var bool: Boolean = true

        @Path("test.int")
        var int: Int = 42

        @Path("test.str")
        var str: String = "hello"
    }

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `load creates file and loads defaults`() {
        val config = Config(tempDir, "test.conf", TestConfig()).load()
        assertTrue(File(tempDir, "test.conf").exists())
        assertTrue(config.bool)
        assertEquals(42, config.int)
        assertEquals("hello", config.str)
    }
}
