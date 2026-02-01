package me.delyfss.cocal

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigRenderOptions
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.declaredMemberProperties
import kotlin.reflect.jvm.isAccessible
import kotlin.collections.linkedMapOf
import kotlin.reflect.jvm.javaField

abstract class DynamicConfig(
    val folder: File,
    val fileName: String,
    val options: Options = Options()
) : AutoCloseable {

    data class Options(
        val header: List<String> = emptyList(),
        val prettyPrint: Boolean = true,
        val debounceDelayMs: Long = 1000L
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
        file.writeText(renderConfig(buildCurrentConfig()))
    }

    private fun buildCurrentConfig(): Config {
        val tree = linkedMapOf<String, Any?>()
        properties.forEach { prop ->
            val path = pathAnnotation(prop)?.value ?: prop.name
            val value = prop.getter.call(this)
            insertValue(tree, path.split('.').filter { it.isNotBlank() }, convertValue(value))
        }
        return ConfigFactory.parseMap(tree)
    }

    private fun convertValue(value: Any?): Any? = when (value) {
        null -> null
        is String, is Number, is Boolean -> value
        is Enum<*> -> value.name
        is List<*> -> value.map { convertValue(it) }
        is Set<*> -> value.map { convertValue(it) }
        is Map<*, *> -> value.entries.associate { (key, entryValue) ->
            key.toString() to convertValue(entryValue)
        }
        else -> if (value::class.isData) {
            snapshotDataClass(value)
        } else {
            value.toString()
        }
    }

    private fun snapshotDataClass(value: Any): Map<String, Any?> {
        val nested = linkedMapOf<String, Any?>()
        collectCurrent(value, emptyList(), nested)
        return nested
    }

    private fun collectCurrent(
        instance: Any,
        prefix: List<String>,
        result: MutableMap<String, Any?>
    ) {
        val klass = instance::class
        val properties = klass.declaredMemberProperties
            .filter { it.javaField != null }
            .onEach { it.isAccessible = true }

        properties.forEach { prop ->
            val pathSegments = (prefix + (pathAnnotation(prop)?.value ?: prop.name)
                .split('.')
                .filter { it.isNotBlank() })

            when (val value = prop.getter.call(instance)) {
                null -> insertValue(result, pathSegments, null)
                is Map<*, *> -> {
                    val convertedMap = value.entries.associate { (k, v) ->
                        k.toString() to convertValue(v)
                    }
                    insertValue(result, pathSegments, convertedMap)
                }
                else -> if (value::class.isData) {
                    collectCurrent(value, pathSegments, result)
                } else {
                    insertValue(result, pathSegments, convertValue(value))
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

    private fun renderConfig(config: Config): String {
        val renderOptions = ConfigRenderOptions.defaults()
            .setComments(false)
            .setOriginComments(false)
            .setJson(false)
            .setFormatted(options.prettyPrint)

        val rendered = config.root().render(renderOptions)
        if (options.header.isEmpty()) return rendered

        val header = options.header.joinToString("\n") { if (it.startsWith("#")) it else "# $it" }
        return "$header\n\n$rendered"
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
