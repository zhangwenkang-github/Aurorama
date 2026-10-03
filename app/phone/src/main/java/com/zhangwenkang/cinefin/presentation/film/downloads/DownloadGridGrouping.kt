package com.zhangwenkang.cinefin.presentation.film.downloads

import com.zhangwenkang.cinefin.film.presentation.downloads.DownloadHierarchyRow

/** W52：平板两列布局的行块（纯函数，单测覆盖）。 */
sealed interface DownloadGridBlock {
    val key: String

    /** 相邻两个「已折叠顶层容器」并排一行。 */
    data class Pair(
        override val key: String,
        val first: DownloadHierarchyRow.ContainerRow,
        val second: DownloadHierarchyRow.ContainerRow,
    ) : DownloadGridBlock

    /** 其余行独占一整行（展开容器连同子项保持整宽从属关系）。 */
    data class Single(
        override val key: String,
        val row: DownloadHierarchyRow,
    ) : DownloadGridBlock
}

/**
 * W52 平板两列成行规则：只把**相邻且都已折叠**的顶层容器两两成行。
 *
 * 展开的容器不会与别人并排——它的子项必须整宽跟在自己下面，否则层级从属关系会被两列布局打断。
 */
object DownloadGridGrouping {

    fun group(rows: List<DownloadHierarchyRow>): List<DownloadGridBlock> {
        val blocks = mutableListOf<DownloadGridBlock>()
        var index = 0
        while (index < rows.size) {
            val row = rows[index]
            val next = rows.getOrNull(index + 1)
            if (
                row is DownloadHierarchyRow.ContainerRow &&
                    row.collapsed &&
                    next is DownloadHierarchyRow.ContainerRow &&
                    next.collapsed
            ) {
                blocks +=
                    DownloadGridBlock.Pair(
                        key = "pair:${row.key}|${next.key}",
                        first = row,
                        second = next,
                    )
                index += 2
            } else {
                blocks += DownloadGridBlock.Single(row.key, row)
                index += 1
            }
        }
        return blocks
    }
}
