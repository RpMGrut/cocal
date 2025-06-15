package me.delyfss.cocal

import com.typesafe.config.*
import java.io.File

class Config<T : Any>(
    private val folder: File,
    private val fileName: String,
    private val target: T
) {
    fun load(): T {
        val file = File(folder, fileName)
        folder.mkdirs()

        val defaultConfig = ConfigFactory.parseString(generateDefaults(target))
        val existingConfig = if (file.exists()) ConfigFactory.parseFile(file) else ConfigFactory.empty()
        val merged = defaultConfig.withFallback(existingConfig).resolve()

        if (!file.exists()) {
            file.writeText(defaultConfig.root().render(ConfigRenderOptions.defaults().setComments(true).setOriginComments(false)))
        } else {
            val mergedText = merged.root().render(ConfigRenderOptions.defaults().setComments(false).setOriginComments(false).setJson(false))
            file.writeText(mergedText)
        }

        for (field in target::class.java.declaredFields) {
            field.isAccessible = true
            val path = field.getAnnotation(Path::class.java)?.value ?: continue

            when (field.type) {
                Boolean::class.javaPrimitiveType -> field.setBoolean(target, merged.getBoolean(path))
                Int::class.javaPrimitiveType -> field.setInt(target, merged.getInt(path))
                Long::class.javaPrimitiveType -> field.setLong(target, merged.getLong(path))
                Double::class.javaPrimitiveType -> field.setDouble(target, merged.getDouble(path))
                String::class.java -> field.set(target, merged.getString(path))
                else -> error("Unsupported config type for path '$path'")
            }
        }

        return target
    }

    private fun generateDefaults(target: T): String {
        val sb = StringBuilder()
        for (field in target::class.java.declaredFields) {
            field.isAccessible = true
            val path = field.getAnnotation(Path::class.java)?.value ?: continue
            val value = field.get(target)
            sb.append("$path = ").append(formatValue(value)).append("\n")
        }
        return sb.toString()
    }

    private fun formatValue(value: Any?): String = when (value) {
        is String -> "\"$value\""
        else -> value.toString()
    }
}