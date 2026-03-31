import me.delyfss.cocal.message.Messages
import me.delyfss.cocal.message.PlaceholderHandler
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.plugin.java.JavaPlugin
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import sun.misc.Unsafe
import java.io.File
import java.util.logging.Logger

class MessagesParserBackendTest {

    @TempDir
    lateinit var tempDir: File

    private val logger = Logger.getLogger("MessagesParserBackendTest")
    private val plainSerializer = PlainTextComponentSerializer.plainText()

    @Test
    fun `options use MiniMessage by default`() {
        assertEquals(Messages.ParserBackend.MINI_MESSAGE, Messages.Options().parserBackend)
    }

    @Test
    fun `common minimessage tags parse successfully across backends`() {
        val input = "<hover:show_text:'<red>Hint'><click:run_command:'/wave'><#62f5b1><bold>Hello</bold> <name></click></hover><newline><gray><italic>Second</italic></gray>"
        val replacements = mapOf("name" to "World")

        val mini = PlaceholderHandler(logger, Messages.ParserBackend.MINI_MESSAGE)
            .componentLines(listOf(input), null, replacements)
        val quick = PlaceholderHandler(logger, Messages.ParserBackend.QUICK_MINI_MESSAGE)
            .componentLines(listOf(input), null, replacements)

        assertEquals(2, mini.size)
        assertEquals(2, quick.size)
        assertLineSemantics(mini)
        assertLineSemantics(quick)
    }

    @Test
    fun `plain output stays stable across backends`() {
        val input = "<#62f5b1><bold>Hello</bold> <name>"
        val replacements = mapOf("name" to "Alex")

        val mini = PlaceholderHandler(logger, Messages.ParserBackend.MINI_MESSAGE)
            .plain(input, null, replacements)
        val quick = PlaceholderHandler(logger, Messages.ParserBackend.QUICK_MINI_MESSAGE)
            .plain(input, null, replacements)

        assertEquals("Hello Alex", mini)
        assertEquals(mini, quick)
    }

    @Test
    fun `fromFile options overload applies selected backend`() {
        val configFile = writeMessagesConfig()
        val messages = Messages.fromFile(
            fileProvider = { configFile },
            logger = logger,
            options = Messages.Options(
                rootPath = "messages",
                parserBackend = Messages.ParserBackend.QUICK_MINI_MESSAGE
            )
        )

        messages.load()

        assertEquals("Hello Alex", messages.plain("greet", replacements = mapOf("name" to "Alex")))
        assertEquals(Messages.ParserBackend.QUICK_MINI_MESSAGE, extractPlaceholderHandler(messages).parserBackend)
    }

    @Test
    fun `plugin fromFile options overload applies selected backend`() {
        val configFile = writeMessagesConfig()
        val messages = Messages.fromFile(
            plugin = allocatePlugin(),
            fileProvider = { configFile },
            logger = logger,
            options = Messages.Options(
                rootPath = "messages",
                parserBackend = Messages.ParserBackend.QUICK_MINI_MESSAGE
            )
        )

        messages.load()

        assertNotNull(messages.template("greet"))
        assertEquals(Messages.ParserBackend.QUICK_MINI_MESSAGE, extractPlaceholderHandler(messages).parserBackend)
    }

    private fun writeMessagesConfig(): File {
        val file = File(tempDir, "messages.conf")
        file.writeText(
            """
            messages {
              greet = "<hover:show_text:'<gray>Hint'><click:run_command:'/wave'><#62f5b1><bold>Hello</bold> <name></click></hover>"
            }
            """.trimIndent()
        )
        return file
    }

    private fun extractPlaceholderHandler(messages: Messages): PlaceholderHandler {
        val field = Messages::class.java.getDeclaredField("placeholderHandler")
        field.isAccessible = true
        return field.get(messages) as PlaceholderHandler
    }

    private fun assertLineSemantics(components: List<Component>) {
        assertEquals(listOf("Hello World", "Second"), components.map(plainSerializer::serialize))
        assertTrue(hasClickEvent(components.first()))
        assertTrue(hasHoverEvent(components.first()))
        assertTrue(hasColor(components.first(), TextColor.fromHexString("#62f5b1")))
        assertTrue(hasDecoration(components.first(), TextDecoration.BOLD))
        assertTrue(hasColor(components.last(), NamedTextColor.GRAY))
    }

    private fun hasClickEvent(component: Component): Boolean {
        return component.style().clickEvent() != null || component.children().any(::hasClickEvent)
    }

    private fun hasHoverEvent(component: Component): Boolean {
        return component.style().hoverEvent() != null || component.children().any(::hasHoverEvent)
    }

    private fun hasColor(component: Component, expected: TextColor?): Boolean {
        return component.style().color() == expected || component.children().any { hasColor(it, expected) }
    }

    private fun hasDecoration(component: Component, decoration: TextDecoration): Boolean {
        return component.style().decoration(decoration) == TextDecoration.State.TRUE
                || component.children().any { hasDecoration(it, decoration) }
    }

    private fun allocatePlugin(): JavaPlugin {
        val field = Unsafe::class.java.getDeclaredField("theUnsafe")
        field.isAccessible = true
        val unsafe = field.get(null) as Unsafe
        return unsafe.allocateInstance(TestPlugin::class.java) as JavaPlugin
    }

    private class TestPlugin : JavaPlugin()
}
