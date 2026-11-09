package com.lighttodo.app.data

/**
 * 一条待办。
 *
 * updatedAt：改文字时刷新，用于「按最后修改时间」排序。
 * movedAt：勾选/取消完成时刷新，保证刚操作过的条目落在所在分组的顶部。
 * pinned：置顶，排序时永远排在未置顶项之前。
 * order：手动拖动后的位置（1、2、3…）。0 表示没手动排过，
 *        这类条目永远排在所在分组最前面，所以新加的和刚勾选的会出现在顶部。
 * column：双栏卡片里的归属，0 = 左栏，1 = 右栏；经典卡片永远是 0。
 */
data class TodoItem(
    val id: Long,
    val title: String,
    val done: Boolean,
    val createdAt: Long,
    val updatedAt: Long = createdAt,
    val pinned: Boolean = false,
    val movedAt: Long = 0L,
    val order: Long = 0L,
    val column: Int = 0
)

/**
 * 展示顺序：置顶分组优先 → 已完成是否沉底（sinkDone）→ 手动位置 →
 * 最近勾选/取消的排最前 → 按最后修改时间（newestFirst 决定方向）→ id 兜底。
 *
 * 关掉 sinkDone 后，「已完成」不再单独成组，完全按手动位置排列，
 * 勾选只改变文字样式，不会把条目挪到末尾。
 */
fun sortForDisplay(
    items: List<TodoItem>,
    newestFirst: Boolean,
    sinkDone: Boolean = true
): List<TodoItem> {
    val timeOrder: Comparator<TodoItem> =
        if (newestFirst) compareByDescending { it.updatedAt } else compareBy { it.updatedAt }
    return items.sortedWith(
        compareBy<TodoItem> { !it.pinned }
            .thenComparator { a, b -> if (sinkDone) a.done.compareTo(b.done) else 0 }
            .thenComparator { a, b ->
                when {
                    a.order == 0L && b.order == 0L -> 0
                    a.order == 0L -> -1
                    b.order == 0L -> 1
                    else -> a.order.compareTo(b.order)
                }
            }
            .thenByDescending { it.movedAt }
            .then(timeOrder)
            .thenByDescending { it.id }
    )
}
