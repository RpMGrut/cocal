package me.delyfss.cocal.menu.action

import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.Bukkit
import org.bukkit.Sound
import org.bukkit.SoundCategory
import java.util.logging.Logger

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
        private val command = argument.removePrefix("/")
        override fun run(context: ActionContext) {
            context.player.performCommand(command)
        }
    }
}

internal object ConsoleCommandActionFactory : ActionFactory {
    override val tag = "console"
    override fun create(argument: String): Action = object : Action {
        private val command = argument.removePrefix("/")
        override fun run(context: ActionContext) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command)
        }
    }
}

internal object MessageActionFactory : ActionFactory {
    override val tag = "message"
    override fun create(argument: String): Action = object : Action {
        private val message = argument
        private val miniMessage = MiniMessage.miniMessage()
        override fun run(context: ActionContext) {
            context.player.sendMessage(miniMessage.deserialize(message))
        }
    }
}

internal class SoundActionFactory(private val logger: Logger?) : ActionFactory {
    override val tag = "sound"
    override fun create(argument: String): Action {
        val parts = argument.split(':', limit = 3)
        val soundName = parts.getOrNull(0)?.trim().orEmpty()
        val volume = parts.getOrNull(1)?.toFloatOrNull() ?: 1f
        val pitch = parts.getOrNull(2)?.toFloatOrNull() ?: 1f
        val sound = runCatching { Sound.valueOf(soundName.uppercase().replace('-', '_').replace('.', '_')) }
            .onFailure { logger?.warning("Unknown sound '$soundName'") }
            .getOrNull()
        return object : Action {
            override fun run(context: ActionContext) {
                if (sound == null) return
                context.player.playSound(context.player.location, sound, SoundCategory.MASTER, volume, pitch)
            }
        }
    }
}

internal object OpenMenuActionFactory : ActionFactory {
    override val tag = "openmenu"
    override fun create(argument: String): Action = object : Action {
        private val menuId = argument.trim()
        override fun run(context: ActionContext) {
            context.menu.session.openMenuRequest = menuId
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
