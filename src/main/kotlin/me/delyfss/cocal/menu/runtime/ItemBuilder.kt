package me.delyfss.cocal.menu.runtime

import me.delyfss.cocal.menu.config.MenuItemConfig
import me.delyfss.cocal.menu.context.MenuContext
import me.delyfss.cocal.message.Messages
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.enchantments.Enchantment
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack

/**
 * Builds a live [ItemStack] from a [MenuItemConfig]. Item names/lore are parsed
 * with MiniMessage and resolved against the context's placeholders.
 */
class ItemBuilder(private val messages: Messages?) {

    private val miniMessage = MiniMessage.miniMessage()

    fun build(config: MenuItemConfig, context: MenuContext): ItemStack {
        val stack = ItemStack(config.type, config.amount.coerceAtLeast(1))
        if (config.type.isAir) return stack

        val meta = stack.itemMeta ?: return stack

        if (config.name.isNotEmpty()) {
            meta.displayName(resolve(config.name, context))
        }

        if (config.lore.isNotEmpty()) {
            val lines = config.lore.map { resolve(it, context) }
            meta.lore(lines)
        }

        if (config.customModelData != 0) {
            meta.setCustomModelData(config.customModelData)
        }

        if (config.glow) {
            meta.addEnchant(Enchantment.DURABILITY, 1, true)
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS)
        }

        stack.itemMeta = meta
        return stack
    }

    private fun resolve(text: String, context: MenuContext): Component {
        val merged = HashMap(context.stringPlaceholders)
        // Feed the raw MiniMessage string through standard string substitution
        // first, then let MiniMessage parse it with component-valued placeholders.
        var expanded = text
        merged.forEach { (key, value) ->
            expanded = expanded.replace("<$key>", value).replace("%$key%", value)
        }
        val resolver = net.kyori.adventure.text.minimessage.tag.resolver.TagResolver.resolver(
            context.componentPlaceholders.map { (key, component) ->
                net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.component(key, component)
            }
        )
        val parsed = miniMessage.deserialize(expanded, resolver)
        return parsed.decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false)
    }
}
