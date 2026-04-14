import me.delyfss.cocal.message.Messages
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.logging.Logger

class MessagesLocaleTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `locale resolution prefers exact then language then default then base`() {
        write(
            File(tempDir, "messages.conf"),
            """
            messages-meta {
              default-locale = "en-US"
            }
            messages {
              greet = "Base"
            }
            """
        )
        write(
            File(tempDir, "languages/en-US.conf"),
            """
            messages {
              greet = "Hello"
            }
            """
        )
        write(
            File(tempDir, "languages/ru.conf"),
            """
            messages {
              greet = "Privet language"
            }
            """
        )
        write(
            File(tempDir, "languages/ru-RU.conf"),
            """
            messages {
              greet = "Privet region"
            }
            """
        )

        val messages = createMessages()
        messages.load()

        assertEquals("Privet region", messages.templateLocalized("greet", "ru_ru")?.chatLines?.firstOrNull())
        assertEquals("Privet language", messages.templateLocalized("greet", "ru-KZ")?.chatLines?.firstOrNull())
        assertEquals("Hello", messages.templateLocalized("greet", "fr-FR")?.chatLines?.firstOrNull())
    }

    @Test
    fun `base messages are used when locale files are missing`() {
        write(
            File(tempDir, "messages.conf"),
            """
            messages-meta {
              default-locale = "en-US"
            }
            messages {
              greet = "Base"
            }
            """
        )

        val messages = createMessages()
        messages.load()

        assertEquals("Base", messages.template("greet")?.chatLines?.firstOrNull())
        assertEquals("Base", messages.raw("greet")?.chatLines?.firstOrNull())
        assertEquals("Base", messages.templateLocalized("greet", "fr-FR")?.chatLines?.firstOrNull())
        assertEquals("Base", messages.plain("greet"))
    }

    @Test
    fun `corrupted locale file is backed up and skipped`() {
        write(
            File(tempDir, "messages.conf"),
            """
            messages-meta {
              default-locale = "en-US"
            }
            messages {
              greet = "Base"
            }
            """
        )
        write(
            File(tempDir, "languages/ru-RU.conf"),
            """
            messages {
              greet = "Broken"
            """.trimIndent()
        )

        val messages = createMessages()
        messages.load()

        assertEquals("Base", messages.templateLocalized("greet", "ru-RU")?.chatLines?.firstOrNull())
        val backup = File(tempDir, "languages").listFiles()
            ?.firstOrNull { it.name.startsWith("ru-RUsave-") && it.extension.equals("conf", ignoreCase = true) }
        assertNotNull(backup)
    }

    @Test
    fun `locale files are normalized by tag`() {
        write(
            File(tempDir, "messages.conf"),
            """
            messages-meta {
              default-locale = "en-US"
            }
            messages {
              greet = "Base"
            }
            """
        )
        write(
            File(tempDir, "languages/en-us.conf"),
            """
            messages {
              greet = "Hello normalized"
            }
            """
        )

        val messages = createMessages()
        messages.load()

        assertEquals("Hello normalized", messages.templateLocalized("greet", "en_US")?.chatLines?.firstOrNull())
        assertEquals("Hello normalized", messages.templateLocalized("greet", "en-us")?.chatLines?.firstOrNull())
        assertTrue(messages.templateLocalized("greet", "en-US") != null)
    }

    @Suppress("DEPRECATION")
    private fun createMessages(): Messages {
        return Messages.fromFile(
            fileProvider = { File(tempDir, "messages.conf") },
            logger = Logger.getLogger("MessagesLocaleTest"),
            rootPath = "messages",
            localesFolderName = "languages",
            metaRootPath = "messages-meta",
            defaultLocaleKey = "default-locale",
            fallbackDefaultLocale = "en-US"
        )
    }

    private fun write(file: File, text: String) {
        file.parentFile?.mkdirs()
        file.writeText(text.trimIndent())
    }
}
