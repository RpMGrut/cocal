package me.delyfss.cocal

import com.typesafe.config.Config as TypesafeConfig
import com.typesafe.config.ConfigException
import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigRenderOptions
import com.typesafe.config.ConfigValueType
import java.io.File
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
        val alwaysWriteFile: Boolean = true
    )

    private data class Blueprint(
        val tree: LinkedHashMap<String, Any?>,
        val dynamicSections: Set<String>
    )

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
        val defaultConfig = ConfigFactory.parseMap(blueprint.tree)
        val defaultText = renderConfig(defaultConfig)

        val existing = readConfigFile(file, defaultText)
        var merged = existing.withFallback(defaultConfig).resolve()

        val klass = prototype::class as KClass<T>
        val result = try {
            instantiateDataClass(klass, merged, "")
        } catch (ex: Exception) {
            restoreFromInvalidValues(file, defaultText, ex)
            merged = ConfigFactory.parseFile(file).withFallback(defaultConfig).resolve()
            instantiateDataClass(klass, merged, "")
        }

        writeOrderedConfig(file, blueprint, merged)
        return result
    }

    private fun loadLegacyObject(): T {
        folder.mkdirs()
        val file = File(folder, fileName)

        val blueprint = buildLegacyBlueprint()
        val defaultConfig = ConfigFactory.parseMap(blueprint.tree)
        val defaultText = renderConfig(defaultConfig)

        val existing = readConfigFile(file, defaultText)
        var merged = existing.withFallback(defaultConfig).resolve()

        try {
            assignLegacyFields(merged)
        } catch (ex: Exception) {
            restoreFromInvalidValues(file, defaultText, ex)
            merged = ConfigFactory.parseFile(file).withFallback(defaultConfig).resolve()
            assignLegacyFields(merged)
        }

        writeOrderedConfig(file, blueprint, merged)
        return prototype
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

    private fun writeOrderedConfig(file: File, blueprint: Blueprint, merged: TypesafeConfig) {
        if (!options.alwaysWriteFile && file.exists()) return
        val ordered = createOrderedConfig(blueprint, merged)
        file.writeText(renderConfig(ordered))
    }

    private fun createOrderedConfig(blueprint: Blueprint, merged: TypesafeConfig): TypesafeConfig {
        val orderedMap = buildOrderedMap(blueprint.tree, merged, "", blueprint.dynamicSections)
        return ConfigFactory.parseMap(orderedMap)
    }

    private fun buildOrderedMap(
        template: Map<String, Any?>,
        source: TypesafeConfig,
        prefix: String,
        dynamicSections: Set<String>
    ): Map<String, Any?> {
        val result = linkedMapOf<String, Any?>()
        template.forEach { (key, value) ->
            val path = appendPath(prefix, key)
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
            val childPath = appendPath(path, key)
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
        config: TypesafeConfig,
        prefix: String
    ): T {
        @Suppress("UNCHECKED_CAST")
        return instantiateAnyDataClass(klass, config, prefix) as T
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
            val value = readValue(param.type, config, path)
            params[param] = value
        }

        return ctor.callBy(params)
    }

    private fun readValue(type: KType, config: TypesafeConfig, path: String): Any? {
        val erasure = type.jvmErasure
        return when {
            erasure == String::class -> config.getString(path)
            erasure == Int::class -> config.getInt(path)
            erasure == Long::class -> config.getLong(path)
            erasure == Double::class -> config.getDouble(path)
            erasure == Float::class -> config.getDouble(path).toFloat()
            erasure == Boolean::class -> config.getBoolean(path)
            erasure.java.isEnum -> readEnum(enumClass(erasure), config.getString(path))
            erasure == List::class -> readList(type, config, path)
            erasure == MutableList::class -> readList(type, config, path).toMutableList()
            erasure == Set::class -> readList(type, config, path).toSet()
            erasure == MutableSet::class -> readList(type, config, path).toMutableSet()
            erasure == Map::class -> readMap(type, config, path)
            erasure == MutableMap::class -> readMap(type, config, path).toMutableMap()
            erasure.isData -> instantiateAnyDataClass(erasure, config.getConfig(path), "")
            else -> error("Unsupported config type '$erasure' at path '$path'")
        }
    }

    private fun readList(type: KType, config: TypesafeConfig, path: String): List<Any?> {
        val elementType = type.arguments.firstOrNull()?.type
            ?: error("List type at '$path' must specify generic parameter")
        val erasure = elementType.jvmErasure
        return when {
            erasure == String::class -> config.getStringList(path)
            erasure == Int::class -> config.getIntList(path)
            erasure == Long::class -> config.getLongList(path)
            erasure == Double::class -> config.getDoubleList(path)
            erasure == Boolean::class -> config.getBooleanList(path)
            erasure.java.isEnum -> config.getStringList(path).map { readEnum(enumClass(erasure), it) }
            erasure.isData -> config.getConfigList(path).map { instantiateAnyDataClass(erasure, it, "") }
            else -> error("Unsupported list element type '$erasure' at path '$path'")
        }
    }

    private fun readMap(type: KType, config: TypesafeConfig, path: String): Map<String, Any?> {
        val keyType = type.arguments.getOrNull(0)?.type?.jvmErasure
            ?: error("Map key type missing at path '$path'")
        require(keyType == String::class) { "Only String map keys are supported at path '$path'" }
        val valueType = type.arguments.getOrNull(1)?.type
            ?: error("Map value type missing at path '$path'")
        val section = config.getConfig(path)
        val keys = section.root().keys
        val result = linkedMapOf<String, Any?>()
        keys.forEach { key ->
            val childPath = if (path.isEmpty()) key else "$path.$key"
            result[key] = readValue(valueType, config, childPath)
        }
        return result
    }

    private fun readEnum(enumClass: Class<out Enum<*>>, raw: String): Enum<*> {
        return try {
            java.lang.Enum.valueOf(enumClass, raw.uppercase())
        } catch (_: IllegalArgumentException) {
            val allowed = enumClass.enumConstants.joinToString(", ") { it.name }
            error("Invalid value '$raw' for enum ${enumClass.simpleName}. Allowed: $allowed")
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun enumClass(erasure: KClass<*>): Class<out Enum<*>> {
        return erasure.java as Class<out Enum<*>>
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
                field.type == Boolean::class.javaPrimitiveType -> field.setBoolean(prototype, config.getBoolean(path))
                field.type == Int::class.javaPrimitiveType -> field.setInt(prototype, config.getInt(path))
                field.type == Long::class.javaPrimitiveType -> field.setLong(prototype, config.getLong(path))
                field.type == Double::class.javaPrimitiveType -> field.setDouble(prototype, config.getDouble(path))
                field.type == String::class.java -> field.set(prototype, config.getString(path))
                List::class.java.isAssignableFrom(field.type) -> {
                    val typeStr = field.genericType.toString()
                    val list = when {
                        "String" in typeStr -> config.getStringList(path)
                        "Integer" in typeStr || "Int" in typeStr -> config.getIntList(path)
                        "Boolean" in typeStr -> config.getBooleanList(path)
                        "Double" in typeStr -> config.getDoubleList(path)
                        "Long" in typeStr -> config.getLongList(path)
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
        collectDefaults(instance, emptyList(), tree, dynamic)
        return Blueprint(tree, dynamic)
    }

    private fun buildLegacyBlueprint(): Blueprint {
        val tree = linkedMapOf<String, Any?>()
        val dynamic = linkedSetOf<String>()
        prototype::class.java.declaredFields.forEach { field ->
            val annotation = field.getDeclaredAnnotation(Path::class.java) ?: return@forEach
            val segments = annotation.value.split('.').filter { it.isNotBlank() }
            field.isAccessible = true
            val value = field.get(prototype)
            if (value != null && value::class.isData) {
                collectDefaults(value, segments, tree, dynamic)
            } else {
                val converted = convertValue(value, segments, dynamic)
                insertValue(tree, segments, converted)
            }
        }
        return Blueprint(tree, dynamic)
    }

    private fun collectDefaults(
        instance: Any,
        prefix: List<String>,
        result: MutableMap<String, Any?>,
        dynamicSections: MutableSet<String>
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
            property.isAccessible = true
            val value = property.getter.call(instance)
            val typeErasure = property.returnType.jvmErasure
            val isMapType = typeErasure == Map::class || typeErasure == MutableMap::class

            if (isMapType && segments.isNotEmpty()) {
                dynamicSections.add(segments.joinToString("."))
            }

            if (value != null && value::class.isData) {
                collectDefaults(value, segments, result, dynamicSections)
            } else {
                val converted = convertValue(value, segments, dynamicSections)
                insertValue(result, segments, converted)
            }
        }
    }

    private fun convertValue(
        value: Any?,
        path: List<String>,
        dynamicSections: MutableSet<String>
    ): Any? = when (value) {
        null -> null
        is String, is Number, is Boolean -> value
        is Enum<*> -> value.name
        is List<*> -> value.map { convertCollectionValue(it, dynamicSections) }
        is Set<*> -> value.map { convertCollectionValue(it, dynamicSections) }
        is Map<*, *> -> {
            val fullPath = path.joinToString(".")
            if (fullPath.isNotBlank()) {
                dynamicSections.add(fullPath)
            }
            value.entries.associate { (key, entryValue) ->
                key.toString() to convertCollectionValue(entryValue, dynamicSections)
            }
        }
        else -> if (value::class.isData) {
            snapshotDataClass(value, dynamicSections)
        } else {
            value.toString()
        }
    }

    private fun convertCollectionValue(value: Any?, dynamicSections: MutableSet<String>): Any? = when (value) {
        null -> null
        is String, is Number, is Boolean -> value
        is Enum<*> -> value.name
        is List<*> -> value.map { convertCollectionValue(it, dynamicSections) }
        is Set<*> -> value.map { convertCollectionValue(it, dynamicSections) }
        is Map<*, *> -> value.entries.associate { (key, entryValue) ->
            key.toString() to convertCollectionValue(entryValue, dynamicSections)
        }
        else -> if (value::class.isData) {
            snapshotDataClass(value, dynamicSections)
        } else {
            value.toString()
        }
    }

    private fun snapshotDataClass(value: Any, dynamicSections: MutableSet<String>): Map<String, Any?> {
        val nested = linkedMapOf<String, Any?>()
        collectDefaults(value, emptyList(), nested, dynamicSections)
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

    private fun renderConfig(config: TypesafeConfig): String {
        val renderOptions = ConfigRenderOptions.defaults()
            .setComments(false)
            .setOriginComments(false)
            .setJson(false)
            .setFormatted(options.prettyPrint)

        val rendered = config.root().render(renderOptions)
        if (options.header.isEmpty()) {
            return rendered
        }
        val header = options.header.joinToString(separator = "\n") { line ->
            if (line.startsWith("#")) line else "# $line"
        }
        return buildString {
            appendLine(header)
            appendLine()
            append(rendered)
        }
    }

    private fun appendPath(prefix: String, key: String): String {
        if (prefix.isEmpty()) return key
        if (key.isEmpty()) return prefix
        return "$prefix.$key"
    }

}
