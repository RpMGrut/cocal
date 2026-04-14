package me.delyfss.cocal.message

import gg.aquatic.quickminimessage.MMParser
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver

internal interface MessageComponentParser {
    fun deserialize(input: String): Component

    fun deserialize(input: String, resolver: TagResolver): Component

    companion object {
        fun create(backend: Messages.ParserBackend): MessageComponentParser {
            return when (backend) {
                Messages.ParserBackend.MINI_MESSAGE -> MiniMessageComponentParser
                Messages.ParserBackend.QUICK_MINI_MESSAGE -> QuickMiniMessageComponentParser
            }
        }
    }
}

internal object MiniMessageComponentParser : MessageComponentParser {
    private val miniMessage = MiniMessage.miniMessage()

    override fun deserialize(input: String): Component = miniMessage.deserialize(input)

    override fun deserialize(input: String, resolver: TagResolver): Component =
        miniMessage.deserialize(input, resolver)
}

internal object QuickMiniMessageComponentParser : MessageComponentParser {
    private val miniMessage = MiniMessage.miniMessage()

    override fun deserialize(input: String): Component = MMParser.deserialize(input)

    override fun deserialize(input: String, resolver: TagResolver): Component {
        // QuickMiniMessage (gg.aquatic) does not expose a TagResolver API, so when the
        // caller supplies component-valued placeholders we fall back to the standard
        // MiniMessage parser — it understands the same tag syntax. This preserves
        // component formatting in placeholder values (the whole point of the overload).
        return miniMessage.deserialize(input, resolver)
    }
}
