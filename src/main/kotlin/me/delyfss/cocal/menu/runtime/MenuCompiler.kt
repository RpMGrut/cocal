package me.delyfss.cocal.menu.runtime

import me.delyfss.cocal.menu.action.ActionParser
import me.delyfss.cocal.menu.config.MenuConfig
import me.delyfss.cocal.menu.config.MenuItemConfig
import me.delyfss.cocal.menu.config.MenuShape
import me.delyfss.cocal.menu.requirement.RequirementParser
import java.util.logging.Logger

/**
 * Turns a plain [MenuConfig] (parsed from HOCON) into a [CompiledMenu] ready
 * for rendering. Resolves all string-encoded actions and requirements once so
 * the hot render path never touches the parser.
 */
class MenuCompiler(private val logger: Logger?) {

    fun compile(id: String, config: MenuConfig): CompiledMenu {
        val layout = MenuShape.parse(config.shape)

        val compiledItems = HashMap<Char, CompiledItem>(config.items.size)
        config.items.forEach { (key, itemConfig) ->
            if (key.isEmpty()) return@forEach
            val symbol = key[0]
            compiledItems[symbol] = compileItem(itemConfig, symbol)
        }

        layout.assignments.values.toSet().forEach { symbol ->
            if (!compiledItems.containsKey(symbol)) {
                logger?.warning("Menu '$id' shape references unknown item '$symbol'")
            }
        }

        return CompiledMenu(
            id = id,
            config = config,
            shapeSlots = layout.assignments,
            compiledItems = compiledItems,
            openActions = ActionParser.parseAll(config.openActions, logger),
            closeActions = ActionParser.parseAll(config.closeActions, logger)
        )
    }

    /**
     * Compiles a single [MenuItemConfig] into a [CompiledItem]. Used both for static shape items
     * (at [compile] time) and for dynamic [PageSource] items, whose actions must be resolved at
     * click time since they aren't part of the pre-compiled menu.
     */
    fun compileItem(config: MenuItemConfig, key: Char = ' '): CompiledItem = CompiledItem(
        key = key,
        config = config,
        actions = ActionParser.parseAll(config.actions, logger),
        leftActions = ActionParser.parseAll(config.leftActions, logger),
        rightActions = ActionParser.parseAll(config.rightActions, logger),
        shiftLeftActions = ActionParser.parseAll(config.shiftLeftActions, logger),
        shiftRightActions = ActionParser.parseAll(config.shiftRightActions, logger),
        middleActions = ActionParser.parseAll(config.middleActions, logger),
        denyActions = ActionParser.parseAll(config.denyActions, logger),
        requirements = RequirementParser.parseAll(config.clickRequirements, logger)
    )
}
