package me.delyfss.cocal.menu.runtime

import me.delyfss.cocal.menu.config.MenuItemConfig
import me.delyfss.cocal.menu.context.MenuContext
import me.delyfss.cocal.message.Messages
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import org.bukkit.Bukkit
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.SkullMeta

/**
 * Builds a live [ItemStack] from a [MenuItemConfig]. Item names/lore are parsed
 * with MiniMessage and resolved against the context's placeholders. String
 * placeholder VALUES are MiniMessage-escaped so untrusted values can't inject
 * tags (`<click>`, colours, …); pass component placeholders for formatted values.
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
            meta.lore(config.lore.map { resolve(it, context) })
        }

        if (config.customModelData != 0) {
            meta.setCustomModelData(config.customModelData)
        }

        if (config.glow) {
            meta.addEnchant(Enchantment.DURABILITY, 1, true)
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS)
        }

        if (meta is SkullMeta && config.head.isNotEmpty()) {
            applyHead(meta, substitute(config.head, context))
        }

        stack.itemMeta = meta
        return stack
    }

    /** Sets a skull's owner from a player name/uuid; no-ops on invalid input. */
    private fun applyHead(meta: SkullMeta, owner: String) {
        if (owner.isEmpty()) return
        runCatching { meta.owningPlayer = Bukkit.getOfflinePlayer(owner) }
    }

    /** `<key>`/`%key%` substitution (escaped values) + optional PAPI, without MiniMessage parsing. */
    private fun substitute(text: String, context: MenuContext): String {
        if (text.isEmpty()) return text
        var expanded = text
        context.stringPlaceholders.forEach { (key, value) ->
            expanded = expanded.replace("<$key>", value).replace("%$key%", value)
        }
        return applyPapi(expanded, context.player)
    }

    private fun resolve(text: String, context: MenuContext): Component {
        // Iterate the map directly (no defensive copy). String values are escaped so injected
        // MiniMessage tags in untrusted values render literally instead of being interpreted.
        var expanded = text
        context.stringPlaceholders.forEach { (key, value) ->
            val safe = miniMessage.escapeTags(value)
            expanded = expanded.replace("<$key>", safe).replace("%$key%", safe)
        }
        expanded = applyPapi(expanded, context.player)
        val resolver = TagResolver.resolver(
            context.componentPlaceholders.map { (key, component) -> Placeholder.component(key, component) }
        )
        return miniMessage.deserialize(expanded, resolver)
            .decoration(TextDecoration.ITALIC, false)
    }

    private fun applyPapi(text: String, player: Player): String {
        if (text.indexOf('%') < 0) return text
        if (!Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) return text
        return PlaceholderApiBridge.apply(player, text)
    }

    /** Reflective PlaceholderAPI bridge (soft-depend); no-op when PAPI is absent. */
    private object PlaceholderApiBridge {
        @Volatile private var method: java.lang.reflect.Method? = null
        @Volatile private var resolved = false

        fun apply(player: Player, text: String): String {
            val m = method ?: run {
                if (resolved) return text
                val found = runCatching {
                    Class.forName("me.clip.placeholderapi.PlaceholderAPI")
                        .getMethod("setPlaceholders", Player::class.java, String::class.java)
                }.getOrNull()
                method = found
                resolved = true
                found ?: return text
            }
            return runCatching { m.invoke(null, player, text) as? String ?: text }.getOrDefault(text)
        }
    }
}
