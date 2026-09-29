"""从 docs/screenshots 裁切真实媒体海报，作为 S1 设计稿素材。

用法（项目根目录）：
    python docs/design/_src/make_assets.py

输出：docs/design/_src/assets/*.jpg
"""

import os

from PIL import Image

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
SHOTS = os.path.join(ROOT, "docs", "screenshots")
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "assets")

# 源图 2560x1600。坐标为 760px 预览图坐标 x (2560/760)。
SCALE = 2560 / 760


def box(x0, y0, x1, y1):
    return (
        round(x0 * SCALE),
        round(y0 * SCALE),
        round(x1 * SCALE),
        round(y1 * SCALE),
    )


# (源文件, 区域, 输出名)
CROPS = [
    # home-tablet-v3：顶部两张大卡 + 最近添加 6 张海报
    ("home-tablet-v3-2026-09-27.png", box(22, 70, 190, 137), "wide-01.jpg"),
    ("home-tablet-v3-2026-09-27.png", box(193, 70, 358, 137), "wide-02.jpg"),
    ("home-tablet-v3-2026-09-27.png", box(22, 207, 123, 377), "poster-01.jpg"),
    ("home-tablet-v3-2026-09-27.png", box(134, 207, 234, 377), "poster-02.jpg"),
    ("home-tablet-v3-2026-09-27.png", box(245, 207, 345, 377), "poster-03.jpg"),
    ("home-tablet-v3-2026-09-27.png", box(356, 207, 457, 377), "poster-04.jpg"),
    ("home-tablet-v3-2026-09-27.png", box(468, 207, 568, 377), "poster-05.jpg"),
    ("home-tablet-v3-2026-09-27.png", box(579, 207, 680, 377), "poster-06.jpg"),
    # media-libraries：三张媒体库大图
    ("media-libraries-2026-09-27.png", box(22, 145, 248, 285), "library-01.jpg"),
    ("media-libraries-2026-09-27.png", box(260, 145, 487, 285), "library-02.jpg"),
    # 媒体库大图的上缘条带：用于详情页/播放页的沉浸背景（避开原界面文字）
    ("media-libraries-2026-09-27.png", box(22, 145, 248, 196), "backdrop-01.jpg"),
    ("media-libraries-2026-09-27.png", box(260, 145, 487, 196), "backdrop-02.jpg"),
    # drawer：右侧两张完整海报
    ("drawer-2026-09-27.png", box(268, 262, 435, 447), "poster-07.jpg"),
    ("drawer-2026-09-27.png", box(438, 262, 605, 447), "poster-08.jpg"),
    # 从海报上段裁出横版画面，供"接下来"货架使用（避开原界面文字）
    ("home-tablet-v3-2026-09-27.png", box(245, 207, 345, 268), "wide-03.jpg"),
    ("home-tablet-v3-2026-09-27.png", box(468, 207, 568, 268), "wide-04.jpg"),
    # 16:9 视频画面（海报中段，避开原界面文字）
    ("home-tablet-v3-2026-09-27.png", box(134, 226, 234, 282), "frame-01.jpg"),
    ("home-tablet-v3-2026-09-27.png", box(468, 240, 568, 296), "frame-02.jpg"),
    ("home-tablet-v3-2026-09-27.png", box(356, 232, 457, 288), "frame-03.jpg"),
]


def main():
    os.makedirs(OUT, exist_ok=True)
    for name, region, out_name in CROPS:
        src = Image.open(os.path.join(SHOTS, name)).convert("RGB")
        tile = src.crop(region)
        tile.save(os.path.join(OUT, out_name), quality=90)
        print(out_name, tile.size)


if __name__ == "__main__":
    main()
