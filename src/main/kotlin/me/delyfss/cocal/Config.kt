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

        val defaultText = generateDefaults()
        val defaultConfig = ConfigFactory.parseString(defaultText)

        val existingConfig = if (file.exists() && file.readText().isBlank()) {
            try {
                ConfigFactory.parseFile(file)
            } catch (e: ConfigException) {
                ConfigFactory.empty()
            }
        } else {
            ConfigFactory.empty()
        }

        val merged = existingConfig.withFallback(defaultConfig).resolve()

        for (field in target::class.java.declaredFields) {
            val annotation = field.getDeclaredAnnotation(Path::class.java) ?: continue
            val path = annotation.value
            field.isAccessible = true
            when (field.type) {
                Boolean::class.javaPrimitiveType -> field.setBoolean(target, merged.getBoolean(path))
                Int::class.javaPrimitiveType -> field.setInt(target, merged.getInt(path))
                Long::class.javaPrimitiveType -> field.setLong(target, merged.getLong(path))
                Double::class.javaPrimitiveType -> field.setDouble(target, merged.getDouble(path))
                String::class.java -> field.set(target, merged.getString(path))
                else -> error("Unsupported config type for path '$path'")
            }
        }

        file.writeText(
            merged.root().render(
                ConfigRenderOptions.defaults()
                    .setComments(true)
                    .setOriginComments(false)
                    .setJson(false)
            )
        )

        return target
    }

    private fun generateDefaults(): String =
        target::class.java.declaredFields.mapNotNull {
            val ann = it.getDeclaredAnnotation(Path::class.java) ?: return@mapNotNull null
            it.isAccessible = true
            "${ann.value} = ${formatValue(it.get(target))}"
        }.joinToString("\n")

    private fun formatValue(value: Any?): String =
        if (value is String) "\"$value\"" else value.toString()
}
