package me.delyfss.cocal.menu.runtime

import me.delyfss.cocal.menu.config.MenuItemConfig
import me.delyfss.cocal.menu.context.MenuContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Dynamic content provider for a paginated or scrollable menu. Implementations
 * return the total item count and the item config for a given flat index.
 */
interface PageSource {
    fun size(context: MenuContext): Int
    fun itemAt(index: Int, context: MenuContext): MenuItemConfig
}

object PageSourceRegistry {
    private val sources = ConcurrentHashMap<String, PageSource>()

    fun bind(menuId: String, source: PageSource) {
        sources[menuId] = source
    }

    fun unbind(menuId: String) {
        sources.remove(menuId)
    }

    fun get(menuId: String): PageSource? = sources[menuId]
}
