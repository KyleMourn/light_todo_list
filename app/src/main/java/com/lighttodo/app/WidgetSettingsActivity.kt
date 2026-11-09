package com.lighttodo.app

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.lighttodo.app.widget.TodoWidgetReceiver
import com.lighttodo.app.widget.WidgetConfig
import com.lighttodo.app.widget.WidgetConfigStore
import com.lighttodo.app.widget.WidgetUpdater

/**
 * 小组件外观设置。
 *
 * - 不带 EXTRA_WIDGET_ID：列出桌面上所有实例，点一个进配置。
 * - 带 EXTRA_WIDGET_ID：编辑那一个实例的字号 / 颜色 / 行间距 / 标题显隐。
 *
 * 用原生 View 拼装，不引入 Compose 依赖；配置按 appWidgetId 存，实例之间互不影响。
 */
class WidgetSettingsActivity : Activity() {

    private var widgetId: Int = AppWidgetManager.INVALID_APPWIDGET_ID
    private lateinit var config: WidgetConfig

    // 编辑态控件
    private var fontBar: SeekBar? = null
    private var spacingBar: SeekBar? = null
    private var fontLabel: TextView? = null
    private var spacingLabel: TextView? = null
    private var previewCard: android.widget.LinearLayout? = null
    private var previewLeft: TextView? = null
    private var previewRight: TextView? = null
    private var previewDivider: View? = null
    private var titleSwitch: CheckBox? = null
    private val applyHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val applyLater = Runnable { autoApply() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        widgetId = intent.getIntExtra(EXTRA_WIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            showInstanceList()
        } else {
            config = WidgetConfigStore.load(this, widgetId)
            showEditor()
        }
    }

    // ---------- 实例列表 ----------

    private fun showInstanceList() {
        val ids = AppWidgetManager.getInstance(this)
            .getAppWidgetIds(ComponentName(this, TodoWidgetReceiver::class.java))

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.bg_page))
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }

        root.addView(label(getString(R.string.app_name) + " · 小组件设置", 22f, R.color.text_primary).apply {
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        root.addView(label("点一个实例调整字号、颜色和行间距。", 13f, R.color.text_secondary).apply {
            setPadding(0, dp(6), 0, dp(16))
        })

        if (ids.isEmpty()) {
            root.addView(label("桌面上还没有轻待办小组件。长按桌面 → 卡片 → 找到「轻待办」拖到桌面。", 14f, R.color.text_secondary))
        } else {
            ids.sorted().forEach { id ->
                val cfg = WidgetConfigStore.load(this, id)
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(16), dp(14), dp(16), dp(14))
                    background = rounded(0x14FFFFFF, dp(14))
                    setOnClickListener { openEditor(id) }
                }
                row.addView(label("小组件 #$id", 16f, R.color.text_primary))
                row.addView(label(
                    "${cfg.fontSizeSp}sp · 行距 ${cfg.rowSpacingDp}dp · ${if (cfg.showTitle) "显示标题" else "已隐藏标题"}",
                    12f,
                    R.color.text_secondary
                ).apply { setPadding(0, dp(4), 0, 0) })
                val margin = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                margin.bottomMargin = dp(10)
                root.addView(row, margin)
            }
        }

        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun openEditor(id: Int) {
        startActivity(
            Intent(this, WidgetSettingsActivity::class.java)
                .putExtra(EXTRA_WIDGET_ID, id)
        )
    }

    // ---------- 编辑单个实例 ----------

    private fun showEditor() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.bg_page))
            setPadding(dp(20), dp(24), dp(20), dp(32))
        }

        // 顶栏：返回 · 「外观」 · 当前卡片
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        bar.addView(label("←", 22f, R.color.text_primary).apply {
            setPadding(0, dp(6), dp(14), dp(6))
            setOnClickListener { finish() }
        })
        bar.addView(label("外观", 22f, R.color.text_primary).apply {
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(bar)
        root.addView(label(
            config.cardName.ifBlank { getString(R.string.card_title, widgetId) } + " · 改动即时生效",
            13f,
            R.color.text_secondary
        ).apply { setPadding(0, dp(4), 0, dp(16)) })

        // 预览：放在深色底托里，玻璃效果才看得出来
        val previewWrap = android.widget.FrameLayout(this).apply {
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = rounded(0xFF1B1B1F.toInt(), dp(16))
        }
        previewCard = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(config.backgroundColor, dp(14))
            clipToOutline = true
        }
        previewLeft = TextView(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                0,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        }
        previewDivider = View(this).apply {
            setBackgroundColor(0x33FFFFFF)
            layoutParams = android.widget.LinearLayout.LayoutParams(dp(1), dp(64)).apply {
                leftMargin = dp(10)
                rightMargin = dp(10)
            }
        }
        previewRight = TextView(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                0,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        }
        previewCard?.addView(previewLeft)
        previewCard?.addView(previewDivider)
        previewCard?.addView(previewRight)
        previewWrap.addView(
            previewCard,
            android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
            )
        )
        root.addView(previewWrap, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(18) })

        // 字号
        fontLabel = label("字号：${config.fontSizeSp}sp", 15f, R.color.text_primary)
        root.addView(fontLabel)
        fontBar = SeekBar(this).apply {
            max = WidgetConfig.MAX_FONT_SP - WidgetConfig.MIN_FONT_SP
            progress = config.fontSizeSp - WidgetConfig.MIN_FONT_SP
            setOnSeekBarChangeListener(simpleListener(
                onChange = {
                    config = config.copy(fontSizeSp = WidgetConfig.MIN_FONT_SP + progress)
                    fontLabel?.text = "字号：${config.fontSizeSp}sp"
                    refreshPreview()
                },
                onCommit = { autoApply() }
            ))
        }
        root.addView(fontBar)

        // 行间距
        spacingLabel = label("行间距：${config.rowSpacingDp}dp", 15f, R.color.text_primary)
        spacingLabel?.setPadding(0, dp(10), 0, 0)
        root.addView(spacingLabel)
        spacingBar = SeekBar(this).apply {
            max = WidgetConfig.MAX_ROW_SPACING
            progress = config.rowSpacingDp
            setOnSeekBarChangeListener(simpleListener(
                onChange = {
                    config = config.copy(rowSpacingDp = progress)
                    spacingLabel?.text = "行间距：${config.rowSpacingDp}dp"
                    refreshPreview()
                },
                onCommit = { autoApply() }
            ))
        }
        root.addView(spacingBar)

        // 动画速度：完成/取消完成时的下沉与上浮，0 表示关闭动画
        val animLabel = label(animLabelText(config.animMs), 15f, R.color.text_primary)
        animLabel.setPadding(0, dp(10), 0, 0)
        root.addView(animLabel)
        root.addView(SeekBar(this).apply {
            max = WidgetConfig.MAX_ANIM_MS - WidgetConfig.MIN_ANIM_MS
            progress = config.animMs - WidgetConfig.MIN_ANIM_MS
            setOnSeekBarChangeListener(simpleListener(
                onChange = {
                    config = config.copy(
                        animMs = (WidgetConfig.MIN_ANIM_MS + progress)
                            .coerceIn(WidgetConfig.MIN_ANIM_MS, WidgetConfig.MAX_ANIM_MS)
                    )
                    animLabel.text = animLabelText(config.animMs)
                },
                onCommit = { autoApply() }
            ))
        })

        // 三个颜色（文字颜色不提供全透明色块，否则字直接看不见）
        addColorRow(root, "文字颜色", config.textColor, allowTransparent = false) {
            config = config.copy(textColor = it)
            refreshPreview()
            autoApply()
        }
        addColorRow(root, "已完成颜色", config.doneTextColor, allowTransparent = false) {
            config = config.copy(doneTextColor = it)
            refreshPreview()
            autoApply()
        }
        addColorRow(root, "卡片背景色", config.backgroundColor, allowTransparent = true) {
            config = config.copy(backgroundColor = it)
            refreshPreview()
            autoApply()
        }

        // 背景透明度：滑杆值就是「透明度」，100% = 完全透明（alpha 0），0% = 完全不透明
        val alphaLabel = label("背景透明度：${transparencyPercent(config.backgroundColor)}%", 15f, R.color.text_primary)
        alphaLabel.setPadding(0, dp(10), 0, 0)
        root.addView(alphaLabel)
        root.addView(SeekBar(this).apply {
            max = 100
            progress = transparencyPercent(config.backgroundColor)
            setOnSeekBarChangeListener(simpleListener(
                onChange = {
                    val base = config.backgroundColor and 0x00FFFFFF
                    config = config.copy(backgroundColor = base or ((255 * (100 - progress) / 100) shl 24))
                    alphaLabel.text = "背景透明度：${transparencyPercent(config.backgroundColor)}%"
                    refreshPreview()
                },
                onCommit = { autoApply() }
            ))
        })

        // 排序方式：按最后修改时间正序 / 倒序
        root.addView(label("排序方式", 15f, R.color.text_primary).apply {
            setPadding(0, dp(12), 0, dp(4))
        })
        val sortGroup = android.widget.RadioGroup(this).apply {
            orientation = android.widget.RadioGroup.HORIZONTAL
        }
        val newest = android.widget.RadioButton(this).apply {
            id = android.view.View.generateViewId()
            text = "最新的在前"
            isChecked = config.sortMode == WidgetConfig.SORT_NEWEST_FIRST
            setTextColor(getColor(R.color.text_primary))
            setOnCheckedChangeListener { _, checked ->
                if (checked) {
                    config = config.copy(sortMode = WidgetConfig.SORT_NEWEST_FIRST)
                    autoApply()
                }
            }
        }
        val oldest = android.widget.RadioButton(this).apply {
            id = android.view.View.generateViewId()
            text = "最早的在前"
            isChecked = config.sortMode == WidgetConfig.SORT_OLDEST_FIRST
            setTextColor(getColor(R.color.text_primary))
            setOnCheckedChangeListener { _, checked ->
                if (checked) {
                    config = config.copy(sortMode = WidgetConfig.SORT_OLDEST_FIRST)
                    autoApply()
                }
            }
        }
        sortGroup.addView(newest)
        sortGroup.addView(oldest)
        root.addView(sortGroup)

        // 隐藏标题
        titleSwitch = CheckBox(this).apply {
            text = "隐藏顶部「${config.cardName.ifBlank { getString(R.string.card_title, widgetId) }}」标题"
            isChecked = !config.showTitle
            setTextColor(getColor(R.color.text_primary))
            setOnCheckedChangeListener { _, checked ->
                config = config.copy(showTitle = !checked)
                refreshPreview()
                autoApply()
            }
        }
        root.addView(titleSwitch, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) })

        // 显示已完成：关掉后小组件只列未完成项，App 详情页不受影响
        root.addView(CheckBox(this).apply {
            text = getString(R.string.label_show_done)
            isChecked = config.showDone
            setTextColor(getColor(R.color.text_primary))
            setOnCheckedChangeListener { _, checked ->
                config = config.copy(showDone = checked)
                refreshPreview()
                autoApply()
            }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        // 完成的待办是否沉底：关掉后已完成项留在原位，只变灰加删除线
        root.addView(CheckBox(this).apply {
            text = "已完成的待办沉到底部"
            isChecked = config.sinkDone
            setTextColor(getColor(R.color.text_primary))
            setOnCheckedChangeListener { _, checked ->
                config = config.copy(sinkDone = checked)
                refreshPreview()
                autoApply()
            }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        // 粗体：只改字重，不改字号
        root.addView(CheckBox(this).apply {
            text = "文字加粗"
            isChecked = config.bold
            setTextColor(getColor(R.color.text_primary))
            setOnCheckedChangeListener { _, checked ->
                config = config.copy(bold = checked)
                refreshPreview()
                autoApply()
            }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        // 液态玻璃：半透明渐变 + 高光边（RemoteViews 拿不到壁纸像素，做不了真模糊）
        root.addView(CheckBox(this).apply {
            text = "液态玻璃效果（半透明＋高光边）"
            isChecked = config.glass
            setTextColor(getColor(R.color.text_primary))
            setOnCheckedChangeListener { _, checked ->
                config = config.copy(glass = checked)
                refreshPreview()
                autoApply()
            }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))


        root.addView(Button(this).apply {
            text = "恢复默认"
            setOnClickListener {
                config = WidgetConfig()
                WidgetConfigStore.save(this@WidgetSettingsActivity, widgetId, config)
                WidgetUpdater.update(this@WidgetSettingsActivity, widgetId)
                Toast.makeText(this@WidgetSettingsActivity, "已恢复默认", Toast.LENGTH_SHORT).show()
                finish()
            }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(4) })

        refreshPreview()
        setContentView(ScrollView(this).apply { addView(root) })
    }

    /** 一个颜色行：标题 + 预设色块；选中的色块套一圈品牌色描边。 */
    private fun addColorRow(
        parent: LinearLayout,
        title: String,
        initial: Int,
        allowTransparent: Boolean = true,
        onChange: (Int) -> Unit
    ) {
        // 文字色/完成色去掉全透明，避免选完字看不见；背景色保留透明用来做无底卡片。
        val colors: List<Int> =
            if (allowTransparent) PALETTE.toList() else PALETTE.filter { it != 0 }
        parent.addView(label(title, 15f, R.color.text_primary).apply {
            setPadding(0, dp(14), 0, dp(6))
        })

        // 色板四周留出余量：选中色块会放大 1.12× 并套 3dp 描边，
        // 贴着容器边缘时左端会被裁掉，所以容器和滚动视图都要让出空间。
        val swatches = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        val views = mutableListOf<View>()
        val brand = getColor(R.color.brand)

        fun paintSelection(selectedIndex: Int) {
            views.forEachIndexed { index, view ->
                val argb = colors[index]
                val drawable = rounded(argb, dp(16))
                if (index == selectedIndex) {
                    drawable.setStroke(dp(3), brand)
                    view.scaleX = 1.12f
                    view.scaleY = 1.12f
                } else {
                    drawable.setStroke(dp(1), 0x33FFFFFF)
                    view.scaleX = 1f
                    view.scaleY = 1f
                }
                view.background = drawable
            }
        }

        colors.forEachIndexed { index, argb ->
            val view = View(this).apply {
                background = rounded(argb, dp(16))
                setOnClickListener {
                    paintSelection(index)
                    onChange(if (argb == 0) 0 else argb)
                }
            }
            views.add(view)
            swatches.addView(view, LinearLayout.LayoutParams(dp(30), dp(30)).apply {
                marginEnd = dp(8)
                topMargin = dp(10)
                bottomMargin = dp(4)
            })
        }
        paintSelection(colors.indexOfFirst { it == initial })
        parent.addView(HorizontalScrollView(this).apply {
            clipToPadding = false
            isFillViewport = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(swatches)
        })
    }

    private fun alphaPercent(argb: Int): Int = (argb ushr 24) * 100 / 255

    /** 滑杆语义是「透明度」：alpha 越大越不透明，所以这里取反。 */
    private fun transparencyPercent(argb: Int): Int = 100 - alphaPercent(argb)

    /** 动画滑杆的文案：0 表示关闭，其余显示毫秒数。 */
    private fun animLabelText(ms: Int): String =
        if (ms <= 0) "动画：关闭" else "动画：${ms}ms"

    private fun refreshPreview() {
        val card = previewCard ?: return
        // 预览要像桌面卡片那样是圆角：桌面端由 launcher 把整个卡片裁成圆角，
        // 这里用圆角背景 + clipToOutline 复现同样的观感（透明时透出深色托底）。
        if (config.glass) {
            card.setBackgroundResource(R.drawable.widget_bg_glass)
        } else {
            card.background = rounded(config.backgroundColor, dp(14))
        }
        card.clipToOutline = true

        val columns = listOfNotNull(previewLeft, previewRight)
        columns.forEach {
            it.setTextColor(config.textColor)
            it.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, config.fontSizeSp.toFloat())
            it.setLineSpacing(dp(config.rowSpacingDp).toFloat(), 1f)
            it.setTypeface(
                it.typeface,
                if (config.bold) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL
            )
        }

        // 双栏卡片：预览也给两列 + 中缝，和桌面上的样子对齐
        val dual = config.dual
        previewDivider?.visibility = if (dual) View.VISIBLE else View.GONE
        previewRight?.visibility = if (dual) View.VISIBLE else View.GONE
        if (dual) {
            previewLeft?.text = columnPreviewText(config.leftTitle)
            previewRight?.text = columnPreviewText(config.rightTitle) + "\n○ 读书 30 分钟"
        } else {
            val header = config.cardName.ifBlank { getString(R.string.card_title, widgetId) }
            previewLeft?.text = columnPreviewText(header) + "\n○ 取快递"
        }
    }

    /** 预览里一栏的示例内容：标题（可隐藏）+ 两条待办。 */
    private fun columnPreviewText(header: String): String {
        val shown = if (config.showTitle) "$header\n" else ""
        return shown + "○ 写周报\n● 买牛奶"
    }

    /** 输入框打字时不要每个字符都写盘，300ms 合并一次。 */
    private fun scheduleApply() {
        applyHandler.removeCallbacks(applyLater)
        applyHandler.postDelayed(applyLater, 300)
    }

    private fun simpleWatcher(onChange: (String) -> Unit) = object : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: android.text.Editable?) {
            onChange(s?.toString().orEmpty())
        }
    }

    private fun save() {
        autoApply()
        Toast.makeText(this, "已应用到小组件 #$widgetId", Toast.LENGTH_SHORT).show()
        finish()
    }

    /** 任何一处改动都立刻落盘并刷新这张卡片——不需要用户再点保存。 */
    private fun autoApply() {
        WidgetConfigStore.save(this, widgetId, config)
        WidgetUpdater.update(this, widgetId)
    }

    // ---------- 小工具 ----------

    private fun label(text: String, sizeSp: Float, colorRes: Int): TextView =
        TextView(this).apply {
            this.text = text
            textSize = sizeSp
            setTextColor(getColor(colorRes))
        }

    private fun simpleListener(
        onChange: () -> Unit,
        onCommit: () -> Unit = {}
    ) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
            onChange()
        }

        override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

        override fun onStopTrackingTouch(seekBar: SeekBar?) {
            onCommit()
        }
    }

    private fun rounded(argb: Int, radius: Int) = GradientDrawable().apply {
        setColor(argb)
        cornerRadius = radius.toFloat()
        setStroke(dp(1), 0x33FFFFFF)
    }

    private fun hex(argb: Int): String = String.format("#%08X", argb)

    private fun parseHex(raw: String): Int? {
        val s = raw.trim().removePrefix("#")
        if (s.length != 6 && s.length != 8) return null
        val value = s.toLongOrNull(16) ?: return null
        // 6 位按不透明处理
        return if (s.length == 6) (0xFF000000 or value).toInt() else value.toInt()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        const val EXTRA_WIDGET_ID = "widget_id"

        /** 12 个预设色：4 个中性 + 8 个彩色，足够覆盖深/浅卡片。 */
        private val PALETTE = intArrayOf(
        0x00000000,      // 透明
        0x80FFFFFF.toInt(),
            Color.WHITE,
            0xFFE6E6EC.toInt(),
            0xFF9C9CA8.toInt(),
            0xFF23232A.toInt(),
            0xFF7C8CF8.toInt(),
            0xFF4CAF50.toInt(),
            0xFF26C6DA.toInt(),
            0xFFFFC107.toInt(),
            0xFFFF7043.toInt(),
            0xFFF06292.toInt(),
            0xFF000000.toInt(),
            0xFFEF5350.toInt()
        )
    }
}
