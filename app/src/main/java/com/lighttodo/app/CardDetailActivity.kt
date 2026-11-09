package com.lighttodo.app

import android.app.AlertDialog
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Paint
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lighttodo.app.data.TodoItem
import com.lighttodo.app.data.TodoRepository
import com.lighttodo.app.data.sortForDisplay
import com.lighttodo.app.widget.TodoWidgetReceiver
import com.lighttodo.app.widget.WidgetConfig
import com.lighttodo.app.widget.WidgetConfigStore
import com.lighttodo.app.widget.WidgetUpdater
import kotlinx.coroutines.launch
import android.util.Log

/**
 * 单张卡片的详情页：这个清单的待办 + 这张卡片的外观入口。
 *
 * 卡片名称 = 小组件顶部标题，长按标题就能改（外观页里不再重复放一份）。
 * 所有写操作完成后都立即刷新对应小组件，不需要用户再点「保存」。
 *
 * 列表用 RecyclerView：长按任意一条即可上下拖动排序，松手后把新顺序写回数据
 * （拖动置顶项时顺手取消置顶，否则它会被排序规则排回顶部）。
 */
class CardDetailActivity : ComponentActivity() {

    private var listId: Int = 0
    private lateinit var repo: TodoRepository
    private lateinit var title: TextView
    private lateinit var input: EditText
    private lateinit var listView: RecyclerView
    private lateinit var empty: TextView
    private lateinit var adapter: TodoAdapter
    private lateinit var dragHelper: ItemTouchHelper
    private var config: WidgetConfig = WidgetConfig()
    private var lastItems: List<TodoItem> = emptyList()

    /** 正在拖动的那条待办的 id：拖动置顶项时要取消置顶。 */
    private var draggingId: Long? = null

    /** 双栏卡片：当前在编辑哪一栏（0=左，1=右）；经典卡片恒为 0。 */
    private var selectedColumn: Int = 0
    private lateinit var columnSwitch: View
    private lateinit var colLeft: TextView
    private lateinit var colRight: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_card_detail)

        listId = resolveListId(intent)
        if (listId <= 0) {
            finish()
            return
        }
        repo = TodoRepository.get(this)
        repo.migrateLegacyIfNeeded(listId)
        config = WidgetConfigStore.load(this, listId)

        title = findViewById(R.id.card_title)
        input = findViewById(R.id.input)
        listView = findViewById(R.id.list)
        empty = findViewById(R.id.empty)
        columnSwitch = findViewById(R.id.column_switch)
        colLeft = findViewById(R.id.col_left)
        colRight = findViewById(R.id.col_right)

        selectedColumn = intent.getIntExtra(EXTRA_COLUMN, 0).coerceIn(0, 1)
        setUpColumnSwitch()

        bindTitle()
        findViewById<View>(R.id.back).setOnClickListener { finish() }
        findViewById<View>(R.id.add).setOnClickListener { submit() }
        findViewById<View>(R.id.card_settings).setOnClickListener {
            startActivity(
                Intent(this, WidgetSettingsActivity::class.java)
                    .putExtra(WidgetSettingsActivity.EXTRA_WIDGET_ID, listId)
            )
        }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submit()
                true
            } else {
                false
            }
        }

        setUpList()

        lifecycleScope.launch {
            repo.flow(listId).collect {
                lastItems = it
                render(it)
            }
        }

        if (intent.getBooleanExtra(EXTRA_FOCUS_ADD, false)) {
            input.requestFocus()
        }
    }

    private fun setUpList() {
        adapter = TodoAdapter()
        listView.layoutManager = LinearLayoutManager(this)
        listView.adapter = adapter
        applyAnimationSpeed()

        val callback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN,
            0
        ) {
            // 拖拽由行自己的长按手势启动，避免和圆圈点击、文字点击互相抢事件
            override fun isLongPressDragEnabled(): Boolean = false

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = viewHolder.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) {
                    return false
                }
                adapter.moveItem(from, to)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                persistOrder()
            }
        }
        dragHelper = ItemTouchHelper(callback)
        dragHelper.attachToRecyclerView(listView)
    }

    /**
     * 完成 / 取消完成后的「下沉、上浮」动画速度。
     *
     * 速度来自这张卡片的配置（外观页的滑杆），0 表示完全不动。
     * 位移动画负责「下沉/上浮」，change 动画负责文字样式（删除线、置灰）的淡入淡出。
     */
    private fun applyAnimationSpeed() {
        val durationMs = config.animMs.toLong()
        if (durationMs <= 0L) {
            listView.itemAnimator = null
            return
        }
        val animator = listView.itemAnimator as? RowItemAnimator ?: RowItemAnimator(durationMs).also {
            listView.itemAnimator = it
            listView.itemAnimator?.addDuration = 0L
            listView.itemAnimator?.removeDuration = 0L
        }
        animator.setDurations(durationMs)
    }

    /** RecyclerView 默认动画器：把时长换成配置值，并保留 change（样式变化）动画。 */
    private class RowItemAnimator(durationMs: Long) : androidx.recyclerview.widget.DefaultItemAnimator() {
        init {
            supportsChangeAnimations = true
            setDurations(durationMs)
        }

        fun setDurations(durationMs: Long) {
            addDuration = durationMs
            removeDuration = durationMs
            moveDuration = durationMs
            changeDuration = durationMs
        }

        override fun animateMove(
            holder: RecyclerView.ViewHolder?,
            fromX: Int,
            fromY: Int,
            toX: Int,
            toY: Int
        ): Boolean {
            // 只为验证「下沉/上浮」确实在跑：打一条带时长的日志
            Log.i("LightTodoPerf", "anim move dur=${moveDuration}ms from=$fromY to=$toY")
            return super.animateMove(holder, fromX, fromY, toX, toY)
        }
    }


    /** 拖完松手：把当前顺序写进数据。拖动的是置顶项就顺手取消置顶。 */
    private fun persistOrder() {
        val ids = adapter.rows.map { it.id }
        val dragged = draggingId
        draggingId = null
        if (ids.isEmpty()) {
            return
        }
        mutate { repo.applyOrder(listId, ids, dragged) }
    }

    /** 双栏卡片才显示「左栏 / 右栏」，切换后列表只显示该栏的待办。 */
    private fun setUpColumnSwitch() {
        columnSwitch.visibility = if (config.dual) View.VISIBLE else View.GONE
        if (!config.dual) {
            selectedColumn = 0
            return
        }
        colLeft.text = config.leftTitle
        colRight.text = config.rightTitle
        colLeft.setOnClickListener { selectColumn(0) }
        colRight.setOnClickListener { selectColumn(1) }
        // RemoteViews 不支持长按，改名入口放在这里：长按栏名胶囊
        colLeft.setOnLongClickListener {
            showRenameColumnDialog(0)
            true
        }
        colRight.setOnLongClickListener {
            showRenameColumnDialog(1)
            true
        }
        paintColumnChips()
    }

    private fun selectColumn(column: Int) {
        if (selectedColumn == column) return
        selectedColumn = column
        paintColumnChips()
        render(lastItems)
    }

    private fun paintColumnChips() {
        val active = listOf(colLeft, colRight)
        active.forEachIndexed { index, view ->
            val on = index == selectedColumn
            view.setTextColor(
                if (on) getColor(R.color.brand) else getColor(R.color.text_secondary)
            )
            view.background = getDrawable(if (on) R.drawable.bg_row else R.drawable.bg_input)
        }
    }

    /** 标题由配置里的 cardName 决定；长按弹窗改名。 */
    private fun bindTitle() {
        title.text = config.cardName.ifBlank { getString(R.string.card_title, listId) }
        title.setOnLongClickListener {
            showRenameDialog()
            true
        }
    }

    private fun showRenameDialog() {
        showNameDialog(
            titleRes = R.string.rename_card_title,
            hintText = getString(R.string.hint_card_name_empty, listId),
            current = config.cardName,
            fallbackHint = getString(R.string.card_title, listId)
        ) { name ->
            config = config.copy(cardName = name)
            WidgetConfigStore.save(this, listId, config)
            bindTitle()
            WidgetUpdater.update(this, listId)
        }
    }

    /** 改某一栏的名字（0 左 / 1 右），留空回落到「左栏 / 右栏」。 */
    private fun showRenameColumnDialog(column: Int) {
        val current = if (column == 0) config.leftTitle else config.rightTitle
        val fallback = if (column == 0) WidgetConfig.DEFAULT_LEFT_TITLE else WidgetConfig.DEFAULT_RIGHT_TITLE
        showNameDialog(
            titleRes = R.string.rename_column_title,
            hintText = getString(R.string.hint_column_name_empty),
            current = current,
            fallbackHint = fallback
        ) { name ->
            val finalName = name.ifBlank { fallback }
            config = if (column == 0) config.copy(leftTitle = finalName) else config.copy(rightTitle = finalName)
            WidgetConfigStore.save(this, listId, config)
            colLeft.text = config.leftTitle
            colRight.text = config.rightTitle
            paintColumnChips()
            WidgetUpdater.update(this, listId)
        }
    }

    /** 与 App 其它页面一致的白底圆角改名弹窗，卡片名与栏名共用。 */
    private fun showNameDialog(
        titleRes: Int,
        hintText: String,
        current: String,
        fallbackHint: String,
        onSave: (String) -> Unit
    ) {
        val view = layoutInflater.inflate(R.layout.dialog_rename_card, null)
        val edit = view.findViewById<EditText>(R.id.name_input)
        view.findViewById<TextView>(R.id.name_hint).text = hintText
        view.findViewById<TextView>(R.id.dialog_title).text = getString(titleRes)
        edit.setText(current)
        edit.setSelection(edit.text.length)
        edit.hint = fallbackHint

        val dialog = android.app.Dialog(this)
        dialog.setContentView(view)
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0x00000000))

        fun save() {
            val name = edit.text.toString().trim()
            onSave(name)
            dialog.dismiss()
        }

        view.findViewById<View>(R.id.btn_cancel).setOnClickListener { dialog.dismiss() }
        view.findViewById<View>(R.id.btn_save).setOnClickListener { save() }
        edit.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                save()
                true
            } else {
                false
            }
        }

        dialog.show()
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.86f).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        edit.requestFocus()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val newId = resolveListId(intent)
        if (newId > 0 && newId != listId) {
            finish()
            startActivity(intent)
            return
        }
        // 从桌面点「新建」时，页面可能已经在栈里，回到前台后要重新聚焦输入框
        if (intent.getBooleanExtra(EXTRA_FOCUS_ADD, false)) {
            input.requestFocus()
        }
    }

    private fun resolveListId(intent: Intent?): Int {
        val explicit = intent?.getIntExtra(EXTRA_LIST_ID, 0) ?: 0
        if (explicit > 0) {
            return explicit
        }
        val ids = AppWidgetManager.getInstance(this)
            .getAppWidgetIds(ComponentName(this, TodoWidgetReceiver::class.java))
        return ids.firstOrNull() ?: 0
    }

    private fun submit() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) {
            return
        }
        input.setText("")
        mutate { repo.add(listId, text, selectedColumn) }
    }

    /** 所有写操作统一走这里：落库 → 立刻刷新这张卡片。 */
    private fun mutate(block: suspend () -> Unit) {
        lifecycleScope.launch {
            block()
            WidgetUpdater.refreshNow(this@CardDetailActivity, listId)
        }
    }

    override fun onResume() {
        super.onResume()
        // 从外观设置返回时排序/名称/动画速度可能变了，重新读一次配置
        config = WidgetConfigStore.load(this, listId)
        bindTitle()
        applyAnimationSpeed()
        if (lastItems.isNotEmpty()) render(lastItems)
    }

    private fun render(items: List<TodoItem>) {
        val newestFirst = config.sortMode == WidgetConfig.SORT_NEWEST_FIRST
        val visible = if (config.dual) {
            items.filter { it.column == selectedColumn }
        } else {
            items
        }
        adapter.submit(sortForDisplay(visible, newestFirst, config.sinkDone))
        empty.visibility = if (visible.isEmpty()) View.VISIBLE else View.GONE
    }

    // ---------- 列表 ----------

    private inner class TodoAdapter : RecyclerView.Adapter<TodoAdapter.RowHolder>() {

        val rows = mutableListOf<TodoItem>()

        fun submit(items: List<TodoItem>) {
            val old = rows.toList()
            if (old == items) return
            // 用 diff 派发更新，才能让 RecyclerView 播位移动画（整表 notifyDataSetChanged 会直接跳变）
            val diff = androidx.recyclerview.widget.DiffUtil.calculateDiff(
                object : androidx.recyclerview.widget.DiffUtil.Callback() {
                    override fun getOldListSize(): Int = old.size
                    override fun getNewListSize(): Int = items.size
                    override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean =
                        old[oldPos].id == items[newPos].id
                    override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean =
                        old[oldPos] == items[newPos]
                }
            )
            rows.clear()
            rows.addAll(items)
            diff.dispatchUpdatesTo(this)
        }

        /** 拖动过程中就地交换，只动数据与单个 item，不重建整页。 */
        fun moveItem(from: Int, to: Int) {
            if (from == to) return
            val moved = rows.removeAt(from)
            rows.add(to, moved)
            notifyItemMoved(from, to)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowHolder =
            RowHolder(buildRowView())

        override fun onBindViewHolder(holder: RowHolder, position: Int) {
            bindRow(holder, rows[position])
        }

        override fun getItemCount(): Int = rows.size

        inner class RowHolder(val row: LinearLayout) : RecyclerView.ViewHolder(row) {
            val circle: TextView = row.getChildAt(0) as TextView
            val body: TextView = row.getChildAt(1) as TextView
            val pin: ImageView = row.getChildAt(2) as ImageView
            val delete: TextView = row.getChildAt(3) as TextView
        }

        private fun buildRowView(): LinearLayout {
            val row = LinearLayout(this@CardDetailActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundResource(R.drawable.bg_row)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT
                )
            }
            row.addView(TextView(this@CardDetailActivity))
            row.addView(
                TextView(this@CardDetailActivity),
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
            row.addView(
                ImageView(this@CardDetailActivity),
                LinearLayout.LayoutParams(dp(20), dp(20)).apply { gravity = Gravity.CENTER_VERTICAL }
            )
            row.addView(TextView(this@CardDetailActivity))
            return row
        }

        private fun bindRow(holder: RowHolder, item: TodoItem) {
            holder.row.layoutParams = RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT,
                RecyclerView.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }

            // 圆圈：点一下就是完成 / 取消完成
            holder.circle.apply {
                text = if (item.done) "●" else "○"
                textSize = 24f
                setTextColor(getColor(if (item.done) R.color.text_secondary else R.color.brand))
                // 圆圈本身放大，四周补内边距把点击区做到约 44dp（含行内 10dp 上下留白）
                gravity = Gravity.CENTER
                minWidth = dp(44)
                minHeight = dp(40)
                setPadding(dp(8), 0, dp(8), 0)
                setOnClickListener { mutate { repo.toggle(listId, item.id) } }
                setOnLongClickListener { startDrag(holder) }
            }

            // 文字：点一下进编辑页改内容
            holder.body.apply {
                text = item.title
                textSize = 16f
                setTextColor(
                    getColor(if (item.done) R.color.text_secondary else R.color.text_primary)
                )
                paintFlags = if (item.done) {
                    paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
                } else {
                    paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
                }
                setPadding(0, dp(4), dp(8), dp(4))
                setOnClickListener {
                    startActivity(
                        Intent(this@CardDetailActivity, EditTodoActivity::class.java)
                            .putExtra(EditTodoActivity.EXTRA_LIST_ID, listId)
                            .putExtra(EditTodoActivity.EXTRA_TODO_ID, item.id)
                    )
                }
                setOnLongClickListener { startDrag(holder) }
            }

            // 图钉：置顶 / 取消置顶（置顶项永远排在未置顶项之前）
            holder.pin.apply {
                setImageResource(if (item.pinned) R.drawable.ic_pin_on else R.drawable.ic_pin_off)
                scaleType = ImageView.ScaleType.FIT_XY
                contentDescription = if (item.pinned) "取消置顶" else "置顶"
                setPadding(dp(6), dp(4), dp(6), dp(4))
                setOnClickListener { mutate { repo.setPinned(listId, item.id, !item.pinned) } }
                setOnLongClickListener { startDrag(holder) }
            }

            holder.delete.apply {
                text = getString(R.string.action_delete)
                textSize = 13f
                setTextColor(getColor(R.color.text_danger))
                setPadding(dp(10), dp(4), 0, dp(4))
                setOnClickListener { mutate { repo.delete(listId, item.id) } }
                setOnLongClickListener { startDrag(holder) }
            }
        }

        /** 长按任意位置开始拖动；记下这条的 id，松手时若它是置顶项就一并取消置顶。 */
        private fun startDrag(holder: RowHolder): Boolean {
            val position = holder.bindingAdapterPosition
            if (position == RecyclerView.NO_POSITION) return false
            draggingId = rows[position].id
            dragHelper.startDrag(holder)
            return true
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        const val EXTRA_LIST_ID = "list_id"
        const val EXTRA_FOCUS_ADD = "focus_add"

        /** 双栏卡片从桌面点进时带上是哪一栏。 */
        const val EXTRA_COLUMN = "detail_column"
    }
}
