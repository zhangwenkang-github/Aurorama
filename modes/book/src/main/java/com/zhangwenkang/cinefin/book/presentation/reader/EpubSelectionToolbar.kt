package com.zhangwenkang.cinefin.book.presentation.reader

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zhangwenkang.cinefin.core.presentation.components.CinefinButton
import com.zhangwenkang.cinefin.core.presentation.components.CinefinButtonSize
import com.zhangwenkang.cinefin.core.presentation.components.CinefinButtonVariant
import com.zhangwenkang.cinefin.core.presentation.components.cinefinClickable
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinShapes
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinSpacing
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinType

/** 浮动工具栏尺寸（定稿五 §2：选区上方 / 贴边翻下方；预留展开色板后的高度，展开时不重排）。 */
internal const val EPUB_TOOLBAR_WIDTH_DP: Float = 288f

/** 单行（含四色展开）预留高度。 */
internal const val EPUB_TOOLBAR_HEIGHT_DP: Float = 92f

/** 工具栏与选区的间距。 */
internal const val EPUB_TOOLBAR_GAP_DP: Float = 8f

/** 描述一次选区快照：定位走 [locatorJson]；矩形只用于把工具栏锚在选区旁（不落盘）。 */
internal data class EpubSelectionSnapshot(
    val text: String,
    val locatorJson: String,
    val chapterHref: String,
    val chapterTitle: String = "",
    val spineIndex: Int = -1,
    val progression: Double? = null,
    val cssSelector: String? = null,
    val leftPx: Float = 0f,
    val topPx: Float = 0f,
    val rightPx: Float = 0f,
    val bottomPx: Float = 0f,
)

/** 工具栏锚点（dp，相对阅读页容器左上角）。 */
internal data class EpubToolbarPlacement(val xDp: Float, val yDp: Float, val above: Boolean)

/**
 * 工具栏定位（定稿五 §2）：默认选区上方居中，上方放不下则翻到下方，左右贴边夹取。
 *
 * 纯函数（入参 / 出参都是 dp 浮点），单测覆盖「上方 / 翻下方 / 左右越界 / 容器过窄」四种情形。
 */
internal fun epubToolbarPlacement(
    selectionLeftDp: Float,
    selectionTopDp: Float,
    selectionRightDp: Float,
    selectionBottomDp: Float,
    containerWidthDp: Float,
    containerHeightDp: Float,
    toolbarWidthDp: Float = EPUB_TOOLBAR_WIDTH_DP,
    toolbarHeightDp: Float = EPUB_TOOLBAR_HEIGHT_DP,
    gapDp: Float = EPUB_TOOLBAR_GAP_DP,
): EpubToolbarPlacement {
    val maxX = (containerWidthDp - toolbarWidthDp).coerceAtLeast(0f)
    val centered = (selectionLeftDp + selectionRightDp) / 2f - toolbarWidthDp / 2f
    val x = centered.coerceIn(0f, maxX)
    val aboveY = selectionTopDp - gapDp - toolbarHeightDp
    if (aboveY >= 0f) return EpubToolbarPlacement(x, aboveY, above = true)
    val maxY = (containerHeightDp - toolbarHeightDp).coerceAtLeast(0f)
    val belowY = (selectionBottomDp + gapDp).coerceIn(0f, maxY)
    return EpubToolbarPlacement(x, belowY, above = false)
}

/** 选区 locator 的 `otherLocations` 补丁：cssSelector 为空时保持原样。 */
internal fun epubLocatorOtherLocations(
    base: Map<String, Any>,
    cssSelector: String?,
): Map<String, Any> {
    val selector = cssSelector?.trim().orEmpty()
    return if (selector.isEmpty()) base else base + ("cssSelector" to selector)
}

/** Readium locator 的 text.highlight 优先；为空时用 JS 拿到的选中原文兜底。 */
internal fun epubSelectionText(highlight: String, selected: String): String =
    highlight.trim().ifEmpty { selected.trim() }

/** 浮动工具栏动作（READER-24：高亮多色 / 想法 / 复制 / 书内搜索 / 词典）。 */
internal enum class EpubSelectionAction(val label: String) {
    Highlight("高亮"),
    Idea("想法"),
    Copy("复制"),
    Search("搜索"),
    Dictionary("词典"),
}

/** 动作 + 可用性：书内搜索（EPUB）属 T10，未就绪时灰置但仍显示（READER-24 的入口完整性）。 */
internal data class EpubSelectionActionItem(
    val action: EpubSelectionAction,
    val enabled: Boolean,
)

internal fun epubSelectionActions(inBookSearchAvailable: Boolean): List<EpubSelectionActionItem> =
    listOf(
        EpubSelectionActionItem(EpubSelectionAction.Highlight, enabled = true),
        EpubSelectionActionItem(EpubSelectionAction.Idea, enabled = true),
        EpubSelectionActionItem(EpubSelectionAction.Copy, enabled = true),
        EpubSelectionActionItem(EpubSelectionAction.Search, enabled = inBookSearchAvailable),
        EpubSelectionActionItem(EpubSelectionAction.Dictionary, enabled = true),
    )

/** 「想法」/「编辑笔记」的目标：新选区（首次创建）或已有标注。 */
internal sealed interface EpubNoteTarget {
    data class NewSelection(
        val selection: EpubSelectionSnapshot,
        val color: HighlightColor,
    ) : EpubNoteTarget

    data class Existing(val annotation: BookAnnotation) : EpubNoteTarget
}

/**
 * 选区浮动工具栏（定稿五 §2）。
 *
 * 高亮 = 展开四色；其余动作直接回调。搜索入口在 EPUB 书内搜索（T10）就位前灰置。
 */
@Composable
internal fun EpubSelectionToolbar(
    inBookSearchAvailable: Boolean,
    containerColor: Color,
    contentColor: Color,
    onAction: (EpubSelectionAction) -> Unit,
    onPickColor: (HighlightColor) -> Unit,
    modifier: Modifier = Modifier,
) {
    var paletteOpen by remember { mutableStateOf(false) }
    val actions = epubSelectionActions(inBookSearchAvailable)
    Surface(
        modifier = modifier,
        shape = CinefinShapes.Button,
        color = containerColor.copy(alpha = 0.96f),
        contentColor = contentColor,
    ) {
        Column(modifier = Modifier.padding(horizontal = CinefinSpacing.Space1)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                actions.forEach { item ->
                    val enabled = item.enabled
                    val color = if (enabled) contentColor else contentColor.copy(alpha = 0.38f)
                    Box(
                        modifier =
                            Modifier.heightIn(min = 48.dp)
                                .widthIn(min = 56.dp)
                                .cinefinClickable(
                                    onClickLabel = item.action.label,
                                    onClick = {
                                        when {
                                            !enabled -> Unit
                                            item.action == EpubSelectionAction.Highlight ->
                                                paletteOpen = !paletteOpen
                                            else -> onAction(item.action)
                                        }
                                    },
                                ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = item.action.label,
                            style = CinefinType.LabelMedium,
                            color = color,
                        )
                    }
                }
            }
            if (paletteOpen) {
                HorizontalDivider(color = contentColor.copy(alpha = 0.12f))
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = CinefinSpacing.Space1),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    HighlightColor.entries.forEach { color ->
                        Box(
                            modifier =
                                Modifier.size(48.dp)
                                    .cinefinClickable(
                                        onClickLabel = "高亮 ${color.id}",
                                        onClick = {
                                            paletteOpen = false
                                            onPickColor(color)
                                        },
                                    ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                modifier =
                                    Modifier.size(32.dp)
                                        .clip(CircleShape)
                                        .background(Color(color.argb))
                                        .border(
                                            width = 1.dp,
                                            color = contentColor.copy(alpha = 0.24f),
                                            shape = CircleShape,
                                        )
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 点击已有高亮后的底部小面板内容（定稿五 §2：改色 / 编辑笔记 / 删除 / 跳转）。 */
@Composable
internal fun EpubAnnotationActionsSheet(
    annotation: BookAnnotation,
    contentColor: Color,
    onPickColor: (HighlightColor) -> Unit,
    onEditNote: () -> Unit,
    onJump: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = CinefinSpacing.Space6)
                .padding(bottom = CinefinSpacing.Space6),
        verticalArrangement = Arrangement.spacedBy(CinefinSpacing.Space3),
    ) {
        Text(
            text = epubAnnotationSummary(annotation.selectedText).ifBlank { "高亮" },
            style = CinefinType.TitleMedium,
            color = contentColor,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (annotation.noteText.isNotBlank()) {
            Text(
                text = annotation.noteText,
                style = CinefinType.BodyMedium,
                color = contentColor.copy(alpha = 0.72f),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = "高亮颜色",
            style = CinefinType.LabelMedium,
            color = contentColor.copy(alpha = 0.72f),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(CinefinSpacing.Space2)) {
            HighlightColor.entries.forEach { color ->
                Box(
                    modifier =
                        Modifier.size(48.dp)
                            .cinefinClickable(
                                onClickLabel = "改为 ${color.id}",
                                onClick = { onPickColor(color) },
                            ),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier =
                            Modifier.size(32.dp)
                                .clip(CircleShape)
                                .background(Color(color.argb))
                                .border(
                                    width = if (annotation.highlightColor == color) 2.dp else 1.dp,
                                    color =
                                        if (annotation.highlightColor == color) contentColor
                                        else contentColor.copy(alpha = 0.24f),
                                    shape = CircleShape,
                                )
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(CinefinSpacing.Space2)) {
            CinefinButton(
                text = if (annotation.noteText.isBlank()) "写想法" else "编辑笔记",
                onClick = onEditNote,
                variant = CinefinButtonVariant.Outlined,
                size = CinefinButtonSize.Small,
            )
            CinefinButton(
                text = "跳转",
                onClick = onJump,
                variant = CinefinButtonVariant.Text,
                size = CinefinButtonSize.Small,
            )
            CinefinButton(
                text = "删除",
                onClick = onDelete,
                variant = CinefinButtonVariant.Text,
                size = CinefinButtonSize.Small,
            )
        }
    }
}

/** 标注列表（并入「更多」面板）：按章节顺序列出，可跳转 / 删除。 */
@Composable
internal fun EpubAnnotationListSheet(
    annotations: List<BookAnnotation>,
    contentColor: Color,
    onJump: (BookAnnotation) -> Unit,
    onRemove: (BookAnnotation) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = CinefinSpacing.Space6)
                .padding(bottom = CinefinSpacing.Space6),
        verticalArrangement = Arrangement.spacedBy(CinefinSpacing.Space2),
    ) {
        Text(text = "标注列表", style = CinefinType.TitleMedium, color = contentColor)
        Text(
            text = "只存本地；高亮按 locator 文本定位，字号 / 分页变化后仍可还原。",
            style = CinefinType.LabelMedium,
            color = contentColor.copy(alpha = 0.72f),
        )
        if (annotations.isEmpty()) {
            Text(
                text = "还没有标注：长按正文选中文字，用浮动工具栏加高亮或想法。",
                style = CinefinType.BodyMedium,
                color = contentColor.copy(alpha = 0.72f),
                modifier = Modifier.padding(vertical = CinefinSpacing.Space3),
            )
        } else {
            Text(
                text = "共 ${annotations.size} 条",
                style = CinefinType.LabelMedium,
                color = contentColor.copy(alpha = 0.72f),
            )
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
                verticalArrangement = Arrangement.spacedBy(CinefinSpacing.Space1),
            ) {
                items(items = annotations, key = { it.id }) { annotation ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier =
                                Modifier.padding(end = CinefinSpacing.Space2)
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(Color(annotation.highlightColor.argb))
                        )
                        Text(
                            text = annotation.label,
                            style = CinefinType.BodyMedium,
                            color = contentColor,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier =
                                Modifier.weight(1f).padding(vertical = CinefinSpacing.Space1),
                        )
                        TextButton(onClick = { onJump(annotation) }) {
                            Text(text = "跳转", color = contentColor)
                        }
                        TextButton(onClick = { onRemove(annotation) }) {
                            Text(text = "删除", color = contentColor)
                        }
                    }
                }
            }
        }
    }
}

/**
 * 词典入口（定稿五 §2）：系统 `ACTION_DEFINE` → 网页搜索 → 失败返回 false（调用方降级为复制）。
 *
 * 不内置在线服务；查询串截断到 60 字，避免把整段正文塞进 Intent。
 */
internal fun launchEpubDictionary(context: Context, text: String): Boolean {
    val query = text.trim().take(60)
    if (query.isEmpty()) return false
    val define =
        Intent(Intent.ACTION_DEFINE).setType("text/plain").putExtra(Intent.EXTRA_TEXT, query)
    if (startEpubIntent(context, define)) return true
    val webSearch = Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, query)
    return startEpubIntent(context, webSearch)
}

private fun startEpubIntent(context: Context, intent: Intent): Boolean = runCatching {
    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
    .isSuccess

/** 可复制的选区文本：折叠空白，去掉首尾空格。 */
internal fun epubCopyableText(text: String): String = text.trim()
