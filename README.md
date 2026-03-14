# Cocal Config

A lightweight HOCON-based configuration helper for Kotlin/JVM plugins.

## What is new in 1.3

- Config comments via annotations:
  - `@Comment(...)`
  - `@SectionComment(...)`
- Locale-aware messages with file layout:
  - `messages.conf` (base)
  - `languages/<locale>.conf` (overrides)
- Locale fallback chain:
  - exact locale -> language -> configured default locale -> base file

## Quick start

```kts
repositories {
    mavenCentral()
    maven("https://jitpack.io")
}

dependencies {
    implementation("com.github.RpMGrut:cocal:v1.4")
}
```

## Config models

```kotlin
import me.delyfss.cocal.Comment
import me.delyfss.cocal.Config
import me.delyfss.cocal.Path
import me.delyfss.cocal.SectionComment

enum class BarColor { RED, BLUE }

data class BossBar(
    @Comment("Enable boss bar")
    val enabled: Boolean = true,
    @Comment("Displayed text")
    val text: String = "<#62f5b1>Battle in <time>",
    val color: BarColor = BarColor.RED
)

data class BattleConfig(
    @Comment("Main switch")
    val enabled: Boolean = false,
    @Comment("Countdown sequence")
    @Path("countdown-seconds")
    val countdownSeconds: List<Int> = listOf(5, 4, 3, 2, 1),
    @SectionComment("Tool settings")
    val tool: Tool = Tool(),
    @SectionComment("Named boss bar presets")
    @Path("boss-bars")
    val bossBars: Map<String, BossBar> = mapOf(
        "pvp" to BossBar(),
        "nether" to BossBar(text = "Nether opens in <time>", color = BarColor.BLUE)
    )
)

data class Tool(
    val material: String = "STONE_AXE",
    @Path("display-name")
    val displayName: String = "<#7bff6b><bold>Mega Axe</bold>",
    val lore: List<String> = listOf(
        "<gray>Right-click - place flask",
        "<gray>Left-click on barrier - remove"
    )
)

class ConfigManager(private val plugin: JavaPlugin) {
    private val loader = Config(plugin.dataFolder, "battle.conf", BattleConfig())

    fun reload(): BattleConfig = loader.load()
}
```

## Config options

```kotlin
val loader = Config(
    plugin.dataFolder,
    "battle.conf",
    BattleConfig(),
    Config.Options(
        header = listOf("Battle Royale", "https://github.com/your-repo"),
        prettyPrint = true,
        alwaysWriteFile = true,
        commentsEnabled = true,
        commentPrefix = "# "
    )
)
```

Map keys support:

- `String`
- `Int`
- `Double`
- `Enum`

Options:

- `header`: comment block at top of file
- `prettyPrint`: formatted output
- `alwaysWriteFile`: re-render merged file on each load
- `commentsEnabled`: render annotation comments
- `commentPrefix`: comment prefix for generated comment lines

## Dynamic config (auto-save)

```kotlin
class MenuStateConfig(folder: File) : DynamicConfig(
    folder,
    "menu-state.conf",
    DynamicConfig.Options(
        debounceDelayMs = 1000,
        commentsEnabled = true
    )
) {
    @Path("last-opened")
    @Comment("Unix millis of last open")
    var lastOpened: Long = 0L

    var tabs: MutableList<String> = mutableListOf()
}

val state = MenuStateConfig(plugin.dataFolder)

state.update {
    lastOpened = System.currentTimeMillis()
    tabs.add("main")
}
```

Notes:

- mutate inside `update { ... }`
- call `close()` on plugin disable

## Localized messages (1.3)

### File layout

```text
plugins/YourPlugin/
  messages.conf
  languages/
    en-US.conf
    ru-RU.conf
    ru.conf
```

### Base file (`messages.conf`)

```hocon
messages-meta {
  default-locale = "en-US"
}

messages {
  prefix = "<#7bff6b><bold>Server</bold> <gray>> </gray>"

  ability.cooldown {
    chat = "<prefix><#ffad42>Ability cooldown: <white><time></white>s"
    actionbar = "<#ffad42>Ready in <time>s"
  }

  command.usage = "<prefix><gray>Usage: <#f9c23c>/server <white>(give|reload)</white>"
}
```

### Locale override (`languages/ru-RU.conf`)

```hocon
messages {
  ability.cooldown {
    chat = "<prefix><#ffad42>Перезарядка: <white><time></white>с"
    actionbar = "<#ffad42>Готово через <time>с"
  }

  command.usage = "<prefix><gray>Использование: <#f9c23c>/server <white>(give|reload)</white>"
}
```

### Fallback chain

For locale `ru-RU`:

1. `languages/ru-RU.conf`
2. `languages/ru.conf`
3. locale from `messages-meta.default-locale`
4. `messages.conf` base

If `languages/` does not exist, behavior is the same as 1.2.

## Messages API

```kotlin
class MessageExample(private val plugin: JavaPlugin) {
    private val messages = Messages.fromFile(
        plugin = plugin,
        fileProvider = { File(plugin.dataFolder, "messages.conf") },
        logger = plugin.logger,
        rootPath = "messages",
        onCorrupted = { _, _ ->
            plugin.getResource("messages.conf")?.reader()?.readText()
        }
    )

    fun init() = messages.load()

    fun autoLocale(player: Player) {
        // Backward-compatible API: locale is auto-detected from player.locale
        messages.send(player, "ability.cooldown", mapOf("time" to "4"))
    }

    fun forcedLocale(sender: CommandSender) {
        // New 1.3 API
        messages.sendLocalized(sender, "ability.cooldown", "ru-RU", mapOf("time" to "4"))
    }

    fun plain(locale: String): String? {
        return messages.plainLocalized("command.usage", locale)
    }
}
```

Available channels inside one message template remain the same:

- chat (`chat`, `text`, `lines`)
- actionbar (`actionbar` / `action-bar`)
- titlebar (`titlebar` string/object)
- sound (`sound` string/object)

## Migration from 1.2

No breaking migration is required.

1. Existing `Config`, `DynamicConfig`, `Messages` usage keeps working.
2. You can adopt comments incrementally by adding `@Comment` / `@SectionComment`.
3. You can adopt locale files incrementally by creating `languages/*.conf`.
4. Old methods (`send`, `plain`, `template`, `raw`) stay valid.

## Troubleshooting

### Locale file is broken

If a locale file has syntax/type errors:

- a backup like `ru-RUsave-2026-02-21-12-34-56.conf` is created
- warning is logged
- that locale is skipped
- fallback continues using remaining chain

### Broken base `messages.conf`

`Messages.fromFile(..., onCorrupted = { ... })` can restore file text from resource or custom fallback.

### Config parse/type issues

`Config` now recovers invalid values selectively:

- only the invalid path is rolled back to its default value
- warning contains file, line, path, bad value preview, and recovery action
- other valid user overrides stay untouched

Backups (`*save-...conf`) are created only for global recovery scenarios (for example syntax corruption or unrecoverable type errors).

Special case for dynamic maps:

- if a custom map entry has an invalid value and no default exists for that exact path, `Config` performs a global backup/reset

## Legacy mutable field models

Still supported:

```kotlin
class MenuConfig {
    @Path("menu.title")
    var title: String = "<green>Test"
}

val config = Config(folder, "menu.conf", MenuConfig()).load()
```

You can migrate to data classes gradually.
