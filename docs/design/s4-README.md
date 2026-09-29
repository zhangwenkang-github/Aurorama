# S4 交付说明 · Prism 棱镜设计系统 v1.0 + 修订稿

> 设计会话：S4（2026-09-29）｜分支：`feature/s4-design-system`｜状态：已交付（v1.0 冻结候选）
> 依据：`docs/design/s1-decision.md`（B 骨架 + A 沉浸 + C 皮肤；**媒体色融入按钮**）+ `docs/REQUIREMENTS.md` §6/§9/§10

---

## 1. 交付物清单

| 文件 | 说明 |
|------|------|
| `docs/UI_DESIGN_SYSTEM.md` | 设计系统 v1.0：色彩 / 字体 / 间距栅格 / 形状 / 动效 / 图标 / 全组件状态矩阵 / Compose 映射 / 验收清单（732 行，含全部 hex / dp / sp / ms / bezier 数值） |
| `docs/design/_src/direction-s4.html` | 修订稿渲染源（4 屏 + 3 状态板；复用 B 的素材与无头 Edge 渲染管线） |
| `docs/design/_src/render.ps1` | 新增 `-Dir` 参数与 `s4-revision` 目标（原用法不变） |
| `docs/design/s4-revision/home.png` | 首页（2048×1280） |
| `docs/design/s4-revision/detail.png` | 详情页（2048×1280） |
| `docs/design/s4-revision/library.png` | 媒体库（新增屏，2048×1280） |
| `docs/design/s4-revision/music.png` | 音乐 · 正在播放（2048×1280） |
| `docs/design/s4-revision/board-buttons.png` | 组件状态板 1：按钮 4 变形 × 5 状态 + chip + 分段 + 徽标 + 三域样例 |
| `docs/design/s4-revision/board-cards.png` | 组件状态板 2：海报卡 / 横版卡 / 列表行 + 形状描边标注 |
| `docs/design/s4-revision/board-list.png` | 组件状态板 3：列表 / 进度 / 空状态 / 对话框 / Toast / 歌词 / 阅读排版面板 |

## 2. 本次改动点（相对 S1 方向稿）

1. **媒体色融入按钮（用户修订，已全面落实）**
   - 删除原稿的全部"独立色块"：chip 内色点（`.sq`）、徽标内色点（`.dt`）、侧导航右侧色点（`.dt`）、按钮旁色块；
   - 按钮四变形统一按新规则：Filled（底 `Media.Base` + `OnBase`）、Outlined（`Media.Outline` 描边 + `Media.Bright` 内容）、Text（`Media.Bright` 文字）、Icon（`Media.Bright` 图标）；
   - 全状态矩阵：default / hover（白 8%）/ pressed（`Media.Dim` / `Media.Container`）/ focused（2dp 外环 60%）/ disabled（38%）；
   - 详情页"播放"= 琥珀 Filled，"加入收藏 / 下载"= 琥珀 Outlined；首页 hero、"继续阅读"、音乐播放键同理换入各自域色。
2. **新增"媒体库"屏**：分段控件（影视 / 音乐 / 阅读）+ 6 个筛选 chip + 继续观看横版卡 + 4 列影视网格 + 最近添加列表；用于验证 chip / 分段 / 卡片 / 列表在媒体色纪律下的组合。
3. **新增 3 张组件状态板**：覆盖任务书要求的按钮矩阵、卡片、列表；额外带上进度、Toast、对话框、空状态、歌词与阅读排版面板。
4. **色彩 token 升级为"明暗变体"体系**：每域 7 个 token（Base / Bright / Dim / Container / ContainerPressed / OnBase / Outline），并给出合成 hex + Compose alpha 两种写法。
5. **三色纪律写明例外条款**：同屏 1 媒体色，仅首页 / 设置页例外；播放器画面覆盖层允许"玻璃 + 奶白"特例；阅读器纸色 / 护眼主题把天青替换为纸页棕 `#A8843C`（C · Nocturne 皮肤）。
6. **可读性修正**：详情页头图遮罩加深（brightness .40 + 0.94/0.88 渐变），消除原稿亮背景压字问题；媒体库跨域图标改中性。

## 3. 与 S1 方向稿的差异表

| 项 | S1 · B 稿 | S4 修订稿 |
|----|-----------|-----------|
| 主按钮 | 奶白填充（`.btn.primary`） | 当前域媒体色 Filled（含 `OnBase` 深色文字） |
| 次级按钮 | 中性 `.btn.ghost` | 媒体色 Outlined |
| chip | 未选中含 8px 色点；选中 = 奶白填充 | 无色点；选中 = `Media.Container` + `Media.Bright` + `Media.Outline` |
| 徽标 | 含 7dp 色点 | 无色点；描边 + 文字着色 |
| 侧导航 | 选中 = 中性底 + 右侧色点 | 选中 = 域色容器底 + `Media.Bright` 文字，无点 |
| 媒体色明暗 | 仅 3 个 base 值 | 每域 7 个 token（含明暗与容器变体） |
| 媒体库屏 | 无 | 新增（分段 + chip + 网格 + 列表） |
| 组件状态板 | 无 | 3 张（按钮 / 卡片 / 列表反馈） |
| 阅读器纸色强调 | 黄铜（仅阅读页） | 明确为"纸色主题域色变体 `#A8843C`"，深色 / OLED 仍用天青 |

## 4. 渲染复现命令

```powershell
# 项目根目录（E:\Codex work\work space\c013\Cinefin）
pwsh docs/design/_src/render.ps1 -Dir s4-revision

# 只重渲染单张（键名：home / detail / library / music / board-buttons / board-cards / board-list）
pwsh docs/design/_src/render.ps1 -Dir s4-revision -Only board-buttons
```

渲染管线：无头 Edge（`C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe`）+ `?s=<key>` 切屏 + `--window-size=2048,1280`；素材沿用 `docs/design/_src/assets/`。

## 5. 与设计系统的对应验证

| 修订稿 | 验证的规范条目 |
|--------|----------------|
| `home.png` | §8.1 按钮变形 / §8.3 chip / §8.9 徽标 / §2.5 三色纪律（首页例外） |
| `detail.png` | §8.1 Filled + Outlined / §8.5 列表当前行 / §8.4 卡片 / §7.2 图标 |
| `library.png` | §8.2 分段控件 / §8.3 chip / §8.4 卡片 / §8.6 侧导航选中态 |
| `music.png` | §8.13 歌词组件 / §8.7 播放器控件 / §2.2 音乐域 token |
| `board-buttons.png` | §8.1 全状态矩阵 / §8.2 / §8.3 / §8.9 |
| `board-cards.png` | §8.4 / §5.1 圆角 / §5.2 描边与内高光 / §5.3 无投影 |
| `board-list.png` | §8.5 / §8.8 进度 / §8.10 空状态 / §8.11 对话框 / §8.12 Toast / §8.14 阅读面板 |

## 6. 已知取舍

- 视觉稿素材仍是本地截图裁切（与 S1 相同），仅验证版面与色彩规则；落地时由 Jellyfin 实际海报填充。
- 组件状态板的 hover / pressed 在静态 PNG 中以"叠加倍率 + 状态底色"呈现，落地实现以 `UI_DESIGN_SYSTEM.md` §8.1 的状态层数值为准（M3 状态层：hover 8% / focus 12% / pressed 12%）。
- 手机（Compact）与折叠态的规范已在设计系统 §4.3/§4.4 写全，本轮未新增手机修订稿；如需出手机稿，用同一渲染管线加 `phone` 尺寸即可。
