package me.delyfss.cocal.message

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver

internal interface MessageComponentParser {
    fun deserialize(input: String): Component

    fun deserialize(input: String, resolver: TagResolver): Component

    companion object {
        // Both backends now use Adventure MiniMessage. The QUICK_MINI_MESSAGE backend used to bind
        // gg.aquatic:QuickMiniMessage, but that dependency lived in a single unmirrored repo that
        // repeatedly broke jitpack/CI builds and offered no TagResolver API (it already fell back to
        // MiniMessage for the resolver overload). It is kept as an enum alias so existing
        // configs/code referencing QUICK_MINI_MESSAGE keep working.
        fun create(backend: Messages.ParserBackend): MessageComponentParser = MiniMessageComponentParser
    }
}

internal object MiniMessageComponentParser : MessageComponentParser {
    private val miniMessage = MiniMessage.miniMessage()

    override fun deserialize(input: String): Component = miniMessage.deserialize(input)

    override fun deserialize(input: String, resolver: TagResolver): Component =
        miniMessage.deserialize(input, resolver)
}
