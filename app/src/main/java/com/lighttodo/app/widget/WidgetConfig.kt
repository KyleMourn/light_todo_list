package com.lighttodo.app.widget

import android.content.Context
import org.json.JSONObject

/**
 * 单个小组件实例的外观配置。所有字段都有默认值，老实例没有配置时按默认渲染。
 *
 * 放在 widget 包里是因为渲染与设置页共用；存储按 appWidgetId 分 key，实例之间互不影响。
 */
data class WidgetConfig(
    val fontSizeSp: Int = DEFAULT_FONT_SP,
    val textColor: Int = DEFAULT_TEXT_COLOR,
    val doneTextColor: Int = DEFAULT_DONE_COLOR,
    val backgroundColor: Int = DEFAULT_BACKGROUND,
    val rowSpacingDp: Int = DEFAULT_ROW_SPACING,
    val showTitle: Boolean = true,
    /** 卡片名称：App 首页列表与小组件顶部标题共用这一个字段。空串回退成「卡片 #id」。 */
    val cardName: String = "",
    /** 小组件里是否显示已完成的待办。 */
    val showDone: Boolean = true,
    /** 排序：0 = 按最后修改时间新→旧；1 = 旧→新。 */
    val sortMode: Int = SORT_NEWEST_FIRST,
    /** 液态玻璃外观：半透明渐变 + 高光描边，覆盖 backgroundColor。 */
    val glass: Boolean = false,
    /** 已完成的待办是否沉到底部。关掉后完全按手动顺序排列。 */
    val sinkDone: Boolean = true,
    /** 文字是否加粗。 */
    val bold: Boolean = false,
    /** 完成/取消完成后的动画时长（毫秒），0 表示不做动画。 */
    val animMs: Int = DEFAULT_ANIM_MS,
    /** 双栏卡片左栏小标题。 */
    val leftTitle: String = DEFAULT_LEFT_TITLE,
    /** 双栏卡片右栏小标题。 */
    val rightTitle: String = DEFAULT_RIGHT_TITLE,
    /** 是否为双栏卡片（由双栏 provider 创建时置 true，经典卡片保持 false）。 */
    val dual: Boolean = false
) {

    fun toJson(): String = JSONObject()
        .put(KEY_FONT, fontSizeSp)
        .put(KEY_TEXT, textColor)
        .put(KEY_DONE, doneTextColor)
        .put(KEY_BG, backgroundColor)
        .put(KEY_SPACING, rowSpacingDp)
        .put(KEY_TITLE, showTitle)
        .put(KEY_CARD_NAME, cardName)
        // 旧版本读 headerTitle，这里继续写一份保持兼容
        .put(KEY_HEADER, cardName)
        .put(KEY_SHOW_DONE, showDone)
        .put(KEY_SORT, sortMode)
        .put(KEY_GLASS, glass)
        .put(KEY_SINK_DONE, sinkDone)
        .put(KEY_BOLD, bold)
        .put(KEY_ANIM, animMs)
        .put(KEY_LEFT_TITLE, leftTitle)
        .put(KEY_RIGHT_TITLE, rightTitle)
        .put(KEY_DUAL, dual)
        .toString()

    companion object {
        const val MIN_FONT_SP = 11
        const val MAX_FONT_SP = 20
        const val MIN_ROW_SPACING = 0
        const val MAX_ROW_SPACING = 12
        const val MIN_ANIM_MS = 0
        const val MAX_ANIM_MS = 600

        const val DEFAULT_FONT_SP = 13
        const val DEFAULT_TEXT_COLOR = 0xFFE6E6EC.toInt()
        const val DEFAULT_DONE_COLOR = 0xFF9C9CA8.toInt()
        const val DEFAULT_BACKGROUND = 0xFF23232A.toInt()
        const val DEFAULT_ROW_SPACING = 4
        const val DEFAULT_ANIM_MS = 220
        const val DEFAULT_HEADER_TITLE = "待办清单"
        const val DEFAULT_LEFT_TITLE = "左栏"
        const val DEFAULT_RIGHT_TITLE = "右栏"
        const val SORT_NEWEST_FIRST = 0
        const val SORT_OLDEST_FIRST = 1

        private const val KEY_FONT = "fontSizeSp"
        private const val KEY_TEXT = "textColor"
        private const val KEY_DONE = "doneTextColor"
        private const val KEY_BG = "backgroundColor"
        private const val KEY_SPACING = "rowSpacingDp"
        private const val KEY_TITLE = "showTitle"
        private const val KEY_CARD_NAME = "cardName"
        private const val KEY_HEADER = "headerTitle"
        private const val KEY_SHOW_DONE = "showDone"
        private const val KEY_SORT = "sortMode"
        private const val KEY_GLASS = "glass"
        private const val KEY_SINK_DONE = "sinkDone"
        private const val KEY_BOLD = "bold"
        private const val KEY_ANIM = "animMs"
        private const val KEY_LEFT_TITLE = "leftTitle"
        private const val KEY_RIGHT_TITLE = "rightTitle"
        private const val KEY_DUAL = "dual"

        /** 解析失败或字段缺失都退回默认值，避免坏数据把小组件画崩。 */
        fun fromJson(raw: String?): WidgetConfig {
            if (raw.isNullOrBlank()) return WidgetConfig()
            return runCatching {
                val json = JSONObject(raw)
                WidgetConfig(
                    fontSizeSp = json.optInt(KEY_FONT, DEFAULT_FONT_SP)
                        .coerceIn(MIN_FONT_SP, MAX_FONT_SP),
                    textColor = json.optInt(KEY_TEXT, DEFAULT_TEXT_COLOR),
                    doneTextColor = json.optInt(KEY_DONE, DEFAULT_DONE_COLOR),
                    backgroundColor = json.optInt(KEY_BG, DEFAULT_BACKGROUND),
                    rowSpacingDp = json.optInt(KEY_SPACING, DEFAULT_ROW_SPACING)
                        .coerceIn(MIN_ROW_SPACING, MAX_ROW_SPACING),
                    showTitle = json.optBoolean(KEY_TITLE, true),
                    cardName = json.optString(KEY_CARD_NAME, "")
                        .ifBlank { json.optString(KEY_HEADER, "") },
                    showDone = json.optBoolean(KEY_SHOW_DONE, true),
                    sortMode = if (json.optInt(KEY_SORT, SORT_NEWEST_FIRST) == SORT_OLDEST_FIRST) {
                        SORT_OLDEST_FIRST
                    } else {
                        SORT_NEWEST_FIRST
                    },
                    glass = json.optBoolean(KEY_GLASS, false),
                    sinkDone = json.optBoolean(KEY_SINK_DONE, true),
                    bold = json.optBoolean(KEY_BOLD, false),
                    animMs = json.optInt(KEY_ANIM, DEFAULT_ANIM_MS)
                        .coerceIn(MIN_ANIM_MS, MAX_ANIM_MS),
                    leftTitle = json.optString(KEY_LEFT_TITLE, "").ifBlank { DEFAULT_LEFT_TITLE },
                    rightTitle = json.optString(KEY_RIGHT_TITLE, "").ifBlank { DEFAULT_RIGHT_TITLE },
                    dual = json.optBoolean(KEY_DUAL, false)
                )
            }.getOrDefault(WidgetConfig())
        }
    }
}

/** 按 appWidgetId 存取配置：`widget_cfg` 里一个 id 一条 JSON。 */
object WidgetConfigStore {

    private const val PREFS = "light_todo_widget_cfg"

    fun load(context: Context, appWidgetId: Int): WidgetConfig =
        WidgetConfig.fromJson(prefs(context).getString(key(appWidgetId), null))

    fun save(context: Context, appWidgetId: Int, config: WidgetConfig) {
        prefs(context).edit().putString(key(appWidgetId), config.toJson()).apply()
    }

    fun delete(context: Context, appWidgetIds: IntArray) {
        if (appWidgetIds.isEmpty()) return
        val editor = prefs(context).edit()
        appWidgetIds.forEach { editor.remove(key(it)) }
        editor.apply()
    }

    /** 设置页列实例用：返回 id -> 配置（没有配置的实例不会出现在这里）。 */
    fun all(context: Context): Map<Int, WidgetConfig> =
        prefs(context).all.mapNotNull { (k, v) ->
            val id = k.removePrefix("cfg_").toIntOrNull() ?: return@mapNotNull null
            id to WidgetConfig.fromJson(v as? String)
        }.toMap()

    private fun key(appWidgetId: Int) = "cfg_$appWidgetId"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
