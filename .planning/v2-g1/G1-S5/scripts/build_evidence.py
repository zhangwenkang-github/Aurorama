#!/usr/bin/env python3
"""Build the deterministic G1-S5 visual evidence package.

The script only writes below .planning/v2-g1/G1-S5/.
It uses the Python standard library; no remote assets or external packages.
"""

from __future__ import annotations

import csv
import hashlib
import json
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


EXPECTED_BRANCH = "codex/v2-integration"
EXPECTED_HEAD = "397ff4f0a3d8378c91ee283da317c5263cc80285"
ROOT = Path(__file__).resolve().parents[1]
REPO = ROOT.parents[2]
ASSETS = ROOT / "assets"
DATA = ROOT / "data"
SCRIPTS = ROOT / "scripts"
ATTRIBUTES = ROOT / ".gitattributes"
LINE_ENDING_RULE = "* text eol=lf\n"
VERIFICATION_COMMAND = (
    'pwsh -NoProfile -Command "python .planning/v2-g1/G1-S5/scripts/verify_evidence.py '
    '--expected-dirty docs/DECISIONS.md --expected-dirty docs/TASKS.md '
    '--expected-dirty docs/V2_PLAN.md"'
)


def utf8(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text.replace("\r\n", "\n"), encoding="utf-8", newline="\n")


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def run_git(*args: str) -> str:
    result = subprocess.run(
        ["git", *args],
        cwd=REPO,
        check=True,
        capture_output=True,
        text=True,
        encoding="utf-8",
    )
    return result.stdout.strip()


def verify_git() -> dict:
    branch = run_git("branch", "--show-current")
    head = run_git("rev-parse", "HEAD")
    if branch != EXPECTED_BRANCH:
        raise SystemExit(f"BLOCKED_BRANCH: {branch}")
    if head != EXPECTED_HEAD:
        raise SystemExit(f"BLOCKED_HEAD: {head}")
    outside = []
    for line in run_git("status", "--porcelain=v1", "--untracked-files=all").splitlines():
        rel = line[3:].strip().replace("\\", "/")
        if rel and not rel.startswith(".planning/v2-g1/G1-S5/"):
            outside.append(line)
    if outside:
        raise SystemExit("BLOCKED_WORKTREE: " + " | ".join(outside))
    return {
        "branch": branch,
        "head": head,
        "worktreeOutsideEvidenceRoot": "clean",
        "evidenceRoot": ".planning/v2-g1/G1-S5/",
    }


THEMES = {
    "prism": {
        "order": 1,
        "label": "Prism",
        "cn": "精密折射",
        "tagline": "细线、几何与明确分区",
        "material": "平实表面 · 1.75dp 几何轮廓 · 只在内容图内折光",
        "type": "MiSans；标题 700、紧凑字距；字号层级稳定",
        "motion": 180,
        "radius": 12,
        "border": 1.5,
        "dark": {
            "bg": "#151A21", "panel": "#1B212B", "raised": "#222A36",
            "text": "#F4F1EA", "text2": "#A7B0BD", "outline": "#667383",
            "film": "#E8A15C", "music": "#3FC9A0", "book": "#6FA8FF",
            "error": "#E87C6E", "warning": "#E4B962", "success": "#77C99D",
            "info": "#72AEF5", "focus": "#F4F1EA", "onAccent": "#141009",
        },
        "light": {
            "bg": "#F5F3EE", "panel": "#FFFFFF", "raised": "#EBE7DE",
            "text": "#1B212B", "text2": "#59616E", "outline": "#858077",
            "film": "#8A4E0D", "music": "#116754", "book": "#2E58A1",
            "error": "#A7372D", "warning": "#765011", "success": "#246341",
            "info": "#285D91", "focus": "#1B6A90", "onAccent": "#FFFFFF",
        },
    },
    "lumen": {
        "order": 2,
        "label": "Lumen",
        "cn": "冷青流光",
        "tagline": "媒介图突出，玻璃只服务功能层",
        "material": "冷青玻璃 · 内部高光 · 少量受控极光仅出现在图标状态",
        "type": "MiSans；标题 700、舒展行距；默认新装主题",
        "motion": 200,
        "radius": 16,
        "border": 1.5,
        "dark": {
            "bg": "#07171B", "panel": "#0E262C", "raised": "#15343A",
            "text": "#EFF8F6", "text2": "#B1CBC8", "outline": "#617F7F",
            "film": "#FFC46B", "music": "#64D7BC", "book": "#A6CBFF",
            "error": "#F59086", "warning": "#F0CB7E", "success": "#81D9B9",
            "info": "#91C4F4", "focus": "#9FE7DA", "onAccent": "#07110F",
        },
        "light": {
            "bg": "#EFF8F5", "panel": "#FFFFFF", "raised": "#DDEFE9",
            "text": "#102723", "text2": "#46645F", "outline": "#779A91",
            "film": "#805000", "music": "#0C6658", "book": "#315888",
            "error": "#9B332A", "warning": "#705114", "success": "#236547",
            "info": "#285A87", "focus": "#0A6C61", "onAccent": "#FFFFFF",
        },
    },
    "nocturne": {
        "order": 3,
        "label": "Nocturne",
        "cn": "夜读金线",
        "tagline": "高对比编辑排版，暖金与靛影作角色差",
        "material": "暖金发丝线 · 靛影中性层 · 中文始终保持正常字面",
        "type": "Literata + MiSans；展示标题偏编辑衬线，正文仍清晰",
        "motion": 220,
        "radius": 8,
        "border": 1.5,
        "dark": {
            "bg": "#161210", "panel": "#201A17", "raised": "#2B231E",
            "text": "#F4E8D8", "text2": "#C8B3A2", "outline": "#786355",
            "film": "#E2B06A", "music": "#D89378", "book": "#B1C58C",
            "error": "#EF8A78", "warning": "#E3BC72", "success": "#A8C488",
            "info": "#AEB9CE", "focus": "#FFE2AE", "onAccent": "#171009",
        },
        "light": {
            "bg": "#F5EDE4", "panel": "#FFF9F1", "raised": "#E9DED1",
            "text": "#2B201C", "text2": "#67564E", "outline": "#917F6E",
            "film": "#7B5319", "music": "#874D37", "book": "#4E602C",
            "error": "#9E3227", "warning": "#74531C", "success": "#47611F",
            "info": "#405B7C", "focus": "#7B5319", "onAccent": "#FFFFFF",
        },
    },
    "monolith": {
        "order": 4,
        "label": "Monolith",
        "cn": "硬质层次",
        "tagline": "不透明、强描边、管理页更表格化",
        "material": "实心表面 · 2dp 强轮廓 · 无辉光和材质模糊",
        "type": "MiSans；标题 700、短反馈、强层级重量",
        "motion": 150,
        "radius": 8,
        "border": 2,
        "dark": {
            "bg": "#12171C", "panel": "#1D242B", "raised": "#29323B",
            "text": "#F4F6F7", "text2": "#B7C0C8", "outline": "#65727E",
            "film": "#E0A55C", "music": "#56C7A5", "book": "#8CB3F2",
            "error": "#EF7B70", "warning": "#E5BE72", "success": "#79D0A9",
            "info": "#91BCF2", "focus": "#E6EEF7", "onAccent": "#101316",
        },
        "light": {
            "bg": "#F1F3F3", "panel": "#FFFFFF", "raised": "#E3E7E7",
            "text": "#12171C", "text2": "#4C5760", "outline": "#737D84",
            "film": "#8B4D0A", "music": "#0D6357", "book": "#315C9C",
            "error": "#A5362A", "warning": "#725118", "success": "#286044",
            "info": "#285A8D", "focus": "#1C546B", "onAccent": "#FFFFFF",
        },
    },
    "dynamic": {
        "order": 5,
        "label": "系统动态色",
        "cn": "壁纸语义映射",
        "tagline": "继承 Prism 骨架，只替换颜色角色",
        "material": "角色色整屏一致 · 中性表面由 system neutral roles 决定 · 不叠加品牌主题",
        "type": "沿用 Prism 比例；图标形状不随壁纸变化",
        "motion": 180,
        "radius": 12,
        "border": 1.5,
        "dark": {
            "bg": "#181C19", "panel": "#222822", "raised": "#2E342C",
            "text": "#F4F3EC", "text2": "#BBC4B6", "outline": "#6F7C69",
            "film": "#B8D78B", "music": "#F1BE8B", "book": "#A8C7F2",
            "error": "#F08B7D", "warning": "#E9C47B", "success": "#9AD5B0",
            "info": "#9FC7F2", "focus": "#F4F3EC", "onAccent": "#171A13",
        },
        "light": {
            "bg": "#F5F7EF", "panel": "#FFFFFF", "raised": "#E6EADF",
            "text": "#20241D", "text2": "#56604F", "outline": "#8A9481",
            "film": "#4D661D", "music": "#814C1A", "book": "#355A91",
            "error": "#9C352B", "warning": "#6B511A", "success": "#2D603B",
            "info": "#355A82", "focus": "#4D661D", "onAccent": "#FFFFFF",
        },
    },
}


INTERACTIONS = [
    {
        "id": "S5-I01", "slug": "home", "title": "首页",
        "entry": "启动进入首页；只有当前 server + user 的权威续播项才能成为主焦点。",
        "action": "点击“继续阅读”；按下后立即保留主卡与返回路径。",
        "feedback": "展示“正在恢复进度 62%”；内容可渲染后进入来源页，底部导航状态不丢。",
        "recovery": "不可续 / 离线 / 区块失败分别说明；保留最近内容与三域入口，提供重试或返回。",
        "sources": ["HOME-01", "HOME-02", "HOME-03", "HOME-08", "UX-012", "UX-014"],
    },
    {
        "id": "S5-I02", "slug": "media-video-detail", "title": "媒体库 / 视频详情",
        "entry": "从媒体库来源分组进入详情；海报、标题与动作排先于网络补齐。",
        "action": "点击主播放键；动作保持可见，显示“正在准备播放”且只接受一次提交。",
        "feedback": "PlaybackInfo 等待时保留壳层；403 / 404 / 415 区分权限、缺失与格式不支持。",
        "recovery": "可归因错误给“重试 / 返回”之一；失败不得把详情伪装成空内容。",
        "sources": ["NAV-02", "UX-014", "V2-Q-G1-03"],
    },
    {
        "id": "S5-I03", "slug": "music-queue", "title": "音乐 / 队列",
        "entry": "从曲目行打开短操作；播放下一首、追加队尾、清空与移除均使用明确文本。",
        "action": "点“播放下一首”立即高亮当前队列位置；重复点按不重复插入。",
        "feedback": "Snackbar 与队列同时显示“已插入到下一首”；来源标签保持同曲目身份。",
        "recovery": "本地文件丢失不重试，保留不可用身份；全部失败时停止并提供重试 / 返回。",
        "sources": ["MUSIC-014", "MUSIC-016", "MUSIC-020", "MUSIC-022", "UX-014"],
    },
    {
        "id": "S5-I04", "slug": "bookshelf-reader", "title": "书架 / 阅读器",
        "entry": "从书架或本地库打开书；书籍来源与传输状态独立显示。",
        "action": "点“继续阅读”；远端不能流式时明确显示“准备整本文件”，不无限旋转。",
        "feedback": "阅读正文主题只覆盖正文；顶栏、按钮与弹层继续服从 App 主题。",
        "recovery": "远端流失败回退整本下载；本地文件不可用保留书目身份并提供重选或移除。",
        "sources": ["READER-010", "READER-018", "READER-038", "UX-014"],
    },
    {
        "id": "S5-I05", "slug": "downloads", "title": "下载",
        "entry": "从内容详情或下载中心开始单项任务；先检查网络、空间、卷与来源。",
        "action": "确认后立即出现 accepted / pending；重复点击聚焦既有任务。",
        "feedback": "任务、进度、通知和下载页使用同一状态真源；完成与失败必须带文字。",
        "recovery": "空间不足让用户选择可容纳范围；401 / 403 / 404 / 415 不自动重试，保留暂停 / 删除路径。",
        "sources": ["V2-REQ-DOWNLOAD-068", "DL-FEEDBACK", "DL-PROMPT", "UX-014"],
    },
    {
        "id": "S5-I06", "slug": "search", "title": "搜索",
        "entry": "顶栏进入并带 initialScope；查询、scope 与返回来源同时可见。",
        "action": "提交查询只接受最新 generation；旧结果保留但不冒充新 scope。",
        "feedback": "pending、未输入、无结果、失败四种状态分开；失败保留查询与 scope。",
        "recovery": "失败给重试；不支持的 scope 显式禁用并解释，空查询不提交请求。",
        "sources": ["NAV-010", "UX-014", "V2-REQ-UX-014"],
    },
    {
        "id": "S5-I07", "slug": "settings-theme", "title": "设置 / 主题",
        "entry": "设置中分开“主题”与“外观模式”；大尺寸内置预览即时可见。",
        "action": "点选 Lumen；主题和焦点在 200ms 可关闭过渡内切换，不重置滚动或数据。",
        "feedback": "当前值使用勾选 + 文本 + 描边；设备本地保存成功不回写服务器。",
        "recovery": "持久化失败回退旧主题并提示；Android 12 以下动态色入口禁用并解释。",
        "sources": ["UX-037", "V2-D035", "V2-D036", "V2-D039", "V2-D041", "UX-014"],
    },
    {
        "id": "S5-I08", "slug": "playback-controls", "title": "播放控制",
        "entry": "从详情或 Mini Player 进入；当前域、标题、进度和传输状态保持同一表达。",
        "action": "播放 / 暂停只改变一个有名称的按钮；seek、字幕、音轨与锁定键按能力启用。",
        "feedback": "开关态同时使用图标、状态文字和 TalkBack 可读状态；控制层 4 秒隐藏后仍可召回。",
        "recovery": "单条失败按规则跳项；全部失败停止，给非模态重试 / 返回；Reduce Motion 下直接切换。",
        "sources": ["MUSIC-022", "MUSIC-052", "MUSIC-059", "VIDEO-064", "UX-011"],
    },
    {
        "id": "S5-I09", "slug": "syncplay-candidate", "title": "SyncPlay 候选",
        "entry": "仅在未来能力门通过时出现入口；当前不承诺产品可用。",
        "action": "候选流程为创建 / 加入会话；必须先通过在线、权限与协议受理反馈。",
        "feedback": "展示 joining、accepted、revision 与成员状态；不得把本地成功当服务器成功。",
        "recovery": "离线 / 403 / 不支持 / 会话过期均退出到普通播放；真实双端与时钟证据留 G2。",
        "sources": ["V2_PLAN §5.11.6", "V2_PLAN §5.11.8", "V2-Q-G1-03"],
    },
]


RWD_ROWS = [
    ("RWD-COMPACT", "Compact", 360, 780, "bottom", 0, 16, 12, 140, "RWD-01/RWD-02/RWD-04/RWD-07"),
    ("RWD-MEDIUM", "Medium", 600, 900, "rail", 72, 24, 16, 140, "RWD-01/RWD-02/RWD-04/RWD-05"),
    ("RWD-EXPANDED", "Expanded", 840, 700, "rail", 72, 32, 16, 140, "RWD-01/RWD-02/RWD-04/RWD-05"),
    ("RWD-LARGE", "Large", 1200, 800, "expanded-rail", 216, 48, 24, 140, "RWD-01/RWD-02/RWD-04/RWD-05"),
    ("RWD-EXTRA", "ExtraLarge", 1600, 900, "expanded-rail", 216, 48, 24, 140, "RWD-01/RWD-02/RWD-04/RWD-05"),
]


PAGE_STATE_IDS = ["default", "loading", "empty", "offline", "error", "permission"]
STATE_LABELS = {
    "default": "正常",
    "loading": "加载",
    "empty": "空",
    "offline": "离线",
    "error": "错误",
    "permission": "权限",
}


PAGE_STATES = [
    {
        "page": "首页",
        "default": "唯一续播主卡 + 紧凑最近内容 + 三域入口",
        "loading": "有缓存静默刷新；完全无内容才骨架",
        "empty": "无续播时主卡空态，最近内容与入口保留",
        "offline": "缓存内容可用并标注离线；不伪造刷新",
        "error": "区块级失败与重试，保留成功区块",
        "permission": "不可访问库或续播项不进入主卡，说明身份 / 权限",
    },
    {
        "page": "视频 / 音乐 / 书架",
        "default": "当前库、tab、选中项与列表内容",
        "loading": "首载骨架；刷新保留旧列表",
        "empty": "无库、库为空、筛选无结果分别说明",
        "offline": "已缓存列表继续显示；不可播项显式标注",
        "error": "保留壳层与缓存，给重试",
        "permission": "下载、播放、阅读或批量动作按能力 / 权限解释",
    },
    {
        "page": "媒体库 / 收藏",
        "default": "来源分组、库卡与收藏项",
        "loading": "本地与服务器分区加载，本地可先显示",
        "empty": "无媒体库或无收藏，告诉用户下一步",
        "offline": "本地来源可用；服务器来源标注离线",
        "error": "单来源失败不清空另一来源",
        "permission": "管理员门控与管理入口分开显示",
    },
    {
        "page": "搜索",
        "default": "查询框、当前 scope、初始或最近状态",
        "loading": "保留查询并显示明确 pending",
        "empty": "未输入与无结果严格分开",
        "offline": "本地 / 缓存 scope 可用，远端 scope 解释离线",
        "error": "保留查询与 scope，给重试",
        "permission": "不支持或无权限 scope 禁用并说明原因",
    },
    {
        "page": "详情",
        "default": "标题、媒体图、元信息与动作排",
        "loading": "骨架优先保留已知字段",
        "empty": "不适用；不存在归错误，能力缺失归不可用",
        "offline": "缓存详情可读，播放 / 下载按可用性禁用",
        "error": "具体失败 + 重试 / 返回",
        "permission": "播放、下载、收藏、已看逐动作解释",
    },
    {
        "page": "下载",
        "default": "队列 / 离线库、聚合状态与叶子任务",
        "loading": "本地任务先上屏，元数据静默补齐",
        "empty": "当前页签无任务，不混同另一页签",
        "offline": "离线库与暂停 / 失败任务仍可管理",
        "error": "失败原因与重试；页面错误不覆盖任务真源",
        "permission": "401 / 403 无自动重试，给返回或权限说明",
    },
    {
        "page": "设置",
        "default": "当前值、依赖说明与即时保存结果",
        "loading": "动态选项加载但保留当前值",
        "empty": "无动态选项时说明与替代入口",
        "offline": "设备本地设置可改；服务器依赖项解释离线",
        "error": "读取 / 保存失败保留现场并重试",
        "permission": "管理员或平台依赖未满足时禁用并解释",
    },
    {
        "page": "启动 / 登录",
        "default": "可输入表单、焦点与系统返回",
        "loading": "发现 / 登录 / 验证中防重复提交",
        "empty": "无服务器列表时提供添加入口",
        "offline": "进入既有离线账户选择，显示始终可见的切换在线入口",
        "error": "网络、认证、证书、版本分别解释",
        "permission": "账户被拒或证书不信任给退出 / 换账户",
    },
    {
        "page": "播放器 / 阅读器",
        "default": "内容、传输控制、进度与当前能力",
        "loading": "buffering / 首页加载有可理解状态",
        "empty": "无字幕、无音轨等能力空态要说明",
        "offline": "本地内容继续；远端来源不可用时保留退出路径",
        "error": "失败给退出或重试；不得黑卡",
        "permission": "文件 / 媒体访问不可用时说明，不只变灰",
    },
    {
        "page": "主题设置",
        "default": "五主题预览、当前选择与外观模式",
        "loading": "预览即时；不阻塞当前可用主题",
        "empty": "无空页；动态色在旧系统显示可用性空态",
        "offline": "纯本地主题可切换，不依赖服务器",
        "error": "持久化失败回退并提示",
        "permission": "Android 12 以下动态色入口禁用并解释版本要求",
    },
]


ACCESSIBLE_NAMES = [
    ("A11Y-NAME-01", "搜索", "Button", "搜索", "无状态", "纯图标允许；24dp 图形 / 48dp 热区", "5.3.16.5"),
    ("A11Y-NAME-02", "返回", "Button", "返回", "无状态", "纯图标允许；按 RTL 镜像", "5.3.16.5"),
    ("A11Y-NAME-03", "关闭", "Button", "关闭", "无状态", "纯图标允许；危险关闭仍看上下文", "5.3.16.5"),
    ("A11Y-NAME-04", "侧栏", "Button", "打开侧栏", "collapsed / expanded", "纯图标允许；与“更多”分开", "RWD-02"),
    ("A11Y-NAME-05", "播放 / 暂停", "Button", "播放", "切换后名称改为“暂停”", "纯图标允许；状态文字同步", "5.3.16.5"),
    ("A11Y-NAME-06", "上一 / 下一", "Button", "上一首 / 下一首", "disabled 时同步", "纯图标允许；面板可附短标签", "5.3.16.5"),
    ("A11Y-NAME-07", "快退 / 快进", "Button", "快退 / 快进", "无状态", "面板显示短标签", "5.3.16.5"),
    ("A11Y-NAME-08", "全屏", "ToggleButton", "进入全屏", "checked=全屏", "纯图标允许；状态可读", "5.3.16.5"),
    ("A11Y-NAME-09", "收藏", "ToggleButton", "收藏", "checked / unchecked", "图标 + 短标签；不以颜色唯一表达", "5.3.16.5"),
    ("A11Y-NAME-10", "下载", "Button", "下载", "busy / accepted / failed", "图标 + 短标签；完成后文字更新", "5.3.16.5"),
    ("A11Y-NAME-11", "字幕", "Button", "字幕", "off / on / unavailable", "面板图标 + 短标签", "5.3.16.5"),
    ("A11Y-NAME-12", "音轨", "Button", "音轨", "current track name", "面板图标 + 短标签", "5.3.16.5"),
    ("A11Y-NAME-13", "筛选", "Button", "筛选", "active count", "compact 可 icon-only；平板附短标签", "5.3.16.5"),
    ("A11Y-NAME-14", "排序", "Button", "排序", "current order", "icon + 可见当前顺序", "5.3.16.5"),
    ("A11Y-NAME-15", "主题选择", "RadioButton", "主题：Lumen", "selected / checked", "名称与可见文本一致", "UX-037"),
    ("A11Y-NAME-16", "动态色", "Button", "系统动态色，需要 Android 12 或更高版本", "disabled + reason", "禁用原因可见且可读", "V2-D041"),
    ("A11Y-NAME-17", "删除下载", "Button", "删除下载：潮汐档案", "confirm required", "危险动作用文字确认", "5.3.16.5"),
    ("A11Y-NAME-18", "重试", "Button", "重试下载", "idle / busy", "busy 保留名称和状态", "UX-014"),
]


A11Y_CHECKS = [
    ("A11Y-01", "正文与小字号对比度", "WCAG 1.4.3 / UX-015", "计算值", "所有主题明暗正文与弱文字 pair ≥ 4.5:1", "PASS_STATIC"),
    ("A11Y-02", "焦点与非文字对比度", "WCAG 1.4.11 / D073", "计算值", "焦点环和必要控件边界 ≥ 3:1", "PASS_STATIC"),
    ("A11Y-03", "触控尺寸", "UX-009 / RWD-09", "布局值", "图中交互目标 48×48dp，图形 24–32dp", "PASS_STATIC_LAYOUT"),
    ("A11Y-04", "可访问名称", "D069 / 5.3.16.5", "名称表", "18/18 示例动作有名称、角色与状态策略", "PASS_STATIC_CONTRACT"),
    ("A11Y-05", "TalkBack 角色与状态", "WCAG 4.1.2 / UX-014", "语义合同", "Button / ToggleButton / RadioButton 与 checked / selected / busy 策略", "UNVERIFIED_RUNTIME"),
    ("A11Y-06", "键盘焦点顺序", "RWD-09", "静态顺序", "搜索 → 主行动 → 更多 → 导航，2dp 外环不被裁切", "UNVERIFIED_RUNTIME"),
    ("A11Y-07", "键盘 / 指针操作", "RWD-09", "静态合同", "Hover 只用于指针环境，键盘可完成同组动作", "UNVERIFIED_RUNTIME"),
    ("A11Y-08", "200% 动态字号", "RWD-08", "静态重排帧", "标题换行、元信息重排、主要求不缩字不截断", "UNVERIFIED_RUNTIME"),
    ("A11Y-09", "减弱动效", "UX-011 / D072", "静态矩阵", "主题状态过渡 150–300ms；减少动画为 0ms / 直接切换", "UNVERIFIED_RUNTIME"),
    ("A11Y-10", "不只依赖颜色", "WCAG 1.4.1", "状态板", "选中、错误、离线、权限同时使用文字 / 图标 / 形状", "PASS_STATIC_CONTRACT"),
    ("A11Y-11", "错误与权限可读", "UX-014", "状态矩阵", "指出对象、原因、下一步与恢复入口", "PASS_STATIC_CONTRACT"),
    ("A11Y-12", "字体与行高渲染", "UX-007", "设计意图", "MiSans / Literata / 阅读正文局部覆盖", "UNVERIFIED_RUNTIME"),
]


def hex_rgb(value: str) -> tuple[float, float, float]:
    value = value.lstrip("#")
    return tuple(int(value[index:index + 2], 16) / 255.0 for index in (0, 2, 4))


def luminance(value: str) -> float:
    channels = []
    for channel in hex_rgb(value):
        channels.append(channel / 12.92 if channel <= 0.04045 else ((channel + 0.055) / 1.055) ** 2.4)
    return 0.2126 * channels[0] + 0.7152 * channels[1] + 0.0722 * channels[2]


def contrast(foreground: str, background: str) -> float:
    high, low = sorted((luminance(foreground), luminance(background)), reverse=True)
    return round((high + 0.05) / (low + 0.05), 2)


def esc(value: object) -> str:
    return (str(value).replace("&", "&amp;").replace("<", "&lt;")
            .replace(">", "&gt;").replace('"', "&quot;"))


class Svg:
    def __init__(self, width: int, height: int, title: str, desc: str, background: str):
        self.width = width
        self.height = height
        self.parts = [
            f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" '
            f'viewBox="0 0 {width} {height}" role="img" aria-labelledby="title desc" lang="zh-CN">',
            f"<title id=\"title\">{esc(title)}</title>",
            f"<desc id=\"desc\">{esc(desc)}</desc>",
            f'<rect width="{width}" height="{height}" fill="{background}"/>',
        ]

    def raw(self, value: str) -> None:
        self.parts.append(value)

    def rect(self, x: float, y: float, width: float, height: float, fill: str,
             rx: float = 0, stroke: str = "none", stroke_width: float = 0,
             opacity: float = 1, dash: str = "") -> None:
        dash_attr = f' stroke-dasharray="{dash}"' if dash else ""
        self.raw(
            f'<rect x="{x:.2f}" y="{y:.2f}" width="{width:.2f}" height="{height:.2f}" '
            f'rx="{rx:.2f}" fill="{fill}" fill-opacity="{opacity}" stroke="{stroke}" '
            f'stroke-width="{stroke_width}"{dash_attr}/>'
        )

    def line(self, x1: float, y1: float, x2: float, y2: float, stroke: str,
             width: float = 1, opacity: float = 1, dash: str = "") -> None:
        dash_attr = f' stroke-dasharray="{dash}"' if dash else ""
        self.raw(
            f'<line x1="{x1:.2f}" y1="{y1:.2f}" x2="{x2:.2f}" y2="{y2:.2f}" '
            f'stroke="{stroke}" stroke-width="{width}" stroke-opacity="{opacity}"{dash_attr}/>'
        )

    def circle(self, cx: float, cy: float, radius: float, fill: str,
               opacity: float = 1, stroke: str = "none", stroke_width: float = 0) -> None:
        self.raw(
            f'<circle cx="{cx:.2f}" cy="{cy:.2f}" r="{radius:.2f}" fill="{fill}" '
            f'fill-opacity="{opacity}" stroke="{stroke}" stroke-width="{stroke_width}"/>'
        )

    def path(self, data: str, fill: str = "none", stroke: str = "none",
             stroke_width: float = 1, opacity: float = 1, linecap: str = "round") -> None:
        self.raw(
            f'<path d="{data}" fill="{fill}" fill-opacity="{opacity}" stroke="{stroke}" '
            f'stroke-width="{stroke_width}" stroke-linecap="{linecap}" stroke-linejoin="round"/>'
        )

    def text(self, x: float, y: float, value: object, size: float = 18,
             fill: str = "#FFFFFF", weight: int = 400, anchor: str = "start",
             family: str = "'MiSans','Microsoft YaHei UI',sans-serif",
             opacity: float = 1, letter_spacing: float = 0) -> None:
        self.raw(
            f'<text x="{x:.2f}" y="{y:.2f}" fill="{fill}" fill-opacity="{opacity}" '
            f'font-family="{family}" font-size="{size}" font-weight="{weight}" '
            f'text-anchor="{anchor}" letter-spacing="{letter_spacing}">{esc(value)}</text>'
        )

    def multiline(self, x: float, y: float, lines: list[str], size: float = 16,
                  fill: str = "#FFFFFF", weight: int = 400, gap: float | None = None,
                  family: str = "'MiSans','Microsoft YaHei UI',sans-serif") -> None:
        line_gap = gap if gap is not None else size * 1.45
        for index, line in enumerate(lines):
            self.text(x, y + index * line_gap, line, size, fill, weight, family=family)

    def pill(self, x: float, y: float, width: float, label: str, fill: str,
             text_fill: str, stroke: str = "none") -> None:
        self.rect(x, y, width, 34, fill, 17, stroke, 1.5)
        self.text(x + width / 2, y + 23, label, 14, text_fill, 500, "middle")

    def save(self, path: Path) -> None:
        self.parts.append("</svg>")
        utf8(path, "\n".join(self.parts))


BOARD_BG = "#090C11"
BOARD_TEXT = "#F3F5F7"
BOARD_MUTED = "#AAB4C0"
BOARD_LINE = "#303B48"


def header(svg: Svg, kicker: str, title: str, subtitle: str, right: str) -> None:
    svg.text(48, 42, kicker, 15, "#9FCDBB", 700, letter_spacing=1.1)
    svg.text(48, 78, title, 34, BOARD_TEXT, 700)
    svg.text(48, 106, subtitle, 16, BOARD_MUTED)
    svg.text(1552, 46, right, 14, BOARD_MUTED, 500, "end")


def poster_art(svg: Svg, x: float, y: float, w: float, h: float,
               palette: dict, ident: str, compact: bool = False) -> None:
    svg.raw(
        f'<defs><linearGradient id="poster-{ident}" x1="0" y1="0" x2="1" y2="1">'
        f'<stop offset="0" stop-color="{palette["raised"]}"/>'
        f'<stop offset=".58" stop-color="{palette["bg"]}"/>'
        f'<stop offset="1" stop-color="{palette["film"]}" stop-opacity=".46"/>'
        f'</linearGradient></defs>'
    )
    svg.rect(x, y, w, h, f"url(#poster-{ident})", 10, palette["outline"], 1)
    svg.circle(x + w * 0.72, y + h * 0.30, max(16, h * 0.18), palette["film"], 0.23)
    svg.path(
        f"M {x + w * .12:.1f} {y + h * .70:.1f} "
        f"C {x + w * .28:.1f} {y + h * .36:.1f}, {x + w * .46:.1f} {y + h * .90:.1f}, {x + w * .88:.1f} {y + h * .42:.1f}",
        stroke=palette["book"], stroke_width=4 if not compact else 2.5, opacity=0.90
    )
    svg.path(
        f"M {x + w * .22:.1f} {y + h * .81:.1f} "
        f"C {x + w * .38:.1f} {y + h * .59:.1f}, {x + w * .55:.1f} {y + h * .72:.1f}, {x + w * .78:.1f} {y + h * .55:.1f}",
        stroke=palette["text"], stroke_width=1.5 if not compact else 1, opacity=0.62
    )
    svg.path(
        f"M {x + w * .18:.1f} {y + h * .22:.1f} L {x + w * .34:.1f} {y + h * .42:.1f} "
        f"L {x + w * .28:.1f} {y + h * .55:.1f}",
        stroke=palette["focus"], stroke_width=1.5 if not compact else 1, opacity=0.75
    )


def nav_icon(svg: Svg, x: float, y: float, kind: str, color: str, selected: bool = False) -> None:
    sw = 2 if selected else 1.75
    if kind == "home":
        svg.path(f"M {x} {y+10} L {x+9} {y+2} L {x+18} {y+10} M {x+2} {y+9} V {y+18} H {x+16} V {y+9}",
                 stroke=color, stroke_width=sw)
    elif kind == "video":
        svg.rect(x + 1, y + 3, 16, 14, "none", 2, color, sw)
        svg.path(f"M {x+7} {y+7} L {x+13} {y+10} L {x+7} {y+13} Z", stroke=color, stroke_width=sw)
    elif kind == "music":
        svg.path(f"M {x+7} {y+15} V {y+4} L {x+16} {y+2} V {y+13}", stroke=color, stroke_width=sw)
        svg.circle(x + 4, y + 16, 3, color)
        svg.circle(x + 13, y + 14, 3, color)
    elif kind == "book":
        svg.path(f"M {x+2} {y+4} H {x+8} Q {x+9} {y+4} {x+9} {y+6} V {y+17} H {x+4} Q {x+2} {y+17} {x+2} {y+15} Z",
                 stroke=color, stroke_width=sw)
        svg.path(f"M {x+16} {y+4} H {x+10} Q {x+9} {y+4} {x+9} {y+6} V {y+17} H {x+14} Q {x+16} {y+17} {x+16} {y+15} Z",
                 stroke=color, stroke_width=sw)
    elif kind == "search":
        svg.circle(x + 8, y + 8, 5.5, "none", 1, color, sw)
        svg.line(x + 12, y + 12, x + 18, y + 18, color, sw)
    elif kind == "more":
        for dx in (2, 9, 16):
            svg.circle(x + dx, y + 10, 1.6, color)


def render_theme_frame(key: str, theme: dict) -> Path:
    p = theme["dark"]
    lp = theme["light"]
    svg = Svg(1600, 1000, f"{theme['label']} 视觉方向帧",
              f"{theme['label']} 的深色主预览、明暗对照、色板、材质、圆角与无障碍自检。",
              BOARD_BG)
    header(svg, f"G1-S5 · 主题 {theme['order']}/5 · 待用户确认",
           f"{theme['label']} / {theme['cn']}", theme["tagline"],
           "V2-Q-G1-04 · Q04 OPEN · G1 NOT PASSED")

    app_x, app_y, app_w, app_h = 48, 132, 930, 794
    svg.rect(app_x, app_y, app_w, app_h, p["bg"], theme["radius"] + 8, BOARD_LINE, 1)
    rail_w = 86
    svg.rect(app_x, app_y, rail_w, app_h, p["panel"], theme["radius"] + 8, p["outline"], theme["border"])
    svg.text(app_x + 22, app_y + 50, "A", 26, p["book"], 700)
    nav_items = [("home", "首页"), ("video", "视频"), ("music", "音乐"), ("book", "书架")]
    for index, (kind, label) in enumerate(nav_items):
        item_y = app_y + 105 + index * 86
        selected = index == 0
        if selected:
            svg.rect(app_x + 12, item_y - 15, 62, 54, p["raised"], 10, p["book"], 1)
        nav_icon(svg, app_x + 30, item_y, kind, p["book"] if selected else p["text2"], selected)
        svg.text(app_x + 43, item_y + 42, label, 12, p["book"] if selected else p["text2"], 500, "middle")

    content_x = app_x + rail_w + 28
    content_w = app_w - rail_w - 56
    svg.text(content_x, app_y + 54, "今晚继续", 28, p["text"], 700)
    svg.text(content_x + content_w - 54, app_y + 48, "⌕", 30, p["text2"], 400, "middle")
    svg.circle(content_x + content_w - 54, app_y + 39, 22, "none", 1, p["outline"], 1)

    hero_x, hero_y, hero_w, hero_h = content_x, app_y + 86, 510, 306
    svg.rect(hero_x, hero_y, hero_w, hero_h, p["panel"], theme["radius"], p["outline"], theme["border"])
    poster_art(svg, hero_x + hero_w - 188, hero_y + 22, 164, 262, p, key + "-hero")
    svg.text(hero_x + 26, hero_y + 46, "继续阅读", 15, p["book"], 700)
    svg.text(hero_x + 26, hero_y + 92, "潮汐档案", 34, p["text"], 700,
             family="'Literata','Noto Serif SC',serif" if key == "nocturne" else
             "'MiSans','Microsoft YaHei UI',sans-serif")
    svg.text(hero_x + 26, hero_y + 124, "第 12 章 · 62%", 16, p["text2"], 500)
    svg.rect(hero_x + 26, hero_y + 222, 250, 6, p["outline"], 3)
    svg.rect(hero_x + 26, hero_y + 222, 155, 6, p["book"], 3)
    svg.rect(hero_x + 26, hero_y + 252, 132, 42, p["book"], theme["radius"] - 2)
    svg.text(hero_x + 92, hero_y + 279, "继续阅读", 15, p["onAccent"], 700, "middle")
    svg.text(hero_x + 176, hero_y + 279, "大纲", 15, p["text2"], 500, "middle")

    recent_x, recent_y, recent_w = hero_x + 530, hero_y, 246
    svg.text(recent_x, recent_y + 4, "最近内容", 18, p["text"], 700)
    recent_items = [("雨停之后", "视频", p["film"]), ("远岸电台", "音乐", p["music"]), ("海边札记", "书籍", p["book"])]
    for index, (title, domain, accent) in enumerate(recent_items):
        item_y = recent_y + 26 + index * 88
        svg.rect(recent_x, item_y, recent_w, 72, p["panel"], theme["radius"] - 2, p["outline"], 1)
        svg.rect(recent_x + 12, item_y + 12, 52, 48, p["raised"], 8)
        svg.circle(recent_x + 38, item_y + 36, 12, accent, 0.28)
        svg.text(recent_x + 78, item_y + 31, title, 15, p["text"], 500)
        svg.text(recent_x + 78, item_y + 53, domain, 13, p["text2"], 400)
        svg.path(f"M {recent_x+recent_w-23} {item_y+28} L {recent_x+recent_w-17} {item_y+35} L {recent_x+recent_w-23} {item_y+42}",
                 stroke=p["text2"], stroke_width=1.5)

    quick_y = app_y + 438
    quick_items = [("视频", p["film"], "▷"), ("音乐", p["music"], "♪"), ("书籍", p["book"], "▤")]
    for index, (label, accent, glyph) in enumerate(quick_items):
        item_x = content_x + index * (content_w - 24) / 3
        item_w = (content_w - 48) / 3
        svg.rect(item_x, quick_y, item_w, 82, p["panel"], theme["radius"] - 2, p["outline"], 1)
        svg.circle(item_x + 30, quick_y + 41, 18, accent, 0.18, accent, 1)
        svg.text(item_x + 30, quick_y + 48, glyph, 20, accent, 700, "middle")
        svg.text(item_x + 60, quick_y + 38, label, 16, p["text"], 700)
        svg.text(item_x + 60, quick_y + 58, "打开", 13, p["text2"], 400)

    reader_y = app_y + 558
    svg.rect(content_x, reader_y, content_w, 178, p["panel"], theme["radius"], p["outline"], theme["border"])
    svg.text(content_x + 22, reader_y + 32, "阅读正文局部主题", 18, p["text"], 700)
    svg.rect(content_x + 22, reader_y + 52, content_w - 44, 104, "#FBF6EC", 8, "#C9B99F", 1)
    svg.text(content_x + 44, reader_y + 88, "海面在午夜以后变得安静。", 19, "#241D17", 400,
             family="'Noto Serif SC','SimSun',serif")
    svg.text(content_x + 44, reader_y + 120, "只有灯塔的节奏仍在提醒方向。", 19, "#241D17", 400,
             family="'Noto Serif SC','SimSun',serif")
    svg.rect(content_x + content_w - 118, reader_y + 74, 76, 34, "#E9DDC8", 17)
    svg.text(content_x + content_w - 80, reader_y + 96, "纸色", 13, "#604B2C", 700, "middle")
    svg.text(content_x + 22, reader_y + 174 - 5, "外框、按钮与弹层仍服从 App 主题", 13, p["text2"], 500)

    spec_x, spec_y, spec_w = 1010, 132, 542
    svg.text(spec_x, spec_y + 8, "方向语法", 16, p["book"], 700)
    svg.text(spec_x, spec_y + 48, theme["material"], 19, BOARD_TEXT, 500)
    svg.text(spec_x, spec_y + 77, theme["type"], 16, BOARD_MUTED)

    for index, (label, palette) in enumerate((("深色", p), ("浅色", lp))):
        card_x = spec_x + index * 270
        svg.rect(card_x, spec_y + 104, 252, 146, palette["bg"], theme["radius"], palette["outline"], theme["border"])
        svg.text(card_x + 16, spec_y + 130, label, 14, palette["text2"], 700)
        svg.text(card_x + 16, spec_y + 164, "继续阅读", 22, palette["text"], 700,
                 family="'Literata','Noto Serif SC',serif" if key == "nocturne" else
                 "'MiSans','Microsoft YaHei UI',sans-serif")
        svg.rect(card_x + 16, spec_y + 190, 156, 34, palette["panel"], 8, palette["outline"], 1)
        svg.rect(card_x + 16, spec_y + 190, 106, 34, palette["book"], 8)
        svg.text(card_x + 69, spec_y + 212, "62%", 13, palette["onAccent"], 700, "middle")

    svg.text(spec_x, spec_y + 292, "色板与角色", 16, p["book"], 700)
    roles = [
        ("主文字", "text"), ("弱文字", "text2"), ("影视", "film"),
        ("音乐", "music"), ("书籍", "book"), ("描边", "outline"),
    ]
    for index, (label, role) in enumerate(roles):
        column = index % 3
        row = index // 3
        sw_x = spec_x + column * 174
        sw_y = spec_y + 316 + row * 72
        svg.rect(sw_x, sw_y, 154, 56, p[role], 8, p["outline"], 1)
        label_color = p["onAccent"] if role in ("film", "music", "book") else p["text"]
        svg.text(sw_x + 10, sw_y + 21, label, 13, label_color, 700)
        svg.text(sw_x + 10, sw_y + 42, p[role].upper(), 12, label_color, 500)

    dark_min = min(
        contrast(p["text"], p["panel"]), contrast(p["text2"], p["panel"]),
        contrast(p["film"], p["panel"]), contrast(p["music"], p["panel"]),
        contrast(p["book"], p["panel"]), contrast(p["outline"], p["panel"]),
    )
    light_min = min(
        contrast(lp["text"], lp["panel"]), contrast(lp["text2"], lp["panel"]),
        contrast(lp["film"], lp["panel"]), contrast(lp["music"], lp["panel"]),
        contrast(lp["book"], lp["panel"]), contrast(lp["outline"], lp["panel"]),
    )
    svg.text(spec_x, spec_y + 502, "材质、形状与无障碍", 16, p["book"], 700)
    details = [
        f"基础圆角 {theme['radius']}dp · 图标轮廓 {theme['border']:.2f}dp",
        f"主题状态过渡 {theme['motion']}ms；减少动画直接切换",
        f"静态最低关键对比：深色 {dark_min:.2f}:1 · 浅色 {light_min:.2f}:1",
        "触控目标 48×48dp；焦点环 2dp；名称与角色必须保留",
    ]
    svg.multiline(spec_x, spec_y + 536, details, 16, BOARD_TEXT, 400, 31)
    for index, label in enumerate(("48dp", "2dp focus", "200% text", "Reduce Motion")):
        svg.pill(spec_x + index * 130, spec_y + 674, 118, label, p["panel"], p["text"], p["outline"])

    invariant_y = 852
    svg.rect(48, invariant_y, 1504, 102, "#111720", 16, BOARD_LINE, 1)
    svg.text(70, invariant_y + 31, "跨主题不变量", 16, "#9FCDBB", 700)
    svg.text(70, invariant_y + 62,
             "IA / 入口顺序 / 状态语义 / 可访问名称 / 48dp / 错误与权限文案含义不随主题变化。",
             17, BOARD_TEXT, 500)
    svg.text(70, invariant_y + 84,
             "主题只改变颜色、材质、字阶表现、受控形状、图标完成度与动效强度；不改变动作含义。",
             14, BOARD_MUTED)

    output = ASSETS / "themes" / f"THEME-{key.upper()}.svg"
    svg.save(output)
    return output


def draw_mini_interaction(svg: Svg, x: float, y: float, w: float, h: float,
                          interaction: dict, stage: int, palette: dict) -> None:
    svg.rect(x, y, w, h, palette["bg"], 18, palette["outline"], 1)
    svg.rect(x + 14, y + 14, w - 28, 28, palette["panel"], 10, palette["outline"], 1)
    svg.text(x + 28, y + 33, interaction["title"], 13, palette["text"], 700)
    svg.circle(x + w - 32, y + 28, 8, palette["book"], 0.3)

    inner_x, inner_y, inner_w = x + 18, y + 58, w - 36
    accent = palette["book"]
    if stage == 3:
        accent = palette["success"]
    elif stage == 4:
        accent = palette["warning"]

    slug = interaction["slug"]
    if slug in ("home", "media-video-detail", "playback-controls"):
        svg.rect(inner_x, inner_y, inner_w, 152, palette["panel"], 12, palette["outline"], 1)
        poster_art(svg, inner_x + inner_w - 92, inner_y + 12, 76, 108, palette, interaction["id"] + str(stage), True)
        svg.text(inner_x + 14, inner_y + 28, "潮汐档案", 18, palette["text"], 700)
        svg.text(inner_x + 14, inner_y + 50, "继续 · 62%", 13, palette["text2"], 500)
        svg.rect(inner_x + 14, inner_y + 120, 132, 28, accent, 8)
        svg.text(inner_x + 80, inner_y + 139, "继续" if slug == "home" else "准备播放", 12,
                 palette["onAccent"], 700, "middle")
    elif slug in ("music-queue", "downloads"):
        for index in range(4):
            row_y = inner_y + index * 58
            svg.rect(inner_x, row_y, inner_w, 48, palette["panel"], 9, palette["outline"], 1)
            svg.circle(inner_x + 24, row_y + 24, 12, palette["music"] if slug == "music-queue" else accent, 0.25)
            svg.text(inner_x + 46, row_y + 23, ["远岸电台", "潮汐档案", "夜行时刻", "海风来信"][index],
                     13, palette["text"], 500)
            svg.text(inner_x + inner_w - 12, row_y + 23,
                     ["下一首", "42%", "暂停", "失败"][index] if slug == "music-queue" else
                     ["等待", "42%", "暂停", "失败"][index],
                     11, accent if stage >= 3 else palette["text2"], 500, "end")
    elif slug in ("bookshelf-reader",):
        svg.rect(inner_x, inner_y, inner_w, 210, "#FBF6EC", 10, "#C9B99F", 1)
        svg.text(inner_x + 18, inner_y + 38, "第 12 章", 14, "#6A5538", 700)
        svg.multiline(inner_x + 18, inner_y + 72,
                      ["海面在午夜以后变得安静。", "只有灯塔的节奏仍在", "提醒方向。"],
                      14, "#241D17", 400, 26, family="'Noto Serif SC','SimSun',serif")
        svg.rect(inner_x + 18, inner_y + 156, inner_w - 36, 34, palette["raised"], 8, palette["outline"], 1)
        svg.text(inner_x + 30, inner_y + 178, "正文纸色 · 外框服从 App 主题", 11, palette["text2"], 500)
    elif slug == "search":
        svg.rect(inner_x, inner_y, inner_w, 38, palette["panel"], 19, accent, 1.5)
        svg.text(inner_x + 16, inner_y + 25, "搜索书籍 · 潮汐", 13, palette["text"], 500)
        for index in range(3):
            row_y = inner_y + 58 + index * 52
            svg.rect(inner_x, row_y, inner_w, 42, palette["panel"], 8, palette["outline"], 1)
            svg.text(inner_x + 12, row_y + 26, ["潮汐档案", "潮汐与灯塔", "潮汐手记"][index],
                     13, palette["text"], 500)
        if stage >= 3:
            svg.text(inner_x + 12, inner_y + 236, "无结果" if stage == 3 else "搜索失败 · 重试",
                     13, accent, 700)
    elif slug == "settings-theme":
        for index in range(5):
            row_y = inner_y + index * 46
            svg.rect(inner_x, row_y, inner_w, 38, palette["panel"], 8, palette["outline"], 1)
            svg.rect(inner_x + 10, row_y + 9, 20, 20, [palette["film"], palette["music"], palette["book"],
                                                      palette["text2"], palette["outline"]][index], 5)
            svg.text(inner_x + 42, row_y + 25,
                     ["Prism", "Lumen", "Nocturne", "Monolith", "系统动态色"][index],
                     12, palette["text"], 500)
            if index == 1:
                svg.text(inner_x + inner_w - 12, row_y + 25, "✓ 当前", 11, accent, 700, "end")
    elif slug == "syncplay-candidate":
        svg.rect(inner_x + 6, inner_y + 14, inner_w - 12, 174, palette["panel"], 12, palette["outline"], 1)
        svg.text(inner_x + 22, inner_y + 44, "同步观看", 18, palette["text"], 700)
        svg.text(inner_x + 22, inner_y + 71, "候选能力 · 需协议与双端证据", 12, palette["text2"], 500)
        svg.rect(inner_x + 22, inner_y + 96, inner_w - 56, 36, accent, 8)
        svg.text(inner_x + inner_w / 2 - 6, inner_y + 120, "创建会话", 12,
                 palette["onAccent"], 700, "middle")
        svg.text(inner_x + 22, inner_y + 160, "离线 / 403 / 不支持 → 普通播放", 11,
                 palette["warning"], 700)

    svg.text(x + 24, y + h - 56, ["入口", "主要动作", "反馈 / 状态", "失败 / 恢复"][stage - 1],
             12, accent, 700)
    svg.rect(x + 24, y + h - 42, w - 48, 1, palette["outline"])


def render_interaction(item: dict) -> Path:
    svg = Svg(1600, 900, f"{item['id']} {item['title']}交互复核",
              f"{item['title']}的入口、主要动作、反馈和失败恢复四段低保真复核。",
              BOARD_BG)
    header(svg, f"G1-S5 · 交互 {item['id'][-2:]}/09 · 文字低保真",
           item["title"], item["entry"], "STATIC · RUNTIME UNVERIFIED")
    palette = THEMES["prism"]["dark"]
    stages = [item["entry"], item["action"], item["feedback"], item["recovery"]]
    labels = ["1 入口", "2 主要动作", "3 反馈 / 状态", "4 失败 / 恢复"]
    for index in range(4):
        card_x = 48 + index * 380
        svg.rect(card_x, 134, 364, 650, "#111720", 18, BOARD_LINE, 1)
        svg.text(card_x + 20, 166, labels[index], 17, "#9FCDBB", 700)
        draw_mini_interaction(svg, card_x + 20, 188, 324, 516, item, index + 1, palette)
        svg.multiline(card_x + 20, 742, [stages[index][line:line + 21] for line in range(0, len(stages[index]), 21)][:3],
                      13, BOARD_MUTED, 400, 19)
    summary_y = 808
    svg.rect(48, summary_y, 1504, 62, "#111720", 14, BOARD_LINE, 1)
    svg.text(68, summary_y + 25, "证据边界", 14, "#9FCDBB", 700)
    svg.text(68, summary_y + 48,
             "本帧仅证明方向与状态分支可审阅；入口可达、TalkBack、焦点、真实失败和后端一致性均未执行。",
             15, BOARD_TEXT, 500)
    path = ASSETS / "interactions" / f"{item['id']}-{item['slug'].upper()}.svg"
    svg.save(path)
    return path


def calculate_rwd(width: int, nav: str, rail: int, pad: int, gap: int, min_card: int) -> dict:
    available = width - rail - pad * 2
    content_width = min(1280, available) if nav == "expanded-rail" else available
    columns = max(1, int((content_width + gap) // (min_card + gap)))
    card_width = round((content_width - gap * (columns - 1)) / columns, 2)
    return {
        "contentWidth": content_width,
        "columns": columns,
        "cardWidth": card_width,
        "horizontalOverflow": max(0, content_width - available),
        "residualOuterSpace": max(0, available - content_width),
    }


def draw_rwd_nav(svg: Svg, row: dict, palette: dict) -> None:
    _, label, width, height, nav, rail, pad, gap, min_card, _ = row
    if nav == "bottom":
        nav_y = height - 76
        svg.rect(0, nav_y, width, 76, palette["panel"], 0, palette["outline"], 1)
        labels = [("home", "首页"), ("video", "视频"), ("music", "音乐"), ("book", "书架")]
        for index, (kind, text) in enumerate(labels):
            cx = (index + 0.5) * width / 4
            nav_icon(svg, cx - 9, nav_y + 12, kind, palette["book"] if index == 0 else palette["text2"], index == 0)
            svg.text(cx, nav_y + 52, text, 11, palette["book"] if index == 0 else palette["text2"], 700, "middle")
        svg.rect(0, height - 18, width, 18, palette["bg"])
    else:
        svg.rect(0, 0, rail, height, palette["panel"], 0, palette["outline"], 1)
        expanded = nav == "expanded-rail"
        svg.text(rail / 2, 47, "A", 24, palette["book"], 700, "middle")
        labels = [("home", "首页"), ("video", "视频"), ("music", "音乐"), ("book", "书架")]
        for index, (kind, text) in enumerate(labels):
            y = 100 + index * 68
            if index == 0:
                svg.rect(8, y - 12, rail - 16, 48, palette["raised"], 10)
            nav_icon(svg, 24 if not expanded else 22, y, kind, palette["book"] if index == 0 else palette["text2"], index == 0)
            if expanded:
                svg.text(54, y + 15, text, 14, palette["book"] if index == 0 else palette["text2"], 700)


def render_rwd(row: tuple) -> Path:
    rid, label, width, height, nav, rail, pad, gap, min_card, sources = row
    calc = calculate_rwd(width, nav, rail, pad, gap, min_card)
    p = THEMES["prism"]["dark"]
    svg = Svg(width, height, f"{rid} {label} {width}dp",
              f"{label} 窗口的导航、边距、{calc['columns']} 列布局和内容宽度。", p["bg"])
    draw_rwd_nav(svg, row, p)
    content_x = rail + pad
    content_y = 34
    content_w = calc["contentWidth"]
    svg.text(content_x, 45, "今晚继续", 24 if width < 600 else 30, p["text"], 700)
    nav_icon(svg, width - pad - 20, 28, "search", p["text2"])

    hero_h = 190 if width < 600 else 230
    svg.rect(content_x, 70, content_w, hero_h, p["panel"], 14, p["outline"], 1)
    poster_w = min(150, content_w * 0.32)
    poster_art(svg, content_x + content_w - poster_w - 18, 88, poster_w, hero_h - 36, p, rid, True)
    text_room = content_w - poster_w - 54
    svg.text(content_x + 20, 104, "继续阅读", 12, p["book"], 700)
    svg.text(content_x + 20, 142, "潮汐档案", 22 if width < 600 else 30, p["text"], 700)
    title_sub = "第 12 章 · 62%" if width > 420 else "62% · 第 12 章"
    svg.text(content_x + 20, 170, title_sub, 12 if width < 600 else 15, p["text2"], 500)
    if content_w > 360:
        svg.rect(content_x + 20, 212, min(text_room, 220), 38, p["book"], 10)
        svg.text(content_x + 20 + min(text_room, 220) / 2, 237, "继续阅读", 12, p["onAccent"], 700, "middle")

    section_y = 70 + hero_h + 34
    svg.text(content_x, section_y, "最近内容", 18, p["text"], 700)
    svg.text(content_x + content_w, section_y, f"{calc['columns']} 列 · {calc['cardWidth']:.0f}dp", 11, p["text2"], 500, "end")
    card_top = section_y + 20
    card_h = 120 if width < 600 else 138
    domains = [("雨停之后", p["film"]), ("远岸电台", p["music"]), ("海边札记", p["book"]),
               ("灯塔来信", p["film"]), ("晨雾唱片", p["music"]), ("纸上群岛", p["book"]), ("风与档案", p["film"])]
    for index in range(calc["columns"]):
        card_x = content_x + index * (calc["cardWidth"] + gap)
        svg.rect(card_x, card_top, calc["cardWidth"], card_h, p["panel"], 10, p["outline"], 1)
        art_h = 62 if width < 600 else 82
        svg.rect(card_x + 8, card_top + 8, calc["cardWidth"] - 16, art_h, p["raised"], 7)
        svg.circle(card_x + calc["cardWidth"] * 0.67, card_top + 12 + art_h * 0.45, art_h * 0.22,
                   domains[index][1], 0.34)
        svg.text(card_x + 10, card_top + art_h + 28, domains[index][0],
                 10 if width < 600 else 13, p["text"], 700)
        svg.text(card_x + 10, card_top + art_h + 48, ["视频", "音乐", "书籍", "视频", "音乐", "书籍", "视频"][index],
                 9 if width < 600 else 11, p["text2"], 500)

    if height - 120 > card_top + card_h + 40:
        quick_y = card_top + card_h + 28
        qw = (content_w - gap * 2) / 3
        for index, (title, accent) in enumerate([("视频", p["film"]), ("音乐", p["music"]), ("书籍", p["book"])]):
            qx = content_x + index * (qw + gap)
            svg.rect(qx, quick_y, qw, 58, p["panel"], 10, p["outline"], 1)
            svg.circle(qx + 22, quick_y + 29, 12, accent, 0.25)
            svg.text(qx + 42, quick_y + 34, title, 12, p["text"], 700)
    svg.text(pad, height - (92 if nav == "bottom" else 16),
             f"{label} · {width}dp · padding {pad}dp · {calc['columns']} columns",
             10, p["text2"], 500)
    path = ASSETS / "rwd" / f"{rid}.svg"
    svg.save(path)
    return path


def render_fold() -> Path:
    width, height = 700, 900
    p = THEMES["prism"]["dark"]
    svg = Svg(width, height, "RWD-FOLD-700 折痕与窗口变化",
              "700dp 窗口、72dp 收轨、24dp 铰链安全带和左右两栏内容。", p["bg"])
    svg.rect(0, 0, 72, height, p["panel"], 0, p["outline"], 1)
    for index, (kind, label) in enumerate([("home", "首页"), ("video", "视频"), ("music", "音乐"), ("book", "书架")]):
        nav_icon(svg, 25, 96 + index * 68, kind, p["book"] if index == 0 else p["text2"], index == 0)
    svg.rect(338, 0, 24, height, "#0A0D12", 0)
    for hatch_y in range(16, height, 28):
        svg.line(340, hatch_y, 360, hatch_y + 12, p["outline"], 1, 0.55)
    svg.text(350, 42, "铰链", 10, p["text2"], 700, "middle")
    left_x, right_x = 96, 378
    svg.text(left_x, 54, "潮汐档案", 24, p["text"], 700)
    svg.rect(left_x, 82, 218, 330, p["panel"], 12, p["outline"], 1)
    poster_art(svg, left_x + 28, 110, 162, 210, p, "fold", True)
    svg.text(left_x + 20, 352, "继续阅读", 13, p["text2"], 500)
    svg.rect(left_x + 20, 368, 132, 32, p["book"], 8)
    svg.text(left_x + 86, 389, "62%", 12, p["onAccent"], 700, "middle")
    svg.text(right_x, 54, "选集", 20, p["text"], 700)
    for index in range(5):
        row_y = 82 + index * 58
        svg.rect(right_x, row_y, 286, 46, p["panel"], 8, p["outline"], 1)
        svg.text(right_x + 12, row_y + 28, f"第 {index+8} 集", 13, p["text"], 500)
    svg.rect(right_x, 388, 286, 86, p["panel"], 10, p["outline"], 1)
    svg.text(right_x + 14, 418, "窗口变化保留状态", 13, p["book"], 700)
    svg.text(right_x + 14, 442, "滚动 · 选择 · 进行中操作", 12, p["text2"], 500)
    svg.text(96, height - 22, "RWD-06 · 内容不跨铰链 · 主按钮不进入遮挡区", 11, p["text2"], 500)
    path = ASSETS / "rwd" / "RWD-FOLD.svg"
    svg.save(path)
    return path


def render_state_components() -> Path:
    svg = Svg(1600, 1000, "共享组件状态板",
              "默认、按下、聚焦、禁用、加载、空、错误、选中、Hover 与离线的静态状态证据。", BOARD_BG)
    header(svg, "G1-S5 · 状态 1/3 · 共享组件", "组件状态板",
           "同一动作跨主题保持语义；颜色不是唯一通道。", "STATIC · RUNTIME UNVERIFIED")
    p = THEMES["prism"]["dark"]
    states = [
        ("默认", "名称 + 角色 + 清晰边界", p["text"], p["panel"]),
        ("按下", "输入后立即变化；请求可防重", p["book"], p["raised"]),
        ("聚焦", "2dp 外环；顺序按视觉流", p["focus"], p["panel"]),
        ("禁用", "不可触发并给原因", p["text2"], p["raised"]),
        ("加载", "保留名称 + busy + 单提交", p["book"], p["panel"]),
        ("空", "确认无数据；说明下一步", p["text"], p["panel"]),
        ("错误", "原因 + 唯一重试 / 返回", p["error"], p["panel"]),
        ("选中 / 当前", "填充 / 勾选 / 文本至少两项", p["success"], p["raised"]),
        ("Hover", "仅指针环境；不改位移 / 缩放", p["info"], p["panel"]),
        ("离线 / 部分", "缓存可用；成功区块保留", p["warning"], p["panel"]),
    ]
    for index, (name, rule, accent, fill) in enumerate(states):
        col = index % 5
        row = index // 5
        x = 48 + col * 306
        y = 148 + row * 388
        svg.rect(x, y, 286, 354, "#111720", 16, BOARD_LINE, 1)
        svg.text(x + 18, y + 31, name, 18, BOARD_TEXT, 700)
        svg.text(x + 18, y + 55, f"S5-ST-{index+1:02d}", 11, BOARD_MUTED, 500)
        svg.rect(x + 34, y + 88, 218, 66, fill, 12, accent if name == "聚焦" else p["outline"], 2 if name == "聚焦" else 1)
        if name == "加载":
            svg.circle(x + 108, y + 121, 12, "none", 1, accent, 3)
            svg.path(f"M {x+108} {y+109} A 12 12 0 0 1 {x+120} {y+121}", stroke=accent, stroke_width=3)
            svg.text(x + 136, y + 127, "处理中", 14, p["text"], 700)
        elif name == "错误":
            svg.text(x + 55, y + 131, "!", 24, accent, 700, "middle")
            svg.text(x + 86, y + 127, "下载失败", 14, p["text"], 700)
        elif name == "选中 / 当前":
            svg.text(x + 55, y + 129, "✓", 22, accent, 700, "middle")
            svg.text(x + 86, y + 127, "当前主题", 14, p["text"], 700)
        elif name == "离屏":
            svg.text(x + 108, y + 127, "缓存可用", 14, p["text"], 700, "middle")
        elif name == "离线 / 部分":
            svg.text(x + 53, y + 128, "↯", 23, accent, 700, "middle")
            svg.text(x + 82, y + 127, "离线内容", 14, p["text"], 700)
        else:
            svg.text(x + 108, y + 127, "继续", 14, p["text"], 700, "middle")
        svg.multiline(x + 20, y + 190, [rule[line:line + 14] for line in range(0, len(rule), 14)][:3],
                      14, BOARD_MUTED, 400, 22)
        svg.rect(x + 18, y + 285, 250, 44, p["bg"], 9, p["outline"], 1)
        svg.text(x + 30, y + 312, "名称 / 角色 / 状态不丢失", 12, p["text"], 500)
    path = ASSETS / "states" / "STATE-COMPONENTS.svg"
    svg.save(path)
    return path


def cell_lines(value: str, limit: int = 18) -> list[str]:
    return [value[index:index + limit] for index in range(0, len(value), limit)][:3]


def render_state_pages() -> Path:
    p = THEMES["prism"]["dark"]
    svg = Svg(1600, 1180, "页面状态映射矩阵",
              "十个页面组在正常、加载、空、离线、错误和权限状态下的静态行为映射。", BOARD_BG)
    header(svg, "G1-S5 · 状态 2/3 · 页面映射", "页面状态矩阵",
           "空、加载、离线、错误与权限必须分开，不得互相伪装。", "10 页面组 × 6 状态")
    table_x, table_y = 40, 136
    first_w, cell_w, row_h = 176, 220, 92
    headers = ["页面组"] + [STATE_LABELS[key] for key in PAGE_STATE_IDS]
    for index, title in enumerate(headers):
        x = table_x + index * cell_w if index else table_x
        width = first_w if index == 0 else cell_w
        if index == 0:
            svg.rect(table_x, table_y, first_w, 42, p["raised"], 8, p["outline"], 1)
            svg.text(table_x + 14, table_y + 27, title, 14, p["text"], 700)
        else:
            svg.rect(table_x + first_w + (index - 1) * cell_w, table_y, cell_w, 42,
                     p["raised"], 0, p["outline"], 1)
            svg.text(table_x + first_w + (index - 1) * cell_w + cell_w / 2, table_y + 27,
                     title, 14, p["text"], 700, "middle")
    for row_index, row in enumerate(PAGE_STATES):
        y = table_y + 42 + row_index * row_h
        svg.rect(table_x, y, first_w, row_h, "#111720", 0, p["outline"], 1)
        svg.text(table_x + 14, y + 50, row["page"], 14, p["text"], 700)
        for col_index, key in enumerate(PAGE_STATE_IDS):
            x = table_x + first_w + col_index * cell_w
            svg.rect(x, y, cell_w, row_h, "#111720" if row_index % 2 == 0 else "#0E141B", 0, p["outline"], 1)
            accent = {
                "default": p["text2"], "loading": p["info"], "empty": p["text2"],
                "offline": p["warning"], "error": p["error"], "permission": p["book"],
            }[key]
            svg.circle(x + 14, y + 18, 4, accent)
            svg.multiline(x + 26, y + 24, cell_lines(row[key], 18), 11, p["text"], 400, 15)
    svg.text(40, 1150,
             "来源：V2_PLAN §5.3.16.4；静态矩阵不证明真实入口、网络失败、权限弹窗或 TalkBack。",
             12, BOARD_MUTED, 500)
    path = ASSETS / "states" / "STATE-PAGE-MATRIX.svg"
    svg.save(path)
    return path


def render_state_permission() -> Path:
    p = THEMES["prism"]["dark"]
    svg = Svg(1600, 900, "权限与不可用状态",
              "下载 403、管理员门控、动态色平台限制和 SyncPlay 候选的状态区分。", BOARD_BG)
    header(svg, "G1-S5 · 状态 3/3 · 权限", "权限 / 不可用边界",
           "权限拒绝不自动重试；不可用、离线、空与错误必须分开。", "STATIC · RUNTIME UNVERIFIED")
    cards = [
        ("下载 403", "对象：潮汐档案", "来源拒绝访问；无自动重试。", "返回详情；权限变化后由用户重新发起。", p["error"]),
        ("管理员门控", "对象：媒体库管理", "当前账户无管理能力。", "显示可见原因；普通浏览入口仍可用。", p["book"]),
        ("动态色版本门", "Android 12 以下", "主题入口禁用，不伪装成可点预览。", "显示系统版本要求；当前主题保持可用。", p["warning"]),
        ("SyncPlay 候选", "能力未通过 G1/G2", "当前不展示已可用承诺。", "协议 / 双端 / 时钟证据就绪后再评审。", p["info"]),
    ]
    for index, (title, obj, state, recovery, accent) in enumerate(cards):
        x = 48 + index * 382
        svg.rect(x, 154, 362, 620, "#111720", 18, BOARD_LINE, 1)
        svg.circle(x + 34, 196, 14, accent, 0.22, accent, 2)
        svg.text(x + 62, 202, title, 20, BOARD_TEXT, 700)
        svg.text(x + 26, 250, obj, 15, BOARD_MUTED, 700)
        svg.rect(x + 26, 274, 310, 130, p["panel"], 14, p["outline"], 1)
        svg.text(x + 46, 310, "状态", 13, accent, 700)
        svg.multiline(x + 46, 342, [state[line:line + 17] for line in range(0, len(state), 17)][:3],
                      17, p["text"], 500, 27)
        svg.text(x + 26, 454, "恢复路径", 13, p["text2"], 700)
        svg.multiline(x + 26, 484, [recovery[line:line + 19] for line in range(0, len(recovery), 19)][:4],
                      15, BOARD_TEXT, 400, 26)
        svg.rect(x + 26, 640, 310, 48, p["raised"], 10, p["outline"], 1)
        svg.text(x + 36, 670, "文字 + 图标 + 形状，缺一不可", 13, p["text"], 500)
    path = ASSETS / "states" / "STATE-PERMISSION.svg"
    svg.save(path)
    return path


def contrast_rows() -> list[dict]:
    rows = []
    pair_defs = [
        ("body_bg", "text", "bg", 4.5, "正文 / 页面"),
        ("body_panel", "text", "panel", 4.5, "正文 / 面板"),
        ("secondary_bg", "text2", "bg", 4.5, "弱文字 / 页面"),
        ("secondary_panel", "text2", "panel", 4.5, "弱文字 / 面板"),
        ("film_panel", "film", "panel", 4.5, "影视文字 / 面板"),
        ("music_panel", "music", "panel", 4.5, "音乐文字 / 面板"),
        ("book_panel", "book", "panel", 4.5, "书籍文字 / 面板"),
        ("error_panel", "error", "panel", 4.5, "错误文字 / 面板"),
        ("focus_bg", "focus", "bg", 3.0, "焦点环 / 页面"),
        ("border_panel", "outline", "panel", 3.0, "必要边界 / 面板"),
        ("onaccent_film", "onAccent", "film", 4.5, "影视填充 / 前景"),
        ("onaccent_music", "onAccent", "music", 4.5, "音乐填充 / 前景"),
        ("onaccent_book", "onAccent", "book", 4.5, "书籍填充 / 前景"),
    ]
    for theme_id, theme in THEMES.items():
        for appearance in ("dark", "light"):
            palette = theme[appearance]
            for check_id, fg, bg, threshold, label in pair_defs:
                value = contrast(palette[fg], palette[bg])
                rows.append({
                    "id": f"CONTRAST-{theme_id.upper()}-{appearance.upper()}-{check_id.upper().replace('_','-')}",
                    "theme": theme["label"],
                    "appearance": appearance,
                    "check": label,
                    "foreground": palette[fg].upper(),
                    "background": palette[bg].upper(),
                    "ratio": value,
                    "threshold": threshold,
                    "result": "PASS" if value >= threshold else "FAIL",
                })
    return rows


def render_contrast(rows: list[dict]) -> Path:
    p = THEMES["prism"]["dark"]
    svg = Svg(1600, 1100, "无障碍对比度复算",
              "五主题明暗变体的正文、弱文字、内容域、焦点与边界对比度。", BOARD_BG)
    header(svg, "G1-S5 · 无障碍 1/3 · 对比度", "WCAG 对比度复算",
           "颜色由脚本按 WCAG 相对亮度计算；目标为正文 4.5:1、非文字 3:1。",
           f"{len(rows)} 项 · 全部重新计算")
    columns = ["主题", "外观", "正文", "弱文字", "影视", "音乐", "书籍", "焦点", "边界", "结论"]
    widths = [190, 90, 130, 130, 130, 130, 130, 130, 130, 190]
    x0, y0 = 40, 142
    x = x0
    for index, title in enumerate(columns):
        svg.rect(x, y0, widths[index], 42, p["raised"], 0, p["outline"], 1)
        svg.text(x + widths[index] / 2, y0 + 27, title, 13, p["text"], 700, "middle")
        x += widths[index]
    for row_index, (theme_id, theme) in enumerate(THEMES.items()):
        for appearance_index, appearance in enumerate(("dark", "light")):
            index = row_index * 2 + appearance_index
            palette = theme[appearance]
            values = {
                "body": contrast(palette["text"], palette["panel"]),
                "secondary": contrast(palette["text2"], palette["panel"]),
                "film": contrast(palette["film"], palette["panel"]),
                "music": contrast(palette["music"], palette["panel"]),
                "book": contrast(palette["book"], palette["panel"]),
                "focus": contrast(palette["focus"], palette["bg"]),
                "border": contrast(palette["outline"], palette["panel"]),
            }
            y = y0 + 42 + index * 72
            x = x0
            cells = [theme["label"], "深色" if appearance == "dark" else "浅色",
                     f"{values['body']:.2f}:1", f"{values['secondary']:.2f}:1",
                     f"{values['film']:.2f}:1", f"{values['music']:.2f}:1",
                     f"{values['book']:.2f}:1", f"{values['focus']:.2f}:1",
                     f"{values['border']:.2f}:1", "PASS"]
            for cell_index, cell in enumerate(cells):
                svg.rect(x, y, widths[cell_index], 72, "#111720" if index % 2 == 0 else "#0E141B",
                         0, p["outline"], 1)
                color = p["success"] if cell == "PASS" else p["text"]
                svg.text(x + widths[cell_index] / 2, y + 44, cell,
                         13 if cell_index else 14, color, 700 if cell == "PASS" else 500, "middle")
                x += widths[cell_index]
    all_pass = all(row["result"] == "PASS" for row in rows)
    svg.rect(40, 936, 1520, 72, "#111720", 14, p["outline"], 1)
    svg.text(62, 965, "静态结论", 14, p["success"] if all_pass else p["error"], 700)
    svg.text(62, 992, f"{sum(1 for row in rows if row['result']=='PASS')}/{len(rows)} PASS；真实渲染与动态色重算仍为 UNVERIFIED。",
             15, BOARD_TEXT, 500)
    svg.text(40, 1062, "未执行：Compose 实渲染、WallpaperColors 动态重算、设备对比度设置、视觉终判。",
             12, BOARD_MUTED, 500)
    path = ASSETS / "a11y" / "A11Y-CONTRAST.svg"
    svg.save(path)
    return path


def render_a11y_interaction() -> Path:
    p = THEMES["prism"]["dark"]
    svg = Svg(1600, 1000, "无障碍交互证据",
              "48dp 触控、焦点顺序、200% 字号重排和减弱动效的静态证据。", BOARD_BG)
    header(svg, "G1-S5 · 无障碍 2/3 · 交互", "触控、焦点、动态字号与动效",
           "静态布局与合同可复算；TalkBack、键盘和 Compose 运行效果未执行。",
           "18 个动作名称映射 · 0 运行时断言")
    panels = [
        (48, "48dp 触控", "32dp 图形置于 48×48dp 热区"),
        (424, "焦点 / 键盘", "2dp 外环；顺序按视觉流"),
        (800, "200% 动态字号", "标题换行、动作重排、不缩字"),
        (1176, "减弱动效", "0ms 或直接切换；状态保留"),
    ]
    for x, title, subtitle in panels:
        svg.rect(x, 148, 376, 760, "#111720", 16, BOARD_LINE, 1)
        svg.text(x + 20, 181, title, 19, BOARD_TEXT, 700)
        svg.text(x + 20, 207, subtitle, 13, BOARD_MUTED, 500)

    x = 76
    svg.rect(x + 22, 260, 48, 48, p["raised"], 12, p["outline"], 1)
    svg.rect(x + 30, 268, 32, 32, p["book"], 8)
    svg.text(x + 154, 278, "图形 32dp", 13, p["text"], 500)
    svg.text(x + 154, 301, "热区 48dp", 13, p["text2"], 500)
    svg.rect(x + 22, 350, 300, 96, p["panel"], 12, p["outline"], 1)
    svg.text(x + 40, 383, "独立边界检查", 14, p["book"], 700)
    svg.text(x + 40, 412, "相邻动作不共享 / 不重叠热区", 13, p["text"], 500)
    svg.rect(x + 22, 486, 300, 220, p["bg"], 12, p["outline"], 1)
    for index in range(4):
        row_y = 506 + index * 48
        svg.rect(x + 36, row_y, 272, 36, p["panel"], 8, p["outline"], 1)
        svg.text(x + 52, row_y + 24, ["截图示例", "按钮图形", "文本行", "列表行"][index], 12, p["text"], 500)
        svg.text(x + 290, row_y + 24, "≥48", 11, p["success"], 700, "end")

    x = 452
    focus_items = [("1", "搜索", 252), ("2", "继续阅读", 332), ("3", "更多", 412), ("4", "首页", 492)]
    for number, label, y in focus_items:
        selected = number == "2"
        svg.rect(x + 24, y - 24, 326, 52, p["panel"], 10,
                 p["focus"] if selected else p["outline"], 2 if selected else 1)
        svg.circle(x + 50, y + 2, 15, p["focus"] if selected else p["raised"],
                   1 if selected else 1, p["bg"] if selected else p["outline"], 1)
        svg.text(x + 50, y + 7, number, 12, p["bg"] if selected else p["text2"], 700, "middle")
        svg.text(x + 82, y + 8, label, 14, p["text"], 700)
    svg.text(x + 24, 590, "焦点环：2dp + 1dp gap", 14, p["focus"], 700)
    svg.text(x + 24, 620, "不被滚动容器裁切", 13, p["text2"], 500)
    svg.multiline(x + 24, 676, ["Tab / Shift+Tab 顺序与视觉一致", "Hover 只在指针环境出现", "Switch Access 可聚焦同类动作"],
                  13, p["text"], 400, 25)

    x = 828
    svg.rect(x + 30, 244, 316, 570, p["bg"], 16, p["outline"], 1)
    svg.text(x + 48, 278, "首页", 17, p["text"], 700)
    svg.multiline(x + 48, 320, ["潮汐档案与一封很长的", "海上来信，在 200% 字号下", "仍允许完整换行。"],
                  18, p["text"], 700, 34)
    svg.rect(x + 48, 438, 280, 50, p["panel"], 10, p["outline"], 1)
    svg.text(x + 62, 469, "继续阅读", 16, p["book"], 700)
    svg.rect(x + 48, 508, 280, 62, p["panel"], 10, p["outline"], 1)
    svg.multiline(x + 62, 535, ["第 12 章 · 62%", "最近活动：今天"], 13, p["text2"], 500, 24)
    svg.rect(x + 48, 592, 130, 52, p["book"], 9)
    svg.text(x + 113, 623, "继续", 15, p["onAccent"], 700, "middle")
    svg.rect(x + 190, 592, 138, 52, p["panel"], 9, p["outline"], 1)
    svg.text(x + 259, 623, "详情", 15, p["text"], 700, "middle")
    svg.text(x + 48, 696, "静态重排示意；字体放大运行时未验证", 11, p["text2"], 500)

    x = 1204
    svg.text(x + 24, 258, "常规主题切换", 13, p["text2"], 700)
    svg.rect(x + 24, 278, 318, 8, p["outline"], 4)
    svg.rect(x + 24, 278, 180, 8, p["book"], 4)
    svg.text(x + 342, 286, "180ms", 11, p["text"], 700, "end")
    svg.text(x + 24, 338, "减少动画", 13, p["text2"], 700)
    svg.rect(x + 24, 358, 318, 8, p["outline"], 4)
    svg.rect(x + 24, 358, 4, 8, p["success"], 2)
    svg.text(x + 342, 366, "0ms", 11, p["success"], 700, "end")
    svg.rect(x + 24, 410, 318, 142, p["panel"], 12, p["outline"], 1)
    svg.text(x + 44, 444, "状态仍然完整", 16, p["text"], 700)
    svg.text(x + 44, 474, "颜色 + 文字 + 图标", 13, p["text2"], 500)
    svg.text(x + 44, 500, "操作结果立即可见", 13, p["text2"], 500)
    svg.text(x + 24, 606, "不关闭：进度 · 歌词跟随", 12, p["text2"], 500)
    svg.text(x + 24, 632, "可降低：缩放 / 位移 / 模糊", 12, p["text2"], 500)
    svg.text(x + 24, 658, "禁止：装饰循环 / 全屏粒子", 12, p["text2"], 500)
    path = ASSETS / "a11y" / "A11Y-INTERACTION.svg"
    svg.save(path)
    return path


def render_a11y_names() -> Path:
    p = THEMES["prism"]["dark"]
    svg = Svg(1600, 1000, "可访问名称与语义合同",
              "18 个高频动作的可见标签、可访问名称、角色和状态。", BOARD_BG)
    header(svg, "G1-S5 · 无障碍 3/3 · 名称 / 角色", "可访问名称映射",
           "视觉可以无文字，TalkBack / 语音控制不能失去名称、角色与状态。",
           "18/18 名称映射 · TalkBack UNVERIFIED")
    columns = ["动作", "角色", "可访问名称 / 状态", "可见策略"]
    widths = [200, 170, 560, 590]
    x0, y0 = 40, 144
    x = x0
    for index, title in enumerate(columns):
        svg.rect(x, y0, widths[index], 40, p["raised"], 0, p["outline"], 1)
        svg.text(x + 14, y0 + 26, title, 13, p["text"], 700)
        x += widths[index]
    for index, item in enumerate(ACCESSIBLE_NAMES):
        _, label, role, name, state, visible, source = item
        y = y0 + 40 + index * 41
        values = [f"{item[0][-2:]}  {label}", role, f"{name} · {state}", visible]
        x = x0
        for column, value in enumerate(values):
            svg.rect(x, y, widths[column], 41, "#111720" if index % 2 == 0 else "#0E141B",
                     0, p["outline"], 1)
            svg.text(x + 14, y + 26, value, 12, p["text"] if column else p["book"],
                     700 if column == 0 else 500)
            x += widths[column]
    svg.text(40, 946, "运行时未验证：Compose semantics、TalkBack 朗读、Switch Access、Voice Access、焦点裁切与 disabled 可读性。",
             13, BOARD_MUTED, 500)
    svg.text(40, 973, "来源：V2_PLAN §5.3.16.5、RWD-09、V2-D069、V2-D073。",
             12, BOARD_MUTED, 500)
    path = ASSETS / "a11y" / "A11Y-NAMES.svg"
    svg.save(path)
    return path


def interaction_records(paths: list[Path]) -> list[dict]:
    records = []
    for item, path in zip(INTERACTIONS, paths):
        records.append({
            **item,
            "evidencePath": path.relative_to(ROOT).as_posix(),
            "panels": [
                {"stage": "entry", "text": item["entry"]},
                {"stage": "primaryAction", "text": item["action"]},
                {"stage": "feedbackState", "text": item["feedback"]},
                {"stage": "failureRecovery", "text": item["recovery"]},
            ],
            "status": "STATIC_DECISION_INPUT",
            "runtimeStatus": "UNVERIFIED",
        })
    return records


def rwd_records() -> list[dict]:
    records = []
    for row in RWD_ROWS:
        rid, label, width, height, nav, rail, pad, gap, min_card, sources = row
        calc = calculate_rwd(width, nav, rail, pad, gap, min_card)
        records.append({
            "id": rid, "class": label, "width": width, "height": height,
            "navigation": nav, "railWidth": rail, "pagePadding": pad,
            "gutter": gap, "minimumCardWidth": min_card,
            "contentWidth": calc["contentWidth"], "columns": calc["columns"],
            "cardWidth": calc["cardWidth"], "horizontalOverflow": calc["horizontalOverflow"],
            "residualOuterSpace": calc["residualOuterSpace"],
            "sources": sources.split("/"), "status": "STATIC_LAYOUT_PASS",
            "runtimeStatus": "UNVERIFIED",
        })
    records.append({
        "id": "RWD-FOLD", "class": "Foldable", "width": 700, "height": 900,
        "navigation": "rail", "railWidth": 72, "pagePadding": 24,
        "hingeWidth": 24, "leftPaneWidth": 242, "rightPaneWidth": 314,
        "horizontalOverflow": 0,
        "sources": ["RWD-06", "RWD-07", "RWD-08"],
        "status": "STATIC_LAYOUT_PASS", "runtimeStatus": "UNVERIFIED",
    })
    return records


def state_records() -> list[dict]:
    records = []
    for page_index, page in enumerate(PAGE_STATES):
        for state in PAGE_STATE_IDS:
            records.append({
                "id": f"S5-STATE-{page_index+1:02d}-{state.upper()}",
                "pageGroup": page["page"],
                "state": state,
                "stateLabel": STATE_LABELS[state],
                "mapping": page[state],
                "sources": ["V2_PLAN §5.3.16.4", "V2-REQ-UX-014", "V2-D025", "V2-D032"],
                "status": "STATIC_DECISION_INPUT",
            })
    return records


def a11y_records(contrast_data: list[dict]) -> list[dict]:
    failed = [row for row in contrast_data if row["result"] != "PASS"]
    records = []
    for check_id, title, source, value_type, value, status in A11Y_CHECKS:
        actual = value
        if check_id == "A11Y-01":
            relevant = [row for row in contrast_data if row["threshold"] == 4.5]
            actual = f"{sum(row['result']=='PASS' for row in relevant)}/{len(relevant)} pairs ≥4.5:1; min {min(row['ratio'] for row in relevant):.2f}:1"
        elif check_id == "A11Y-02":
            relevant = [row for row in contrast_data if row["threshold"] == 3.0]
            actual = f"{sum(row['result']=='PASS' for row in relevant)}/{len(relevant)} pairs ≥3:1; min {min(row['ratio'] for row in relevant):.2f}:1"
        elif check_id == "A11Y-04":
            actual = f"{len(ACCESSIBLE_NAMES)}/{len(ACCESSIBLE_NAMES)} 示例动作有名称与状态策略"
        elif check_id == "A11Y-09":
            actual = "主题状态过渡 150–300ms；使用 Reduce Motion 时 0ms / 直接切换"
        records.append({
            "id": check_id, "title": title, "source": source,
            "valueType": value_type, "required": value, "actual": actual,
            "status": status, "runtimeExecuted": False,
        })
    if failed:
        records.append({
            "id": "A11Y-CONTRAST-FAIL", "title": "对比度自动检查失败",
            "source": "WCAG", "valueType": "计算值",
            "required": "全部静态 pair 达标", "actual": json.dumps(failed, ensure_ascii=False),
            "status": "FAIL_STATIC", "runtimeExecuted": False,
        })
    return records


def write_csv(path: Path, headers: list[str], rows: list[list[object]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.writer(handle, lineterminator="\n")
        writer.writerow(headers)
        writer.writerows(rows)


def write_data_and_docs(theme_paths: list[Path], interaction_paths: list[Path],
                        rwd_paths: list[Path], state_paths: list[Path],
                        a11y_paths: list[Path], contrast_data: list[dict],
                        a11y_data: list[dict], source_snapshot: dict, git_state: dict) -> None:
    theme_data = []
    for key, theme in THEMES.items():
        item = {k: v for k, v in theme.items() if k not in ("dark", "light")}
        item["id"] = key
        item["palettes"] = {"dark": theme["dark"], "light": theme["light"]}
        item["darkMinimumKeyContrast"] = min(
            contrast(theme["dark"][fg], theme["dark"]["panel"])
            for fg in ("text", "text2", "film", "music", "book", "outline")
        )
        item["lightMinimumKeyContrast"] = min(
            contrast(theme["light"][fg], theme["light"]["panel"])
            for fg in ("text", "text2", "film", "music", "book", "outline")
        )
        item["evidencePath"] = theme_paths[theme["order"] - 1].relative_to(ROOT).as_posix()
        item["sources"] = ["V2_PLAN §5.3.16.6", "V2-D021", "V2-D026", "V2-D027",
                           "V2-D043", "V2-D044", "V2-D057", "V2-D058", "V2-D074"]
        item["status"] = "READY_FOR_USER_DECISION"
        theme_data.append(item)
    utf8(DATA / "themes.json", json.dumps(theme_data, ensure_ascii=False, indent=2) + "\n")

    token_rows = []
    for theme in theme_data:
        for appearance, palette in theme["palettes"].items():
            for role, value in palette.items():
                token_rows.append([theme["id"], theme["label"], appearance, role, value.upper()])
    write_csv(DATA / "theme-tokens.csv", ["theme_id", "theme_label", "appearance", "role", "hex"], token_rows)

    interactions = interaction_records(interaction_paths)
    utf8(DATA / "interactions.json", json.dumps(interactions, ensure_ascii=False, indent=2) + "\n")
    write_csv(DATA / "interactions.csv",
              ["id", "title", "entry", "action", "feedback", "recovery", "status", "path"],
              [[item["id"], item["title"], item["entry"], item["action"], item["feedback"],
                item["recovery"], item["status"], item["evidencePath"]] for item in interactions])

    rwd = rwd_records()
    for record in rwd:
        record["evidencePath"] = (
            next((p.relative_to(ROOT).as_posix() for p in rwd_paths if p.stem == record["id"]),
                 "assets/rwd/RWD-FOLD.svg")
        )
    utf8(DATA / "rwd-matrix.json", json.dumps(rwd, ensure_ascii=False, indent=2) + "\n")
    write_csv(DATA / "rwd-matrix.csv",
              ["id", "class", "width", "height", "navigation", "railWidth", "pagePadding",
               "gutter", "minimumCardWidth", "contentWidth", "columns", "cardWidth", "status", "path"],
              [[r["id"], r["class"], r["width"], r["height"], r["navigation"], r["railWidth"],
                r["pagePadding"], r.get("gutter", ""), r.get("minimumCardWidth", ""),
                r.get("contentWidth", ""), r.get("columns", ""), r.get("cardWidth", ""),
                r["status"], r["evidencePath"]] for r in rwd])

    states = state_records()
    utf8(DATA / "state-matrix.json", json.dumps(states, ensure_ascii=False, indent=2) + "\n")
    write_csv(DATA / "state-matrix.csv",
              ["id", "page_group", "state", "label", "mapping", "sources", "status"],
              [[r["id"], r["pageGroup"], r["state"], r["stateLabel"], r["mapping"],
                "; ".join(r["sources"]), r["status"]] for r in states])

    failed = [row for row in contrast_data if row["result"] != "PASS"]
    utf8(DATA / "contrast-matrix.json", json.dumps({
        "method": "WCAG 2.x relative luminance; ratio=(L1+0.05)/(L2+0.05)",
        "pairCount": len(contrast_data),
        "passCount": sum(row["result"] == "PASS" for row in contrast_data),
        "failCount": len(failed),
        "rows": contrast_data,
    }, ensure_ascii=False, indent=2) + "\n")
    write_csv(DATA / "contrast-matrix.csv",
              ["id", "theme", "appearance", "check", "foreground", "background", "ratio", "threshold", "result"],
              [[r["id"], r["theme"], r["appearance"], r["check"], r["foreground"],
                r["background"], f"{r['ratio']:.2f}", f"{r['threshold']:.1f}", r["result"]]
               for r in contrast_data])

    write_csv(DATA / "accessible-names.csv",
              ["id", "action", "role", "accessible_name", "state", "visible_strategy", "source"],
              [list(row) for row in ACCESSIBLE_NAMES])
    utf8(DATA / "accessibility.json", json.dumps({
        "checks": a11y_data,
        "accessibleNames": [
            dict(zip(["id", "action", "role", "accessibleName", "state", "visibleStrategy", "source"], row))
            for row in ACCESSIBLE_NAMES
        ],
        "staticContrastAllPass": not failed,
        "runtimeEvidence": "UNVERIFIED_NO_DEVICE_OR_TALKBACK",
    }, ensure_ascii=False, indent=2) + "\n")

    utf8(DATA / "source-snapshot.json", json.dumps(source_snapshot, ensure_ascii=False, indent=2) + "\n")

    assessment = {
        "taskId": "G1-S5",
        "decisionInput": "V2-Q-G1-04",
        "status": "READY_FOR_USER_DECISION",
        "selfVerification": "PASS" if not failed else "FAIL",
        "q04": "OPEN",
        "g1": "NOT_PASSED",
        "runtimeVisualQa": "UNVERIFIED",
        "recommendedPackage": "R1_DNA_PRESERVING_SHARED_SKELETON",
        "coverage": {
            "themes": "5/5",
            "interactions": "9/9",
            "rwdFrames": f"{len(rwd_paths)}/{len(rwd_paths)}",
            "rwdWidthClasses": "5/5",
            "statePageMappings": f"{len(states)}/60",
            "accessibleNames": f"{len(ACCESSIBLE_NAMES)}/{len(ACCESSIBLE_NAMES)}",
            "contrastPairs": f"{sum(row['result']=='PASS' for row in contrast_data)}/{len(contrast_data)}",
        },
        "git": git_state,
        "sources": source_snapshot,
    }
    utf8(DATA / "assessment.json", json.dumps(assessment, ensure_ascii=False, indent=2) + "\n")

    static_fail_text = "全部通过" if not failed else f"{len(failed)} 项失败"
    readme = f"""# Aurorama 2.0 · G1-S5 视觉方向证据包

状态：`READY_FOR_V2_Q_G1_04_USER_DECISION / Q04_OPEN / G1_NOT_PASSED / RUNTIME_UNVERIFIED`

本目录只是 `V2-Q-G1-04` 的用户决策输入，不是 Q04 已确认、G1 PASS、实现、独立 QA 或发布证据。
权威基线：分支 `{git_state['branch']}`，HEAD `{git_state['head']}`；目录级 `.gitattributes`
固定 `text` / `eol=lf`，保证 checkout 与 manifest 按字节复现。

## 建议读序

1. `decision-brief.md`：先看推荐整包、备选、取舍和待确认问题。
2. `index.html`：按主题 → 九交互 → RWD → 状态 → 无障碍浏览独立 SVG 帧。
3. `manifest.json`：机械核对证据 ID、来源、路径、MIME、尺寸与 SHA-256。
4. `data/`：读取可复算的 token、RWD、状态、对比度、名称映射和源文档快照。

## 覆盖

- 五主题：Prism、Lumen、Nocturne、Monolith、系统动态色，5/5 独立风格帧。
- 九交互：首页、媒体库 / 视频详情、音乐 / 队列、书架 / 阅读器、下载、搜索、设置 / 主题、播放控制、SyncPlay 候选，9/9 四段复核。
- RWD：Compact 360、Medium 600、Expanded 840、Large 1200、ExtraLarge 1600，另有 700dp 折叠 / 窗口帧，共 6 帧。
- 状态：10 个页面组 × 6 类状态 = 60 条映射；另有组件与权限状态板。
- 无障碍：{len(contrast_data)} 个对比度 pair、18 个动作名称映射、12 项适用检查。静态失败：{static_fail_text}。

## 复现

终端必须使用 PowerShell 7：

```powershell
pwsh -NoProfile -Command "python .planning/v2-g1/G1-S5/scripts/build_evidence.py"
{VERIFICATION_COMMAND}
```

生成器只写本目录；不使用外部包、远程素材、设备、服务器、认证或生产资源。

## 未覆盖

- Compose / Kotlin 实际 token 落地、字体真渲染、Android 设置与设备真实布局。
- TalkBack、Switch Access、Voice Access、键盘、指针、权限弹窗和动态色运行时。
- S5 之后的每主题 3 界面明暗稿、独立视觉 QA、S6、G2、Release。
"""
    utf8(ROOT / "README.md", readme)

    brief = """# V2-Q-G1-04 用户决策简报

## 明确状态

这是候选视觉方向证据，**待用户确认**。文件不构成 Q04 关闭、G1 PASS、V2 设计权威变更、实现或独立 QA 结论。

## 推荐整包 R1：DNA 保留 + 共享骨架

- 五主题保留各自材料语法，但共享 IA、入口顺序、状态含义、可访问名称、48dp 触控和错误 / 权限文案职责。
- Prism：细线几何；Lumen：冷青玻璃与媒介图；Nocturne：暖金编辑排版；Monolith：不透明强描边；系统动态色：只替换 Prism 颜色角色。
- `AmbientColors` 不再作为页面主题解析来源，也不形成第二视觉权威。若保留，只允许在当前 App 主题内生成局部、单一、可关闭的内容强调，并且必须重新通过对比度。
- RWD 使用 Compact / Medium / Expanded / Large / ExtraLarge 五档；72dp 收轨与 216dp 展开轨；页面边距 16 / 24 / 32 / 48dp；折叠与窗口变化保留滚动、选择和进行中动作。
- 九条低保真按“入口 → 主要动作 → 反馈 / 状态 → 失败 / 恢复”复核；SyncPlay 仅作为能力未通过前的候选，不承诺现有可用。

推荐理由：该整包最能保留用户已接受的五主题方向，同时把实际漂移风险收进单一解析链和可复算证据。它也比直接实现更早暴露动态色、阅读器外框、RWD 和失败状态冲突。

## 备选 A2：更强材料表现

允许每个主题在更多功能层使用材质和局部光感，例如更明显的玻璃、内高光与编辑排版权重。视觉差异更强，但增加可读性、性能和“第二主题”漂移风险；需要额外视觉 QA。

## 备选 A3：更强结构统一

五主题主要只替换颜色与圆角，材质和动效趋同。实现更稳、跨主题更一致，但 Lumen / Nocturne / Monolith 的辨识度明显下降，可能不满足五主题方向。

## 跨主题不变量

- 一级 IA 与入口顺序不变：首页、视频、音乐、书架；媒体库、下载、设置保持既有层级。
- 同一动作不换义；错误、权限、危险确认保留文字；颜色不是唯一状态通道。
- 48×48dp 触控底线、2dp 焦点环、200% 字号重排与 Reduce Motion 均为质量门。
- 阅读显示主题只覆盖正文区域，外框、顶栏、按钮和弹层继续服从 App 主题。
- 动态色主题不能叠加品牌主题；系统色变化后必须重新计算对比度。

## 关键差异

| 主题 | 材料 | 形状 | 排版 / 动效 |
|---|---|---|---|
| Prism | 平实、细线、只在内容图内折光 | 12dp | MiSans 标题偏几何；180ms |
| Lumen | 冷青玻璃、内部高光 | 16dp | 媒介图更突出；200ms |
| Nocturne | 暖金发丝线、靛影中性层 | 8dp | Literata 展示标题；220ms |
| Monolith | 实心面、2dp 强轮廓、无辉光 | 8dp | 最短反馈；150ms |
| 系统动态色 | 沿用 Prism，仅换角色色 | 12dp | 图标形状不变；180ms |

## 待用户确认

1. 是否接受推荐整包 R1 的五主题色板、材料语法与圆角候选？
2. 是否接受“`AmbientColors` 不作页面主题，只保留受控局部强调”的取舍？
3. 是否接受五档 RWD、72 / 216dp 轨道与 16 / 24 / 32 / 48dp 页面边距方向？
4. 九条低保真的入口、反馈与恢复分支是否有需要改动作或层级的地方？
5. 若不接受 R1，选择 A2、A3 还是逐项修订？

## 已执行 / 未覆盖

已实际执行：静态 SVG 生成、token / 对比度复算、RWD 几何计算、18 个名称映射、manifest 哈希与尺寸自验。

未覆盖：Compose / 设备 / TalkBack / 键盘 / 权限弹窗 / 动态色 / 字体真渲染 / 视觉感知终判。
S5 之后仍需独立视觉 QA 与用户主观确认；核心真实链路仍按 G2 门执行。
"""
    utf8(ROOT / "decision-brief.md", brief)


def make_index() -> None:
    theme_cards = "\n".join(
        f'<figure><img src="assets/themes/THEME-{key.upper()}.svg" alt="{esc(theme["label"])} 视觉方向帧">'
        f'<figcaption>{theme["label"]} / {theme["cn"]}</figcaption></figure>'
        for key, theme in THEMES.items()
    )
    interaction_cards = "\n".join(
        f'<figure><img src="assets/interactions/{item["id"]}-{item["slug"].upper()}.svg" '
        f'alt="{esc(item["title"])} 低保真交互复核"><figcaption>{item["id"]} · {item["title"]}</figcaption></figure>'
        for item in INTERACTIONS
    )
    rwd_cards = "\n".join(
        f'<figure><img src="assets/rwd/{Path(path).name}" alt="{Path(path).stem} RWD 帧">'
        f'<figcaption>{Path(path).stem}</figcaption></figure>'
        for path in [
            "assets/rwd/RWD-COMPACT.svg", "assets/rwd/RWD-MEDIUM.svg",
            "assets/rwd/RWD-EXPANDED.svg", "assets/rwd/RWD-LARGE.svg",
            "assets/rwd/RWD-EXTRA.svg", "assets/rwd/RWD-FOLD.svg",
        ]
    )
    html = f"""<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>G1-S5 视觉方向证据</title>
  <style>
    :root {{ color-scheme: dark; --bg:#090c11; --surface:#111720; --text:#f3f5f7; --muted:#aab4c0; --line:#303b48; --accent:#9fcdbb; }}
    * {{ box-sizing:border-box; }}
    body {{ margin:0; background:var(--bg); color:var(--text); font:400 16px/1.55 "Microsoft YaHei UI",sans-serif; }}
    header, main {{ width:min(1600px,calc(100% - 40px)); margin:auto; }}
    header {{ padding:48px 0 28px; border-bottom:1px solid var(--line); }}
    h1 {{ margin:0 0 8px; font-size:34px; }}
    h2 {{ margin:0; font-size:24px; }}
    p {{ color:var(--muted); max-width:80ch; }}
    section {{ padding:34px 0; border-bottom:1px solid var(--line); }}
    .grid {{ display:grid; grid-template-columns:repeat(auto-fit,minmax(360px,1fr)); gap:20px; margin-top:20px; }}
    figure {{ margin:0; background:var(--surface); border:1px solid var(--line); padding:12px; border-radius:14px; }}
    img {{ display:block; width:100%; height:auto; background:var(--bg); }}
    figcaption {{ padding:10px 4px 2px; color:var(--muted); }}
    a {{ color:var(--accent); }}
  </style>
</head>
<body>
<header>
  <h1>G1-S5 视觉方向证据</h1>
  <p>供 V2-Q-G1-04 用户决策。状态：Q04 OPEN / G1 NOT PASSED / RUNTIME UNVERIFIED。</p>
  <p><a href="decision-brief.md">决策简报</a> · <a href="manifest.json">manifest.json</a> · <a href="data/contrast-matrix.csv">对比度 CSV</a> · <a href="data/state-matrix.csv">状态 CSV</a></p>
</header>
<main>
  <section><h2>五主题风格帧</h2><p>每帧同时展示深色主预览、明暗对照、色板、材质和静态无障碍结果。</p><div class="grid">{theme_cards}</div></section>
  <section><h2>九条低保真交互</h2><p>入口、主要动作、反馈 / 状态、失败 / 恢复逐条独立审阅。</p><div class="grid">{interaction_cards}</div></section>
  <section><h2>RWD 代表帧</h2><p>五档宽度与一条折痕 / 窗口变化帧。</p><div class="grid">{rwd_cards}</div></section>
  <section><h2>状态</h2><div class="grid">
    <figure><img src="assets/states/STATE-COMPONENTS.svg" alt="组件状态板"><figcaption>组件状态</figcaption></figure>
    <figure><img src="assets/states/STATE-PAGE-MATRIX.svg" alt="页面状态矩阵"><figcaption>页面状态矩阵</figcaption></figure>
    <figure><img src="assets/states/STATE-PERMISSION.svg" alt="权限状态板"><figcaption>权限 / 不可用边界</figcaption></figure>
  </div></section>
  <section><h2>无障碍</h2><div class="grid">
    <figure><img src="assets/a11y/A11Y-CONTRAST.svg" alt="对比度表"><figcaption>对比度复算</figcaption></figure>
    <figure><img src="assets/a11y/A11Y-INTERACTION.svg" alt="触控焦点字号动效证据"><figcaption>触控 / 焦点 / 字号 / 动效</figcaption></figure>
    <figure><img src="assets/a11y/A11Y-NAMES.svg" alt="可访问名称映射"><figcaption>可访问名称</figcaption></figure>
  </div></section>
</main>
</body>
</html>
"""
    utf8(ROOT / "index.html", html)


def source_snapshot() -> dict:
    paths = [
        "AGENTS.md", "docs/PROJECT_RULES.md", "docs/V2_PLAN.md",
        "docs/UI_DESIGN_SYSTEM.md", "docs/DECISIONS.md",
        "docs/adr/0007-v2-app-wide-theme-model.md",
    ]
    return {
        "files": [
            {"path": path, "sha256": sha256(REPO / path)}
            for path in paths
        ],
        "sectionsRead": [
            "PROJECT_RULES R020/R025/R027/R028",
            "V2_PLAN §5.3.16, §5.11.6, §5.11.7, §5.11.12, §12 ACTIVE",
            "UI_DESIGN_SYSTEM §2, §4, §6, §8.14",
            "ADR-0007",
            "DECISIONS V2-Q-G1-04",
        ],
    }


def mime_for(path: Path) -> str:
    return {
        ".gitattributes": "text/plain",
        ".svg": "image/svg+xml",
        ".html": "text/html",
        ".md": "text/markdown",
        ".json": "application/json",
        ".csv": "text/csv",
        ".py": "text/x-python",
    }.get(path.suffix.lower(), "application/octet-stream")


def svg_size(path: Path) -> dict | None:
    if path.suffix.lower() != ".svg":
        return None
    root = ET.parse(path).getroot()
    return {"width": int(float(root.attrib["width"])), "height": int(float(root.attrib["height"]))}


def build_manifest(git_state: dict, source_data: dict, contrast_data: list[dict]) -> None:
    all_files = sorted(
        [path for path in ROOT.rglob("*") if path.is_file()
         and path.name != "manifest.json"
         and "__pycache__" not in path.parts],
        key=lambda path: path.relative_to(ROOT).as_posix(),
    )
    theme_ids = [f"THEME-{key.upper()}" for key in THEMES]
    interaction_ids = [item["id"] for item in INTERACTIONS]
    entries = []
    for path in all_files:
        rel = path.relative_to(ROOT).as_posix()
        theme = None
        interaction = None
        rwd = []
        states = []
        a11y = []
        sources = ["V2_PLAN §12 ACTIVE"]
        kind = "support"
        evidence_id = "SUPPORT-" + re.sub(r"[^A-Z0-9]+", "-", rel.upper()).strip("-")
        status = "FROZEN_STATIC"
        if rel == ".gitattributes":
            evidence_id = "CONTROL-GITATTRIBUTES"
            kind = "line-ending-policy"
            sources = ["Git attribute: text eol=lf"]
        elif rel.startswith("assets/themes/"):
            theme = Path(rel).stem.replace("THEME-", "").title().replace("Dynamic", "系统动态色")
            evidence_id = Path(rel).stem
            kind = "theme-frame"
            sources = ["V2_PLAN §5.3.16.6", "V2-D026", "V2-D057", "V2-D074"]
            status = "READY_FOR_USER_DECISION"
        elif rel.startswith("assets/interactions/"):
            evidence_id = Path(rel).stem.split("-", 2)[0] + "-" + Path(rel).stem.split("-", 2)[1]
            interaction = evidence_id
            kind = "interaction-low-fi"
            sources = next(item["sources"] for item in INTERACTIONS if item["id"] == interaction)
            status = "STATIC_DECISION_INPUT"
        elif rel.startswith("assets/rwd/"):
            evidence_id = Path(rel).stem
            rwd = [evidence_id]
            kind = "rwd-frame"
            sources = ["V2_PLAN §5.3.16.3", "RWD-01", "RWD-09", "V2-D080"]
            status = "STATIC_LAYOUT_PASS"
        elif rel.startswith("assets/states/"):
            evidence_id = Path(rel).stem
            states = ["default", "loading", "empty", "offline", "error", "permission"]
            kind = "state-frame"
            sources = ["V2_PLAN §5.3.16.4", "V2-REQ-UX-014"]
            status = "STATIC_DECISION_INPUT"
        elif rel.startswith("assets/a11y/"):
            evidence_id = Path(rel).stem
            a11y = ["contrast", "touch", "focus", "dynamic-type", "motion", "names"]
            kind = "a11y-frame"
            sources = ["V2_PLAN §5.3.16.5", "RWD-08", "RWD-09", "V2-D069", "V2-D073"]
            status = "STATIC_VERIFIED_RUNTIME_UNVERIFIED"
        elif rel.startswith("data/"):
            evidence_id = "DATA-" + Path(rel).stem.upper() + "-" + Path(rel).suffix.lstrip(".").upper()
            kind = "machine-readable-evidence"
            status = "FROZEN_STATIC"
        elif rel.endswith(".py"):
            evidence_id = "SCRIPT-" + Path(rel).stem.upper()
            kind = "reproduction-script"
            status = "FROZEN_STATIC"
        elif rel in ("README.md", "decision-brief.md"):
            evidence_id = "DOC-" + Path(rel).stem.upper()
            kind = "decision-document"
            status = "READY_FOR_USER_DECISION"
        elif rel == "index.html":
            evidence_id = "INDEX-REVIEW"
            kind = "review-index"
            status = "READY_FOR_USER_DECISION"
        if rel == "data/contrast-matrix.json":
            a11y.append("contrast")
        if rel == "data/accessibility.json":
            a11y.append("all-applicable-items")
        entries.append({
            "id": evidence_id,
            "kind": kind,
            "theme": theme,
            "interaction": interaction,
            "rwd": rwd,
            "state": states,
            "a11y": sorted(set(a11y)),
            "sources": sources,
            "path": rel,
            "mime": mime_for(path),
            "pixelSize": svg_size(path),
            "bytes": path.stat().st_size,
            "sha256": sha256(path),
            "status": status,
            "limits": [] if kind in (
                "support", "line-ending-policy", "machine-readable-evidence",
                "reproduction-script",
            ) else [
                "Static evidence only; runtime and user visual judgement remain outside this artifact."
            ],
        })
    ids = [entry["id"] for entry in entries]
    if len(ids) != len(set(ids)):
        raise SystemExit("BLOCKED_DUPLICATE_MANIFEST_ID")
    manifest = {
        "schemaVersion": "1.0",
        "taskId": "G1-S5",
        "role": "资深 UI / 视觉设计证据负责人",
        "decisionInput": "V2-Q-G1-04",
        "status": "READY_FOR_USER_DECISION",
        "selfVerification": "PASS",
        "q04": "OPEN",
        "g1": "NOT_PASSED",
        "gitBaseline": git_state,
        "lineEndingPolicy": {
            "scope": ".planning/v2-g1/G1-S5/",
            "attributesFile": ".gitattributes",
            "rule": "* text eol=lf",
            "text": "set",
            "eol": "lf",
        },
        "sourceSnapshot": source_data,
        "coverage": {
            "themes": {"required": 5, "covered": len(theme_ids), "ids": theme_ids},
            "interactions": {"required": 9, "covered": len(interaction_ids), "ids": interaction_ids},
            "rwd": {"widthClasses": 5, "frames": len([p for p in all_files if p.parent.name == "rwd"]),
                    "ids": ["RWD-COMPACT", "RWD-MEDIUM", "RWD-EXPANDED", "RWD-LARGE", "RWD-EXTRA", "RWD-FOLD"]},
            "states": {"pageGroups": len(PAGE_STATES), "stateTypes": 6, "mappings": len(PAGE_STATES) * 6,
                       "componentStates": ["default", "pressed", "focused", "disabled", "loading", "empty",
                                           "error", "selected", "hover", "offline-partial"]},
            "a11y": {"applicableChecks": len(A11Y_CHECKS),
                     "contrastPairs": len(contrast_data),
                     "contrastPass": sum(row["result"] == "PASS" for row in contrast_data),
                     "accessibleNames": len(ACCESSIBLE_NAMES),
                     "runtime": "UNVERIFIED"},
        },
        "decisionBrief": "decision-brief.md",
        "reviewIndex": "index.html",
        "verificationCommand": VERIFICATION_COMMAND,
        "entries": entries,
    }
    utf8(ROOT / "manifest.json", json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")


def main() -> int:
    utf8(ATTRIBUTES, LINE_ENDING_RULE)
    git_state = verify_git()
    for directory in (ASSETS / "themes", ASSETS / "interactions", ASSETS / "rwd",
                      ASSETS / "states", ASSETS / "a11y", DATA, SCRIPTS):
        directory.mkdir(parents=True, exist_ok=True)
    theme_paths = [render_theme_frame(key, theme) for key, theme in THEMES.items()]
    interaction_paths = [render_interaction(item) for item in INTERACTIONS]
    rwd_paths = [render_rwd(row) for row in RWD_ROWS] + [render_fold()]
    state_paths = [render_state_components(), render_state_pages(), render_state_permission()]
    contrast_data = contrast_rows()
    contrast_failures = [row for row in contrast_data if row["result"] != "PASS"]
    if contrast_failures:
        raise SystemExit("BLOCKED_CONTRAST: " + json.dumps(contrast_failures, ensure_ascii=False))
    a11y_paths = [render_contrast(contrast_data), render_a11y_interaction(), render_a11y_names()]
    a11y_data = a11y_records(contrast_data)
    snapshot = source_snapshot()
    write_data_and_docs(theme_paths, interaction_paths, rwd_paths, state_paths,
                        a11y_paths, contrast_data, a11y_data, snapshot, git_state)
    make_index()
    build_manifest(git_state, snapshot, contrast_data)
    print(json.dumps({
        "status": "BUILT",
        "themes": len(theme_paths),
        "interactions": len(interaction_paths),
        "rwdFrames": len(rwd_paths),
        "stateFrames": len(state_paths),
        "a11yFrames": len(a11y_paths),
        "contrastPairs": len(contrast_data),
        "contrastPass": sum(row["result"] == "PASS" for row in contrast_data),
        "manifest": str(ROOT / "manifest.json"),
    }, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())
