package com.zhangwenkang.cinefin.film.presentation.detail

/** 详情内容加载的单调所有权；只有最后一次 RESUMED / 用户触发的请求可以发布。 */
internal class LatestContentRequest {
    private var issued = 0L

    fun begin(): Long {
        issued += 1L
        return issued
    }

    fun isCurrent(request: Long): Boolean = request == issued
}
