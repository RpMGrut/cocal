package me.delyfss.cocal.menu.runtime

import me.delyfss.cocal.menu.action.Action
import me.delyfss.cocal.menu.config.MenuConfig
import me.delyfss.cocal.menu.requirement.Requirement

/**
 * A [MenuConfig] with all string-encoded actions and requirements already
 * resolved into executables. Built once per menu file (or whenever the file
 * is reloaded) and reused for every player view.
 */
class CompiledMenu(
    val id: String,
    val config: MenuConfig,
    val shapeSlots: Map<Int, Char>,
    val compiledItems: Map<Char, CompiledItem>,
    val openActions: List<Action>,
    val closeActions: List<Action>
)

class CompiledItem(
    val key: Char,
    val config: me.delyfss.cocal.menu.config.MenuItemConfig,
    val actions: List<Action>,
    val denyActions: List<Action>,
    val requirements: List<Requirement>
)
