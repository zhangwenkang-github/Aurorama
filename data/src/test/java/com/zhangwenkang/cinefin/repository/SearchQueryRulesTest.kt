package com.zhangwenkang.cinefin.repository

import org.jellyfin.sdk.model.api.BaseItemKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** W82 #25：服务器搜索类型单一来源单测。 */
class SearchQueryRulesTest {

    @Test
    fun `搜索类型覆盖影视音乐书籍且保留原有影视类型`() {
        val types = SearchQueryRules.includeItemTypes
        assertTrue(types.containsAll(listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES)))
        assertTrue(types.containsAll(listOf(BaseItemKind.AUDIO, BaseItemKind.BOOK)))
        assertEquals(types.size, types.distinct().size)
    }
}
