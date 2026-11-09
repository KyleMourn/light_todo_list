package com.lighttodo.app.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.util.Log
import com.lighttodo.app.CardDetailActivity
import com.lighttodo.app.MainActivity
import com.lighttodo.app.EditTodoActivity
import com.lighttodo.app.WidgetSettingsActivity
import com.lighttodo.app.data.TodoRepository
import kotlinx.coroutines.runBlocking

/**
 * 卡片实例的生命周期 + 卡片内点击入口。
 *
 * 仍然叫 TodoWidgetReceiver 是为了让已经放到桌面上的实例（ComponentInfo 不变）继续有效。
 * 之前 extends GlanceAppWidgetReceiver，现在换成 AppWidgetProvider：每次数据/配置变化都
 * 重新拼 RemoteViews，点击用唯一 requestCode 的 PendingIntent。
 */
open class TodoWidgetReceiver : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { WidgetRenderer.update(context, it) }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        manager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: android.os.Bundle
    ) {
        // 拉伸/缩小后行数变化，立刻按新尺寸重排
        WidgetRenderer.update(context, appWidgetId)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        WidgetConfigStore.delete(context, appWidgetIds)
        super.onDeleted(context, appWidgetIds)
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(
            "LightTodo",
            "onReceive action=${intent.action} widget=${intent.getIntExtra(WidgetRenderer.EXTRA_WIDGET, -1)} column=${intent.getIntExtra(WidgetRenderer.EXTRA_COLUMN, -1)}"
        )
        when (intent.action) {
            WidgetRenderer.ACTION_TOGGLE -> {
                val listId = intent.getIntExtra(WidgetRenderer.EXTRA_WIDGET, -1)
                val todoId = intent.getLongExtra(WidgetRenderer.EXTRA_TODO, -1L)
                if (listId > 0 && todoId > 0) {
                    toggleAsync(context, listId, todoId, goAsync())
                }
            }

            WidgetRenderer.ACTION_EDIT -> {
                val listId = intent.getIntExtra(WidgetRenderer.EXTRA_WIDGET, -1)
                val todoId = intent.getLongExtra(WidgetRenderer.EXTRA_TODO, -1L)
                if (listId > 0 && todoId > 0) {
                    startCardStack(
                        context,
                        listId,
                        target = Intent(context, EditTodoActivity::class.java)
                            .putExtra(EditTodoActivity.EXTRA_LIST_ID, listId)
                            .putExtra(EditTodoActivity.EXTRA_TODO_ID, todoId)
                    )
                }
            }

            // 集合控件里点的行：具体动作由 fillInIntent 的 extras 带进来
            WidgetRenderer.ACTION_ITEM -> {
                val listId = intent.getIntExtra(WidgetRenderer.EXTRA_WIDGET, -1)
                val todoId = intent.getLongExtra(WidgetRenderer.EXTRA_TODO, -1L)
                when (intent.getStringExtra(WidgetRenderer.EXTRA_ITEM_ACTION)) {
                    "toggle" -> if (listId > 0 && todoId > 0) {
                        toggleAsync(context, listId, todoId, goAsync())
                    }

                    "edit" -> if (listId > 0 && todoId > 0) {
                        startCardStack(
                            context,
                            listId,
                            target = Intent(context, EditTodoActivity::class.java)
                                .putExtra(EditTodoActivity.EXTRA_LIST_ID, listId)
                                .putExtra(EditTodoActivity.EXTRA_TODO_ID, todoId)
                        )
                    }

                    else -> openCard(context, intent, focusAdd = true)
                }
            }

            WidgetRenderer.ACTION_ADD -> openCard(context, intent, focusAdd = true)
            WidgetRenderer.ACTION_CARD -> openCard(context, intent, focusAdd = false)

            WidgetRenderer.ACTION_SETTINGS -> {
                val listId = intent.getIntExtra(WidgetRenderer.EXTRA_WIDGET, -1)
                if (listId > 0) {
                    startCardStack(
                        context,
                        listId,
                        target = Intent(context, WidgetSettingsActivity::class.java)
                            .putExtra(WidgetSettingsActivity.EXTRA_WIDGET_ID, listId)
                    )
                }
            }

            else -> super.onReceive(context, intent)
        }
    }

    /**
     * 卡片内勾选 / 取消完成。
     *
     * 放到工作线程做，避免在广播主线程里跑 JSON 读写；写完立刻刷新。
     * 动画开关来自这张卡片的配置：animMs > 0 走「整卡重绘 + 淡入」，
     * 否则只通知集合控件重新读数据（更轻，也不会把 ListView 滚回顶部）。
     */
    private fun toggleAsync(
        context: Context,
        listId: Int,
        todoId: Long,
        pending: android.content.BroadcastReceiver.PendingResult?
    ) {
        val appContext = context.applicationContext
        val t0 = android.os.SystemClock.elapsedRealtime()
        lastToggleAt = t0
        Thread {
            runCatching {
                runBlocking { TodoRepository.get(appContext).toggle(listId, todoId) }
                val t1 = android.os.SystemClock.elapsedRealtime()
                val animMs = WidgetConfigStore.load(appContext, listId).animMs
                if (animMs > 0) {
                    WidgetRenderer.render(appContext, listId, animMs)
                } else {
                    WidgetRenderer.refreshRows(appContext, listId)
                }
                val t2 = android.os.SystemClock.elapsedRealtime()
                Log.i(
                    PERF_TAG,
                    "toggle w=$listId todo=$todoId write=${t1 - t0}ms dispatch=${t2 - t1}ms anim=$animMs"
                )
            }.onFailure { Log.w(PERF_TAG, "toggle failed w=$listId todo=$todoId", it) }
            pending?.finish()
        }.apply {
            name = "widget-toggle-$listId"
            isDaemon = true
        }.start()
    }

    private fun openCard(context: Context, intent: Intent, focusAdd: Boolean) {        val listId = intent.getIntExtra(WidgetRenderer.EXTRA_WIDGET, -1)
        val column = intent.getIntExtra(WidgetRenderer.EXTRA_COLUMN, 0).coerceIn(0, 1)
        startCardStack(context, listId, focusAdd = focusAdd, column = column)
    }

    /**
     * 从桌面直接进某个页面时，先补出「首页 → 卡片详情」这一层，
     * 这样按返回键是 编辑页 → 卡片详情 → 首页 → 桌面，而不是一步退回桌面。
     */
    private fun startCardStack(
        context: Context,
        listId: Int,
        focusAdd: Boolean = false,
        column: Int = 0,
        target: Intent? = null
    ) {
        val root = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val detail = Intent(context, CardDetailActivity::class.java)
            .putExtra(CardDetailActivity.EXTRA_LIST_ID, listId)
            .putExtra(CardDetailActivity.EXTRA_FOCUS_ADD, focusAdd)
            .putExtra(CardDetailActivity.EXTRA_COLUMN, column)
        val intents = arrayListOf(root, detail)
        if (target != null) intents.add(target)
        context.startActivities(intents.toTypedArray())
    }

    companion object {
        private const val PERF_TAG = "LightTodoPerf"

        /** 最近一次点击的时间戳（elapsedRealtime），给 RemoteViewsFactory 算「点击到出图」的间隔。 */
        @Volatile
        var lastToggleAt: Long = 0L
    }
}
