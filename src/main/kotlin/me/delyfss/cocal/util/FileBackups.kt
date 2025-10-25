package me.delyfss.cocal.util

import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

internal object FileBackups {
    private val signatures = ConcurrentHashMap<String, Int>()
    private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss")
        .withZone(ZoneOffset.UTC)

    fun backup(file: File, contents: String?, suffix: String = "save"): File? {
        if (contents.isNullOrBlank()) return null
        val signature = contents.hashCode()
        val key = file.absolutePath
        if (signatures[key] == signature) {
            return null
        }
        signatures[key] = signature
        val stamp = formatter.format(Instant.now())
        val extension = file.extension.ifBlank { "conf" }
        val parent = file.parentFile
            ?: file.absoluteFile.parentFile
            ?: file.absoluteFile.parentFile?.parentFile
            ?: throw IllegalStateException("Cannot resolve backup directory for ${'$'}{file.absolutePath}")
        val backupName = "${file.nameWithoutExtension}$suffix-$stamp.$extension"
        val backupFile = File(parent, backupName)
        backupFile.writeText(contents)
        return backupFile
    }
}
