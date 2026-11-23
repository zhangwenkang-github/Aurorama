package com.zhangwenkang.cinefin.setup.presentation.addresses

/** 地址页的用户可见结果：屏幕据此弹 Snackbar（与设置 / 登录页同一事件通道模式）。 */
sealed interface ServerAddressesEvent {
    /** 删除的是当前正在使用的地址：数据库层拒绝且未做任何改动。 */
    data object CannotDeleteCurrentAddress : ServerAddressesEvent
}
