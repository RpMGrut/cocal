package me.delyfss.cocal.message

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigRenderOptions
import com.typesafe.config.ConfigValueType
import net.kyori.adventure.text.Component
import net.kyori.adventure.title.Title
import org.bukkit.Sound
import org.bukkit.SoundCategory
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import me.delyfss.cocal.util.FileBackups
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.HashMap
import java.util.logging.Logger

class Messages(
    private val configSupplier: () -> Config,
    private val logger: Logger,
    private val options: Options = Options(),
    private val plugin: JavaPlugin? = null
) {

    fun ensureDefaults(file: File, defaultPath: String? = null) {
        if (plugin == null) {
            logger.warning("ensureDefaults requires a plugin instance to read default resources")
            return
        }
        val userConfig = try {
            ConfigFactory.parseFile(file)
        } catch (e: Exception) {
            val contents = runCatching { file.readText() }.getOrNull()
            FileBackups.backup(file, contents)
            logger.severe("${e.message}. Error while reading user config.")
            return
        }

        val path = defaultPath ?: runCatching {
            plugin.dataFolder.toPath()
                .relativize(file.toPath())
                .toString()
                .replace('\\', '/')
        }.getOrNull()

        if (path.isNullOrBlank()) {
            logger.warning("Unable to resolve default resource path for ${file.path}. Provide defaultPath explicitly.")
            return
        }

        val stream = plugin.getResource(path)
        if (stream == null) {
            logger.warning("Resource $path not found in jar, skipping default config")
            return
        }

        val defaultConfig = try {
            stream.use { ConfigFactory.parseReader(it.reader(StandardCharsets.UTF_8)) }
        } catch (e: Exception) {
            logger.severe("${e.message}. Error while reading default config.")
            return
        }

        file.bufferedWriter(StandardCharsets.UTF_8).use { writer ->
            val renderOptions = ConfigRenderOptions.defaults()
                .setOriginComments(false)
                .setJson(false)
                .setFormatted(true)

            val merged = userConfig.withFallback(defaultConfig).resolve()
            writer.write(merged.root().render(renderOptions))
        }
    }

    data class Options(
        val rootPath: String = "messages",
        val sharedPlaceholders: Map<String, String> = emptyMap()
    )

    private var config: Config = ConfigFactory.empty()
    private val placeholderHandler = PlaceholderHandler(logger)
    private var sharedReplacements: Map<String, String> = options.sharedPlaceholders

    fun load() = reload()

    fun reload() {
        config = configSupplier().resolve()
        sharedReplacements = resolveSharedReplacements()
    }

    fun raw(path: String): MessageTemplate? = template(path)

    fun template(path: String): MessageTemplate? {
        val fullPath = toFullPath(path)
        if (!config.hasPath(fullPath)) return null
        val value = config.getValue(fullPath)
        return when (value.valueType()) {
            ConfigValueType.STRING -> MessageTemplate(chatLines = listOf(config.getString(fullPath)))
            ConfigValueType.LIST -> MessageTemplate(chatLines = config.getStringList(fullPath))
            ConfigValueType.OBJECT -> parseObject(config.getConfig(fullPath), path)
            else -> null
        }
    }

    fun plain(path: String, player: Player? = null, replacements: Map<String, String> = emptyMap()): String? {
        val template = template(path) ?: return null
        val firstLine = template.chatLines.firstOrNull() ?: return null
        return placeholderHandler.plain(firstLine, player, mergeReplacements(replacements))
    }

    fun send(sender: CommandSender, path: String, replacements: Map<String, String> = emptyMap()) {
        val template = template(path) ?: return
        deliver(sender, template, replacements)
    }

    fun sendMany(
        recipients: Iterable<CommandSender>,
        path: String,
        replacements: Map<String, String> = emptyMap()
    ) {
        val template = template(path) ?: return
        recipients.forEach { deliver(it, template, replacements) }
    }

    private fun deliver(sender: CommandSender, template: MessageTemplate, replacements: Map<String, String>) {
        val merged = mergeReplacements(replacements)
        val player = sender as? Player
        val chatComponents = placeholderHandler.componentLines(template.chatLines, player, merged)
        if (chatComponents.isNotEmpty()) {
            chatComponents.forEach { sender.sendMessage(it) }
        }

        template.actionBar?.let { actionText ->
            if (player != null) {
                placeholderHandler.component(actionText, player, merged)?.let { component ->
                    player.sendActionBar(component)
                }
            }
        }

        template.titleBar?.let { titleSpec ->
            if (player != null) {
                val titleComponent = placeholderHandler.component(titleSpec.title, player, merged) ?: Component.empty()
                val subtitleComponent = placeholderHandler.component(titleSpec.subtitle, player, merged) ?: Component.empty()
                val times = Title.Times.times(
                    ticksToDuration(titleSpec.fadeIn),
                    ticksToDuration(titleSpec.stay),
                    ticksToDuration(titleSpec.fadeOut)
                )
                player.showTitle(Title.title(titleComponent, subtitleComponent, times))
            }
        }

        template.sound?.let { soundSpec ->
            if (player != null) {
                val category = soundSpec.category ?: SoundCategory.MASTER
                player.playSound(player.location, soundSpec.sound, category, soundSpec.volume, soundSpec.pitch)
            }
        }
    }

    private fun parseObject(section: Config, path: String): MessageTemplate {
        val lines = mutableListOf<String>()
        lines += collectStrings(section, "chat")
        lines += collectStrings(section, "text")
        lines += collectStrings(section, "lines")
        val actionBar = section.getStringOrNull("actionbar", "action-bar")
        val titleBar = parseTitleBar(section, path)
        val sound = parseSound(section, path)
        return MessageTemplate(lines, actionBar, titleBar, sound)
    }

    private fun parseTitleBar(section: Config, path: String): TitleBar? {
        if (!section.hasPath("titlebar")) return null
        val value = section.getValue("titlebar")
        return when (value.valueType()) {
            ConfigValueType.STRING -> TitleBar(value.unwrapped().toString(), null, DEFAULT_FADE_IN, DEFAULT_STAY, DEFAULT_FADE_OUT)
            ConfigValueType.OBJECT -> {
                val titleConfig = section.getConfig("titlebar")
                TitleBar(
                    title = titleConfig.getStringOrNull("title"),
                    subtitle = titleConfig.getStringOrNull("subtitle"),
                    fadeIn = titleConfig.getIntOrDefault(DEFAULT_FADE_IN, "fade-in", "fadeIn"),
                    stay = titleConfig.getIntOrDefault(DEFAULT_STAY, "stay"),
                    fadeOut = titleConfig.getIntOrDefault(DEFAULT_FADE_OUT, "fade-out", "fadeOut")
                )
            }
            else -> {
                logger.warning("Invalid titlebar format at '$path.titlebar'")
                null
            }
        }
    }

    private fun parseSound(section: Config, path: String): SoundSpec? {
        if (!section.hasPath("sound")) return null
        val value = section.getValue("sound")
        return when (value.valueType()) {
            ConfigValueType.STRING -> soundFromString(value.unwrapped().toString(), path)
            ConfigValueType.OBJECT -> {
                val soundConfig = section.getConfig("sound")
                val name = soundConfig.getStringOrNull("name", "id")
                soundFromString(name, path)?.copy(
                    volume = soundConfig.getDoubleOrDefault("volume", 1.0).toFloat(),
                    pitch = soundConfig.getDoubleOrDefault("pitch", 1.0).toFloat(),
                    category = soundConfig.getStringOrNull("category")?.let(::parseSoundCategory)
                )
            }
            else -> {
                logger.warning("Unsupported sound node at '$path.sound'")
                null
            }
        }
    }

    private fun soundFromString(name: String?, path: String): SoundSpec? {
        if (name.isNullOrBlank()) {
            logger.warning("Sound name missing at '$path.sound'")
            return null
        }
        return try {
            val sound = Sound.valueOf(name.uppercase())
            SoundSpec(sound, 1f, 1f, null)
        } catch (_: IllegalArgumentException) {
            logger.warning("Unknown sound '$name' at '$path.sound'")
            null
        }
    }

    private fun collectStrings(section: Config, key: String): List<String> {
        if (!section.hasPath(key)) return emptyList()
        return when (section.getValue(key).valueType()) {
            ConfigValueType.STRING -> listOf(section.getString(key))
            ConfigValueType.LIST -> section.getStringList(key)
            else -> emptyList()
        }
    }

    private fun mergeReplacements(extra: Map<String, String>): Map<String, String> {
        if (sharedReplacements.isEmpty()) return extra
        if (extra.isEmpty()) return sharedReplacements
        val merged = HashMap<String, String>(sharedReplacements.size + extra.size)
        merged.putAll(sharedReplacements)
        merged.putAll(extra)
        return merged
    }

    private fun resolveSharedReplacements(): Map<String, String> {
        val base = if (options.sharedPlaceholders.isEmpty()) emptyMap() else HashMap(options.sharedPlaceholders)
        val prefixPath = toFullPath("prefix")
        if (config.hasPath(prefixPath)) {
            val prefix = config.getString(prefixPath)
            if (prefix.isNotBlank()) {
                if (base.isEmpty()) {
                    return mapOf("prefix" to prefix)
                }
                val merged = HashMap(base)
                merged["prefix"] = prefix
                return merged
            }
        }
        return base
    }

    private fun toFullPath(path: String): String {
        if (path.isEmpty()) return options.rootPath
        return "${options.rootPath}.$path"
    }

    private fun ticksToDuration(ticks: Int): Duration {
        val clamped = ticks.coerceAtLeast(0)
        return Duration.ofMillis(clamped * 50L)
    }

    private fun Config.getStringOrNull(vararg keys: String): String? {
        keys.forEach { key ->
            if (hasPath(key)) {
                val value = getValue(key)
                return when (value.valueType()) {
                    ConfigValueType.STRING -> getString(key)
                    ConfigValueType.NULL -> null
                    else -> null
                }
            }
        }
        return null
    }

    private fun Config.getIntOrDefault(default: Int, vararg keys: String): Int {
        keys.forEach { key ->
            if (hasPath(key)) return getInt(key)
        }
        return default
    }

    private fun Config.getDoubleOrDefault(key: String, default: Double): Double {
        return if (hasPath(key)) getDouble(key) else default
    }

    private fun parseSoundCategory(name: String): SoundCategory? {
        return runCatching { SoundCategory.valueOf(name.uppercase()) }
            .getOrElse {
                logger.warning("Unknown sound category '$name'")
                null
            }
    }

    companion object {
        private const val DEFAULT_FADE_IN = 10
        private const val DEFAULT_STAY = 70
        private const val DEFAULT_FADE_OUT = 20

        @Deprecated(
            message = "This method doesn't support server startup checks. Use the overload with the plugin parameter instead.",
            replaceWith = ReplaceWith(
                "Messages.fromFile(plugin, fileProvider, logger, rootPath, sharedPlaceholders, onCorrupted)"
            ),
            level = DeprecationLevel.WARNING)
        fun fromFile(
            fileProvider: () -> File,
            logger: Logger,
            rootPath: String = "messages",
            sharedPlaceholders: Map<String, String> = emptyMap(),
            onCorrupted: ((File, Exception) -> String?)? = null
        ): Messages {
            val supplier = {
                val file = fileProvider()
                loadFromFile(file, onCorrupted)
            }
            return Messages(supplier, logger, Options(rootPath, sharedPlaceholders))
        }

        fun fromFile(
            plugin: JavaPlugin,
            fileProvider: () -> File,
            logger: Logger,
            rootPath: String = "messages",
            sharedPlaceholders: Map<String, String> = emptyMap(),
            onCorrupted: ((File, Exception) -> String?)? = null
        ): Messages {
            val supplier = {
                val file = fileProvider()
                loadFromFile(file, onCorrupted)
            }
            return Messages(supplier, logger, Options(rootPath, sharedPlaceholders), plugin)
        }

        private fun loadFromFile(
            file: File,
            onCorrupted: ((File, Exception) -> String?)?
        ): Config {
            if (!file.exists()) {
                throw IllegalStateException("Messages file not found: ${file.absolutePath}")
            }
            return try {
                ConfigFactory.parseFile(file).resolve()
            } catch (ex: Exception) {
                val contents = runCatching { file.readText() }.getOrNull()
                FileBackups.backup(file, contents)
                val replacement = onCorrupted?.invoke(file, ex)
                if (replacement != null) {
                    file.writeText(replacement)
                    ConfigFactory.parseString(replacement).resolve()
                } else {
                    throw ex
                }
            }
        }
    }
}
