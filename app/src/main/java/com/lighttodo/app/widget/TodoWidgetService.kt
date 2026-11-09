package com.lighttodo.app.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.lighttodo.app.R
import com.lighttodo.app.data.TodoItem
import com.lighttodo.app.data.TodoRepository
import com.lighttodo.app.data.sortForDisplay

/**
 * 卡片内列表的数据源：ListView 集合控件，能在桌面卡片里直接上下滑动查看全部待办。
 *
 * 每个 appWidgetId 一个工厂实例；行内点击用 fillInIntent 把「点哪个控件、对哪条待办」
 * 填进模板 PendingIntent，避免为每一行单独创建 PendingIntent。
 */
class TodoWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        TodoViewsFactory(
            applicationContext,
            intent.getIntExtra(WidgetRenderer.EXTRA_WIDGET, 0),
            intent.getIntExtra(WidgetRenderer.EXTRA_COLUMN, 0)
        )
}

class TodoViewsFactory(
    private val context: Context,
    private val appWidgetId: Int,
    private val column: Int
) : RemoteViewsService.RemoteViewsFactory {

    private var items: List<TodoItem> = emptyList()
    private var config: WidgetConfig = WidgetConfig()

    override fun onCreate() = Unit

    override fun onDataSetChanged() {
        val t0 = android.os.SystemClock.elapsedRealtime()
        config = WidgetConfigStore.load(context, appWidgetId)
        val newestFirst = config.sortMode == WidgetConfig.SORT_NEWEST_FIRST
        val all = TodoRepository.get(context).snapshot(appWidgetId)
        // 双栏卡片每个 ListView 只取自己那一栏；经典卡片 column 恒为 0，等于全量
        val inColumn = if (column == 1) all.filter { it.column == 1 } else all.filter { it.column != 1 }
        // 关掉「显示已完成」时，已完成条目不进列表（数据不删，只是不展示）
        val visible = if (config.showDone) inColumn else inColumn.filter { !it.done }
        items = sortForDisplay(visible, newestFirst, config.sinkDone)
        val since = TodoWidgetReceiver.lastToggleAt
        if (since > 0) {
            android.util.Log.i(
                "LightTodoPerf",
                "factory bind w=$appWidgetId build=${android.os.SystemClock.elapsedRealtime() - t0}ms " +
                    "sinceToggle=${t0 - since}ms"
            )
        }
    }

    override fun onDestroy() = Unit

    override fun getCount(): Int = items.size

    override fun getViewAt(position: Int): RemoteViews? {
        val item = items.getOrNull(position) ?: return null
        val view = RemoteViews(context.packageName, R.layout.widget_row)
        val size = config.fontSizeSp.toFloat()
        val density = context.resources.displayMetrics.density
        fun dp(value: Float) = (value * density + 0.5f).toInt()

        view.setViewVisibility(R.id.circle, View.VISIBLE)
        view.setTextViewText(R.id.circle, if (item.done) "●" else "○")
        view.setTextColor(R.id.circle, if (item.done) config.doneTextColor else config.textColor)
        // 圆圈比文字大一号，并留出左右内边距，顺手把点击热区撑大（文字本身不动）
        val circleSize = (size + 5f).coerceAtLeast(16f)
        view.setTextViewTextSize(R.id.circle, TypedValue.COMPLEX_UNIT_DIP, circleSize)
        view.setViewPadding(R.id.circle, 0, dp(2f), dp(6f), dp(2f))

        view.setTextViewText(R.id.label, styled(item.title))
        view.setTextColor(R.id.label, if (item.done) config.doneTextColor else config.textColor)
        view.setTextViewTextSize(R.id.label, TypedValue.COMPLEX_UNIT_DIP, size)

        val vPad = dp(config.rowSpacingDp / 2f)
        view.setViewPadding(R.id.row, 0, vPad, 0, vPad)

        // 行内三个落点：圆圈=勾选、文字=编辑、整行其余部分=新建
        view.setOnClickFillInIntent(
            R.id.circle,
            fillIn("toggle", item.id, item.column)
        )
        view.setOnClickFillInIntent(
            R.id.label,
            fillIn("edit", item.id, item.column)
        )
        view.setOnClickFillInIntent(R.id.row, fillIn("add", item.id, item.column))
        return view
    }

    private fun fillIn(action: String, todoId: Long, itemColumn: Int): Intent =
        Intent()
            .putExtra(WidgetRenderer.EXTRA_ITEM_ACTION, action)
            .putExtra(WidgetRenderer.EXTRA_WIDGET, appWidgetId)
            .putExtra(WidgetRenderer.EXTRA_TODO, todoId)
            .putExtra(WidgetRenderer.EXTRA_COLUMN, itemColumn)

    /**
     * RemoteViews 没有设置字重的现成方法，用带 StyleSpan 的 CharSequence 代替：
     * 只影响字重，不影响字号与行高，换卡片尺寸时观感一致。
     */
    private fun styled(text: String): CharSequence {
        if (!config.bold || text.isEmpty()) {
            return text
        }
        return android.text.SpannableString(text).apply {
            setSpan(
                android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                0,
                text.length,
                android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }

    override fun getLoadingView(): RemoteViews? = null

    override fun getViewTypeCount(): Int = 1

    override fun getItemId(position: Int): Long = items.getOrNull(position)?.id ?: 0L

    override fun hasStableIds(): Boolean = true
}
