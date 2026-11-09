package com.lighttodo.app

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import com.lighttodo.app.data.TodoRepository
import com.lighttodo.app.widget.DualWidgetReceiver
import com.lighttodo.app.widget.TodoWidgetReceiver

/**
 * 首页：每张桌面卡片（appWidgetId）是一个独立清单，这里只做简要展示，
 * 点进去才进入该卡片的待办编辑与外观设置。
 */
class MainActivity : ComponentActivity() {

    private lateinit var cards: LinearLayout
    private lateinit var empty: TextView
    private lateinit var hint: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        cards = findViewById(R.id.cards)
        empty = findViewById(R.id.empty)
        hint = findViewById(R.id.hint)
        // 打开 App 时把所有卡片重画一遍：OEM 桌面有时会缓存旧 RemoteViews
        com.lighttodo.app.widget.WidgetUpdater.updateAll(this)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /** 兼容从小组件直接跳来的旧入口：带 listId 就进详情页。 */
    private fun handleIntent(intent: Intent?) {
        val safe = intent ?: return
        val listId = safe.getIntExtra(CardDetailActivity.EXTRA_LIST_ID, 0)
        if (listId > 0) {
            val focus = safe.getBooleanExtra(CardDetailActivity.EXTRA_FOCUS_ADD, false)
            safe.removeExtra(CardDetailActivity.EXTRA_LIST_ID)
            startActivity(
                Intent(this, CardDetailActivity::class.java)
                    .putExtra(CardDetailActivity.EXTRA_LIST_ID, listId)
                    .putExtra(CardDetailActivity.EXTRA_FOCUS_ADD, focus)
            )
        }
    }

    private fun listIds(): IntArray {
        val manager = AppWidgetManager.getInstance(this)
        // 经典与双栏是两个 Provider，首页要把两边的实例都列出来，否则双栏卡片在 App 里看不到
        val classic = manager.getAppWidgetIds(ComponentName(this, TodoWidgetReceiver::class.java))
        val dual = manager.getAppWidgetIds(ComponentName(this, DualWidgetReceiver::class.java))
        return (classic.toList() + dual.toList()).distinct().sorted().toIntArray()
    }

    private fun render() {
        val repo = TodoRepository.get(this)
        val ids = listIds()
        if (ids.isNotEmpty()) {
            repo.migrateLegacyIfNeeded(ids.first())
        }
        cards.removeAllViews()
        empty.visibility = if (ids.isEmpty()) View.VISIBLE else View.GONE
        hint.visibility = if (ids.isEmpty()) View.VISIBLE else View.GONE
        ids.forEach { id -> cards.addView(buildCard(repo, id)) }
    }

    private fun buildCard(repo: TodoRepository, listId: Int): View {
        val items = repo.snapshot(listId)
        val undone = items.count { !it.done }
        val config = com.lighttodo.app.widget.WidgetConfigStore.load(this, listId)

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_card)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
            isClickable = true
            setOnClickListener {
                startActivity(
                    Intent(this@MainActivity, CardDetailActivity::class.java)
                        .putExtra(CardDetailActivity.EXTRA_LIST_ID, listId)
                )
            }
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(
            TextView(this).apply {
                val custom = config.cardName
                text = custom.ifBlank { getString(R.string.card_title, listId) }
                textSize = 17f
                setTextColor(getColor(R.color.text_primary))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
        )
        // 类型标签：一眼区分经典 / 双栏
        header.addView(
            TextView(this).apply {
                text = if (config.dual) getString(R.string.type_dual) else getString(R.string.type_classic)
                textSize = 10f
                setTextColor(getColor(R.color.brand))
                setBackgroundResource(R.drawable.bg_type_chip)
                setPadding(dp(7), dp(2), dp(7), dp(2))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(8) }
            }
        )
        header.addView(
            TextView(this).apply {
                text = if (items.isEmpty()) {
                    getString(R.string.card_summary_empty)
                } else {
                    getString(R.string.card_summary, items.size, undone)
                }
                textSize = 13f
                setTextColor(getColor(R.color.text_secondary))
            }
        )
        card.addView(header)

        val preview = items.filter { !it.done }.take(2)
        if (preview.isNotEmpty()) {
            preview.forEach { item ->
                card.addView(
                    TextView(this).apply {
                        text = "· ${item.title}"
                        textSize = 14f
                        maxLines = 1
                        setTextColor(getColor(R.color.text_secondary))
                        setPadding(0, dp(6), 0, 0)
                    }
                )
            }
        }
        return card
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
}
