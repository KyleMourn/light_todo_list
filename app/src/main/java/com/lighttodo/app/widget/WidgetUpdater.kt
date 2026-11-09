package com.lighttodo.app.widget

import android.content.Context

/**
 * 小组件刷新入口：现在直接走 RemoteViews 渲染，不再依赖 Glance 会话。
 * 保留这层 API 是为了让 App 内的调用点不用改。
 */
object WidgetUpdater {

    fun update(context: Context, listId: Int) {
        WidgetRenderer.update(context, listId)
    }

    /** 卡片内勾选/取消完成：只让列表重新读数据，不整卡重建（更快、不丢滚动位置）。 */
    fun refreshRows(context: Context, listId: Int) {
        WidgetRenderer.refreshRows(context, listId)
    }

    /** 带淡入的整卡重绘，用于「完成/取消完成」这类需要看到变化的情况。 */
    fun updateAnimated(context: Context, listId: Int, fadeMs: Int) {
        WidgetRenderer.render(context, listId, fadeMs)
    }

    fun updateAll(context: Context) {
        WidgetRenderer.updateAll(context)
    }

    /** 小组件回调等需要「刷新完再返回」的场景，同步执行即可。 */
    suspend fun refreshNow(context: Context, listId: Int) {
        WidgetRenderer.update(context, listId)
    }
}
