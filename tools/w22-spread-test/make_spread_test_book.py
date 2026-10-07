#!/usr/bin/env python3
"""W22/W26 「拆页型对图」测试书生成器（可复现，供跨页合并真机终验）。

背景：金田一原画 PDF 每页本身就是整幅对开图，跨页合并没有触发点（READER_PLAN §7.9.1）。
本脚本自造一本 30 页的合成漫画书，专门覆盖 `SpreadMerge.kt` 的判定与合成分支：

| 槽位（= 相邻两页） | 数量 | 期望 |
|--------------------|------|------|
| 独立单页对（封面 / 扉页 / 版权，内缘纸白） | 5 对 | 不合并 |
| **对图**（横跨中缝的整幅画被切成左右两页，竖版半页） | 8 对 | **合并** |
| 低相似负样本（两页内容都贴内缘但互不相关） | 1 对 | 不合并 |
| 横版整页（每页本身就是一幅对开图，宽高比 1.41） | 1 对 | 不合并 |

W26 profile（`--profile w26`，42 页 = 21 槽）在原 15 槽之后追加 6 个槽位，覆盖「带纸边对图」：

| 槽位 | 数量 | 期望 |
|------|------|------|
| 带纸边对图（内容两侧各留内缘纸白，需裁边后才能对穿） | 4 对 | **合并（裁剪路径）** |
| 带纸边独立页对（两页不同内容、各自内缘纸白） | 1 对 | 不合并 |
| 带纸边低相似对（内缘纸白 + 裁剪后曲线相位相反） | 1 对 | 不合并 |
| 带纸边中相关对（裁剪后曲线 corr≈0.5 < 0.75 裁剪门槛） | 1 对 | 不合并 |

对图按**水平镜像对称**绘制：中缝两侧像素连续（LTR 相位命中），外侧两缘也逐行一致
（RTL 相位命中），因此在「右起翻页」开与关下都能验收同一条合并链路。

产物（同一批 JPEG 页，两种容器）：
- `w22` profile → `<out-dir>/W22-Spread-Test.pdf/.cbz`（30 页，W22 回归）
- `w26` profile → `<out-dir>/W26-Spread-Edge-Test.pdf/.cbz`（42 页，带纸边新增样本）

生成后立刻用**与 Kotlin 纯函数同口径**的逻辑逐页自测（几何门槛 / 256 px 缩略图 / 内缘 2 列亮度带 /
四条门槛 / 纸边裁剪路径），打印每个槽位的命中与误判统计；不达标会以非零退出码结束。

用法：
    python tools/w22-spread-test/make_spread_test_book.py \
        --out-dir "F:\\Develop\\codex_work\\test_files" [--profile w26]
    python tools/w22-spread-test/make_spread_test_book.py --check-only <文件路径>

依赖：Pillow（生成 + 解析 PDF 内嵌页图）。固定随机种子 → 产物可复现。
"""

from __future__ import annotations

import argparse
import io
import math
import sys
import zipfile
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

# 单页（竖版半页）尺寸：宽高比 0.7068 ≈ A5/B6 漫画页
PAGE_W = 1408
PAGE_H = 1992

# —— 与 SpreadMerge.kt 完全一致的判定常量（改动需同步） ——
THUMB_MAX_SIDE = 256
EDGE_BAND_PX = 2
INK_THRESHOLD = 200
MIN_CONTINUITY = 0.05
MIN_VARIATION = 10.0
MIN_CORRELATION = 0.6
MAX_DIFFERENCE = 0.18
HALF_RATIO_MIN, HALF_RATIO_MAX = 0.55, 0.98
HEIGHT_TOLERANCE = 0.02
COMBINED_MIN, COMBINED_MAX = 1.15, 2.05
MIN_SEAM_ROWS = 8
# W26 纸边裁剪路径（与 SpreadMerge.kt 同口径）
TRIM_MIN_CORRELATION = 0.75
TRIM_MAX_DIFFERENCE = 0.15
MAX_EDGE_TRIM_RATIO = 0.18
PAPER_COLUMN_MIN_BRIGHTNESS = 215.0
PAPER_COLUMN_MAX_STD = 20.0

SEED = 20221002
QUALITY_RANGE = (96, 60)  # 体积自动调参的上下界（目标 20–50 MB）
TARGET_BYTES = (20 * 1024 * 1024, 50 * 1024 * 1024)
PDF_RESOLUTION = 150.0  # 只影响页面框：1408×1992 px → 675.8×956.2 pt（宽高比不变）

# 槽位表：15 个槽位 × 2 页 = 30 页（页序必须固定，spread = 索引 2k / 2k+1）
SLOTS = (
    "single",  # 0-1   封面 + 扉页
    "spread",
    "spread",
    "single",
    "spread",
    "mismatch",
    "spread",
    "landscape",
    "single",
    "spread",
    "spread",
    "single",
    "spread",
    "single",
    "spread",
)

# W26 追加槽位：带纸边对图 ×4 + 带纸边独立页对 + 带纸边低相似对
SLOTS_W26 = SLOTS + (
    "edge",
    "edge",
    "edge",
    "edge_single",
    "edge_mismatch",
    "edge",
    "edge_phase",
)

PROFILES = {
    "w22": {"slots": SLOTS, "stem": "W22-Spread-Test", "pages": len(SLOTS) * 2},
    "w26": {"slots": SLOTS_W26, "stem": "W26-Spread-Edge-Test", "pages": len(SLOTS_W26) * 2},
}

POSITIVE_SLOT_KINDS = ("spread", "edge")


def _rng(seed: int) -> np.random.Generator:
    return np.random.default_rng(seed)


def _vertical_gradient(width: int, height: int, top: int, bottom: int) -> np.ndarray:
    column = np.linspace(top, bottom, height, dtype=np.float32)[:, None]
    return np.repeat(column, width, axis=1)


def _paper_grain(shape, rng, sigma: float) -> np.ndarray:
    """细颗粒纸纹：只影响观感，缩略图平均后不影响中缝相关性。"""
    return rng.normal(0.0, sigma, shape).astype(np.float32)


def _draw_wave(draw: ImageDraw.ImageDraw, width: int, height: int, y0: float, amplitude: float, period: float, fill, thickness: int) -> None:
    points = []
    for x in range(0, width + 1, 6):
        y = y0 + amplitude * math.sin(2 * math.pi * x / period)
        points.append((x, y))
    draw.line(points, fill=fill, width=thickness, joint="curve")
    return None


def _spread_artwork(seed: int, style: int) -> Image.Image:
    """横跨中缝的整幅画（宽 = 2 个半页）：左半画完后镜像到右半，保证中缝连续 + 外侧两缘一致。"""
    rng = _rng(seed)
    half_w = PAGE_W
    palettes = (
        ((18, 26, 44), (222, 232, 240), (232, 92, 62)),
        ((36, 18, 24), (240, 226, 206), (58, 132, 168)),
        ((16, 34, 30), (226, 238, 222), (196, 158, 62)),
        ((28, 22, 44), (230, 226, 244), (120, 96, 200)),
    )
    dark, light, accent = palettes[style % len(palettes)]

    # 背景：竖向渐变（保证内缘逐行亮度有起伏，满足 variation 门槛）
    base = _vertical_gradient(half_w, PAGE_H, dark[0], light[0])
    rgb = np.repeat(base[:, :, None], 3, axis=2)
    for channel in range(3):
        rgb[:, :, channel] = _vertical_gradient(half_w, PAGE_H, dark[channel], light[channel])
    rgb += _paper_grain((PAGE_H, half_w, 1), rng, 4.0)
    half = Image.fromarray(np.clip(rgb, 0, 255).astype(np.uint8), "RGB")

    draw = ImageDraw.Draw(half)
    # ① 跨中缝的大圆 / 圆环（圆心落在中缝上 → 左右两半各画一半，镜像后成整圆）
    cx, cy = half_w, PAGE_H * (0.30 + 0.08 * (style % 3))
    radius = PAGE_H * (0.20 + 0.03 * (style % 4))
    draw.ellipse((cx - radius, cy - radius, cx + radius, cy + radius), outline=accent, width=9)
    draw.ellipse(
        (cx - radius * 0.62, cy - radius * 0.62, cx + radius * 0.62, cy + radius * 0.62),
        fill=tuple(int(c * 0.86) for c in accent),
    )
    # ② 跨中缝的波浪线（线条连续，中缝处像素逐行相关）
    for index in range(7):
        _draw_wave(
            draw,
            half_w,
            PAGE_H,
            y0=PAGE_H * (0.45 + 0.07 * index),
            amplitude=PAGE_H * (0.02 + 0.004 * index),
            period=half_w * (0.8 + 0.12 * index),
            fill=(20, 20, 24),
            thickness=4 + index % 3,
        )
    # ③ 斜向速度线（跨中缝的注视引导）
    for index in range(26):
        offset = PAGE_H * (0.06 + 0.035 * index)
        draw.line(
            (half_w * 0.05, offset, half_w, offset - PAGE_H * 0.10),
            fill=(40, 40, 46),
            width=2,
        )
    # ④ 网格网点（规则排布：不给中缝引入随机噪声）
    grid = 26
    for y in range(int(PAGE_H * 0.55), int(PAGE_H * 0.95), grid):
        for x in range(int(half_w * 0.25), half_w, grid):
            draw.ellipse((x, y, x + 3, y + 3), fill=(30, 30, 34))

    # 镜像成整幅：中缝处左右两列完全一致（corr=1），外侧两缘也一致（RTL 相位同样命中）
    return _mirror_pair(half)


def _mirror_pair(half: Image.Image) -> Image.Image:
    spread = Image.new("RGB", (half.width * 2, half.height))
    spread.paste(half, (0, 0))
    spread.paste(half.transpose(Image.FLIP_LEFT_RIGHT), (half.width, 0))
    return spread


def _single_page(seed: int, kind: str) -> Image.Image:
    """独立单页：四周留纸白（内缘 = 纸白 → 连续性与起伏都不达标，不会误拼）。"""
    rng = _rng(seed)
    page = Image.new("RGB", (PAGE_W, PAGE_H), (250, 249, 246))
    draw = ImageDraw.Draw(page)
    margin_x, margin_y = int(PAGE_W * 0.09), int(PAGE_H * 0.07)
    inner = (margin_x, margin_y, PAGE_W - margin_x, PAGE_H - margin_y)
    if kind == "cover":
        draw.rectangle(inner, fill=(28, 34, 52), outline=(232, 92, 62), width=8)
        draw.text((margin_x * 2, margin_y * 2), "W22 SPREAD TEST", fill=(240, 240, 244))
        for index in range(9):
            y = margin_y * 2 + 140 + index * 130
            draw.line((margin_x * 2, y, PAGE_W - margin_x * 2, y), fill=(232, 92, 62), width=6)
    else:
        draw.rectangle(inner, fill=(246, 244, 240), outline=(24, 24, 28), width=6)
        for index in range(6):
            top = margin_y + 80 + index * (PAGE_H - 2 * margin_y - 160) // 6
            box = (margin_x + 40, top, PAGE_W - margin_x - 40, top + 200)
            draw.rectangle(box, outline=(24, 24, 28), width=5)
            draw.line((box[0] + 20, (box[1] + box[3]) // 2, box[2] - 20, (box[1] + box[3]) // 2), fill=(60, 60, 66), width=4)
    grain = rng.normal(0.0, 3.0, (PAGE_H, PAGE_W, 1)).astype(np.float32)
    array = np.clip(np.asarray(page, dtype=np.float32) + grain, 0, 255).astype(np.uint8)
    return Image.fromarray(array, "RGB")


def _mismatch_pair(seed: int) -> tuple[Image.Image, Image.Image]:
    """低相似负样本：两页内容都贴内缘、都有起伏，但亮度曲线相位相反（相关系数 ≈ −1）。"""
    rng = _rng(seed)

    def page(inverted: bool) -> Image.Image:
        top, bottom = (30, 226) if not inverted else (226, 30)
        array = _vertical_gradient(PAGE_W, PAGE_H, top, bottom)
        rgb = np.repeat(array[:, :, None], 3, axis=2)
        rgb += _paper_grain((PAGE_H, PAGE_W, 1), rng, 5.0)
        image = Image.fromarray(np.clip(rgb, 0, 255).astype(np.uint8), "RGB")
        # 内容贴内缘：12% 宽的深色带，带内沿行号做正弦起伏（两页相位相反）
        # → 有内容、有起伏（std ≈ 56），但相关系数 ≈ −1，只能被相关性门槛拦下。
        rows = np.arange(PAGE_H, dtype=np.float32)
        profile = 90.0 + 80.0 * np.sin(2 * math.pi * rows / 90.0 + (math.pi if inverted else 0.0))
        band = np.repeat(profile[:, None], int(PAGE_W * 0.12), axis=1)
        band_rgb = np.repeat(band[:, :, None], 3, axis=2)
        pixels = np.asarray(image, dtype=np.float32)
        if inverted:
            pixels[:, : band.shape[1], :] = band_rgb
        else:
            pixels[:, -band.shape[1] :, :] = band_rgb
        return Image.fromarray(np.clip(pixels, 0, 255).astype(np.uint8), "RGB")

    return page(False), page(True)


def _landscape_pair(seed: int) -> tuple[Image.Image, Image.Image]:
    """横版整页负样本：每页本身就是一幅对开图（宽高比 1.41 → 几何门槛直接否决）。"""
    rng = _rng(seed)
    artwork = _mirror_pair(_spread_artwork(seed + 7, 1)).resize((PAGE_H, PAGE_W), Image.LANCZOS)
    array = np.clip(np.asarray(artwork, dtype=np.float32) + _paper_grain((PAGE_W, PAGE_H, 1), rng, 4.0), 0, 255)
    landscape = Image.fromarray(array.astype(np.uint8), "RGB")
    shifted = landscape.transpose(Image.FLIP_LEFT_RIGHT)
    return landscape.copy(), shifted


def _edge_page_with_border(content: Image.Image, side: str, paper_px: int) -> Image.Image:
    """把内容缩放到 (PAGE_W - paper_px) 宽后贴到纸白页；side = 内容所在的一侧（left/right）。"""
    inner_w = PAGE_W - paper_px
    scaled = content.resize((inner_w, PAGE_H), Image.LANCZOS)
    page = Image.new("RGB", (PAGE_W, PAGE_H), (250, 249, 246))
    page.paste(scaled, (0 if side == "left" else paper_px, 0))
    return page


def _edge_pair(seed: int, style: int, paper_px: int = 84) -> tuple[Image.Image, Image.Image]:
    """带纸边对图：整幅画切成两半，各自内缘留纸白（模拟扫描时的页面白边）。"""
    artwork = _mirror_pair(_spread_artwork(seed, style))
    left = artwork.crop((0, 0, PAGE_W, PAGE_H))
    right = artwork.crop((PAGE_W, 0, PAGE_W * 2, PAGE_H))
    return (
        _edge_page_with_border(left, "left", paper_px),
        _edge_page_with_border(right, "right", paper_px),
    )


def _edge_single_pair(seed: int, paper_px: int = 84) -> tuple[Image.Image, Image.Image]:
    """带纸边独立页对（负样本）：两张不同内容页，各自内缘纸白。"""
    return (
        _edge_page_with_border(_single_page(seed, "single"), "left", paper_px),
        _edge_page_with_border(_single_page(seed + 1, "single"), "right", paper_px),
    )


def _edge_mismatch_pair(seed: int, paper_px: int = 84) -> tuple[Image.Image, Image.Image]:
    """带纸边低相似对（负样本）：内缘纸白，裁边后两侧亮度曲线相位相反。"""
    first, second = _mismatch_pair(seed)
    return (
        _edge_page_with_border(first, "left", paper_px),
        _edge_page_with_border(second, "right", paper_px),
    )


def _edge_phase_pair(seed: int, paper_px: int = 84) -> tuple[Image.Image, Image.Image]:
    """带纸边中相关对（负样本）：裁边后两侧同频正弦相位差 60°（corr ≈0.5 < 裁剪门槛 0.75）。"""
    rng = _rng(seed)

    def page(inverted: bool, phase: float) -> Image.Image:
        base = _vertical_gradient(PAGE_W, PAGE_H, 210, 230)
        rgb = np.repeat(base[:, :, None], 3, axis=2)
        rgb += _paper_grain((PAGE_H, PAGE_W, 1), rng, 5.0)
        rows = np.arange(PAGE_H, dtype=np.float32)
        profile = 90.0 + 70.0 * np.sin(2 * math.pi * rows / 96.0 + phase)
        band = np.repeat(profile[:, None], int(PAGE_W * 0.12), axis=1)
        band_rgb = np.repeat(band[:, :, None], 3, axis=2)
        pixels = rgb
        if inverted:
            pixels[:, : band.shape[1], :] = band_rgb
        else:
            pixels[:, -band.shape[1] :, :] = band_rgb
        return Image.fromarray(np.clip(pixels, 0, 255).astype(np.uint8), "RGB")

    return (
        _edge_page_with_border(page(False, 0.0), "left", paper_px),
        _edge_page_with_border(page(True, math.pi / 3), "right", paper_px),
    )


def build_pages(profile: str = "w22") -> tuple[list[Image.Image], list[bool]]:
    slots = PROFILES[profile]["slots"]
    pages: list[Image.Image] = []
    expected: list[bool] = []
    spread_index = 0
    edge_index = 0
    for slot_index, kind in enumerate(slots):
        seed = SEED + slot_index * 101
        if kind == "spread":
            artwork = _mirror_pair(_spread_artwork(seed, spread_index))
            pages.append(artwork.crop((0, 0, PAGE_W, PAGE_H)))
            pages.append(artwork.crop((PAGE_W, 0, PAGE_W * 2, PAGE_H)))
            expected.extend([True, True])
            spread_index += 1
        elif kind == "edge":
            paper_px = (84, 60, 110, 36)[edge_index % 4]
            first, second = _edge_pair(seed, spread_index + edge_index, paper_px=paper_px)
            pages.extend([first, second])
            expected.extend([True, True])
            edge_index += 1
        elif kind == "single":
            label = "cover" if slot_index == 0 else "single"
            pages.append(_single_page(seed, label))
            pages.append(_single_page(seed + 1, "single"))
            expected.extend([False, False])
        elif kind == "mismatch":
            first, second = _mismatch_pair(seed)
            pages.extend([first, second])
            expected.extend([False, False])
        elif kind == "edge_single":
            first, second = _edge_single_pair(seed)
            pages.extend([first, second])
            expected.extend([False, False])
        elif kind == "edge_mismatch":
            first, second = _edge_mismatch_pair(seed)
            pages.extend([first, second])
            expected.extend([False, False])
        elif kind == "edge_phase":
            first, second = _edge_phase_pair(seed)
            pages.extend([first, second])
            expected.extend([False, False])
        else:  # landscape
            first, second = _landscape_pair(seed)
            pages.extend([first, second])
            expected.extend([False, False])
    return pages, expected


def _jpeg_bytes(image: Image.Image, quality: int) -> bytes:
    buffer = io.BytesIO()
    image.save(buffer, "JPEG", quality=quality, subsampling=0, optimize=True)
    return buffer.getvalue()


def write_outputs(pages: list[Image.Image], out_dir: Path, stem: str) -> tuple[Path, Path, int]:
    out_dir.mkdir(parents=True, exist_ok=True)
    pdf_path = out_dir / f"{stem}.pdf"
    cbz_path = out_dir / f"{stem}.cbz"
    # 质量自高向低调参：PDF 体积（≈ 内嵌 JPEG 总量）落进目标区间即停。
    quality = QUALITY_RANGE[0]
    while True:
        encoded = [_jpeg_bytes(page, quality) for page in pages]
        decoded = [Image.open(io.BytesIO(data)) for data in encoded]
        decoded[0].save(
            pdf_path,
            "PDF",
            save_all=True,
            append_images=decoded[1:],
            quality=quality,
            subsampling=0,
            resolution=PDF_RESOLUTION,
        )
        with zipfile.ZipFile(cbz_path, "w", zipfile.ZIP_STORED) as archive:
            for index, data in enumerate(encoded, start=1):
                archive.writestr(f"{index:03d}.jpg", data)
        if pdf_path.stat().st_size <= TARGET_BYTES[1] or quality <= QUALITY_RANGE[1]:
            break
        quality -= 4
    return pdf_path, cbz_path, quality


def _load_pdf_pages(path: Path) -> list[Image.Image]:
    from pypdf import PdfReader

    reader = PdfReader(str(path))
    return [Image.open(io.BytesIO(next(iter(page.images)).data)).convert("RGB") for page in reader.pages]


def _load_cbz_pages(path: Path) -> list[Image.Image]:
    with zipfile.ZipFile(path) as archive:
        names = sorted(archive.namelist())
        return [Image.open(io.BytesIO(archive.read(name))).convert("RGB") for name in names]


def _thumbnail_pdf(image: Image.Image) -> np.ndarray:
    """PDF 路径：PdfRenderer 按比例缩放到长边 = 256。"""
    scale = THUMB_MAX_SIDE / max(image.size)
    size = (max(1, round(image.width * scale)), max(1, round(image.height * scale)))
    return np.asarray(image.resize(size, Image.LANCZOS), dtype=np.float32)


def _thumbnail_cbz(image: Image.Image) -> np.ndarray:
    """CBZ 路径：BitmapFactory 的 2 的幂 inSampleSize 降采样。"""
    sample = 1
    while max(image.size) // sample > THUMB_MAX_SIDE:
        sample *= 2
    size = (max(1, round(image.width / sample)), max(1, round(image.height / sample)))
    return np.asarray(image.resize(size, Image.LANCZOS), dtype=np.float32)


def _edge_profile(
    gray: np.ndarray, edge: str, band: int = EDGE_BAND_PX, inset: int = 0
) -> np.ndarray:
    # 与 Kotlin 的 spreadEdgeSample 同口径：先压亮度，再取内缘 band 列逐行平均（inset 跳过纸边）
    if gray.ndim == 3:
        gray = gray[..., 0] * 0.3008 + gray[..., 1] * 0.5898 + gray[..., 2] * 0.1094
    if edge == "left":
        return gray[:, inset : inset + band].mean(axis=1)
    end = gray.shape[1] - inset
    return gray[:, end - band : end].mean(axis=1)


def _edge_column_stats(gray: np.ndarray, edge: str, max_px: int) -> tuple[np.ndarray, np.ndarray]:
    """与 Kotlin spreadEdgeColumnStats 同口径：内缘起 max_px 列的均值与列内标准差。"""
    if gray.ndim == 3:
        gray = gray[..., 0] * 0.3008 + gray[..., 1] * 0.5898 + gray[..., 2] * 0.1094
    columns = gray.shape[1]
    count = min(max_px, columns)
    means = np.empty(count, dtype=np.float64)
    stds = np.empty(count, dtype=np.float64)
    for index in range(count):
        x = index if edge == "left" else columns - 1 - index
        column = gray[:, x]
        means[index] = column.mean()
        stds[index] = column.std()
    return means, stds


def _paper_trim_inset(means: np.ndarray, stds: np.ndarray) -> int | None:
    """Kotlin paperTrimInset 同口径：内缘即内容 → 0；纸白后遇内容 → 偏移；整段纸白 → None。"""
    if len(means) == 0:
        return None
    paper = (means >= PAPER_COLUMN_MIN_BRIGHTNESS) & (stds <= PAPER_COLUMN_MAX_STD)
    if not bool(paper[0]):
        return 0
    for index in range(len(paper)):
        if not bool(paper[index]):
            return index
    return None


def _passes(evidence: tuple[float, float, float, float]) -> bool:
    continuity, variation, correlation, difference = evidence
    return (
        continuity >= MIN_CONTINUITY
        and variation >= MIN_VARIATION
        and correlation >= MIN_CORRELATION
        and difference <= MAX_DIFFERENCE
    )


def decide_merge(
    first_gray: np.ndarray, second_gray: np.ndarray, first_edge: str, second_edge: str
) -> tuple[bool, dict]:
    """与 Kotlin spreadMergeDecision 同口径：直接路径优先，失败后尝试纸边裁剪路径。"""
    base = _evidence(_edge_profile(first_gray, first_edge), _edge_profile(second_gray, second_edge))
    if _passes(base):
        return True, {"path": "direct", "trim_a": 0, "trim_b": 0, "evidence": base}
    max_first = max(1, int(first_gray.shape[1] * MAX_EDGE_TRIM_RATIO))
    max_second = max(1, int(second_gray.shape[1] * MAX_EDGE_TRIM_RATIO))
    means_first, stds_first = _edge_column_stats(first_gray, first_edge, max_first)
    means_second, stds_second = _edge_column_stats(second_gray, second_edge, max_second)
    trim_first = _paper_trim_inset(means_first, stds_first)
    trim_second = _paper_trim_inset(means_second, stds_second)
    if trim_first is None or trim_second is None or (trim_first <= 0 and trim_second <= 0):
        return False, {
            "path": "miss",
            "trim_a": trim_first,
            "trim_b": trim_second,
            "evidence": base,
        }
    trimmed = _evidence(
        _edge_profile(first_gray, first_edge, inset=trim_first),
        _edge_profile(second_gray, second_edge, inset=trim_second),
    )
    hit = (
        _passes(trimmed)
        and trimmed[2] >= TRIM_MIN_CORRELATION
        and trimmed[3] <= TRIM_MAX_DIFFERENCE
    )
    return hit, {"path": "trim", "trim_a": trim_first, "trim_b": trim_second, "evidence": trimmed}


def _evidence(first: np.ndarray, second: np.ndarray) -> tuple[float, float, float, float]:
    rows = min(len(first), len(second))
    first, second = first[:rows], second[:rows]
    both = (first < INK_THRESHOLD) & (second < INK_THRESHOLD)
    count = int(both.sum())
    if count < MIN_SEAM_ROWS:
        return count / rows, 0.0, 0.0, 1.0
    x, y = first[both].astype(np.float64), second[both].astype(np.float64)
    variance_x, variance_y = float(((x - x.mean()) ** 2).sum()), float(((y - y.mean()) ** 2).sum())
    deviation_x, deviation_y = math.sqrt(variance_x / count), math.sqrt(variance_y / count)
    variation = min(deviation_x, deviation_y)
    if variation < MIN_VARIATION or variance_x <= 0 or variance_y <= 0:
        correlation = 0.0
    else:
        correlation = float(((x - x.mean()) * (y - y.mean())).sum() / math.sqrt(variance_x * variance_y))
    difference = float(np.abs((x - x.mean()) - (y - y.mean())).mean() / 255.0)
    return count / rows, variation, correlation, difference


def detect(pages: list[Image.Image], thumbnail, slots: tuple[str, ...]) -> list[dict]:
    """与 Kotlin 同口径的逐槽位判定（LTR / RTL 两个相位）。"""
    results: list[dict] = []
    thumbs = [thumbnail(page) for page in pages]
    for slot in range(len(pages) // 2):
        first, second = pages[slot * 2], pages[slot * 2 + 1]
        ratio_first = first.width / first.height
        ratio_second = second.width / second.height
        geometry = (
            HALF_RATIO_MIN <= ratio_first <= HALF_RATIO_MAX
            and HALF_RATIO_MIN <= ratio_second <= HALF_RATIO_MAX
            and abs(first.height - second.height) / max(first.height, second.height) <= HEIGHT_TOLERANCE
            and COMBINED_MIN
            <= (first.width + second.width) / ((first.height + second.height) / 2)
            <= COMBINED_MAX
        )
        phases = {}
        for rtl in (False, True):
            first_edge = "left" if rtl else "right"
            second_edge = "right" if rtl else "left"
            hit, info = decide_merge(
                thumbs[slot * 2], thumbs[slot * 2 + 1], first_edge, second_edge
            )
            continuity, variation, correlation, difference = info["evidence"]
            phases["rtl" if rtl else "ltr"] = {
                "hit": geometry and hit,
                "geometry": geometry,
                "path": info["path"],
                "trim_a": info["trim_a"],
                "trim_b": info["trim_b"],
                "continuity": continuity,
                "variation": variation,
                "correlation": correlation,
                "difference": difference,
            }
        results.append({"slot": slot, "kind": slots[slot], "phases": phases})
    return results


def report(results: list[dict], label: str) -> bool:
    expected_slots = [result["kind"] in POSITIVE_SLOT_KINDS for result in results]
    print(f"== {label} ==")
    for rtl_key, phase_label in (("ltr", "LTR"), ("rtl", "RTL")):
        hits = misses = false_positive = 0
        for result, want in zip(results, expected_slots):
            phase = result["phases"][rtl_key]
            hit = phase["hit"]
            detail = (
                f"path={phase['path']} trim={phase['trim_a']}/{phase['trim_b']} "
                f"cont={phase['continuity']:.3f} std={phase['variation']:.1f} "
                f"corr={phase['correlation']:.3f} diff={phase['difference']:.3f}"
            )
            if want and hit:
                hits += 1
            elif want:
                misses += 1
                print(f"  [{phase_label}] 漏判 slot={result['slot']:02d} ({result['kind']}) {detail}")
            elif hit:
                false_positive += 1
                print(f"  [{phase_label}] 误判 slot={result['slot']:02d} ({result['kind']}) {detail}")
        total_pairs = len(results)
        ok = misses == 0 and false_positive == 0
        print(
            f"  {phase_label}: 对图命中 {hits}/{sum(1 for want in expected_slots if want)}"
            f"，漏判 {misses}，误判 {false_positive} / 共 {total_pairs} 槽位 "
            f"{'✓ 达标' if ok else '✗ 不达标'}"
        )
    hits = sum(1 for r, want in zip(results, expected_slots) if want and r["phases"]["ltr"]["hit"] and r["phases"]["rtl"]["hit"])
    expected_count = sum(1 for want in expected_slots if want)
    return hits == expected_count


def main() -> int:
    # Windows 默认 GBK 控制台装不下 ✓ / 中文，统一切到 UTF-8（不影响文件输出）。
    for stream in (sys.stdout, sys.stderr):
        reconfigure = getattr(stream, "reconfigure", None)
        if callable(reconfigure):
            try:
                reconfigure(encoding="utf-8", errors="replace")
            except (ValueError, OSError):
                pass
    parser = argparse.ArgumentParser(description="生成 / 自测 W22/W26 拆页型对图测试书")
    parser.add_argument(
        "--out-dir",
        default=r"F:\Develop\codex_work\test_files",
        help="输出目录（默认 = 真机测试素材目录，不入库）",
    )
    parser.add_argument(
        "--profile",
        choices=tuple(PROFILES),
        default="w22",
        help="测试书规格：w22 = 30 页 W22 回归；w26 = 42 页追加带纸边样本",
    )
    parser.add_argument("--check-only", help="只复检已有文件（PDF 或 CBZ），不重新生成")
    args = parser.parse_args()

    if args.check_only:
        path = Path(args.check_only)
        pages = _load_pdf_pages(path) if path.suffix.lower() == ".pdf" else _load_cbz_pages(path)
        profile = "w26" if "W26" in path.name.upper() else "w22"
        slots = PROFILES[profile]["slots"]
        if len(pages) != PROFILES[profile]["pages"]:
            print(
                f"页数 {len(pages)} 与 profile={profile} 的槽位表"
                f"（{PROFILES[profile]['pages']} 页）不匹配"
            )
            return 1
        thumbnail = _thumbnail_pdf if path.suffix.lower() == ".pdf" else _thumbnail_cbz
        results = detect(pages, thumbnail, slots)
        return 0 if report(results, f"{path.name}（复检 · {profile}）") else 1

    pages, _ = build_pages(args.profile)
    slots = PROFILES[args.profile]["slots"]
    pdf_path, cbz_path, quality = write_outputs(
        pages, Path(args.out_dir), PROFILES[args.profile]["stem"]
    )
    print(f"生成：{pdf_path}（{pdf_path.stat().st_size / 1048576:.1f} MB）")
    print(f"生成：{cbz_path}（{cbz_path.stat().st_size / 1048576:.1f} MB）")
    print(f"页数：{len(pages)}　JPEG quality={quality}")
    for path in (pdf_path, cbz_path):
        size = path.stat().st_size
        if not TARGET_BYTES[0] <= size <= TARGET_BYTES[1]:
            print(f"提示：{path.name} 体积 {size / 1048576:.1f} MB 不在 20–50 MB 目标区间内")

    ok_pdf = report(
        detect(_load_pdf_pages(pdf_path), _thumbnail_pdf, slots),
        "PDF 路径（缩放到长边 256）",
    )
    ok_cbz = report(
        detect(_load_cbz_pages(cbz_path), _thumbnail_cbz, slots),
        "CBZ 路径（2 的幂降采样）",
    )
    return 0 if ok_pdf and ok_cbz else 1


if __name__ == "__main__":
    sys.exit(main())
