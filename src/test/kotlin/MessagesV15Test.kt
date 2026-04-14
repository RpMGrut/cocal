import me.delyfss.cocal.message.Messages
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.logging.Logger

class MessagesV15Test {

    @TempDir
    lateinit var tempDir: File

    private val logger = Logger.getLogger("MessagesV15Test")
    private val plainSerializer = PlainTextComponentSerializer.plainText()

    @Suppress("DEPRECATION")
    private fun loadMessages(body: String): Messages {
        val file = File(tempDir, "messages.conf")
        file.writeText(body.trimIndent())
        val messages = Messages.fromFile(
            fileProvider = { file },
            logger = logger
        )
        messages.load()
        return messages
    }

    @Test
    fun `rawString returns untouched minimessage text`() {
        val messages = loadMessages(
            """
            messages {
              greet = "<red>Hello <name></red>"
            }
            """
        )
        assertEquals("<red>Hello <name></red>", messages.rawString("greet"))
    }

    @Test
    fun `miniMessage returns a component with colour intact`() {
        val messages = loadMessages(
            """
            messages {
              welcome = "<green>Welcome"
            }
            """
        )
        val component = messages.miniMessage("welcome")
        assertNotNull(component)
        assertEquals("Welcome", plainSerializer.serialize(component!!))
        assertEquals(NamedTextColor.GREEN, component.color())
    }

    @Test
    fun `component placeholder preserves formatting inside a plain chat line`() {
        val messages = loadMessages(
            """
            messages {
              intro = "Hello <username>!"
            }
            """
        )
        val username = Component.text("Alex", NamedTextColor.GOLD)
        val result = messages.miniMessage(
            path = "intro",
            componentReplacements = mapOf("username" to username)
        )
        assertNotNull(result)
        val plain = plainSerializer.serialize(result!!)
        assertEquals("Hello Alex!", plain)
        // The gold colour should survive the resolver path.
        assertTrue(result.toString().contains("gold"))
    }
}
