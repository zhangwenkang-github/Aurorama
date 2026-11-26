package com.zhangwenkang.cinefin.presentation.utils

import androidx.compose.runtime.Composable
import com.zhangwenkang.cinefin.core.presentation.utils.SafePadding
import com.zhangwenkang.cinefin.core.presentation.utils.rememberSafePadding as coreRememberSafePadding

/**
 * 2.0.2 D1：实现已下沉 `core`（`core.presentation.utils.rememberSafePadding`），此处保留同名委托， app 侧 23 个文件 / 28
 * 处调用零改动；新代码请直接引用 core 版本。签名与行为与下沉前完全一致。
 */
@Composable
fun rememberSafePadding(handleStartInsets: Boolean = true): SafePadding =
    coreRememberSafePadding(handleStartInsets)
