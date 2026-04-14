import com.typesafe.config.ConfigFactory
import me.delyfss.cocal.message.Messages
import org.bukkit.plugin.java.JavaPlugin
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import sun.misc.Unsafe
import java.io.File
import java.net.URLClassLoader
import java.util.logging.Logger

class MessagesEnsureDefaultsTest {

    @TempDir
    lateinit var tempDir: File

    private val logger = Logger.getLogger("MessagesEnsureDefaultsTest")

    @Test
    fun `ensureDefaults creates missing root messages file from jar resource`() {
        val resourcesDir = File(tempDir, "resources").apply { mkdirs() }
        val dataDir = File(tempDir, "data").apply { mkdirs() }
        val messagesFile = File(dataDir, "messages.conf")

        write(
            File(resourcesDir, "messages.conf"),
            """
            messages {
              greet = "Hello"
            }
            """
        )

        val messages = Messages(
            configSupplier = { ConfigFactory.empty() },
            logger = logger,
            plugin = allocatePlugin(dataDir, resourcesDir)
        )

        messages.ensureDefaults(messagesFile)

        assertTrue(messagesFile.exists())
        val parsed = ConfigFactory.parseFile(messagesFile).resolve()
        assertEquals("Hello", parsed.getString("messages.greet"))
    }

    @Test
    fun `ensureDefaults creates missing locale directories and file from jar resource`() {
        val resourcesDir = File(tempDir, "resources").apply { mkdirs() }
        val dataDir = File(tempDir, "data").apply { mkdirs() }
        val localeFile = File(dataDir, "languages/ru-RU.conf")

        write(
            File(resourcesDir, "languages/ru-RU.conf"),
            """
            messages {
              greet = "Privet"
            }
            """
        )

        val messages = Messages(
            configSupplier = { ConfigFactory.empty() },
            logger = logger,
            plugin = allocatePlugin(dataDir, resourcesDir)
        )

        messages.ensureDefaults(localeFile)

        assertTrue(localeFile.exists())
        val parsed = ConfigFactory.parseFile(localeFile).resolve()
        assertEquals("Privet", parsed.getString("messages.greet"))
    }

    @Test
    fun `ensureDefaults preserves user values and appends missing defaults`() {
        val resourcesDir = File(tempDir, "resources").apply { mkdirs() }
        val dataDir = File(tempDir, "data").apply { mkdirs() }
        val messagesFile = File(dataDir, "messages.conf")

        write(
            File(resourcesDir, "messages.conf"),
            """
            messages {
              greet = "Default"
              bye = "Bye"
            }
            """
        )
        write(
            messagesFile,
            """
            messages {
              greet = "Custom"
            }
            """
        )

        val messages = Messages(
            configSupplier = { ConfigFactory.empty() },
            logger = logger,
            plugin = allocatePlugin(dataDir, resourcesDir)
        )

        messages.ensureDefaults(messagesFile)

        val parsed = ConfigFactory.parseFile(messagesFile).resolve()
        assertEquals("Custom", parsed.getString("messages.greet"))
        assertEquals("Bye", parsed.getString("messages.bye"))
    }

    private fun allocatePlugin(dataFolder: File, resourcesDir: File): JavaPlugin {
        val field = Unsafe::class.java.getDeclaredField("theUnsafe")
        field.isAccessible = true
        val unsafe = field.get(null) as Unsafe
        val plugin = unsafe.allocateInstance(TestPlugin::class.java) as JavaPlugin

        setField(plugin, JavaPlugin::class.java, "dataFolder", dataFolder)
        setField(
            plugin,
            JavaPlugin::class.java,
            "classLoader",
            URLClassLoader(arrayOf(resourcesDir.toURI().toURL()), javaClass.classLoader)
        )

        return plugin
    }

    private fun setField(target: Any, owner: Class<*>, name: String, value: Any) {
        val field = owner.getDeclaredField(name)
        field.isAccessible = true
        field.set(target, value)
    }

    private fun write(file: File, text: String) {
        file.parentFile?.mkdirs()
        file.writeText(text.trimIndent())
    }

    private class TestPlugin : JavaPlugin()
}
