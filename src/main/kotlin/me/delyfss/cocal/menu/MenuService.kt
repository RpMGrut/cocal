package me.delyfss.cocal.menu

import me.delyfss.cocal.menu.action.ActionContext
import me.delyfss.cocal.menu.action.Actions
import me.delyfss.cocal.menu.action.BackActionFactory
import me.delyfss.cocal.menu.action.CloseActionFactory
import me.delyfss.cocal.menu.action.ConsoleCommandActionFactory
import me.delyfss.cocal.menu.action.MessageActionFactory
import me.delyfss.cocal.menu.action.OpenMenuActionFactory
import me.delyfss.cocal.menu.action.PageActionFactory
import me.delyfss.cocal.menu.action.PlayerCommandActionFactory
import me.delyfss.cocal.menu.action.PlayerOpCommandActionFactory
import me.delyfss.cocal.menu.action.RefreshActionFactory
import me.delyfss.cocal.menu.action.ScrollActionFactory
import me.delyfss.cocal.menu.action.SoundActionFactory
import me.delyfss.cocal.menu.config.MenuConfig
import me.delyfss.cocal.menu.config.MenuItemConfig
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
import me.delyfss.cocal.menu.runtime.PageSourceRegistry
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
    private var updateTask: org.bukkit.scheduler.BukkitTask? = null

    /** Cache of compiled dynamic (PageSource) items, keyed by the item config value. Bounded. */
    private val pageItemCache = ConcurrentHashMap<MenuItemConfig, CompiledItem>()
    private val pageItemCacheLimit = 512

    /** How often (ticks) the auto-update task ticks; per-menu [MenuConfig.updateInterval] gates work. */
    private val updateTaskPeriodTicks = 20L

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

        // Drives per-menu auto-refresh (MenuConfig.updateInterval) for animated / live menus.
        updateTask = Bukkit.getScheduler().runTaskTimer(plugin, Runnable { tickAutoUpdates() }, updateTaskPeriodTicks, updateTaskPeriodTicks)
        enabled = true
    }

    fun disable() {
        if (!enabled) return
        updateTask?.cancel()
        updateTask = null
        closeAllSessions()
        // Release PageSources bound for our menus so consumer plugins aren't leaked across /reload.
        registry.ids().forEach { PageSourceRegistry.unbind(it) }
        registry.clear()
        pageItemCache.clear()
        enabled = false
        // Listener is tied to the plugin lifecycle — Bukkit unregisters it on
        // plugin disable automatically.
        listener = null
    }

    private fun tickAutoUpdates() {
        if (sessions.isEmpty()) return
        val nowTick = plugin.server.currentTick.toLong()
        sessions.forEach { (uuid, session) ->
            val compiled = registry.get(session.menuId) ?: return@forEach
            val interval = compiled.config.updateInterval
            if (interval <= 0) return@forEach
            if (nowTick - session.lastAutoUpdateTick < interval) return@forEach
            val player = Bukkit.getPlayer(uuid) ?: return@forEach
            session.lastAutoUpdateTick = nowTick
            runCatching { refresh(player) }
                .onFailure { ex -> logger?.warning("Auto-update for menu '${session.menuId}' failed: ${ex.message}") }
        }
    }

    private fun registerBuiltinActions() {
        Actions.registerBuiltins(
            listOf(
                CloseActionFactory,
                PlayerCommandActionFactory,
                PlayerOpCommandActionFactory(plugin),
                ConsoleCommandActionFactory,
                MessageActionFactory,
                SoundActionFactory(logger),
                OpenMenuActionFactory,
                BackActionFactory,
                RefreshActionFactory,
                PageActionFactory("next"),
                PageActionFactory("previous"),
                PageActionFactory("prev"),
                PageActionFactory("first"),
                PageActionFactory("last"),
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
        PageSourceRegistry.unbind(id)
    }

    fun menu(id: String): CompiledMenu? = registry.get(id)

    /** Returns the currently open cocal menu session for [player], if any. */
    fun sessionOf(player: Player): MenuSession? = sessions[player.uniqueId]

    fun open(
        player: Player,
        menuId: String,
        placeholders: Map<String, String> = emptyMap()
    ): Boolean = openInternal(player, menuId, placeholders, emptyList())

    /**
     * Shared open path. [initialHistory] seeds the new session's navigation stack — the public
     * [open] starts empty, while [openmenu]/[back] navigation carries the stack forward so [back]
     * works across menus. [placeholders] are stored on the session so refresh / clicks / navigation
     * keep the same context.
     */
    private fun warnIfOffMain(op: String) {
        if (!Bukkit.isPrimaryThread()) {
            logger?.warning("MenuService.$op called off the main thread — Bukkit inventory calls must run on the main thread")
        }
    }

    private fun openInternal(
        player: Player,
        menuId: String,
        placeholders: Map<String, String>,
        initialHistory: List<String>
    ): Boolean {
        warnIfOffMain("open")
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
        session.placeholders = placeholders
        // Cap history so repeated back-and-forth navigation (A→B→A→…) can't grow it without bound.
        val bounded = if (initialHistory.size > MAX_HISTORY) initialHistory.takeLast(MAX_HISTORY) else initialHistory
        session.history.addAll(bounded)
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
        warnIfOffMain("refresh")
        val session = sessions[player.uniqueId] ?: return
        val compiled = registry.get(session.menuId) ?: return
        val context = MenuContext(player, session.menuId, session, session.placeholders)
        if (compiled.config.type == MenuType.PLAYER) {
            renderer.renderIntoPlayer(compiled, context, player)
            return
        }
        // Re-render into the already-open inventory when possible (no flicker / no close+open
        // event churn for page nav, scroll and auto-update); fall back to reopening otherwise.
        val top = player.openInventory.topInventory
        val holder = top.holder
        if (holder is MenuHolder && holder.session === session && top.size == compiled.config.size) {
            renderer.renderInto(top, compiled, context)
        } else {
            player.openInventory(renderer.render(compiled, context))
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
        val context = MenuContext(player, compiled.id, session, session.placeholders)
        val actionContext = ActionContext(player, clickType, context)

        val passes = item.requirements.all { req ->
            runCatching { req.test(actionContext) }.getOrElse { false }
        }
        val actionsToRun = if (passes) item.actionsFor(clickType) else item.denyActions
        actionsToRun.forEach { action ->
            runCatching { action.run(actionContext) }
                .onFailure { ex -> logger?.warning("Menu action failed: ${ex.message}") }
        }

        processPendingSessionRequests(player, compiled, session)
    }

    /**
     * Resolves and dispatches a click on a dynamic [me.delyfss.cocal.menu.runtime.PageSource]-backed
     * pagination slot. Unlike static shape items (pre-compiled at [registerMenu]), a page item's
     * config is produced per-render, so its actions are compiled here on click. [slotKey] is the raw
     * inventory slot for chest menus, or the shape slot for PLAYER menus — matching how
     * [me.delyfss.cocal.menu.config.PaginationConfig.slots] is interpreted per type. Returns true
     * when a page item was found and dispatched.
     */
    internal fun dispatchPaginatedClick(
        player: Player,
        clickType: ClickType,
        compiled: CompiledMenu,
        session: MenuSession,
        slotKey: Int
    ): Boolean {
        val pagination = compiled.config.pagination ?: return false
        val source = PageSourceRegistry.get(compiled.id) ?: return false
        val indexInPage = pagination.slots.indexOf(slotKey)
        if (indexInPage < 0) return false

        val context = MenuContext(player, compiled.id, session, session.placeholders)
        val total = source.size(context)
        val pageSize = pagination.slots.size.coerceAtLeast(1)
        val totalPages = ((total + pageSize - 1) / pageSize).coerceAtLeast(1)
        val page = session.page.coerceIn(0, totalPages - 1)
        val itemIndex = page * pageSize + indexInPage
        if (itemIndex >= total) return false

        val itemConfig = source.itemAt(itemIndex, context)
        // Memoize per config value: identical page items (data-class equality) reuse their parsed
        // actions/requirements instead of re-running the parser on every click.
        val compiledItem = pageItemCache[itemConfig] ?: compiler.compileItem(itemConfig).also {
            if (pageItemCache.size >= pageItemCacheLimit) pageItemCache.clear()
            pageItemCache[itemConfig] = it
        }
        dispatchItemClick(player, clickType, compiled, session, compiledItem)
        return true
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
                    val history = session.history.toList()   // remaining stack after the pop
                    val carried = session.placeholders
                    if (compiled.config.type == MenuType.PLAYER) {
                        // Cleanly exit this PLAYER view before opening the next.
                        restorePlayerInventory(player, session)
                        sessions.remove(player.uniqueId)
                    }
                    openInternal(player, previous, carried, history)
                }
            }
            session.openMenuRequest != null -> {
                val target = session.openMenuRequest!!
                session.openMenuRequest = null
                val history = session.history.toList() + session.menuId
                val carried = session.placeholders
                if (compiled.config.type == MenuType.PLAYER) {
                    restorePlayerInventory(player, session)
                    sessions.remove(player.uniqueId)
                }
                openInternal(player, target, carried, history)
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
        val context = MenuContext(player, session.menuId, session, session.placeholders)
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
        val context = MenuContext(player, session.menuId, session, session.placeholders)
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

    private companion object {
        /** Max retained navigation-history depth per session (bounds cyclic forward navigation). */
        const val MAX_HISTORY = 32
    }
}
