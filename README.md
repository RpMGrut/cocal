# Cocal

Kotlin library for Paper/Spigot plugins. Provides HOCON config loading, localized MiniMessage support, a config-driven menu/GUI subsystem, Database (HikariCP), and Redis/Dragonfly (Lettuce) out of a single dependency.

## What is new in 1.6

- **Menu per-click action lists** (DeluxeMenus parity) — each item can declare `left-actions`, `right-actions`, `shift-left-actions`, `shift-right-actions`, `middle-actions` in addition to the general `actions` fallback. See the dedicated section below.
- **`PLAYER`-type menus** now do the full save → overwrite → restore cycle. The player's inventory is snapshot on open, replaced with menu items, and restored on close / quit / service disable.
- **Config legacy loader** reads `@Path` through Kotlin reflection, fixing a silent 0-byte config-wipe bug that hit Kotlin 2.3+ plugins with `var`-based models. The writer also refuses to overwrite a non-empty file with an empty blueprint.
- **Zero build warnings.**

## What was added in 1.5

- **Messages**
  - `rawString(path)` / `rawStringLocalized(path, locale)` — fetch the unprocessed string (no MiniMessage, no placeholders, no Adventure). Useful for logs and custom processing.
  - `Map<String, Component>` placeholder overloads on every public method (`plain`, `send`, `sendLocalized`, `sendMany`, `plainLocalized`). Component formatting is preserved via MiniMessage `TagResolver`.
  - `miniMessage(path, …)` / `miniMessageLocalized(…)` — same as `plain` but returns a `Component` directly.
- **Config**
  - Bukkit-style enum values and keys are now normalised automatically. `type = "minecraft:gray_stained_glass_pane"` and `type = "gray-stained-glass-pane"` both resolve to `Material.GRAY_STAINED_GLASS_PANE`. Works for any `Enum<*>` — `Material`, `EntityType`, `PotionType`, `Sound`, etc.
- **Menu / GUI subsystem** (new — `me.delyfss.cocal.menu`)
  - Config-driven, shape-based menus with 19 inventory types (CHEST, ANVIL, HOPPER, WORKBENCH, …).
  - Built-in actions: `[close]`, `[player]`, `[console]`, `[message]`, `[sound]`, `[openmenu]`, `[back]`, `[refresh]`, `[scroll up|down|left|right]`.
  - Custom actions via `Actions.register(factory)`.
  - Built-in `[permission]` click requirement; extensible registry for more.
  - Pagination & scrolling via `PageSource` interface.
  - `MenuProtectionListener` blocks drag/drop/pickup/shift-click with a 75 ms debounce. Only cocal inventories are touched — regular chests/anvils/etc. are ignored.
- **Database subsystem** (new — `me.delyfss.cocal.database`)
  - HikariCP pool, MySQL/MariaDB/SQLite drivers.
  - `DatabaseService.withConnection { }` and `transaction { }` helpers (auto-commit, rollback on exception).
- **Redis / Dragonfly subsystem** (new — `me.delyfss.cocal.cache`)
  - Lettuce-backed, fully async (`CompletableFuture`), works against any Redis-protocol server.
  - Key/value, hash, set, and pub/sub operations.
  - Graceful degradation — when disabled or unreachable, every operation short-circuits safely.
- **Distribution** — cocal ships as both a library (for `compileOnly`) and a runnable plugin (`plugins/cocal.jar`). The core plugin wires up one shared menu listener, one DB pool, and one Redis client per server, exposed via `Bukkit.getServicesManager()`.

## Requirements

- Java 21+
- Paper / Spigot 1.20.4+

## Quick start

```kts
repositories {
    mavenCentral()
    maven("https://jitpack.io")
    maven("https://repo.nekroplex.com/releases")
}

dependencies {
    compileOnly("com.github.RpMGrut:cocal:v1.6")
}
```

Install `cocal-1.6.jar` into your server's `plugins/` directory. Downstream plugins then access shared services:

```kotlin
import me.delyfss.cocal.Cocal

class MyPlugin : JavaPlugin() {
    override fun onEnable() {
        val menus = Cocal.menus
        menus.registerMenu("main", loadMenuConfig())
        // ...
    }
}
```

Or construct services yourself (library-only mode):

```kotlin
val menuService = MenuService(plugin).also { it.enable() }
val database = DatabaseService(DatabaseConfig(...), logger).also { it.start() }
val redis = RedisService(RedisConfig(...), logger).also { it.start() }
```

## Config models

```kotlin
import me.delyfss.cocal.Comment
import me.delyfss.cocal.Config
import me.delyfss.cocal.Path
import me.delyfss.cocal.SectionComment
import org.bukkit.Material

data class Tool(
    @Comment("Item material (namespaced keys accepted)")
    val material: Material = Material.STONE_AXE,
    @Path("display-name")
    val displayName: String = "<#7bff6b><bold>Mega Axe</bold>",
    val lore: List<String> = listOf(
        "<gray>Right-click - place flask",
        "<gray>Left-click on barrier - remove"
    )
)

data class BattleConfig(
    @Comment("Main switch")
    val enabled: Boolean = false,
    @Path("countdown-seconds")
    val countdownSeconds: List<Int> = listOf(5, 4, 3, 2, 1),
    @SectionComment("Tool settings")
    val tool: Tool = Tool()
)

class ConfigManager(private val plugin: JavaPlugin) {
    private val loader = Config(plugin.dataFolder, "battle.conf", BattleConfig())
    fun reload(): BattleConfig = loader.load()
}
```

### Bukkit enum auto-mapping (1.5)

Any of these HOCON values resolve to `Material.GRAY_STAINED_GLASS_PANE`:

```hocon
type = "GRAY_STAINED_GLASS_PANE"
type = "gray_stained_glass_pane"
type = "minecraft:gray_stained_glass_pane"
type = "gray-stained-glass-pane"
type = "Gray Stained Glass Pane"
```

The same applies to map keys and list elements typed as any `Enum<*>`.

## Messages API

```kotlin
val messages = Messages.fromFile(
    plugin = plugin,
    fileProvider = { File(plugin.dataFolder, "messages.conf") },
    logger = plugin.logger,
    options = Messages.Options(
        rootPath = "messages",
        parserBackend = Messages.ParserBackend.MINI_MESSAGE
    )
)
messages.load()

// Existing plain/send methods still work:
messages.send(player, "ability.cooldown", mapOf("time" to "4"))

// New in 1.5 — raw string getter for logs/custom processing
val raw = messages.rawString("ability.cooldown")

// New in 1.5 — Map<String, Component> placeholder overloads preserve component formatting
val username = Component.text("Alex", NamedTextColor.GOLD)
messages.send(
    player,
    "greeting",
    replacements = emptyMap(),
    componentReplacements = mapOf("username" to username)
)

// New in 1.5 — miniMessage(path) returns a parsed Component
val welcome: Component? = messages.miniMessage("welcome")
```

### Locale fallback chain

Unchanged from 1.4. See the 1.4 docs inside this file's history.

## Menu / GUI subsystem

### Config example (TZ-style shape)

```hocon
main {
  type = "CHEST"
  name = "<#62aef5>City Buildings"
  size = 54

  shape = [
    "AAAAAAAAA",
    "A#A#A#A#A",
    "A#A#A#A#A",
    "AAAAAAAAA",
    "A   C   A",
    "AAAAAAAAA"
  ]

  items {
    A {
      type = "gray_stained_glass_pane"
      name = ""
      lore = []
    }

    C {
      type = "barrier"
      name = "<#e35b5b>Close"
      lore = [
        "<#dadde8>Closes the menu"
      ]
      click-requirements = ["[permission] mycity.menu"]
      deny-actions = ["[message] <red>You don't have permission"]
      actions = ["[close]"]
    }
  }
}
```

Load and open it:

```kotlin
val loaded = Config(plugin.dataFolder, "main.conf", MenuConfig()).load()
Cocal.menus.registerMenu("main", loaded)
Cocal.menus.open(player, "main")
```

### Shape rules

- Each character in each row corresponds to one inventory slot.
- `#` and space characters are *empty* slots.
- Every non-empty character must have a matching key in `items`.
- The inventory size is dictated by the `size` field, not the shape dimensions.

### Supported inventory types

`CHEST` (default, variable size 9/18/27/36/45/54), `ENDER_CHEST`, `BARREL`, `SHULKER_BOX`, `ANVIL`, `BEACON`, `BLAST_FURNACE`, `BREWING`, `CARTOGRAPHY`, `DISPENSER`, `DROPPER`, `ENCHANTING`, `FURNACE`, `GRINDSTONE`, `HOPPER`, `LOOM`, `PLAYER`, `SMOKER`, `WORKBENCH`.

Non-chest inventories have a fixed slot count from vanilla Bukkit; the `size` field is ignored for them.

### Built-in actions

| Tag | Argument | Description |
|---|---|---|
| `[close]` | — | Closes the menu for the clicker |
| `[player]` | `<command>` | Runs the command as the player |
| `[console]` | `<command>` | Runs the command as console |
| `[message]` | `<minimessage>` | Sends a MiniMessage line to the player |
| `[sound]` | `<name>[:volume[:pitch]]` | Plays a Bukkit `Sound` |
| `[openmenu]` | `<id>` | Navigates to another registered menu |
| `[back]` | — | Pops the menu history stack |
| `[refresh]` | — | Re-renders the current menu |
| `[scroll up\|down\|left\|right]` | `[step]` | Mutates `MenuContext.scrollOffset` and refreshes |

### Per-click action lists (DeluxeMenus parity)

Each item supports a general `actions` list plus five click-type-specific lists. When a click type has its own non-empty list, only that list runs; otherwise `actions` is the fallback.

```hocon
items {
  K {
    type = "diamond_sword"
    name = "<gold>Kit"
    actions = [
      "[message] <gray>Pick a click: LMB=free, RMB=premium, Shift+LMB=info"
    ]
    left-actions = [
      "[player] kit free"
      "[sound] entity_experience_orb_pickup"
    ]
    right-actions = [
      "[player] kit premium"
    ]
    shift-left-actions = [
      "[message] <#62aef5>Kits give you a starter loadout"
    ]
    shift-right-actions = [
      "[console] broadcast <player> opened the kit menu"
    ]
    middle-actions = [
      "[refresh]"
    ]
  }
}
```

Click → list mapping:

| Bukkit `ClickType` | List used | Fallback if empty |
|---|---|---|
| `LEFT` | `left-actions` | `actions` |
| `RIGHT` | `right-actions` | `actions` |
| `SHIFT_LEFT` | `shift-left-actions` | `actions` (**not** left-actions) |
| `SHIFT_RIGHT` | `shift-right-actions` | `actions` (**not** right-actions) |
| `MIDDLE` | `middle-actions` | `actions` |
| any other (number keys, drops, double-click, etc.) | — | `actions` |

Same item can still declare `click-requirements` and `deny-actions`; they apply uniformly across every click type. If you need per-click permission gates, use multiple requirements/deny-actions inside the individual click lists instead.

### Custom actions

```kotlin
object GiveMoneyActionFactory : ActionFactory {
    override val tag = "givemoney"
    override fun create(argument: String): Action = object : Action {
        private val amount = argument.toDoubleOrNull() ?: 0.0
        override fun run(context: ActionContext) {
            Economy.add(context.player, amount)
        }
    }
}

Actions.register(GiveMoneyActionFactory)
```

### Click requirements (1.5)

Only `[permission] <node>` ships in 1.5. The `Requirement` / `RequirementFactory` interfaces are extensible — custom requirements register the same way as actions.

### Pagination

```kotlin
PageSourceRegistry.bind("buildings_menu", object : PageSource {
    override fun size(context: MenuContext): Int = repository.count()
    override fun itemAt(index: Int, context: MenuContext): MenuItemConfig =
        repository[index].toMenuItem()
})
```

### Protection listener

Registered exactly once by `MenuService.enable()`. It guards every cocal menu against click/drag/drop/pickup exploits. It never touches non-cocal inventories — every callback short-circuits on `inventory.holder !is MenuHolder`.

## Database subsystem

```kotlin
val database = DatabaseService(
    config = DatabaseConfig(
        driver = DatabaseDriver.MYSQL,
        url = "localhost:3306/mydb",
        user = "root",
        password = "…",
        maximumPoolSize = 10
    ),
    logger = plugin.logger
)
database.start()

database.transaction { connection ->
    connection.prepareStatement("INSERT INTO players(uuid, name) VALUES (?, ?)").use {
        it.setString(1, player.uniqueId.toString())
        it.setString(2, player.name)
        it.executeUpdate()
    }
}
```

MySQL, MariaDB, and SQLite are supported. SQLite is bundled at runtime; MySQL/MariaDB drivers must be supplied by the consuming plugin.

## Redis / Dragonfly subsystem

Works identically against Redis, Dragonfly, or KeyDB — cocal only uses the Redis wire protocol.

```kotlin
val redis = RedisService(
    config = RedisConfig(
        enabled = true,
        uri = "redis://localhost:6379"
    ),
    logger = plugin.logger
)
redis.start()

if (redis.isAvailable) {
    redis.setWithTtl("cache:player:$uuid", json, ttlSeconds = 300).join()
    val cached = redis.get("cache:player:$uuid").join()

    val token = redis.subscribe("cocal:broadcast") { message ->
        logger.info("received $message")
    }
}
```

When `enabled = false` or the connection fails, `isAvailable` stays `false` and every method returns a safe default (empty, `false`, `0`, or `null`) so plugins can keep running.

## Core plugin

`cocal-1.6.jar` drops into `plugins/` like any other plugin. On first run it creates `plugins/cocal/core.conf`:

```hocon
database {
  enabled = false
  driver = "SQLITE"
  url = "plugins/cocal/cocal.db"
  user = ""
  password = ""
  maximum-pool-size = 10
  minimum-idle = 2
}

redis {
  enabled = false
  uri = "redis://localhost:6379"
  connection-timeout-millis = 5000
  client-name = "cocal"
}
```

Edit this file to enable the shared DB pool and Redis client, then restart the server. Downstream plugins access the shared services via `Cocal.menus`, `Cocal.database`, and `Cocal.redis`.

### PLAYER-type menus

`MenuType.PLAYER` is a special inventory type where the "GUI" is the player's own inventory. Useful for kit selectors, custom hotbars, lobby menus, etc. cocal handles the full save / overwrite / restore cycle:

1. On `open()`, cocal snapshots `player.inventory.contents` into the session.
2. The storage slots (0..35) are cleared and filled with items from the menu shape.
3. On `[close]`, player quit, or service disable, the saved contents are written back.

Shape slot → player inventory slot mapping (see `PlayerMenuSlots`):

| Shape row | Player inventory slots | Visual position |
|---|---|---|
| 0 | 9..17 | top row of main inventory |
| 1 | 18..26 | middle row of main inventory |
| 2 | 27..35 | bottom row of main inventory |
| 3 | 0..8 | hotbar |

Rows beyond 3 are ignored. Armor and offhand are not touched by the shape but are preserved in the snapshot and restored correctly.

While a PLAYER menu is active, the protection listener cancels: item drops, drag operations, inventory clicks that would move menu items, and F-key offhand swaps. Clicks on menu slots inside the player's own inventory (opened with E) are routed through the normal action dispatch path.

## Not realised in 1.5

Per TZ section 13, these items were explicitly scoped out:

- **Requirements beyond `[permission]`** — `HasItem`, `HasMoney`, `Regex`, `StringLength`, JavaScript and proximity requirements from DeluxeMenus. The `Requirement` registry is ready; the factories are not shipped. Adding them is one file each — see the "Custom actions" section for the pattern.
- **Embedded Redis for tests** — the real-Redis integration test is gated on `REDIS_TEST_URI`; no embedded server is shipped because Dragonfly isn't embeddable and `embedded-redis` projects are unmaintained.

## Migration from 1.4

No breaking changes. Existing `Config`, `DynamicConfig`, and `Messages` usage keeps working unchanged. The new subsystems are additive and opt-in.

## Troubleshooting

- **Menu not opening** — check that `MenuService.enable()` was called (it is called automatically when the cocal plugin is installed).
- **Items missing from shape** — the warning `Menu '…' shape references unknown item '…'` is logged when a shape character has no matching `items` key.
- **Redis `isAvailable == false`** — check the logs for the Lettuce connection exception; common causes are `enabled = false`, wrong URI, or firewall rules.
- **Database `IllegalStateException: DatabaseService not started`** — call `start()` before any `withConnection` / `transaction` invocation.
