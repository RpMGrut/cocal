package me.delyfss.cocal.menu.config

import org.bukkit.Material

data class MenuItemConfig(
    val type: Material = Material.AIR,
    val name: String = "",
    val lore: List<String> = emptyList(),
    val amount: Int = 1,
    val customModelData: Int = 0,
    val glow: Boolean = false,
    val actions: List<String> = emptyList(),
    val clickRequirements: List<String> = emptyList(),
    val denyActions: List<String> = emptyList()
)
