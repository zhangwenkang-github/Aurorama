package com.zhangwenkang.cinefin.settings.presentation.models

import androidx.annotation.StringRes
import com.zhangwenkang.cinefin.settings.domain.models.HomeLibrarySettings
import com.zhangwenkang.cinefin.settings.domain.models.Preference as PreferenceBackend
import com.zhangwenkang.cinefin.settings.presentation.enums.DeviceType

/**
 * 首页逐库设置行（W54-D）：一行 = 库名 +「在首页显示」开关 +「默认分页」下拉。
 *
 * 值由 `SettingsViewModel#loadPreferences` 从偏好里填充；更新走 `SettingsAction.OnUpdate`， ViewModel 按「期望值 vs
 * 落盘值」的差异决定写哪个键（见 `SettingsViewModel#onAction`）。
 */
data class PreferenceHomeLibrary(
    @param:StringRes override val nameStringResource: Int,
    @param:StringRes override val descriptionStringRes: Int? = null,
    override val iconDrawableId: Int? = null,
    override val enabled: Boolean = true,
    override val dependencies: List<PreferenceBackend<Boolean>> = emptyList(),
    override val supportedDeviceTypes: List<DeviceType> = listOf(DeviceType.PHONE),
    val libraryId: String,
    val libraryName: String,
    val libraryType: String,
    /** 当前「在首页显示」状态（默认 true）。 */
    val visible: Boolean = true,
    /** 当前「默认分页」key（取值见 [HomeLibrarySettings.pageKeys]）。 */
    val pageKey: String = HomeLibrarySettings.DEFAULT_PAGE_KEY,
) : Preference

/** 媒体库顺序行（W54-D）：点开对话框上下调整。 */
data class PreferenceHomeLibraryOrder(
    @param:StringRes override val nameStringResource: Int,
    @param:StringRes override val descriptionStringRes: Int? = null,
    override val iconDrawableId: Int? = null,
    override val enabled: Boolean = true,
    override val dependencies: List<PreferenceBackend<Boolean>> = emptyList(),
    override val supportedDeviceTypes: List<DeviceType> = listOf(DeviceType.PHONE),
    /** 当前显示顺序（id + 名称）。 */
    val libraries: List<HomeLibraryOrderEntry> = emptyList(),
) : Preference

data class HomeLibraryOrderEntry(val id: String, val name: String)
