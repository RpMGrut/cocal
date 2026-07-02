package me.delyfss.cocal.menu.action

import me.delyfss.cocal.menu.context.MenuContext
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.Bukkit
import org.bukkit.Sound
import org.bukkit.SoundCategory
import org.bukkit.permissions.PermissionAttachment
import org.bukkit.plugin.Plugin
import java.util.logging.Logger

/**
 * Substitutes `<key>` / `%key%` from a menu context's string placeholders. Applied at click time
 * (not menu-load time) so dynamic/[PageSource] items resolve their per-row data in commands and
 * messages, mirroring how [me.delyfss.cocal.menu.runtime.ItemBuilder] resolves item text.
 */
internal object MenuActionText {
    fun apply(raw: String, context: MenuContext): String {
        if (raw.isEmpty() || context.stringPlaceholders.isEmpty()) return raw
        var result = raw
        context.stringPlaceholders.forEach { (key, value) ->
            result = result.replace("<$key>", value).replace("%$key%", value)
        }
        return result
    }
}

internal object CloseActionFactory : ActionFactory {
    override val tag = "close"
    override fun create(argument: String): Action = object : Action {
        override fun run(context: ActionContext) {
            context.menu.session.closeRequested = true
            context.player.closeInventory()
        }
    }
}

internal object PlayerCommandActionFactory : ActionFactory {
    override val tag = "player"
    override fun create(argument: String): Action = object : Action {
        private val template = argument.removePrefix("/")
        override fun run(context: ActionContext) {
            context.player.performCommand(MenuActionText.apply(template, context.menu))
        }
    }
}

/**
 * Runs a command as the clicking player but with a temporary wildcard permission attachment, so
 * reward/kit commands work without permanently granting the player the underlying permission.
 * The attachment is always removed in a finally block.
 */
internal class PlayerOpCommandActionFactory(private val plugin: Plugin) : ActionFactory {
    override val tag = "player-op"
    override fun create(argument: String): Action = object : Action {
        private val template = argument.removePrefix("/")
        override fun run(context: ActionContext) {
            val player = context.player
            val command = MenuActionText.apply(template, context.menu)
            var attachment: PermissionAttachment? = null
            try {
                attachment = player.addAttachment(plugin)
                attachment.setPermission("*", true)
                player.recalculatePermissions()
                player.performCommand(command)
            } finally {
                if (attachment != null) runCatching { player.removeAttachment(attachment) }
                player.recalculatePermissions()
            }
        }
    }
}

internal object ConsoleCommandActionFactory : ActionFactory {
    override val tag = "console"
    override fun create(argument: String): Action = object : Action {
        private val template = argument.removePrefix("/")
        override fun run(context: ActionContext) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), MenuActionText.apply(template, context.menu))
        }
    }
}

internal object MessageActionFactory : ActionFactory {
    override val tag = "message"
    override fun create(argument: String): Action = object : Action {
        private val template = argument
        private val miniMessage = MiniMessage.miniMessage()
        override fun run(context: ActionContext) {
            context.player.sendMessage(miniMessage.deserialize(MenuActionText.apply(template, context.menu)))
        }
    }
}

internal class SoundActionFactory(private val logger: Logger?) : ActionFactory {
    override val tag = "sound"
    override fun create(argument: String): Action = object : Action {
        private val parts = argument.split(':', limit = 3)
        private val soundName = parts.getOrNull(0)?.trim().orEmpty()
        private val volume = parts.getOrNull(1)?.toFloatOrNull() ?: 1f
        private val pitch = parts.getOrNull(2)?.toFloatOrNull() ?: 1f
        private val sound = runCatching { Sound.valueOf(soundName.uppercase().replace('-', '_').replace('.', '_')) }
            .onFailure { logger?.warning("Unknown sound '$soundName'") }
            .getOrNull()
        override fun run(context: ActionContext) {
            if (sound == null) return
            context.player.playSound(context.player.location, sound, SoundCategory.MASTER, volume, pitch)
        }
    }
}

internal object OpenMenuActionFactory : ActionFactory {
    override val tag = "openmenu"
    override fun create(argument: String): Action = object : Action {
        private val menuId = argument.trim()
        override fun run(context: ActionContext) {
            context.menu.session.openMenuRequest = MenuActionText.apply(menuId, context.menu)
        }
    }
}

internal object BackActionFactory : ActionFactory {
    override val tag = "back"
    override fun create(argument: String): Action = object : Action {
        override fun run(context: ActionContext) {
            context.menu.session.backRequested = true
        }
    }
}

internal object RefreshActionFactory : ActionFactory {
    override val tag = "refresh"
    override fun create(argument: String): Action = object : Action {
        override fun run(context: ActionContext) {
            context.menu.session.refreshRequested = true
        }
    }
}

internal class PageActionFactory(private val direction: String) : ActionFactory {
    override val tag = "page $direction"
    override fun create(argument: String): Action = object : Action {
        override fun run(context: ActionContext) {
            val session = context.menu.session
            val before = session.page
            when (direction) {
                "next" -> if (session.page < session.pageCount - 1) session.page += 1
                "previous", "prev" -> if (session.page > 0) session.page -= 1
                "first" -> session.page = 0
                "last" -> session.page = (session.pageCount - 1).coerceAtLeast(0)
            }
            if (session.page != before) session.refreshRequested = true
        }
    }
}

internal class ScrollActionFactory(private val direction: String) : ActionFactory {
    override val tag = "scroll $direction"
    override fun create(argument: String): Action = object : Action {
        override fun run(context: ActionContext) {
            val delta = (argument.toIntOrNull() ?: 1).coerceAtLeast(1)
            when (direction) {
                "up", "left" -> context.menu.session.scrollOffset -= delta
                "down", "right" -> context.menu.session.scrollOffset += delta
            }
            if (context.menu.session.scrollOffset < 0) context.menu.session.scrollOffset = 0
            context.menu.session.refreshRequested = true
        }
    }
}
