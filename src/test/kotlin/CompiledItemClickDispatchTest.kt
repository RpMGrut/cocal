import me.delyfss.cocal.menu.action.Action
import me.delyfss.cocal.menu.action.ActionContext
import me.delyfss.cocal.menu.config.MenuItemConfig
import me.delyfss.cocal.menu.runtime.CompiledItem
import org.bukkit.event.inventory.ClickType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Per-click action dispatch: `CompiledItem.actionsFor(clickType)` must honour
 * DeluxeMenus semantics. A click-type-specific list wins if non-empty;
 * otherwise the general fallback runs. SHIFT_LEFT/SHIFT_RIGHT fall back to
 * the general list, not to LEFT/RIGHT.
 */
class CompiledItemClickDispatchTest {

    private class NamedAction(val name: String) : Action {
        override fun run(context: ActionContext) {}
    }

    private fun actionList(vararg names: String): List<Action> =
        names.map { NamedAction(it) }

    private fun itemWith(
        general: List<Action> = emptyList(),
        left: List<Action> = emptyList(),
        right: List<Action> = emptyList(),
        shiftLeft: List<Action> = emptyList(),
        shiftRight: List<Action> = emptyList(),
        middle: List<Action> = emptyList()
    ) = CompiledItem(
        key = 'X',
        config = MenuItemConfig(),
        actions = general,
        leftActions = left,
        rightActions = right,
        shiftLeftActions = shiftLeft,
        shiftRightActions = shiftRight,
        middleActions = middle,
        denyActions = emptyList(),
        requirements = emptyList()
    )

    private fun names(actions: List<Action>): List<String> =
        actions.map { (it as NamedAction).name }

    @Test
    fun `specific click list wins over general when non-empty`() {
        val item = itemWith(
            general = actionList("general"),
            left = actionList("leftOnly")
        )
        assertEquals(listOf("leftOnly"), names(item.actionsFor(ClickType.LEFT)))
        assertEquals(listOf("general"), names(item.actionsFor(ClickType.RIGHT)))
    }

    @Test
    fun `empty click list falls back to general actions`() {
        val item = itemWith(general = actionList("fallback"))
        assertEquals(listOf("fallback"), names(item.actionsFor(ClickType.LEFT)))
        assertEquals(listOf("fallback"), names(item.actionsFor(ClickType.RIGHT)))
        assertEquals(listOf("fallback"), names(item.actionsFor(ClickType.SHIFT_LEFT)))
        assertEquals(listOf("fallback"), names(item.actionsFor(ClickType.SHIFT_RIGHT)))
        assertEquals(listOf("fallback"), names(item.actionsFor(ClickType.MIDDLE)))
    }

    @Test
    fun `shift-left does NOT fall back to left-actions`() {
        val item = itemWith(
            general = actionList("general"),
            left = actionList("leftOnly")
        )
        // SHIFT_LEFT should hit general fallback, not leftOnly.
        assertEquals(listOf("general"), names(item.actionsFor(ClickType.SHIFT_LEFT)))
    }

    @Test
    fun `shift-right uses its own list when provided`() {
        val item = itemWith(
            general = actionList("general"),
            shiftRight = actionList("sRightOnly")
        )
        assertEquals(listOf("sRightOnly"), names(item.actionsFor(ClickType.SHIFT_RIGHT)))
        assertEquals(listOf("general"), names(item.actionsFor(ClickType.RIGHT)))
    }

    @Test
    fun `middle click uses its own list`() {
        val item = itemWith(
            general = actionList("general"),
            middle = actionList("wheelClick")
        )
        assertEquals(listOf("wheelClick"), names(item.actionsFor(ClickType.MIDDLE)))
    }

    @Test
    fun `unhandled click types resolve to general fallback`() {
        val item = itemWith(general = actionList("general"))
        // DOUBLE_CLICK, DROP, CONTROL_DROP, NUMBER_KEY, UNKNOWN etc.
        assertEquals(listOf("general"), names(item.actionsFor(ClickType.DOUBLE_CLICK)))
        assertEquals(listOf("general"), names(item.actionsFor(ClickType.DROP)))
    }

    @Test
    fun `item with no actions at all returns empty for every click`() {
        val item = itemWith()
        assertEquals(emptyList<String>(), names(item.actionsFor(ClickType.LEFT)))
        assertEquals(emptyList<String>(), names(item.actionsFor(ClickType.SHIFT_RIGHT)))
    }
}
