import me.delyfss.cocal.message.Messages
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.logging.Logger

/**
 * Caller-supplied placeholder VALUES are untrusted and must not be able to inject MiniMessage tags
 * (colours, `<click>`, `<hover>`, …). They are escaped so they render literally; the trusted config
 * `prefix` keeps its formatting. Formatted values must go through component placeholders instead.
 *
 * NOTE: value escaping is TEMPORARILY DISABLED in cocal 1.10.1 (it broke every message that injects
 * a pre-built MiniMessage fragment via a `<placeholder>`), so the two escaping assertions below are
 * `@Disabled` until a trusted/raw opt-out lands and escaping is turned back on.
 */
class MessagesInjectionTest {

    @TempDir
    lateinit var tempDir: File

    private val logger = Logger.getLogger("MessagesInjectionTest")

    private fun load(body: String): Messages {
        val file = File(tempDir, "messages.conf")
        file.writeText(body.trimIndent())
        return Messages.fromFile(fileProvider = { file }, logger = logger).also { it.load() }
    }

    @Test
    @Disabled("Value escaping temporarily off in cocal 1.10.1 — re-enable with the trusted/raw opt-out")
    fun `injected minimessage tags in a placeholder value render literally`() {
        val m = load(
            """
            messages {
              greet = "Hi <who>!"
            }
            """
        )
        // Without escaping, <red> would be interpreted as a colour and stripped by plain-text
        // serialization ("Hi Evil!"). Escaped, the tag survives as literal text.
        assertEquals("Hi <red>Evil!", m.plain("greet", null, mapOf("who" to "<red>Evil")))
    }

    @Test
    fun `plain placeholder values are unaffected`() {
        val m = load(
            """
            messages {
              greet = "Hi <who>!"
            }
            """
        )
        assertEquals("Hi Steve!", m.plain("greet", null, mapOf("who" to "Steve")))
    }

    @Test
    @Disabled("Value escaping temporarily off in cocal 1.10.1 — re-enable with the trusted/raw opt-out")
    fun `trusted prefix keeps its formatting`() {
        val m = load(
            """
            messages {
              prefix = "<bold>[App]</bold> "
              hi = "<prefix><who>"
            }
            """
        )
        // Prefix (trusted config) is parsed as MiniMessage; the untrusted value is escaped. Plain
        // serialization drops the bold formatting but keeps text, and the value stays literal.
        assertEquals("[App] <red>x", m.plain("hi", null, mapOf("who" to "<red>x")))
    }
}
