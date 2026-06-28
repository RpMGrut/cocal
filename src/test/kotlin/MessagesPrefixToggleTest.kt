import me.delyfss.cocal.message.Messages
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.logging.Logger

/**
 * `messages.prefix-enabled` toggle: a `<prefix>` placeholder is always resolved to a
 * concrete string (never left literal). `prefix-enabled = false` blanks it while keeping
 * the configured `prefix` value, so it can be flipped back on without retyping.
 */
class MessagesPrefixToggleTest {

    @TempDir
    lateinit var tempDir: File

    private val logger = Logger.getLogger("MessagesPrefixToggleTest")

    @Suppress("DEPRECATION")
    private fun load(body: String): Messages {
        val file = File(tempDir, "messages.conf")
        file.writeText(body.trimIndent())
        return Messages.fromFile(fileProvider = { file }, logger = logger).also { it.load() }
    }

    @Test
    fun `prefix renders when enabled by default`() {
        val m = load(
            """
            messages {
              prefix = "[App] "
              hi = "<prefix>hello"
            }
            """
        )
        assertEquals("[App] hello", m.plain("hi"))
    }

    @Test
    fun `prefix-enabled true behaves like default`() {
        val m = load(
            """
            messages {
              prefix = "[App] "
              prefix-enabled = true
              hi = "<prefix>hello"
            }
            """
        )
        assertEquals("[App] hello", m.plain("hi"))
    }

    @Test
    fun `prefix-enabled false blanks the prefix but keeps it configured`() {
        val m = load(
            """
            messages {
              prefix = "[App] "
              prefix-enabled = false
              hi = "<prefix>hello"
            }
            """
        )
        assertEquals("hello", m.plain("hi"))
    }

    @Test
    fun `an absent prefix collapses to empty, never literal`() {
        val m = load(
            """
            messages {
              hi = "<prefix>hello"
            }
            """
        )
        assertEquals("hello", m.plain("hi"))
    }
}
