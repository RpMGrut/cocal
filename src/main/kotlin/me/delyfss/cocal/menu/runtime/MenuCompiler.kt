package me.delyfss.cocal.menu.runtime

import me.delyfss.cocal.menu.action.ActionParser
import me.delyfss.cocal.menu.config.MenuConfig
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
            compiledItems[symbol] = CompiledItem(
                key = symbol,
                config = itemConfig,
                actions = ActionParser.parseAll(itemConfig.actions, logger),
                leftActions = ActionParser.parseAll(itemConfig.leftActions, logger),
                rightActions = ActionParser.parseAll(itemConfig.rightActions, logger),
                shiftLeftActions = ActionParser.parseAll(itemConfig.shiftLeftActions, logger),
                shiftRightActions = ActionParser.parseAll(itemConfig.shiftRightActions, logger),
                middleActions = ActionParser.parseAll(itemConfig.middleActions, logger),
                denyActions = ActionParser.parseAll(itemConfig.denyActions, logger),
                requirements = RequirementParser.parseAll(itemConfig.clickRequirements, logger)
            )
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
}
