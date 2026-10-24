package com.zhangwenkang.cinefin.presentation.update

import com.zhangwenkang.cinefin.settings.R as SettingsR
import com.zhangwenkang.cinefin.settings.presentation.enums.DeviceType
import com.zhangwenkang.cinefin.settings.presentation.settings.updateSettingsCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateSettingsEntryTest {

    @Test
    fun settingsCategoryExposesChineseUpdateCopyAndOpensTheUpdatePage() {
        var opened = false

        val category = updateSettingsCategory { opened = true }

        assertEquals(SettingsR.string.settings_update, category.nameStringResource)
        assertEquals(SettingsR.string.settings_update_summary, category.descriptionStringRes)
        category.onClick(category)

        assertTrue(opened)
        assertTrue(DeviceType.PHONE in category.supportedDeviceTypes)
        assertEquals(UpdateRoute, updateDestination())
    }
}
