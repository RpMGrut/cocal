package me.delyfss.cocal.menu.requirement

import me.delyfss.cocal.menu.action.ActionContext
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger

interface Requirement {
    fun test(context: ActionContext): Boolean
}

interface RequirementFactory {
    val tag: String
    fun create(argument: String): Requirement
}

object Requirements {
    private val factories = ConcurrentHashMap<String, RequirementFactory>()

    fun register(factory: RequirementFactory) {
        factories[factory.tag.lowercase()] = factory
    }

    fun unregister(tag: String) {
        factories.remove(tag.lowercase())
    }

    fun get(tag: String): RequirementFactory? = factories[tag.lowercase()]

    internal fun registerBuiltins(builtins: List<RequirementFactory>) {
        builtins.forEach { register(it) }
    }
}

object RequirementParser {

    private val TAG_PATTERN = Regex("^\\[([a-zA-Z_][a-zA-Z0-9_]*)](.*)$", RegexOption.DOT_MATCHES_ALL)

    fun parse(raw: String, logger: Logger? = null): Requirement? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val match = TAG_PATTERN.matchEntire(trimmed)
        if (match == null) {
            logger?.warning("Requirement '$raw' is missing a [tag] prefix")
            return null
        }
        val tag = match.groupValues[1].trim().lowercase()
        val argument = match.groupValues[2].trim()
        val factory = Requirements.get(tag)
        if (factory == null) {
            logger?.warning("Unknown menu requirement tag: [$tag]")
            return null
        }
        return runCatching { factory.create(argument) }
            .onFailure { ex ->
                logger?.warning("Failed to build requirement [$tag] '$argument': ${ex.message}")
            }
            .getOrNull()
    }

    fun parseAll(rawList: List<String>, logger: Logger? = null): List<Requirement> {
        return rawList.mapNotNull { parse(it, logger) }
    }
}

internal object HasPermissionRequirementFactory : RequirementFactory {
    override val tag = "permission"
    override fun create(argument: String): Requirement = object : Requirement {
        private val node = argument.trim()
        override fun test(context: ActionContext): Boolean {
            if (node.isEmpty()) return true
            return context.player.hasPermission(node)
        }
    }
}
