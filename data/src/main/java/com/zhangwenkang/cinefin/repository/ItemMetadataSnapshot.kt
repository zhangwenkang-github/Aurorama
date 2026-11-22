package com.zhangwenkang.cinefin.repository

import java.util.UUID

/**
 * W8-T6：条目元数据编辑的当前值快照（名称 / 简介 / 排序名称）。
 *
 * 编辑对话框必须先拿到服务器上的完整值再提交：Jellyfin 的 `POST /Items/{id}` 按提交的 DTO 覆盖更新， 只发差异字段会把未提交的字段清空。
 */
data class ItemMetadataSnapshot(
    val itemId: UUID,
    val name: String,
    val overview: String?,
    val sortName: String?,
)
