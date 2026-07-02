package me.delyfss.cocal.message

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.util.logging.Logger

internal class PlaceholderHandler(
    private val logger: Logger?,
    internal val parserBackend: Messages.ParserBackend = Messages.ParserBackend.MINI_MESSAGE
) {
    private val componentParser = MessageComponentParser.create(parserBackend)
    private val plainSerializer = PlainTextComponentSerializer.plainText()

    fun component(
        text: String?,
        player: Player?,
        replacements: Map<String, String>,
        componentReplacements: Map<String, Component> = emptyMap()
    ): Component? {
        if (text.isNullOrEmpty()) return null
        val parsed = apply(text, player, replacements)
        if (parsed.isEmpty()) return null
        val firstLine = normalizedLines(parsed).firstOrNull() ?: return null
        if (firstLine.isEmpty()) return Component.empty()
        return parseWithResolver(firstLine, componentReplacements)
            .decoration(TextDecoration.ITALIC, false)
    }

    fun componentLines(
        texts: Collection<String>,
        player: Player?,
        replacements: Map<String, String>,
        componentReplacements: Map<String, Component> = emptyMap()
    ): List<Component> {
        if (texts.isEmpty()) return emptyList()
        val components = ArrayList<Component>()
        texts.forEach { text ->
            if (text.isEmpty()) {
                components += Component.empty()
                return@forEach
            }
            val parsed = apply(text, player, replacements)
            normalizedLines(parsed).forEach { line ->
                if (line.isEmpty()) {
                    components += Component.empty()
                } else {
                    components += parseWithResolver(line, componentReplacements)
                        .decoration(TextDecoration.ITALIC, false)
                }
            }
        }
        return components
    }

    fun plain(
        text: String?,
        player: Player?,
        replacements: Map<String, String>,
        componentReplacements: Map<String, Component> = emptyMap()
    ): String {
        val component = component(text, player, replacements, componentReplacements) ?: return ""
        return plainSerializer.serialize(component)
    }

    private val deserializeCache = java.util.concurrent.ConcurrentHashMap<String, Component>()

    private fun parseWithResolver(
        text: String,
        componentReplacements: Map<String, Component>
    ): Component {
        if (componentReplacements.isEmpty()) {
            // deserialize(text) is a pure function of `text`; memoize it (Components are immutable,
            // so sharing a cached instance is safe — callers only derive new ones from it).
            deserializeCache[text]?.let { return it }
            val parsed = componentParser.deserialize(text)
            if (deserializeCache.size >= DESERIALIZE_CACHE_LIMIT) deserializeCache.clear()
            deserializeCache[text] = parsed
            return parsed
        }
        val placeholders = componentReplacements.map { (key, component) ->
            Placeholder.component(key, component)
        }
        val resolver = TagResolver.resolver(placeholders)
        return componentParser.deserialize(text, resolver)
    }

    private fun normalizedLines(value: String): List<String> {
        if (value.isEmpty()) return emptyList()
        val replaced = NEWLINE_PATTERN.replace(value) { "\n" }
        return replaced.split('\n')
    }

    private fun apply(text: String, player: Player?, replacements: Map<String, String>): String {
        if (text.isEmpty()) return ""
        var result = text
        if (replacements.isNotEmpty()) {
            replacements.forEach { (key, value) ->
                result = result.replace("<$key>", value)
                    .replace("%$key%", value)
            }
        }
        if (player != null) {
            result = result.replace("<player>", player.name)
                .replace("%player%", player.name)
            result = applyPlaceholderApi(result, player)
        }
        return result
    }

    private fun applyPlaceholderApi(text: String, player: Player): String {
        if (!isPlaceholderApiAvailable()) {
            return text
        }
        val bridge = placeholderBridge() ?: return text
        return runCatching { bridge.apply(player, text) }
            .onFailure { logger?.warning("PlaceholderAPI error: ${it.message ?: it::class.simpleName}") }
            .getOrDefault(text)
    }

    private fun isPlaceholderApiAvailable(): Boolean {
        // Re-checked every call (a cheap plugin-manager lookup) so PAPI loading AFTER this handler
        // is first used isn't permanently cached as "absent" for the whole server session.
        return try {
            Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI") && placeholderBridge() != null
        } catch (_: Throwable) {
            false
        }
    }

    companion object {
        private const val DESERIALIZE_CACHE_LIMIT = 1024
        private val NEWLINE_PATTERN = Regex("(?i)<newline>")
        @Volatile
        private var cachedBridge: PlaceholderBridge? = null

        private fun placeholderBridge(): PlaceholderBridge? {
            cachedBridge?.let { return it }
            // Only cache a SUCCESSFUL lookup — if PAPI isn't loaded yet, retry on the next call.
            val bridge = runCatching {
                val clazz = Class.forName("me.clip.placeholderapi.PlaceholderAPI")
                val method = clazz.getMethod("setPlaceholders", Player::class.java, String::class.java)
                PlaceholderBridge(method)
            }.getOrNull()
            if (bridge != null) cachedBridge = bridge
            return bridge
        }

        private class PlaceholderBridge(private val method: java.lang.reflect.Method) {
            fun apply(player: Player, text: String): String {
                return (method.invoke(null, player, text) as? String) ?: text
            }
        }
    }
}
