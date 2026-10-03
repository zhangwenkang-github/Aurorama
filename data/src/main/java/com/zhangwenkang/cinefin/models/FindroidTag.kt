package com.zhangwenkang.cinefin.models

import java.util.UUID

/**
 * 库内分类项（W54-B）：类型（Genre）/ 制片发行商（Studio）。
 *
 * 只带 `id` 与名称——库内容页的分类 tab 用它渲染分类网格；点选后按**名称**回落到库内容页的 「库内过滤」链路（Jellyfin `/Items` 的 `genres` /
 * `studios` 参数按名称过滤，见 `LibraryViewModel`）， 不需要图片 / 用户数据。
 */
data class FindroidTag(val id: UUID, val name: String)
