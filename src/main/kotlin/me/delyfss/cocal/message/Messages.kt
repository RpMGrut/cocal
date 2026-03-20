package me.delyfss.cocal.message

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigRenderOptions
import com.typesafe.config.ConfigValueType
import me.delyfss.cocal.util.FileBackups
import net.kyori.adventure.text.Component
import net.kyori.adventure.title.Title
import org.bukkit.Sound
import org.bukkit.SoundCategory
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.HashMap
import java.util.LinkedHashMap
import java.util.logging.Logger

class Messages(
    private val configSupplier: () -> Config,
    private val logger: Logger,
    private val options: Options = Options(),
    private val plugin: JavaPlugin? = null,
    private val localeConfigSupplier: (() -> Map<String, Config>)? = null
) {

    fun ensureDefaults(file: File, defaultPath: String? = null) {
        val plugin = plugin ?: run {
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

    enum class ParserBackend {
        MINI_MESSAGE,
        QUICK_MINI_MESSAGE
    }

    data class Options(
        val rootPath: String = "messages",
        val sharedPlaceholders: Map<String, String> = emptyMap(),
        val localesFolderName: String = "languages",
        val metaRootPath: String = "messages-meta",
        val defaultLocaleKey: String = "default-locale",
        val fallbackDefaultLocale: String = DEFAULT_FALLBACK_LOCALE,
        val parserBackend: ParserBackend = ParserBackend.MINI_MESSAGE
    )

    private var config: Config = ConfigFactory.empty()
    private var localeConfigs: Map<String, Config> = emptyMap()
    private val placeholderHandler = PlaceholderHandler(logger, options.parserBackend)
    private var sharedReplacements: Map<String, String> = options.sharedPlaceholders

    fun load() = reload()

    fun reload() {
        config = configSupplier().resolve()
        localeConfigs = runCatching { localeConfigSupplier?.invoke() ?: emptyMap() }
            .onFailure { ex ->
                logger.warning("Failed to load locale files: ${ex.message ?: ex::class.simpleName}")
            }
            .getOrDefault(emptyMap())
        sharedReplacements = options.sharedPlaceholders
    }

    fun raw(path: String): MessageTemplate? = template(path)

    fun rawLocalized(path: String, localeTag: String?): MessageTemplate? = templateLocalized(path, localeTag)

    fun template(path: String): MessageTemplate? = templateLocalized(path, null)

    fun templateLocalized(path: String, localeTag: String?): MessageTemplate? {
        val fullPath = toFullPath(path)
        return resolveConfigChain(localeTag).firstNotNullOfOrNull { source ->
            parseTemplateAt(source, fullPath)
        }
    }

    fun plain(path: String, player: Player? = null, replacements: Map<String, String> = emptyMap()): String? {
        return plainLocalized(path, player?.locale, player, replacements)
    }

    fun plainLocalized(
        path: String,
        localeTag: String?,
        player: Player? = null,
        replacements: Map<String, String> = emptyMap()
    ): String? {
        val template = templateLocalized(path, localeTag) ?: return null
        val firstLine = template.chatLines.firstOrNull() ?: return null
        return placeholderHandler.plain(firstLine, player, mergeReplacements(replacements, localeTag))
    }

    fun send(sender: CommandSender, path: String, replacements: Map<String, String> = emptyMap()) {
        val locale = (sender as? Player)?.locale
        sendLocalized(sender, path, locale, replacements)
    }

    fun sendLocalized(
        sender: CommandSender,
        path: String,
        localeTag: String?,
        replacements: Map<String, String> = emptyMap()
    ) {
        val template = templateLocalized(path, localeTag) ?: return
        deliver(sender, template, localeTag, replacements)
    }

    fun sendMany(
        recipients: Iterable<CommandSender>,
        path: String,
        replacements: Map<String, String> = emptyMap()
    ) {
        recipients.forEach { recipient ->
            val locale = (recipient as? Player)?.locale
            sendLocalized(recipient, path, locale, replacements)
        }
    }

    private fun deliver(
        sender: CommandSender,
        template: MessageTemplate,
        localeTag: String?,
        replacements: Map<String, String>
    ) {
        val merged = mergeReplacements(replacements, localeTag)
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

    private fun parseTemplateAt(source: Config, fullPath: String): MessageTemplate? {
        if (!source.hasPath(fullPath)) return null
        val value = source.getValue(fullPath)
        return when (value.valueType()) {
            ConfigValueType.STRING -> MessageTemplate(chatLines = listOf(source.getString(fullPath)))
            ConfigValueType.LIST -> MessageTemplate(chatLines = source.getStringList(fullPath))
            ConfigValueType.OBJECT -> parseObject(source.getConfig(fullPath), fullPath)
            else -> null
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

    private fun mergeReplacements(extra: Map<String, String>, localeTag: String?): Map<String, String> {
        val localizedPrefix = resolveLocalizedString("prefix", localeTag)

        val shared = if (localizedPrefix != null && localizedPrefix.isNotBlank()) {
            val merged = LinkedHashMap<String, String>(sharedReplacements.size + 1)
            merged.putAll(sharedReplacements)
            merged["prefix"] = localizedPrefix
            merged
        } else {
            sharedReplacements
        }

        if (shared.isEmpty()) return extra
        if (extra.isEmpty()) return shared

        val merged = HashMap<String, String>(shared.size + extra.size)
        merged.putAll(shared)
        merged.putAll(extra)
        return merged
    }

    private fun resolveLocalizedString(path: String, localeTag: String?): String? {
        val fullPath = toFullPath(path)
        resolveConfigChain(localeTag).forEach { source ->
            if (!source.hasPath(fullPath)) return@forEach
            val value = source.getValue(fullPath)
            if (value.valueType() == ConfigValueType.STRING) {
                return source.getString(fullPath)
            }
        }
        return null
    }

    private fun resolveConfigChain(localeTag: String?): List<Config> {
        val chain = ArrayList<Config>(4)
        val added = HashSet<String>()

        fun addLocale(tag: String?) {
            val normalized = normalizeLocaleTag(tag) ?: return
            val localeConfig = localeConfigs[normalized] ?: return
            if (added.add("locale:$normalized")) {
                chain += localeConfig
            }
        }

        val normalizedRequested = normalizeLocaleTag(localeTag)
        if (normalizedRequested != null) {
            addLocale(normalizedRequested)
            val language = normalizedRequested.substringBefore('-', normalizedRequested)
            if (language != normalizedRequested) {
                addLocale(language)
            }
        }

        addLocale(resolveDefaultLocaleTag())

        if (added.add("base")) {
            chain += config
        }

        return chain
    }

    private fun resolveDefaultLocaleTag(): String {
        val metaPath = "${options.metaRootPath}.${options.defaultLocaleKey}"
        val configured = if (config.hasPath(metaPath)) {
            config.getString(metaPath)
        } else {
            options.fallbackDefaultLocale
        }
        return normalizeLocaleTag(configured)
            ?: normalizeLocaleTag(options.fallbackDefaultLocale)
            ?: DEFAULT_FALLBACK_LOCALE
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
        private const val DEFAULT_FALLBACK_LOCALE = "en-US"

        fun fromFile(
            fileProvider: () -> File,
            logger: Logger,
            options: Options,
            onCorrupted: ((File, Exception) -> String?)? = null
        ): Messages {
            return createFromFile(
                plugin = null,
                fileProvider = fileProvider,
                logger = logger,
                options = options,
                onCorrupted = onCorrupted
            )
        }

        @Deprecated(
            message = "This method doesn't support server startup checks. Use the overload with the plugin parameter instead.",
            replaceWith = ReplaceWith(
                "Messages.fromFile(fileProvider, logger, Messages.Options(rootPath, sharedPlaceholders, localesFolderName, metaRootPath, defaultLocaleKey, fallbackDefaultLocale), onCorrupted)"
            ),
            level = DeprecationLevel.WARNING
        )
        fun fromFile(
            fileProvider: () -> File,
            logger: Logger,
            rootPath: String = "messages",
            sharedPlaceholders: Map<String, String> = emptyMap(),
            onCorrupted: ((File, Exception) -> String?)? = null,
            localesFolderName: String = "languages",
            metaRootPath: String = "messages-meta",
            defaultLocaleKey: String = "default-locale",
            fallbackDefaultLocale: String = DEFAULT_FALLBACK_LOCALE
        ): Messages {
            val options = Options(
                rootPath = rootPath,
                sharedPlaceholders = sharedPlaceholders,
                localesFolderName = localesFolderName,
                metaRootPath = metaRootPath,
                defaultLocaleKey = defaultLocaleKey,
                fallbackDefaultLocale = fallbackDefaultLocale
            )
            return fromFile(
                fileProvider = fileProvider,
                logger = logger,
                options = options,
                onCorrupted = onCorrupted
            )
        }

        fun fromFile(
            plugin: JavaPlugin,
            fileProvider: () -> File,
            logger: Logger,
            options: Options,
            onCorrupted: ((File, Exception) -> String?)? = null
        ): Messages {
            return createFromFile(
                plugin = plugin,
                fileProvider = fileProvider,
                logger = logger,
                options = options,
                onCorrupted = onCorrupted
            )
        }

        fun fromFile(
            plugin: JavaPlugin,
            fileProvider: () -> File,
            logger: Logger,
            rootPath: String = "messages",
            sharedPlaceholders: Map<String, String> = emptyMap(),
            onCorrupted: ((File, Exception) -> String?)? = null,
            localesFolderName: String = "languages",
            metaRootPath: String = "messages-meta",
            defaultLocaleKey: String = "default-locale",
            fallbackDefaultLocale: String = DEFAULT_FALLBACK_LOCALE
        ): Messages {
            val options = Options(
                rootPath = rootPath,
                sharedPlaceholders = sharedPlaceholders,
                localesFolderName = localesFolderName,
                metaRootPath = metaRootPath,
                defaultLocaleKey = defaultLocaleKey,
                fallbackDefaultLocale = fallbackDefaultLocale
            )
            return fromFile(
                plugin = plugin,
                fileProvider = fileProvider,
                logger = logger,
                options = options,
                onCorrupted = onCorrupted
            )
        }

        private fun createFromFile(
            plugin: JavaPlugin?,
            fileProvider: () -> File,
            logger: Logger,
            options: Options,
            onCorrupted: ((File, Exception) -> String?)?
        ): Messages {
            val supplier = {
                val file = fileProvider()
                loadFromFile(file, onCorrupted)
            }
            val localeSupplier = {
                val file = fileProvider()
                loadLocaleFiles(file.parentFile, options.localesFolderName, logger)
            }
            return Messages(
                configSupplier = supplier,
                logger = logger,
                options = options,
                plugin = plugin,
                localeConfigSupplier = localeSupplier
            )
        }

        private fun loadLocaleFiles(
            parentFolder: File?,
            localesFolderName: String,
            logger: Logger
        ): Map<String, Config> {
            if (parentFolder == null) return emptyMap()
            val localesFolder = File(parentFolder, localesFolderName)
            if (!localesFolder.exists() || !localesFolder.isDirectory) {
                return emptyMap()
            }

            val files = localesFolder.listFiles { file ->
                file.isFile && file.extension.equals("conf", ignoreCase = true)
            }?.sortedBy { it.name.lowercase() } ?: return emptyMap()

            val loaded = linkedMapOf<String, Config>()
            files.forEach { file ->
                val localeTag = normalizeLocaleTag(file.nameWithoutExtension)
                if (localeTag == null) {
                    logger.warning("Skipping locale file with invalid tag: ${file.name}")
                    return@forEach
                }

                val parsed = try {
                    ConfigFactory.parseFile(file).resolve()
                } catch (ex: Exception) {
                    val contents = runCatching { file.readText() }.getOrNull()
                    FileBackups.backup(file, contents)
                    logger.warning("Failed to parse locale file '${file.name}': ${ex.message ?: ex::class.simpleName}")
                    return@forEach
                }

                loaded[localeTag] = parsed
            }
            return loaded
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

        internal fun normalizeLocaleTag(raw: String?): String? {
            if (raw.isNullOrBlank()) return null
            val normalized = raw.trim().replace('_', '-')
            val parts = normalized.split('-').filter { it.isNotBlank() }
            if (parts.isEmpty()) return null

            val language = parts.first().lowercase()
            if (!language.matches(Regex("[a-z]{2,8}"))) return null
            if (parts.size == 1) return language

            val region = parts[1].uppercase()
            if (!region.matches(Regex("[A-Z0-9]{2,8}"))) return null
            return "$language-$region"
        }
    }
}
