import com.typesafe.config.ConfigFactory
import me.delyfss.cocal.Comment
import me.delyfss.cocal.Config
import me.delyfss.cocal.Path
import me.delyfss.cocal.SectionComment
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ConfigCommentTest {

    data class Nested(
        @Comment("Maximum threshold")
        @Path("max-value")
        val maxValue: Int = 10
    )

    data class CommentedConfig(
        @Comment("Feature switch")
        val enabled: Boolean = true,
        @Comment("Display title")
        @Path("display-name")
        val displayName: String = "Demo",
        @SectionComment("Nested configuration block")
        val nested: Nested = Nested()
    )

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `comments are rendered for keys and sections`() {
        val loader = Config(tempDir, "commented.conf", CommentedConfig())
        loader.load()

        val file = File(tempDir, "commented.conf")
        val text = file.readText()

        assertTrue(text.contains("# Feature switch"))
        assertTrue(text.contains("# Display title"))
        assertTrue(text.contains("# Nested configuration block"))
        assertTrue(text.contains("nested {"))

        ConfigFactory.parseString(text)
    }

    @Test
    fun `comments can be disabled through options`() {
        val loader = Config(
            tempDir,
            "commented-no-comments.conf",
            CommentedConfig(),
            Config.Options(commentsEnabled = false)
        )
        loader.load()

        val file = File(tempDir, "commented-no-comments.conf")
        val text = file.readText()
        assertFalse(text.contains("# Feature switch"))
        assertFalse(text.contains("# Display title"))
        assertFalse(text.contains("# Nested configuration block"))

        ConfigFactory.parseString(text)
    }
}
