package me.delyfss.cocal.menu.config

data class MenuConfig(
    val type: MenuType = MenuType.CHEST,
    val name: String = "",
    val size: Int = 27,
    val shape: List<String> = emptyList(),
    val items: Map<String, MenuItemConfig> = emptyMap(),
    val pagination: PaginationConfig? = null,
    val scroll: ScrollConfig? = null,
    val openActions: List<String> = emptyList(),
    val closeActions: List<String> = emptyList()
)

data class PaginationConfig(
    val slots: List<Int> = emptyList(),
    val next: NavButtonConfig? = null,
    val previous: NavButtonConfig? = null,
    val currentPlaceholder: String = "page",
    val totalPlaceholder: String = "pages"
)

data class NavButtonConfig(
    val symbol: String = "",
    val onEmpty: String = "hide"
)

data class ScrollConfig(
    val mode: ScrollMode = ScrollMode.VERTICAL,
    val step: Int = 1
)

enum class ScrollMode { VERTICAL, HORIZONTAL }
