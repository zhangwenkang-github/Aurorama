package com.zhangwenkang.cinefin.core.selection

/**
 * 通用多选状态（W58：音乐 / 视频 / 书籍共用）。
 *
 * 只保存「选中了哪些 id」与「是否处于多选模式」；条目来源（当前视图**已加载**的列表） 由调用方在每次操作时传入，选择语义全部走本文件的纯函数—— 不持有列表副本，列表刷新 / 分页追加后按
 * id 求交集即可，不引入额外状态。
 *
 * 约定：**非多选模式下选中集合恒为空**（[clear] / 空列表全选会退出多选模式）， 这样「已选 N 项」不会在普通浏览态残留。
 */
data class MultiSelectState(
    val selectionMode: Boolean = false,
    val selectedIds: Set<String> = emptySet(),
) {

    val selectedCount: Int
        get() = selectedIds.size

    val hasSelection: Boolean
        get() = selectedIds.isNotEmpty()

    fun isSelected(id: String): Boolean = id in selectedIds

    /** 长按进入多选：把触发条目加入选中集合（已进入时等价于切换）。 */
    fun longPress(id: String): MultiSelectState =
        copy(selectionMode = true, selectedIds = selectedIds + id)

    /** 多选模式下单击：切换单个条目的选中态；全不选时退出多选模式。 */
    fun toggle(id: String): MultiSelectState {
        if (!selectionMode) return this
        val updated = if (id in selectedIds) selectedIds - id else selectedIds + id
        return copy(selectionMode = updated.isNotEmpty(), selectedIds = updated)
    }

    /** 全选当前视图**已加载**的条目（分页列表只选已加载部分，不触发拉全库）。 */
    fun selectAll(loadedIds: List<String>): MultiSelectState {
        val updated = selectedIds + loadedIds
        return copy(selectionMode = updated.isNotEmpty(), selectedIds = updated)
    }

    /** 取消全选：清空选中但保留多选模式（由 [toggleSelectionMode] / [clear] 退出）。 */
    fun selectNone(loadedIds: List<String>): MultiSelectState =
        copy(selectedIds = selectedIds - loadedIds.toSet())

    /** 列表刷新 / 分页后丢弃已不存在的选择。 */
    fun retain(validIds: Set<String>): MultiSelectState {
        val updated = selectedIds.intersect(validIds)
        return if (updated == selectedIds) this
        else copy(selectionMode = selectionMode && updated.isNotEmpty(), selectedIds = updated)
    }

    /** 退出多选（清空选择）。 */
    fun clear(): MultiSelectState = MultiSelectState()

    /** 进入 / 退出多选；退出时清空选择。 */
    fun toggleSelectionMode(): MultiSelectState =
        if (selectionMode) clear() else copy(selectionMode = true)
}
