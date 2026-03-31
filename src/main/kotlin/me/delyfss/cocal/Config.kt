package me.delyfss.cocal

import com.typesafe.config.Config as TypesafeConfig
import com.typesafe.config.ConfigException
import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigValueType
import java.io.File
import java.util.logging.Logger
import me.delyfss.cocal.internal.ConfigTextRenderer
import me.delyfss.cocal.util.FileBackups
import kotlin.reflect.KClass
import kotlin.reflect.KParameter
import kotlin.reflect.KProperty1
import kotlin.reflect.KType
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.jvm.isAccessible
import kotlin.reflect.jvm.javaField
import kotlin.reflect.jvm.jvmErasure

class Config<T : Any>(
    private val folder: File,
    private val fileName: String,
    private val prototype: T,
    private val options: Options = Options()
) {

    data class Options(
        val header: List<String> = emptyList(),
        val prettyPrint: Boolean = true,
        val alwaysWriteFile: Boolean = true,
        val commentsEnabled: Boolean = true,
        val commentPrefix: String = "# "
    )

    private data class Blueprint(
        val tree: LinkedHashMap<String, Any?>,
        val dynamicSections: Set<String>,
        val keyComments: Map<String, List<String>>,
        val sectionComments: Map<String, List<String>>
    )

    private data class RecoveryResult<R>(
        val loaded: R,
        val merged: TypesafeConfig,
        val hasSelectiveFixes: Boolean
    )

    private enum class RecoveryAction {
        SELECTIVE_ROLLBACK,
        GLOBAL_RESET
    }

    private class InvalidConfigValueException(
        val displayPath: String,
        val recoveryPath: String,
        val reason: String,
        val lineNumber: Int?,
        val invalidValuePreview: String?,
        cause: Throwable? = null
    ) : RuntimeException(reason, cause)

    private val logger: Logger = Logger.getLogger(Config::class.java.name)

    fun load(): T {
        return if (prototype::class.isData) {
            loadDataClass()
        } else {
            loadLegacyObject()
        }
    }

    private fun loadDataClass(): T {
        folder.mkdirs()
        val file = File(folder, fileName)

        val blueprint = buildDataBlueprint(prototype)
        val defaultText = renderConfig(blueprint.tree, blueprint.keyComments, blueprint.sectionComments)
        val defaultConfig = parseRenderedConfig(defaultText)

        val existing = readConfigFile(file, defaultText)
        val klass = prototype::class as KClass<T>

        val recovery = loadWithRecovery(file, defaultText, defaultConfig, existing) { merged ->
            instantiateDataClass(klass, merged)
        }

        writeOrderedConfig(
            file = file,
            blueprint = blueprint,
            merged = recovery.merged,
            forceWrite = recovery.hasSelectiveFixes
        )
        return recovery.loaded
    }

    private fun loadLegacyObject(): T {
        folder.mkdirs()
        val file = File(folder, fileName)

        val blueprint = buildLegacyBlueprint()
        val defaultText = renderConfig(blueprint.tree, blueprint.keyComments, blueprint.sectionComments)
        val defaultConfig = parseRenderedConfig(defaultText)

        val existing = readConfigFile(file, defaultText)

        val recovery = loadWithRecovery(file, defaultText, defaultConfig, existing) { merged ->
            assignLegacyFields(merged)
            prototype
        }

        writeOrderedConfig(
            file = file,
            blueprint = blueprint,
            merged = recovery.merged,
            forceWrite = recovery.hasSelectiveFixes
        )
        return recovery.loaded
    }

    private inline fun <R> loadWithRecovery(
        file: File,
        defaultText: String,
        defaultConfig: TypesafeConfig,
        existing: TypesafeConfig,
        instantiate: (TypesafeConfig) -> R
    ): RecoveryResult<R> {
        var current = existing
        var merged = current.withFallback(defaultConfig).resolve()
        var hasSelectiveFixes = false
        var attempts = 0

        while (true) {
            try {
                val loaded = instantiate(merged)
                return RecoveryResult(loaded, merged, hasSelectiveFixes)
            } catch (invalid: InvalidConfigValueException) {
                attempts += 1
                when (decideRecoveryAction(invalid, defaultConfig, attempts)) {
                    RecoveryAction.SELECTIVE_ROLLBACK -> {
                        logInvalidValue(
                            file = file,
                            issue = invalid,
                            action = "roll back to default"
                        )
                        current = current.withValue(invalid.recoveryPath, defaultConfig.getValue(invalid.recoveryPath))
                        merged = current.withFallback(defaultConfig).resolve()
                        hasSelectiveFixes = true
                    }

                    RecoveryAction.GLOBAL_RESET -> {
                        logInvalidValue(
                            file = file,
                            issue = invalid,
                            action = "global reset with backup"
                        )
                        restoreFromInvalidValues(file, defaultText, invalid)
                        merged = ConfigFactory.parseFile(file).withFallback(defaultConfig).resolve()
                        val loaded = instantiate(merged)
                        return RecoveryResult(loaded, merged, true)
                    }
                }
            } catch (ex: Exception) {
                restoreFromInvalidValues(file, defaultText, ex)
                merged = ConfigFactory.parseFile(file).withFallback(defaultConfig).resolve()
                val loaded = instantiate(merged)
                return RecoveryResult(loaded, merged, true)
            }
        }
    }

    private fun decideRecoveryAction(
        invalid: InvalidConfigValueException,
        defaultConfig: TypesafeConfig,
        attempts: Int
    ): RecoveryAction {
        if (attempts > MAX_RECOVERY_ATTEMPTS) {
            return RecoveryAction.GLOBAL_RESET
        }
        val recoveryPath = invalid.recoveryPath
        if (recoveryPath.isBlank()) {
            return RecoveryAction.GLOBAL_RESET
        }
        val hasDefault = runCatching { defaultConfig.hasPath(recoveryPath) }
            .getOrDefault(false)
        if (!hasDefault) {
            return RecoveryAction.GLOBAL_RESET
        }
        return RecoveryAction.SELECTIVE_ROLLBACK
    }

    private fun logInvalidValue(file: File, issue: InvalidConfigValueException, action: String) {
        val line = issue.lineNumber?.toString() ?: "unknown"
        val value = issue.invalidValuePreview ?: "<unavailable>"
        logger.warning(
            "Invalid config value: file='${file.absolutePath}', line=$line, path='${issue.displayPath}', value='$value', reason='${issue.reason}', action='$action'"
        )
    }

    private fun readConfigFile(file: File, defaultText: String): TypesafeConfig {
        if (!file.exists()) return ConfigFactory.empty()
        val text = file.readText()
        if (text.isBlank()) return ConfigFactory.empty()
        return try {
            ConfigFactory.parseFile(file)
        } catch (ex: ConfigException) {
            recoverCorruptedFile(file, text, defaultText, ex)
            ConfigFactory.parseFile(file)
        }
    }

    private fun restoreFromInvalidValues(file: File, defaultText: String, cause: Exception) {
        if (!file.exists()) {
            file.writeText(defaultText)
            return
        }
        val current = file.readText()
        FileBackups.backup(file, current)
        file.writeText(defaultText)
    }

    private fun recoverCorruptedFile(
        file: File,
        contents: String,
        defaultText: String,
        cause: Exception
    ) {
        FileBackups.backup(file, contents)
        file.writeText(defaultText)
    }

    private fun parseRenderedConfig(text: String): TypesafeConfig {
        if (text.isBlank()) return ConfigFactory.empty()
        return ConfigFactory.parseString(text)
    }

    private fun writeOrderedConfig(
        file: File,
        blueprint: Blueprint,
        merged: TypesafeConfig,
        forceWrite: Boolean
    ) {
        if (!forceWrite && !options.alwaysWriteFile && file.exists()) return
        val orderedMap = buildOrderedMap(blueprint.tree, merged, "", blueprint.dynamicSections)
        file.writeText(renderConfig(orderedMap, blueprint.keyComments, blueprint.sectionComments))
    }

    private fun buildOrderedMap(
        template: Map<String, Any?>,
        source: TypesafeConfig,
        prefix: String,
        dynamicSections: Set<String>
    ): Map<String, Any?> {
        val result = linkedMapOf<String, Any?>()
        template.forEach { (key, value) ->
            val path = appendConfigPath(prefix, key)
            val resolvedValue = when {
                dynamicSections.contains(path) -> snapshotDynamicSection(source, path) ?: value
                value is Map<*, *> -> {
                    @Suppress("UNCHECKED_CAST")
                    buildOrderedMap(value as Map<String, Any?>, source, path, dynamicSections)
                }

                else -> readOrDefault(source, path, value)
            }
            result[key] = resolvedValue
        }
        return result
    }

    private fun snapshotDynamicSection(config: TypesafeConfig, path: String): Map<String, Any?>? {
        if (!config.hasPath(path)) return null
        val obj = config.getObject(path)
        val map = linkedMapOf<String, Any?>()
        obj.forEach { (key, value) ->
            val childPath = appendConfigPath(path, key)
            val resolved = when (value.valueType()) {
                ConfigValueType.OBJECT -> snapshotDynamicSection(config, childPath)
                ConfigValueType.LIST,
                ConfigValueType.BOOLEAN,
                ConfigValueType.NUMBER,
                ConfigValueType.STRING,
                ConfigValueType.NULL -> config.getValue(childPath).unwrapped()
            }
            map[key] = resolved
        }
        return map
    }

    private fun readOrDefault(config: TypesafeConfig, path: String, fallback: Any?): Any? {
        if (path.isEmpty()) return fallback
        return if (config.hasPath(path)) config.getValue(path).unwrapped() else fallback
    }

    private fun instantiateDataClass(
        klass: KClass<*>,
        config: TypesafeConfig
    ): T {
        @Suppress("UNCHECKED_CAST")
        return instantiateAnyDataClass(klass, config, "") as T
    }

    private fun instantiateAnyDataClass(
        klass: KClass<*>,
        config: TypesafeConfig,
        prefix: String
    ): Any {
        val ctor = klass.primaryConstructor
            ?: error("Data class ${klass.simpleName} must have a primary constructor")
        val params = mutableMapOf<KParameter, Any?>()
        val properties = klass.memberProperties.associateBy { it.name }

        ctor.parameters.forEach { param ->
            val property = properties[param.name]
                ?: error("No property found for parameter ${param.name} in ${klass.simpleName}")
            val path = resolvePath(prefix, property, param)
            if (!config.hasPath(path)) {
                when {
                    param.isOptional -> return@forEach
                    param.type.isMarkedNullable -> {
                        params[param] = null
                        return@forEach
                    }
                }
            }

            val value = readValue(param.type, config, path)
            params[param] = value
        }

        return ctor.callBy(params)
    }

    private fun readValue(type: KType, config: TypesafeConfig, path: String): Any? {
        val erasure = type.jvmErasure
        return when {
            erasure == String::class -> readTyped(config, path, "STRING") { config.getString(path) }
            erasure == Int::class -> readTyped(config, path, "INT") { config.getInt(path) }
            erasure == Long::class -> readTyped(config, path, "LONG") { config.getLong(path) }
            erasure == Double::class -> readTyped(config, path, "DOUBLE") { config.getDouble(path) }
            erasure == Float::class -> readTyped(config, path, "FLOAT") { config.getDouble(path).toFloat() }
            erasure == Boolean::class -> readTyped(config, path, "BOOLEAN") { config.getBoolean(path) }
            erasure.java.isEnum -> {
                val raw = readTyped(config, path, "STRING") { config.getString(path) }
                readEnum(enumClass(erasure), raw, config, path)
            }

            erasure == List::class -> readList(type, config, path)
            erasure == MutableList::class -> readList(type, config, path).toMutableList()
            erasure == Set::class -> readList(type, config, path).toSet()
            erasure == MutableSet::class -> readList(type, config, path).toMutableSet()
            erasure == Map::class -> readMap(type, config, path)
            erasure == MutableMap::class -> readMap(type, config, path).toMutableMap()
            erasure.isData -> {
                val child = readTyped(config, path, "OBJECT") { config.getConfig(path) }
                try {
                    instantiateAnyDataClass(erasure, child, "")
                } catch (ex: InvalidConfigValueException) {
                    throw prependInvalidPath(ex, path)
                }
            }

            else -> error("Unsupported config type '$erasure' at path '$path'")
        }
    }

    private fun readList(type: KType, config: TypesafeConfig, path: String): List<Any?> {
        val elementType = type.arguments.firstOrNull()?.type
            ?: error("List type at '$path' must specify generic parameter")
        val erasure = elementType.jvmErasure
        return when {
            erasure == String::class -> readTyped(config, path, "LIST<STRING>") { config.getStringList(path) }
            erasure == Int::class -> readTyped(config, path, "LIST<INT>") { config.getIntList(path) }
            erasure == Long::class -> readTyped(config, path, "LIST<LONG>") { config.getLongList(path) }
            erasure == Double::class -> readTyped(config, path, "LIST<DOUBLE>") { config.getDoubleList(path) }
            erasure == Boolean::class -> readTyped(config, path, "LIST<BOOLEAN>") { config.getBooleanList(path) }
            erasure.java.isEnum -> {
                readTyped(config, path, "LIST<STRING>") { config.getStringList(path) }
                    .map { raw -> readEnum(enumClass(erasure), raw, config, path) }
            }

            erasure.isData -> {
                val entries = readTyped(config, path, "LIST<OBJECT>") { config.getConfigList(path) }
                entries.mapIndexed { index, entry ->
                    try {
                        instantiateAnyDataClass(erasure, entry, "")
                    } catch (ex: InvalidConfigValueException) {
                        val localPath = if (ex.displayPath.isBlank()) "entry" else ex.displayPath
                        val displayPath = "$path[$index].$localPath"
                        val line = ex.lineNumber ?: sanitizeLine(entry.root().origin().lineNumber())
                        throw remapInvalidPath(ex, displayPath = displayPath, recoveryPath = path, lineNumber = line)
                    }
                }
            }

            else -> error("Unsupported list element type '$erasure' at path '$path'")
        }
    }

    private fun readMap(type: KType, config: TypesafeConfig, path: String): Map<Any, Any?> {
        val keyType = type.arguments.getOrNull(0)?.type?.jvmErasure
            ?: error("Map key type missing at path '$path'")
        val valueType = type.arguments.getOrNull(1)?.type
            ?: error("Map value type missing at path '$path'")
        val section = readTyped(config, path, "OBJECT") { config.getConfig(path) }
        val keys = section.root().keys
        val result = linkedMapOf<Any, Any?>()
        keys.forEach { rawKey ->
            val childPath = appendConfigPath(path, rawKey)
            val key = parseMapKey(keyType, rawKey, config, path, childPath)
            result[key] = readValue(valueType, config, childPath)
        }
        return result
    }

    private fun parseMapKey(
        keyType: KClass<*>,
        rawKey: String,
        config: TypesafeConfig,
        mapPath: String,
        childPath: String
    ): Any {
        return when {
            keyType == String::class -> rawKey
            keyType == Int::class -> rawKey.toIntOrNull()
                ?: throw invalidConfigValue(
                    config = config,
                    displayPath = childPath,
                    recoveryPath = mapPath,
                    reason = "Key '$rawKey' must be an integer (Int)",
                    lineNumberOverride = extractLineNumber(config, childPath),
                    rawValueOverride = rawKey
                )

            keyType == Double::class -> rawKey.toDoubleOrNull()
                ?.takeIf { it.isFinite() }
                ?: throw invalidConfigValue(
                    config = config,
                    displayPath = childPath,
                    recoveryPath = mapPath,
                    reason = "Key '$rawKey' must be a finite double (Double)",
                    lineNumberOverride = extractLineNumber(config, childPath),
                    rawValueOverride = rawKey
                )

            keyType.java.isEnum -> parseEnumMapKey(enumClass(keyType), rawKey, config, mapPath, childPath)
            else -> error(
                "Key of type '$keyType' at path '$mapPath' is not supported. " +
                    "Supported key types: String, Int, Double, Enum."
            )
        }
    }

    private fun parseEnumMapKey(
        enumClass: Class<out Enum<*>>,
        rawKey: String,
        config: TypesafeConfig,
        mapPath: String,
        childPath: String
    ): Enum<*> {
        return try {
            java.lang.Enum.valueOf(enumClass, rawKey.uppercase())
        } catch (_: IllegalArgumentException) {
            val allowed = enumClass.enumConstants.joinToString(", ") { it.name }
            throw invalidConfigValue(
                config = config,
                displayPath = childPath,
                recoveryPath = mapPath,
                reason = "Invalid enum key '$rawKey' for ${enumClass.simpleName}. Allowed: $allowed",
                lineNumberOverride = extractLineNumber(config, childPath),
                rawValueOverride = rawKey
            )
        }
    }

    private fun readEnum(
        enumClass: Class<out Enum<*>>,
        raw: String,
        config: TypesafeConfig,
        path: String
    ): Enum<*> {
        return try {
            java.lang.Enum.valueOf(enumClass, raw.uppercase())
        } catch (_: IllegalArgumentException) {
            val allowed = enumClass.enumConstants.joinToString(", ") { it.name }
            throw invalidConfigValue(
                config = config,
                displayPath = path,
                recoveryPath = path,
                reason = "Invalid enum value '$raw' for ${enumClass.simpleName}. Allowed: $allowed",
                rawValueOverride = raw
            )
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun enumClass(erasure: KClass<*>): Class<out Enum<*>> {
        return erasure.java as Class<out Enum<*>>
    }

    private inline fun <R> readTyped(
        config: TypesafeConfig,
        path: String,
        expectedType: String,
        block: () -> R
    ): R {
        return try {
            block()
        } catch (ex: InvalidConfigValueException) {
            throw ex
        } catch (ex: ConfigException) {
            throw invalidConfigValue(
                config = config,
                displayPath = path,
                recoveryPath = path,
                reason = ex.message ?: "Expected $expectedType at '$path'",
                cause = ex
            )
        }
    }

    private fun invalidConfigValue(
        config: TypesafeConfig,
        displayPath: String,
        recoveryPath: String,
        reason: String,
        cause: Throwable? = null,
        lineNumberOverride: Int? = null,
        rawValueOverride: Any? = null
    ): InvalidConfigValueException {
        val line = lineNumberOverride
            ?: extractLineNumber(config, recoveryPath)
            ?: (cause as? ConfigException)?.origin()?.lineNumber()?.let(::sanitizeLine)
        val rawValue = rawValueOverride ?: extractRawValue(config, recoveryPath)
        return InvalidConfigValueException(
            displayPath = displayPath,
            recoveryPath = recoveryPath,
            reason = reason,
            lineNumber = line,
            invalidValuePreview = previewValue(rawValue),
            cause = cause
        )
    }

    private fun prependInvalidPath(
        exception: InvalidConfigValueException,
        pathPrefix: String
    ): InvalidConfigValueException {
        return InvalidConfigValueException(
            displayPath = appendPath(pathPrefix, exception.displayPath),
            recoveryPath = appendPath(pathPrefix, exception.recoveryPath),
            reason = exception.reason,
            lineNumber = exception.lineNumber,
            invalidValuePreview = exception.invalidValuePreview,
            cause = exception
        )
    }

    private fun remapInvalidPath(
        exception: InvalidConfigValueException,
        displayPath: String,
        recoveryPath: String,
        lineNumber: Int? = exception.lineNumber
    ): InvalidConfigValueException {
        return InvalidConfigValueException(
            displayPath = displayPath,
            recoveryPath = recoveryPath,
            reason = exception.reason,
            lineNumber = lineNumber,
            invalidValuePreview = exception.invalidValuePreview,
            cause = exception
        )
    }

    private fun extractLineNumber(config: TypesafeConfig, path: String): Int? {
        val line = runCatching { config.getValue(path).origin().lineNumber() }
            .getOrNull()
        return line?.let(::sanitizeLine)
    }

    private fun extractRawValue(config: TypesafeConfig, path: String): Any? {
        return runCatching { config.getValue(path).unwrapped() }
            .getOrNull()
    }

    private fun sanitizeLine(line: Int): Int? = line.takeIf { it > 0 }

    private fun previewValue(rawValue: Any?): String? {
        if (rawValue == null) return "null"
        val normalized = rawValue.toString().replace("\n", "\\n")
        return if (normalized.length <= MAX_VALUE_PREVIEW) {
            normalized
        } else {
            normalized.substring(0, MAX_VALUE_PREVIEW) + "..."
        }
    }

    private fun resolvePath(prefix: String, property: KProperty1<*, *>, parameter: KParameter): String {
        val target = propertyPath(parameter, property)
        return when {
            prefix.isEmpty() -> target
            target.isEmpty() -> prefix
            else -> "$prefix.$target"
        }
    }

    private fun propertyPath(parameter: KParameter, property: KProperty1<*, *>): String {
        val annotation = parameter.findAnnotation<Path>()
            ?: property.findAnnotation<Path>()
            ?: property.javaField?.getAnnotation(Path::class.java)
        return annotation?.value ?: property.name
    }

    private fun assignLegacyFields(config: TypesafeConfig) {
        prototype::class.java.declaredFields.forEach { field ->
            val annotation = field.getDeclaredAnnotation(Path::class.java) ?: return@forEach
            val path = annotation.value
            field.isAccessible = true
            when {
                field.type == Boolean::class.javaPrimitiveType ->
                    field.setBoolean(prototype, readTyped(config, path, "BOOLEAN") { config.getBoolean(path) })

                field.type == Int::class.javaPrimitiveType ->
                    field.setInt(prototype, readTyped(config, path, "INT") { config.getInt(path) })

                field.type == Long::class.javaPrimitiveType ->
                    field.setLong(prototype, readTyped(config, path, "LONG") { config.getLong(path) })

                field.type == Double::class.javaPrimitiveType ->
                    field.setDouble(prototype, readTyped(config, path, "DOUBLE") { config.getDouble(path) })

                field.type == String::class.java ->
                    field.set(prototype, readTyped(config, path, "STRING") { config.getString(path) })

                List::class.java.isAssignableFrom(field.type) -> {
                    val typeStr = field.genericType.toString()
                    val list = when {
                        "String" in typeStr -> readTyped(config, path, "LIST<STRING>") { config.getStringList(path) }
                        "Integer" in typeStr || "Int" in typeStr -> readTyped(config, path, "LIST<INT>") { config.getIntList(path) }
                        "Boolean" in typeStr -> readTyped(config, path, "LIST<BOOLEAN>") { config.getBooleanList(path) }
                        "Double" in typeStr -> readTyped(config, path, "LIST<DOUBLE>") { config.getDoubleList(path) }
                        "Long" in typeStr -> readTyped(config, path, "LIST<LONG>") { config.getLongList(path) }
                        else -> error("Unsupported list type for path '$path'")
                    }
                    field.set(prototype, list)
                }

                else -> error("Unsupported config type for path '$path'")
            }
        }
    }

    private fun buildDataBlueprint(instance: Any): Blueprint {
        val tree = linkedMapOf<String, Any?>()
        val dynamic = linkedSetOf<String>()
        val keyComments = linkedMapOf<String, List<String>>()
        val sectionComments = linkedMapOf<String, List<String>>()
        collectDefaults(instance, emptyList(), tree, dynamic, keyComments, sectionComments)
        return Blueprint(tree, dynamic, keyComments, sectionComments)
    }

    private fun buildLegacyBlueprint(): Blueprint {
        val tree = linkedMapOf<String, Any?>()
        val dynamic = linkedSetOf<String>()
        val keyComments = linkedMapOf<String, List<String>>()
        val sectionComments = linkedMapOf<String, List<String>>()
        prototype::class.java.declaredFields.forEach { field ->
            val annotation = field.getDeclaredAnnotation(Path::class.java) ?: return@forEach
            val segments = annotation.value.split('.').filter { it.isNotBlank() }
            val joinedPath = segments.joinToString(".")
            fieldComments(field)?.let { comments -> keyComments.putIfAbsent(joinedPath, comments) }
            fieldSectionComments(field)?.let { comments -> sectionComments.putIfAbsent(joinedPath, comments) }
            field.isAccessible = true
            val value = field.get(prototype)
            if (value != null && value::class.isData) {
                collectDefaults(value, segments, tree, dynamic, keyComments, sectionComments)
            } else {
                val converted = convertValue(value, segments, dynamic, keyComments, sectionComments)
                insertValue(tree, segments, converted)
            }
        }
        return Blueprint(tree, dynamic, keyComments, sectionComments)
    }

    private fun collectDefaults(
        instance: Any,
        prefix: List<String>,
        result: MutableMap<String, Any?>,
        dynamicSections: MutableSet<String>,
        keyComments: MutableMap<String, List<String>>,
        sectionComments: MutableMap<String, List<String>>
    ) {
        val klass = instance::class
        val ctor = klass.primaryConstructor
            ?: error("Data class ${klass.simpleName} must have a primary constructor")
        val properties = klass.memberProperties.associateBy { it.name }

        ctor.parameters.forEach { param ->
            val property = properties[param.name]
                ?: error("No property found for parameter ${param.name} in ${klass.simpleName}")
            val path = propertyPath(param, property).split('.').filter { it.isNotBlank() }
            val segments = prefix + path
            val joinedPath = segments.joinToString(".")
            propertyComment(param, property)?.let { comments -> keyComments.putIfAbsent(joinedPath, comments) }
            propertySectionComment(param, property)?.let { comments -> sectionComments.putIfAbsent(joinedPath, comments) }
            property.isAccessible = true
            val value = property.getter.call(instance)
            val typeErasure = property.returnType.jvmErasure
            val isMapType = typeErasure == Map::class || typeErasure == MutableMap::class

            if (isMapType && segments.isNotEmpty()) {
                dynamicSections.add(segments.joinToString("."))
            }

            if (value != null && value::class.isData) {
                collectDefaults(value, segments, result, dynamicSections, keyComments, sectionComments)
            } else {
                val converted = convertValue(value, segments, dynamicSections, keyComments, sectionComments)
                insertValue(result, segments, converted)
            }
        }
    }

    private fun convertValue(
        value: Any?,
        path: List<String>,
        dynamicSections: MutableSet<String>,
        keyComments: MutableMap<String, List<String>>,
        sectionComments: MutableMap<String, List<String>>
    ): Any? = when (value) {
        null -> null
        is String, is Number, is Boolean -> value
        is Enum<*> -> value.name
        is List<*> -> value.map { convertCollectionValue(it, dynamicSections, keyComments, sectionComments) }
        is Set<*> -> value.map { convertCollectionValue(it, dynamicSections, keyComments, sectionComments) }
        is Map<*, *> -> {
            val fullPath = path.joinToString(".")
            if (fullPath.isNotBlank()) {
                dynamicSections.add(fullPath)
            }
            value.entries.associate { (key, entryValue) ->
                key.toString() to convertCollectionValue(entryValue, dynamicSections, keyComments, sectionComments)
            }
        }

        else -> if (value::class.isData) {
            snapshotDataClass(value, dynamicSections, keyComments, sectionComments)
        } else {
            value.toString()
        }
    }

    private fun convertCollectionValue(
        value: Any?,
        dynamicSections: MutableSet<String>,
        keyComments: MutableMap<String, List<String>>,
        sectionComments: MutableMap<String, List<String>>
    ): Any? = when (value) {
        null -> null
        is String, is Number, is Boolean -> value
        is Enum<*> -> value.name
        is List<*> -> value.map { convertCollectionValue(it, dynamicSections, keyComments, sectionComments) }
        is Set<*> -> value.map { convertCollectionValue(it, dynamicSections, keyComments, sectionComments) }
        is Map<*, *> -> value.entries.associate { (key, entryValue) ->
            key.toString() to convertCollectionValue(entryValue, dynamicSections, keyComments, sectionComments)
        }

        else -> if (value::class.isData) {
            snapshotDataClass(value, dynamicSections, keyComments, sectionComments)
        } else {
            value.toString()
        }
    }

    private fun snapshotDataClass(
        value: Any,
        dynamicSections: MutableSet<String>,
        keyComments: MutableMap<String, List<String>>,
        sectionComments: MutableMap<String, List<String>>
    ): Map<String, Any?> {
        val nested = linkedMapOf<String, Any?>()
        collectDefaults(value, emptyList(), nested, dynamicSections, keyComments, sectionComments)
        return nested
    }

    private fun insertValue(target: MutableMap<String, Any?>, path: List<String>, rawValue: Any?) {
        if (path.isEmpty()) return
        var cursor: MutableMap<String, Any?> = target
        path.dropLast(1).forEach { key ->
            val next = cursor[key]
            cursor = if (next is MutableMap<*, *>) {
                @Suppress("UNCHECKED_CAST")
                next as MutableMap<String, Any?>
            } else {
                val map = linkedMapOf<String, Any?>()
                cursor[key] = map
                map
            }
        }
        cursor[path.last()] = rawValue
    }

    private fun renderConfig(
        tree: Map<String, Any?>,
        keyComments: Map<String, List<String>>,
        sectionComments: Map<String, List<String>>
    ): String {
        return ConfigTextRenderer.render(
            root = tree,
            options = ConfigTextRenderer.Options(
                header = options.header,
                prettyPrint = options.prettyPrint,
                commentsEnabled = options.commentsEnabled,
                commentPrefix = options.commentPrefix
            ),
            keyComments = keyComments,
            sectionComments = sectionComments
        )
    }

    private fun propertyComment(parameter: KParameter, property: KProperty1<*, *>): List<String>? {
        val raw = parameter.findAnnotation<Comment>()?.value
            ?: property.findAnnotation<Comment>()?.value
            ?: property.javaField?.getAnnotation(Comment::class.java)?.value
        return sanitizeComments(raw)
    }

    private fun propertySectionComment(parameter: KParameter, property: KProperty1<*, *>): List<String>? {
        val raw = parameter.findAnnotation<SectionComment>()?.value
            ?: property.findAnnotation<SectionComment>()?.value
            ?: property.javaField?.getAnnotation(SectionComment::class.java)?.value
        return sanitizeComments(raw)
    }

    private fun fieldComments(field: java.lang.reflect.Field): List<String>? =
        sanitizeComments(field.getDeclaredAnnotation(Comment::class.java)?.value)

    private fun fieldSectionComments(field: java.lang.reflect.Field): List<String>? =
        sanitizeComments(field.getDeclaredAnnotation(SectionComment::class.java)?.value)

    private fun sanitizeComments(lines: Array<out String>?): List<String>? {
        if (lines == null) return null
        val normalized = lines.map { it.trimEnd() }
        return normalized.takeIf { it.isNotEmpty() }
    }

    private fun appendPath(prefix: String, key: String): String {
        if (prefix.isEmpty()) return key
        if (key.isEmpty()) return prefix
        return "$prefix.$key"
    }

    private fun appendConfigPath(prefix: String, key: String): String {
        return appendPath(prefix, renderConfigPathSegment(key))
    }

    private fun renderConfigPathSegment(raw: String): String {
        return if (PATH_SEGMENT_REGEX.matches(raw)) raw else quotePathSegment(raw)
    }

    private fun quotePathSegment(raw: String): String {
        val escaped = buildString(raw.length + 4) {
            raw.forEach { ch ->
                when (ch) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    else -> append(ch)
                }
            }
        }
        return "\"$escaped\""
    }

    companion object {
        private const val MAX_RECOVERY_ATTEMPTS = 64
        private const val MAX_VALUE_PREVIEW = 200
        private val PATH_SEGMENT_REGEX = Regex("[A-Za-z0-9_-]+")
    }
}
