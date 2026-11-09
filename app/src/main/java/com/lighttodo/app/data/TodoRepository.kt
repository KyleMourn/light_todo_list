package com.lighttodo.app.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 待办数据的唯一入口。数据按「卡片」隔离：一个桌面小组件实例 = 一个列表，
 * 列表 id 就是 appWidgetId，存储键为 `list_<id>`。
 *
 * 约定：所有写操作都走这里，写完由调用方刷新对应小组件；
 * 界面层不允许直接读写持久化。
 */
class TodoRepository private constructor(context: Context) {

    private val appContext: Context = context.applicationContext

    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val cache = HashMap<Int, MutableStateFlow<List<TodoItem>>>()

    private fun holder(listId: Int): MutableStateFlow<List<TodoItem>> = synchronized(cache) {
        cache.getOrPut(listId) { MutableStateFlow(readAll(listId)) }
    }

    fun flow(listId: Int): StateFlow<List<TodoItem>> = holder(listId).asStateFlow()

    /** 小组件渲染用：同步拿当前快照，不订阅 Flow。 */
    fun snapshot(listId: Int): List<TodoItem> = holder(listId).value

    suspend fun add(listId: Int, title: String, column: Int = 0): Unit = withContext(Dispatchers.IO) {
        val text = title.trim()
        if (text.isEmpty()) {
            return@withContext
        }
        val current = holder(listId).value
        val now = System.currentTimeMillis()
        val next = TodoItem(
            id = (current.maxOfOrNull { it.id } ?: 0L) + 1L,
            title = text,
            done = false,
            createdAt = now,
            updatedAt = now,
            column = column.coerceIn(0, 1)
        )
        commit(listId, current + next)
    }

    suspend fun setDone(listId: Int, id: Long, done: Boolean): Unit = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        commit(
            listId,
            holder(listId).value.map {
                if (it.id == id) {
                    it.copy(done = done, updatedAt = now, movedAt = now, order = 0L)
                } else {
                    it
                }
            }
        )
    }

    suspend fun toggle(listId: Int, id: Long): Unit = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        commit(
            listId,
            holder(listId).value.map {
                if (it.id == id) {
                    it.copy(done = !it.done, updatedAt = now, movedAt = now, order = 0L)
                } else {
                    it
                }
            }
        )
    }

    suspend fun setPinned(listId: Int, id: Long, pinned: Boolean): Unit =
        withContext(Dispatchers.IO) {
            commit(
                listId,
                holder(listId).value.map { if (it.id == id) it.copy(pinned = pinned) else it }
            )
        }

    /**
     * 拖动排序：按界面上的最终顺序重写 order（1、2、3… 乘 1000，留出插入余量）。
     * [unpinId] 是本次被拖动的那条——拖动置顶项时顺手取消置顶，避免它又被排回顶部。
     */
    suspend fun applyOrder(
        listId: Int,
        orderedIds: List<Long>,
        unpinId: Long? = null
    ): Unit = withContext(Dispatchers.IO) {
        if (orderedIds.isEmpty()) {
            return@withContext
        }
        val positions = orderedIds.withIndex()
            .associate { (index, id) -> id to (index + 1).toLong() * ORDER_STEP }
        commit(
            listId,
            holder(listId).value.map { item ->
                val position = positions[item.id]
                when {
                    position == null -> item
                    item.id == unpinId -> item.copy(order = position, pinned = false)
                    else -> item.copy(order = position)
                }
            }
        )
    }

    suspend fun rename(listId: Int, id: Long, title: String): Unit = withContext(Dispatchers.IO) {
        val text = title.trim()
        if (text.isEmpty()) {
            return@withContext
        }
        val now = System.currentTimeMillis()
        commit(
            listId,
            holder(listId).value.map {
                if (it.id == id) it.copy(title = text, updatedAt = now) else it
            }
        )
    }

    suspend fun delete(listId: Int, id: Long): Unit = withContext(Dispatchers.IO) {
        commit(listId, holder(listId).value.filterNot { it.id == id })
    }

    /** 只负责落盘与通知；展示顺序由 sortForDisplay(...) 按卡片的排序配置决定。 */
    private fun commit(listId: Int, list: List<TodoItem>) {
        holder(listId).value = list
        prefs.edit().putString(keyOf(listId), encode(list)).apply()
    }

    /**
     * 旧版本只有一个全局列表（键 items_json）。第一次拿到实例 id 时把它搬进该实例，
     * 搬完删掉旧键，避免多个实例重复读到同一份数据。
     */
    fun migrateLegacyIfNeeded(firstListId: Int) {
        if (firstListId <= 0) {
            return
        }
        synchronized(this) {
            if (prefs.getBoolean(KEY_MIGRATED, false)) {
                return
            }
            val legacy = prefs.getString(KEY_LEGACY_ITEMS, null)
            if (!legacy.isNullOrBlank() && prefs.getString(keyOf(firstListId), null) == null) {
                prefs.edit()
                    .putString(keyOf(firstListId), legacy)
                    .remove(KEY_LEGACY_ITEMS)
                    .putBoolean(KEY_MIGRATED, true)
                    .apply()
                synchronized(cache) { cache.remove(firstListId) }
            } else {
                prefs.edit()
                    .remove(KEY_LEGACY_ITEMS)
                    .putBoolean(KEY_MIGRATED, true)
                    .apply()
            }
        }
    }

    private fun keyOf(listId: Int): String = "list_$listId"

    private fun readAll(listId: Int): List<TodoItem> =
        decode(prefs.getString(keyOf(listId), null))

    private fun encode(list: List<TodoItem>): String {
        val array = JSONArray()
        list.forEach { item ->
            array.put(
                JSONObject()
                    .put("id", item.id)
                    .put("title", item.title)
                    .put("done", item.done)
                    .put("createdAt", item.createdAt)
                    .put("updatedAt", item.updatedAt)
                    .put("pinned", item.pinned)
                    .put("movedAt", item.movedAt)
                    .put("order", item.order)
                    .put("column", item.column)
            )
        }
        return array.toString()
    }

    private fun decode(raw: String?): List<TodoItem> {
        if (raw.isNullOrBlank()) {
            return emptyList()
        }
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { index ->
                val obj = array.getJSONObject(index)
                val created = obj.optLong("createdAt")
                TodoItem(
                    id = obj.optLong("id"),
                    title = obj.optString("title"),
                    done = obj.optBoolean("done"),
                    createdAt = created,
                    updatedAt = obj.optLong("updatedAt", created),
                    pinned = obj.optBoolean("pinned", false),
                    movedAt = obj.optLong("movedAt", 0L),
                    order = obj.optLong("order", 0L),
                    column = obj.optInt("column", 0).coerceIn(0, 1)
                )
            }.filter { it.title.isNotBlank() }
        }.getOrDefault(emptyList())
    }

    companion object {
        private const val PREFS_NAME = "light_todo"
        private const val KEY_LEGACY_ITEMS = "items_json"
        private const val KEY_MIGRATED = "migrated_v2"

        /** 手动排序的步长：写 order 时留出空档，以后要在两条之间插队也有位置可放。 */
        private const val ORDER_STEP = 1000L

        @Volatile
        private var instance: TodoRepository? = null

        fun get(context: Context): TodoRepository =
            instance ?: synchronized(this) {
                instance ?: TodoRepository(context).also { instance = it }
            }
    }
}
