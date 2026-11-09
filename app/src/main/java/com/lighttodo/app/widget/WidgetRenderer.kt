package com.lighttodo.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import com.lighttodo.app.R
import com.lighttodo.app.data.TodoItem
import com.lighttodo.app.data.TodoRepository

/**
 * 用原生 RemoteViews 渲染桌面卡片。
 *
 * 之所以不用 Glance：Glance 的 update() 在没有内部 state 变化时只重发同一份 RemoteViews，
 * 数据改了画面却不刷新；而且它的 ActionParameters 依赖会话缓存，OEM 桌面缓存旧视图时点击会失效。
 * 这里每次写入都重新拼一份 RemoteViews 再 updateAppWidget，点击用带唯一 requestCode 的
 * PendingIntent，行为可预测。
 */
object WidgetRenderer {

    private const val TAG = "LightTodo"
    private const val TAG_PERF = "LightTodoPerf"

    const val ACTION_TOGGLE = "com.lighttodo.app.action.TOGGLE"
    const val ACTION_EDIT = "com.lighttodo.app.action.EDIT"
    const val ACTION_ADD = "com.lighttodo.app.action.ADD"
    const val ACTION_CARD = "com.lighttodo.app.action.CARD"
    const val ACTION_SETTINGS = "com.lighttodo.app.action.SETTINGS"

    /** 集合控件里每一行的统一入口：具体动作由 fillInIntent 的 EXTRA_ITEM_ACTION 决定。 */
    const val ACTION_ITEM = "com.lighttodo.app.action.ITEM"

    const val EXTRA_WIDGET = "widget_id"
    const val EXTRA_TODO = "todo_id"
    const val EXTRA_ITEM_ACTION = "item_action"

    /** 双栏卡片里这个 ListView 属于哪一栏：0=左，1=右。 */
    const val EXTRA_COLUMN = "widget_column"

    /** 卡片四周的视觉留白（不是死区：留白放在可点击的子视图内部）。 */
    private const val H_PADDING_DP = 6f
    private const val TITLE_PADDING_START_DP = 14f
    private const val TITLE_PADDING_END_DP = 6f
    private const val TOP_PADDING_DP = 2f

    /** 隐藏标题时，顶部保留一条可点击的细带（进卡片界面），内容紧跟其下。 */
    private const val HIDDEN_TITLE_STRIP_DP = 8f

    fun updateAll(context: Context) {
        widgetIds(context).forEach { update(context, it) }
    }

    fun update(context: Context, appWidgetId: Int) {
        render(context, appWidgetId, fadeMs = 0)
    }

    /**
     * 只让集合控件重新读数据，不重发整份 RemoteViews。
     *
     * 勾选/取消完成只影响列表内容，标题、配色、尺寸都没变；走 notify 比 updateAppWidget
     * 少一次整卡重建，体感更快，也不会把 ListView 的滚动位置重置掉。
     */
    fun refreshRows(context: Context, appWidgetId: Int) {
        if (appWidgetId <= 0) return
        val appContext = context.applicationContext
        val dual = runCatching {
            WidgetConfigStore.load(appContext, appWidgetId).dual
        }.getOrDefault(false)
        runCatching {
            val manager = AppWidgetManager.getInstance(appContext)
            if (dual) {
                manager.notifyAppWidgetViewDataChanged(appWidgetId, R.id.rows_left)
                manager.notifyAppWidgetViewDataChanged(appWidgetId, R.id.rows_right)
            } else {
                manager.notifyAppWidgetViewDataChanged(appWidgetId, R.id.rows)
            }
        }.onFailure { Log.w(TAG, "notify rows failed id=$appWidgetId", it) }
    }

    /**
     * 渲染这张卡片；fadeMs > 0 时额外做一次整卡淡入。
     *
     * RemoteViews 没有动画 API，只能用「把 alpha 改小再分几步推回 1」的方式凑；
     * setFloat 需要 Android 12+，低版本或宿主吞掉中间帧时最终会停在 alpha=1，
     * 等同于没做动画，不会留下半透明卡片。
     */
    fun render(context: Context, appWidgetId: Int, fadeMs: Int) {
        if (appWidgetId <= 0) return
        val appContext = context.applicationContext
        val manager = AppWidgetManager.getInstance(appContext)
        runCatching {
            // 老版本那份全局数据在这里搬到第一个实例上（幂等）
            TodoRepository.get(appContext).migrateLegacyIfNeeded(appWidgetId)
            val config = WidgetConfigStore.load(appContext, appWidgetId)
            val items = TodoRepository.get(appContext).snapshot(appWidgetId)
            val options = manager.getAppWidgetOptions(appWidgetId)
            val views = build(appContext, appWidgetId, config, items, options)
            val fading = fadeMs > 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            if (fading) views.setFloat(R.id.card_root, "setAlpha", Schedule.FADE_START)
            manager.updateAppWidget(appWidgetId, views)
            if (config.dual) {
                manager.notifyAppWidgetViewDataChanged(appWidgetId, R.id.rows_left)
                manager.notifyAppWidgetViewDataChanged(appWidgetId, R.id.rows_right)
            } else {
                manager.notifyAppWidgetViewDataChanged(appWidgetId, R.id.rows)
            }
            Log.d(TAG, "render id=$appWidgetId n=${items.size} done=${items.count { it.done }}")
            if (fading) {
                Schedule.fadeIn(manager, appWidgetId, views, fadeMs)
            }
        }.onFailure { Log.w(TAG, "render failed id=$appWidgetId", it) }
    }

    /**
     * 分步把整卡 alpha 推回 1。每步都是一次 updateAppWidget；
     * 宿主若不刷新中间帧，最后一步仍然会把卡片恢复到不透明。
     */
    private object Schedule {
        const val FADE_START = 0.45f
        private val STEPS = floatArrayOf(0.66f, 0.84f, 1f)

        fun fadeIn(manager: AppWidgetManager, appWidgetId: Int, views: RemoteViews, fadeMs: Int) {
            val perStep = (fadeMs / (STEPS.size + 1)).coerceAtLeast(24)
            Thread {
                runCatching {
                    STEPS.forEach { alpha ->
                        Thread.sleep(perStep.toLong())
                        views.setFloat(R.id.card_root, "setAlpha", alpha)
                        manager.updateAppWidget(appWidgetId, views)
                    }
                }.onFailure { Log.w(TAG_PERF, "fade failed id=$appWidgetId", it) }
            }.apply {
                name = "widget-fade-$appWidgetId"
                isDaemon = true
            }.start()
        }
    }

    private fun widgetIds(context: Context): IntArray {
        val manager = AppWidgetManager.getInstance(context)
        val classic = manager.getAppWidgetIds(
            ComponentName(context, TodoWidgetReceiver::class.java)
        )
        val dual = manager.getAppWidgetIds(
            ComponentName(context, DualWidgetReceiver::class.java)
        )
        return (classic.asList() + dual.asList()).distinct().toIntArray()
    }

    private fun build(
        context: Context,
        appWidgetId: Int,
        config: WidgetConfig,
        items: List<TodoItem>,
        options: Bundle
    ): RemoteViews {
        if (config.dual) {
            return buildDual(context, appWidgetId, config)
        }

        // 两种布局：显示标题（贴顶右对齐）/ 隐藏标题（顶部只留 8dp 点击带，内容整体上移）
        val showTitle = config.showTitle
        val views = RemoteViews(
            context.packageName,
            if (showTitle) R.layout.widget_card else R.layout.widget_card_notitle
        )
        val density = context.resources.displayMetrics.density
        fun dp(value: Float) = (value * density + 0.5f).toInt()

        if (config.glass) {
            views.setInt(R.id.card_root, "setBackgroundResource", R.drawable.widget_bg_glass)
        } else {
            views.setInt(R.id.card_root, "setBackgroundColor", config.backgroundColor)
        }

        // 玻璃底：圆角更大、外圈有 1dp 亮边。内容必须整体往里收一圈，
        // 否则首行会压在上边沿的亮边上、最后一行会被下沿的圆角切掉（列表贴到圆角就会被裁）。
        val glassInset = if (config.glass) dp(4f) else 0
        val glassTop = if (config.glass) dp(6f) else 0

        val header = config.cardName.ifBlank { context.getString(R.string.card_title, appWidgetId) }
        if (showTitle) {
            views.setViewPadding(
                R.id.title_row,
                dp(TITLE_PADDING_START_DP) + glassInset,
                dp(TOP_PADDING_DP) + glassTop,
                dp(TITLE_PADDING_END_DP) + glassInset,
                0
            )
            views.setTextViewText(R.id.title, boldTitle(header, config.bold))
            views.setTextColor(R.id.title, config.textColor)
            views.setTextViewTextSize(
                R.id.title,
                TypedValue.COMPLEX_UNIT_DIP,
                config.fontSizeSp.toFloat()
            )
            views.setOnClickPendingIntent(
                R.id.title_row,
                action(context, appWidgetId, ACTION_CARD, 0L, 2)
            )

            views.setViewVisibility(R.id.add, View.VISIBLE)
            views.setTextViewTextSize(
                R.id.add,
                TypedValue.COMPLEX_UNIT_DIP,
                config.fontSizeSp.toFloat()
            )
            views.setTextColor(R.id.add, config.textColor)
            views.setOnClickPendingIntent(
                R.id.add,
                action(context, appWidgetId, ACTION_ADD, 0L, 3)
            )
        } else {
            // 顶部 8dp 细带：进卡片界面
            views.setOnClickPendingIntent(
                R.id.top_strip,
                action(context, appWidgetId, ACTION_CARD, 0L, 2)
            )
        }

        // 列表是 ListView 集合控件：可在卡片内上下滑动；点击走模板 + fillInIntent。
        // 注意：ListView 是 AdapterView，不能 setOnClickListener（会抛
        // Don't call setOnClickListener for an AdapterView）；空白新建靠每行的 fillInIntent「add」。
        // 列表内边距：玻璃底时再往里收一圈，并让列表允许画到 padding 区域
        // （clipToPadding=false），这样滚动时首/末行不会被上下的内边距「咬掉」。
        val listStart = dp(4f) + glassInset
        val listEnd = dp(2f) + glassInset
        val listTop = if (config.glass) dp(if (showTitle) 2f else 8f) else 0
        val listBottom = if (config.glass) dp(12f) else 0
        views.setViewPadding(R.id.rows, listStart, listTop, listEnd, listBottom)

        if (showTitle) {
            views.setEmptyView(R.id.rows, R.id.empty_text)
            views.setViewPadding(
                R.id.empty_text,
                dp(8f) + glassInset,
                dp(2f) + glassTop / 2,
                dp(8f) + glassInset,
                0
            )
            views.setOnClickPendingIntent(
                R.id.empty_text,
                action(context, appWidgetId, ACTION_ADD, 0L, 5)
            )
        }
        views.setPendingIntentTemplate(
            R.id.rows,
            itemTemplate(context, appWidgetId)
        )
        val adapter = Intent(context, TodoWidgetService::class.java)
            .putExtra(EXTRA_WIDGET, appWidgetId)
            .setData(android.net.Uri.parse("lighttodo://widget/$appWidgetId"))
        views.setRemoteAdapter(R.id.rows, adapter)
        return views
    }

    /**
     * 双栏卡片：左右两个 ListView 各绑一个适配器，中间用 1dp 竖线分隔。
     * 栏标题、加号、空状态点击都带 EXTRA_COLUMN，新建的待办会落到对应栏。
     */
    private fun buildDual(
        context: Context,
        appWidgetId: Int,
        config: WidgetConfig
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_dual)
        val density = context.resources.displayMetrics.density
        fun dp(value: Float) = (value * density + 0.5f).toInt()
        if (config.glass) {
            views.setInt(R.id.card_root, "setBackgroundResource", R.drawable.widget_bg_glass)
        } else {
            views.setInt(R.id.card_root, "setBackgroundColor", config.backgroundColor)
        }
        views.setInt(R.id.divider, "setBackgroundColor", 0x33FFFFFF)

        // 玻璃底：两栏内容一并往里收，底部多留 12dp，避免最后一行被下沿圆角切掉。
        if (config.glass) {
            views.setViewPadding(R.id.left_title_row, dp(10f), dp(8f), dp(2f), 0)
            views.setViewPadding(R.id.right_title_row, dp(6f), dp(8f), dp(8f), 0)
            views.setViewPadding(R.id.rows_left, dp(8f), dp(2f), dp(2f), dp(12f))
            views.setViewPadding(R.id.rows_right, dp(6f), dp(2f), dp(8f), dp(12f))
        }
        // 列表是否裁到 padding 由布局里的 android:clipToPadding="false" 决定。
        // RemoteViews 只能反射调用带 @RemotableViewMethod 的方法，setClipToPadding 不在白名单，
        // 用 setBoolean 调它会让整份 RemoteViews 应用失败（桌面显示「载入窗口小部件时出现问题」）。

        // 注意：不能给「包着 AdapterView 的容器」设点击 —— RemoteViews 应用时会整体失败，
        // 桌面于是保留旧的那份 RemoteViews（表现为「整张卡点什么都没反应」）。
        // 所以栏内空白的三条入口改由标题栏、加号、空状态这三个叶子控件承担。

        fun title(viewId: Int, text: String) {
            views.setTextViewText(viewId, boldTitle(text, config.bold))
            views.setTextColor(viewId, config.textColor)
            views.setTextViewTextSize(
                viewId,
                TypedValue.COMPLEX_UNIT_DIP,
                config.fontSizeSp.toFloat()
            )
        }

        title(R.id.left_title, config.leftTitle.ifBlank { context.getString(R.string.column_left) })
        title(R.id.right_title, config.rightTitle.ifBlank { context.getString(R.string.column_right) })
        title(R.id.left_add, "+")
        title(R.id.right_add, "+")
        views.setTextColor(R.id.empty_left, config.doneTextColor)
        views.setTextColor(R.id.empty_right, config.doneTextColor)
        views.setTextViewTextSize(
            R.id.empty_left,
            TypedValue.COMPLEX_UNIT_DIP,
            config.fontSizeSp.toFloat()
        )
        views.setTextViewTextSize(
            R.id.empty_right,
            TypedValue.COMPLEX_UNIT_DIP,
            config.fontSizeSp.toFloat()
        )

        // 标题栏点一下进 App 的卡片详情（栏标题是叶子 TextView，RemoteViews 里点击最稳；
        // 外层 title_row 也设一份，点标题右侧空白同样能进去）
        // 每个点击入口用不同的 slot（requestCode = widgetId*1000+slot）。
        // 同一 requestCode 且 action/data 相同会让 PendingIntent 复用并覆盖 extras，两栏会串栏；
        // 9 还被 itemTemplate 占用，所以这里从 11 起排。
        views.setOnClickPendingIntent(
            R.id.left_title,
            actionWithColumn(context, appWidgetId, ACTION_CARD, 0, 11)
        )
        views.setOnClickPendingIntent(
            R.id.right_title,
            actionWithColumn(context, appWidgetId, ACTION_CARD, 1, 12)
        )
        views.setOnClickPendingIntent(
            R.id.left_title_row,
            actionWithColumn(context, appWidgetId, ACTION_CARD, 0, 2)
        )
        views.setOnClickPendingIntent(
            R.id.right_title_row,
            actionWithColumn(context, appWidgetId, ACTION_CARD, 1, 13)
        )
        views.setOnClickPendingIntent(
            R.id.left_add,
            actionWithColumn(context, appWidgetId, ACTION_ADD, 0, 3)
        )
        views.setOnClickPendingIntent(
            R.id.right_add,
            actionWithColumn(context, appWidgetId, ACTION_ADD, 1, 4)
        )
        views.setEmptyView(R.id.rows_left, R.id.empty_left)
        views.setEmptyView(R.id.rows_right, R.id.empty_right)
        views.setOnClickPendingIntent(
            R.id.empty_left,
            actionWithColumn(context, appWidgetId, ACTION_ADD, 0, 5)
        )
        views.setOnClickPendingIntent(
            R.id.empty_right,
            actionWithColumn(context, appWidgetId, ACTION_ADD, 1, 6)
        )

        val template = itemTemplate(context, appWidgetId)
        views.setPendingIntentTemplate(R.id.rows_left, template)
        views.setPendingIntentTemplate(R.id.rows_right, template)
        views.setRemoteAdapter(R.id.rows_left, adapterIntent(context, appWidgetId, 0))
        views.setRemoteAdapter(R.id.rows_right, adapterIntent(context, appWidgetId, 1))
        return views
    }

    private fun adapterIntent(context: Context, appWidgetId: Int, column: Int): Intent =
        Intent(context, TodoWidgetService::class.java)
            .putExtra(EXTRA_WIDGET, appWidgetId)
            .putExtra(EXTRA_COLUMN, column)
            .setData(android.net.Uri.parse("lighttodo://widget/$appWidgetId?column=$column"))

    private fun actionWithColumn(
        context: Context,
        appWidgetId: Int,
        action: String,
        column: Int,
        slot: Int
    ): PendingIntent {
        val intent = Intent(context, TodoWidgetReceiver::class.java)
            .setAction(action)
            .putExtra(EXTRA_WIDGET, appWidgetId)
            .putExtra(EXTRA_COLUMN, column)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        return PendingIntent.getBroadcast(context, appWidgetId * 1000 + slot, intent, flags)
    }

    /**
     * 集合控件的模板 PendingIntent：必须 mutable，fillInIntent 的 extras 才会被合并进来。
     * 每个实例一个 requestCode，避免不同卡片互相串扰。
     */
    private fun itemTemplate(context: Context, appWidgetId: Int): PendingIntent {
        val intent = Intent(context, TodoWidgetReceiver::class.java)
            .setAction(ACTION_ITEM)
            .putExtra(EXTRA_WIDGET, appWidgetId)
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        flags = flags or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_IMMUTABLE
        }
        return PendingIntent.getBroadcast(context, appWidgetId * 1000 + 9, intent, flags)
    }

    /**
     * RemoteViews 没有设置字重的现成方法，用带 StyleSpan 的 CharSequence 代替。
     * 只影响字重，不影响字号，所以不同尺寸卡片上仍然一致。
     */
    private fun boldTitle(text: String, bold: Boolean): CharSequence {
        if (!bold || text.isEmpty()) {
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

    /**
     * 每个点击点都有独立的 requestCode（按 widgetId + 槽位），避免 PendingIntent 被复用到别的行。
     */
    private fun action(
        context: Context,
        appWidgetId: Int,
        action: String,
        todoId: Long,
        slot: Int
    ): PendingIntent {
        val intent = Intent(context, TodoWidgetReceiver::class.java)
            .setAction(action)
            .putExtra(EXTRA_WIDGET, appWidgetId)
        if (todoId > 0L) intent.putExtra(EXTRA_TODO, todoId)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        return PendingIntent.getBroadcast(context, appWidgetId * 1000 + slot, intent, flags)
    }
}
