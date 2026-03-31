package me.delyfss.cocal.message

import gg.aquatic.quickminimessage.MMParser
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage

internal interface MessageComponentParser {
    fun deserialize(input: String): Component

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
}

internal object QuickMiniMessageComponentParser : MessageComponentParser {
    override fun deserialize(input: String): Component = MMParser.deserialize(input)
}
