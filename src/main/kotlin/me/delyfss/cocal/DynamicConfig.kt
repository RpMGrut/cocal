package me.delyfss.cocal

import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.collections.linkedMapOf
import kotlin.reflect.full.declaredMemberProperties
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.jvm.isAccessible
import kotlin.reflect.jvm.javaField
import me.delyfss.cocal.internal.ConfigTextRenderer

abstract class DynamicConfig(
    val folder: File,
    val fileName: String,
    val options: Options = Options()
) : AutoCloseable {

    data class Options(
        val header: List<String> = emptyList(),
        val prettyPrint: Boolean = true,
        val debounceDelayMs: Long = 1000L,
        val commentsEnabled: Boolean = true,
        val commentPrefix: String = "# "
    )

    private data class Blueprint(
        val tree: Map<String, Any?>,
        val keyComments: Map<String, List<String>>,
        val sectionComments: Map<String, List<String>>
    )

    private val scheduler = Executors.newSingleThreadScheduledExecutor { run ->
        val thread = Thread(run, "Config-Saver-Thread")
        thread.isDaemon = true
        thread
    }
    private var saveTask: java.util.concurrent.ScheduledFuture<*>? = null
    @Volatile private var closed = false

    private val properties by lazy {
        this::class.declaredMemberProperties
            .filter { it.javaField != null }
            .onEach { it.isAccessible = true }
    }

    fun scheduleSave() {
        synchronized(this) {
            scheduleSaveLocked()
        }
    }

    @Synchronized
    fun save() {
        if (closed) return
        folder.mkdirs()
        val file = File(folder, fileName)
        val blueprint = buildCurrentBlueprint()
        file.writeText(renderConfig(blueprint))
    }

    private fun buildCurrentBlueprint(): Blueprint {
        val tree = linkedMapOf<String, Any?>()
        val keyComments = linkedMapOf<String, List<String>>()
        val sectionComments = linkedMapOf<String, List<String>>()

        properties.forEach { prop ->
            val segments = (pathAnnotation(prop)?.value ?: prop.name)
                .split('.')
                .filter { it.isNotBlank() }
            val path = segments.joinToString(".")

            propertyComment(prop)?.let { comments -> keyComments.putIfAbsent(path, comments) }
            propertySectionComment(prop)?.let { comments -> sectionComments.putIfAbsent(path, comments) }

            val value = prop.getter.call(this)
            if (value != null && value::class.isData) {
                collectCurrent(value, segments, tree, keyComments, sectionComments)
            } else {
                insertValue(tree, segments, convertValue(value, segments, keyComments, sectionComments))
            }
        }

        return Blueprint(tree, keyComments, sectionComments)
    }

    private fun convertValue(
        value: Any?,
        path: List<String>,
        keyComments: MutableMap<String, List<String>>,
        sectionComments: MutableMap<String, List<String>>
    ): Any? = when (value) {
        null -> null
        is String, is Number, is Boolean -> value
        is Enum<*> -> value.name
        is List<*> -> value.map { convertCollectionValue(it, path, keyComments, sectionComments) }
        is Set<*> -> value.map { convertCollectionValue(it, path, keyComments, sectionComments) }
        is Map<*, *> -> value.entries.associate { (key, entryValue) ->
            key.toString() to convertCollectionValue(entryValue, path, keyComments, sectionComments)
        }
        else -> if (value::class.isData) {
            snapshotDataClass(value, path, keyComments, sectionComments)
        } else {
            value.toString()
        }
    }

    private fun convertCollectionValue(
        value: Any?,
        path: List<String>,
        keyComments: MutableMap<String, List<String>>,
        sectionComments: MutableMap<String, List<String>>
    ): Any? = when (value) {
        null -> null
        is String, is Number, is Boolean -> value
        is Enum<*> -> value.name
        is List<*> -> value.map { convertCollectionValue(it, path, keyComments, sectionComments) }
        is Set<*> -> value.map { convertCollectionValue(it, path, keyComments, sectionComments) }
        is Map<*, *> -> value.entries.associate { (key, entryValue) ->
            key.toString() to convertCollectionValue(entryValue, path, keyComments, sectionComments)
        }
        else -> if (value::class.isData) {
            snapshotDataClass(value, path, keyComments, sectionComments)
        } else {
            value.toString()
        }
    }

    private fun snapshotDataClass(
        value: Any,
        prefix: List<String>,
        keyComments: MutableMap<String, List<String>>,
        sectionComments: MutableMap<String, List<String>>
    ): Map<String, Any?> {
        val nested = linkedMapOf<String, Any?>()
        collectCurrent(value, prefix, nested, keyComments, sectionComments)
        return nested
    }

    private fun collectCurrent(
        instance: Any,
        prefix: List<String>,
        result: MutableMap<String, Any?>,
        keyComments: MutableMap<String, List<String>>,
        sectionComments: MutableMap<String, List<String>>
    ) {
        val klass = instance::class
        val properties = klass.declaredMemberProperties
            .filter { it.javaField != null }
            .onEach { it.isAccessible = true }

        properties.forEach { prop ->
            val pathSegments = prefix + (pathAnnotation(prop)?.value ?: prop.name)
                .split('.')
                .filter { it.isNotBlank() }
            val joinedPath = pathSegments.joinToString(".")
            propertyComment(prop)?.let { comments -> keyComments.putIfAbsent(joinedPath, comments) }
            propertySectionComment(prop)?.let { comments -> sectionComments.putIfAbsent(joinedPath, comments) }

            when (val value = prop.getter.call(instance)) {
                null -> insertValue(result, pathSegments, null)
                is Map<*, *> -> {
                    val convertedMap = value.entries.associate { (k, v) ->
                        k.toString() to convertValue(v, pathSegments, keyComments, sectionComments)
                    }
                    insertValue(result, pathSegments, convertedMap)
                }
                else -> if (value::class.isData) {
                    collectCurrent(value, pathSegments, result, keyComments, sectionComments)
                } else {
                    insertValue(
                        result,
                        pathSegments,
                        convertValue(value, pathSegments, keyComments, sectionComments)
                    )
                }
            }
        }
    }

    private fun insertValue(target: MutableMap<String, Any?>, path: List<String>, rawValue: Any?) {
        if (path.isEmpty()) return
        var cursor = target
        for (i in 0 until path.size - 1) {
            val key = path[i]
            val existing = cursor[key]

            val nextMap = if (existing is MutableMap<*, *>) {
                @Suppress("UNCHECKED_CAST")
                existing as MutableMap<String, Any?>
            } else {
                val newMap = linkedMapOf<String, Any?>()
                cursor[key] = newMap
                newMap
            }
            cursor = nextMap
        }
        cursor[path.last()] = rawValue
    }

    private fun renderConfig(blueprint: Blueprint): String {
        return ConfigTextRenderer.render(
            root = blueprint.tree,
            options = ConfigTextRenderer.Options(
                header = options.header,
                prettyPrint = options.prettyPrint,
                commentsEnabled = options.commentsEnabled,
                commentPrefix = options.commentPrefix
            ),
            keyComments = blueprint.keyComments,
            sectionComments = blueprint.sectionComments
        )
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        saveTask?.cancel(false)
        scheduler.shutdown()
    }

    internal fun scheduleSaveLocked() {
        if (closed) return
        saveTask?.cancel(false)
        saveTask = scheduler.schedule({ save() }, options.debounceDelayMs, TimeUnit.MILLISECONDS)
    }

    private fun pathAnnotation(prop: kotlin.reflect.KProperty1<*, *>): Path? {
        return prop.findAnnotation<Path>()
            ?: prop.getter.findAnnotation<Path>()
            ?: prop.javaField?.getAnnotation(Path::class.java)
    }

    private fun propertyComment(prop: kotlin.reflect.KProperty1<*, *>): List<String>? {
        val raw = prop.findAnnotation<Comment>()?.value
            ?: prop.getter.findAnnotation<Comment>()?.value
            ?: prop.javaField?.getAnnotation(Comment::class.java)?.value
        return sanitizeComments(raw)
    }

    private fun propertySectionComment(prop: kotlin.reflect.KProperty1<*, *>): List<String>? {
        val raw = prop.findAnnotation<SectionComment>()?.value
            ?: prop.getter.findAnnotation<SectionComment>()?.value
            ?: prop.javaField?.getAnnotation(SectionComment::class.java)?.value
        return sanitizeComments(raw)
    }

    private fun sanitizeComments(lines: Array<out String>?): List<String>? {
        if (lines == null) return null
        val normalized = lines.map { it.trimEnd() }
        return normalized.takeIf { it.isNotEmpty() }
    }
}

fun <T : DynamicConfig> T.update(block: T.() -> Unit) {
    synchronized(this) {
        try {
            this.block()
            this.scheduleSaveLocked()
        } catch (e: Exception) {
            throw IllegalStateException("Failed to update config: $fileName", e)
        }
    }
}
