package com.lighttodo.app.widget

import android.appwidget.AppWidgetManager
import android.content.Context

/**
 * 「双栏」卡片。
 *
 * 逻辑全部复用 [TodoWidgetReceiver]（点击、勾选、编辑、删除都一样），
 * 区别只有两点：
 * 1. 它有自己的 appwidget-provider（最小 4×2），在桌面添加列表里是独立的一项；
 * 2. 首次更新时把该实例的配置标成 dual = true，渲染器据此走双栏布局。
 */
class DualWidgetReceiver : TodoWidgetReceiver() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { id ->
            val config = WidgetConfigStore.load(context, id)
            if (!config.dual) {
                WidgetConfigStore.save(context, id, config.copy(dual = true))
            }
            WidgetRenderer.update(context, id)
        }
    }
}
