# Cocal Config

A lightweight HOCON-based configuration helper focused on Bukkit/Spigot plugins but usable in any Kotlin/JVM project. The new iteration keeps a single Kotlin model as the source of truth and generates/merges `.conf` files automatically.

## Highlights

- Works best with immutable Kotlin `data class` models
- Automatically writes missing config files from the model defaults
- Supports nested data classes, enums, lists, sets and maps (including `Map<String, SomeDataClass>`)
- Legacy mutable models that relied on `@Path`-annotated fields still work for backwards compatibility
- Optional file headers and pretty formatting via `Config.Options`

## Quick start

```kts
repositories {
    mavenCentral()
    maven("https://jitpack.io") {
        name = "jitpack"
    }
}

dependencies {
    implementation("com.github.RpMGrut:cocal:version")
}
```

```kotlin
import me.delyfss.cocal.Config
import me.delyfss.cocal.Path

enum class BarColor { RED, BLUE }

data class BossBar(
    val enabled: Boolean = true,
    val text: String = "<#62f5b1>Battle in <time>",
    val color: BarColor = BarColor.RED
)

data class BattleConfig(
    val enabled: Boolean = false,
    @Path("countdown-seconds")
    val countdownSeconds: List<Int> = listOf(5, 4, 3, 2, 1),
    val tool: Tool = Tool(),
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
        "<gray>Right-click — place flask",
        "<gray>Left-click on barrier — remove"
    )
)

class ConfigManager(private val plugin: JavaPlugin) {
    private val loader = Config(plugin.dataFolder, "battle.conf", BattleConfig())

    fun reload(): BattleConfig = loader.load()
}
```

On first load the library will create `battle.conf` with the default values derived from the data class. When server admins change the file, only the overridden values are applied; the rest are taken from the Kotlin defaults. No more juggling between a `*.conf` template and a mirror "model" file.

## Auto migration & recovery

- Missing keys from newer plugin versions are appended to the real file using the model order while preserving edited values.
- Removed fields simply disappear from the saved config, so dead settings do not confuse admins.
- Map sections (like `boss-bars` or `items`) keep every custom entry untouched; only the schema defined in Kotlin is tidied.
- Any syntax/type error at load time results in the file being backed up as `examplesave-2025-06-10-15-32-05.conf` (timestamped once per unique content) and regenerated from defaults so the plugin keeps running.

## Path customisation

`@Path` can now be placed directly on constructor properties (no `@field:` prefix needed) as well as legacy fields. Use dotted values to address nested sections: `@Path("messages.reload.success") val reloadMessage = "..."`.

## Options

```kotlin
val loader = Config(
    plugin.dataFolder,
    "battle.conf",
    BattleConfig(),
    Config.Options(
        header = listOf("Battle Royale", "https://github.com/your-repo"),
        prettyPrint = true,
        alwaysWriteFile = true
    )
)
```

- `header` — optional comment block inserted at the top of generated files
- `prettyPrint` — toggles HOCON formatting
- `alwaysWriteFile` — if `true` (default) the merged file is re-rendered every load just like the legacy behaviour

## Legacy field models

Older usage still works:

```kotlin
class MenuConfig {
    @Path("menu.title")
    var title: String = "<green>Test"
}

val config = Config(folder, "menu.conf", MenuConfig()).load()
```

You can migrate step by step by introducing new data-class backed models without breaking existing code.

## MiniMessage-ready messages

messages can now drive every delivery channel (chat, actionbar, titlebar, sound) per entry:

```hocon
messages {
  prefix = "<#7bff6b><bold>Server</bold> <gray>» </gray>"

  ability.cooldown {
    chat = "<prefix><#ffad42>Ability still recharging. Remaining <white><time></white>sec."
    actionbar = "<#ffad42>The ability will be ready in <time>sec"
  }

  command.usage {
    titlebar {
      fade-in = 20
      stay = 80
      fade-out = 10
      title = "<#62aef5>Test"
      subtitle = "<#dadde8>Stage <#b6b8bf>- <#dadde8>Menu."
    }
    actionbar = "<#e35b5b>Information in chat"
    text = "<hover:show_text:''<#62aef5>Left-click <#b6b8bf>- <#dadde8>Put command /menu''><click:run_command:\"/menu\"><#dadde8>Use server menu..."
    sound = {
      name = "BLOCK_NOTE_BLOCK_BELL"
      volume = 1.0
      pitch = 1.1
    }
  }

  command.simple = "<prefix><gray>Usage: <#f9c23c>/server <white>(give|reload|gh)</white>"
}
```

Usage:

```kotlin
class MessageExample(private val plugin: JavaPlugin) {
    private val messages = Messages.fromFile(
        fileProvider = { File(plugin.dataFolder, "messages.conf") },
        logger = plugin.logger,
        rootPath = "messages",
        onCorrupted = { file, _ ->
            // Provide fallback text (e.g. bundled resource) when admins break the file
            plugin.getResource("messages.conf")?.reader()?.readText()
                ?: "messages { }"
        }
    )

    fun init() = messages.load()

    fun notifyCooldown(player: Player, seconds: Int) {
        messages.send(player, "ability.cooldown", mapOf("time" to seconds.toString()))
    }
}
```

- `chat`/`text`/`lines` entries are split on `<newline>` and sent sequentially.
- `actionbar`, `titlebar`, and `sound` are only applied to players (safe to omit any field you do not need).
- `titlebar` accepts either a string or an object with `fade-in`, `stay`, `fade-out`, `title`, and `subtitle` (ticks).
- `sound` accepts a string (`SOUND_NAME`) or an object with `name`, `volume`, `pitch`, and optional `category`.
- PlaceholderAPI placeholders (`%player_name%`, etc.) and custom `<key>` / `%key%` replacements are expanded automatically before MiniMessage parsing.
- The library never creates or saves `messages.conf` for you—pass any file/Config supplier via `Messages.fromFile { … }` or your own lambda that loads `Config` however you like.
