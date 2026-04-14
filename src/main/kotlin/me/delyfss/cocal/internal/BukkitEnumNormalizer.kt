package me.delyfss.cocal.internal

/**
 * Normalises user-provided enum strings so config files can use the relaxed
 * forms commonly seen in Minecraft resources ("minecraft:gray_stained_glass_pane",
 * "gray-stained-glass-pane", "Gray Stained Glass Pane") into the exact
 * Bukkit enum constant name (GRAY_STAINED_GLASS_PANE).
 *
 * Applies to every Enum<*> — including Material, EntityType, PotionType, Sound,
 * EnchantmentTarget, etc. The helper has no Bukkit dependency; it is purely
 * string manipulation.
 */
internal object BukkitEnumNormalizer {
    fun normalize(raw: String): String {
        val stripped = raw.substringAfter(':', raw)
        val builder = StringBuilder(stripped.length)
        stripped.forEach { character ->
            when {
                character == '-' || character == ' ' || character == '.' -> builder.append('_')
                character == '_' -> builder.append('_')
                character.isLetterOrDigit() -> builder.append(character.uppercaseChar())
                // silently drop anything else (e.g. apostrophes, quotes)
            }
        }
        return builder.toString()
    }
}
