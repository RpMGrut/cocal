package me.delyfss.cocal.message

import org.bukkit.Sound
import org.bukkit.SoundCategory

data class MessageTemplate(
    val chatLines: List<String> = emptyList(),
    val actionBar: String? = null,
    val titleBar: TitleBar? = null,
    val sound: SoundSpec? = null
) {
    val isEmpty: Boolean
        get() = chatLines.isEmpty() && actionBar == null && titleBar == null && sound == null
}

data class TitleBar(
    val title: String?,
    val subtitle: String?,
    val fadeIn: Int,
    val stay: Int,
    val fadeOut: Int
)

data class SoundSpec(
    val sound: Sound,
    val volume: Float,
    val pitch: Float,
    val category: SoundCategory?
)
