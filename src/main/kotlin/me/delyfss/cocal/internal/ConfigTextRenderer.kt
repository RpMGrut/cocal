package me.delyfss.cocal.internal

internal object ConfigTextRenderer {

    data class Options(
        val header: List<String> = emptyList(),
        val prettyPrint: Boolean = true,
        val commentsEnabled: Boolean = true,
        val commentPrefix: String = "# "
    )

    fun render(
        root: Map<String, Any?>,
        options: Options,
        keyComments: Map<String, List<String>> = emptyMap(),
        sectionComments: Map<String, List<String>> = emptyMap()
    ): String {
        val body = StringBuilder()
        appendMap(
            out = body,
            map = root,
            pathPrefix = "",
            indentLevel = 0,
            options = options,
            keyComments = keyComments,
            sectionComments = sectionComments
        )

        val content = body.toString().trimEnd()
        if (options.header.isEmpty()) {
            return content
        }
        val header = buildString {
            options.header.forEachIndexed { index, line ->
                append(formatComment(line, "", options.commentPrefix))
                if (index < options.header.lastIndex) append('\n')
            }
        }
        return if (content.isEmpty()) {
            header
        } else {
            "$header\n\n$content"
        }
    }

    private fun appendMap(
        out: StringBuilder,
        map: Map<String, Any?>,
        pathPrefix: String,
        indentLevel: Int,
        options: Options,
        keyComments: Map<String, List<String>>,
        sectionComments: Map<String, List<String>>
    ) {
        map.entries.forEachIndexed { index, (rawKey, value) ->
            val key = rawKey.toString()
            val path = appendPath(pathPrefix, key)
            if (index > 0) {
                out.append('\n')
            }

            val indent = indent(indentLevel)
            if (options.commentsEnabled) {
                if (value is Map<*, *>) {
                    appendComments(out, sectionComments[path], indent, options.commentPrefix)
                }
                appendComments(out, keyComments[path], indent, options.commentPrefix)
            }

            when (value) {
                is Map<*, *> -> {
                    @Suppress("UNCHECKED_CAST")
                    val child = value.mapKeys { it.key.toString() } as Map<String, Any?>
                    if (child.isEmpty()) {
                        out.append(indent).append(renderKey(key)).append(" = {}")
                    } else {
                        out.append(indent).append(renderKey(key)).append(" {")
                        out.append('\n')
                        appendMap(
                            out = out,
                            map = child,
                            pathPrefix = path,
                            indentLevel = indentLevel + 1,
                            options = options,
                            keyComments = keyComments,
                            sectionComments = sectionComments
                        )
                        out.append('\n').append(indent).append("}")
                    }
                }
                else -> {
                    out.append(indent)
                        .append(renderKey(key))
                        .append(" = ")
                        .append(renderValue(value, path, indentLevel, options, keyComments, sectionComments))
                }
            }
        }
    }

    private fun renderValue(
        value: Any?,
        path: String,
        indentLevel: Int,
        options: Options,
        keyComments: Map<String, List<String>>,
        sectionComments: Map<String, List<String>>
    ): String {
        return when (value) {
            null -> "null"
            is String -> quote(value)
            is Number, is Boolean -> value.toString()
            is List<*> -> renderList(value, path, indentLevel, options, keyComments, sectionComments)
            is Map<*, *> -> {
                @Suppress("UNCHECKED_CAST")
                val map = value.mapKeys { it.key.toString() } as Map<String, Any?>
                if (map.isEmpty()) {
                    "{}"
                } else {
                    val nested = StringBuilder()
                    nested.append("{\n")
                    appendMap(
                        out = nested,
                        map = map,
                        pathPrefix = path,
                        indentLevel = indentLevel + 1,
                        options = options,
                        keyComments = keyComments,
                        sectionComments = sectionComments
                    )
                    nested.append('\n').append(indent(indentLevel)).append("}")
                    nested.toString()
                }
            }
            is Enum<*> -> quote(value.name)
            else -> quote(value.toString())
        }
    }

    private fun renderList(
        list: List<*>,
        path: String,
        indentLevel: Int,
        options: Options,
        keyComments: Map<String, List<String>>,
        sectionComments: Map<String, List<String>>
    ): String {
        if (list.isEmpty()) {
            return "[]"
        }

        val hasComplex = list.any { it is Map<*, *> || it is List<*> }
        if (!options.prettyPrint || !hasComplex) {
            return buildString {
                append("[")
                list.forEachIndexed { index, element ->
                    if (index > 0) append(", ")
                    append(renderInlineValue(element))
                }
                append("]")
            }
        }

        val nestedIndent = indent(indentLevel + 1)
        val closingIndent = indent(indentLevel)
        return buildString {
            append("[\n")
            list.forEachIndexed { index, element ->
                append(nestedIndent)
                append(renderComplexListElement(element, path, indentLevel + 1, options, keyComments, sectionComments))
                if (index < list.lastIndex) append(",")
                append('\n')
            }
            append(closingIndent).append("]")
        }
    }

    private fun renderComplexListElement(
        value: Any?,
        path: String,
        indentLevel: Int,
        options: Options,
        keyComments: Map<String, List<String>>,
        sectionComments: Map<String, List<String>>
    ): String {
        return when (value) {
            is Map<*, *> -> {
                @Suppress("UNCHECKED_CAST")
                val map = value.mapKeys { it.key.toString() } as Map<String, Any?>
                if (map.isEmpty()) {
                    "{}"
                } else {
                    val nested = StringBuilder()
                    nested.append("{\n")
                    appendMap(
                        out = nested,
                        map = map,
                        pathPrefix = path,
                        indentLevel = indentLevel + 1,
                        options = options,
                        keyComments = keyComments,
                        sectionComments = sectionComments
                    )
                    nested.append('\n').append(indent(indentLevel)).append("}")
                    nested.toString()
                }
            }
            is List<*> -> renderList(value, path, indentLevel, options, keyComments, sectionComments)
            else -> renderInlineValue(value)
        }
    }

    private fun renderInlineValue(value: Any?): String {
        return when (value) {
            null -> "null"
            is String -> quote(value)
            is Number, is Boolean -> value.toString()
            is Enum<*> -> quote(value.name)
            is List<*> -> value.joinToString(prefix = "[", postfix = "]", separator = ", ") { renderInlineValue(it) }
            is Map<*, *> -> {
                val map = value.entries.joinToString(separator = ", ") { (k, v) ->
                    "${renderKey(k.toString())} = ${renderInlineValue(v)}"
                }
                "{$map}"
            }
            else -> quote(value.toString())
        }
    }

    private fun appendComments(
        out: StringBuilder,
        comments: List<String>?,
        indent: String,
        commentPrefix: String
    ) {
        if (comments.isNullOrEmpty()) return
        comments.forEach { line ->
            out.append(formatComment(line, indent, commentPrefix)).append('\n')
        }
    }

    private fun formatComment(line: String, indent: String, prefix: String): String {
        val trimmed = line.trim()
        if (trimmed.startsWith("#") || trimmed.startsWith("//")) {
            return "$indent$trimmed"
        }
        if (trimmed.isEmpty()) {
            return "$indent${prefix.trimEnd()}"
        }
        return "$indent$prefix$trimmed"
    }

    private fun renderKey(key: String): String {
        return if (KEY_REGEX.matches(key)) key else quote(key)
    }

    private fun appendPath(prefix: String, key: String): String {
        if (prefix.isEmpty()) return key
        if (key.isEmpty()) return prefix
        return "$prefix.$key"
    }

    private fun indent(level: Int): String = "  ".repeat(level)

    private fun quote(raw: String): String {
        val escaped = buildString(raw.length + 8) {
            raw.forEach { ch ->
                when (ch) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(ch)
                }
            }
        }
        return "\"$escaped\""
    }

    private val KEY_REGEX = Regex("[A-Za-z0-9_-]+")
}
