package me.delyfss.cocal.menu.action

import java.util.logging.Logger

/**
 * Parses a list of bracketed action strings like `[close]` or `[player] say hi`
 * into [Action] instances using the factories registered in [Actions].
 */
object ActionParser {

    private val TAG_PATTERN = Regex("^\\[([a-zA-Z_][a-zA-Z0-9_ ]*)](.*)$", RegexOption.DOT_MATCHES_ALL)

    fun parse(raw: String, logger: Logger? = null): Action? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val match = TAG_PATTERN.matchEntire(trimmed)
        if (match == null) {
            logger?.warning("Action '$raw' is missing a [tag] prefix")
            return null
        }
        val tag = match.groupValues[1].trim().lowercase()
        val argument = match.groupValues[2].trim()
        val factory = Actions.get(tag)
        if (factory == null) {
            logger?.warning("Unknown menu action tag: [$tag]")
            return null
        }
        return runCatching { factory.create(argument) }
            .onFailure { ex ->
                logger?.warning("Failed to build action [$tag] '$argument': ${ex.message}")
            }
            .getOrNull()
    }

    fun parseAll(rawList: List<String>, logger: Logger? = null): List<Action> {
        return rawList.mapNotNull { parse(it, logger) }
    }
}
