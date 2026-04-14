import me.delyfss.cocal.menu.action.Action
import me.delyfss.cocal.menu.action.ActionContext
import me.delyfss.cocal.menu.action.ActionFactory
import me.delyfss.cocal.menu.action.ActionParser
import me.delyfss.cocal.menu.action.Actions
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ActionParserTest {

    private class RecordingFactory(override val tag: String) : ActionFactory {
        var lastArgument: String? = null
        override fun create(argument: String): Action {
            lastArgument = argument
            return object : Action {
                override fun run(context: ActionContext) {}
            }
        }
    }

    private lateinit var closeFactory: RecordingFactory
    private lateinit var playerFactory: RecordingFactory

    @BeforeEach
    fun setUp() {
        closeFactory = RecordingFactory("close")
        playerFactory = RecordingFactory("player")
        Actions.register(closeFactory)
        Actions.register(playerFactory)
    }

    @AfterEach
    fun tearDown() {
        Actions.unregister("close")
        Actions.unregister("player")
    }

    @Test
    fun `parses tag with no argument`() {
        assertNotNull(ActionParser.parse("[close]"))
        assertEquals("", closeFactory.lastArgument)
    }

    @Test
    fun `parses tag with whitespace-separated argument`() {
        assertNotNull(ActionParser.parse("[player] say hello"))
        assertEquals("say hello", playerFactory.lastArgument)
    }

    @Test
    fun `returns null for missing tag prefix`() {
        assertNull(ActionParser.parse("close"))
    }

    @Test
    fun `returns null for unknown tag`() {
        assertNull(ActionParser.parse("[unknowntag] foo"))
    }

    @Test
    fun `parseAll filters out invalid entries`() {
        val parsed = ActionParser.parseAll(
            listOf("[close]", "bad", "[player] kick someone", "[unknowntag]")
        )
        assertEquals(2, parsed.size)
    }
}
