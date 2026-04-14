package me.delyfss.cocal.menu

import me.delyfss.cocal.menu.action.ActionContext
import me.delyfss.cocal.menu.action.Actions
import me.delyfss.cocal.menu.action.BackActionFactory
import me.delyfss.cocal.menu.action.CloseActionFactory
import me.delyfss.cocal.menu.action.ConsoleCommandActionFactory
import me.delyfss.cocal.menu.action.MessageActionFactory
import me.delyfss.cocal.menu.action.OpenMenuActionFactory
import me.delyfss.cocal.menu.action.PlayerCommandActionFactory
import me.delyfss.cocal.menu.action.RefreshActionFactory
import me.delyfss.cocal.menu.action.ScrollActionFactory
import me.delyfss.cocal.menu.action.SoundActionFactory
import me.delyfss.cocal.menu.config.MenuConfig
import me.delyfss.cocal.menu.config.MenuType
import me.delyfss.cocal.menu.context.MenuContext
import me.delyfss.cocal.menu.context.MenuSession
import me.delyfss.cocal.menu.listener.MenuProtectionListener
import me.delyfss.cocal.menu.requirement.HasPermissionRequirementFactory
import me.delyfss.cocal.menu.requirement.Requirements
import me.delyfss.cocal.menu.runtime.CompiledItem
import me.delyfss.cocal.menu.runtime.CompiledMenu
import me.delyfss.cocal.menu.runtime.ItemBuilder
import me.delyfss.cocal.menu.runtime.MenuCompiler
import me.delyfss.cocal.menu.runtime.MenuRenderer
import me.delyfss.cocal.message.Messages
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.plugin.Plugin
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger

/**
 * Top-level entry point for the cocal Menu subsystem. One [MenuService] per
 * JVM is the intended pattern; the cocal core plugin instantiates one and
 * exposes it via `Bukkit.getServicesManager()`.
 *
 * Construction is cheap — all heavy work happens in [enable].
 */
class MenuService(
    val plugin: Plugin,
    private val messages: Messages? = null
) {
    val logger: Logger? = plugin.logger

    private val registry = MenuRegistry()
    private val compiler = MenuCompiler(logger)
    private val itemBuilder = ItemBuilder(messages)
    private val renderer = MenuRenderer(itemBuilder)
    private val sessions = ConcurrentHashMap<UUID, MenuSession>()
    private var enabled = false
    private var listener: MenuProtectionListener? = null

    fun enable() {
        if (enabled) {
            logger?.warning("MenuService.enable() called twice — ignoring")
            return
        }
        registerBuiltinActions()
        Requirements.registerBuiltins(listOf(HasPermissionRequirementFactory))

        val protection = MenuProtectionListener(this)
        Bukkit.getPluginManager().registerEvents(protection, plugin)
        listener = protection
        enabled = true
    }

    fun disable() {
        if (!enabled) return
        closeAllSessions()
        registry.clear()
        enabled = false
        // Listener is tied to the plugin lifecycle — Bukkit unregisters it on
        // plugin disable automatically.
        listener = null
    }

    private fun registerBuiltinActions() {
        Actions.registerBuiltins(
            listOf(
                CloseActionFactory,
                PlayerCommandActionFactory,
                ConsoleCommandActionFactory,
                MessageActionFactory,
                SoundActionFactory(logger),
                OpenMenuActionFactory,
                BackActionFactory,
                RefreshActionFactory,
                ScrollActionFactory("up"),
                ScrollActionFactory("down"),
                ScrollActionFactory("left"),
                ScrollActionFactory("right")
            )
        )
    }

    fun registerMenu(id: String, config: MenuConfig) {
        val compiled = compiler.compile(id, config)
        registry.register(compiled)
    }

    fun unregisterMenu(id: String) {
        registry.unregister(id)
    }

    fun menu(id: String): CompiledMenu? = registry.get(id)

    /** Returns the currently open cocal menu session for [player], if any. */
    fun sessionOf(player: Player): MenuSession? = sessions[player.uniqueId]

    fun open(
        player: Player,
        menuId: String,
        placeholders: Map<String, String> = emptyMap()
    ): Boolean {
        val compiled = registry.get(menuId) ?: run {
            logger?.warning("Cannot open unknown menu '$menuId'")
            return false
        }

        // Clean up any lingering PLAYER-type session before swapping menus —
        // otherwise we'd overwrite the menu items with themselves and lose the
        // snapshot.
        sessions[player.uniqueId]?.let { existing ->
            if (compiled.config.type == MenuType.PLAYER || registry.get(existing.menuId)?.config?.type == MenuType.PLAYER) {
                restorePlayerInventory(player, existing)
            }
        }

        val session = MenuSession(player, menuId)
        sessions[player.uniqueId] = session

        val context = MenuContext(
            player = player,
            menuId = menuId,
            session = session,
            stringPlaceholders = placeholders
        )

        if (compiled.config.type == MenuType.PLAYER) {
            // Snapshot slots 0..40 (full contents), then render into real inv.
            session.savedContents = player.inventory.contents.copyOf()
            renderer.renderIntoPlayer(compiled, context, player)
        } else {
            val inventory = renderer.render(compiled, context)
            player.openInventory(inventory)
        }

        val actionContext = ActionContext(player, ClickType.LEFT, context)
        compiled.openActions.forEach { action ->
            runCatching { action.run(actionContext) }
                .onFailure { ex -> logger?.warning("Menu open action failed: ${ex.message}") }
        }
        return true
    }

    fun refresh(player: Player) {
        val session = sessions[player.uniqueId] ?: return
        val compiled = registry.get(session.menuId) ?: return
        val context = MenuContext(player, session.menuId, session)
        if (compiled.config.type == MenuType.PLAYER) {
            renderer.renderIntoPlayer(compiled, context, player)
        } else {
            val inventory = renderer.render(compiled, context)
            player.openInventory(inventory)
        }
    }

    /**
     * Dispatches a click against a compiled item — shared between the chest
     * path (where the listener has a [MenuHolder]) and the PLAYER path (where
     * it matches the session instead). Processes requirements, runs the
     * winning action list, and then flushes any pending session requests
     * (close/openmenu/back/refresh).
     */
    internal fun dispatchItemClick(
        player: Player,
        clickType: ClickType,
        compiled: CompiledMenu,
        session: MenuSession,
        item: CompiledItem
    ) {
        val context = MenuContext(player, compiled.id, session)
        val actionContext = ActionContext(player, clickType, context)

        val passes = item.requirements.all { req ->
            runCatching { req.test(actionContext) }.getOrElse { false }
        }
        val actionsToRun = if (passes) item.actions else item.denyActions
        actionsToRun.forEach { action ->
            runCatching { action.run(actionContext) }
                .onFailure { ex -> logger?.warning("Menu action failed: ${ex.message}") }
        }

        processPendingSessionRequests(player, compiled, session)
    }

    internal fun processPendingSessionRequests(
        player: Player,
        compiled: CompiledMenu,
        session: MenuSession
    ) {
        when {
            session.closeRequested -> {
                session.closeRequested = false
                if (compiled.config.type == MenuType.PLAYER) {
                    // Player-type menus have no inventory view to close; we
                    // restore the saved contents instead.
                    closePlayerMenu(player, session, compiled)
                } else {
                    // For chest-likes, closeInventory was already called from
                    // the action; InventoryCloseEvent will clean up.
                }
            }
            session.backRequested -> {
                session.backRequested = false
                val previous = session.history.removeLastOrNull()
                if (previous != null) {
                    if (compiled.config.type == MenuType.PLAYER) {
                        // Cleanly exit this PLAYER view before opening the next.
                        restorePlayerInventory(player, session)
                        sessions.remove(player.uniqueId)
                    }
                    open(player, previous)
                }
            }
            session.openMenuRequest != null -> {
                val target = session.openMenuRequest!!
                session.openMenuRequest = null
                session.history.addLast(session.menuId)
                if (compiled.config.type == MenuType.PLAYER) {
                    restorePlayerInventory(player, session)
                    sessions.remove(player.uniqueId)
                }
                open(player, target)
            }
            session.refreshRequested -> {
                session.refreshRequested = false
                refresh(player)
            }
        }
    }

    internal fun handleClose(player: Player, holder: MenuHolder) {
        val session = sessions[player.uniqueId] ?: return
        if (session !== holder.session) return
        sessions.remove(player.uniqueId)
        val context = MenuContext(player, session.menuId, session)
        val actionContext = ActionContext(player, ClickType.LEFT, context)
        holder.compiled.closeActions.forEach { action ->
            runCatching { action.run(actionContext) }
                .onFailure { ex -> logger?.warning("Menu close action failed: ${ex.message}") }
        }
    }

    internal fun handleQuit(player: Player) {
        val session = sessions.remove(player.uniqueId) ?: return
        // Critical safety path: if the player disconnects while a PLAYER-type
        // menu is open, restore their inventory BEFORE the save file is
        // written by Bukkit, otherwise the menu items become their real items.
        val compiled = registry.get(session.menuId) ?: return
        if (compiled.config.type == MenuType.PLAYER) {
            restorePlayerInventory(player, session)
        }
    }

    private fun closePlayerMenu(player: Player, session: MenuSession, compiled: CompiledMenu) {
        restorePlayerInventory(player, session)
        sessions.remove(player.uniqueId)
        val context = MenuContext(player, session.menuId, session)
        val actionContext = ActionContext(player, ClickType.LEFT, context)
        compiled.closeActions.forEach { action ->
            runCatching { action.run(actionContext) }
                .onFailure { ex -> logger?.warning("Menu close action failed: ${ex.message}") }
        }
    }

    private fun restorePlayerInventory(player: Player, session: MenuSession) {
        val saved = session.savedContents ?: return
        player.inventory.contents = saved
        player.updateInventory()
        session.savedContents = null
    }

    private fun closeAllSessions() {
        sessions.entries.toList().forEach { (uuid, session) ->
            val player = Bukkit.getPlayer(uuid)
            if (player != null) {
                val compiled = registry.get(session.menuId)
                if (compiled != null && compiled.config.type == MenuType.PLAYER) {
                    restorePlayerInventory(player, session)
                } else {
                    player.closeInventory()
                }
            }
        }
        sessions.clear()
    }
}
