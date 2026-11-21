package com.zhangwenkang.cinefin.book.presentation.reader

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * W6 T4：音量键 / 键盘翻页的键位映射纯规则。
 *
 * 覆盖：音量键开关关 = 放行、开 = 拦截且滚动档映射滚动、方向键 / PgUp / PgDn / Enter 映射、非法键穿透、方向锁定偏好容错。
 */
class ReaderKeyboardTest {

    private fun action(
        keyCode: Int,
        volumeKeysPaging: Boolean = true,
        scrollMode: Boolean = false,
    ): ReaderKeyAction? =
        readerKeyAction(
            keyCode = keyCode,
            volumeKeysPaging = volumeKeysPaging,
            scrollMode = scrollMode,
        )

    private fun dispatchAction(
        keyCode: Int,
        document: ReaderKeyDocument = ReaderKeyDocument.Epub,
        volumeKeysPaging: Boolean = true,
        scrollMode: Boolean = false,
    ): ReaderKeyAction? =
        readerKeyActionBeforeDispatch(
            document = document,
            keyCode = keyCode,
            volumeKeysPaging = volumeKeysPaging,
            scrollMode = scrollMode,
        )

    @Test
    fun `音量键开关关闭时放行系统音量`() {
        assertNull(action(KeyEvent.KEYCODE_VOLUME_UP, volumeKeysPaging = false))
        assertNull(action(KeyEvent.KEYCODE_VOLUME_DOWN, volumeKeysPaging = false))
    }

    @Test
    fun `音量键开关打开时分页档上下翻页`() {
        assertEquals(ReaderKeyAction.PreviousPage, action(KeyEvent.KEYCODE_VOLUME_UP))
        assertEquals(ReaderKeyAction.NextPage, action(KeyEvent.KEYCODE_VOLUME_DOWN))
    }

    @Test
    fun `音量键开关打开时滚动档上下滚动`() {
        assertEquals(
            ReaderKeyAction.ScrollUp,
            action(KeyEvent.KEYCODE_VOLUME_UP, scrollMode = true),
        )
        assertEquals(
            ReaderKeyAction.ScrollDown,
            action(KeyEvent.KEYCODE_VOLUME_DOWN, scrollMode = true),
        )
    }

    @Test
    fun `方向键与翻页键分页档映射上下页`() {
        assertEquals(ReaderKeyAction.PreviousPage, action(KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals(ReaderKeyAction.PreviousPage, action(KeyEvent.KEYCODE_DPAD_UP))
        assertEquals(ReaderKeyAction.PreviousPage, action(KeyEvent.KEYCODE_PAGE_UP))
        assertEquals(ReaderKeyAction.NextPage, action(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals(ReaderKeyAction.NextPage, action(KeyEvent.KEYCODE_DPAD_DOWN))
        assertEquals(ReaderKeyAction.NextPage, action(KeyEvent.KEYCODE_PAGE_DOWN))
    }

    @Test
    fun `方向键与翻页键滚动档映射滚动`() {
        assertEquals(
            ReaderKeyAction.ScrollUp,
            action(KeyEvent.KEYCODE_DPAD_LEFT, scrollMode = true),
        )
        assertEquals(
            ReaderKeyAction.ScrollUp,
            action(KeyEvent.KEYCODE_PAGE_UP, scrollMode = true),
        )
        assertEquals(
            ReaderKeyAction.ScrollDown,
            action(KeyEvent.KEYCODE_DPAD_RIGHT, scrollMode = true),
        )
        assertEquals(
            ReaderKeyAction.ScrollDown,
            action(KeyEvent.KEYCODE_PAGE_DOWN, scrollMode = true),
        )
    }

    @Test
    fun `Enter 与中键唤出控制层且不受音量开关影响`() {
        assertEquals(ReaderKeyAction.ToggleControls, action(KeyEvent.KEYCODE_ENTER))
        assertEquals(ReaderKeyAction.ToggleControls, action(KeyEvent.KEYCODE_NUMPAD_ENTER))
        assertEquals(ReaderKeyAction.ToggleControls, action(KeyEvent.KEYCODE_DPAD_CENTER))
        assertEquals(
            ReaderKeyAction.ToggleControls,
            action(KeyEvent.KEYCODE_ENTER, volumeKeysPaging = false, scrollMode = true),
        )
    }

    @Test
    fun `非法键与返回键穿透不消费`() {
        assertNull(action(KeyEvent.KEYCODE_BACK))
        assertNull(action(KeyEvent.KEYCODE_A))
        assertNull(action(KeyEvent.KEYCODE_MEDIA_NEXT))
        assertNull(action(KeyEvent.KEYCODE_SPACE))
    }

    @Test
    fun `EPUB 在 view 分发前拦截方向键 Enter 与翻页键`() {
        assertEquals(ReaderKeyAction.PreviousPage, dispatchAction(KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals(ReaderKeyAction.PreviousPage, dispatchAction(KeyEvent.KEYCODE_DPAD_UP))
        assertEquals(ReaderKeyAction.NextPage, dispatchAction(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals(ReaderKeyAction.NextPage, dispatchAction(KeyEvent.KEYCODE_DPAD_DOWN))
        assertEquals(ReaderKeyAction.PreviousPage, dispatchAction(KeyEvent.KEYCODE_PAGE_UP))
        assertEquals(ReaderKeyAction.NextPage, dispatchAction(KeyEvent.KEYCODE_PAGE_DOWN))
        assertEquals(ReaderKeyAction.ToggleControls, dispatchAction(KeyEvent.KEYCODE_ENTER))
        assertEquals(ReaderKeyAction.ToggleControls, dispatchAction(KeyEvent.KEYCODE_NUMPAD_ENTER))
        assertEquals(ReaderKeyAction.ToggleControls, dispatchAction(KeyEvent.KEYCODE_DPAD_CENTER))
    }

    /** T4r 回归：分发前拦截绝不能与 `onKeyDown` 映射分叉，否则两条路径会给出不同动作。 */
    @Test
    fun `EPUB 分发前判定与 onKeyDown 映射逐键一致`() {
        val keys =
            listOf(
                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_PAGE_UP,
                KeyEvent.KEYCODE_PAGE_DOWN,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER,
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_VOLUME_UP,
                KeyEvent.KEYCODE_VOLUME_DOWN,
                KeyEvent.KEYCODE_SPACE,
            )
        for (scrollMode in listOf(false, true)) {
            for (keyCode in keys) {
                assertEquals(
                    "keyCode=$keyCode scrollMode=$scrollMode",
                    action(keyCode, scrollMode = scrollMode),
                    dispatchAction(keyCode, scrollMode = scrollMode),
                )
            }
        }
    }

    @Test
    fun `EPUB 分发前音量键开关关闭时放行系统音量`() {
        assertNull(dispatchAction(KeyEvent.KEYCODE_VOLUME_UP, volumeKeysPaging = false))
        assertNull(dispatchAction(KeyEvent.KEYCODE_VOLUME_DOWN, volumeKeysPaging = false))
        assertEquals(
            ReaderKeyAction.NextPage,
            dispatchAction(KeyEvent.KEYCODE_VOLUME_DOWN, volumeKeysPaging = true),
        )
    }

    @Test
    fun `EPUB 分发前滚动档映射滚动而非翻页`() {
        assertEquals(
            ReaderKeyAction.ScrollUp,
            dispatchAction(KeyEvent.KEYCODE_DPAD_UP, scrollMode = true),
        )
        assertEquals(
            ReaderKeyAction.ScrollDown,
            dispatchAction(KeyEvent.KEYCODE_PAGE_DOWN, scrollMode = true),
        )
    }

    @Test
    fun `PDF CBZ 不在 view 分发前拦截以保留 Compose 先收键顺序`() {
        assertNull(dispatchAction(KeyEvent.KEYCODE_DPAD_RIGHT, ReaderKeyDocument.Compose))
        assertNull(dispatchAction(KeyEvent.KEYCODE_PAGE_DOWN, ReaderKeyDocument.Compose))
        assertNull(dispatchAction(KeyEvent.KEYCODE_ENTER, ReaderKeyDocument.Compose))
        assertNull(
            dispatchAction(
                KeyEvent.KEYCODE_VOLUME_DOWN,
                ReaderKeyDocument.Compose,
                volumeKeysPaging = true,
            )
        )
        // 兜底路径（onKeyDown）映射不变，PDF / CBZ 行为与改动前一致。
        assertEquals(ReaderKeyAction.NextPage, action(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals(ReaderKeyAction.ToggleControls, action(KeyEvent.KEYCODE_ENTER))
    }

    @Test
    fun `方向锁定偏好容错回落到跟随系统`() {
        assertEquals(ReaderOrientation.FollowSystem, ReaderOrientation.fromStorage(null))
        assertEquals(ReaderOrientation.FollowSystem, ReaderOrientation.fromStorage("unknown"))
        assertEquals(ReaderOrientation.Portrait, ReaderOrientation.fromStorage("portrait"))
        assertEquals(ReaderOrientation.Landscape, ReaderOrientation.fromStorage("landscape"))
        assertEquals("pref_reader_orientation", ReaderInputPreferences.orientation.backendName)
        assertEquals(false, ReaderInputPreferences.volumeKeysPaging.defaultValue)
    }
}
