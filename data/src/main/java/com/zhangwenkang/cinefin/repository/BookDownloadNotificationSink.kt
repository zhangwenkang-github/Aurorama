package com.zhangwenkang.cinefin.repository

/**
 * W9-T7（F5）：书籍下载链的完成 / 失败通知出口。
 *
 * 书籍不走自研下载引擎，真实体在 data 模块；通知实现（`DownloadNotifications`）在 core 且 data 不反向依赖 core， 所以这里只声明事件，由 core
 * 侧实现复用既有下载通知（同一渠道、图标与文案，完成通知点击直达阅读器）。
 *
 * 只有整本下载真正跑完的一次尝试才回调：[onCompleted]（成功）、[onFailed]（传输失败 / 来源记录写入失败）。 用户暂停 / 取消 / 删除收尾 /
 * 重启恢复对已完成文件的补记都不回调，避免主动操作与冷启动刷出通知。
 */
interface BookDownloadNotificationSink {

    fun onCompleted(task: BookDownloadTask)

    fun onFailed(task: BookDownloadTask)

    /** 缺省实现：不发通知（单测与未接线宿主）。 */
    data object None : BookDownloadNotificationSink {
        override fun onCompleted(task: BookDownloadTask) = Unit

        override fun onFailed(task: BookDownloadTask) = Unit
    }
}
