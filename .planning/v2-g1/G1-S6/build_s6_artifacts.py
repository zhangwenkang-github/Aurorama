#!/usr/bin/env python3
"""Build the Aurorama G1-S6 reviewed planning artifacts.

This script is deterministic for the frozen 2026-11-04 static inputs. It reads
authoritative repository documents and evidence summaries but writes only inside
the G1-S6 evidence directory. It does not execute product tests.
"""

from __future__ import annotations

import hashlib
import json
import re
from collections import Counter, OrderedDict
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[3]
OUT = Path(__file__).resolve().parent
GITATTRIBUTES_PATH = ROOT / ".gitattributes"
V2_PATH = ROOT / "docs" / "V2_PLAN.md"
UI_PATH = ROOT / "docs" / "UI_DESIGN_SYSTEM.md"
DECISIONS_PATH = ROOT / "docs" / "DECISIONS.md"
TASKS_PATH = ROOT / "docs" / "TASKS.md"
PROJECT_PATH = ROOT / "docs" / "PROJECT_PLAN.md"
SEMANTICS_PATH = OUT / "row-semantics.json"
WAVE_MEMBERSHIP_PATH = OUT / "wave-membership.json"
SEMANTIC_SCHEMA = "g1-s6-row-semantics/v2"
WAVE_MEMBERSHIP_SCHEMA = "g1-s6-wave-membership/v1"
UNSPECIFIED = "UNSPECIFIED_IN_SOURCE"

STATUS = (
    "D204_ACCEPTED_ENGINE_STRATEGY_A / SHERPA_MATCHA_COMPAT_FALLBACK / "
    "D205_ACCEPTED_SMALL_ENGINE_RESCAN / SMALL_ANDROID_SECOND_ENGINE_RESCAN / "
    "KOKORO_THEN_MOSS_STATIC_SHORTLIST / ZIPVOICE_HOLD_LICENSE / "
    "C1_EXPRESSIVE_ENGINE_PROFILE_OPEN / S6_SCOPED_RECONFIRM_PASS_AFTER_D204 / "
    "D205_SCOPED_RECONFIRM_PASS / "
    "G1_NOT_PASSED / Q03_OPEN / Q05_CLOSED_D204 / Q04_CLOSED_D201 / "
    "RUNTIME_UNVERIFIED / L2_L3_LOCKED"
)
HISTORICAL_REVIEW_STATUS = "PASS_WITH_P2"
ENGINE_EVIDENCE_ACTION_TAG = "VERIFY_ENGINE_STRATEGY_AND_LICENSE_INPUTS"
SELF_TEST_NEGATIVE_FIXTURE_COUNT = 57
SELF_TEST_BASE_CHECK_COUNT = 10
SELF_TEST_TOTAL_LIVE_CHECK_COUNT = 68
SELF_TEST_BASELINE_CHECK_COUNT = 49
EOL_ATTRIBUTE_SCOPE = (
    "root .gitattributes and three authoritative docs; no wildcard rule"
)
EV_S6_STATUS = (
    "S6_SCOPED_RECONFIRM_PASS_AFTER_D204 / D205_SCOPED_RECONFIRM_PASS / "
    "HISTORICAL_SCOPED_REVIEW_PASS_WITH_P2_Q05_OPEN / RUNTIME_UNVERIFIED"
)
REVIEW_AUTHORITY_FACT = (
    "Historical original S6 review is PASS_WITH_P2 while Q05 was Open; "
    "the fresh scoped documentation reconfirm after D204 is PASS, without "
    "implying G1, QA, runtime or release PASS; D204 exact engine/profile "
    "remains Open and L2/L3 remain LOCKED"
)
READER_060_SOURCE_ID = "V2-REQ-READER-060"
READER_060_PROMISE_ID = "S6-P-READER-060"
READER_060_REQUIRED_DEPENDENCIES = (
    "V2-D088",
    "V2-D097",
    "V2-D203",
    "V2-D204",
)
D204_TTS_W1_DECISION_DEPENDENCIES = (
    "V2-D203",
    "V2-D204",
    "V2-Q-G1-03",
)
STALE_READER_060_MAIN_ENGINE = re.compile(
    r"""
    (
        使用\s*Sherpa(?:-ONNX)?.*?TTS
      | Sherpa(?:-ONNX)?[^。；;\n]{0,40}
        (?:为主引擎|是主引擎|作为(?:当前|默认|主)?(?:朗读)?引擎)
      | (?:当前|默认|主)(?:朗读)?引擎[^。；;\n]{0,12}
        (?:=|:|：|为|是)\s*Sherpa(?:-ONNX)?
      | \bSherpa(?:-ONNX)?\b[^。；;\n]{0,60}
        \b(?:main|primary|default|selected|frozen)\b[^。；;\n]{0,30}
        \bengine\b
      | \b(?:main|primary|default|selected)\s+engine\b[^。；;\n]{0,12}
        (?:=|:|is)\s*(?:the\s+)?\bSherpa(?:-ONNX)?\b
    )
    """,
    re.IGNORECASE | re.VERBOSE,
)
NEGATED_CLAIM_CONTEXT = re.compile(
    r"\b(?:no|not|never|without|unproven|unverified|false|invalid)\b"
    r"|(?:不|未|无|没有|并非|不是|禁止|不得|避免|不能|尚未)",
    re.IGNORECASE,
)
PROHIBITED_EVIDENCE_CLAIMS = (
    re.compile(
        r"\b(?:selects?|selected|chooses?|chose|freezes?|frozen|locks?|locked)\b"
        r"[^.;\n]{0,32}\b(?:engine|profile|voice)\b",
        re.IGNORECASE,
    ),
    re.compile(
        r"\b(?:engine|profile|voice)\b[^.;\n]{0,32}"
        r"\b(?:selected|chosen|frozen|locked)\b",
        re.IGNORECASE,
    ),
    re.compile(
        r"\bSherpa(?:-ONNX)?\b[^.;\n]{0,48}"
        r"\b(?:main|primary|default|selected|frozen)\b"
        r"[^.;\n]{0,24}\bengine\b",
        re.IGNORECASE,
    ),
    re.compile(
        r"(?:已|被)?(?:选择|选中|选定|冻结|锁定|确定)"
        r"[^。；;\n]{0,20}(?:引擎|engine|profile|voice|音色)"
        r"|(?:引擎|engine|profile|voice|音色)"
        r"[^。；;\n]{0,20}(?:已|被)?(?:选择|选中|选定|冻结|锁定|确定)",
        re.IGNORECASE,
    ),
    re.compile(
        r"\b(?:Android\s+)?runtime\s+(?:is|was|has\s+been)\s+"
        r"(?:proven|verified|validated|ready|pass(?:ed)?)\b"
        r"|\b(?:Android\s+)?runtime\s+PASS(?:ED)?\b"
        r"|(?:Android\s*)?runtime\s*(?:已|已经)?(?:证明|验证|通过|就绪)",
        re.IGNORECASE,
    ),
    re.compile(
        r"\bproduction\s+(?:ready|proven|verified|validated|pass(?:ed)?)\b"
        r"|\b(?:candidate|production)\s+(?:package\s+)?PASS(?:ED)?\b"
        r"|(?:生产|发布|候选包)[^。；;\n]{0,10}"
        r"(?:就绪|已证明|已验证|可发布|PASS)",
        re.IGNORECASE,
    ),
)

DOMAINS = ("ARCH", "UX", "READER", "WRITE", "MUSIC", "DOWNLOAD", "VIDEO", "NAV")
PROJECT_G0_ADDENDUM = "PROJECT_G0_ADDENDUM"
EXTERNAL_G0 = "EXTERNAL_G0"
READER_ADDENDUM_IDS = tuple(
    f"V2-REQ-READER-{number:03d}" for number in range(87, 91)
)
READER_ADDENDUM_ID_SET = set(READER_ADDENDUM_IDS)

TABLE_SPECS: "OrderedDict[str, dict[str, Any]]" = OrderedDict(
    [
        (
            "ARCH",
            {
                "start": 144,
                "end": 217,
                "section": "docs/V2_PLAN.md §5.2.2",
                "columns": ["id", "source", "promise", "result", "level", "dependencies"],
            },
        ),
        (
            "UX",
            {
                "start": 741,
                "end": 780,
                "section": "docs/V2_PLAN.md §5.3.15",
                "columns": [
                    "id",
                    "level",
                    "source",
                    "promise",
                    "result",
                    "platform",
                    "dependencies",
                    "evidence",
                ],
            },
        ),
        (
            "READER",
            {
                "start": 1114,
                "end": 1206,
                "section": "docs/V2_PLAN.md §5.4.5",
                "columns": [
                    "id",
                    "level",
                    "source",
                    "promise",
                    "result",
                    "platform",
                    "dependencies",
                    "evidence",
                ],
            },
        ),
        (
            "WRITE",
            {
                "start": 1404,
                "end": 1418,
                "section": "docs/V2_PLAN.md §5.5.4",
                "columns": [
                    "id",
                    "level",
                    "source",
                    "promise",
                    "result",
                    "platform",
                    "dependencies",
                    "evidence",
                ],
            },
        ),
        (
            "MUSIC",
            {
                "start": 1564,
                "end": 1627,
                "section": "docs/V2_PLAN.md §5.6.6.1",
                "columns": [
                    "id",
                    "level",
                    "mvp",
                    "source",
                    "promise",
                    "result",
                    "dependencies",
                    "evidence",
                    "uncovered",
                ],
            },
        ),
        (
            "DOWNLOAD",
            {
                "start": 1840,
                "end": 1909,
                "section": "docs/V2_PLAN.md §5.7.4",
                "columns": [
                    "id",
                    "level",
                    "mvp",
                    "source",
                    "promise",
                    "result",
                    "dependencies",
                    "evidence",
                    "uncovered",
                ],
            },
        ),
        (
            "VIDEO",
            {
                "start": 2162,
                "end": 2260,
                "section": "docs/V2_PLAN.md §5.8.9",
                "columns": [
                    "id",
                    "level",
                    "mvp",
                    "source",
                    "promise",
                    "result",
                    "dependencies",
                    "evidence",
                    "uncovered",
                ],
            },
        ),
        (
            "NAV",
            {
                "start": 2488,
                "end": 2498,
                "section": "docs/V2_PLAN.md §5.9.1",
                "columns": ["id", "level", "promise", "result", "dependencies", "evidence"],
            },
        ),
    ]
)

BASE_SPEC = {
    "start": 2638,
    "end": 2644,
    "section": "docs/V2_PLAN.md §5.10.5",
    "columns": [
        "capability",
        "source",
        "level",
        "promise",
        "result",
        "dependencies",
        "non_goals",
        "uncovered",
    ],
}

EXPECTED_DOMAIN_SOURCE_COUNTS = {
    "ARCH": 72,
    "UX": 38,
    "READER": 90,
    "WRITE": 13,
    "MUSIC": 62,
    "DOWNLOAD": 68,
    "VIDEO": 97,
    "NAV": 9,
}

EXPECTED_DOMAIN_MVP_COUNTS = {
    "ARCH": 69,
    "UX": 21,
    "READER": 70,
    "WRITE": 12,
    "MUSIC": 50,
    "DOWNLOAD": 64,
    "VIDEO": 88,
    "NAV": 6,
}

EXPECTED_EXTERNAL_DOMAIN_SOURCE_COUNTS = {
    **EXPECTED_DOMAIN_SOURCE_COUNTS,
    "READER": 86,
}

EXPECTED_EXTERNAL_DOMAIN_MVP_COUNTS = {
    **EXPECTED_DOMAIN_MVP_COUNTS,
    "READER": 66,
}

COMPOUND_CHILDREN: dict[str, list[dict[str, Any]]] = {
    "V2-REQ-READER-046": [
        {
            "suffix": "BOOKMARK-MANAGEMENT",
            "level": "MUST",
            "promise": "四格式使用格式稳定锚点提供书签管理、列表与准确跳转。",
            "result": "所有支持格式都能从书签准确回到原处。",
        },
        {
            "suffix": "PROGRESS-AXIS-MARKER",
            "level": "SHOULD",
            "promise": "在进度轴显示书签标记作为增强。",
            "result": "标记存在时可见且不阻塞书签管理；缺失时仍可完成书签闭环。",
        },
    ],
    "V2-REQ-READER-056": [
        {
            "suffix": "EXPORT-DATA-FIELDS",
            "level": "MUST",
            "promise": "Annotation 数据保留书名、作者、章节树、进度导出所需字段或可派生信息。",
            "result": "当前批注读写不丢导出所需来源，后续导出无需破坏性迁移。",
        },
        {
            "suffix": "MARKDOWN-EXPORT",
            "level": "LATER",
            "promise": "Markdown 导出 UI 在 2.0 后置。",
            "result": "2.0 不出现未实现的导出承诺；保留字段兼容义务。",
        },
    ],
    "V2-REQ-READER-057": [
        {
            "suffix": "PDF-SEARCH",
            "level": "MUST",
            "promise": "PDF 全书搜索支持上下文、页定位、取消、进度和上限。",
            "result": "大 PDF 可停止，命中可准确跳转并临时高亮。",
        },
        {
            "suffix": "EPUB-TXT-SEARCH",
            "level": "SHOULD",
            "promise": "EPUB/TXT 提供同样可取消、可定位的全书搜索。",
            "result": "搜索命中带上下文并可跳转；不阻塞 PDF MVP。",
        },
    ],
    "V2-REQ-WRITE-001": [
        {
            "suffix": "USER-WRITE",
            "level": "MUST",
            "promise": "2.0 首版交付当前用户级收藏、已看、播放 / 阅读进度与服务器歌单写。",
            "result": "用户可使用真实用户级写，恢复网络与原身份后按原作用域处理离线 intent。",
        },
        {
            "suffix": "MANAGEMENT-WRITE",
            "level": "LATER",
            "promise": "管理类、媒体删除、元数据、用户权限和远程会话管理后置。",
            "result": "2.0 不出现未批准的管理类或破坏性入口。",
        },
    ],
    "V2-REQ-MUSIC-004": [
        {
            "suffix": "CORE-FORMATS",
            "level": "MUST",
            "promise": "MP3 / AAC / FLAC / ALAC(M4A) / WAV / OGG / Opus 为核心可播放格式。",
            "result": "核心格式可在支持引擎链真实播放；未知能力不得以扩展名冒充成功。",
        },
        {
            "suffix": "APE-DSD-PLAYBACK",
            "level": "LATER",
            "promise": "APE / DSD 完整播放后置，但当前必须识别并明确说明不支持。",
            "result": "打开 APE / DSD 时得到可执行说明而非静默失败。",
        },
    ],
    "V2-REQ-MUSIC-051": [
        {
            "suffix": "QUEUE-PLAYLIST-MENU",
            "level": "MUST",
            "promise": "长按曲目菜单提供播放下一首、加入队列末尾、加入播放列表、收藏和查看音频信息。",
            "result": "用户可从列表完成队列、歌单、收藏与音频信息动作。",
        },
        {
            "suffix": "DOWNLOAD-OFFLINE-MENU",
            "level": "MUST",
            "promise": "下载 / 离线菜单入口由下载域 canonical 状态承接。",
            "result": "入口状态、恢复和通知与下载页一致。",
        },
    ],
    "V2-REQ-DOWNLOAD-001": [
        {
            "suffix": "RAW-FOUR-MEDIA",
            "level": "MUST",
            "promise": "覆盖 Movie / Episode、Audio / Music、Book、AudioBook 四类叶子并只下载原始媒体。",
            "result": "四类媒体都能创建原始媒体任务；不可打开格式明确显示已下载不可打开。",
        },
        {
            "suffix": "TRANSCODE-DOWNLOAD",
            "level": "OUT",
            "promise": "转码下载、画质参数和实验性 alpha 转码入口移出 2.0。",
            "result": "转码不会伪装成已支持能力。",
        },
    ],
    "V2-REQ-DOWNLOAD-015": [
        {
            "suffix": "MEDIA-SOURCE-SELECTION",
            "level": "MUST",
            "promise": "每个任务绑定一个明确 MediaSource；单源自动选，多源显式展示并选择，恢复时不静默换源。",
            "result": "用户能看到并选择实际版本；来源消失进入 SOURCE_UNAVAILABLE。",
        },
        {
            "suffix": "AUTO-ALL-VERSIONS",
            "level": "OUT",
            "promise": "自动下载全部版本移出 2.0。",
            "result": "默认选择不等于许可全部版本。",
        },
    ],
    "V2-REQ-VIDEO-001": [
        {
            "suffix": "ANDROID-CORE",
            "level": "MUST",
            "promise": "2.0 覆盖 Android 手机、平板和折叠的核心视频播放闭环。",
            "result": "支持形态内可完成核心播放；不支持形态不出现伪入口。",
        },
        {
            "suffix": "NON-ANDROID-PRODUCTS",
            "level": "OUT",
            "promise": "TV、车机、桌面键盘产品与完整官方周边移出 2.0。",
            "result": "不宣称这些平台的实现或验收。",
        },
    ],
    "V2-REQ-VIDEO-015": [
        {
            "suffix": "1080P-4K",
            "level": "MUST",
            "promise": "1080p 可达；4K 在设备直连或服务器转码链可达。",
            "result": "1080p 与 4K 路径有可验证的 direct / transcode / explicit failure 结果。",
        },
        {
            "suffix": "8K",
            "level": "LATER",
            "promise": "8K 完整播放后置。",
            "result": "8K 不被伪装成 2.0 核心能力。",
        },
    ],
    "V2-REQ-NAV-007": [
        {
            "suffix": "IDENTITY-SWITCH-PAUSE-HIDE",
            "level": "MUST",
            "promise": "在线或离线选择另一身份后，旧 server + user 会话立即暂停并隐藏且不借新 token。",
            "result": "旧媒体不继续播放，也不出现在新身份控制中。",
        },
        {
            "suffix": "ORIGINAL-IDENTITY-RESTORE",
            "level": "MUST",
            "promise": "回原身份恢复队列与位置但保持暂停；纯本地播放继续，混合队列暂停。",
            "result": "回原账号看到原队列 / 位置但未自动起播；离线切换不联网。",
        },
    ],
}

JOURNEY_BASE = {
    "ARCH": ("阶段任务卡 / 架构与发布门", "按已承诺的工程流程执行并留下可复核证据"),
    "UX": ("全局页面骨架 / 设计权威入口", "按已接受 R1 骨架完成页面或组件，并记录 token 与状态来源"),
    "READER": ("书架 → 打开书 → 阅读器", "打开目标书并执行承诺的阅读、定位、批注、搜索或阅读设置动作"),
    "WRITE": ("条目 / 歌单等当前用户可写动作入口", "提交已批准的用户级服务器写意图并观察服务端终态"),
    "MUSIC": ("音乐页 / Mini Player / 全屏 Now Playing", "从真实曲目入口执行队列、播放、显示、设置或错误恢复动作"),
    "DOWNLOAD": ("详情或下载中心 → 开始任务", "选择真实媒体与来源后创建、控制并观察下载任务"),
    "VIDEO": ("详情或本地媒体库 → 主播放", "打开目标媒体并执行播放、轨道、字幕、画面、系统入口或恢复动作"),
    "NAV": ("一级导航 / 账户与身份容器", "执行一级切换、返回、账户选择或身份切换动作"),
}

CARD_SPECS = [
    {
        "id": "G2-ARCH-CONTRACTS-MIGRATION",
        "role": "资深 Android 架构 / 数据迁移集成工程师",
        "objective": "建立逻辑 contracts seam、owner allowlist、debt ratchet 与一次性 FAIL_CLOSED forward migration，作为其余能力的共同底座。",
        "non_goals": [
            "不全量物理拆分 Gradle 模块。",
            "不对真实用户数据做 down migration、清库或破坏性 fallback。",
            "不在本卡实现任何用户功能页面。",
        ],
        "criteria": [
            "目标契约 / owner allowlist / debt ratchet 有可机械执行的门，旧违规只减不增。",
            "1.4→2.0 迁移在可丢弃 profile 上完成中断、恢复和交叉存储一致性验证，任一不一致进入 FAIL_CLOSED。",
            "代码树不存在 release 可达的 fallbackToDestructiveMigration(dropAllTables=true)。",
            "架构边、契约测试、迁移结果与 executed 命令绑定同一候选 SHA。",
        ],
        "boundaries": [
            "逻辑边界：contracts seam、owner allowlist、forward migration journal。",
            "生产文件白名单须在 G2 卡实例化时按 FQCN / module owner 冻结；本卡不预分配文件。",
        ],
        "deps": ["V2-D198", "V2-D199", "EV-S1", "EV-S2"],
        "risks": ["R-S6-P0-01", "R-S6-P1-01", "R-S6-P1-02"],
        "evidence": ["EV-S1", "EV-S2", "EV-S3"],
        "devices": ["Pad 5 或 K60：可丢弃 profile 迁移 / WAL 证据"],
        "writes": ["本卡默认不访问服务器；真实用户数据禁止写入。"],
        "stop": "出现真实数据破坏、未知 provenance 自动猜测、清理 fallback 或迁移无法自证恢复时停止并升级负责人。",
        "artifacts": ["架构守护报告", "owner/debt 清单", "迁移正负 fixture", "candidate SHA 门禁报告"],
        "status": "READY_FOR_G2_PLANNING",
    },
    {
        "id": "G2-SEARCH",
        "role": "资深 Android 搜索 / 路由状态工程师",
        "objective": "交付 BASE-001 的 scope、完整 query shape、generation、空 / 错 / 离线分离和返回恢复。",
        "non_goals": [
            "不新增外部搜索引擎、遥测或服务器写。",
            "不替用户拍板跨源排序 / 分组策略。",
            "不以搜索框存在或 fixture PASS 代替真实结果旅程。",
        ],
        "criteria": [
            "查询与 scope 同时可见；空查询不发请求；仅最新 generation 可提交结果。",
            "正结果可打开目标，空、失败、离线、取消各有独立可观察状态。",
            "返回后恢复原 query、scope、滚动与选择；历史 5 秒 Input timeout 不重现。",
            "fixture、Compose / UI 语义与候选包真实入口证据分别记录。",
        ],
        "boundaries": [
            "逻辑接口：SearchRepository、complete query shape、route state machine、stable result identity。",
            "不调用生产写接口；真实读取需使用负责人批准的只读端点或 fixture 服务。",
        ],
        "deps": ["EV-BASE001", "EV-S3", "V2-D196"],
        "risks": ["R-S6-P1-03", "R-S6-P1-13", "R-S6-P2-04"],
        "evidence": ["EV-BASE001", "EV-S3", "EV-S6"],
        "devices": ["Pad 5 与 K60：返回恢复、输入时延和离线证据"],
        "writes": ["none"],
        "stop": "需要改变产品排序 / 跨源呈现或生产服务器写时停止；历史 P1 未关闭不得宣称已解决。",
        "artifacts": ["搜索状态 fixture 结果", "Compose 语义 / 路由测试", "候选包正 / 空 / 错 / 离线旅程"],
        "status": "READY_FOR_G2_PLANNING",
    },
    {
        "id": "G2-UPDATE-TRUST",
        "role": "资深 Android 应用更新与供应链工程师",
        "objective": "交付可信更新发现、匹配、下载、哈希 / 签名验证、系统安装器交接与安装结果回读。",
        "non_goals": [
            "不静默或特权安装。",
            "不把下载完成、安装器启动或 versionCode 变大当作安装成功。",
            "不扩入未授权的资产更新。",
        ],
        "criteria": [
            "无更新、错 ABI、截断、哈希错、package / signer 错和降级均 fail-closed 且用户可理解。",
            "下载完成、VERIFIED、INSTALLER_HANDOFF、INSTALL_UNKNOWN、CONFIRMED 为不同状态。",
            "系统确认后回读目标 package / versionCode / versionName / signer 才可显示安装确认。",
            "真实 1.4.x→2.0 更新保留已承诺数据。",
        ],
        "boundaries": [
            "逻辑接口：trusted source、channel、ABI selection、artifact digest、signer、installer handoff。",
            "生产服务器 release 资产读取仅限批准来源；禁止绕过 Android 安装确认。",
        ],
        "deps": ["EV-A2", "EV-S3", "V2-D196"],
        "risks": ["R-S6-P1-04", "R-S6-P1-13", "R-S6-P2-02"],
        "evidence": ["EV-A2", "EV-S3", "EV-S6"],
        "devices": ["Pad 5 与 K60：recovery、storage-full、OEM installer 取消"],
        "writes": ["仅读取批准更新源；不写生产业务数据。"],
        "stop": "无法观察最终 URL、ABI、digest 或安装目标回读时停止，不得进入 VERIFIED / CONFIRMED。",
        "artifacts": ["47 类信任状态测试", "真实更新包验证日志", "系统安装器交接 / 回读证据", "升级后资产对照"],
        "status": "READY_FOR_G2_PLANNING",
    },
    {
        "id": "G2-PERSISTENT-SWR",
        "role": "资深 Android 持久缓存 / 并发工程师",
        "objective": "交付按 server + user + namespace + complete query shape 隔离的持久 SWR、写后失效、304 门和跨进程 generation fence。",
        "non_goals": [
            "不把缓存当服务器实时真源或离线写成功。",
            "不在未获用户决策时冻结 TTL、容量、pin 与预取策略。",
            "不把 in-process lock 冒充跨进程单写证据。",
        ],
        "criteria": [
            "公开投影显式含 stale、savedAt、reason 与 identity generation。",
            "切号、切服、完整 query shape 变化均不能跨身份命中。",
            "写成功后旧缓存被持久 fence 失效；304 只在获证服务端能力上启用。",
            "进程重建、并发写、容量边界和时长行为有 fixture / runtime 证据。",
        ],
        "boundaries": [
            "逻辑接口：cache identity、freshness state machine、durable generation / tombstone。",
            "TTL、容量、pin、预取和并发上限须在卡内先形成用户/负责人决策记录。",
        ],
        "deps": ["EV-BASE003", "EV-S3", "V2-D196"],
        "risks": ["R-S6-P1-05", "R-S6-P2-03", "R-S6-P1-13"],
        "evidence": ["EV-BASE003", "EV-S3", "EV-S6"],
        "devices": ["Pad 5：双账号、进程重建、墙钟变化"],
        "writes": ["测试只使用独立可写测试服务或 fixture；生产服务器保持只读。"],
        "stop": "若 304/ETag 无生产合同、跨进程 fence 无方案或 TTL / 容量仍无权威决策，停止过门。",
        "artifacts": ["cache identity / freshness 结果", "跨进程并发负例", "写后失效证据", "用户选择策略记录"],
        "status": "READY_FOR_G2_PLANNING",
    },
    {
        "id": "G2-UI-R1-FOUNDATION",
        "role": "资深 Android Compose 设计系统 / 可访问性工程师",
        "objective": "把 V2 R1 五主题、共享 IA、五档 RWD、九条交互、48dp、200% 重排与 Reduce Motion 落成可验证的 UI 基础。",
        "non_goals": [
            "不建立第二 token 源或页面局部色板。",
            "不让阅读正文主题污染外框、顶栏、按钮或弹层。",
            "不以静态 S5 SVG / 对比度结果冒充 Compose、TalkBack 或设备 PASS。",
        ],
        "criteria": [
            "CinefinTokens.kt 是唯一代码 token 源；五主题与系统动态色按 R1 单一解析链渲染。",
            "Compact / Medium / Expanded / Large / ExtraLarge / 折叠及窗口变化不重置滚动、选择、焦点与进行中动作。",
            "所有交互控件名称、角色、状态可被 TalkBack / 键盘消费，触控热区至少 48×48dp。",
            "200% 字号、Reduce Motion、动态色版本门和九条低保真均有一页一证据。",
        ],
        "boundaries": [
            "人类可读权威：docs/UI_DESIGN_SYSTEM.md V2 R1；代码 token：CinefinTokens.kt。",
            "S5 证据 JSON 只作输入，不是页面运行时 token 来源。",
        ],
        "deps": ["EV-S5-R1", "V2-D201", "V2-D057"],
        "risks": ["R-S6-P1-06", "R-S6-P2-01", "R-S6-P2-05"],
        "evidence": ["EV-S5-R1", "EV-UI-R1", "EV-S6"],
        "devices": ["Pad 5、K60；折叠 / 分屏若可用则纳入，不可用项必须留白"],
        "writes": ["none"],
        "stop": "若需要改变 R1 视觉方向、D057 其他圆角或引入第二 token 源，停止并请求用户。",
        "artifacts": ["主题 token 审计", "RWD / 折叠状态保持测试", "TalkBack / 键盘 / 200% / Reduce Motion 证据", "九条交互状态报告"],
        "status": "READY_FOR_G2_PLANNING",
    },
    {
        "id": "G2-READER-CORE",
        "role": "资深 Android 阅读器内核 / 数据一致性工程师",
        "objective": "交付可替换的 Publication/BookDocument、source-scoped ReadingPosition、分页 / 滚动、书签、批注、搜索降级、离线阅读和大书恢复核心。",
        "non_goals": [
            "不在本卡重开 `V2-D204` engine strategy A，也不选择高表现力第二引擎、把 A 写成具体引擎已选或把 Sherpa 恢复为默认。",
            "不在本卡锁定 PDFium 或冻结未经试听 / 许可的 exact voice-pack profile。",
            "不把页码作为持久化位置真源。",
            "不把 CBZ 文本搜索或未证实 PDF 文字层伪装为空结果。",
        ],
        "criteria": [
            "四格式来源身份隔离；同名、同 item 异源、异 MediaSource 不串书。",
            "字号 / 行距 / 视口变化后按 locator 回原段落；force-stop / 重启恢复同段。",
            "书签、批注、进度在离线可用，损坏文件不阻塞阅读且无静默丢数据。",
            "PDF / EPUB / TXT / CBZ 搜索能力按格式显式降级；弱网和大书有取消 / 返回 / 重试出口。",
        ],
        "boundaries": [
            "逻辑接口：Publication/BookDocument、ReaderSettings、ReadingPosition、Annotation store、format capabilities。",
            "生产文件边界按模块 owner allowlist 在 G2 实例化；TTS 只在 D204 engine strategy、D203 voice-pack 边界与 exact profile 许可 / matrix 门内推进。",
        ],
        "deps": ["EV-S2", "EV-S3", "EV-A1", "V2-D198", "V2-D199", "V2-D203", "V2-D204"],
        "risks": ["R-S6-P0-01", "R-S6-P1-02", "R-S6-P2-06", "R-S6-P1-13"],
        "evidence": ["EV-S2", "EV-S3", "EV-A1", "EV-S6"],
        "devices": ["Pad 5 主验；K60 抽验；PDF / CBZ 大文件按可用样本"],
        "writes": ["服务器进度写只使用已批准测试账号 / 写服；生产服务器只读。"],
        "stop": "需要改持久 locator 兼容、重开 D204、选择 / 冻结未经许可的第二引擎或 exact profile、访问真实用户数据或服务器写权限时停止；TTS 推进在 exact profile / 许可 / notice / hash / runtime 证据不足时停止。",
        "artifacts": ["格式能力矩阵", "位置 / 批注迁移 fixture", "大书 / force-stop / 弱网旅程", "跨界面进度等式"],
        "status": "READY_FOR_G2_PLANNING",
    },
    {
        "id": "G2-READER-TTS-GATED",
        "role": "资深 Android 离线 TTS / MediaSession 与 voice-pack 集成工程师",
        "objective": "在 D204 engine strategy A 与 D203 voice-pack / 后台锁屏边界内，验证高表现力第二引擎或兼容回退的 exact profile；当前 Sherpa / Matcha 仅作兼容回退，第二引擎与 exact profile 均未选。",
        "non_goals": [
            "不冻结 exact voice-pack profile，不下载或随包任何未批准 voice / model / lexicon。",
            "不把架构 local-only 或静态许可写成零网络 runtime PASS。",
            "不创建第二音频 owner、第二 MediaSession 或伪造时间 seek。",
        ],
        "criteria": [
            "exact voice-pack profile 以用户试听和逐资产许可 / 哈希审计进入；导入只追加，活动音色删除回退且不悬空引用。",
            "飞行模式下 App 零网络仍可开始、暂停、跳句、调速、选音色和收起；后台 / 锁屏由 service owner 延续。",
            "通知、锁屏、UI 与系统控制等式成立；TTS / 音乐互斥，停止 TTS 后音乐不自动恢复。",
            "进程重启只恢复暂停态和最后稳定句子；焦点丢失 / noisy 与控制面一致；性能 / 稳定性有设备记录。",
        ],
        "boundaries": [
            "逻辑接口：LocalTtsPort、VoiceCatalog、VoicePackImporter、VoiceSelectionStore、TtsPlaybackOwner、TtsMediaSession、OfflineCapabilityProbe。",
            "exact engine / profile 仍由 C1 用户试听 + Q03 许可门决定；本卡保持 GATED，不因 D204 关闭 engine strategy 转 READY。",
        ],
        "deps": ["V2-D203", "V2-D204", "V2-Q-G1-03", "EV-Q03-NATIVE", "EV-Q03-TTS-PDF", "EV-S4C"],
        "risks": ["R-S6-P1-07", "R-S6-P1-08", "R-S6-P1-17", "R-S6-P1-18", "R-S6-P1-19", "R-S6-P1-13"],
        "evidence": ["EV-Q03-NATIVE", "EV-Q03-TTS-PDF", "EV-S4C", "EV-S6"],
        "devices": ["Pad 5 与 K60：飞行模式、后台 / 锁屏、进程重启、音频焦点 / noisy、音色导入删除与性能"],
        "writes": ["none"],
        "stop": "第二引擎只停留在 strategy、exact profile 未试听 / 许可未批准、Q03 未关、voice-pack 删除出现悬空引用、后台状态等式或零网络无法证明时保持 GATED/BLOCKED。",
        "artifacts": ["profile 试听与决策包", "资产许可 / hash / notice", "追加导入 / 删除回退 / 隐藏恢复状态机", "后台锁屏 / MediaSession / 单音频 owner 设备报告", "飞行模式零网络与性能报告"],
        "status": "GATED_BY_V2_Q_G1_03",
    },
    {
        "id": "G2-READER-PDF-GATED",
        "role": "资深 Android PDFium / Range 集成与供应链工程师",
        "objective": "在 Q03 解除前准备 PDF 引擎门、Range / 取消 / 大文件、16 KB 与 notice 验收，不替换当前 PdfRenderer。",
        "non_goals": [
            "不暗选 stable 或 alpha PDFium。",
            "不在 16 KB、notice、native revision 或 Range 未证实时替换 PdfRenderer。",
            "不以 AAR 内 API 或体积代替真实大 PDF 旅程。",
        ],
        "criteria": [
            "最终引擎候选有固定 AAR、native revision、许可、notice 与 SBOM。",
            "64-bit ELF 在 16 KB page-size 门和真实设备 dlopen / 渲染均通过。",
            "远程 PDF 请求使用合法 Range / 206，支持取消、弱网和缓存上限。",
            "2.53 GB / 5006 页样本首屏、内存、跳页、搜索取消与渲染保真有记录。",
        ],
        "boundaries": [
            "逻辑接口：PageSource / PdfLayoutSource / PdfSearch / Range transport。",
            "生产替换必须先有 Q03 决策和用户授权；当前 PdfRenderer 回滚路径保留。",
        ],
        "deps": ["V2-Q-G1-03", "EV-Q03-NATIVE", "EV-Q03-TTS-PDF", "EV-S4C"],
        "risks": ["R-S6-P1-09", "R-S6-P1-10", "R-S6-P1-13"],
        "evidence": ["EV-Q03-NATIVE", "EV-Q03-TTS-PDF", "EV-S4C", "EV-S6"],
        "devices": ["Pad 5 与 K60；16 KB 设备 / ABI 可用性按环境记录"],
        "writes": ["生产服务器只读；Range 请求不得产生持久 writes。"],
        "stop": "16 KB、native provenance、notice、Range 或服务器只读边界任一失败即保持 PdfRenderer 并 GATED/BLOCKED。",
        "artifacts": ["引擎决策包", "最终 packaged SBOM / notice", "16 KB 静态与设备证据", "大 PDF Range / 内存报告"],
        "status": "GATED_BY_V2_Q_G1_03",
    },
    {
        "id": "G2-MUSIC-CORE",
        "role": "资深 Android Media3 音乐会话 / 队列工程师",
        "objective": "交付单 ExoPlayer / 单 MediaSession、source-scoped 混合队列、恢复、错误隔离、系统媒体入口与大队列懒补。",
        "non_goals": [
            "不增加第二 Player、第二 MediaSession 或双 deck crossfade。",
            "不把本地文件、服务器曲目或歌单条目按裸 itemId 合并。",
            "不把 Worker / UI 缓存当活动播放队列真源。",
        ],
        "criteria": [
            "音乐与视频经共享仲裁器互斥；通知、UI、队列、音效始终跟同一物理实例。",
            "队列、随机置换、Next Up、当前项与恢复位置在重启后一致。",
            "瞬时流失败最多受限重试一次；401 仅刷新一次；403/404/415 不重试。",
            "349–1000 首队列有界懒补，不逐首同步协商，不阻塞当前播放与 Next Up。",
        ],
        "boundaries": [
            "逻辑接口：TrackSource、PlaybackContext、Next Up、QueueSnapshot、MediaSession owner。",
            "不得修改已接受的单实例不变量；任何双 Player 方案回流用户决策。",
        ],
        "deps": ["EV-S2", "EV-S3", "V2-D196", "V2-D194"],
        "risks": ["R-S6-P1-11", "R-S6-P1-05", "R-S6-P1-13", "R-S6-P2-03"],
        "evidence": ["EV-S3", "EV-S2", "EV-S6"],
        "devices": ["K60 主验；Pad 5 抽验；耳机 / 蓝牙可用时纳入"],
        "writes": ["服务器进度写仅使用批准账号 / 测试写服；生产服务器只读。"],
        "stop": "需要双 Player、双 MediaSession、猜 provenance、无限重试或后台写阻塞播放时停止。",
        "artifacts": ["队列 / 随机 / Next Up 等式测试", "错误分类与重试次数", "进程重建恢复", "349–1000 队列懒补报告"],
        "status": "READY_FOR_G2_PLANNING",
    },
    {
        "id": "G2-LOCAL-PLAYLIST",
        "role": "资深 Android 本地歌单 / SAF 交换工程师",
        "objective": "交付本地歌单 CRUD、排序持久化、版本化 JSON / M3U8 预览、去重 / 缺曲 / 冲突和 SAF 原子导入导出。",
        "non_goals": [
            "不把本地歌单自动转换成服务器歌单。",
            "不按标题、路径或当前会话猜测身份。",
            "不在用户未选择的重复 / 部分导入策略下静默合并。",
        ],
        "criteria": [
            "重启后顺序与成员保持；重排 / 删除符合可逆确认规则。",
            "缺曲保留；身份冲突停止写；跨来源同 itemId / 同标题不合并。",
            "JSON / M3U8 往返逐字段记录 preserve / degrade / loss，并生成预览。",
            "SAF 取消、授权失效、原子写和重启恢复均有设备证据。",
        ],
        "boundaries": [
            "逻辑接口：LOCAL_DRAFT identity、exchange v1 schema、M3U8 mapping、preview / commit。",
            "重复、部分导入和合并策略须先由负责人取得用户决策。",
        ],
        "deps": ["EV-BASE004", "EV-S2", "V2-D196"],
        "risks": ["R-S6-P1-12", "R-S6-P2-03", "R-S6-P1-13"],
        "evidence": ["EV-BASE004", "EV-S2", "EV-S6"],
        "devices": ["Pad 5 与 K60：DocumentsUI / SAF / 重启"],
        "writes": ["仅用户选择的本地文档；服务器歌单写属独立卡。"],
        "stop": "需要猜身份、无确认覆盖、自动服务器复制或用户策略未定时停止。",
        "artifacts": ["歌单迁移测试", "JSON / M3U8 往返 fixture", "SAF 原子写与重启证据", "用户策略记录"],
        "status": "READY_FOR_G2_PLANNING",
    },
    {
        "id": "G2-SERVER-WRITE",
        "role": "资深 Jellyfin Android 写协议 / 权限工程师",
        "objective": "交付当前用户级收藏、已看、播放 / 阅读进度和服务器歌单写的身份、权限、确认、回读与冲突语义。",
        "non_goals": [
            "不实现管理类、媒体删除、元数据或远程会话管理。",
            "不把本地 optimistic state 或 HTTP 200 单独当成功。",
            "不跨 server / user 复用 intent 或凭据。",
        ],
        "criteria": [
            "403 映射 DENIED 且不重试；401/404/409/503 语义可区分。",
            "200 响应后通过同源 GET 回读相等才清除 pending 并传播所有界面。",
            "批次与破坏性动作执行前展示对象、范围、数量、副作用，执行后逐项终态。",
            "切号 / 切服暂停旧 intent；冲突只由用户选择重应用、丢弃或安全回读。",
        ],
        "boundaries": [
            "逻辑接口：server + user scoped intent、endpoint policy、read-back anchor、capability cache。",
            "真实写只允许负责人指定的独立可写测试服务器；生产服务器永久只读。",
        ],
        "deps": ["EV-S3", "V2-D198"],
        "risks": ["R-S6-P1-04", "R-S6-P1-13", "R-S6-P2-07"],
        "evidence": ["EV-S3", "EV-S6"],
        "devices": ["Pad 5 / K60：拒绝、恢复、切号"],
        "writes": ["独立可写测试服务器；凭据只从本机既有安全位置读取，不落仓库 / 日志。"],
        "stop": "需要生产服务器写、管理类扩项、未批准批量动作或权限缓存无法按 server+user 失效时停止。",
        "artifacts": ["请求 / 状态 / 回读契约测试", "允许 / 拒绝 / 失败 / 冲突真实旅程", "逐项批次结果", "跨账号隔离证据"],
        "status": "READY_FOR_G2_PLANNING",
    },
    {
        "id": "G2-DOWNLOAD-STATE",
        "role": "资深 Android 下载状态 / WorkManager / 通知一致性工程师",
        "objective": "交付四类媒体下载、canonical provenance、暂停 / 恢复 / 取消 / 删除、通知 / 文件 / DB / 离线投影一致和 1.4→2.0 迁移。",
        "non_goals": [
            "不承诺转码下载、自动全部版本或公共文件管理器路径。",
            "不把 Worker SUCCESS、通知出现、路径存在或 rename 当 COMPLETED。",
            "不在未知来源上猜测当前账号或静默换 MediaSource。",
        ],
        "criteria": [
            "任务创建前完成网络 / 空间 / 卷 / 来源等预检；重复点击聚焦同一任务。",
            "COMPLETED 需要同代、同源、媒体、必需清单、持久状态与文件可读全部相等。",
            "页面、角标、活动通知共享活动 key 集合；终态通知精确一次且删除后无幽灵。",
            "force-stop / reboot / 跨存储故障后恢复到可解释终态，不出现 UI 完成而文件不存在。",
        ],
        "boundaries": [
            "逻辑接口：canonical identity、DownloadStateMachine、persistent receipt / journal、offline projection。",
            "文件、Room、sidecar、通知与 WAL 证据必须绑定同一 profile / generation。",
        ],
        "deps": ["EV-A1", "EV-S2", "EV-S3", "V2-D199"],
        "risks": ["R-S6-P0-01", "R-S6-P1-02", "R-S6-P1-14", "R-S6-P2-08"],
        "evidence": ["EV-A1", "EV-S2", "EV-S3", "EV-S6"],
        "devices": ["Pad 5 与 K60：force-stop、reboot、断网、存储 / SAF 失效"],
        "writes": ["下载读取生产服务器必须保持只读且不产生写副作用；必要时使用批准测试媒体。"],
        "stop": "若完成 / 删除一致性、来源真实性或迁移无法证明，或需生产写调用、破坏性 fallback 时停止。",
        "artifacts": ["12 状态 / 144 pair 合同结果", "四类媒体真实任务旅程", "跨存储 crash / restart 证据", "通知 / 页面 / 文件等式"],
        "status": "READY_FOR_G2_PLANNING",
    },
    {
        "id": "G2-VIDEO-CORE",
        "role": "资深 Android 双内核视频播放 / 字幕 / 生命周期工程师",
        "objective": "交付单逻辑视频会话、Exo 默认与证据型 mpv、状态保持、格式 / 轨道 / 字幕 / 手势 / PiP / 后台和错误恢复。",
        "non_goals": [
            "不实现 TV、车机、桌面键盘或完整官方周边。",
            "不以扩展名、静态 JNI 或历史 1.4 播放记录冒充格式 runtime PASS。",
            "不因包体删除已验证有价值的 Media3 / mpv / codec / 字幕能力。",
        ],
        "criteria": [
            "切内核、seek、切集、旋转 / 折叠 / PiP 后仍使用同一逻辑身份、generation 与目标位置。",
            "每个真实样本明确 direct / transcode / explicit failure；HDR 仅在完整链路可证实时显示直通。",
            "主次字幕、音轨、50ms 延迟、逐来源恢复、样式能力与无等价轨道降级可观察。",
            "控制层约 4 秒隐藏、手势阈值、48dp、TalkBack / 键盘 / Reduce Motion 均有候选包证据。",
        ],
        "boundaries": [
            "逻辑接口：PlaybackEngine、source epoch、generation、track / subtitle model、single MediaSession owner。",
            "Media3 / mpv native 组合必须服从 Q03 runtime / notice 门；不得私选格式或降级能力。",
        ],
        "deps": ["EV-S3", "EV-S4B", "EV-Q03-NATIVE", "V2-D200"],
        "risks": ["R-S6-P1-06", "R-S6-P1-11", "R-S6-P1-13", "R-S6-P2-09"],
        "evidence": ["EV-S3", "EV-S4B", "EV-Q03-NATIVE", "EV-S6"],
        "devices": ["Pad 5 与 K60：格式 / HDR / 字幕 / 手势 / PiP / 回退"],
        "writes": ["播放进度写只使用批准账号；本地文件不写服务器。"],
        "stop": "双内核 load-order、权限、格式、字幕或状态保持无法证明时保持失败关闭；需要范围扩展时停止。",
        "artifacts": ["双内核格式矩阵", "seek / generation 迟到回调测试", "字幕 / 音轨真实样本", "RWD / 无障碍 / PiP 旅程"],
        "status": "READY_FOR_G2_PLANNING",
    },
    {
        "id": "G2-SYNCPLAY-GATED",
        "role": "资深 Jellyfin SyncPlay Android 协议 / 多端一致性工程师",
        "objective": "在能力探测、协议合同和双客户端环境通过后，交付创建、加入、队列、命令确认、时钟同步与恢复。",
        "non_goals": [
            "不把单机 fake 或 WebSocket mock 当多端同步成功。",
            "不实现聊天、深链或未承诺的主机组倍速。",
            "不在端点 / 时钟 / revision 能力缺失时暴露伪入口。",
        ],
        "criteria": [
            "服务端组队列以 playlist entry id 为身份；主机和访客权限、成员、队列来自权威快照。",
            "PENDING / CONFIRMED / REJECTED、断线、重连和离组可区分；迟到命令不覆盖新 generation。",
            "双端时钟、漂移校正、个人暂停、个人轨道和 PiP / 后台离组按已接受规则执行。",
            "能力不满足时隐藏或禁用并解释，普通播放不被污染。",
        ],
        "boundaries": [
            "逻辑接口：capability map、group snapshot、command correlation、clock estimator、membership state。",
            "需要独立可写测试服务与至少两个客户端实例；生产服务器保持只读。",
        ],
        "deps": ["EV-S3", "V2-Q-G1-03"],
        "risks": ["R-S6-P1-15", "R-S6-P1-13", "R-S6-P2-10"],
        "evidence": ["EV-S3", "EV-S6"],
        "devices": ["两台客户端 / 同一隔离网络；写服为负责人批准的测试实例"],
        "writes": ["独立可写测试服务：组、队列、命令与清理。"],
        "stop": "缺少端点、WebSocket、时钟或 revision / resnapshot 合同，或无法隔离写服时保持 GATED/BLOCKED。",
        "artifacts": ["capability matrix", "双端命令 / 时钟 / 重连记录", "权限与主机转移旅程", "离组清理证据"],
        "status": "GATED_BY_CAPABILITY_AND_TEST_SERVER",
    },
    {
        "id": "G2-NAV-IDENTITY",
        "role": "资深 Android 导航 / 账户身份一致性工程师",
        "objective": "交付一级导航落根 / 回根 / 返回语义、在线与离线账户选择、身份切换原子清场和活动播放收敛。",
        "non_goals": [
            "不恢复旧深层详情或把离线选择变成在线校验。",
            "不跨账号迁移播放会话或自动切换在线。",
            "不让 drawer 失败时保留旧账号内容。",
        ],
        "criteria": [
            "一级切换落根并保留根滚动；重复点击回根；系统返回先处理详情 / 沉浸层。",
            "所有界面只投影当前 server + user；失败显示自身状态，不回退旧内容。",
            "在线 / 离线切号立即暂停并隐藏旧身份在线 / 混合播放；纯本地可继续。",
            "回原身份恢复队列但保持暂停；离线网络恢复不自动切在线。",
        ],
        "boundaries": [
            "逻辑接口：nav root state、identity coordinator、offline projection、playback arbiter。",
            "账户组件和导航边界必须服从 R1 IA；不新增平行账户入口。",
        ],
        "deps": ["EV-S3", "EV-S5-R1", "V2-D189", "V2-D191", "V2-D195"],
        "risks": ["R-S6-P1-11", "R-S6-P2-11", "R-S6-P1-13"],
        "evidence": ["EV-S3", "EV-S5-R1", "EV-S6"],
        "devices": ["Pad 5 与 K60：在线 / 离线切号、返回栈"],
        "writes": ["none；切换只读身份与本地作用域"],
        "stop": "需要跨账号会话迁移、离线 token 校验、自动在线或改变 R1 IA 时停止。",
        "artifacts": ["导航栈 / 根滚动测试", "identity switch state matrix", "活动会话清场证据", "离线 / 在线选择组件语义"],
        "status": "READY_FOR_G2_PLANNING",
    },
    {
        "id": "G2-Q03-SUPPLY-CHAIN-GATED",
        "role": "资深 Android 发布供应链 / 原生许可 / SBOM 工程师",
        "objective": "生成候选包 packaged SBOM、native notice、source bundle / written offer、attestation / relink 与 16 KB 证据。",
        "non_goals": [
            "不把 Gradle graph、AAR hash 或 source URL 当作完整 packaged SBOM / source offer。",
            "不隐藏未知 native 许可或把 wrapper 许可覆盖嵌入 FFmpeg / PDFium。",
            "不在 Q03 关闭前批准 Release。",
        ],
        "criteria": [
            "候选 APK/AAB 的每个 packaged file 可映射到 component / hash / license / notice。",
            "GPL / LGPL 所有义务有可审计 source / build script / relink / written-offer 路线。",
            "native load-order、符号和 16 KB 在候选包与设备运行证据中通过。",
            "SBOM、notice、source bundle、attestation 与候选 artifact 同一 SHA，无未解析组件。",
        ],
        "boundaries": [
            "输入：S4-A/B/C、Q03-SBOM/NATIVE/NOTICE/TTS-PDF 与最终候选 artifact。",
            "D203 voice-pack / 后台锁屏约束保持；V2-D204 已接受 engine strategy A，Sherpa-ONNX v2.0.1 + ONNX Runtime 与 Matcha 仅作兼容回退候选，高表现力第二引擎与 exact voice-pack/profile 均未选择。Q03 仍 Open，等待逐资产许可 / model card / notice / hash / provenance、packaged supply chain 与 runtime；PDF 引擎选择仍按既有 Q03 门。",
        ],
        "deps": ["V2-Q-G1-03", "EV-Q03-SBOM", "EV-Q03-NATIVE", "EV-Q03-NOTICE", "EV-Q03-TTS-PDF"],
        "risks": ["R-S6-P1-07", "R-S6-P1-08", "R-S6-P1-09", "R-S6-P1-10", "R-S6-P1-16"],
        "evidence": ["EV-Q03-SBOM", "EV-Q03-NATIVE", "EV-Q03-NOTICE", "EV-Q03-TTS-PDF", "EV-S6"],
        "devices": ["候选目标 ABI 设备；16 KB 设备可用时纳入"],
        "writes": ["none"],
        "stop": "Q03 未关、候选 artifact 不存在、native provenance / notices / source obligations 或 16 KB 未闭合时保持 GATED/BLOCKED。",
        "artifacts": ["packaged-file SBOM", "complete notices", "source bundle / written offer", "attestation / rebuild / relink", "runtime load-order 与 16 KB"],
        "status": "GATED_BY_V2_Q_G1_03",
    },
]

CARD_DEPENDENCIES = {
    "G2-ARCH-CONTRACTS-MIGRATION": [],
    "G2-SEARCH": ["G2-ARCH-CONTRACTS-MIGRATION", "G2-PERSISTENT-SWR", "G2-UI-R1-FOUNDATION"],
    "G2-UPDATE-TRUST": ["G2-ARCH-CONTRACTS-MIGRATION"],
    "G2-PERSISTENT-SWR": ["G2-ARCH-CONTRACTS-MIGRATION"],
    "G2-UI-R1-FOUNDATION": [],
    "G2-READER-CORE": ["G2-ARCH-CONTRACTS-MIGRATION", "G2-UI-R1-FOUNDATION"],
    "G2-READER-TTS-GATED": [
        "G2-READER-CORE",
        "G2-MUSIC-CORE",
        "G2-VIDEO-CORE",
    ],
    "G2-READER-PDF-GATED": ["G2-READER-CORE"],
    "G2-MUSIC-CORE": ["G2-ARCH-CONTRACTS-MIGRATION", "G2-UI-R1-FOUNDATION"],
    "G2-LOCAL-PLAYLIST": ["G2-ARCH-CONTRACTS-MIGRATION", "G2-MUSIC-CORE"],
    "G2-SERVER-WRITE": ["G2-ARCH-CONTRACTS-MIGRATION"],
    "G2-DOWNLOAD-STATE": ["G2-ARCH-CONTRACTS-MIGRATION", "G2-UI-R1-FOUNDATION"],
    "G2-VIDEO-CORE": ["G2-ARCH-CONTRACTS-MIGRATION", "G2-UI-R1-FOUNDATION", "G2-MUSIC-CORE"],
    "G2-SYNCPLAY-GATED": ["G2-SERVER-WRITE", "G2-VIDEO-CORE"],
    "G2-NAV-IDENTITY": ["G2-UI-R1-FOUNDATION", "G2-MUSIC-CORE", "G2-VIDEO-CORE"],
    "G2-Q03-SUPPLY-CHAIN-GATED": [
        "G2-ARCH-CONTRACTS-MIGRATION",
        "G2-READER-TTS-GATED",
        "G2-READER-PDF-GATED",
        "G2-VIDEO-CORE",
    ],
}

CARD_WAVE_DEFS = {
    "G2-ARCH-CONTRACTS-MIGRATION": [
        ("contracts-and-owners", "冻结 contracts seam、owner allowlist 与 debt ratchet。", "架构违规基线归零 / 只减不增报告"),
        ("migration-core", "实现可恢复 journal、DB / sidecar / 文件等式与 FAIL_CLOSED。", "迁移正负 fixture 与跨存储状态机"),
        ("migration-candidate", "在可丢弃 profile 执行候选迁移、强停、重启与恢复。", "E4/E5 迁移旅程和证据 SHA"),
    ],
    "G2-SEARCH": [
        ("state-contract", "完成 complete query shape、generation 与空 / 错 / 离线分离。", "状态 fixture 与 mutation 测试"),
        ("candidate-journey", "在候选包执行正结果、空、错、离线与返回恢复。", "真实搜索 journeys 与时延 trace"),
    ],
    "G2-UPDATE-TRUST": [
        ("trust-gates", "完成来源、版本、ABI、digest、signer 与 fail-closed 门。", "正负合同 fixture 与候选包验证"),
        ("installer-journey", "执行系统安装器交接、取消和安装目标回读。", "真实更新 / 保留数据证据"),
    ],
    "G2-PERSISTENT-SWR": [
        ("cache-core", "完成 identity、stale projection、generation fence 与写后失效。", "跨账号 / 并发 / 写后失效测试"),
        ("runtime-window", "执行墙钟、304 门、进程重建与用户策略边界。", "候选包时序与存储证据"),
    ],
    "G2-UI-R1-FOUNDATION": [
        ("tokens-and-themes", "落实唯一 token 源、五主题与动态色解析链。", "token / 主题 / 对比度审计"),
        ("rwd-and-states", "落实五档 RWD、折叠与页面 / 控件状态保持。", "窗口状态矩阵与设备证据"),
        ("a11y-and-motion", "落实名称、48dp、200%、焦点与 Reduce Motion。", "TalkBack / 键盘 / 重排证据"),
    ],
    "G2-READER-CORE": [
        ("ports-and-identity", "完成 Publication/BookDocument、source identity 与能力探测。", "格式能力与身份隔离测试"),
        ("layout-and-position", "完成分页 / 滚动、locator、设置重排与持久恢复。", "跨引擎位置等式与重启证据"),
        ("annotations-and-search", "完成书签、批注、搜索降级与本地原子持久化。", "格式化批注 / 搜索正负样本"),
        ("large-book-recovery", "完成大书内存、取消、force-stop 与弱网恢复。", "5000 页级 / 大 CBZ / 弱网报告"),
    ],
    "G2-READER-TTS-GATED": [
        ("q03-decision", "消费 D203 voice-pack / 后台锁屏基线与 V2-D204 engine strategy A；Sherpa / Matcha 兼容回退，exact engine / profile 的用户试听、逐资产许可 / notice / hash 与 matrix 未关闭。", "exact engine / profile 试听许可矩阵与资产 hash / notice"),
        ("offline-runtime", "执行飞行模式、句级同步、暂停恢复与性能旅程。", "零网络和性能设备证据"),
        ("background-session", "交付进程 / 前台 service 持有、后台锁屏、通知 / MediaSession 等式与重启暂停恢复。", "后台锁屏与进程重启设备报告"),
        ("voice-catalog-import", "交付统一音色目录、追加导入、幂等身份、全局默认与逐书覆盖。", "voice-pack 目录 / 导入 / 选择状态机"),
        ("voice-delete-fallback", "交付导入音色删除、内置隐藏恢复、活动回退、引用清理与待重试终态。", "voice-pack 删除故障注入报告"),
        ("audio-arbitration", "交付单音频 owner、音乐 / TTS 互斥、audio focus / noisy 与恢复收敛。", "audio owner / MediaSession 设备等式"),
    ],
    "G2-READER-PDF-GATED": [
        ("engine-gate", "冻结 PDFium revision、AAR、16 KB 与 notice 门。", "静态 16 KB / SBOM / notice"),
        ("range-spike", "验证 Range、206、取消、弱网和大文件页窗口。", "2.53GB 级 PDF spike"),
        ("candidate-swap", "在回滚保留前提下执行候选包 PDF 全旅程。", "引擎对照与真实阅读证据"),
    ],
    "G2-MUSIC-CORE": [
        ("single-session", "完成单 ExoPlayer / 单 MediaSession 与音视频仲裁。", "单实例 / 互斥等式测试"),
        ("queue-recovery", "完成 source-scoped 队列、Next Up、随机与重启恢复。", "队列恢复与错误分类证据"),
        ("large-queue", "完成有界懒补、PlaybackInfo 合并与写放大测量。", "349–1000 队列候选包报告"),
        ("local-library-index", "完成首次本地曲库扫描、元数据提取与可复用索引。", "本地扫描 / 元数据缓存 fixture"),
        ("now-playing-experience", "完成 Now Playing、歌词、音效、手势与无障碍状态联动。", "音乐呈现与恢复矩阵"),
    ],
    "G2-LOCAL-PLAYLIST": [
        ("exchange-contract", "完成 LOCAL_DRAFT identity、schema、往返预览与冲突负例。", "JSON / M3U8 roundtrip fixture"),
        ("saf-persistence", "执行 SAF 原子写、取消、授权失效与重启恢复。", "DocumentsUI / SAF 设备证据"),
    ],
    "G2-SERVER-WRITE": [
        ("write-contract", "完成 identity、权限、read-back、409 与离线 intent 合同。", "写状态机与请求/回读 fixture"),
        ("writable-server", "在独立写服执行允许、拒绝、冲突、批次与恢复旅程。", "E6 真实写旅程与逐项清理"),
    ],
    "G2-DOWNLOAD-STATE": [
        ("state-and-preflight", "完成 canonical identity、预检与 12 状态机。", "状态 / 身份 / 预检 fixture"),
        ("cross-store-control", "完成暂停恢复、取消删除、通知与跨存储补偿。", "WAL / file / receipt 证据"),
        ("four-media-candidate", "候选包执行四类媒体、离线、身份切换与迁移。", "E5/E7 核心下载矩阵"),
    ],
    "G2-VIDEO-CORE": [
        ("engine-session", "完成统一 PlaybackEngine、generation、单 MediaSession 与切内核状态。", "双内核状态保留 fixture"),
        ("format-and-fallback", "执行容器 / 编解码 / direct / transcode / HDR 能力矩阵。", "真实样本与回退证据"),
        ("tracks-and-subtitles", "完成音轨、主次字幕、延迟、样式与恢复。", "双内核字幕 / 音轨矩阵"),
        ("controls-and-lifecycle", "完成控制层、手势、PiP、后台与 RWD / 无障碍旅程。", "候选包 UI / 系统入口证据"),
    ],
    "G2-SYNCPLAY-GATED": [
        ("capability-contract", "冻结服务端端点、时钟、队列 entry id 与 resnapshot 合同。", "versioned capability map"),
        ("two-client-spike", "在两客户端执行命令、漂移、重连和主机转移。", "E6 双端同步证据"),
        ("recovery-and-exit", "执行后台 / PiP / 离组 / 失败恢复与普通播放收敛。", "多端恢复和清理矩阵"),
    ],
    "G2-NAV-IDENTITY": [
        ("route-contract", "完成一级落根、回根、返回栈与滚动保持。", "导航状态机与返回恢复证据"),
        ("identity-switch", "完成在线 / 离线切号、旧会话暂停隐藏与回原身份恢复。", "跨界面身份投影与活动播放等式"),
    ],
    "G2-Q03-SUPPLY-CHAIN-GATED": [
        ("artifact-inventory", "对候选 APK/AAB 生成 file-to-component SBOM 与 notice 映射。", "packaged SBOM / notice"),
        ("source-and-attestation", "完成 source bundle / written offer、rebuild 或 attestation。", "source obligation / attestation 证据"),
        ("runtime-and-16kb", "执行 native load-order、符号、R8 与 16 KB runtime 门。", "目标 ABI 设备证据"),
    ],
}


def _found_file(path: str) -> dict[str, str]:
    return {"kind": "FILE", "path": path, "status": "FOUND"}


def _planned_file(path: str) -> dict[str, str]:
    return {"kind": "FILE", "path": path, "status": "PLANNED"}


def _planned_interface(fqcn: str) -> dict[str, str]:
    return {"kind": "INTERFACE", "fqcn": fqcn, "status": "PLANNED"}


CARD_WAVE_BOUNDARIES = {
    ("G2-ARCH-CONTRACTS-MIGRATION", "contracts-and-owners"): [
        _found_file("settings.gradle.kts"),
        _found_file("build.gradle.kts"),
        _found_file("data/src/main/java/com/zhangwenkang/cinefin/repository/JellyfinRepository.kt"),
        _found_file("core/src/main/java/com/zhangwenkang/cinefin/utils/Downloader.kt"),
        _found_file("player/local/src/main/java/com/zhangwenkang/cinefin/player/local/domain/PlaybackCoordinator.kt"),
        _found_file("app/phone/src/main/java/com/zhangwenkang/cinefin/playback/CinefinPlaybackService.kt"),
        _planned_interface("com.zhangwenkang.cinefin.core.domain.contract.ArchitectureOwnerGate"),
        _planned_interface("com.zhangwenkang.cinefin.core.domain.contract.CrossLayerContract"),
    ],
    ("G2-ARCH-CONTRACTS-MIGRATION", "migration-core"): [
        _found_file("data/src/main/java/com/zhangwenkang/cinefin/database/ServerDatabase.kt"),
        _found_file("data/src/main/java/com/zhangwenkang/cinefin/database/ServerDatabaseDao.kt"),
        _found_file("data/src/main/java/com/zhangwenkang/cinefin/utils/DownloadMediaSidecar.kt"),
        _found_file("core/src/main/java/com/zhangwenkang/cinefin/utils/DownloaderImpl.kt"),
        _found_file("data/src/test/java/com/zhangwenkang/cinefin/database/ServerDatabaseMigrationContractTest.kt"),
        _found_file("data/src/androidTest/java/com/zhangwenkang/cinefin/database/ServerDatabaseMigrationTest.kt"),
        _planned_interface("com.zhangwenkang.cinefin.core.domain.contract.ForwardMigrationReceipt"),
        _planned_interface("com.zhangwenkang.cinefin.core.domain.contract.CrossStoreReconciler"),
    ],
    ("G2-ARCH-CONTRACTS-MIGRATION", "migration-candidate"): [
        _found_file("data/src/test/java/com/zhangwenkang/cinefin/database/ServerDatabaseMigrationContractTest.kt"),
        _found_file("data/src/test/java/com/zhangwenkang/cinefin/database/DownloadMusicIntentPersistenceContractTest.kt"),
        _found_file("data/src/test/java/com/zhangwenkang/cinefin/repository/OriginScopedDownloadSourceTest.kt"),
        _found_file("core/src/test/java/com/zhangwenkang/cinefin/utils/DownloadIntentRestartReconciliationTest.kt"),
        _found_file("core/src/test/java/com/zhangwenkang/cinefin/utils/DownloadCanonicalIdentityTest.kt"),
        _planned_file("app/phone/src/androidTest/java/com/zhangwenkang/cinefin/migration/CandidateMigrationJourneyTest.kt"),
        _planned_interface("com.zhangwenkang.cinefin.migration.CandidateMigrationReport"),
    ],
    ("G2-SEARCH", "state-contract"): [
        _found_file("data/src/main/java/com/zhangwenkang/cinefin/repository/SearchQueryRules.kt"),
        _found_file("data/src/main/java/com/zhangwenkang/cinefin/repository/JellyfinRepository.kt"),
        _found_file("modes/film/src/main/java/com/zhangwenkang/cinefin/film/presentation/search/SearchState.kt"),
        _found_file("modes/film/src/main/java/com/zhangwenkang/cinefin/film/presentation/search/SearchViewModel.kt"),
        _found_file("app/phone/src/main/java/com/zhangwenkang/cinefin/presentation/film/SearchScreen.kt"),
        _found_file("data/src/test/java/com/zhangwenkang/cinefin/repository/SearchQueryRulesTest.kt"),
        _planned_interface("com.zhangwenkang.cinefin.repository.search.CompleteSearchQueryShape"),
        _planned_interface("com.zhangwenkang.cinefin.presentation.search.SearchGenerationFence"),
    ],
    ("G2-SEARCH", "candidate-journey"): [
        _found_file("app/phone/src/main/java/com/zhangwenkang/cinefin/presentation/film/SearchScreen.kt"),
        _found_file("app/phone/src/main/java/com/zhangwenkang/cinefin/presentation/navigation/TopLevelNavigation.kt"),
        _found_file("data/src/main/java/com/zhangwenkang/cinefin/repository/SearchQueryRules.kt"),
        _found_file("data/src/test/java/com/zhangwenkang/cinefin/repository/SearchQueryRulesTest.kt"),
        _found_file("app/phone/src/test/java/com/zhangwenkang/cinefin/presentation/search/SearchViewModelTest.kt"),
        _planned_file("app/phone/src/androidTest/java/com/zhangwenkang/cinefin/search/SearchCandidateJourneyTest.kt"),
    ],
    ("G2-UPDATE-TRUST", "trust-gates"): [
        _found_file("data/src/main/java/com/zhangwenkang/cinefin/update/UpdateRepository.kt"),
        _found_file("data/src/main/java/com/zhangwenkang/cinefin/update/UpdateHttpDownloader.kt"),
        _found_file("data/src/main/java/com/zhangwenkang/cinefin/update/UpdateStateStore.kt"),
        _found_file("core/src/main/java/com/zhangwenkang/cinefin/update/UpdateWorkScheduler.kt"),
        _found_file("app/phone/src/main/java/com/zhangwenkang/cinefin/presentation/update/UpdateViewModel.kt"),
        _found_file("data/src/test/java/com/zhangwenkang/cinefin/update/UpdateHttpDownloaderTest.kt"),
        _planned_interface("com.zhangwenkang.cinefin.update.UpdateTrustVerifier"),
        _planned_interface("com.zhangwenkang.cinefin.update.InstallerResultReader"),
    ],
    ("G2-UPDATE-TRUST", "installer-journey"): [
        _found_file("app/phone/src/main/java/com/zhangwenkang/cinefin/presentation/update/UpdateScreen.kt"),
        _found_file("app/phone/src/main/java/com/zhangwenkang/cinefin/presentation/update/UpdateViewModel.kt"),
        _found_file("app/phone/src/main/java/com/zhangwenkang/cinefin/presentation/update/AndroidUpdateInstallerGateway.kt"),
        _found_file("app/phone/src/main/java/com/zhangwenkang/cinefin/presentation/update/UpdateNavigation.kt"),
        _found_file("app/phone/src/main/AndroidManifest.xml"),
        _planned_file("app/phone/src/androidTest/java/com/zhangwenkang/cinefin/update/InstallerHandoffJourneyTest.kt"),
    ],
    ("G2-PERSISTENT-SWR", "cache-core"): [
        _found_file("data/src/main/java/com/zhangwenkang/cinefin/repository/MetadataSnapshotStore.kt"),
        _found_file("data/src/main/java/com/zhangwenkang/cinefin/repository/MetadataSnapshotSwr.kt"),
        _found_file("data/src/main/java/com/zhangwenkang/cinefin/repository/MetadataSnapshotCodec.kt"),
        _found_file("data/src/main/java/com/zhangwenkang/cinefin/repository/MetadataRequestCoordinator.kt"),
        _found_file("data/src/test/java/com/zhangwenkang/cinefin/repository/MetadataSnapshotSwrTest.kt"),
        _found_file("data/src/test/java/com/zhangwenkang/cinefin/repository/MetadataRequestCoordinatorTest.kt"),
        _planned_interface("com.zhangwenkang.cinefin.repository.cache.DurableGenerationFence"),
    ],
    ("G2-PERSISTENT-SWR", "runtime-window"): [
        _found_file("data/src/main/java/com/zhangwenkang/cinefin/repository/MetadataCache.kt"),
        _found_file("data/src/main/java/com/zhangwenkang/cinefin/repository/MetadataPreloader.kt"),
        _found_file("data/src/main/java/com/zhangwenkang/cinefin/repository/MetadataSnapshotStore.kt"),
        _found_file("data/src/test/java/com/zhangwenkang/cinefin/repository/MetadataCacheTest.kt"),
        _found_file("data/src/test/java/com/zhangwenkang/cinefin/repository/MetadataSnapshotStoreTest.kt"),
        _planned_file("app/phone/src/androidTest/java/com/zhangwenkang/cinefin/cache/CacheRuntimeWindowTest.kt"),
    ],
    ("G2-UI-R1-FOUNDATION", "tokens-and-themes"): [
        _found_file("core/src/main/java/com/zhangwenkang/cinefin/core/presentation/theme/CinefinTokens.kt"),
        _found_file("core/src/main/java/com/zhangwenkang/cinefin/core/presentation/theme/CinefinColors.kt"),
        _found_file("core/src/main/java/com/zhangwenkang/cinefin/core/presentation/theme/CinefinTheme.kt"),
        _found_file("core/src/main/java/com/zhangwenkang/cinefin/core/presentation/theme/CinefinShape.kt"),
        _found_file("core/src/main/java/com/zhangwenkang/cinefin/core/presentation/theme/CinefinMotion.kt"),
        _found_file("core/src/main/java/com/zhangwenkang/cinefin/core/presentation/theme/CinefinType.kt"),
        _found_file("core/src/test/java/com/zhangwenkang/cinefin/core/presentation/theme/DesignTokenConsistencyTest.kt"),
        _planned_interface("com.zhangwenkang.cinefin.core.presentation.theme.ThemeResolver"),
    ],
    ("G2-UI-R1-FOUNDATION", "rwd-and-states"): [
        _found_file("app/phone/src/main/java/com/zhangwenkang/cinefin/NavigationRoot.kt"),
        _found_file("app/phone/src/main/java/com/zhangwenkang/cinefin/presentation/navigation/TopLevelNavigation.kt"),
        _found_file("app/phone/src/main/java/com/zhangwenkang/cinefin/presentation/navigation/NavigationIa.kt"),
        _found_file("app/phone/src/main/java/com/zhangwenkang/cinefin/presentation/navigation/CinefinDrawer.kt"),
        _found_file("app/phone/src/main/java/com/zhangwenkang/cinefin/presentation/setup/users/UsersScreen.kt"),
        _found_file("app/phone/src/test/java/com/zhangwenkang/cinefin/presentation/navigation/AdaptiveSidebarWidthTest.kt"),
        _planned_interface("com.zhangwenkang.cinefin.presentation.window.WindowStateRetentionPolicy"),
    ],
    ("G2-UI-R1-FOUNDATION", "a11y-and-motion"): [
        _found_file("core/src/main/java/com/zhangwenkang/cinefin/core/presentation/components/CinefinButton.kt"),
        _found_file("core/src/main/java/com/zhangwenkang/cinefin/core/presentation/components/CinefinInteraction.kt"),
        _found_file("core/src/main/java/com/zhangwenkang/cinefin/core/presentation/components/CinefinPageTopBar.kt"),
        _found_file("core/src/main/java/com/zhangwenkang/cinefin/core/presentation/components/CinefinDrawer.kt"),
        _found_file("core/src/main/java/com/zhangwenkang/cinefin/core/presentation/components/CinefinSnackbar.kt"),
        _planned_interface("com.zhangwenkang.cinefin.core.presentation.a11y.AccessibleActionContract"),
        _planned_file("app/phone/src/androidTest/java/com/zhangwenkang/cinefin/a11y/AccessibleActionJourneyTest.kt"),
    ],
    ("G2-READER-CORE", "ports-and-identity"): [
        _found_file("modes/book/src/main/java/com/zhangwenkang/cinefin/book/presentation/reader/BookDocument.kt"),
        _found_file("modes/book/src/main/java/com/zhangwenkang/cinefin/book/presentation/reader/BookFormat.kt"),
        _found_file("modes/book/src/main/java/com/zhangwenkang/cinefin/book/presentation/reader/PageSource.kt"),
        _found_file("data/src/main/java/com/zhangwenkang/cinefin/repository/ReaderRepository.kt"),
        _found_file("data/src/main/java/com/zhangwenkang/cinefin/repository/ReaderRepositoryImpl.kt"),
        _found_file("data/src/test/java/com/zhangwenkang/cinefin/repository/ReaderBookmarkCodecTest.kt"),
        _planned_interface("com.zhangwenkang.cinefin.book.domain.PublicationIdentity"),
    ],
    ("G2-READER-CORE", "layout-and-position"): [
        _found_file("modes/book/src/main/java/com/zhangwenkang/cinefin/book/presentation/reader/ReaderScreen.kt"),
        _found_file("modes/book/src/main/java/com/zhangwenkang/cinefin/book/presentation/reader/ReaderSettings.kt"),
        _found_file("modes/book/src/main/java/com/zhangwenkang/cinefin/book/presentation/reader/ReaderViewModel.kt"),
        _found_file("data/src/main/java/com/zhangwenkang/cinefin/repository/ReadingProgressMapping.kt"),
        _found_file("data/src/main/java/com/zhangwenkang/cinefin/repository/ReaderProgressStore.kt"),
        _found_file("modes/book/src/test/java/com/zhangwenkang/cinefin/book/presentation/reader/ReaderSettingsTest.kt"),
        _planned_interface("com.zhangwenkang.cinefin.book.domain.StableReadingLocator"),
    ],
    ("G2-READER-CORE", "annotations-and-search"): [
        _found_file("modes/book/src/main/java/com/zhangwenkang/cinefin/book/presentation/reader/ReaderAnnotation.kt"),
        _found_file("modes/book/src/main/java/com/zhangwenkang/cinefin/book/presentation/reader/ReaderAnnotationStore.kt"),
        _found_file("modes/book/src/main/java/com/zhangwenkang/cinefin/book/presentation/reader/PdfSearch.kt"),
        _found_file("modes/book/src/main/java/com/zhangwenkang/cinefin/book/presentation/reader/ReaderSearchSheet.kt"),
        _found_file("modes/book/src/test/java/com/zhangwenkang/cinefin/book/presentation/reader/ReaderAnnotationTest.kt"),
        _found_file("modes/book/src/test/java/com/zhangwenkang/cinefin/book/presentation/reader/PdfSearchTest.kt"),
        _planned_interface("com.zhangwenkang.cinefin.book.domain.ReaderSearchCapability"),
    ],
    ("G2-READER-CORE", "large-book-recovery"): [
        _found_file("modes/book/src/main/java/com/zhangwenkang/cinefin/book/presentation/reader/ReaderStreamingPlan.kt"),
        _found_file("modes/book/src/main/java/com/zhangwenkang/cinefin/book/presentation/reader/RemoteComicPageSource.kt"),
        _found_file("modes/book/src/main/java/com/zhangwenkang/cinefin/book/presentation/reader/PreciseHttpResource.kt"),
        _found_file("modes/book/src/test/java/com/zhangwenkang/cinefin/book/presentation/reader/ReaderStreamingPlanTest.kt"),
        _found_file("modes/book/src/test/java/com/zhangwenkang/cinefin/book/presentation/reader/RemoteComicPageSourceTest.kt"),
        _planned_file("app/phone/src/androidTest/java/com/zhangwenkang/cinefin/reader/LargeBookRecoveryJourneyTest.kt"),
    ],
    ("G2-READER-TTS-GATED", "q03-decision"): [
        _found_file("modes/book/build.gradle.kts"),
        _found_file("modes/book/src/main/java/com/zhangwenkang/cinefin/book/presentation/reader/ReaderScreen.kt"),
        _found_file("modes/book/src/main/java/com/zhangwenkang/cinefin/book/presentation/reader/ReaderViewModel.kt"),
        _planned_interface("com.zhangwenkang.cinefin.book.tts.LocalTtsPort"),
        _planned_interface("com.zhangwenkang.cinefin.book.tts.VoiceCatalog"),
        _planned_interface("com.zhangwenkang.cinefin.book.tts.OfflineCapabilityProbe"),
    ],
    ("G2-READER-TTS-GATED", "offline-runtime"): [
        _found_file("modes/book/src/main/java/com/zhangwenkang/cinefin/book/presentation/reader/ReaderScreen.kt"),
        _found_file("modes/book/src/main/java/com/zhangwenkang/cinefin/book/presentation/reader/ReaderViewModel.kt"),
        _found_file("modes/book/src/main/java/com/zhangwenkang/cinefin/book/presentation/reader/ReaderAnnotationSheet.kt"),
        _planned_file("app/phone/src/androidTest/java/com/zhangwenkang/cinefin/reader/TtsOfflineJourneyTest.kt"),
        _planned_interface("com.zhangwenkang.cinefin.book.tts.TtsPlaybackState"),
    ],
    ("G2-READER-TTS-GATED", "background-session"): [
        _found_file("modes/book/src/main/java/com/zhangwenkang/cinefin/book/presentation/reader/ReaderViewModel.kt"),
        _found_file("app/phone/src/main/java/com/zhangwenkang/cinefin/playback/CinefinPlaybackService.kt"),
        _found_file("app/phone/src/main/java/com/zhangwenkang/cinefin/playback/CinefinMediaNotificationProvider.kt"),
        _planned_interface("com.zhangwenkang.cinefin.book.tts.TtsPlaybackOwner"),
        _planned_interface("com.zhangwenkang.cinefin.book.tts.TtsMediaSession"),
        _planned_file("app/phone/src/androidTest/java/com/zhangwenkang/cinefin/reader/TtsBackgroundLifecycleTest.kt"),
    ],
    ("G2-READER-TTS-GATED", "voice-catalog-import"): [
        _found_file("modes/book/src/main/java/com/zhangwenkang/cinefin/book/presentation/reader/ReaderScreen.kt"),
        _found_file("modes/book/src/main/java/com/zhangwenkang/cinefin/book/presentation/reader/ReaderViewModel.kt"),
        _planned_interface("com.zhangwenkang.cinefin.book.tts.VoiceCatalog"),
        _planned_interface("com.zhangwenkang.cinefin.book.tts.VoicePackImporter"),
        _planned_interface("com.zhangwenkang.cinefin.book.tts.VoiceSelectionStore"),
        _planned_file("modes/book/src/test/java/com/zhangwenkang/cinefin/book/tts/VoicePackCatalogTest.kt"),
    ],
    ("G2-READER-TTS-GATED", "voice-delete-fallback"): [
        _found_file("modes/book/src/main/java/com/zhangwenkang/cinefin/book/presentation/reader/ReaderViewModel.kt"),
        _planned_interface("com.zhangwenkang.cinefin.book.tts.VoiceCatalog"),
        _planned_interface("com.zhangwenkang.cinefin.book.tts.VoiceSelectionStore"),
        _planned_interface("com.zhangwenkang.cinefin.book.tts.VoiceDeletionReceipt"),
        _planned_file("modes/book/src/test/java/com/zhangwenkang/cinefin/book/tts/VoiceDeletionFallbackTest.kt"),
    ],
    ("G2-READER-TTS-GATED", "audio-arbitration"): [
        _found_file("player/local/src/main/java/com/zhangwenkang/cinefin/player/local/domain/PlaybackCoordinator.kt"),
        _found_file("app/phone/src/main/java/com/zhangwenkang/cinefin/playback/CinefinPlaybackService.kt"),
        _found_file("app/phone/src/main/java/com/zhangwenkang/cinefin/playback/CinefinMediaNotificationProvider.kt"),
        _planned_interface("com.zhangwenkang.cinefin.player.domain.SingleSessionArbiter"),
        _planned_interface("com.zhangwenkang.cinefin.book.tts.TtsPlaybackOwner"),
        _planned_interface("com.zhangwenkang.cinefin.book.tts.TtsAudioFocusController"),
        _planned_file("app/phone/src/androidTest/java/com/zhangwenkang/cinefin/reader/TtsAudioOwnerJourneyTest.kt"),
    ],
    ("G2-READER-PDF-GATED", "engine-gate"): [
        _found_file("modes/book/build.gradle.kts"),
        _found_file("modes/book/src/main/java/com/zhangwenkang/cinefin/book/presentation/reader/PdfLayoutSource.kt"),
        _found_file("modes/book/src/main/java/com/zhangwenkang/cinefin/book/presentation/reader/PdfSearch.kt"),
        _planned_interface("com.zhangwenkang.cinefin.book.pdf.PdfEngineProvenance"),
        _planned_interface("com.zhangwenkang.cinefin.book.pdf.Pdf16KbGate"),
    ],
    ("G2-READER-PDF-GATED", "range-spike"): [
        _found_file("data/src/main/java/com/zhangwenkang/cinefin/repository/BookDownloadRangeTransfer.kt"),
        _found_file("modes/book/src/main/java/com/zhangwenkang/cinefin/book/presentation/reader/PreciseHttpResource.kt"),
        _found_file("modes/book/src/main/java/com/zhangwenkang/cinefin/book/presentation/reader/PdfLayoutSource.kt"),
        _found_file("data/src/test/java/com/zhangwenkang/cinefin/repository/BookDownloadRangeResumeTest.kt"),
        _planned_interface("com.zhangwenkang.cinefin.book.pdf.PdfRangeWindow"),
    ],
    ("G2-READER-PDF-GATED", "candidate-swap"): [
        _found_file("modes/book/src/main/java/com/zhangwenkang/cinefin/book/presentation/reader/ReaderActivity.kt"),
        _found_file("modes/book/src/main/java/com/zhangwenkang/cinefin/book/presentation/reader/ReaderScreen.kt"),
        _found_file("modes/book/src/main/java/com/zhangwenkang/cinefin/book/presentation/reader/PdfLayoutSource.kt"),
        _planned_file("app/phone/src/androidTest/java/com/zhangwenkang/cinefin/reader/PdfCandidateSwapJourneyTest.kt"),
        _planned_interface("com.zhangwenkang.cinefin.book.pdf.PdfEngineRollbackPolicy"),
    ],
    ("G2-MUSIC-CORE", "single-session"): [
        _found_file("player/local/src/main/java/com/zhangwenkang/cinefin/player/local/domain/PlaybackCoordinator.kt"),
        _found_file("player/local/src/main/java/com/zhangwenkang/cinefin/player/local/domain/PlaybackCoordinatorImpl.kt"),
        _found_file("player/local/src/main/java/com/zhangwenkang/cinefin/player/local/domain/MusicPlaybackController.kt"),
        _found_file("player/local/src/main/java/com/zhangwenkang/cinefin/player/local/presentation/PlayerHolder.kt"),
        _found_file("app/phone/src/main/java/com/zhangwenkang/cinefin/playback/CinefinPlaybackService.kt"),
        _found_file("app/phone/src/main/java/com/zhangwenkang/cinefin/playback/CinefinMediaNotificationProvider.kt"),
        _planned_interface("com.zhangwenkang.cinefin.player.domain.SingleSessionArbiter"),
    ],
    ("G2-MUSIC-CORE", "queue-recovery"): [
        _found_file("player/local/src/main/java/com/zhangwenkang/cinefin/player/local/domain/PlaylistManager.kt"),
        _found_file("player/local/src/main/java/com/zhangwenkang/cinefin/player/local/domain/PlaybackPositionWriter.kt"),
        _found_file("modes/music/src/main/java/com/zhangwenkang/cinefin/music/data/MusicQueueStore.kt"),
        _found_file("modes/music/src/main/java/com/zhangwenkang/cinefin/music/data/MusicQueuePersister.kt"),
        _found_file("modes/music/src/main/java/com/zhangwenkang/cinefin/music/data/MusicQueueEdits.kt"),
        _found_file("modes/music/src/test/java/com/zhangwenkang/cinefin/music/data/MusicQueueStoreTest.kt"),
        _planned_interface("com.zhangwenkang.cinefin.music.domain.SourceScopedQueueSnapshot"),
    ],
    ("G2-MUSIC-CORE", "large-queue"): [
        _found_file("modes/music/src/main/java/com/zhangwenkang/cinefin/music/data/MusicQueueFill.kt"),
        _found_file("modes/music/src/main/java/com/zhangwenkang/cinefin/music/data/MusicRepository.kt"),
        _found_file("modes/music/src/main/java/com/zhangwenkang/cinefin/music/data/MusicTrackResolver.kt"),
        _found_file("modes/music/src/test/java/com/zhangwenkang/cinefin/music/data/MusicQueueFillTest.kt"),
        _planned_interface("com.zhangwenkang.cinefin.music.domain.BoundedQueueFillPolicy"),
    ],
    ("G2-MUSIC-CORE", "local-library-index"): [
        _found_file("data/src/main/java/com/zhangwenkang/cinefin/local/LocalLibraryRepository.kt"),
        _found_file("data/src/main/java/com/zhangwenkang/cinefin/local/LocalLibraryRepositoryImpl.kt"),
        _found_file("data/src/main/java/com/zhangwenkang/cinefin/repository/MusicTrackMetadata.kt"),
        _found_file("data/src/main/java/com/zhangwenkang/cinefin/database/music/MusicStorage.kt"),
        _found_file("data/src/main/java/com/zhangwenkang/cinefin/database/music/MusicDao.kt"),
        _planned_interface("com.zhangwenkang.cinefin.local.LocalMusicMetadataIndex"),
    ],
    ("G2-MUSIC-CORE", "now-playing-experience"): [
        _found_file("modes/music/src/main/java/com/zhangwenkang/cinefin/music/presentation/MusicNowPlayingScreen.kt"),
        _found_file("modes/music/src/main/java/com/zhangwenkang/cinefin/music/presentation/LyricsSheet.kt"),
        _found_file("modes/music/src/main/java/com/zhangwenkang/cinefin/music/data/lyrics/LyricsRepository.kt"),
        _found_file("modes/music/src/main/java/com/zhangwenkang/cinefin/music/presentation/MusicModeViewModel.kt"),
        _found_file("player/local/src/main/java/com/zhangwenkang/cinefin/player/local/audio/MusicAudioEffectsController.kt"),
        _found_file("player/local/src/main/java/com/zhangwenkang/cinefin/player/local/domain/SleepTimerController.kt"),
        _found_file("app/phone/src/main/java/com/zhangwenkang/cinefin/playback/MusicLyricsOverlayService.kt"),
        _planned_interface("com.zhangwenkang.cinefin.music.presentation.MusicPresentationState"),
    ],
    ("G2-LOCAL-PLAYLIST", "exchange-contract"): [
        _found_file("modes/music/src/main/java/com/zhangwenkang/cinefin/music/data/LocalPlaylistRepository.kt"),
        _found_file("modes/music/src/main/java/com/zhangwenkang/cinefin/music/data/PlaylistExchangeAdapter.kt"),
        _found_file("modes/music/src/main/java/com/zhangwenkang/cinefin/music/presentation/MusicPlaylistSnapshot.kt"),
        _found_file("modes/music/src/test/java/com/zhangwenkang/cinefin/music/data/PlaylistExchangeAdapterTest.kt"),
        _found_file("modes/music/src/test/java/com/zhangwenkang/cinefin/music/data/LocalPlaylistIdentityTest.kt"),
        _planned_interface("com.zhangwenkang.cinefin.music.exchange.LocalDraftIdentity"),
    ],
    ("G2-LOCAL-PLAYLIST", "saf-persistence"): [
        _found_file("data/src/main/java/com/zhangwenkang/cinefin/database/music/LocalPlaylistStorage.kt"),
        _found_file("data/src/main/java/com/zhangwenkang/cinefin/database/music/MusicStorage.kt"),
        _found_file("modes/music/src/main/java/com/zhangwenkang/cinefin/music/presentation/PlaylistExchangeFileTypes.kt"),
        _found_file("modes/music/src/test/java/com/zhangwenkang/cinefin/music/data/PlaylistExchangeImportTest.kt"),
        _planned_interface("com.zhangwenkang.cinefin.music.exchange.SafAtomicCommit"),
    ],
    ("G2-SERVER-WRITE", "write-contract"): [
        _found_file("data/src/main/java/com/zhangwenkang/cinefin/repository/JellyfinRepository.kt"),
        _found_file("data/src/main/java/com/zhangwenkang/cinefin/repository/JellyfinRepositoryImpl.kt"),
        _found_file("data/src/main/java/com/zhangwenkang/cinefin/repository/JellyfinRepositoryOfflineImpl.kt"),
        _found_file("data/src/main/java/com/zhangwenkang/cinefin/repository/UserDataEvents.kt"),
        _found_file("data/src/test/java/com/zhangwenkang/cinefin/repository/ReadingProgressMappingTest.kt"),
        _planned_interface("com.zhangwenkang.cinefin.repository.write.UserScopedWriteIntent"),
        _planned_interface("com.zhangwenkang.cinefin.repository.write.ReadBackAnchor"),
    ],
    ("G2-SERVER-WRITE", "writable-server"): [
        _found_file("data/src/main/java/com/zhangwenkang/cinefin/repository/JellyfinRepository.kt"),
        _found_file("data/src/main/java/com/zhangwenkang/cinefin/repository/JellyfinRepositoryImpl.kt"),
        _found_file("data/src/main/java/com/zhangwenkang/cinefin/repository/UserDataEvents.kt"),
        _planned_file("app/phone/src/androidTest/java/com/zhangwenkang/cinefin/write/ServerWriteJourneyTest.kt"),
        _planned_interface("com.zhangwenkang.cinefin.repository.write.WriteEndpointPolicy"),
    ],
    ("G2-DOWNLOAD-STATE", "state-and-preflight"): [
        _found_file("data/src/main/java/com/zhangwenkang/cinefin/utils/DownloadCanonicalIdentity.kt"),
        _found_file("core/src/main/java/com/zhangwenkang/cinefin/utils/DownloadEngineScheduleRules.kt"),
        _found_file("core/src/main/java/com/zhangwenkang/cinefin/core/presentation/downloader/DownloaderState.kt"),
        _found_file("data/src/main/java/com/zhangwenkang/cinefin/models/DownloadIntentDto.kt"),
        _found_file("core/src/test/java/com/zhangwenkang/cinefin/utils/DownloadCanonicalIdentityTest.kt"),
        _found_file("core/src/test/java/com/zhangwenkang/cinefin/utils/DownloadEngineScheduleRulesTest.kt"),
        _planned_interface("com.zhangwenkang.cinefin.utils.DownloadPreflightDecision"),
    ],
    ("G2-DOWNLOAD-STATE", "cross-store-control"): [
        _found_file("core/src/main/java/com/zhangwenkang/cinefin/utils/DownloaderImpl.kt"),
        _found_file("core/src/main/java/com/zhangwenkang/cinefin/utils/DownloadNotifications.kt"),
        _found_file("data/src/main/java/com/zhangwenkang/cinefin/utils/DownloadMediaSidecar.kt"),
        _found_file("core/src/main/java/com/zhangwenkang/cinefin/core/presentation/downloader/DownloaderViewModel.kt"),
        _found_file("modes/film/src/main/java/com/zhangwenkang/cinefin/film/presentation/downloads/DownloadsViewModel.kt"),
        _found_file("core/src/test/java/com/zhangwenkang/cinefin/utils/DownloaderImplRecoveryTest.kt"),
        _found_file("core/src/test/java/com/zhangwenkang/cinefin/utils/DownloadNotificationsTest.kt"),
        _planned_interface("com.zhangwenkang.cinefin.utils.CrossStoreReceipt"),
    ],
    ("G2-DOWNLOAD-STATE", "four-media-candidate"): [
        _found_file("app/phone/src/main/java/com/zhangwenkang/cinefin/presentation/film/DownloadsScreen.kt"),
        _found_file("app/phone/src/main/java/com/zhangwenkang/cinefin/presentation/film/downloads/DownloadRows.kt"),
        _found_file("app/phone/src/main/java/com/zhangwenkang/cinefin/presentation/downloads/DownloadStatusMonitor.kt"),
        _found_file("modes/film/src/main/java/com/zhangwenkang/cinefin/film/presentation/downloads/DownloadSelectionRules.kt"),
        _found_file("app/phone/src/test/java/com/zhangwenkang/cinefin/presentation/film/downloads/DownloadGridGroupingTest.kt"),
        _planned_file("app/phone/src/androidTest/java/com/zhangwenkang/cinefin/download/FourMediaDownloadJourneyTest.kt"),
    ],
    ("G2-VIDEO-CORE", "engine-session"): [
        _found_file("player/local/src/main/java/com/zhangwenkang/cinefin/player/local/domain/PlaybackCoordinator.kt"),
        _found_file("player/local/src/main/java/com/zhangwenkang/cinefin/player/local/presentation/PlayerHolder.kt"),
        _found_file("player/local/src/main/java/com/zhangwenkang/cinefin/player/local/domain/PlaybackSnapshotState.kt"),
        _found_file("app/phone/src/main/java/com/zhangwenkang/cinefin/playback/CinefinPlaybackService.kt"),
        _found_file("app/phone/src/main/java/com/zhangwenkang/cinefin/playback/PlaybackSessionRouting.kt"),
        _planned_interface("com.zhangwenkang.cinefin.player.domain.PlaybackEngine"),
        _planned_interface("com.zhangwenkang.cinefin.player.domain.PlaybackGeneration"),
    ],
    ("G2-VIDEO-CORE", "format-and-fallback"): [
        _found_file("player/local/src/main/java/com/zhangwenkang/cinefin/player/local/domain/PlayerDecodeMode.kt"),
        _found_file("player/local/src/main/java/com/zhangwenkang/cinefin/player/local/mpv/MPVPlayer.kt"),
        _found_file("player/local/src/main/java/com/zhangwenkang/cinefin/player/local/presentation/PlayerViewModel.kt"),
        _found_file("modes/film/src/main/java/com/zhangwenkang/cinefin/film/domain/VideoSourceSelector.kt"),
        _found_file("player/local/src/test/java/com/zhangwenkang/cinefin/player/local/domain/PlayerDecodeFallbackTest.kt"),
        _planned_interface("com.zhangwenkang.cinefin.player.domain.PlaybackCapabilityResult"),
    ],
    ("G2-VIDEO-CORE", "tracks-and-subtitles"): [
        _found_file("player/local/src/main/java/com/zhangwenkang/cinefin/player/local/domain/TrackSelectionEngine.kt"),
        _found_file("player/local/src/main/java/com/zhangwenkang/cinefin/player/local/subtitle/PlayerSubtitleController.kt"),
        _found_file("player/local/src/main/java/com/zhangwenkang/cinefin/player/local/subtitle/PlayerSubtitleSelection.kt"),
        _found_file("app/phone/src/main/java/com/zhangwenkang/cinefin/presentation/player/TrackSelectionDialogFragment.kt"),
        _found_file("app/phone/src/main/java/com/zhangwenkang/cinefin/presentation/player/PlayerSubtitleOverlay.kt"),
        _found_file("player/local/src/test/java/com/zhangwenkang/cinefin/player/local/subtitle/PlayerSubtitleSelectionTest.kt"),
    ],
    ("G2-VIDEO-CORE", "controls-and-lifecycle"): [
        _found_file("app/phone/src/main/java/com/zhangwenkang/cinefin/presentation/player/PlayerControlOverlay.kt"),
        _found_file("app/phone/src/main/java/com/zhangwenkang/cinefin/presentation/player/PlayerOverlayContainer.kt"),
        _found_file("player/local/src/main/java/com/zhangwenkang/cinefin/player/local/presentation/SeekPlayerEventListener.kt"),
        _found_file("app/phone/src/main/java/com/zhangwenkang/cinefin/PlayerActivity.kt"),
        _found_file("app/phone/src/test/java/com/zhangwenkang/cinefin/presentation/player/PlayerControlLayoutTest.kt"),
        _planned_file("app/phone/src/androidTest/java/com/zhangwenkang/cinefin/video/VideoLifecycleJourneyTest.kt"),
    ],
    ("G2-SYNCPLAY-GATED", "capability-contract"): [
        _found_file("app/phone/src/main/java/com/zhangwenkang/cinefin/playback/PlaybackSessionRouting.kt"),
        _found_file("player/local/src/main/java/com/zhangwenkang/cinefin/player/local/domain/PlaybackCoordinator.kt"),
        _planned_interface("com.zhangwenkang.cinefin.player.syncplay.SyncPlayCapability"),
        _planned_interface("com.zhangwenkang.cinefin.player.syncplay.GroupSnapshot"),
        _planned_interface("com.zhangwenkang.cinefin.player.syncplay.ClockEstimator"),
    ],
    ("G2-SYNCPLAY-GATED", "two-client-spike"): [
        _found_file("app/phone/src/main/java/com/zhangwenkang/cinefin/playback/CinefinPlaybackService.kt"),
        _planned_file("app/phone/src/androidTest/java/com/zhangwenkang/cinefin/syncplay/TwoClientSyncPlaySpikeTest.kt"),
        _planned_interface("com.zhangwenkang.cinefin.player.syncplay.CommandCorrelation"),
        _planned_interface("com.zhangwenkang.cinefin.player.syncplay.GroupMembership"),
    ],
    ("G2-SYNCPLAY-GATED", "recovery-and-exit"): [
        _found_file("app/phone/src/main/java/com/zhangwenkang/cinefin/playback/PlaybackSessionRouting.kt"),
        _planned_interface("com.zhangwenkang.cinefin.player.syncplay.ResnapshotRecovery"),
        _planned_interface("com.zhangwenkang.cinefin.player.syncplay.OrdinaryPlaybackExit"),
        _planned_file("app/phone/src/androidTest/java/com/zhangwenkang/cinefin/syncplay/SyncPlayRecoveryExitTest.kt"),
    ],
    ("G2-NAV-IDENTITY", "route-contract"): [
        _found_file("app/phone/src/main/java/com/zhangwenkang/cinefin/NavigationRoot.kt"),
        _found_file("app/phone/src/main/java/com/zhangwenkang/cinefin/presentation/navigation/TopLevelNavigation.kt"),
        _found_file("app/phone/src/main/java/com/zhangwenkang/cinefin/presentation/navigation/NavigationIa.kt"),
        _found_file("app/phone/src/test/java/com/zhangwenkang/cinefin/presentation/navigation/TopLevelNavigationTest.kt"),
        _found_file("app/phone/src/test/java/com/zhangwenkang/cinefin/presentation/navigation/NavigationIaTest.kt"),
        _planned_interface("com.zhangwenkang.cinefin.presentation.navigation.RootReturnContract"),
    ],
    ("G2-NAV-IDENTITY", "identity-switch"): [
        _found_file("app/phone/src/main/java/com/zhangwenkang/cinefin/presentation/setup/users/UsersScreen.kt"),
        _found_file("setup/src/main/java/com/zhangwenkang/cinefin/setup/presentation/users/UsersViewModel.kt"),
        _found_file("setup/src/main/java/com/zhangwenkang/cinefin/setup/domain/UserSessionSelection.kt"),
        _found_file("setup/src/main/java/com/zhangwenkang/cinefin/setup/domain/OfflineIdentity.kt"),
        _found_file("app/phone/src/main/java/com/zhangwenkang/cinefin/presentation/offline/OfflineModeViewModel.kt"),
        _found_file("app/phone/src/main/java/com/zhangwenkang/cinefin/playback/PlaybackSessionRouting.kt"),
        _found_file("app/phone/src/test/java/com/zhangwenkang/cinefin/setup/OfflineIdentityCoordinatorTest.kt"),
        _planned_interface("com.zhangwenkang.cinefin.setup.domain.IdentitySwitchPlaybackFence"),
    ],
    ("G2-Q03-SUPPLY-CHAIN-GATED", "artifact-inventory"): [
        _found_file("settings.gradle.kts"),
        _found_file("gradle/libs.versions.toml"),
        _found_file("app/phone/build.gradle.kts"),
        _found_file("app/phone/src/main/AndroidManifest.xml"),
        _planned_file("app/phone/src/main/assets/sbom/component-inventory.json"),
        _planned_interface("com.zhangwenkang.cinefin.release.PackagedSbomManifest"),
    ],
    ("G2-Q03-SUPPLY-CHAIN-GATED", "source-and-attestation"): [
        _found_file("settings.gradle.kts"),
        _found_file("build.gradle.kts"),
        _found_file("app/phone/build.gradle.kts"),
        _planned_file("app/phone/src/main/assets/licenses/NOTICE-2.0.txt"),
        _planned_file("app/phone/src/main/assets/licenses/source-offer.json"),
        _planned_interface("com.zhangwenkang.cinefin.release.SourceObligationManifest"),
        _planned_interface("com.zhangwenkang.cinefin.release.CandidateAttestation"),
    ],
    ("G2-Q03-SUPPLY-CHAIN-GATED", "runtime-and-16kb"): [
        _found_file("player/local/src/main/java/com/zhangwenkang/cinefin/player/local/mpv/MPVPlayer.kt"),
        _found_file("player/local/src/main/java/com/zhangwenkang/cinefin/player/local/audio/CinefinRenderersFactory.kt"),
        _found_file("player/local/build.gradle.kts"),
        _planned_interface("com.zhangwenkang.cinefin.player.nativebridge.NativeLoadOrderContract"),
        _planned_interface("com.zhangwenkang.cinefin.release.SixteenKbPageVerifier"),
        _planned_file("app/phone/src/androidTest/java/com/zhangwenkang/cinefin/nativebridge/NativeLoadOrderJourneyTest.kt"),
    ],
}

WAVE_CONTRACTS = {
    ("G2-ARCH-CONTRACTS-MIGRATION", "contracts-and-owners"): {
        "capabilities": [
            "ARCH.CONTRACT",
            "ARCH.OWNER",
            "ARCH.DOCS",
            "ARCH.EVIDENCE",
            "ARCH.QA",
            "ARCH.RELEASE_GATE",
            "ARCH.INTEGRATION",
        ],
        "entry_tags": ["ARCH_REVIEW", "RELEASE_GATE"],
        "action_tags": ["REVIEW", "GUARD", "INTEGRATE", "RELEASE"],
        "anchors": {
            "ARCH.CONTRACT": [
                "com.zhangwenkang.cinefin.core.domain.contract.CrossLayerContract"
            ],
            "ARCH.OWNER": [
                "com.zhangwenkang.cinefin.core.domain.contract.ArchitectureOwnerGate"
            ],
            "ARCH.DOCS": ["settings.gradle.kts"],
            "ARCH.EVIDENCE": ["build.gradle.kts"],
            "ARCH.QA": ["build.gradle.kts"],
            "ARCH.RELEASE_GATE": ["build.gradle.kts"],
            "ARCH.INTEGRATION": ["settings.gradle.kts"],
        },
    },
    ("G2-ARCH-CONTRACTS-MIGRATION", "migration-core"): {
        "capabilities": ["ARCH.MIGRATION_CORE", "ARCH.PERSISTENCE"],
        "entry_tags": ["MIGRATION"],
        "action_tags": ["MIGRATE", "RECOVER"],
        "anchors": {
            "ARCH.MIGRATION_CORE": [
                "data/src/main/java/com/zhangwenkang/cinefin/database/ServerDatabase.kt",
                "com.zhangwenkang.cinefin.core.domain.contract.ForwardMigrationReceipt",
            ],
            "ARCH.PERSISTENCE": [
                "core/src/main/java/com/zhangwenkang/cinefin/utils/DownloaderImpl.kt",
                "com.zhangwenkang.cinefin.core.domain.contract.CrossStoreReconciler",
            ],
        },
    },
    ("G2-ARCH-CONTRACTS-MIGRATION", "migration-candidate"): {
        "capabilities": ["ARCH.MIGRATION_CANDIDATE", "ARCH.RUNTIME_RECOVERY"],
        "entry_tags": ["MIGRATION"],
        "action_tags": ["MIGRATE", "RECOVER"],
        "anchors": {
            "ARCH.MIGRATION_CANDIDATE": [
                "app/phone/src/androidTest/java/com/zhangwenkang/cinefin/migration/CandidateMigrationJourneyTest.kt",
                "com.zhangwenkang.cinefin.migration.CandidateMigrationReport",
            ],
            "ARCH.RUNTIME_RECOVERY": [
                "data/src/test/java/com/zhangwenkang/cinefin/database/ServerDatabaseMigrationContractTest.kt"
            ],
        },
    },
    ("G2-SEARCH", "state-contract"): {
        "capabilities": ["SEARCH.STATE_CONTRACT"],
        "entry_tags": ["SEARCH"],
        "action_tags": ["QUERY"],
        "anchors": {
            "SEARCH.STATE_CONTRACT": [
                "data/src/main/java/com/zhangwenkang/cinefin/repository/SearchQueryRules.kt",
                "com.zhangwenkang.cinefin.repository.search.CompleteSearchQueryShape",
            ]
        },
    },
    ("G2-SEARCH", "candidate-journey"): {
        "capabilities": ["SEARCH.CANDIDATE_JOURNEY"],
        "entry_tags": ["SEARCH"],
        "action_tags": ["QUERY", "OPEN_RESULT", "RECOVER_ROUTE"],
        "anchors": {
            "SEARCH.CANDIDATE_JOURNEY": [
                "app/phone/src/main/java/com/zhangwenkang/cinefin/presentation/film/SearchScreen.kt",
                "app/phone/src/androidTest/java/com/zhangwenkang/cinefin/search/SearchCandidateJourneyTest.kt",
            ]
        },
    },
    ("G2-UPDATE-TRUST", "trust-gates"): {
        "capabilities": ["UPDATE.TRUST_CHAIN"],
        "entry_tags": ["UPDATE"],
        "action_tags": ["VERIFY_UPDATE"],
        "anchors": {
            "UPDATE.TRUST_CHAIN": [
                "data/src/main/java/com/zhangwenkang/cinefin/update/UpdateRepository.kt",
                "com.zhangwenkang.cinefin.update.UpdateTrustVerifier",
            ]
        },
    },
    ("G2-UPDATE-TRUST", "installer-journey"): {
        "capabilities": ["UPDATE.INSTALLER"],
        "entry_tags": ["UPDATE"],
        "action_tags": ["INSTALL_UPDATE", "VERIFY_UPDATE"],
        "anchors": {
            "UPDATE.INSTALLER": [
                "app/phone/src/main/java/com/zhangwenkang/cinefin/presentation/update/AndroidUpdateInstallerGateway.kt"
            ]
        },
    },
    ("G2-PERSISTENT-SWR", "cache-core"): {
        "capabilities": ["CACHE.PERSISTENT_SWR"],
        "entry_tags": ["CACHE"],
        "action_tags": ["CACHE_READ", "CACHE_WRITE", "REVALIDATE"],
        "anchors": {
            "CACHE.PERSISTENT_SWR": [
                "data/src/main/java/com/zhangwenkang/cinefin/repository/MetadataSnapshotSwr.kt",
                "com.zhangwenkang.cinefin.repository.cache.DurableGenerationFence",
            ]
        },
    },
    ("G2-PERSISTENT-SWR", "runtime-window"): {
        "capabilities": ["CACHE.RUNTIME_WINDOW"],
        "entry_tags": ["CACHE"],
        "action_tags": ["CACHE_READ", "REVALIDATE", "RESTART"],
        "anchors": {
            "CACHE.RUNTIME_WINDOW": [
                "app/phone/src/androidTest/java/com/zhangwenkang/cinefin/cache/CacheRuntimeWindowTest.kt",
                "data/src/main/java/com/zhangwenkang/cinefin/repository/MetadataPreloader.kt",
            ]
        },
    },
    ("G2-UI-R1-FOUNDATION", "tokens-and-themes"): {
        "capabilities": ["UI.TOKEN_THEME"],
        "entry_tags": ["DESIGN_SYSTEM"],
        "action_tags": ["TOKEN_AUDIT", "SELECT_THEME"],
        "anchors": {
            "UI.TOKEN_THEME": [
                "core/src/main/java/com/zhangwenkang/cinefin/core/presentation/theme/CinefinTokens.kt",
                "com.zhangwenkang.cinefin.core.presentation.theme.ThemeResolver",
            ]
        },
    },
    ("G2-UI-R1-FOUNDATION", "rwd-and-states"): {
        "capabilities": ["UI.RWD_STATE", "UI.IA_ENTRY"],
        "entry_tags": ["UI_SURFACE", "NAVIGATION", "ACCOUNT_SELECTOR"],
        "action_tags": ["RWD_STATE", "NAVIGATE", "SELECT_ACCOUNT", "RESTORE_STATE"],
        "anchors": {
            "UI.RWD_STATE": [
                "app/phone/src/main/java/com/zhangwenkang/cinefin/NavigationRoot.kt",
                "com.zhangwenkang.cinefin.presentation.window.WindowStateRetentionPolicy",
            ],
            "UI.IA_ENTRY": [
                "app/phone/src/main/java/com/zhangwenkang/cinefin/presentation/navigation/NavigationIa.kt",
                "app/phone/src/main/java/com/zhangwenkang/cinefin/presentation/setup/users/UsersScreen.kt",
            ],
        },
    },
    ("G2-UI-R1-FOUNDATION", "a11y-and-motion"): {
        "capabilities": ["UI.A11Y_MOTION"],
        "entry_tags": ["UI_SURFACE", "NAVIGATION"],
        "action_tags": ["A11Y", "MOTION", "NAVIGATE"],
        "anchors": {
            "UI.A11Y_MOTION": [
                "com.zhangwenkang.cinefin.core.presentation.a11y.AccessibleActionContract",
                "core/src/main/java/com/zhangwenkang/cinefin/core/presentation/components/CinefinInteraction.kt",
            ]
        },
    },
    ("G2-READER-CORE", "ports-and-identity"): {
        "capabilities": ["READER.PORTS_IDENTITY", "READER.FORMAT_SOURCE"],
        "entry_tags": ["READER", "BOOKSHELF"],
        "action_tags": ["OPEN_IDENTIFY", "IMPORT", "DETECT_FORMAT"],
        "anchors": {
            "READER.PORTS_IDENTITY": [
                "modes/book/src/main/java/com/zhangwenkang/cinefin/book/presentation/reader/BookDocument.kt",
                "com.zhangwenkang.cinefin.book.domain.PublicationIdentity",
            ],
            "READER.FORMAT_SOURCE": [
                "modes/book/src/main/java/com/zhangwenkang/cinefin/book/presentation/reader/BookFormat.kt",
                "data/src/main/java/com/zhangwenkang/cinefin/repository/ReaderRepositoryImpl.kt",
            ],
        },
    },
    ("G2-READER-CORE", "layout-and-position"): {
        "capabilities": [
            "READER.LAYOUT_POSITION",
            "READER.UI_SETTINGS",
            "READER.UI_A11Y",
        ],
        "entry_tags": ["READER_UI", "BOOKSHELF"],
        "action_tags": ["LAYOUT", "SETTINGS", "SAVE_POSITION", "A11Y"],
        "anchors": {
            "READER.LAYOUT_POSITION": [
                "modes/book/src/main/java/com/zhangwenkang/cinefin/book/presentation/reader/ReaderScreen.kt",
                "com.zhangwenkang.cinefin.book.domain.StableReadingLocator",
            ],
            "READER.UI_SETTINGS": [
                "modes/book/src/main/java/com/zhangwenkang/cinefin/book/presentation/reader/ReaderSettings.kt"
            ],
            "READER.UI_A11Y": [
                "modes/book/src/main/java/com/zhangwenkang/cinefin/book/presentation/reader/ReaderScreen.kt"
            ],
        },
    },
    ("G2-READER-CORE", "annotations-and-search"): {
        "capabilities": ["READER.ANNOTATION_SEARCH", "READER.PERSISTENCE"],
        "entry_tags": ["READER_CONTENT"],
        "action_tags": ["BOOKMARK", "ANNOTATE", "SEARCH", "PERSIST_LOCAL"],
        "anchors": {
            "READER.ANNOTATION_SEARCH": [
                "modes/book/src/main/java/com/zhangwenkang/cinefin/book/presentation/reader/ReaderAnnotationStore.kt",
                "modes/book/src/main/java/com/zhangwenkang/cinefin/book/presentation/reader/ReaderSearchSheet.kt",
            ],
            "READER.PERSISTENCE": [
                "modes/book/src/main/java/com/zhangwenkang/cinefin/book/presentation/reader/ReaderAnnotation.kt"
            ],
        },
    },
    ("G2-READER-CORE", "large-book-recovery"): {
        "capabilities": ["READER.LARGE_RECOVERY", "READER.OFFLINE"],
        "entry_tags": ["READER_RUNTIME"],
        "action_tags": ["LARGE_BOOK", "OFFLINE", "RECOVER"],
        "anchors": {
            "READER.LARGE_RECOVERY": [
                "app/phone/src/androidTest/java/com/zhangwenkang/cinefin/reader/LargeBookRecoveryJourneyTest.kt",
                "modes/book/src/main/java/com/zhangwenkang/cinefin/book/presentation/reader/ReaderStreamingPlan.kt",
            ],
            "READER.OFFLINE": [
                "modes/book/src/main/java/com/zhangwenkang/cinefin/book/presentation/reader/RemoteComicPageSource.kt"
            ],
        },
    },
    ("G2-READER-TTS-GATED", "q03-decision"): {
        "capabilities": ["TTS.ENGINE_DECISION"],
        "entry_tags": ["READER_TTS"],
        "action_tags": [ENGINE_EVIDENCE_ACTION_TAG, "SELECT_VOICE"],
        "anchors": {
            "TTS.ENGINE_DECISION": [
                "com.zhangwenkang.cinefin.book.tts.LocalTtsPort",
                "com.zhangwenkang.cinefin.book.tts.VoiceCatalog",
            ]
        },
    },
    ("G2-READER-TTS-GATED", "offline-runtime"): {
        "capabilities": ["TTS.OFFLINE_RUNTIME"],
        "entry_tags": ["READER_TTS"],
        "action_tags": ["TTS_PLAY", "TTS_SYNC", "OFFLINE"],
        "anchors": {
            "TTS.OFFLINE_RUNTIME": [
                "app/phone/src/androidTest/java/com/zhangwenkang/cinefin/reader/TtsOfflineJourneyTest.kt",
                "com.zhangwenkang.cinefin.book.tts.TtsPlaybackState",
            ]
        },
    },
    ("G2-READER-TTS-GATED", "background-session"): {
        "capabilities": ["TTS.BACKGROUND_SESSION"],
        "entry_tags": ["READER_TTS", "MEDIA_SESSION"],
        "action_tags": [
            "TTS_PLAY",
            "TTS_PAUSE",
            "TTS_PREVIOUS_SENTENCE",
            "TTS_NEXT_SENTENCE",
            "TTS_STOP",
            "RESTORE_STATE",
        ],
        "anchors": {
            "TTS.BACKGROUND_SESSION": [
                "com.zhangwenkang.cinefin.book.tts.TtsPlaybackOwner",
                "com.zhangwenkang.cinefin.book.tts.TtsMediaSession",
            ]
        },
    },
    ("G2-READER-TTS-GATED", "voice-catalog-import"): {
        "capabilities": [
            "TTS.VOICE_CATALOG",
            "TTS.VOICE_IMPORT",
            "TTS.VOICE_SELECTION",
        ],
        "entry_tags": ["READER_TTS", "VOICE_SETTINGS"],
        "action_tags": [
            "IMPORT_VOICE",
            "SELECT_VOICE",
            "OVERRIDE_BOOK_VOICE",
            "DEDUPLICATE_PROFILE",
        ],
        "anchors": {
            "TTS.VOICE_CATALOG": [
                "com.zhangwenkang.cinefin.book.tts.VoiceCatalog",
            ],
            "TTS.VOICE_IMPORT": [
                "com.zhangwenkang.cinefin.book.tts.VoicePackImporter",
            ],
            "TTS.VOICE_SELECTION": [
                "com.zhangwenkang.cinefin.book.tts.VoiceSelectionStore",
            ],
        },
    },
    ("G2-READER-TTS-GATED", "voice-delete-fallback"): {
        "capabilities": [
            "TTS.VOICE_DELETE",
            "TTS.VOICE_FALLBACK",
        ],
        "entry_tags": ["READER_TTS", "VOICE_SETTINGS"],
        "action_tags": [
            "DELETE_VOICE",
            "HIDE_BUILTIN_VOICE",
            "RESTORE_BUILTIN_VOICE",
            "FALLBACK_VOICE",
            "CLEAN_REFERENCES",
            "RETRY_DELETE",
        ],
        "anchors": {
            "TTS.VOICE_DELETE": [
                "com.zhangwenkang.cinefin.book.tts.VoiceDeletionReceipt",
                "com.zhangwenkang.cinefin.book.tts.VoiceCatalog",
            ],
            "TTS.VOICE_FALLBACK": [
                "com.zhangwenkang.cinefin.book.tts.VoiceSelectionStore",
                "modes/book/src/main/java/com/zhangwenkang/cinefin/book/presentation/reader/ReaderViewModel.kt",
            ],
        },
    },
    ("G2-READER-TTS-GATED", "audio-arbitration"): {
        "capabilities": [
            "TTS.AUDIO_OWNER",
            "TTS.AUDIO_FOCUS",
            "TTS.RESTART_RECOVERY",
        ],
        "entry_tags": ["READER_TTS", "MEDIA_SESSION", "MUSIC"],
        "action_tags": [
            "START_TTS",
            "PAUSE_MUSIC",
            "STOP_TTS",
            "AUDIO_FOCUS",
            "NOISY",
            "RESTORE_STATE",
        ],
        "anchors": {
            "TTS.AUDIO_OWNER": [
                "com.zhangwenkang.cinefin.player.domain.SingleSessionArbiter",
                "app/phone/src/main/java/com/zhangwenkang/cinefin/playback/CinefinPlaybackService.kt",
            ],
            "TTS.AUDIO_FOCUS": [
                "com.zhangwenkang.cinefin.book.tts.TtsAudioFocusController",
            ],
            "TTS.RESTART_RECOVERY": [
                "player/local/src/main/java/com/zhangwenkang/cinefin/player/local/domain/PlaybackCoordinator.kt",
                "com.zhangwenkang.cinefin.book.tts.TtsPlaybackOwner",
            ],
        },
    },
    ("G2-READER-PDF-GATED", "engine-gate"): {
        "capabilities": ["PDF.ENGINE_GATE"],
        "entry_tags": ["PDF"],
        "action_tags": ["SELECT_ENGINE", "VERIFY_ENGINE"],
        "anchors": {
            "PDF.ENGINE_GATE": [
                "com.zhangwenkang.cinefin.book.pdf.PdfEngineProvenance",
                "com.zhangwenkang.cinefin.book.pdf.Pdf16KbGate",
            ]
        },
    },
    ("G2-READER-PDF-GATED", "range-spike"): {
        "capabilities": ["PDF.RANGE"],
        "entry_tags": ["PDF"],
        "action_tags": ["RANGE_READ", "SEARCH"],
        "anchors": {
            "PDF.RANGE": [
                "modes/book/src/main/java/com/zhangwenkang/cinefin/book/presentation/reader/PreciseHttpResource.kt",
                "com.zhangwenkang.cinefin.book.pdf.PdfRangeWindow",
            ]
        },
    },
    ("G2-READER-PDF-GATED", "candidate-swap"): {
        "capabilities": ["PDF.CANDIDATE"],
        "entry_tags": ["PDF"],
        "action_tags": ["OPEN_PDF", "SEARCH", "RESTORE"],
        "anchors": {
            "PDF.CANDIDATE": [
                "app/phone/src/androidTest/java/com/zhangwenkang/cinefin/reader/PdfCandidateSwapJourneyTest.kt",
                "com.zhangwenkang.cinefin.book.pdf.PdfEngineRollbackPolicy",
            ]
        },
    },
    ("G2-MUSIC-CORE", "single-session"): {
        "capabilities": [
            "MUSIC.SOURCE_IDENTITY",
            "MUSIC.FORMAT",
            "MUSIC.SESSION",
            "MUSIC.PLAYBACK",
        ],
        "entry_tags": ["PLAYER"],
        "action_tags": ["OPEN_TRACK", "VERIFY_FORMAT", "PLAYBACK", "YIELD_SESSION"],
        "anchors": {
            "MUSIC.SOURCE_IDENTITY": [
                "player/local/src/main/java/com/zhangwenkang/cinefin/player/local/domain/PlaybackCoordinator.kt"
            ],
            "MUSIC.FORMAT": [
                "player/local/src/main/java/com/zhangwenkang/cinefin/player/local/domain/MusicPlaybackController.kt"
            ],
            "MUSIC.SESSION": [
                "com.zhangwenkang.cinefin.player.domain.SingleSessionArbiter",
                "app/phone/src/main/java/com/zhangwenkang/cinefin/playback/CinefinPlaybackService.kt",
            ],
            "MUSIC.PLAYBACK": [
                "player/local/src/main/java/com/zhangwenkang/cinefin/player/local/presentation/PlayerHolder.kt"
            ],
        },
    },
    ("G2-MUSIC-CORE", "queue-recovery"): {
        "capabilities": [
            "MUSIC.QUEUE_EDIT",
            "MUSIC.QUEUE_RECOVERY",
            "MUSIC.ERROR_RECOVERY",
        ],
        "entry_tags": ["ACTIVE_QUEUE", "TRACK_ACTION"],
        "action_tags": ["ORCHESTRATE_QUEUE", "RECOVER_QUEUE", "RECOVER_ERROR", "ADD_TO_QUEUE"],
        "anchors": {
            "MUSIC.QUEUE_EDIT": [
                "modes/music/src/main/java/com/zhangwenkang/cinefin/music/data/MusicQueueEdits.kt",
                "player/local/src/main/java/com/zhangwenkang/cinefin/player/local/domain/PlaylistManager.kt",
            ],
            "MUSIC.QUEUE_RECOVERY": [
                "modes/music/src/main/java/com/zhangwenkang/cinefin/music/data/MusicQueueStore.kt",
                "modes/music/src/main/java/com/zhangwenkang/cinefin/music/data/MusicQueuePersister.kt",
            ],
            "MUSIC.ERROR_RECOVERY": [
                "player/local/src/main/java/com/zhangwenkang/cinefin/player/local/domain/PlaybackPositionWriter.kt"
            ],
        },
    },
    ("G2-MUSIC-CORE", "large-queue"): {
        "capabilities": ["MUSIC.LARGE_QUEUE"],
        "entry_tags": ["LARGE_QUEUE"],
        "action_tags": ["FILL_QUEUE", "VERIFY_BOUNDED_FILL"],
        "anchors": {
            "MUSIC.LARGE_QUEUE": [
                "modes/music/src/main/java/com/zhangwenkang/cinefin/music/data/MusicQueueFill.kt",
                "com.zhangwenkang.cinefin.music.domain.BoundedQueueFillPolicy",
            ]
        },
    },
    ("G2-MUSIC-CORE", "local-library-index"): {
        "capabilities": ["MUSIC.LOCAL_SCAN_METADATA"],
        "entry_tags": ["LOCAL_LIBRARY"],
        "action_tags": ["SCAN_METADATA", "REUSE_INDEX"],
        "anchors": {
            "MUSIC.LOCAL_SCAN_METADATA": [
                "data/src/main/java/com/zhangwenkang/cinefin/local/LocalLibraryRepositoryImpl.kt",
                "data/src/main/java/com/zhangwenkang/cinefin/repository/MusicTrackMetadata.kt",
            ]
        },
    },
    ("G2-MUSIC-CORE", "now-playing-experience"): {
        "capabilities": [
            "MUSIC.NOW_PLAYING_UI",
            "MUSIC.LYRICS",
            "MUSIC.EFFECTS",
            "MUSIC.GESTURE",
            "MUSIC.MOTION_A11Y",
        ],
        "entry_tags": ["NOW_PLAYING", "LYRICS_PANEL"],
        "action_tags": ["PRESENT", "LYRICS", "EFFECTS", "GESTURE", "ACCESSIBLE_MOTION"],
        "anchors": {
            "MUSIC.NOW_PLAYING_UI": [
                "modes/music/src/main/java/com/zhangwenkang/cinefin/music/presentation/MusicNowPlayingScreen.kt",
                "com.zhangwenkang.cinefin.music.presentation.MusicPresentationState",
            ],
            "MUSIC.LYRICS": [
                "modes/music/src/main/java/com/zhangwenkang/cinefin/music/presentation/LyricsSheet.kt",
                "modes/music/src/main/java/com/zhangwenkang/cinefin/music/data/lyrics/LyricsRepository.kt",
            ],
            "MUSIC.EFFECTS": [
                "player/local/src/main/java/com/zhangwenkang/cinefin/player/local/audio/MusicAudioEffectsController.kt",
                "player/local/src/main/java/com/zhangwenkang/cinefin/player/local/domain/SleepTimerController.kt",
            ],
            "MUSIC.GESTURE": [
                "modes/music/src/main/java/com/zhangwenkang/cinefin/music/presentation/MusicModeViewModel.kt"
            ],
            "MUSIC.MOTION_A11Y": [
                "app/phone/src/main/java/com/zhangwenkang/cinefin/playback/MusicLyricsOverlayService.kt"
            ],
        },
    },
    ("G2-LOCAL-PLAYLIST", "exchange-contract"): {
        "capabilities": ["PLAYLIST.EXCHANGE_IDENTITY"],
        "entry_tags": ["LOCAL_PLAYLIST", "TRACK_ACTION"],
        "action_tags": ["EDIT_PLAYLIST", "EXCHANGE_PLAYLIST", "ADD_TO_PLAYLIST"],
        "anchors": {
            "PLAYLIST.EXCHANGE_IDENTITY": [
                "modes/music/src/main/java/com/zhangwenkang/cinefin/music/data/PlaylistExchangeAdapter.kt",
                "com.zhangwenkang.cinefin.music.exchange.LocalDraftIdentity",
            ]
        },
    },
    ("G2-LOCAL-PLAYLIST", "saf-persistence"): {
        "capabilities": ["PLAYLIST.SAF_PERSISTENCE"],
        "entry_tags": ["LOCAL_PLAYLIST", "SAF"],
        "action_tags": ["EXCHANGE_PLAYLIST", "PERSIST_PLAYLIST", "RESTORE_PLAYLIST"],
        "anchors": {
            "PLAYLIST.SAF_PERSISTENCE": [
                "data/src/main/java/com/zhangwenkang/cinefin/database/music/LocalPlaylistStorage.kt",
                "com.zhangwenkang.cinefin.music.exchange.SafAtomicCommit",
            ]
        },
    },
    ("G2-SERVER-WRITE", "write-contract"): {
        "capabilities": ["WRITE.CONTRACT"],
        "entry_tags": ["SERVER_WRITE"],
        "action_tags": ["WRITE", "DENY_WRITE", "READ_BACK"],
        "anchors": {
            "WRITE.CONTRACT": [
                "com.zhangwenkang.cinefin.repository.write.UserScopedWriteIntent",
                "com.zhangwenkang.cinefin.repository.write.ReadBackAnchor",
            ]
        },
    },
    ("G2-SERVER-WRITE", "writable-server"): {
        "capabilities": ["WRITE.SERVER_JOURNEY"],
        "entry_tags": ["SERVER_WRITE"],
        "action_tags": ["WRITE", "DENY_WRITE", "READ_BACK"],
        "anchors": {
            "WRITE.SERVER_JOURNEY": [
                "app/phone/src/androidTest/java/com/zhangwenkang/cinefin/write/ServerWriteJourneyTest.kt",
                "com.zhangwenkang.cinefin.repository.write.WriteEndpointPolicy",
            ]
        },
    },
    ("G2-DOWNLOAD-STATE", "state-and-preflight"): {
        "capabilities": ["DOWNLOAD.STATE_PREFLIGHT_IDENTITY"],
        "entry_tags": ["DOWNLOAD_CREATE"],
        "action_tags": ["CREATE_TASK", "PREFLIGHT", "IDENTIFY_SOURCE"],
        "anchors": {
            "DOWNLOAD.STATE_PREFLIGHT_IDENTITY": [
                "data/src/main/java/com/zhangwenkang/cinefin/utils/DownloadCanonicalIdentity.kt",
                "com.zhangwenkang.cinefin.utils.DownloadPreflightDecision",
            ]
        },
    },
    ("G2-DOWNLOAD-STATE", "cross-store-control"): {
        "capabilities": ["DOWNLOAD.CONTROL_RECOVERY_NOTIFICATION"],
        "entry_tags": ["DOWNLOAD_ACTIVE"],
        "action_tags": ["CONTROL_TASK", "RECOVER_TASK", "NOTIFY", "DELETE_TASK"],
        "anchors": {
            "DOWNLOAD.CONTROL_RECOVERY_NOTIFICATION": [
                "core/src/main/java/com/zhangwenkang/cinefin/utils/DownloaderImpl.kt",
                "core/src/main/java/com/zhangwenkang/cinefin/utils/DownloadNotifications.kt",
            ]
        },
    },
    ("G2-DOWNLOAD-STATE", "four-media-candidate"): {
        "capabilities": ["DOWNLOAD.MEDIA_HIERARCHY_CANDIDATE"],
        "entry_tags": ["DOWNLOAD_LIBRARY"],
        "action_tags": ["BROWSE_HIERARCHY", "OPEN_OFFLINE", "SELECT_MEDIA"],
        "anchors": {
            "DOWNLOAD.MEDIA_HIERARCHY_CANDIDATE": [
                "app/phone/src/main/java/com/zhangwenkang/cinefin/presentation/film/DownloadsScreen.kt",
                "app/phone/src/androidTest/java/com/zhangwenkang/cinefin/download/FourMediaDownloadJourneyTest.kt",
            ]
        },
    },
    ("G2-VIDEO-CORE", "engine-session"): {
        "capabilities": ["VIDEO.SESSION_IDENTITY"],
        "entry_tags": ["VIDEO"],
        "action_tags": ["OPEN_VIDEO", "YIELD_SESSION", "RESTORE_SESSION"],
        "anchors": {
            "VIDEO.SESSION_IDENTITY": [
                "player/local/src/main/java/com/zhangwenkang/cinefin/player/local/domain/PlaybackCoordinator.kt",
                "com.zhangwenkang.cinefin.player.domain.PlaybackEngine",
            ]
        },
    },
    ("G2-VIDEO-CORE", "format-and-fallback"): {
        "capabilities": ["VIDEO.FORMAT_FALLBACK"],
        "entry_tags": ["VIDEO_DIAGNOSTICS"],
        "action_tags": ["VERIFY_FORMAT", "FALLBACK", "RECOVER"],
        "anchors": {
            "VIDEO.FORMAT_FALLBACK": [
                "player/local/src/main/java/com/zhangwenkang/cinefin/player/local/mpv/MPVPlayer.kt",
                "com.zhangwenkang.cinefin.player.domain.PlaybackCapabilityResult",
            ]
        },
    },
    ("G2-VIDEO-CORE", "tracks-and-subtitles"): {
        "capabilities": ["VIDEO.TRACKS_SUBTITLES"],
        "entry_tags": ["VIDEO_TRACKS"],
        "action_tags": ["SELECT_TRACK", "SELECT_SUBTITLE", "ADJUST_DELAY"],
        "anchors": {
            "VIDEO.TRACKS_SUBTITLES": [
                "player/local/src/main/java/com/zhangwenkang/cinefin/player/local/domain/TrackSelectionEngine.kt",
                "player/local/src/main/java/com/zhangwenkang/cinefin/player/local/subtitle/PlayerSubtitleController.kt",
            ]
        },
    },
    ("G2-VIDEO-CORE", "controls-and-lifecycle"): {
        "capabilities": ["VIDEO.CONTROLS_LIFECYCLE"],
        "entry_tags": ["VIDEO_CONTROLS"],
        "action_tags": ["CONTROL", "GESTURE", "LIFECYCLE"],
        "anchors": {
            "VIDEO.CONTROLS_LIFECYCLE": [
                "app/phone/src/main/java/com/zhangwenkang/cinefin/presentation/player/PlayerControlOverlay.kt",
                "app/phone/src/main/java/com/zhangwenkang/cinefin/presentation/player/PlayerOverlayContainer.kt",
            ]
        },
    },
    ("G2-SYNCPLAY-GATED", "capability-contract"): {
        "capabilities": ["SYNC.CAPABILITY_CONTRACT"],
        "entry_tags": ["SYNCPLAY"],
        "action_tags": ["PROBE_CAPABILITY", "CREATE_GROUP", "SYNC_QUEUE"],
        "anchors": {
            "SYNC.CAPABILITY_CONTRACT": [
                "com.zhangwenkang.cinefin.player.syncplay.SyncPlayCapability",
                "com.zhangwenkang.cinefin.player.syncplay.GroupSnapshot",
            ]
        },
    },
    ("G2-SYNCPLAY-GATED", "two-client-spike"): {
        "capabilities": ["SYNC.TWO_CLIENT"],
        "entry_tags": ["SYNCPLAY"],
        "action_tags": ["JOIN_GROUP", "CONTROL_GROUP", "SYNC_CLOCK"],
        "anchors": {
            "SYNC.TWO_CLIENT": [
                "app/phone/src/androidTest/java/com/zhangwenkang/cinefin/syncplay/TwoClientSyncPlaySpikeTest.kt",
                "com.zhangwenkang.cinefin.player.syncplay.CommandCorrelation",
            ]
        },
    },
    ("G2-SYNCPLAY-GATED", "recovery-and-exit"): {
        "capabilities": ["SYNC.RECOVERY_EXIT"],
        "entry_tags": ["SYNCPLAY"],
        "action_tags": ["RECOVER_SYNC", "LEAVE_GROUP", "PERSONAL_TRACK"],
        "anchors": {
            "SYNC.RECOVERY_EXIT": [
                "app/phone/src/androidTest/java/com/zhangwenkang/cinefin/syncplay/SyncPlayRecoveryExitTest.kt",
                "com.zhangwenkang.cinefin.player.syncplay.ResnapshotRecovery",
            ]
        },
    },
    ("G2-NAV-IDENTITY", "route-contract"): {
        "capabilities": ["NAV.ROUTE_CONTRACT"],
        "entry_tags": ["NAVIGATION"],
        "action_tags": ["NAVIGATE", "BACK", "RESTORE_SCROLL"],
        "anchors": {
            "NAV.ROUTE_CONTRACT": [
                "app/phone/src/main/java/com/zhangwenkang/cinefin/presentation/navigation/TopLevelNavigation.kt",
                "com.zhangwenkang.cinefin.presentation.navigation.RootReturnContract",
            ]
        },
    },
    ("G2-NAV-IDENTITY", "identity-switch"): {
        "capabilities": ["NAV.IDENTITY_SWITCH", "NAV.ACCOUNT_SELECT"],
        "entry_tags": ["ACCOUNT_SELECTOR", "IDENTITY_SWITCH"],
        "action_tags": ["SELECT_ACCOUNT", "SWITCH_IDENTITY", "RECOVER_IDENTITY"],
        "anchors": {
            "NAV.IDENTITY_SWITCH": [
                "setup/src/main/java/com/zhangwenkang/cinefin/setup/domain/UserSessionSelection.kt",
                "app/phone/src/main/java/com/zhangwenkang/cinefin/presentation/offline/OfflineModeViewModel.kt",
            ],
            "NAV.ACCOUNT_SELECT": [
                "app/phone/src/main/java/com/zhangwenkang/cinefin/presentation/setup/users/UsersScreen.kt",
                "setup/src/main/java/com/zhangwenkang/cinefin/setup/presentation/users/UsersViewModel.kt",
            ],
        },
    },
    ("G2-Q03-SUPPLY-CHAIN-GATED", "artifact-inventory"): {
        "capabilities": ["Q03.ARTIFACT_INVENTORY"],
        "entry_tags": ["ARTIFACT"],
        "action_tags": ["INVENTORY_COMPONENT", "MAP_NOTICE"],
        "anchors": {
            "Q03.ARTIFACT_INVENTORY": [
                "com.zhangwenkang.cinefin.release.PackagedSbomManifest",
                "app/phone/src/main/assets/sbom/component-inventory.json",
            ]
        },
    },
    ("G2-Q03-SUPPLY-CHAIN-GATED", "source-and-attestation"): {
        "capabilities": ["Q03.SOURCE_ATTESTATION"],
        "entry_tags": ["ARTIFACT"],
        "action_tags": ["SOURCE_OFFER", "VERIFY_ATTESTATION"],
        "anchors": {
            "Q03.SOURCE_ATTESTATION": [
                "com.zhangwenkang.cinefin.release.SourceObligationManifest",
                "com.zhangwenkang.cinefin.release.CandidateAttestation",
            ]
        },
    },
    ("G2-Q03-SUPPLY-CHAIN-GATED", "runtime-and-16kb"): {
        "capabilities": ["Q03.RUNTIME_16KB"],
        "entry_tags": ["NATIVE_RUNTIME"],
        "action_tags": ["VERIFY_LOAD_ORDER", "VERIFY_16KB"],
        "anchors": {
            "Q03.RUNTIME_16KB": [
                "com.zhangwenkang.cinefin.player.nativebridge.NativeLoadOrderContract",
                "com.zhangwenkang.cinefin.release.SixteenKbPageVerifier",
            ]
        },
    },
}

RISK_SPECS = [
    {
        "id": "R-S6-P0-01",
        "severity": "P0",
        "title": "破坏性数据库 fallback 或迁移不一致可造成真实数据丢失",
        "domain": ["ARCH", "DOWNLOAD", "READER", "MUSIC", "WRITE"],
        "source": ["EV-S2", "V2-D199"],
        "impact": "真实用户登录、下载、书签、进度、歌单或设置可能被清空，或 UI 显示成功后文件 / 状态不一致。",
        "gate": "G2-ARCH-CONTRACTS-MIGRATION / G2-DOWNLOAD-STATE",
        "closure_evidence": [
            "release 可达代码不存在 destructive fallback",
            "1.4→2.0 在可丢弃 profile 上完成中断 / 恢复 / FAIL_CLOSED 测试",
            "DB + WAL/SHM + sidecar + journal + file + projection 全部对账",
        ],
    },
    {
        "id": "R-S6-P1-01",
        "severity": "P1",
        "title": "逻辑 seam 与 owner allowlist 只存在于静态计划",
        "domain": ["ARCH"],
        "source": ["EV-S1", "V2-D198"],
        "impact": "实现可能继续跨层直连并放大后续集成、测试和回滚成本。",
        "gate": "G2-ARCH-CONTRACTS-MIGRATION",
        "closure_evidence": ["debt ratchet 可执行", "旧违规只减不增", "关键 contract / owner 测试绑定候选 SHA"],
    },
    {
        "id": "R-S6-P1-02",
        "severity": "P1",
        "title": "跨存储状态并非单事务，崩溃窗口仍可能制造假完成 / 幽灵状态",
        "domain": ["ARCH", "DOWNLOAD", "READER"],
        "source": ["EV-S2", "EV-A1", "V2-D199"],
        "impact": "Room、sidecar、文件、通知、receipt 或离线投影可在崩溃后不一致。",
        "gate": "G2-ARCH-CONTRACTS-MIGRATION / G2-DOWNLOAD-STATE / G2-READER-CORE",
        "closure_evidence": ["generation / receipt / journal 可重放", "force-stop / reboot 后状态收敛", "故障点注入逐项有终态"],
    },
    {
        "id": "R-S6-P1-03",
        "severity": "P1",
        "title": "搜索历史 ANR / Input timeout 尚未在 2.0 候选包关闭",
        "domain": ["UX", "NAV", "BASE-001"],
        "source": ["EV-BASE001", "EV-S3"],
        "impact": "查询或结果渲染可能再次阻塞 Main，导致输入失效或 ANR。",
        "gate": "G2-SEARCH",
        "closure_evidence": ["候选包连续搜索无 ≥5s Main block / Input timeout", "返回恢复与离线状态证据", "trace 绑定候选 SHA"],
    },
    {
        "id": "R-S6-P1-04",
        "severity": "P1",
        "title": "应用更新与服务器写均存在“受理不等于确认”的假成功风险",
        "domain": ["BASE-002", "WRITE"],
        "source": ["EV-A2", "EV-S3", "V2-D102"],
        "impact": "更新可能未安装却显示完成；服务器写可能未回读却清除 pending。",
        "gate": "G2-UPDATE-TRUST / G2-SERVER-WRITE",
        "closure_evidence": ["安装目标四字段回读", "写后同源 GET equality", "403 不重试", "失败 / 冲突逐项可见"],
    },
    {
        "id": "R-S6-P1-05",
        "severity": "P1",
        "title": "SWR 的跨进程 fence、304 合同与产品策略未冻结",
        "domain": ["BASE-003"],
        "source": ["EV-BASE003", "EV-S3"],
        "impact": "旧 generation 或跨账号缓存可能覆盖新值，缓存可能被误当实时真源。",
        "gate": "G2-PERSISTENT-SWR",
        "closure_evidence": ["durable generation / tombstone", "跨进程并发负例", "304 只在获证能力启用", "用户策略记录"],
    },
    {
        "id": "R-S6-P1-06",
        "severity": "P1",
        "title": "R1 与单 MediaSession / 双内核组合只有静态设计或 ABI 证据",
        "domain": ["UX", "VIDEO", "MUSIC"],
        "source": ["EV-S5-R1", "EV-S4B", "EV-Q03-NATIVE"],
        "impact": "Compose、TalkBack、RWD、动态色、双内核 load-order 或单实例可能运行时失败。",
        "gate": "G2-UI-R1-FOUNDATION / G2-VIDEO-CORE",
        "closure_evidence": ["候选包主题 / RWD / 无障碍旅程", "单 Player / 单 MediaSession 运行证据", "双 FFmpeg / libass load-order 实测"],
    },
    {
        "id": "R-S6-P1-07",
        "severity": "P1",
        "title": "TTS 小说质量不足、D204 engine strategy A 与 exact profile 许可仍未闭合",
        "domain": ["READER"],
        "source": ["V2-D203", "V2-D204", "EV-Q03-NATIVE", "EV-Q03-TTS-PDF", "EV-S4C"],
        "impact": "当前 Sherpa 三组小说声线均质量不足，Sherpa / Matcha 仅降为兼容回退；D204 只关闭 engine strategy A，IndexTTS / CosyVoice / GPT-SoVITS 尚无官方 Android arm64 runtime 证据，exact engine / profile、model card、声线权属、lexicon、rule、notice 与 hash 均未闭合。",
        "gate": "G2-READER-TTS-GATED / G2-Q03-SUPPLY-CHAIN-GATED",
        "closure_evidence": [
            "D204-A 第二引擎 L1 静态可行性 Spike 与许可门",
            "exact engine / 高表现力或兼容回退 profile 的用户试听确认",
            "逐资产 model card / license / notice / hash",
            "pack/profile provenance 与 packaged source obligation",
        ],
    },
    {
        "id": "R-S6-P1-08",
        "severity": "P1",
        "title": "TTS 纯本地承诺尚无零网络 runtime 与性能证据",
        "domain": ["READER"],
        "source": ["EV-Q03-NATIVE", "EV-Q03-TTS-PDF", "V2-D088"],
        "impact": "架构 local-only 仍可能触发模型下载、网络回退、首音过长或设备不稳定。",
        "gate": "G2-READER-TTS-GATED",
        "closure_evidence": ["飞行模式零 App 网络", "中文 voice 可播放", "首音 / RTF / 内存 / 耗电 / 30 分钟稳定性"],
    },
    {
        "id": "R-S6-P1-09",
        "severity": "P1",
        "title": "PDFium 16 KB、native provenance 与 notice 未闭合",
        "domain": ["READER", "Q03"],
        "source": ["EV-Q03-NATIVE", "EV-Q03-TTS-PDF", "issue #56"],
        "impact": "targetSdk 36 下可能无法加载；BSD third-party notice 与 source revision 不可审计。",
        "gate": "G2-READER-PDF-GATED / G2-Q03-SUPPLY-CHAIN-GATED",
        "closure_evidence": ["固定 native revision / GN args", "64-bit 16 KB static + device PASS", "complete notice / source inventory"],
    },
    {
        "id": "R-S6-P1-10",
        "severity": "P1",
        "title": "PDF Range、取消、弱网与大文件 runtime 未执行",
        "domain": ["READER", "Q03"],
        "source": ["EV-Q03-NATIVE", "EV-S4C"],
        "impact": "远程大 PDF 可能整本加载、内存失控、无法取消或卡死。",
        "gate": "G2-READER-PDF-GATED",
        "closure_evidence": ["206 / Content-Range 逐段验证", "2.53 GB / 5006 页旅程", "取消 / 弱网 / 内存上限"],
    },
    {
        "id": "R-S6-P1-11",
        "severity": "P1",
        "title": "音乐、视频、SyncPlay 与系统媒体入口可能分裂状态真源",
        "domain": ["MUSIC", "VIDEO", "NAV"],
        "source": ["EV-S3", "V2-D107", "V2-D190", "V2-D195"],
        "impact": "出现双 Player、双 MediaSession、旧账号媒体继续播放或通知 / UI / 队列不一致。",
        "gate": "G2-MUSIC-CORE / G2-VIDEO-CORE / G2-NAV-IDENTITY",
        "closure_evidence": ["单活动逻辑会话", "切号暂停并隐藏", "通知 / UI / 队列 / 进度同一 owner 等式"],
    },
    {
        "id": "R-S6-P1-12",
        "severity": "P1",
        "title": "本地歌单生产 parser / SAF / 身份适配尚未实现",
        "domain": ["BASE-004", "MUSIC"],
        "source": ["EV-BASE004", "EV-S2"],
        "impact": "导入可能误合并、丢身份、非原子覆盖或重启后顺序丢失。",
        "gate": "G2-LOCAL-PLAYLIST",
        "closure_evidence": ["LOCAL_DRAFT identity adapter", "SAF 原子写", "重复 / 缺曲 / 冲突策略决策", "重启恢复"],
    },
    {
        "id": "R-S6-P1-13",
        "severity": "P1",
        "title": "静态证据、fixture、fake、历史 1.4 记录被误升级为 2.0 PASS",
        "domain": ["ALL", "R1"],
        "source": ["docs/V2_PLAN.md §5.11.7", "EV-S5-R1", "EV-A1", "EV-A2"],
        "impact": "发布门可能被无 device / candidate evidence 的绿色报告穿透。",
        "gate": "ALL_G2_CARD_REVIEWS",
        "closure_evidence": ["每条 PASS 标记执行环境和证据等级", "E5–E7 未执行不得写 PASS", "候选 artifact SHA 与证据一致"],
    },
    {
        "id": "R-S6-P1-14",
        "severity": "P1",
        "title": "下载 provenance / 通知 / 文件 / 删除一致性仍只有静态合同",
        "domain": ["DOWNLOAD", "READER", "MUSIC", "VIDEO"],
        "source": ["EV-A1", "EV-S2", "EV-S3"],
        "impact": "跨账号串副本、幽灵通知、UI 完成但文件缺失或删除假成功。",
        "gate": "G2-DOWNLOAD-STATE",
        "closure_evidence": ["canonical four-part identity", "complete receipt chain", "WAL / file / projection / notification equality"],
    },
    {
        "id": "R-S6-P1-15",
        "severity": "P1",
        "title": "SyncPlay 官方能力存在但客户端整合与多端运行证据缺失",
        "domain": ["VIDEO"],
        "source": ["EV-S3"],
        "impact": "可能把 pending / echo / 时钟漂移显示成已同步，或创建无法收口的组。",
        "gate": "G2-SYNCPLAY-GATED",
        "closure_evidence": ["versioned capability map", "双客户端命令 / 时钟 / 重连", "revision / resnapshot", "离组清理"],
    },
    {
        "id": "R-S6-P1-16",
        "severity": "P1",
        "title": "packaged SBOM、notice、source bundle 与 attestation 尚不存在",
        "domain": ["Q03", "Architecture", "Release"],
        "source": ["EV-Q03-SBOM", "EV-Q03-NOTICE", "EV-S4A"],
        "impact": "候选包无法完成许可证 / 源码义务和供应链审计，Release 必须保持关闭。",
        "gate": "G2-Q03-SUPPLY-CHAIN-GATED",
        "closure_evidence": ["file-to-component map", "complete notices", "source / written offer", "attestation / rebuild / relink"],
    },
    {
        "id": "R-S6-P1-17",
        "severity": "P1",
        "title": "后台 / 锁屏 TTS owner 与 Activity / ViewModel 生命周期可能混淆",
        "domain": ["READER", "MUSIC", "VIDEO"],
        "source": ["V2-D203", "EV-Q03-TTS-PDF"],
        "impact": "离页或锁屏可能误停朗读，或进程重建后意外自动播放、通知与真实状态分裂。",
        "gate": "G2-READER-TTS-GATED",
        "closure_evidence": ["service owner 在后台 / 锁屏持续", "Activity / ViewModel 销毁不停止", "进程死亡 / 重启只恢复暂停态", "锁屏 / 通知 / UI 状态等式"],
    },
    {
        "id": "R-S6-P1-18",
        "severity": "P1",
        "title": "voice-pack 导入、删除与引用清理可能产生覆盖、幽灵音色或悬空 profile",
        "domain": ["READER"],
        "source": ["V2-D203"],
        "impact": "导入可能替换已有音色；重复导入可能制造副本；删除活动音色可能留下全局 / 逐书悬空引用或破坏其他音色。",
        "gate": "G2-READER-TTS-GATED",
        "closure_evidence": ["append-only import", "exact pack/profile idempotency", "同名不同内容新 profile", "active delete stop/fallback/clean refs", "delete retry receipt"],
    },
    {
        "id": "R-S6-P1-19",
        "severity": "P1",
        "title": "TTS、音乐与视频可能形成双音频 owner 或错误 MediaSession 状态",
        "domain": ["READER", "MUSIC", "VIDEO"],
        "source": ["V2-D203", "V2-D190"],
        "impact": "TTS 与音乐可能双播，停止 TTS 后音乐可能错误自动恢复，audio focus / noisy / 重启后控制面可能撒谎。",
        "gate": "G2-READER-TTS-GATED / G2-MUSIC-CORE / G2-VIDEO-CORE",
        "closure_evidence": ["single active audio owner", "single MediaSession", "TTS start pauses music and keeps queue", "music start terminates TTS", "focus / noisy / restart state matrix"],
    },
    {
        "id": "R-S6-P2-01",
        "severity": "P2",
        "title": "R1 五主题、200%、动态色、折叠和 TalkBack 仅有静态覆盖",
        "domain": ["UX", "R1"],
        "source": ["EV-S5-R1", "EV-UI-R1"],
        "impact": "视觉和交互可能在真实字体、系统设置、焦点顺序或窗口变化中失败。",
        "gate": "G2-UI-R1-FOUNDATION",
        "closure_evidence": ["每主题明暗实机帧", "TalkBack / 键盘 / 动态色 / 200% / 折叠证据"],
    },
    {
        "id": "R-S6-P2-02",
        "severity": "P2",
        "title": "安装包当前候选与 R8 / installer 回读未执行",
        "domain": ["BASE-002"],
        "source": ["EV-A2", "EV-S4B"],
        "impact": "静态信任合同无法证明 2.0 release 安装链。",
        "gate": "G2-UPDATE-TRUST",
        "closure_evidence": ["2.0 candidate package", "installer result readback", "upgrade asset preservation"],
    },
    {
        "id": "R-S6-P2-03",
        "severity": "P2",
        "title": "TTL、容量、预取、歌单重复 / 部分导入等策略尚无用户决策",
        "domain": ["BASE-003", "BASE-004", "MUSIC"],
        "source": ["EV-BASE003", "EV-BASE004"],
        "impact": "实现会被迫自行选择产品行为，导致可持续的返工或数据合并风险。",
        "gate": "G2-PERSISTENT-SWR / G2-LOCAL-PLAYLIST",
        "closure_evidence": ["负责人 / 用户决策记录", "bounded runtime evidence"],
    },
    {
        "id": "R-S6-P2-04",
        "severity": "P2",
        "title": "搜索排序、分组与跨源呈现仍待用户选择",
        "domain": ["BASE-001", "NAV"],
        "source": ["EV-BASE001"],
        "impact": "相同正结果可能以互相冲突的层级或顺序展示。",
        "gate": "G2-SEARCH",
        "closure_evidence": ["用户排序 / 跨源决策", "fixture + UI + device 对照"],
    },
    {
        "id": "R-S6-P2-05",
        "severity": "P2",
        "title": "UI 文本和可访问名称可能回归为颜色或图标单通道",
        "domain": ["UX", "R1"],
        "source": ["EV-S5-R1", "V2-D069"],
        "impact": "TalkBack、色觉障碍或减少动画用户无法判断动作 / 状态。",
        "gate": "G2-UI-R1-FOUNDATION",
        "closure_evidence": ["可访问名称 / 角色 / 状态表", "对比度与键盘焦点证据"],
    },
    {
        "id": "R-S6-P2-06",
        "severity": "P2",
        "title": "阅读大书、弱网、格式差异与本地化字体尚未真机量化",
        "domain": ["READER"],
        "source": ["docs/V2_PLAN.md §5.4.5", "EV-S2"],
        "impact": "可能出现卡顿、内存峰值、错误空态或位置错页。",
        "gate": "G2-READER-CORE",
        "closure_evidence": ["5000-page / large CBZ / weak network", "locator after reflow", "font / 200% rendering"],
    },
    {
        "id": "R-S6-P2-07",
        "severity": "P2",
        "title": "服务器写真实 409 / 403 / 部分失败语义未执行",
        "domain": ["WRITE"],
        "source": ["EV-S3"],
        "impact": "权限拒绝或 RMW 冲突可能被折叠为普通错误或误报成功。",
        "gate": "G2-SERVER-WRITE",
        "closure_evidence": ["独立写服 401/403/404/409/503", "read-back mismatch", "batch partial results"],
    },
    {
        "id": "R-S6-P2-08",
        "severity": "P2",
        "title": "下载通知 / 前台服务 / 存储卷 / OEM 行为存在设备差异",
        "domain": ["DOWNLOAD"],
        "source": ["EV-A1", "EV-S2"],
        "impact": "通知权限、MIUI 限制或 SAF 失效会让任务状态不可见或恢复失败。",
        "gate": "G2-DOWNLOAD-STATE",
        "closure_evidence": ["双设备通知 / 存储 / force-stop / reboot", "权限拒绝不阻塞传输"],
    },
    {
        "id": "R-S6-P2-09",
        "severity": "P2",
        "title": "双 FFmpeg / libass 同名符号、R8 与多 ABI 兼容仍有运行时风险",
        "domain": ["VIDEO", "MUSIC"],
        "source": ["EV-S4B", "EV-Q03-NATIVE"],
        "impact": "类初始化顺序、符号 interposition 或 R8 映射可能导致崩溃或静默回退。",
        "gate": "G2-VIDEO-CORE / G2-Q03-SUPPLY-CHAIN-GATED",
        "closure_evidence": ["同进程 load / switch", "符号解析日志", "R8 candidate", "四 ABI / 16 KB"],
    },
    {
        "id": "R-S6-P2-10",
        "severity": "P2",
        "title": "SyncPlay 无显式 revision，排序依赖时间戳与 resnapshot",
        "domain": ["VIDEO"],
        "source": ["EV-S3"],
        "impact": "并发命令和网络抖动可能造成同组状态不同步。",
        "gate": "G2-SYNCPLAY-GATED",
        "closure_evidence": ["相关 id / generation", "双端重连与 resnapshot", "时钟误差和漂移窗口"],
    },
    {
        "id": "R-S6-P2-11",
        "severity": "P2",
        "title": "导航侧轨紧凑项 44dp 视觉高度与 48dp 热区仍未设备确认",
        "domain": ["NAV", "R1"],
        "source": ["EV-S5-R1", "EV-S3"],
        "impact": "紧凑项可能误触或焦点不可见。",
        "gate": "G2-NAV-IDENTITY / G2-UI-R1-FOUNDATION",
        "closure_evidence": ["真实命中区测量", "键盘焦点环", "TalkBack 导航"],
    },
    {
        "id": "R-S6-P3-01",
        "severity": "P3",
        "title": "S5 证据包中的 Q04=OPEN 是历史快照",
        "domain": ["R1", "Q04"],
        "source": ["EV-S5-R1", "V2-D201"],
        "impact": "冷启动或 reviewer 可能把历史快照误当当前状态。",
        "gate": "Scoped review entry",
        "closure_evidence": ["所有下游以 DECISIONS D201 和 V2_PLAN ACTIVE 当前状态为准"],
    },
    {
        "id": "R-S6-P3-02",
        "severity": "P3",
        "title": "历史 1.4 QA / 界面记录与 2.0 候选包存在对象漂移",
        "domain": ["ALL"],
        "source": ["docs/V2_PLAN.md §9", "docs/TASKS.md"],
        "impact": "复用历史 PASS 可能掩盖 V2 新行为或新缺陷。",
        "gate": "Scoped review / G2 acceptance",
        "closure_evidence": ["历史记录只作低阶参考", "最终 PASS 绑定 2.0 candidate SHA"],
    },
]

THRESHOLD_SPECS = [
    ("T-P0-ZERO", ["V2-REQ-ARCH-008", "V2-REQ-ARCH-040"], "P0 未关闭数量", "==", 0, "count", "release candidate", "E7", "ACCEPTED_STATIC / RUNTIME_UNVERIFIED", "docs/V2_PLAN.md §5.2.2"),
    ("T-P1-CLOSED", ["V2-REQ-ARCH-008", "V2-REQ-ARCH-040"], "P1 未关闭数量", "==", 0, "count", "release candidate，除非有用户书面延期", "E7", "ACCEPTED_STATIC / EXCEPTION_RECORD_REQUIRED", "docs/V2_PLAN.md §5.2.2"),
    ("T-MODULE-CYCLES", ["V2-REQ-ARCH-013", "V2-REQ-ARCH-057", "V2-REQ-ARCH-068", "V2-REQ-ARCH-070"], "模块级循环依赖数量", "==", 0, "count", "candidate architecture gate", "E1", "STATIC_BASELINE_PASS / IMPLEMENTATION_PENDING", "docs/V2_PLAN.md §5.2.2; EV-S1"),
    ("T-UNAUTHORIZED-EDGES", ["V2-REQ-ARCH-013", "V2-REQ-ARCH-057", "V2-REQ-ARCH-068"], "未授权跨层 / 反向 / 隐式依赖", "==", 0, "count", "candidate architecture gate", "E1", "CONTRACT_ONLY / RUNTIME_UNVERIFIED", "docs/V2_PLAN.md §5.2.2; EV-S1"),
    ("T-DATA-OWNER", ["V2-REQ-ARCH-058"], "无唯一 writer 的核心数据类别", "==", 0, "count", "candidate architecture gate", "E1", "CONTRACT_ONLY", "docs/V2_PLAN.md §5.2.2"),
    ("T-PAGE-PADDING", ["V2-REQ-UX-009", "R1-RWD-MEDIUM"], "页面内边距下限", ">=", 12, "dp", "phone and window classes", "E5/E7", "ACCEPTED_STATIC / RUNTIME_UNVERIFIED", "docs/V2_PLAN.md §5.3.15; docs/UI_DESIGN_SYSTEM.md §V2-R1-03"),
    ("T-BLOCK-GAP", ["V2-REQ-UX-009"], "区块间距下限", ">=", 32, "dp", "all UI forms", "E5/E7", "ACCEPTED_STATIC / RUNTIME_UNVERIFIED", "docs/V2_PLAN.md §5.3.15"),
    ("T-TOUCH-TARGET", ["V2-REQ-UX-009", "V2-REQ-MUSIC-033", "V2-REQ-READER-023", "V2-REQ-VIDEO-062", "R1-A11Y-TOUCH-48"], "交互触控热区", ">=", "48x48", "dp", "touch devices", "E5/E7", "ACCEPTED_STATIC / RUNTIME_UNVERIFIED", "docs/UI_DESIGN_SYSTEM.md V2-R1-04"),
    ("T-FOCUS-RING", ["V2-REQ-UX-009"], "键盘焦点环宽度", "==", 2, "dp", "keyboard / pointer environment", "E5/E7", "ACCEPTED_STATIC / RUNTIME_UNVERIFIED", "docs/UI_DESIGN_SYSTEM.md V2-R1-04"),
    ("T-A11Y-ACTION-NAMES", ["R1-A11Y-NAMES"], "具有可访问名称、角色与状态策略的示例动作数", "==", 18, "actions", "static R1 mapping / TalkBack and keyboard runtime separate", "E0/E5/E7", "STATIC_MAPPING_18/18 / RUNTIME_UNVERIFIED", "docs/UI_DESIGN_SYSTEM.md V2-R1-04"),
    ("T-TEXT-CONTRAST", ["V2-REQ-UX-008", "R1-A11Y-CONTRAST"], "正文文字对比度", ">=", 4.5, ":1", "all themes / appearances", "E0/E5/E7", "STATIC_PASS_TEXT_110/110 / RUNTIME_UNVERIFIED", "docs/UI_DESIGN_SYSTEM.md V2-R1-04"),
    ("T-BOUNDARY-CONTRAST", ["R1-A11Y-CONTRAST"], "焦点与必要边界对比度", ">=", 3.0, ":1", "all themes / appearances", "E0/E5/E7", "STATIC_PASS_BOUNDARY_20/20 / RUNTIME_UNVERIFIED", "docs/UI_DESIGN_SYSTEM.md §129-130"),
    ("T-TOTAL-CONTRAST", ["R1-A11Y-CONTRAST"], "S5 静态对比度 pair 总数", "==", 130, "pair", "static themes / appearances", "E0", "STATIC_PASS_TOTAL_130/130 / RUNTIME_UNVERIFIED", "docs/UI_DESIGN_SYSTEM.md §13-14, §129-130"),
    ("T-THEME-MOTION", ["V2-REQ-UX-011", "R1-I07"], "主题交互动效时长", "between", [150, 300], "ms", "normal motion", "E5/E7", "ACCEPTED_STATIC / RUNTIME_UNVERIFIED", "docs/V2_PLAN.md §5.3.15"),
    ("T-REDUCE-MOTION", ["V2-REQ-UX-011", "R1-A11Y-REDUCE-MOTION"], "Reduce Motion 下装饰过渡时长", "==", 0, "ms", "system reduced motion", "E5/E7", "ACCEPTED_STATIC / RUNTIME_UNVERIFIED", "docs/UI_DESIGN_SYSTEM.md §V2-R1-04"),
    ("T-RWD-COMPACT", ["V2-REQ-UX-037", "R1-RWD-COMPACT"], "Compact 有效宽度", "<", 600, "dp", "window class selection", "E3/E5/E7", "ACCEPTED_STATIC / RUNTIME_UNVERIFIED", "docs/UI_DESIGN_SYSTEM.md §V2-R1-03"),
    ("T-RWD-MEDIUM", ["V2-REQ-UX-037", "R1-RWD-MEDIUM"], "Medium 有效宽度", "range_inclusive", [600, 839], "dp", "window class selection", "E3/E5/E7", "ACCEPTED_STATIC / RUNTIME_UNVERIFIED", "docs/UI_DESIGN_SYSTEM.md §V2-R1-03"),
    ("T-RWD-EXPANDED", ["V2-REQ-UX-037", "R1-RWD-EXPANDED"], "Expanded 有效宽度", "range_inclusive", [840, 1199], "dp", "window class selection", "E3/E5/E7", "ACCEPTED_STATIC / RUNTIME_UNVERIFIED", "docs/UI_DESIGN_SYSTEM.md §V2-R1-03"),
    ("T-RWD-LARGE", ["V2-REQ-UX-037", "R1-RWD-LARGE"], "Large 有效宽度", "range_inclusive", [1200, 1599], "dp", "window class selection", "E3/E5/E7", "ACCEPTED_STATIC / RUNTIME_UNVERIFIED", "docs/UI_DESIGN_SYSTEM.md §V2-R1-03"),
    ("T-RWD-EXTRA-LARGE", ["V2-REQ-UX-037", "R1-RWD-EXTRA-LARGE"], "ExtraLarge 有效宽度", ">=", 1600, "dp", "window class selection", "E3/E5/E7", "ACCEPTED_STATIC / RUNTIME_UNVERIFIED", "docs/UI_DESIGN_SYSTEM.md §V2-R1-03"),
    ("T-NOCTURNE-RADIUS", ["R1-THEME-NOCTURNE", "V2-D201", "V2-D057"], "Nocturne 基础圆角", "==", 8, "dp", "Nocturne theme", "E5/E7", "ACCEPTED_STATIC / RUNTIME_UNVERIFIED", "docs/UI_DESIGN_SYSTEM.md §V2-R1-02"),
    ("T-PRISM-RADIUS", ["R1-THEME-PRISM", "V2-D057"], "Prism 基础圆角", "==", 12, "dp", "Prism theme", "E5/E7", "ACCEPTED_STATIC / RUNTIME_UNVERIFIED", "docs/UI_DESIGN_SYSTEM.md §V2-R1-02"),
    ("T-LUMEN-RADIUS", ["R1-THEME-LUMEN", "V2-D057"], "Lumen 基础圆角", "==", 16, "dp", "Lumen theme", "E5/E7", "ACCEPTED_STATIC / RUNTIME_UNVERIFIED", "docs/UI_DESIGN_SYSTEM.md §V2-R1-02"),
    ("T-MONOLITH-RADIUS", ["R1-THEME-MONOLITH", "V2-D057"], "Monolith 基础圆角", "==", 8, "dp", "Monolith theme", "E5/E7", "ACCEPTED_STATIC / RUNTIME_UNVERIFIED", "docs/UI_DESIGN_SYSTEM.md §V2-R1-02"),
    ("T-DYNAMIC-RADIUS", ["R1-THEME-DYNAMIC", "V2-D057"], "系统动态色基础圆角", "==", 12, "dp", "Android dynamic color theme", "E5/E7", "ACCEPTED_STATIC / RUNTIME_UNVERIFIED", "docs/UI_DESIGN_SYSTEM.md §V2-R1-02"),
    ("T-MUSIC-SPEED", ["V2-REQ-MUSIC-046"], "音乐变速范围", "range_inclusive", [0.5, 2.0], "x", "music playback", "E7", "ACCEPTED_STATIC / RUNTIME_UNVERIFIED", "docs/V2_PLAN.md §5.6.6.1"),
    ("T-MUSIC-RETRY", ["V2-REQ-MUSIC-018"], "可重试瞬时网络失败最大额外尝试", "==", 1, "attempt", "first failure; 401 only after one successful credential refresh", "E7", "ACCEPTED_STATIC / RUNTIME_UNVERIFIED", "docs/V2_PLAN.md §5.6.6.1"),
    ("T-MUSIC-HORIZONTAL-COMMIT", ["V2-REQ-MUSIC-053"], "横向切歌提交条件", "OR", {"min_distance_dp": 24, "distance_dp": 72, "velocity_dp_s": 600}, "dp or dp/s", "music gesture", "E5/E7", "ACCEPTED_STATIC / RUNTIME_UNVERIFIED", "docs/V2_PLAN.md §5.6.6.1"),
    ("T-MUSIC-VERTICAL-COMMIT", ["V2-REQ-MUSIC-053"], "纵向面板提交条件", "OR", {"available_height_pct": 18, "velocity_dp_s": 800}, "% or dp/s", "music gesture", "E5/E7", "ACCEPTED_STATIC / RUNTIME_UNVERIFIED", "docs/V2_PLAN.md §5.6.6.1"),
    ("T-MUSIC-SLEEP-FADE", ["V2-REQ-MUSIC-047"], "睡眠定时结束前淡出", "==", 5, "s", "active playback", "E5/E7", "ACCEPTED_STATIC / RUNTIME_UNVERIFIED", "docs/V2_PLAN.md §5.6.6.1"),
    ("T-MUSIC-SLEEP-CUSTOM", ["V2-REQ-MUSIC-047"], "自定义睡眠定时范围", "range_inclusive", [1, 240], "minute", "music playback", "E5/E7", "ACCEPTED_STATIC / RUNTIME_UNVERIFIED", "docs/V2_PLAN.md §5.6.6.1"),
    ("T-BASE005-INITIAL-N", ["V2-REQ-BASE-005"], "首屏懒补曲目数 N", "UNRESOLVED", "UNRESOLVED", "track", "large playlist / queue", "G1 decision", "UNRESOLVED", "docs/V2_PLAN.md §5.10.5"),
    ("T-BASE005-SCENARIO", ["V2-REQ-BASE-005"], "大队列代表性规模", "range_inclusive", [349, 1000], "tracks", "large playlist / queue", "E7", "ACCEPTED_SCENARIO / NOT_A_HARD_LIMIT", "docs/V2_PLAN.md §5.10.5"),
    ("T-DOWNLOAD-PROGRESS-DISPLAY", ["V2-REQ-DOWNLOAD-006"], "显示进度最短刷新间隔", ">=", 500, "ms", "download UI", "E7", "ACCEPTED_STATIC / RUNTIME_UNVERIFIED", "docs/V2_PLAN.md §5.7; V2-D139"),
    ("T-VIDEO-SPEED", ["V2-REQ-VIDEO-027"], "普通视频倍速范围", "range_inclusive", [0.25, 4.0], "x", "video playback", "E7", "ACCEPTED_STATIC / RUNTIME_UNVERIFIED", "docs/V2_PLAN.md §5.8.9"),
    ("T-VIDEO-LONG-PRESS-SPEED", ["V2-REQ-VIDEO-027"], "长按临时倍速", "==", 2.0, "x", "video playback gesture", "E5/E7", "ACCEPTED_STATIC / RUNTIME_UNVERIFIED", "docs/V2_PLAN.md §5.8.9"),
    ("T-VIDEO-SUBTITLE-DELAY-DEFAULT", ["V2-REQ-VIDEO-042"], "字幕延迟默认值", "==", 50, "ms", "video subtitle", "E7", "ACCEPTED_STATIC / RUNTIME_UNVERIFIED", "docs/V2_PLAN.md §5.8.9"),
    ("T-VIDEO-SUBTITLE-DELAY-STEP", ["V2-REQ-VIDEO-042"], "字幕延迟调整步长", "==", 50, "ms", "video subtitle", "E7", "ACCEPTED_STATIC / RUNTIME_UNVERIFIED", "docs/V2_PLAN.md §5.8.9"),
    ("T-VIDEO-SUBTITLE-DELAY-RANGE", ["V2-REQ-VIDEO-042"], "字幕延迟范围", "range_inclusive", [-10000, 10000], "ms", "video subtitle", "E7", "ACCEPTED_STATIC / RUNTIME_UNVERIFIED", "docs/V2_PLAN.md §5.8.9"),
    ("T-VIDEO-CONTROL-HIDE", ["V2-REQ-VIDEO-062", "R1-I08"], "播放中无操作隐藏控制层", "~=", 4, "s", "video playback; paused/panel/assistive focus keeps visible", "E5/E7", "ACCEPTED_APPROXIMATE / RUNTIME_UNVERIFIED", "docs/V2_PLAN.md §5.8.9; docs/UI_DESIGN_SYSTEM.md V2-R1-04"),
    ("T-VIDEO-GESTURE-DIRECTION", ["V2-REQ-VIDEO-057"], "首次显著位移方向锁阈值", "~range", [8, 10], "vp", "video gesture", "E5/E7", "ACCEPTED_APPROXIMATE / EXACT_DEVICE_VALUE_OPEN", "docs/V2_PLAN.md §5.8.9"),
    ("T-SYNCPLAY-SPEED", ["V2-REQ-VIDEO-093"], "SyncPlay 成员用户倍速", "==", 1.0, "x", "joined SyncPlay group", "E7", "ACCEPTED_STATIC / RUNTIME_UNVERIFIED", "docs/V2_PLAN.md §5.8.9"),
    ("T-SEARCH-MAIN-BLOCK", ["V2-REQ-BASE-001"], "搜索 Main 阻塞 / Input timeout", "<", 5000, "ms", "candidate package search", "E5/E7", "ACCEPTED_REOPEN_THRESHOLD / RUNTIME_UNVERIFIED", "docs/V2_PLAN.md §5.10.5; §9 L14-01"),
    ("T-SBOM-UNRESOLVED", ["V2-REQ-ARCH-070", "V2-Q-G1-03"], "snapshot 未解析 external component", "==", 0, "count", "frozen S4-A snapshot only", "E0", "STATIC_PASS / CANDIDATE_PACKAGED_SBOM_OPEN", "EV-Q03-SBOM"),
    ("T-PDF-16KB", ["V2-Q-G1-03", "V2-REQ-READER-081"], "64-bit native 16 KB page-size 兼容", "==", "PASS", "static and runtime gate", "candidate target ABI", "E0/E5/E7", "BLOCKED", "EV-Q03-NATIVE; issue #56"),
    ("T-TTS-ZERO-NETWORK", ["V2-REQ-READER-060", "V2-REQ-READER-063", "V2-REQ-READER-087", "V2-REQ-READER-088", "V2-REQ-READER-089", "V2-REQ-READER-090", "V2-Q-G1-03"], "TTS 期间 App 网络流量", "==", 0, "bytes / app", "flight mode / background / selected device", "E5/E7", "BLOCKED", "V2-D203; EV-Q03-TTS-PDF; EV-S4C"),
    ("T-TTS-PROCESS-RESTART-AUTOPLAY", ["V2-REQ-READER-087"], "进程死亡 / 重启后自动开始 TTS", "==", 0, "count", "candidate package process death / relaunch", "E4/E5/E7", "BLOCKED", "V2-D203"),
    ("T-TTS-VOICE-DANGLING", ["V2-REQ-READER-088", "V2-REQ-READER-089"], "删除音色后悬空全局 / 逐书 profile 引用", "==", 0, "count", "voice-pack deletion fault injection", "E1/E4/E5", "BLOCKED", "V2-D203"),
    ("T-TTS-VOICE-REIMPORT", ["V2-REQ-READER-088"], "完全相同 pack / profile 重复导入新增条目", "==", 0, "count", "voice-pack import idempotency", "E1/E4", "BLOCKED", "V2-D203"),
    ("T-TTS-ACTIVE-OWNER", ["V2-REQ-READER-090"], "同时活动的 TTS 与音乐 audio owner / MediaSession", "==", 0, "count", "background / focus / noisy / restart", "E4/E5/E7", "BLOCKED", "V2-D203"),
]

EVIDENCE_SPECS = [
    {
        "id": "EV-S1",
        "label": "G1-S1 architecture static evidence",
        "path": ".planning/v2-g1/G1-S1/report.json",
        "kind": "report",
        "level": "E0/E1-static-architecture",
        "status": "COMPLETE_STATIC_EVIDENCE / 0 cycles / 19 direct edges",
        "can_prove": ["21? actual report declares 19 direct edges and 0 module cycles", "11 stable violations", "logical seam comparison"],
        "cannot_prove": ["compile / APK", "runtime behavior", "physical split benefit"],
    },
    {
        "id": "EV-S2",
        "label": "G1-S2 schema / identity / migration evidence",
        "path": ".planning/v2-g1/G1-S2/report.json",
        "kind": "report",
        "level": "E0/static",
        "status": "EVIDENCE_FROZEN_FOR_INTEGRATION / P0 destructive fallback remains",
        "can_prove": ["1.4 schema and identity baseline", "cross-store invariants", "fixture design"],
        "cannot_prove": ["real migration", "WAL/SHM/hot journal behavior", "runtime"],
    },
    {
        "id": "EV-S3",
        "label": "G1-S3 protocol capability contracts",
        "path": ".planning/v2-g1/G1-S3/report.json",
        "kind": "report",
        "level": "E0/static",
        "status": "G1_S3_STATIC_EVIDENCE_COMPLETE_WITH_BLOCKED_RUNTIME_GAPS",
        "can_prove": ["official endpoint / state model is statically expressible", "client current gaps"],
        "cannot_prove": ["real HTTP / write / SyncPlay / range behavior"],
    },
    {
        "id": "EV-S4A",
        "label": "G1-S4-A runtime closure and license input",
        "path": ".planning/v2-g1/G1-S4-A/report.json",
        "kind": "report",
        "level": "E0/static",
        "status": "COMPLETE_WITH_BLOCKED_LICENSE_CLOSURE",
        "can_prove": ["296 external dependency graph nodes", "35 direct external", "9 project modules", "1.4 APK inventory"],
        "cannot_prove": ["candidate packaged SBOM", "native exact license/provenance", "runtime compatibility"],
    },
    {
        "id": "EV-S4B",
        "label": "G1-S4-B media/native ABI matrix",
        "path": ".planning/v2-g1/G1-S4-B/report.json",
        "kind": "report",
        "level": "E0/static",
        "status": "COMPLETE_WITH_RUNTIME_AND_DOWNSTREAM_Q03_GAPS",
        "can_prove": ["Media3 / decoder / libmpv / libc++ static ABI and symbol facts", "17? see report details", "Q03 media input ready"],
        "cannot_prove": ["dual-kernel runtime load-order", "current release R8", "playback / subtitle runtime"],
    },
    {
        "id": "EV-S4C",
        "label": "G1-S4-C TTS/PDF option matrix",
        "path": ".planning/v2-g1/G1-S4-C/report.json",
        "kind": "report",
        "level": "E0/static",
        "status": "non-media input ready for Q03 / full Q03 not ready",
        "can_prove": ["candidate options and hard gates", "P0 none / six P1 risks"],
        "cannot_prove": ["selected engine / voice", "device / runtime / 16 KB"],
    },
    {
        "id": "EV-S5-R1",
        "label": "G1-S5 R1 static visual evidence",
        "path": ".planning/v2-g1/G1-S5/manifest.json",
        "kind": "manifest/report",
        "level": "E0/static",
        "status": "historical decision input; current Q04 closed by D201, R1 UI authority accepted",
        "can_prove": ["5 themes / 9 interactions / 6 RWD frames / 60 state mappings / 12 a11y checks / 130 contrast pairs static"],
        "cannot_prove": ["Compose / TalkBack / font / dynamic color / foldable / runtime"],
    },
    {
        "id": "EV-UI-R1",
        "label": "UI Design System V2 R1 accepted authority",
        "path": "docs/UI_DESIGN_SYSTEM.md",
        "kind": "authority",
        "level": "human-readable authority",
        "status": "V2_R1_ACCEPTED_DESIGN_AUTHORITY / RUNTIME_UNVERIFIED / G1_NOT_PASSED",
        "can_prove": ["accepted theme / tokens / RWD / interaction / a11y requirements"],
        "cannot_prove": ["render / device / a11y runtime"],
    },
    {
        "id": "EV-A1",
        "label": "A1 download state contract",
        "path": ".planning/v2-g1/G1-A1-DOWNLOAD-STATE-CONTRACT/report.json",
        "kind": "report",
        "level": "E0/fixture",
        "status": "STATIC_CONTRACT_COMPLETE_WITH_CAPABILITY_GAPS / RUNTIME_UNVERIFIED",
        "can_prove": ["12 states", "24 allowed transitions", "16 forbidden examples", "55 fixtures", "10 equations"],
        "cannot_prove": ["Room+sidecar+file+WAL", "WorkManager / notification / device", "real download journey"],
    },
    {
        "id": "EV-A2",
        "label": "A2 update trust contract",
        "path": ".planning/v2-g1/G1-A2-UPDATE-TRUST-CONTRACT/report.json",
        "kind": "report",
        "level": "E0/fixture",
        "status": "STATIC_CONTRACT_COMPLETE_WITH_IMPLEMENTATION_GAPS",
        "can_prove": ["10 states", "17 transitions", "23 ordered checks", "46 fixtures", "348 properties"],
        "cannot_prove": ["real GitHub asset", "real APK signer / installer", "device upgrade"],
    },
    {
        "id": "EV-BASE001",
        "label": "BASE-001 search route contract",
        "path": ".planning/v2-g1/G1-BASE-001-SEARCH-ROUTE-CONTRACT/report.json",
        "kind": "report",
        "level": "E0/fixture",
        "status": "STATIC_CONTRACT_COMPLETE_RUNTIME_UNVERIFIED",
        "can_prove": ["4 scopes", "complete query shape", "11 route states", "31 fixtures", "3 mutation classes"],
        "cannot_prove": ["production search", "candidate ANR / restore / offline"],
    },
    {
        "id": "EV-BASE003",
        "label": "BASE-003 persistent SWR contract",
        "path": ".planning/v2-g1/G1-BASE-003-PERSISTENT-SWR/report.json",
        "kind": "report",
        "level": "E0/fixture",
        "status": "STATIC_CONTRACT_COMPLETE_WITH_OPEN_POLICY_AND_RUNTIME_GAPS",
        "can_prove": ["19 identity dimensions", "10 projection states", "11 TTL boundary rules", "4 fixtures"],
        "cannot_prove": ["candidate stale marker", "cross-process fence", "304 endpoint contract"],
    },
    {
        "id": "EV-BASE004",
        "label": "BASE-004 local playlist exchange contract",
        "path": ".planning/v2-g1/G1-BASE-004-PLAYLIST-EXCHANGE/report.json",
        "kind": "report",
        "level": "E0/fixture",
        "status": "STATIC_CONTRACT_COMPLETE_WITH_B_C_D_OPEN",
        "can_prove": ["v1 schema", "34 M3U8 mapping rows", "69 roundtrip rows", "99/99 static checks"],
        "cannot_prove": ["production parser", "SAF device atomicity", "user merge / partial import choices"],
    },
    {
        "id": "EV-BASE005",
        "label": "BASE-005 large queue / write amplification requirement and S3 input",
        "path": "docs/V2_PLAN.md",
        "kind": "requirement/static",
        "level": "E0",
        "status": "MUST group / runtime and N unresolved",
        "can_prove": ["no per-item synchronous negotiation for 349–1000 tail scenario", "need bounded lazy fill"],
        "cannot_prove": ["initial N", "concurrency cap", "write amplification", "device performance"],
    },
    {
        "id": "EV-Q03-SBOM",
        "label": "Q03 snapshot SBOM",
        "path": ".planning/v2-g1/G1-S4-A-Q03-SBOM/report.json",
        "kind": "report/SBOM summary",
        "level": "E0/static",
        "status": "PARTIAL / Q03_OPEN",
        "can_prove": ["296 external components", "35 direct", "261 transitive", "0 unresolved snapshot", "296 PURL"],
        "cannot_prove": ["candidate packaged SBOM", "native exact provenance", "runtime compatibility"],
    },
    {
        "id": "EV-Q03-NATIVE",
        "label": "Q03 native supply-chain matrix",
        "path": ".planning/v2-g1/G1-S4-A-Q03-NATIVE/report.json",
        "kind": "report",
        "level": "E0/static",
        "status": "Q03_NATIVE_PARTIAL / Q03_NOT_CLOSED",
        "can_prove": ["31 subitems = 13 PROVEN / 6 PARTIAL / 12 BLOCKED", "Media3 / libmpv / TTS / PDF static facts"],
        "cannot_prove": ["load-order", "zero-network TTS", "PDFium 16 KB / Range", "final notices"],
    },
    {
        "id": "EV-Q03-NOTICE",
        "label": "Q03 notice / source offer matrix",
        "path": ".planning/v2-g1/G1-S4-A-Q03-NOTICE/report.json",
        "kind": "report",
        "level": "E0/static",
        "status": "MEDIA_NOTICE_PARTIAL / Q03_OPEN",
        "can_prove": ["provenance 24=13/4/7", "notice-source 10=5/2/3"],
        "cannot_prove": ["final packaged notice", "source bundle", "attestation / relink"],
    },
    {
        "id": "EV-Q03-TTS-PDF",
        "label": "Q03 TTS / PDF evidence",
        "path": ".planning/v2-g1/G1-S4-A-Q03-TTS-PDF/report.json",
        "kind": "report",
        "level": "E0/static",
        "status": "TTS_PDF_PARTIAL / Q03_OPEN",
        "can_prove": [
            "21 evidence rows = 9 PROVEN / 2 PARTIAL / 10 BLOCKED",
            "Sherpa/Piper code and artifact facts plus PDF wrapper/native-license separation are statically reported; no TTS engine or voice is selected",
        ],
        "cannot_prove": [
            "specific second engine / exact profile / voice",
            "model / lexicon / rule / voice license and complete notice / source bundle",
            "background / lockscreen / zero-network TTS runtime",
            "PDFium exact revision / third-party notice / 16 KB / Range runtime",
        ],
    },
    {
        "id": "EV-S6",
        "label": "S6 scoped-reviewed matrix and planning artifacts",
        "path": ".planning/v2-g1/G1-S6/acceptance-matrix.json",
        "kind": "S6 reviewed planning package",
        "level": "E0/planning",
        "status": EV_S6_STATUS,
        "can_prove": ["traceability, owner map, thresholds, risks and G2 input coverage", "external 445/376 preserved; D203 addendum 4 / normalized MVP 380"],
        "cannot_prove": ["implementation", "runtime", "device", "QA", "G1 PASS"],
    },
]


def read_text(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def load_row_semantics() -> dict[str, dict[str, Any]]:
    if not SEMANTICS_PATH.is_file():
        raise FileNotFoundError(
            f"{SEMANTICS_PATH}: explicit row semantics are required; no keyword fallback is permitted"
        )
    document = json.loads(read_text(SEMANTICS_PATH))
    if document.get("schema_version") not in {
        "g1-s6-row-semantics/v1",
        SEMANTIC_SCHEMA,
    }:
        raise ValueError(f"unexpected row semantics schema: {document.get('schema_version')}")
    records = document.get("rows")
    if not isinstance(records, list):
        raise ValueError("row-semantics.json rows must be a list")
    by_id: dict[str, dict[str, Any]] = {}
    for record in records:
        promise_id = record.get("promise_id")
        if not isinstance(promise_id, str) or not promise_id:
            raise ValueError("row semantics contains blank promise_id")
        if promise_id in by_id:
            raise ValueError(f"duplicate row semantic record: {promise_id}")
        by_id[promise_id] = record
    return by_id


def load_wave_membership() -> dict[str, dict[str, Any]]:
    if not WAVE_MEMBERSHIP_PATH.is_file():
        raise FileNotFoundError(
            f"{WAVE_MEMBERSHIP_PATH}: explicit row-to-wave membership is required; "
            "no partition, keyword, hash or round-robin fallback is permitted"
        )
    document = json.loads(read_text(WAVE_MEMBERSHIP_PATH))
    if document.get("schema_version") != WAVE_MEMBERSHIP_SCHEMA:
        raise ValueError(
            f"unexpected wave membership schema: {document.get('schema_version')}"
        )
    records = document.get("memberships")
    if not isinstance(records, list):
        raise ValueError("wave-membership.json memberships must be a list")
    by_row_id: dict[str, dict[str, Any]] = {}
    for record in records:
        row_id = record.get("row_id")
        if not isinstance(row_id, str) or not row_id:
            raise ValueError("wave membership contains blank row_id")
        if row_id in by_row_id:
            raise ValueError(f"duplicate wave membership row_id: {row_id}")
        by_row_id[row_id] = record
    return by_row_id


def normalize_wave_membership_registry() -> None:
    document = json.loads(read_text(WAVE_MEMBERSHIP_PATH))
    if document.get("schema_version") != WAVE_MEMBERSHIP_SCHEMA:
        raise ValueError(
            f"unexpected wave membership schema: {document.get('schema_version')}"
        )
    document["status"] = STATUS
    for record in document.get("memberships", []):
        record["action_tags"] = [
            (
                ENGINE_EVIDENCE_ACTION_TAG
                if tag == "VERIFY_FIXED_ENGINE_AND_LICENSE_INPUTS"
                else tag
            )
            for tag in record.get("action_tags", [])
        ]
    write_json(WAVE_MEMBERSHIP_PATH, document)


def compose_operation(record: dict[str, Any]) -> str:
    actor = record["actor"]
    entry = record["entry_surface"]
    verb = record["action_verb"]
    action_object = record["action_object"]
    expected = record["expected_response"]
    mode = record["operation_mode"]
    if mode == "USER_ACTION":
        return f"{actor}在{entry}，{verb}“{action_object}”；应观察到：{expected}"
    if mode == "ENGINEERING_CHECK":
        return f"{actor}在{entry}审查并{verb}“{action_object}”；交付证据应显示：{expected}"
    if mode == "NEGATIVE_SCOPE":
        return f"{actor}在{entry}确认“{action_object}”保持关闭，并{verb}；应观察到：{expected}"
    if mode == "STATE_AUDIT":
        return f"{actor}从{entry}触发并{verb}“{action_object}”；应观察到：{expected}"
    raise ValueError(f"unknown operation mode: {mode}")


def source_clauses(text: str) -> list[str]:
    clauses = [
        clean_md(part)
        for part in re.split(r"[；。]", text)
        if clean_md(part)
    ]
    return clauses or [clean_md(text)]


ACTION_METADATA_PATTERNS = (
    re.compile(r"\bV2-D\d{3}\b"),
    re.compile(r"(?<![A-Za-z0-9])D\d{3}(?![A-Za-z0-9])"),
    re.compile(r"\b\d{3,5}\s*[–—-]\s*\d{3,5}\b"),
    re.compile(r"(?<![\d.])(?:1[0-2]\d{2})(?![\d.]|p\b|[×x]\b|dp\b|ms\b)"),
)


def clean_action_text(text: str) -> str:
    cleaned = text
    for pattern in ACTION_METADATA_PATTERNS:
        cleaned = pattern.sub("", cleaned)
    cleaned = re.sub(r"(?:权威源片段|原子子项)[：:][\s；，,、]*", "", cleaned)
    cleaned = re.sub(r"([；，,、])\s*\1+", r"\1", cleaned)
    cleaned = re.sub(r"[；，,、]\s*(?=[。.”])", "", cleaned)
    cleaned = re.sub(r"\s{2,}", " ", cleaned)
    return cleaned.strip(" ；，,、")


def has_substantive_action_text(text: str) -> bool:
    cleaned = clean_action_text(text)
    return len(re.sub(r"[\s；，,、。.]", "", cleaned)) >= 8


def has_action_metadata(text: str) -> bool:
    return any(pattern.search(text) for pattern in ACTION_METADATA_PATTERNS)


def source_excerpt(text: str) -> str:
    substantive = [
        clause for clause in source_clauses(text) if has_substantive_action_text(clause)
    ]
    metadata_free = [
        clause for clause in substantive if not has_action_metadata(clause)
    ]
    if metadata_free:
        return metadata_free[0]
    if substantive:
        return substantive[0]
    return source_clauses(text)[0]


ENTRY_RULES: dict[str, list[tuple[tuple[str, ...], str]]] = {
    "ARCH": [
        (("原始材料", "任务卡", "专业身份", "来源"), "需求任务卡与来源引用审查入口"),
        (("决策", "问题", "确认"), "问题、决策与确认记录"),
        (("证据", "验证", "维护", "发布"), "阶段交付证据审查"),
        (("迁移", "架构", "契约", "owner"), "架构契约与迁移门"),
        (("测试", "门禁"), "自动化验收门"),
    ],
    "UX": [
        (("token", "主题", "颜色", "字体", "间距", "组件", "动效"), "页面骨架、主题与组件评审入口"),
        (("rwd", "窗口", "折叠", "重排"), "窗口、RWD 与可访问性审查入口"),
        (("导航", "入口", "骨架"), "全局页面骨架与导航评审入口"),
    ],
    "READER": [
        (("搜索",), "书架 → 打开书籍 → 阅读器搜索"),
        (("书签",), "阅读器书签面板 / 进度轴"),
        (("批注", "备注", "高亮"), "阅读器批注层与批注列表"),
        (("tts", "朗读", "语音"), "阅读器 TTS 控制条"),
        (("pdf",), "书架 → PDF 阅读器"),
        (("cbz",), "书架 → CBZ 阅读器"),
        (("epub",), "书架 → EPUB 阅读器"),
        (("txt",), "书架 → TXT 阅读器"),
        (("位置", "进度", "恢复", "字号", "行距", "排版", "翻页"), "阅读器正文与阅读设置"),
        (("导入", "来源", "身份", "删除", "下载", "离线"), "书架 / 书籍操作菜单"),
    ],
    "WRITE": [
        (("歌单",), "歌单详情与服务器歌单写入口"),
        (("收藏", "已看"), "媒体详情 / 播放结束的收藏与已看入口"),
        (("进度",), "播放器 / 阅读器进度写入口"),
        (("管理", "删除", "权限", "会话"), "需求范围审查入口（管理写为负向范围）"),
    ],
    "MUSIC": [
        (("下载", "离线"), "音乐曲目菜单 / 下载入口"),
        (("歌词",), "Now Playing 歌词面板"),
        (("音效", "eq", "replaygain", "淡入", "淡出"), "音乐音效设置 / Now Playing"),
        (("通知", "mediasession", "蓝牙", "耳机"), "系统媒体通知 / 蓝牙媒体控制"),
        (("质量", "转码", "编解码"), "音乐 PlaybackInfo 与质量设置入口"),
        (("格式", "ape", "dsd"), "音乐曲目打开入口"),
        (("队列", "随机", "下一首", "恢复", "错误"), "音乐队列 / Mini Player / Now Playing"),
        (("播放",), "音乐页 / Mini Player / Now Playing"),
    ],
    "DOWNLOAD": [
        (("版本", "mediasource", "来源"), "详情版本选择 / 下载任务来源"),
        (("通知",), "系统下载通知 / 下载中心"),
        (("存储", "权限", "wifi", "网络"), "下载预检 / 系统权限页"),
        (("删除", "取消", "暂停", "恢复", "重试", "失败", "进度"), "下载中心任务行与系统通知"),
        (("下载", "任务", "完成"), "详情下载按钮 / 下载中心 / 完成通知"),
    ],
    "VIDEO": [
        (("字幕",), "播放器字幕菜单 / 字幕设置"),
        (("音轨",), "播放器音轨菜单"),
        (("pip", "后台"), "播放器系统入口（PiP / 后台）"),
        (("手势", "亮度", "音量", "双击", "长按", "缩放"), "视频播放画面与手势层"),
        (("章节", "片头", "片尾", "进度"), "播放器控制层 / 章节条"),
        (("格式", "解码", "转码", "hdr", "分辨率", "4k", "8k"), "视频详情质量选项 / 播放诊断"),
        (("播放", "引擎", "错误", "切集", "恢复"), "详情 → 视频播放器"),
    ],
    "NAV": [
        (("账户", "身份", "服务器", "离线"), "账户与身份选择"),
        (("导航", "入口", "根", "滚动"), "一级导航 / 返回栈"),
        (("返回", "详情", "播放器", "沉浸"), "系统返回栈 / 播放器层"),
    ],
}

D204_SEMANTIC_PROFILES: dict[str, dict[str, str]] = {
    READER_060_PROMISE_ID: {
        "entry_surface": "阅读器 TTS 控制条 / 锁屏 / 系统媒体控制",
        "action_verb": "从当前位置开始并控制本地朗读",
        "action_object_prefix": "目标 Android 完全离线本地 TTS、voice-pack 与后台锁屏控制",
        "action_object_exact": "EPUB/TXT 从当前位置或当前章节起始使用可在目标 Android 完全离线的本地 TTS；控制播放 / 暂停、上一句 / 下一句、语速、音色与停止，支持收起、后台 / 锁屏与单活动 MediaSession；该决定仅确定兼容回退 + 高表现力第二开源引擎 gated Android arm64 Spike，Sherpa / Matcha 当前仅兼容回退，第二 engine 与 exact profile 未选",
        "operation_mode": "USER_ACTION",
        "actor": "用户",
    },
}


D203_SEMANTIC_PROFILES: dict[str, dict[str, str]] = {
    "S6-P-READER-087": {
        "entry_surface": "阅读器 TTS 控制条 / 锁屏 / 系统媒体控制",
        "action_verb": "核对后台锁屏 TTS 生命周期",
        "action_object_prefix": "后台 / 锁屏 TTS 生命周期、通知与 MediaSession 真值",
        "operation_mode": "STATE_AUDIT",
        "actor": "用户",
    },
    "S6-P-READER-088": {
        "entry_surface": "阅读器 TTS 音色列表 / 导入 / 逐书覆盖",
        "action_verb": "导入、选择并配置音色",
        "action_object_prefix": "voice-pack 追加导入、幂等身份与逐书覆盖",
        "operation_mode": "USER_ACTION",
        "actor": "用户",
    },
    "S6-P-READER-089": {
        "entry_surface": "阅读器 TTS 音色列表 / 删除 / 恢复",
        "action_verb": "删除或恢复音色并核对回退",
        "action_object_prefix": "voice-pack 删除、回退、恢复与引用清理",
        "operation_mode": "USER_ACTION",
        "actor": "用户",
    },
    "S6-P-READER-090": {
        "entry_surface": "阅读器 TTS / 音乐 / 系统媒体控制",
        "action_verb": "控制音频 owner 并核对系统控制",
        "action_object_prefix": "单音频 owner、音乐互斥与系统媒体控制真值",
        "operation_mode": "STATE_AUDIT",
        "actor": "用户",
    },
}


def entry_surface_for(
    domain: str,
    text: str,
    seed_entry: str,
    exact_entry: str | None = None,
) -> tuple[str, str]:
    if exact_entry:
        return exact_entry, "SOURCE_GROUNDED"
    lowered = text.lower()
    for keywords, surface in ENTRY_RULES.get(domain, []):
        if any(keyword in lowered for keyword in keywords):
            return surface, "SOURCE_GROUNDED"
    stripped = re.sub(r"；?\s*V2-REQ-[A-Z]+-\d{3}", "", seed_entry).strip()
    stripped = re.sub(r"（\s*）|\(\s*\)", "", stripped).strip()
    if stripped:
        return stripped, "DOMAIN_SURFACE_ONLY"
    return UNSPECIFIED, "UNSPECIFIED_IN_SOURCE"


def operation_profile(
    domain: str,
    level: str,
    text: str,
    exact_profile: dict[str, str] | None = None,
) -> tuple[str, str, str]:
    if exact_profile:
        return (
            exact_profile["operation_mode"],
            exact_profile["actor"],
            exact_profile["action_verb"],
        )
    lowered = text.lower()
    if level in {"OUT", "LATER"}:
        return "NEGATIVE_SCOPE", "范围验收负责人", "核对关闭边界"
    engineering_tokens = (
        "只借鉴",
        "不复制",
        "openapi",
        "schema",
        "架构",
        "契约",
        "迁移",
        "owner",
        "许可",
    )
    if (
        domain == "ARCH"
        or level == "PROCESS"
        or any(token in lowered for token in engineering_tokens)
    ):
        return "ENGINEERING_CHECK", "交付负责人", "核对证据与契约"
    if domain == "UX":
        return (
            "STATE_AUDIT",
            "设计系统与可访问性负责人",
            "执行视觉、状态与无障碍走查",
        )
    if domain == "DOWNLOAD":
        return "USER_ACTION", "用户", "创建、控制并观察"
    if domain == "MUSIC":
        if any(token in lowered for token in ("质量", "音效", "replaygain", "设置")):
            return "USER_ACTION", "用户", "选择并验证"
        if "歌词" in lowered:
            return "USER_ACTION", "用户", "打开并控制显示"
        return "USER_ACTION", "用户", "打开并控制播放"
    if domain == "VIDEO":
        if any(token in lowered for token in ("字幕", "音轨")):
            return "USER_ACTION", "用户", "选择并切换轨道"
        if any(token in lowered for token in ("格式", "解码", "转码", "hdr", "分辨率", "4k", "8k")):
            return "USER_ACTION", "用户", "选择并验证"
        return "USER_ACTION", "用户", "打开并控制播放"
    if domain == "WRITE":
        if "歌单" in lowered:
            return "USER_ACTION", "用户", "创建或编辑并回读"
        return "USER_ACTION", "用户", "提交并回读"
    if domain == "NAV":
        return "USER_ACTION", "用户", "切换入口并返回"

    verb_rules = (
        (("下载", "任务"), "创建、控制并观察"),
        (("搜索",), "输入查询并打开结果"),
        (("保存",), "保存并回读"),
        (("导入", "导出"), "导入或导出并核对"),
        (("写", "收藏", "已看", "歌单", "进度"), "提交并回读"),
        (("选择", "设置", "质量", "主题", "音效", "字号", "行距"), "选择并验证"),
        (("字幕", "音轨"), "选择并切换轨道"),
        (("导航", "入口", "返回", "根"), "切换入口并返回"),
        (("打开", "阅读"), "打开并阅读"),
        (("播放", "队列", "下一首", "章节"), "打开并控制播放"),
        (("失败", "错误", "取消", "重试", "恢复"), "触发并恢复"),
        (("批注", "书签"), "创建并回读"),
    )
    for keywords, verb in verb_rules:
        if any(keyword in lowered for keyword in keywords):
            return "USER_ACTION", "用户", verb
    return "USER_ACTION", "用户", "执行用户路径并核对"


STATE_TOKENS = (
    "状态",
    "失败",
    "错误",
    "离线",
    "在线",
    "加载",
    "空",
    "禁用",
    "取消",
    "恢复",
    "重试",
    "权限",
    "冲突",
    "完成",
    "进行中",
    "当前",
    "选中",
    "暂停",
    "播放",
    "下载",
    "登录",
    "身份",
    "同步",
    "保留",
)
A11Y_TOKENS = (
    "无障碍",
    "talkback",
    "可访问",
    "48",
    "焦点",
    "键盘",
    "名称",
    "角色",
    "reduce motion",
    "字号",
)
RWD_TOKENS = (
    "rwd",
    "窗口",
    "折叠",
    "横屏",
    "竖屏",
    "平板",
    "compact",
    "medium",
    "expanded",
    "large",
    "extralarge",
    "分屏",
    "旋转",
    "重排",
)
SEMANTIC_FIELD_OVERRIDES = {
    "S6-P-MUSIC-003": {
        "entry_surface": "本地曲库首次扫描 / 元数据提取",
        "action_verb": "触发扫描并提取",
        "action_object_prefix": "本地曲库元数据",
        "operation_mode": "USER_ACTION",
        "actor": "用户",
    },
    "S6-P-MUSIC-014": {
        "entry_surface": "音乐队列 / Next Up / 播放上下文",
        "action_verb": "编排队列",
        "operation_mode": "USER_ACTION",
        "actor": "用户",
    },
    "S6-P-NAV-004": {
        "entry_surface": "账户切换后的新身份投影",
        "action_verb": "切换身份并验证",
        "operation_mode": "USER_ACTION",
        "actor": "用户",
    },
    "S6-P-NAV-005": {
        "entry_surface": "账户页 / 添加账户",
        "action_verb": "选择账户并确认",
        "operation_mode": "USER_ACTION",
        "actor": "用户",
    },
    "S6-P-NAV-006": {
        "entry_surface": "离线账户选择 / 切换到在线",
        "action_verb": "切换离线身份并核验",
        "operation_mode": "USER_ACTION",
        "actor": "用户",
    },
}


def explicit_dimension(text: str, tokens: tuple[str, ...]) -> list[str]:
    matches = [
        clause
        for clause in source_clauses(text)
        if any(token in clause.lower() for token in tokens)
    ]
    return list(dict.fromkeys(matches))[:2] or [UNSPECIFIED]


def enrich_semantic_record(
    seed: dict[str, Any],
    row: dict[str, Any],
    atomic_promise: str,
    accepted_promise: str,
) -> dict[str, Any]:
    combined = f"{row['user_promise']}；{row['observable_result']}"
    profile = D204_SEMANTIC_PROFILES.get(
        row["promise_id"]
    ) or D203_SEMANTIC_PROFILES.get(row["promise_id"])
    entry_surface, entry_status = entry_surface_for(
        row["domain"],
        combined,
        str(seed.get("entry", "")),
        exact_entry=profile.get("entry_surface") if profile else None,
    )
    mode, actor, action_verb = operation_profile(
        row["domain"],
        row["level"],
        f"{atomic_promise}；{row['observable_result']}",
        exact_profile=profile,
    )
    action_excerpt = source_excerpt(accepted_promise)
    user_action_text = clean_action_text(row["user_promise"])
    atomic_action_text = clean_action_text(atomic_promise)
    action_parts = [user_action_text] if user_action_text else []
    excerpt_key = re.sub(r"\s+", "", action_excerpt)
    joined_action = re.sub(r"\s+", "", "；".join(action_parts))
    if excerpt_key not in joined_action:
        action_parts.append(f"权威源片段：{action_excerpt}")
    if atomic_action_text and re.sub(
        r"\s+", "", atomic_action_text
    ) not in re.sub(r"\s+", "", "；".join(action_parts)):
        action_parts.append(f"原子子项：{atomic_action_text}")
    action_object = "；".join(dict.fromkeys(action_parts))
    override = profile or SEMANTIC_FIELD_OVERRIDES.get(row["promise_id"], {})
    if exact_action_object := override.get("action_object_exact"):
        action_object = exact_action_object
    elif prefix := override.get("action_object_prefix"):
        action_object = f"{prefix}：{action_object}"
    entry_surface = override.get("entry_surface", entry_surface)
    entry_status = "SOURCE_GROUNDED" if override else entry_status
    mode = override.get("operation_mode", mode)
    actor = override.get("actor", actor)
    action_verb = override.get("action_verb", action_verb)
    states = explicit_dimension(combined, STATE_TOKENS)
    accessibility = explicit_dimension(combined, A11Y_TOKENS)
    rwd = explicit_dimension(combined, RWD_TOKENS)
    missing_dimensions = [
        label
        for label, values in (
            ("状态", states),
            ("无障碍", accessibility),
            ("RWD", rwd),
        )
        if values == [UNSPECIFIED]
    ]
    missing_reason = (
        "权威源未明确枚举以下维度：" + "、".join(missing_dimensions) + "；"
        if missing_dimensions
        else "权威源仅给出已列出的子集；"
    ) + "G2 必须保留未枚举分支 gap，不得由静态规划代签。"
    record = {
        **seed,
        "promise_id": row["promise_id"],
        "requirement_id": row["parent_source_id"],
        "actor": actor,
        "entry_surface": entry_surface,
        "entry": entry_surface,
        "entry_status": entry_status,
        "action_verb": action_verb,
        "action_object": action_object,
        "expected_response": row["observable_result"],
        "operation_mode": mode,
        "action_source_excerpt": action_excerpt,
        "operation_source_excerpt": action_excerpt,
        "operation": "",
        "atomic_promise": atomic_promise,
        "semantic_basis": "EXPLICIT_ROW_RECORD",
        "missing_reason": missing_reason,
        "states_accessibility_rwd": {
            "record_id": row["promise_id"],
            "basis": f"EXPLICIT_ROW_RECORD:{row['promise_id']}",
            "semantic_source_id": row["parent_source_id"],
            "applicability": (
                "PROCESS_OR_ARCHITECTURE_GATE"
                if row["domain"] == "ARCH" or row["level"] == "PROCESS"
                else "USER_VISIBLE_SOURCE_CONTRACT"
            ),
            "states": states,
            "accessibility": accessibility,
            "rwd": rwd,
            "coverage_status": "SOURCE_EXPLICIT_OR_GAP_MARKED; RUNTIME_UNVERIFIED",
            "missing_reason": missing_reason,
        },
    }
    record["operation"] = compose_operation(record)
    return record


def enrich_semantics(
    rows: list[dict[str, Any]],
    seed_by_id: dict[str, dict[str, Any]],
) -> dict[str, Any]:
    enriched = []
    for row in rows:
        seed = seed_by_id[row["promise_id"]]
        record = enrich_semantic_record(
            seed,
            row,
            row["_atomic_promise"],
            row["_accepted_promise"],
        )
        enriched.append(record)
        row["entry"] = record["entry_surface"]
        row["operation"] = record["operation"]
        row["states_accessibility_rwd"] = record["states_accessibility_rwd"]
        row["semantic_context"] = {
            "record_id": row["promise_id"],
            "basis": "EXPLICIT_ROW_RECORD",
            "entry_status": record["entry_status"],
            "source_anchor": record["semantic_source_anchor"],
            **{
                field: record[field]
                for field in (
                    "actor",
                    "entry_surface",
                    "action_verb",
                    "action_object",
                    "expected_response",
                    "operation_mode",
                    "action_source_excerpt",
                )
            },
        }
        row["uncovered"] = (
            f"{row['uncovered']} {record['missing_reason']}".strip()
        )
        del row["_atomic_promise"]
        del row["_accepted_promise"]
    return {
        "schema_version": SEMANTIC_SCHEMA,
        "status": STATUS,
        "generated_on": "2026-11-04",
        "rules": {
            "join_key": "promise_id",
            "operation": (
                "Operation is rendered from actor + entry_surface + action_verb + "
                "action_object + expected_response; IDs do not replace user action."
            ),
            "grounding": (
                "action_source_excerpt must occur in action_object and the accepted "
                "V2_PLAN source line."
            ),
            "unspecified": (
                "Unspecified state/accessibility/RWD dimensions use "
                "UNSPECIFIED_IN_SOURCE and retain an explicit gap."
            ),
        },
        "rows": enriched,
    }


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest().upper()


def clean_md(value: str) -> str:
    value = value.strip()
    if len(value) >= 2 and value[0] == "`" and value[-1] == "`":
        value = value[1:-1]
    value = value.replace("`", "")
    value = value.replace("**", "")
    return value.strip()


def split_row(line: str) -> list[str]:
    if not line.startswith("|") or not line.endswith("|"):
        raise ValueError(f"not a markdown table row: {line[:80]}")
    return [clean_md(part) for part in line.strip().strip("|").split("|")]


def parse_table(lines: list[str], spec: dict[str, Any]) -> tuple[list[str], list[dict[str, Any]]]:
    start = int(spec["start"])
    end = int(spec["end"])
    header = split_row(lines[start - 1])
    expected = int(len(spec["columns"]))
    if len(header) != expected:
        raise ValueError(f"header column mismatch at line {start}: {header}")
    rows: list[dict[str, Any]] = []
    for line_no in range(start + 2, end + 1):
        raw = lines[line_no - 1]
        if not raw.startswith("|"):
            raise ValueError(f"expected table row at line {line_no}: {raw[:80]}")
        cells = split_row(raw)
        if len(cells) != expected:
            raise ValueError(f"row column mismatch at line {line_no}: {len(cells)} != {expected}")
        row = dict(zip(spec["columns"], cells))
        row["_line"] = line_no
        rows.append(row)
    return header, rows


def claim_is_negated(text: str, start: int, end: int) -> bool:
    prefix = text[:start]
    clause_start = max(
        prefix.rfind("。"),
        prefix.rfind("；"),
        prefix.rfind(";"),
        prefix.rfind("\n"),
        start - 48,
        0,
    ) + 1
    context = text[clause_start : min(len(text), end + 16)]
    return NEGATED_CLAIM_CONTEXT.search(context) is not None


def has_stale_reader_060_engine_claim(text: object) -> bool:
    value = str(text)
    return any(
        not claim_is_negated(value, match.start(), match.end())
        for match in STALE_READER_060_MAIN_ENGINE.finditer(value)
    )


def load_reader_060_row(v2_lines: list[str]) -> dict[str, Any]:
    _, reader_rows = parse_table(v2_lines, TABLE_SPECS["READER"])
    matches = [row for row in reader_rows if row.get("id") == READER_060_SOURCE_ID]
    if len(matches) != 1:
        raise ValueError(
            f"{READER_060_SOURCE_ID} must appear exactly once in the READER table"
        )
    return matches[0]


def validate_reader_060_row(row: dict[str, Any]) -> None:
    if "MUST" not in levels_from_raw(row["level"]):
        raise ValueError(f"{READER_060_SOURCE_ID} must remain MUST")
    if not row["source"].startswith("468"):
        raise ValueError(f"{READER_060_SOURCE_ID} external source must remain 468")
    missing_dependencies = [
        dependency
        for dependency in READER_060_REQUIRED_DEPENDENCIES
        if dependency not in row["dependencies"]
    ]
    if missing_dependencies:
        raise ValueError(
            f"{READER_060_SOURCE_ID} dependencies missing {missing_dependencies}"
        )
    if has_stale_reader_060_engine_claim(row["promise"]):
        raise ValueError(
            f"{READER_060_SOURCE_ID} must not mandate Sherpa-ONNX as the TTS engine"
        )
    required_promise_tokens = (
        "可在目标 Android 完全离线的本地 TTS",
        "V2-D204",
        "兼容回退",
        "第二开源引擎",
        "gated Android arm64 Spike",
        "Sherpa / Matcha 当前仅兼容回退",
        "exact profile",
        "未选",
    )
    missing_promise_tokens = [
        token for token in required_promise_tokens if token not in row["promise"]
    ]
    if missing_promise_tokens:
        raise ValueError(
            f"{READER_060_SOURCE_ID} product contract missing "
            f"{missing_promise_tokens}"
        )


def build_d204_reader_060_semantic_seed(
    v2_lines: list[str],
    existing_seed: dict[str, Any],
) -> dict[str, Any]:
    row = load_reader_060_row(v2_lines)
    validate_reader_060_row(row)
    profile = D204_SEMANTIC_PROFILES[READER_060_PROMISE_ID]
    return {
        **existing_seed,
        "promise_id": READER_060_PROMISE_ID,
        "requirement_id": READER_060_SOURCE_ID,
        "user_promise": row["promise"],
        "entry": profile["entry_surface"],
        "dependencies": row["dependencies"],
    }


def validate_reader_060_engine_neutral_outputs(
    semantic_document: dict[str, Any],
    matrix: dict[str, Any],
    cards: list[dict[str, Any]],
) -> None:
    record = next(
        item
        for item in semantic_document["rows"]
        if item["promise_id"] == READER_060_PROMISE_ID
    )
    matrix_row = next(
        item
        for item in matrix["acceptance_rows"]
        if item["promise_id"] == READER_060_PROMISE_ID
    )
    tts_card = next(item for item in cards if item["id"] == "G2-READER-TTS-GATED")
    w1 = next(
        item
        for item in tts_card["delivery_waves"]
        if item["id"] == "G2-READER-TTS-GATED-W1"
    )
    outputs = {
        "READER-060 semantic user_promise": record.get("user_promise", ""),
        "READER-060 semantic operation": record.get("operation", ""),
        "READER-060 matrix user_promise": matrix_row.get("user_promise", ""),
        "READER-060 matrix operation": matrix_row.get("operation", ""),
        "G2-READER-TTS-GATED-W1 objective": w1.get("objective", ""),
        "G2-READER-TTS-GATED-W1 operation": w1.get("operation", ""),
    }
    stale = [
        label
        for label, text in outputs.items()
        if has_stale_reader_060_engine_claim(text)
    ]
    if stale:
        raise ValueError(
            f"READER-060 generated outputs retain a Sherpa main-engine claim: {stale}"
        )


def build_d203_semantic_seeds(v2_lines: list[str]) -> dict[str, dict[str, Any]]:
    _, reader_rows = parse_table(v2_lines, TABLE_SPECS["READER"])
    by_source_id = {
        row["id"]: row
        for row in reader_rows
        if re.fullmatch(r"V2-REQ-[A-Z]+-\d{3}", row["id"])
    }
    missing = [source_id for source_id in READER_ADDENDUM_IDS if source_id not in by_source_id]
    if missing:
        raise ValueError(f"D203 addendum rows missing: {missing}")

    seeds: dict[str, dict[str, Any]] = {}
    for source_id in READER_ADDENDUM_IDS:
        row = by_source_id[source_id]
        if "MUST" not in levels_from_raw(row["level"]):
            raise ValueError(f"{source_id} must remain MUST")
        if "D203" not in row["source"] or "G0 addendum" not in row["source"]:
            raise ValueError(f"{source_id} lacks D203 project-addendum provenance")
        promise_id = "S6-P-" + source_id.removeprefix("V2-REQ-")
        profile = D203_SEMANTIC_PROFILES[promise_id]
        seed = {
            "promise_id": promise_id,
            "requirement_id": source_id,
            "source_origin": PROJECT_G0_ADDENDUM,
            "counted_in_external_445": False,
            "counted_in_external_376": False,
            "user_promise": row["promise"],
            "entry": profile["entry_surface"],
            "semantic_source_anchor": {
                "path": "docs/V2_PLAN.md",
                "section": TABLE_SPECS["READER"]["section"],
                "line": row["_line"],
                "requirement_id": source_id,
                "excerpt": row["result"],
            },
        }
        minimal_row = {
            "promise_id": promise_id,
            "parent_source_id": source_id,
            "domain": "READER",
            "level": "MUST",
            "user_promise": row["promise"],
            "observable_result": row["result"],
        }
        seeds[promise_id] = enrich_semantic_record(
            seed,
            minimal_row,
            row["promise"],
            row["promise"],
        )
    return seeds


def rebase_semantic_anchor_lines(
    v2_lines: list[str],
    semantics_by_id: dict[str, dict[str, Any]],
) -> None:
    for spec in TABLE_SPECS.values():
        _, table_rows = parse_table(v2_lines, spec)
        for row in table_rows:
            if not re.fullmatch(r"V2-REQ-[A-Z]+-\d{3}", row["id"]):
                continue
            matching_records = [
                record
                for record in semantics_by_id.values()
                if record.get("requirement_id") == row["id"]
            ]
            if not matching_records:
                continue
            for record in matching_records:
                anchor = record.setdefault("semantic_source_anchor", {})
                anchor["path"] = "docs/V2_PLAN.md"
                anchor["section"] = spec["section"]
                anchor["line"] = row["_line"]
                anchor["requirement_id"] = row["id"]
                anchor["excerpt"] = row["result"]


def levels_from_raw(raw: str) -> list[str]:
    found = re.findall(r"\b(?:MUST|SHOULD|LATER|OUT)\b", raw)
    result: list[str] = []
    for item in found:
        if item not in result:
            result.append(item)
    return result


def is_process(raw: str) -> bool:
    return "流程" in raw and not levels_from_raw(raw)


def node_cards(domain: str, source_id: str, child_suffix: str | None = None) -> list[str]:
    if domain == "ARCH":
        return ["G2-ARCH-CONTRACTS-MIGRATION"]
    if domain == "UX":
        return ["G2-UI-R1-FOUNDATION"]
    if domain == "WRITE":
        return ["G2-SERVER-WRITE"]
    if domain == "DOWNLOAD":
        return ["G2-DOWNLOAD-STATE"]
    if domain == "NAV":
        return ["G2-NAV-IDENTITY", "G2-UI-R1-FOUNDATION"]
    if domain == "READER":
        if source_id in {
            "V2-REQ-READER-060",
            "V2-REQ-READER-061",
            "V2-REQ-READER-062",
            "V2-REQ-READER-063",
            *READER_ADDENDUM_IDS,
        }:
            return ["G2-READER-TTS-GATED", "G2-Q03-SUPPLY-CHAIN-GATED"]
        if source_id == "V2-REQ-READER-081":
            return ["G2-READER-PDF-GATED", "G2-Q03-SUPPLY-CHAIN-GATED"]
        if source_id == "V2-REQ-READER-057":
            if child_suffix == "PDF-SEARCH":
                return ["G2-READER-PDF-GATED", "G2-Q03-SUPPLY-CHAIN-GATED"]
            return ["G2-READER-CORE"]
        return ["G2-READER-CORE"]
    if domain == "MUSIC":
        if source_id == "V2-REQ-MUSIC-051" and child_suffix == "DOWNLOAD-OFFLINE-MENU":
            return ["G2-MUSIC-CORE", "G2-DOWNLOAD-STATE"]
        if source_id == "V2-REQ-MUSIC-051":
            return ["G2-MUSIC-CORE", "G2-LOCAL-PLAYLIST"]
        if source_id in {"V2-REQ-MUSIC-014", "V2-REQ-MUSIC-016"}:
            return ["G2-MUSIC-CORE"]
        return ["G2-MUSIC-CORE"]
    if domain == "VIDEO":
        if source_id in {
            "V2-REQ-VIDEO-080",
            "V2-REQ-VIDEO-081",
            "V2-REQ-VIDEO-082",
            "V2-REQ-VIDEO-083",
            "V2-REQ-VIDEO-084",
            "V2-REQ-VIDEO-085",
            "V2-REQ-VIDEO-086",
            "V2-REQ-VIDEO-087",
            "V2-REQ-VIDEO-088",
            "V2-REQ-VIDEO-089",
            "V2-REQ-VIDEO-090",
            "V2-REQ-VIDEO-091",
            "V2-REQ-VIDEO-092",
            "V2-REQ-VIDEO-093",
            "V2-REQ-VIDEO-094",
            "V2-REQ-VIDEO-095",
            "V2-REQ-VIDEO-096",
        }:
            return ["G2-SYNCPLAY-GATED"]
        return ["G2-VIDEO-CORE"]
    raise ValueError(f"unknown domain: {domain}")


def evidence_for_row(domain: str, source_id: str, level: str) -> list[str]:
    ids: list[str] = []
    if domain == "ARCH":
        ids += ["EV-S1", "EV-S2"]
    elif domain == "UX":
        ids += ["EV-S5-R1", "EV-UI-R1"]
    elif domain == "READER":
        ids += ["EV-S2", "EV-S3"]
        if source_id in {
            "V2-REQ-READER-060",
            "V2-REQ-READER-061",
            "V2-REQ-READER-062",
            "V2-REQ-READER-063",
            *READER_ADDENDUM_IDS,
        }:
            ids += ["EV-Q03-NATIVE", "EV-Q03-TTS-PDF", "EV-S4C"]
        if source_id in {"V2-REQ-READER-057", "V2-REQ-READER-081"}:
            ids += ["EV-Q03-NATIVE", "EV-Q03-TTS-PDF", "EV-S4C"]
    elif domain == "WRITE":
        ids += ["EV-S3"]
    elif domain == "MUSIC":
        ids += ["EV-S3", "EV-S2"]
    elif domain == "DOWNLOAD":
        ids += ["EV-A1", "EV-S2", "EV-S3"]
    elif domain == "VIDEO":
        ids += ["EV-S3", "EV-S4B", "EV-Q03-NATIVE"]
    elif domain == "NAV":
        ids += ["EV-S3", "EV-S5-R1"]
    return list(dict.fromkeys(ids))


def owner_for(level: str, source_id: str, cards: list[str]) -> tuple[str, str]:
    if level == "PROCESS":
        return "PROCESS_GATE", "Process node is traceable but is not a product MVP item."
    if level not in {"MUST", "SHOULD"}:
        return "NON_MVP_TRACE_ONLY", "LATER/OUT item remains traceable and is not a 2.0 implementation commitment."
    if source_id in {
        "V2-REQ-READER-060",
        "V2-REQ-READER-061",
        "V2-REQ-READER-062",
        "V2-REQ-READER-063",
        *READER_ADDENDUM_IDS,
    }:
        return "BLOCKED_Q03_TTS_VOICE_RUNTIME", "Blocked by Q03 engine/voice/license/zero-network gates."
    if source_id in {"V2-REQ-READER-081"}:
        return "BLOCKED_Q03_PDFIUM_RUNTIME", "Blocked by Q03 PDFium 16 KB/provenance/Range gates."
    if source_id == "V2-REQ-READER-057":
        if cards:
            return (
                f"G2_CARD:{cards[0]}",
                "Each READER-057 child follows its own subitem card: PDF is gated; EPUB/TXT is READER-CORE.",
            )
    if cards:
        return f"G2_CARD:{cards[0]}", "Assigned to at least one independently verifiable G2 card."
    return "BLOCKED_OWNER_UNASSIGNED", "Reviewer must assign before G2."


def thresholds_for(source_id: str) -> list[str]:
    return sorted(
        threshold_id
        for entry in THRESHOLD_SPECS
        for threshold_id in [entry[0]]
        if source_id in entry[1]
    )


def threshold_refs(source_id: str) -> list[str]:
    return thresholds_for(source_id) or ["NONE_IN_ACCEPTED_SOURCE"]


def render_state_field(domain: str, level: str) -> dict[str, Any]:
    if domain == "ARCH" or level == "PROCESS":
        return {
            "applicability": "PROCESS_OR_ARCHITECTURE_GATE",
            "status": "USER_UI_STATE_NOT_APPLICABLE",
        }
    return {
        "applicability": "USER_VISIBLE",
        "states": ["default", "loading", "empty", "error", "disabled", "selected/current", "cancelled/offline where applicable"],
        "accessibility": ["accessible name/role/state", "48x48dp target", "not color-only", "focus visibility", "Reduce Motion"],
        "rwd": ["Compact", "Medium", "Expanded", "Large", "ExtraLarge", "Foldable/window-change state retention"],
        "coverage_status": "CONTRACT_ONLY_AT_S6; runtime and device UNVERIFIED",
        "r1_source_rows": ["R1-A11Y-TOUCH-48", "R1-A11Y-NAMES", "R1-A11Y-REDUCE-MOTION", "R1-RWD-COMPACT"],
    }


def e_levels(level: str, domain: str) -> dict[str, Any]:
    if level in {"LATER", "OUT"}:
        required = "NOT_REQUIRED_IN_2_0"
    elif domain == "ARCH":
        required = "E1_PLUS_RELEASE_GATE_COMMANDS"
    elif level == "PROCESS":
        required = "E7_WHEN_PROCESS_IS_INVOKED"
    else:
        required = "E7_CANDIDATE_PACKAGE_JOURNEY"
    return {
        "current_best": "E0_STATIC_INPUT_ONLY",
        "required_final": required,
        "runtime_executed": False,
        "device_executed": False,
        "package_executed": False,
        "cannot_upgrade_by": ["static fixture", "S5 static coverage", "historical 1.4", "Worker SUCCESS", "screenshot"],
    }


def build_external_nodes(
    v2_lines: list[str],
    semantics_by_id: dict[str, dict[str, Any]],
) -> tuple[list[dict[str, Any]], list[dict[str, Any]], dict[str, Any]]:
    nodes: list[dict[str, Any]] = []
    acceptance_rows: list[dict[str, Any]] = []
    domain_counts: dict[str, Any] = {}
    for domain, spec in TABLE_SPECS.items():
        _, table_rows = parse_table(v2_lines, spec)
        table_rows = [
            row for row in table_rows if re.fullmatch(r"V2-REQ-[A-Z]+-\d{3}", row["id"])
        ]
        if len(table_rows) != EXPECTED_DOMAIN_SOURCE_COUNTS[domain]:
            raise ValueError(f"{domain} source count {len(table_rows)}")
        domain_level_counter: Counter[str] = Counter()
        domain_mvp = 0
        domain_external_sources = 0
        domain_addendum_sources = 0
        domain_external_mvp = 0
        domain_addendum_mvp = 0
        for raw_row in table_rows:
            source_id = raw_row["id"]
            source_origin = (
                PROJECT_G0_ADDENDUM
                if source_id in READER_ADDENDUM_ID_SET
                else EXTERNAL_G0
            )
            if source_origin == PROJECT_G0_ADDENDUM:
                domain_addendum_sources += 1
            else:
                domain_external_sources += 1
            source_uri = raw_row.get("source", raw_row.get("promise", ""))
            level_raw = raw_row["level"]
            process = is_process(level_raw)
            raw_levels = [] if process else levels_from_raw(level_raw)
            compound_children = COMPOUND_CHILDREN.get(source_id)
            if compound_children:
                children = []
                for child in compound_children:
                    children.append(
                        {
                            **child,
                            "subitem_id": f"{source_id.removeprefix('V2-REQ-')}-{child['suffix']}",
                            "mvp": child["level"] == "MUST",
                        }
                    )
            elif process:
                children = [
                    {
                        "suffix": "PROCESS",
                        "subitem_id": source_id.removeprefix("V2-REQ-") + "-PROCESS",
                        "level": "PROCESS",
                        "mvp": False,
                        "promise": raw_row["promise"],
                        "result": raw_row["result"],
                    }
                ]
            else:
                if len(raw_levels) != 1:
                    raise ValueError(f"unexpected non-compound levels for {source_id}: {raw_levels}")
                children = [
                    {
                        "suffix": "SINGLE",
                        "subitem_id": source_id.removeprefix("V2-REQ-"),
                        "level": raw_levels[0],
                        "mvp": raw_levels[0] == "MUST",
                        "promise": raw_row["promise"],
                        "result": raw_row["result"],
                    }
                ]

            for child in children:
                domain_level_counter[child["level"]] += 1
                if child["mvp"]:
                    domain_mvp += 1
                    if source_origin == PROJECT_G0_ADDENDUM:
                        domain_addendum_mvp += 1
                    else:
                        domain_external_mvp += 1

            all_cards: list[str] = []
            for child in children:
                cards = node_cards(domain, source_id, child.get("suffix"))
                all_cards.extend(cards)
            all_cards = list(dict.fromkeys(all_cards))

            node = {
                "source_id": source_id,
                "domain": domain,
                "source_origin": source_origin,
                "counted_in_external_445": source_origin == EXTERNAL_G0,
                "source_location": {
                    "path": "docs/V2_PLAN.md",
                    "section": spec["section"],
                    "line": raw_row["_line"],
                    "source_refs": source_uri,
                },
                "level_raw": level_raw,
                "levels": [child["level"] for child in children],
                "is_compound": len(children) > 1 and not process,
                "is_process": process,
                "mvp_subitem_count": sum(1 for child in children if child["mvp"]),
                "subitems": [
                    {
                        "subitem_id": child["subitem_id"],
                        "level": child["level"],
                        "mvp": child["mvp"],
                    }
                    for child in children
                ],
                "g2_card_ids": all_cards,
            }
            nodes.append(node)

            for child in children:
                cards = node_cards(domain, source_id, child.get("suffix"))
                owner, owner_reason = owner_for(child["level"], source_id, cards)
                promise_id = "S6-P-" + child["subitem_id"].removeprefix("V2-REQ-").replace("V2-", "")
                semantic = semantics_by_id.get(promise_id)
                if semantic is None:
                    raise ValueError(f"no explicit semantic record for {promise_id}")
                if semantic.get("requirement_id") != source_id:
                    raise ValueError(
                        f"{promise_id} semantic requirement {semantic.get('requirement_id')} != {source_id}"
                    )
                anchor = semantic.get("semantic_source_anchor", {})
                if (
                    anchor.get("path") != "docs/V2_PLAN.md"
                    or anchor.get("line") != raw_row["_line"]
                ):
                    raise ValueError(
                        f"{promise_id} semantic anchor mismatch: "
                        f"{anchor.get('path')}:{anchor.get('line')} != docs/V2_PLAN.md:{raw_row['_line']}"
                    )
                entry = semantic["entry"]
                entry_status = semantic["entry_status"]
                operation = semantic["operation"]
                user_promise = semantic["user_promise"]
                state_context = semantic["states_accessibility_rwd"]
                deps = raw_row.get("dependencies", "")
                uncovered = raw_row.get("uncovered", "")
                if not uncovered:
                    uncovered = "Accepted source does not enumerate candidate-package recovery coverage; G2 must record it."
                if child["level"] in {"MUST", "SHOULD"}:
                    uncovered += " S6 did not execute runtime/device/candidate-package evidence."
                if entry == "UNSPECIFIED_IN_SOURCE":
                    uncovered += " Exact UI entry is UNSPECIFIED in the accepted source."
                state_missing_reason = state_context.get("missing_reason")
                if state_missing_reason:
                    uncovered += f" {state_missing_reason}"
                acceptance_rows.append(
                    {
                        "promise_id": promise_id,
                        "parent_source_id": source_id,
                        "subitem_id": child["subitem_id"],
                        "domain": domain,
                        "source_ids": [source_id],
                        "source_origin": source_origin,
                        "counted_in_external_445": source_origin == EXTERNAL_G0,
                        "counted_in_external_376": (
                            child["mvp"] and source_origin == EXTERNAL_G0
                        ),
                        "counted_in_normalized_mvp": child["mvp"],
                        "source_location": node["source_location"],
                        "_atomic_promise": child["promise"],
                        "_accepted_promise": raw_row["promise"],
                        "level": child["level"],
                        "mvp": child["mvp"],
                        "mvp_class": (
                            "PROJECT_G0_ADDENDUM_MVP"
                            if child["mvp"] and source_origin == PROJECT_G0_ADDENDUM
                            else "EXTERNAL_MVP"
                            if child["mvp"]
                            else "NON_MVP_TRACE_ONLY"
                            if child["level"] in {"LATER", "OUT"}
                            else "PROCESS"
                            if child["level"] == "PROCESS"
                            else "IN_SCOPE_NON_MVP"
                        ),
                        "user_promise": user_promise,
                        "entry": entry,
                        "operation": operation,
                        "observable_result": child["result"],
                        "semantic_context": {
                            "record_id": promise_id,
                            "basis": "EXPLICIT_ROW_RECORD",
                            "entry_status": entry_status,
                            "source_anchor": semantic["semantic_source_anchor"],
                        },
                        "states_accessibility_rwd": state_context,
                        "e0_e7_strength": e_levels(child["level"], domain),
                        "automation_fixture_evidence": {
                            "evidence_ids": evidence_for_row(domain, source_id, child["level"]),
                            "source_evidence_codes": raw_row.get("evidence", "NONE_IN_TABLE"),
                            "status": (
                                "STATIC_INPUT_ONLY"
                                if evidence_for_row(domain, source_id, child["level"])
                                else "UNCOVERED"
                            ),
                            "gap": (
                                ""
                                if evidence_for_row(domain, source_id, child["level"])
                                else "No concrete external fixture/ledger/evidence ID covers this row."
                            ),
                            "executed_by_s6": False,
                            "cannot_support": ["runtime PASS", "device PASS", "production protocol PASS"],
                        },
                        "candidate_device_evidence": {
                            "status": "NONE_AT_S6",
                            "reason": "S6 planning only; no candidate package, device, server write, runtime or QA execution.",
                        },
                        "threshold_ids": threshold_refs(source_id),
                        "uncovered": uncovered.strip(),
                        "dependencies": deps,
                        "g2_card_ids": cards,
                        "owner": owner,
                        "owner_reason": owner_reason,
                        "blocked_reason": (
                            "V2-Q-G1-03 remains Open."
                            if owner.startswith("BLOCKED_")
                            else ""
                        ),
                    }
                )

        domain_counts[domain] = {
            "source_nodes": len(table_rows),
            "external_source_nodes": domain_external_sources,
            "project_addendum_source_nodes": domain_addendum_sources,
            "level_subitems": dict(sorted(domain_level_counter.items())),
            "mvp_subitems": domain_mvp,
            "external_mvp_subitems": domain_external_mvp,
            "project_addendum_mvp_subitems": domain_addendum_mvp,
            "expected_mvp": EXPECTED_DOMAIN_MVP_COUNTS[domain],
        }
        if domain_mvp != EXPECTED_DOMAIN_MVP_COUNTS[domain]:
            raise ValueError(f"{domain} MVP count {domain_mvp} != {EXPECTED_DOMAIN_MVP_COUNTS[domain]}")
        if domain_external_sources != EXPECTED_EXTERNAL_DOMAIN_SOURCE_COUNTS[domain]:
            raise ValueError(
                f"{domain} external source count {domain_external_sources} != "
                f"{EXPECTED_EXTERNAL_DOMAIN_SOURCE_COUNTS[domain]}"
            )
        if domain_external_mvp != EXPECTED_EXTERNAL_DOMAIN_MVP_COUNTS[domain]:
            raise ValueError(
                f"{domain} external MVP count {domain_external_mvp} != "
                f"{EXPECTED_EXTERNAL_DOMAIN_MVP_COUNTS[domain]}"
            )
    row_ids = {row["promise_id"] for row in acceptance_rows}
    missing = sorted(row_ids - set(semantics_by_id))
    extra = sorted(set(semantics_by_id) - row_ids)
    if missing or extra:
        raise ValueError(
            f"row semantics coverage mismatch: missing={missing[:8]} extra={extra[:8]}"
        )
    return nodes, acceptance_rows, domain_counts


def parse_base_groups(v2_lines: list[str]) -> list[dict[str, Any]]:
    _, rows = parse_table(v2_lines, BASE_SPEC)
    if len(rows) != 5:
        raise ValueError(f"BASE count {len(rows)} != 5")
    cards = [1, 2, 3, 4, 5]
    mapping = {
        "V2-REQ-BASE-001": "G2-SEARCH",
        "V2-REQ-BASE-002": "G2-UPDATE-TRUST",
        "V2-REQ-BASE-003": "G2-PERSISTENT-SWR",
        "V2-REQ-BASE-004": "G2-LOCAL-PLAYLIST",
        "V2-REQ-BASE-005": "G2-MUSIC-CORE",
    }
    evidence_map = {
        "V2-REQ-BASE-001": ["EV-BASE001", "EV-S3"],
        "V2-REQ-BASE-002": ["EV-A2", "EV-S3"],
        "V2-REQ-BASE-003": ["EV-BASE003", "EV-S3"],
        "V2-REQ-BASE-004": ["EV-BASE004", "EV-S2"],
        "V2-REQ-BASE-005": ["EV-S3", "EV-BASE005"],
    }
    result = []
    for row in rows:
        m = re.search(r"V2-REQ-BASE-\d{3}", row["capability"])
        if not m:
            raise ValueError(f"BASE id missing: {row['capability']}")
        source_id = m.group(0)
        result.append(
            {
                "promise_id": f"S6-P-{source_id.removeprefix('V2-REQ-')}",
                "source_ids": [source_id],
                "capability": row["capability"],
                "source_location": {
                    "path": "docs/V2_PLAN.md",
                    "section": BASE_SPEC["section"],
                    "line": row["_line"],
                    "source_refs": row["source"],
                },
                "level": clean_md(row["level"]),
                "mvp_class": "BASE_MUST_GROUP",
                "counted_in_external_376": False,
                "user_promise": row["promise"],
                "entry": "既有能力真实入口；G2 卡必须先定位实际组件 / 路由后执行",
                "operation": "从真实用户入口执行该既有能力组，不以合同包或历史 1.4 记录替代",
                "observable_result": row["result"],
                "states_accessibility_rwd": render_state_field("BASE", "MUST"),
                "e0_e7_strength": e_levels("MUST", "BASE"),
                "automation_fixture_evidence": {
                    "evidence_ids": evidence_map[source_id],
                    "status": "STATIC_ONLY",
                    "executed_by_s6": False,
                },
                "candidate_device_evidence": {
                    "status": "NONE_AT_S6",
                    "reason": "BASE group awaits G2 implementation and candidate evidence.",
                },
                "threshold_ids": threshold_refs(source_id),
                "uncovered": row["uncovered"],
                "g2_card_ids": [mapping[source_id]],
                "owner": f"G2_CARD:{mapping[source_id]}",
                "blocked_reason": (
                    "Runtime / implementation gaps remain; static contract is not candidate evidence."
                ),
            }
        )
    return result


def build_r1_rows() -> list[dict[str, Any]]:
    themes = [
        ("R1-THEME-PRISM", "Prism", "平实表面；1.75dp 几何轮廓；12dp；180ms。", "5 主题设计中 Prism 的 token 与动作状态可按 R1 复现。", ["G2-UI-R1-FOUNDATION"]),
        ("R1-THEME-LUMEN", "Lumen", "冷青玻璃；内部高光；16dp；200ms；新装默认。", "5 主题设计中 Lumen 的材质、色板与动作状态可按 R1 复现。", ["G2-UI-R1-FOUNDATION"]),
        ("R1-THEME-NOCTURNE", "Nocturne", "暖金发丝线；靛影中性层；中文正常字面；8dp（仅 D201 窄覆盖 D057 的 12dp）；220ms。", "Nocturne 使用 8dp 基础圆角，其他 D057 内容不被改写。", ["G2-UI-R1-FOUNDATION"]),
        ("R1-THEME-MONOLITH", "Monolith", "实心表面；2dp 强轮廓；无辉光 / 模糊；8dp；150ms。", "5 主题设计中 Monolith 的实心结构与强轮廓可按 R1 复现。", ["G2-UI-R1-FOUNDATION"]),
        ("R1-THEME-DYNAMIC", "系统动态色", "继承 Prism 骨架，只替换颜色角色；12dp；Android 12 以下禁用并解释。", "系统色变化后重新计算对比度，且不叠加品牌主题。", ["G2-UI-R1-FOUNDATION"]),
    ]
    rows: list[dict[str, Any]] = []
    for promise_id, name, promise, result, cards in themes:
        rows.append(
            {
                "promise_id": promise_id,
                "source_ids": [promise_id, "V2-D201", "V2-D057"],
                "domain": "R1",
                "level": "MUST",
                "mvp": True,
                "mvp_class": "R1_CROSS_CUTTING_MUST",
                "counted_in_external_376": False,
                "user_promise": f"{name}: {promise}",
                "entry": "设置 → 主题 / 外观",
                "operation": "选择主题与外观，并打开不同页面状态",
                "observable_result": result,
                "states_accessibility_rwd": render_state_field("UX", "MUST"),
                "e0_e7_strength": e_levels("MUST", "UX"),
                "automation_fixture_evidence": {"evidence_ids": ["EV-S5-R1", "EV-UI-R1"], "status": "STATIC_ONLY"},
                "candidate_device_evidence": {"status": "NONE_AT_S6", "reason": "Theme runtime not executed."},
                "threshold_ids": threshold_refs(promise_id),
                "uncovered": "Compose 实际渲染、真字体、TalkBack、动态色、折叠和用户主观终判未执行。",
                "g2_card_ids": cards,
                "owner": f"G2_CARD:{cards[0]}",
                "blocked_reason": "RUNTIME_UNVERIFIED",
                "source_location": {"path": "docs/UI_DESIGN_SYSTEM.md", "section": "V2-R1-02", "line": 49},
            }
        )

    rows.append(
        {
            "promise_id": "R1-IA-ORDER",
            "source_ids": ["R1-IA-ORDER", "V2-D201"],
            "domain": "R1",
            "level": "MUST",
            "mvp": True,
            "mvp_class": "R1_CROSS_CUTTING_MUST",
            "counted_in_external_376": False,
            "user_promise": "一级 IA 与入口顺序固定为首页、视频、音乐、书架；媒体库、下载、设置保持既有层级。",
            "entry": "App 一级容器",
            "operation": "遍历一级入口并进入既有二级工具入口",
            "observable_result": "五主题下入口顺序、动作含义与层级一致。",
            "states_accessibility_rwd": render_state_field("UX", "MUST"),
            "e0_e7_strength": e_levels("MUST", "UX"),
            "automation_fixture_evidence": {"evidence_ids": ["EV-S5-R1", "EV-UI-R1"], "status": "STATIC_ONLY"},
            "candidate_device_evidence": {"status": "NONE_AT_S6", "reason": "Navigation runtime not executed."},
            "threshold_ids": ["NONE_IN_ACCEPTED_SOURCE"],
            "uncovered": "真实路由、返回栈与跨窗口状态保持未验证。",
            "g2_card_ids": ["G2-UI-R1-FOUNDATION", "G2-NAV-IDENTITY"],
            "owner": "G2_CARD:G2-UI-R1-FOUNDATION",
            "blocked_reason": "RUNTIME_UNVERIFIED",
            "source_location": {"path": "docs/UI_DESIGN_SYSTEM.md", "section": "V2-R1-02", "line": 55},
        }
    )

    interactions = [
        ("R1-I01", "S5-I01 首页", "首页 → 继续阅读", "显示正在恢复进度 62%；保留主卡、返回路径与底栏状态。", "不可续 / 离线 / 区块失败分别说明，保留最近内容与三域入口。", ["G2-NAV-IDENTITY", "G2-READER-CORE"]),
        ("R1-I02", "S5-I02 媒体库 / 视频详情", "详情 → 主播放", "保留壳层；只接受一次提交；区分 403 / 404 / 415。", "可归因错误给重试 / 返回，不伪装成空内容。", ["G2-VIDEO-CORE"]),
        ("R1-I03", "S5-I03 音乐 / 队列", "曲目短操作 → 播放下一首", "幂等插入；Snackbar 与队列同步；保持同曲身份。", "本地文件丢失不重试、保留不可用身份；全部失败可重试 / 返回。", ["G2-MUSIC-CORE"]),
        ("R1-I04", "S5-I04 书架 / 阅读器", "打开书 → 继续阅读", "非流式明确显示准备整本文件；阅读主题只覆盖正文。", "流失败回退整本；本地文件不可用保留书目身份并允许重选 / 移除。", ["G2-READER-CORE"]),
        ("R1-I05", "S5-I05 下载", "详情或下载中心 → 开始任务", "预检网络 / 空间 / 卷 / 来源；accepted / pending；任务、进度、通知、下载页同一状态真源。", "空间不足可选范围；401 / 403 / 404 / 415 不自动重试，保留暂停 / 删除。", ["G2-DOWNLOAD-STATE"]),
        ("R1-I06", "S5-I06 搜索", "顶栏 → 提交查询", "查询与 scope 同时可见；只接受最新 generation；pending / 未输入 / 无结果 / 失败分开。", "保留查询与 scope；不支持 scope 禁用并解释；空查询不发请求。", ["G2-SEARCH"]),
        ("R1-I07", "S5-I07 设置 / 主题", "设置 → 选择 Lumen", "200ms 可关闭过渡；不重置滚动 / 数据；勾选 + 文本 + 描边。", "保存失败回退旧主题；Android 12 以下动态色禁用并解释。", ["G2-UI-R1-FOUNDATION"]),
        ("R1-I08", "S5-I08 播放控制", "详情 / Mini Player → 播放或暂停", "单按钮命名；seek / 字幕 / 音轨 / 锁定按能力启用；状态不只靠颜色；4 秒隐藏后可召回。", "单条失败跳项；全部失败停止并给非模态重试 / 返回；Reduce Motion 直接切换。", ["G2-VIDEO-CORE", "G2-MUSIC-CORE"]),
        ("R1-I09", "S5-I09 SyncPlay 候选", "仅能力门通过后 → 创建 / 加入", "展示 joining、accepted、revision、成员状态；不得把本地成功当服务器成功。", "离线 / 403 / 不支持 / 过期退普通播放；双端时钟证据留 G2；当前不承诺可用。", ["G2-SYNCPLAY-GATED"]),
    ]
    for promise_id, name, entry, feedback, failure, cards in interactions:
        rows.append(
            {
                "promise_id": promise_id,
                "source_ids": [promise_id, "V2-D201"],
                "domain": "R1",
                "level": "MUST",
                "mvp": True,
                "mvp_class": "R1_CROSS_CUTTING_MUST",
                "counted_in_external_376": False,
                "user_promise": f"{name}: {entry}",
                "entry": entry,
                "operation": f"执行{name}的主要动作并覆盖反馈、失败与恢复",
                "observable_result": feedback,
                "states_accessibility_rwd": render_state_field("UX", "MUST"),
                "e0_e7_strength": e_levels("MUST", "UX"),
                "automation_fixture_evidence": {"evidence_ids": ["EV-S5-R1", "EV-UI-R1"], "status": "STATIC_ONLY"},
                "candidate_device_evidence": {"status": "NONE_AT_S6", "reason": "Nine interactions are static S5 inputs only."},
                "threshold_ids": threshold_refs(promise_id),
                "uncovered": failure,
                "g2_card_ids": cards,
                "owner": f"G2_CARD:{cards[0]}",
                "blocked_reason": (
                    "GATED_BY_CAPABILITY_AND_TEST_SERVER"
                    if promise_id == "R1-I09"
                    else "RUNTIME_UNVERIFIED"
                ),
                "source_location": {"path": "docs/UI_DESIGN_SYSTEM.md", "section": "V2-R1-04", "line": 115},
            }
        )

    rwd = [
        ("R1-RWD-COMPACT", "Compact", "<600dp；底部导航；16/12dp；2 列", 100),
        ("R1-RWD-MEDIUM", "Medium", "600–839dp；72dp 收轨；24/16dp；3 列", 101),
        ("R1-RWD-EXPANDED", "Expanded", "840–1199dp；72dp 收轨；32/16dp；4 列", 102),
        ("R1-RWD-LARGE", "Large", "1200–1599dp；216dp 展开轨；48/24dp；5 列", 103),
        ("R1-RWD-EXTRA-LARGE", "ExtraLarge", "≥1600dp；216dp 展开轨；48/24dp；7 列", 104),
        ("R1-RWD-FOLDABLE", "Foldable", "静态 700×900；72dp 收轨 + 24dp 铰链；左/右 242/314dp", 105),
    ]
    for promise_id, name, description, line in rwd:
        rows.append(
            {
                "promise_id": promise_id,
                "source_ids": [promise_id, "V2-D201"],
                "domain": "R1",
                "level": "MUST",
                "mvp": True,
                "mvp_class": "R1_CROSS_CUTTING_MUST",
                "counted_in_external_376": False,
                "user_promise": f"{name}: {description}",
                "entry": "任意一级页面 / 播放器 / 阅读器",
                "operation": "改变窗口宽度、分屏、旋转或折叠状态",
                "observable_result": "档位按有效宽度切换，滚动、选择、焦点、下载 / 播放 / 阅读动作与返回路径保持。",
                "states_accessibility_rwd": render_state_field("UX", "MUST"),
                "e0_e7_strength": e_levels("MUST", "UX"),
                "automation_fixture_evidence": {"evidence_ids": ["EV-S5-R1", "EV-UI-R1"], "status": "STATIC_ONLY"},
                "candidate_device_evidence": {"status": "NONE_AT_S6", "reason": f"{name} runtime is not executed."},
                "threshold_ids": threshold_refs(promise_id),
                "uncovered": "Compose / 折叠铰链 / 分屏 / 旋转 / 状态保持未执行。",
                "g2_card_ids": ["G2-UI-R1-FOUNDATION"],
                "owner": "G2_CARD:G2-UI-R1-FOUNDATION",
                "blocked_reason": "RUNTIME_UNVERIFIED",
                "source_location": {"path": "docs/UI_DESIGN_SYSTEM.md", "section": "V2-R1-03", "line": line},
            }
        )

    a11y = [
        ("R1-A11Y-CONTRAST", "正文文字对比度 ≥4.5:1；焦点 / 必要边界 ≥3:1；S5 静态 130/130 pair。", "静态对比度通过不能替代真机截图 / 动态色 / 字体渲染。", 129),
        ("R1-A11Y-TOUCH-48", "触控目标至少 48×48dp。", "真实热区不得与相邻动作重叠。", 131),
        ("R1-A11Y-NAMES", "18/18 示例动作有可访问名称、角色与状态策略；18/18 映射完成。", "TalkBack / 键盘真实播报与焦点恢复未执行。", 132),
        ("R1-A11Y-REFOLD-200", "200% 字号下标题换行、元信息重排、主要求不缩字、不截断。", "真实字体与运行时重排未执行。", 134),
        ("R1-A11Y-REDUCE-MOTION", "Reduce Motion 下主题与状态过渡为 0ms / 直接切换。", "实际系统设置和全部组件未执行。", 135),
        ("R1-READER-BODY-LOCAL-THEME", "阅读显示主题只作用于正文；外框、顶栏、按钮、弹层服从 App 主题。", "阅读器五主题真实渲染未执行。", 27),
    ]
    for promise_id, promise, uncovered, line in a11y:
        rows.append(
            {
                "promise_id": promise_id,
                "source_ids": [promise_id, "V2-D201"],
                "domain": "R1",
                "level": "MUST",
                "mvp": True,
                "mvp_class": "R1_CROSS_CUTTING_MUST",
                "counted_in_external_376": False,
                "user_promise": promise,
                "entry": "全局组件 / 阅读器 / 主题 / 系统辅助设置",
                "operation": "遍历适用状态并开启相应系统辅助能力",
                "observable_result": promise,
                "states_accessibility_rwd": render_state_field("UX", "MUST"),
                "e0_e7_strength": e_levels("MUST", "UX"),
                "automation_fixture_evidence": {"evidence_ids": ["EV-S5-R1", "EV-UI-R1"], "status": "STATIC_ONLY"},
                "candidate_device_evidence": {"status": "NONE_AT_S6", "reason": "Accessibility runtime not executed."},
                "threshold_ids": threshold_refs(promise_id),
                "uncovered": uncovered,
                "g2_card_ids": ["G2-UI-R1-FOUNDATION"],
                "owner": "G2_CARD:G2-UI-R1-FOUNDATION",
                "blocked_reason": "RUNTIME_UNVERIFIED",
                "source_location": {"path": "docs/UI_DESIGN_SYSTEM.md", "section": "V2-R1-04", "line": line},
            }
        )

    invariants = [
        ("R1-TOKEN-SINGLE-SOURCE", "Primitive → Theme → Semantic → Component；CinefinTokens.kt 是唯一代码 token 源；S5 JSON 不成为页面运行时 token 源。"),
        ("R1-COLOR-NOT-ONLY", "颜色不得成为唯一状态通道；错误、权限和危险确认保留文字。"),
        ("R1-DYNAMIC-COLOR-EXCLUSION", "动态色只替换 Prism 骨架颜色，不叠加品牌主题；Android 12 以下入口禁用并解释。"),
    ]
    for promise_id, promise in invariants:
        rows.append(
            {
                "promise_id": promise_id,
                "source_ids": [promise_id, "V2-D201", "V2-D054", "V2-D057"],
                "domain": "R1",
                "level": "MUST",
                "mvp": True,
                "mvp_class": "R1_CROSS_CUTTING_MUST",
                "counted_in_external_376": False,
                "user_promise": promise,
                "entry": "设计 / 实现 / 审查入口",
                "operation": "检查 token 来源、状态语义和主题组合",
                "observable_result": "代码审查可定位唯一 token 与可访问状态来源，主题组合无混色。",
                "states_accessibility_rwd": render_state_field("UX", "MUST"),
                "e0_e7_strength": e_levels("MUST", "UX"),
                "automation_fixture_evidence": {"evidence_ids": ["EV-S5-R1", "EV-UI-R1"], "status": "STATIC_ONLY"},
                "candidate_device_evidence": {"status": "NONE_AT_S6", "reason": "Runtime token / dynamic-color proof not executed."},
                "threshold_ids": threshold_refs(promise_id),
                "uncovered": "Compose token 审计、TalkBack 状态和动态色系统切换未执行。",
                "g2_card_ids": ["G2-UI-R1-FOUNDATION"],
                "owner": "G2_CARD:G2-UI-R1-FOUNDATION",
                "blocked_reason": "RUNTIME_UNVERIFIED",
                "source_location": {"path": "docs/UI_DESIGN_SYSTEM.md", "section": "V2-R1-01/02/04", "line": 18},
            }
        )
    return rows


def bind_wave_boundaries(
    card_id: str,
    slug: str,
    boundaries: list[dict[str, str]],
) -> list[dict[str, Any]]:
    contract = WAVE_CONTRACTS[(card_id, slug)]
    result = [{**boundary, "capability_tags": []} for boundary in boundaries]
    by_ref = {
        boundary.get("path") or boundary.get("fqcn"): boundary
        for boundary in result
    }
    for capability in contract["capabilities"]:
        anchors = contract["anchors"].get(capability, [])
        if not anchors:
            raise ValueError(f"{card_id}/{slug} capability has no boundary anchor: {capability}")
        for anchor in anchors:
            boundary = by_ref.get(anchor)
            if boundary is None:
                raise ValueError(
                    f"{card_id}/{slug} capability {capability} anchor is absent: {anchor}"
                )
            if capability not in boundary["capability_tags"]:
                boundary["capability_tags"].append(capability)
    return result


def artifact_contract(
    wave_id: str,
    slug: str,
    expected: str,
    objective: str,
) -> dict[str, Any]:
    text = f"{objective} {expected}"
    lowered = text.lower()
    if any(token in text for token in ("候选包", "真实更新", "真实搜索", "核心下载矩阵", "真实阅读")):
        kind = "candidate-package"
        evidence_level = "E7_CANDIDATE_PACKAGE"
    elif any(
        token in lowered
        for token in (
            "设备",
            "talkback",
            "键盘",
            "saf",
            "documentsui",
            "16 kb",
            "真机",
            "零网络",
        )
    ):
        kind = "device-evidence"
        evidence_level = "E5_DEVICE"
    elif any(token in text for token in ("E6", "双端", "真实写")):
        kind = "report"
        evidence_level = "E6_TEST_SERVER"
    elif any(token in text for token in ("迁移", "WAL", "跨存储", "receipt")):
        kind = "report"
        evidence_level = "E4_INSTRUMENTATION"
    elif "fixture" in lowered:
        kind = "fixture"
        evidence_level = "E1_STATE_TEST"
    elif any(token in text for token in ("清单", "矩阵", "SBOM", "notice", "capability")):
        kind = "static-ledger"
        evidence_level = "E0_STATIC"
    elif any(token in lowered for token in ("test", "测试")):
        kind = "test"
        evidence_level = "E1_STATE_TEST"
    else:
        kind = "report"
        evidence_level = "E1_EVIDENCE_REPORT"
    return {
        "id": f"ART-{wave_id}",
        "kind": kind,
        "locator": f"future-evidence://{wave_id}/{slug}",
        "expected": expected,
        "evidence_level": evidence_level,
        "status": "PLANNED_NOT_EXECUTED",
        "can_prove": "Only the declared future artifact after execution is bound to a candidate SHA.",
        "cannot_prove": "No runtime, device, QA or release claim is made at S6.",
    }


def build_card_outputs(
    nodes: list[dict[str, Any]],
    rows: list[dict[str, Any]],
    base_rows: list[dict[str, Any]],
    r1_rows: list[dict[str, Any]],
    membership_by_row: dict[str, dict[str, Any]],
) -> list[dict[str, Any]]:
    cards = []
    all_rows = rows + base_rows + r1_rows
    for row in all_rows:
        membership = membership_by_row.get(row["promise_id"])
        if membership is None:
            raise ValueError(f"{row['promise_id']} membership is missing")
        wave_ids = membership.get("wave_ids")
        if not isinstance(wave_ids, list) or not wave_ids:
            raise ValueError(f"{row['promise_id']} membership wave_ids must be non-empty")
        if len(wave_ids) != len(set(wave_ids)):
            raise ValueError(f"{row['promise_id']} has duplicate wave membership")
        row["g2_wave_ids"] = list(wave_ids)
    extra_membership_ids = sorted(set(membership_by_row) - {row["promise_id"] for row in all_rows})
    if extra_membership_ids:
        raise ValueError(f"orphan wave memberships: {extra_membership_ids[:8]}")
    for spec in CARD_SPECS:
        wave_defs = CARD_WAVE_DEFS[spec["id"]]
        candidates = [
            row
            for row in all_rows
            if spec["id"] in row.get("g2_card_ids", [])
        ]
        delivery_waves = []
        prior_wave = None
        for index, (slug, objective, artifact) in enumerate(wave_defs, 1):
            wave_id = f"{spec['id']}-W{index}"
            assigned_rows = [
                row
                for row in candidates
                if wave_id in membership_by_row[row["promise_id"]]["wave_ids"]
            ]
            if not assigned_rows:
                raise ValueError(f"{wave_id} has no atomic promise row")
            promise_ids = [row["promise_id"] for row in assigned_rows]
            boundaries = bind_wave_boundaries(
                spec["id"],
                slug,
                CARD_WAVE_BOUNDARIES[(spec["id"], slug)],
            )
            wave_contract = WAVE_CONTRACTS[(spec["id"], slug)]
            expanded_files = sum(
                1 for boundary in boundaries if boundary["kind"] == "FILE"
            )
            entries = list(
                dict.fromkeys(row["entry"] for row in assigned_rows)
            )[:3]
            operations = list(
                dict.fromkeys(row["operation"] for row in assigned_rows)
            )[:2]
            results = list(
                dict.fromkeys(row["observable_result"] for row in assigned_rows)
            )[:2]
            operation_text = "；".join(operations)
            if len(assigned_rows) > len(operations):
                operation_text += "；其余原子行按各自 operation 串行执行"
            wave_external_dependencies = list(
                CARD_DEPENDENCIES[spec["id"]]
            )
            if wave_id == "G2-READER-TTS-GATED-W1":
                wave_external_dependencies = list(
                    dict.fromkeys(
                        [
                            *D204_TTS_W1_DECISION_DEPENDENCIES,
                            *wave_external_dependencies,
                        ]
                    )
                )
            blockers = [
                f"前置依赖未满足：{dependency}"
                for dependency in wave_external_dependencies
            ]
            if spec["status"].startswith("GATED_"):
                blockers.append("V2-Q-G1-03 remains Open")
            blockers.append(spec["stop"])
            delivery_waves.append(
                {
                    "id": wave_id,
                    "card_id": spec["id"],
                    "slug": slug,
                    "execution_kind": "IMPLEMENTABLE_WAVE",
                    "objective": objective,
                    "owner": spec["role"],
                    "promise_ids": promise_ids,
                    "capability_tags": list(wave_contract["capabilities"]),
                    "entry_tags": list(wave_contract["entry_tags"]),
                    "action_tags": list(wave_contract["action_tags"]),
                    "entry": "；".join(entries),
                    "operation": operation_text,
                    "observable_acceptance": (
                        f"{artifact}；代表性可观察结果：" + "；".join(results)
                    ),
                    "external_dependencies": wave_external_dependencies,
                    "wave_dependencies": [] if prior_wave is None else [prior_wave],
                    "file_interface_boundaries": boundaries,
                    "existing_file_expansion_count": expanded_files,
                    "acceptance_artifacts": [
                        artifact_contract(wave_id, slug, artifact, objective)
                    ],
                    "blocking_conditions": list(dict.fromkeys(blockers)),
                    "stop_condition": (
                        f"{artifact} 不可复算、需要越出本 wave 边界，或出现以下条件时停止："
                        f"{spec['stop']}"
                    ),
                }
            )
            prior_wave = wave_id
        covered_must = sorted(
            {
                row["parent_source_id"]
                for row in rows
                if spec["id"] in row["g2_card_ids"] and row["level"] == "MUST"
            }
        )
        covered_external_must = sorted(
            source_id
            for source_id in covered_must
            if source_id not in READER_ADDENDUM_ID_SET
        )
        covered_addendum_must = sorted(
            source_id
            for source_id in covered_must
            if source_id in READER_ADDENDUM_ID_SET
        )
        covered_base = sorted(
            {
                base["source_ids"][0]
                for base in base_rows
                if spec["id"] in base["g2_card_ids"]
            }
        )
        covered_r1 = sorted(
            {
                row["promise_id"]
                for row in r1_rows
                if spec["id"] in row["g2_card_ids"]
            }
        )
        cards.append(
            {
                **spec,
                "evidence": [
                    evidence_id
                    for evidence_id in spec["evidence"]
                    if evidence_id != "EV-S6"
                ],
                "acceptance_criteria": spec["criteria"],
                "card_dependencies": CARD_DEPENDENCIES[spec["id"]],
                "planning_umbrella": True,
                "execution_kind": "PLANNING_UMBRELLA",
                "boundary_model": "WAVE_SCOPED_EXPLICIT_FILE_OR_FQCN_ONLY",
                "delivery_waves": delivery_waves,
                "delivery_wave_count": len(delivery_waves),
                "wave_membership_edge_count": sum(
                    len(wave["promise_ids"]) for wave in delivery_waves
                ),
                "max_wave_boundary_count": max(
                    len(wave["file_interface_boundaries"]) for wave in delivery_waves
                ),
                "wave_span": f"{len(delivery_waves)} independently verifiable delivery wave(s)",
                "covers_must_source_ids": covered_must,
                "covers_external_must_source_ids": covered_external_must,
                "covers_project_addendum_source_ids": covered_addendum_must,
                "covers_base_groups": covered_base,
                "covers_r1_promises": covered_r1,
                "acceptance_artifacts": [
                    artifact["id"]
                    for wave in delivery_waves
                    for artifact in wave["acceptance_artifacts"]
                ],
                "independent_qa_required_after_implementation": True,
                "owner_in_s6": spec["role"],
            }
        )
    return cards


def build_threshold_rows() -> list[dict[str, Any]]:
    result = []
    for idx, item in enumerate(THRESHOLD_SPECS, 1):
        threshold_id, req_ids, metric, operator, value, unit, environment, evidence_level, status, source = item
        result.append(
            {
                "id": threshold_id,
                "requirement_ids": req_ids,
                "metric": metric,
                "operator": operator,
                "value": value,
                "unit": unit,
                "environment": environment,
                "evidence_level": evidence_level,
                "status": status,
                "source": source,
                "runtime_executed": False,
                "device_executed": False,
            }
        )
    return result


EVIDENCE_DEPENDENCIES = {
    "EV-S1": [],
    "EV-S2": [],
    "EV-S3": [],
    "EV-S4A": [],
    "EV-S4B": ["EV-S4A"],
    "EV-S4C": [],
    "EV-S5-R1": ["EV-UI-R1"],
    "EV-UI-R1": [],
    "EV-A1": ["EV-S2", "EV-S3"],
    "EV-A2": ["EV-S3"],
    "EV-BASE001": ["EV-S3"],
    "EV-BASE003": ["EV-S3"],
    "EV-BASE004": ["EV-S2"],
    "EV-BASE005": ["EV-S3"],
    "EV-Q03-SBOM": ["EV-S4A"],
    "EV-Q03-NATIVE": ["EV-S4A", "EV-S4B", "EV-S4C"],
    "EV-Q03-NOTICE": ["EV-S4A", "EV-S4B"],
    "EV-Q03-TTS-PDF": ["EV-S4C"],
    "EV-S6": [
        "EV-S1",
        "EV-S2",
        "EV-S3",
        "EV-S4A",
        "EV-S4B",
        "EV-S4C",
        "EV-S5-R1",
        "EV-UI-R1",
        "EV-A1",
        "EV-A2",
        "EV-BASE001",
        "EV-BASE003",
        "EV-BASE004",
        "EV-BASE005",
        "EV-Q03-SBOM",
        "EV-Q03-NATIVE",
        "EV-Q03-NOTICE",
        "EV-Q03-TTS-PDF",
    ],
}


def evidence_package_claim_errors(
    evidence_packages: list[dict[str, Any]],
) -> list[str]:
    errors: list[str] = []
    for package in evidence_packages:
        evidence_id = str(package.get("id", "UNKNOWN"))
        claims = package.get("can_prove", [])
        if not isinstance(claims, list):
            claims = [claims]
        for claim_index, claim in enumerate(claims):
            text = str(claim)
            for pattern in PROHIBITED_EVIDENCE_CLAIMS:
                match = pattern.search(text)
                if match and not claim_is_negated(
                    text,
                    match.start(),
                    match.end(),
                ):
                    errors.append(
                        f"{evidence_id}.can_prove[{claim_index}] general overclaim: "
                        "selected/frozen engine or profile, Android runtime proof, "
                        "production readiness or runtime PASS lacks executed evidence"
                    )
                    break
    return errors


def evidence_index() -> list[dict[str, Any]]:
    result = []
    for spec in EVIDENCE_SPECS:
        if spec["id"] == "EV-S6":
            result.append(
                {
                    **spec,
                    "depends_on": EVIDENCE_DEPENDENCIES[spec["id"]],
                    "exists": True,
                    "bytes_at_generation": None,
                    "sha256_at_generation": None,
                    "self_reference_policy": "generated by this run; manifest verifies the actual bytes",
                }
            )
            continue
        path = ROOT / spec["path"]
        if not path.exists():
            raise FileNotFoundError(path)
        data = path.read_bytes()
        result.append(
            {
                **spec,
                "depends_on": EVIDENCE_DEPENDENCIES[spec["id"]],
                "exists": True,
                "bytes_at_generation": len(data),
                "sha256_at_generation": sha256_bytes(data),
            }
        )
    claim_errors = evidence_package_claim_errors(result)
    if claim_errors:
        raise ValueError("; ".join(claim_errors))
    return result


def build_source_index(matrix: dict[str, Any]) -> dict[str, Any]:
    semantic_bytes = SEMANTICS_PATH.read_bytes()
    semantic_document = json.loads(semantic_bytes.decode("utf-8"))
    membership_bytes = WAVE_MEMBERSHIP_PATH.read_bytes()
    membership_document = json.loads(membership_bytes.decode("utf-8"))
    v2_doc_lines = read_text(V2_PATH).splitlines()
    def heading_line(prefix: str) -> int:
        return next(
            index
            for index, line in enumerate(v2_doc_lines, start=1)
            if line.startswith(prefix)
        )

    section_5_10_start = heading_line("### 5.10 ")
    section_5_11_start = heading_line("### 5.11 ")
    section_13_start = next(
        index
        for index, line in enumerate(v2_doc_lines, start=1)
        if line.startswith("## 13.")
    )
    raw_v2_g0_ranges = [
        (spec["start"], spec["end"]) for spec in TABLE_SPECS.values()
    ] + [(BASE_SPEC["start"], BASE_SPEC["end"])]
    raw_v2_g0_ranges.append((section_5_10_start, section_5_11_start - 1))
    v2_g0_ranges: list[tuple[int, int]] = []
    for start, end in sorted(raw_v2_g0_ranges):
        if v2_g0_ranges and start <= v2_g0_ranges[-1][1] + 1:
            v2_g0_ranges[-1] = (v2_g0_ranges[-1][0], max(v2_g0_ranges[-1][1], end))
        else:
            v2_g0_ranges.append((start, end))
    doc_specs = [
        (
            "DOC-AGENTS",
            "AGENTS.md",
            "repository instructions: memory, PowerShell, graphify, write boundaries",
            ["all"],
            [(1, min(220, len(read_text(ROOT / "AGENTS.md").splitlines())))],
        ),
        (
            "DOC-PROJECT",
            "docs/PROJECT_PLAN.md",
            "project task lines and multi-session protocol",
            ["goals", "write boundaries", "exit contract"],
            [(33, 98), (233, 265), (277, 284)],
        ),
        (
            "DOC-V2-G0",
            "docs/V2_PLAN.md",
            "accepted G0 matrices and counts",
            ["external 445 / 376", "D203 addendum 4 / normalized MVP 380", "BASE groups", "E0–E7"],
            v2_g0_ranges,
        ),
        (
            "DOC-V2-G1",
            "docs/V2_PLAN.md",
            "G1 static evidence, Q03, R1, D205 and ACTIVE handoff",
            ["E0–E7", "Q03", "Q05", "Q04", "S6", "D203", "D204", "D205"],
            [(section_5_11_start, section_13_start - 1), (section_13_start, len(v2_doc_lines))],
        ),
        (
            "DOC-TASKS",
            "docs/TASKS.md",
            "current In Progress and done evidence",
            ["status"],
            [(1, len(read_text(TASKS_PATH).splitlines()))],
        ),
        (
            "DOC-DECISIONS",
            "docs/DECISIONS.md",
            "accepted decisions, closed Q05 and open Q03",
            ["D057", "D198–D205", "Q03", "Q05", "background / voice-pack TTS"],
            [(1, len(read_text(DECISIONS_PATH).splitlines()))],
        ),
        (
            "DOC-UI-R1",
            "docs/UI_DESIGN_SYSTEM.md",
            "accepted R1 UI authority",
            ["themes", "RWD", "interactions", "a11y"],
            [(1, 149), (412, 558), (634, 795), (875, 891)],
        ),
    ]
    docs = []
    for source_id, rel, purpose, uses, ranges in doc_specs:
        path = ROOT / rel
        data = path.read_bytes()
        docs.append(
            {
                "id": source_id,
                "path": rel,
                "purpose": purpose,
                "uses": uses,
                "line_ranges": [{"start": a, "end": b} for a, b in ranges],
                "bytes": len(data),
                "sha256": sha256_bytes(data),
                "evidence_level": "human-readable authority / static input",
            }
        )
    gitattributes_data = GITATTRIBUTES_PATH.read_bytes()
    return {
        "schema_version": "g1-s6-source-index/v1",
        "status": STATUS,
        "generated_on": "2026-11-04",
        "current_decision_state": {
            "d204": "ACCEPTED_ENGINE_STRATEGY_A",
            "d205": "ACCEPTED_SMALL_ENGINE_RESCAN",
            "q05": "CLOSED_D204",
            "engine_strategy": "A_COMPAT_FALLBACK_PLUS_SECOND_ENGINE_SCOPE",
            "exact_engine": "UNSELECTED",
            "exact_profile": "OPEN",
            "sherpa_matcha": "COMPATIBILITY_FALLBACK_NOT_FROZEN",
            "second_engine": "KOKORO_THEN_MOSS_STATIC_SHORTLIST",
            "q03": "OPEN",
            "g1": "NOT_PASSED",
            "runtime": "UNVERIFIED",
            "s6": "SCOPED_RECONFIRM_PASS_AFTER_D204",
            "d205_review": "SCOPED_RECONFIRM_PASS",
            "l2_l3": "LOCKED",
        },
        "historical_review": {
            "status": HISTORICAL_REVIEW_STATUS,
            "scope": "S6 before D203 rebuild and D204; Q05 was Open at review time",
            "current_reapproval": False,
            "note": "The historical review remains Q05-Open context. The D204-after scoped documentation reconfirm was the predecessor PASS; the D205 current revision has a separate fresh scoped reconfirm PASS with no findings. Neither review implies runtime, QA or release PASS.",
        },
        "line_ending_policy": {
            "path": ".gitattributes",
            "bytes": len(gitattributes_data),
            "sha256": sha256_bytes(gitattributes_data),
            "rules": {
                ".gitattributes": {"text": "set", "eol": "lf"},
                "docs/DECISIONS.md": {"text": "set", "eol": "lf"},
                "docs/V2_PLAN.md": {"text": "set", "eol": "lf"},
                "docs/TASKS.md": {"text": "set", "eol": "lf"},
            },
            "scope": EOL_ATTRIBUTE_SCOPE,
        },
        "document_sources": docs,
        "source_origin_counts": {
            "external_source_nodes": matrix["counts"]["external_source_nodes"],
            "project_addendum_source_nodes": matrix["counts"]["project_addendum_source_nodes"],
            "normalized_source_nodes": matrix["counts"]["normalized_source_nodes"],
            "external_acceptance_rows": matrix["counts"]["external_acceptance_rows"],
            "project_addendum_acceptance_rows": matrix["counts"]["project_addendum_acceptance_rows"],
            "normalized_acceptance_rows": matrix["counts"]["normalized_acceptance_rows"],
            "external_mvp_subitems": matrix["counts"]["external_mvp_subitems"],
            "project_addendum_mvp_subitems": matrix["counts"]["project_addendum_mvp_subitems"],
            "normalized_mvp_subitems": matrix["counts"]["normalized_mvp_subitems"],
        },
        "semantic_registry": {
            "path": "row-semantics.json",
            "schema_version": semantic_document["schema_version"],
            "record_count": len(semantic_document["rows"]),
            "join_key": "promise_id",
            "bytes": len(semantic_bytes),
            "sha256": sha256_bytes(semantic_bytes),
            "authority": (
                "Explicit static seed entries plus deterministic source-grounded "
                "action composition; every action excerpt is checked against its "
                "accepted V2_PLAN line and unresolved dimensions stay explicit gaps."
            ),
            "verification_limit": (
                "Mechanical checks cover schema, source anchors, coverage and known contradictions; "
                "natural-language equivalence requires scoped review."
            ),
        },
        "wave_membership_registry": {
            "path": "wave-membership.json",
            "schema_version": membership_document["schema_version"],
            "membership_record_count": len(membership_document["memberships"]),
            "row_wave_edge_count": sum(
                len(item["wave_ids"])
                for item in membership_document["memberships"]
            ),
            "join_key": "row_id",
            "bytes": len(membership_bytes),
            "sha256": sha256_bytes(membership_bytes),
            "authority": (
                "Explicit atomic row to one-or-more delivery-wave membership. "
                "The builder joins this table; it does not partition rows by order, hash or keywords."
            ),
            "verification_limit": (
                "Capability, entry, action and boundary tags are mechanically checked; "
                "natural-language completeness still requires independent scoped review."
            ),
        },
        "evidence_packages": evidence_index(),
        "status_authorities": [
            {
                "fact": "Q04 closed by D201 and UI authority is V2 R1",
                "source_ids": ["DOC-DECISIONS", "DOC-UI-R1"],
            },
            {
                "fact": "Q03 remains Open, Q05 is Closed by D204, and G1 is NOT_PASSED",
                "source_ids": ["DOC-DECISIONS", "DOC-V2-G1", "DOC-TASKS"],
            },
            {
                "fact": "D203 voice-pack / background-lockscreen TTS remains; D204 accepts engine strategy A with Sherpa / Matcha compatibility fallback and no selected second engine or exact profile",
                "source_ids": ["DOC-DECISIONS", "DOC-V2-G1", "DOC-TASKS"],
            },
            {
                "fact": REVIEW_AUTHORITY_FACT,
                "source_ids": ["DOC-V2-G1", "DOC-TASKS"],
            },
            {
                "fact": "READER-060 requires target-Android fully offline local TTS; D204-A is compatibility fallback plus a gated expressive second-engine Spike, not a current Sherpa main-engine mandate",
                "source_ids": ["DOC-V2-G0", "DOC-V2-G1", "DOC-DECISIONS", "DOC-TASKS"],
            },
        ],
        "evidence_upgrade_rules": [
            "Static fixture can support E0 only.",
            "S5 static coverage can support static visual/a11y assertions, not Compose / TalkBack / device PASS.",
            "Historical 1.4 evidence is lower-order context and is not a 2.0 candidate journey.",
            "Only E5–E7 candidate runtime evidence may support a user-visible runtime PASS claim.",
            "Fake, Worker SUCCESS, screenshot-only and notification-existence evidence cannot substitute production protocol or completion.",
        ],
    }


def matrix_document(
    nodes: list[dict[str, Any]],
    rows: list[dict[str, Any]],
    base_rows: list[dict[str, Any]],
    r1_rows: list[dict[str, Any]],
    domain_counts: dict[str, Any],
    membership_by_row: dict[str, dict[str, Any]],
) -> dict[str, Any]:
    source_ids = {node["source_id"] for node in nodes}
    external_nodes = [node for node in nodes if node["source_origin"] == EXTERNAL_G0]
    addendum_nodes = [
        node for node in nodes if node["source_origin"] == PROJECT_G0_ADDENDUM
    ]
    if len(external_nodes) != 445 or len(addendum_nodes) != 4:
        raise ValueError(
            f"source count mismatch: external={len(external_nodes)} "
            f"addendum={len(addendum_nodes)}"
        )
    if len(nodes) != 449 or len(source_ids) != 449:
        raise ValueError(f"normalized source count mismatch: rows={len(nodes)} unique={len(source_ids)}")
    external_mvp = sum(
        1 for row in rows if row["mvp"] and row["source_origin"] == EXTERNAL_G0
    )
    addendum_mvp = sum(
        1 for row in rows if row["mvp"] and row["source_origin"] == PROJECT_G0_ADDENDUM
    )
    total_mvp = sum(1 for row in rows if row["mvp"])
    if external_mvp != 376 or addendum_mvp != 4 or total_mvp != 380:
        raise ValueError(
            f"MVP count mismatch: external={external_mvp} addendum={addendum_mvp} "
            f"normalized={total_mvp}"
        )
    reader_single_must = sum(
        1
        for node in nodes
        if node["domain"] == "READER"
        and not node["is_compound"]
        and not node["is_process"]
        and node["levels"] == ["MUST"]
    )
    return {
        "schema_version": "g1-s6-acceptance-matrix/v1",
        "task_id": "G1-S6",
        "status": STATUS,
        "generated_on": "2026-11-04",
        "counts": {
            "normalized_source_nodes": len(nodes),
            "external_source_nodes": len(external_nodes),
            "project_addendum_source_nodes": len(addendum_nodes),
            "external_acceptance_rows": sum(
                1 for row in rows if row["source_origin"] == EXTERNAL_G0
            ),
            "project_addendum_acceptance_rows": sum(
                1 for row in rows if row["source_origin"] == PROJECT_G0_ADDENDUM
            ),
            "normalized_acceptance_rows": len(rows),
            "explicit_semantic_records": len(rows),
            "external_mvp_subitems": external_mvp,
            "project_addendum_mvp_subitems": addendum_mvp,
            "normalized_mvp_subitems": total_mvp,
            "reader_matrix_source_nodes": sum(
                1 for node in nodes if node["domain"] == "READER"
            ),
            "reader_single_level_must": reader_single_must,
            "reader_mvp_subitems": sum(
                1
                for row in rows
                if row["domain"] == "READER" and row["mvp"]
            ),
            "compound_source_nodes": sum(1 for node in nodes if node["is_compound"]),
            "process_source_nodes": sum(1 for node in nodes if node["is_process"]),
            "base_must_groups": len(base_rows),
            "r1_cross_cutting_promises": len(r1_rows),
            "thresholds": len(THRESHOLD_SPECS),
            "g2_cards": len(CARD_SPECS),
            "g2_delivery_waves": sum(len(CARD_WAVE_DEFS[card["id"]]) for card in CARD_SPECS),
            "wave_membership_records": len(membership_by_row),
            "wave_membership_edges": sum(
                len(record["wave_ids"]) for record in membership_by_row.values()
            ),
            "max_wave_boundary_entries": max(
                len(boundaries)
                for boundaries in CARD_WAVE_BOUNDARIES.values()
            ),
            "risks": len(RISK_SPECS),
        },
        "normalization_rules": {
            "levels": ["MUST", "SHOULD", "LATER", "OUT", "PROCESS"],
            "mvp": "MUST product subitems only; external 376 excludes BASE, R1 and D203 project addendum; normalized MVP is 380",
            "source_origin": "external source IDs remain 445; D203 READER-087..090 are project-originated G0 addendum and are never counted as external source IDs",
            "compound": "Each accepted compound source node is split into stable child subitems; no compound is collapsed.",
            "process": "Process nodes remain traceable and are not product-level MVP.",
            "unknown": "UNRESOLVED / NONE_AT_S6 / BLOCKED replace unsupported facts; no runtime inference.",
            "wave_membership": (
                "Atomic rows join wave-membership.json by row_id; no round-robin, "
                "row-order, hash or keyword allocation is permitted."
            ),
        },
        "domain_counts": domain_counts,
        "source_nodes": nodes,
        "acceptance_rows": rows,
        "base_groups": base_rows,
        "r1_quality_rows": r1_rows,
        "global_boundaries": {
            "g1": "NOT_PASSED",
            "q03": "OPEN",
            "q05": "CLOSED_D204_ENGINE_STRATEGY_A",
            "q04": "CLOSED_D201",
            "ui_design_system": "V2_R1_ACCEPTED_DESIGN_AUTHORITY",
            "runtime": "UNVERIFIED",
            "s6": "SCOPED_RECONFIRM_PASS_AFTER_D204",
            "d205_review": "SCOPED_RECONFIRM_PASS",
            "d203": "ACCEPTED_VOICE_PACK_BACKGROUND_TTS",
            "d204": "ACCEPTED_ENGINE_STRATEGY_A",
            "d205": "ACCEPTED_SMALL_ENGINE_RESCAN",
            "sherpa_quality": "SHERPA_QUALITY_INSUFFICIENT_COMPAT_FALLBACK",
            "second_engine": "UNSELECTED_KOKORO_THEN_MOSS_STATIC_SHORTLIST",
            "c1_exact_profile": "OPEN_EXPRESSIVE_ENGINE_PROFILE",
            "l2_l3": "LOCKED",
        },
    }


def risk_document(cards: list[dict[str, Any]]) -> dict[str, Any]:
    card_ids = {card["id"] for card in cards}
    for risk in RISK_SPECS:
        gate_ids = [part.removeprefix("G2-") for part in risk["gate"].split(" / ")]
        for gate in risk["gate"].split(" / "):
            if gate.startswith("G2-") and gate not in card_ids:
                raise ValueError(f"risk {risk['id']} references unknown card {gate}")
    severity_counts = Counter(risk["severity"] for risk in RISK_SPECS)
    return {
        "schema_version": "g1-s6-risk-register/v1",
        "status": STATUS,
        "severity_counts": {key: severity_counts.get(key, 0) for key in ["P0", "P1", "P2", "P3"]},
        "risks": RISK_SPECS,
        "cross_domain_invariants": [
            {
                "id": "INV-IDENTITY",
                "rule": "serverId + userId 是服务器状态边界；资源还需稳定 item/source/book/media identity；未知 provenance 不猜。",
                "cards": ["G2-ARCH-CONTRACTS-MIGRATION", "G2-SEARCH", "G2-PERSISTENT-SWR", "G2-DOWNLOAD-STATE", "G2-READER-CORE", "G2-MUSIC-CORE", "G2-VIDEO-CORE", "G2-NAV-IDENTITY"],
            },
            {
                "id": "INV-SINGLE-SESSION",
                "rule": "音乐、视频与 TTS 共享单活动音频 owner / MediaSession；TTS 启动暂停音乐并保留队列，音乐再起播前终止 TTS。",
                "cards": ["G2-READER-TTS-GATED", "G2-MUSIC-CORE", "G2-VIDEO-CORE", "G2-NAV-IDENTITY", "G2-SYNCPLAY-GATED"],
            },
            {
                "id": "INV-CROSS-SURFACE-EQUALITY",
                "rule": "同一状态的页面、通知、角标、队列、离线库和失败终态必须来自同一持久真源并满足等式。",
                "cards": ["G2-DOWNLOAD-STATE", "G2-MUSIC-CORE", "G2-VIDEO-CORE", "G2-READER-CORE", "G2-READER-TTS-GATED"],
            },
            {
                "id": "INV-FAIL-CLOSED",
                "rule": "未知来源、旧 generation、迁移不一致、权限不明确、能力不可表达时必须 FAIL_CLOSED 或显式降级。",
                "cards": ["G2-ARCH-CONTRACTS-MIGRATION", "G2-DOWNLOAD-STATE", "G2-SERVER-WRITE", "G2-READER-PDF-GATED", "G2-Q03-SUPPLY-CHAIN-GATED"],
            },
        ],
    }


def readme_text(matrix: dict[str, Any], risks: dict[str, Any], cards: list[dict[str, Any]]) -> str:
    return f"""# Aurorama 2.0 · G1-S6 总验收矩阵与 G2 任务卡输入

状态：`{STATUS}`

当前 decision state：`D204_ACCEPTED_ENGINE_STRATEGY_A / D205_ACCEPTED_SMALL_ENGINE_RESCAN / SMALL_ANDROID_SECOND_ENGINE_RESCAN / KOKORO_THEN_MOSS_STATIC_SHORTLIST / ZIPVOICE_HOLD_LICENSE / D205_SCOPED_RECONFIRM_PASS / Q05_CLOSED_D204 / C1_EXPRESSIVE_ENGINE_PROFILE_OPEN / Q03_OPEN / G1_NOT_PASSED / RUNTIME_UNVERIFIED / L2_L3_LOCKED`。
`EV-S6.status`：`{EV_S6_STATUS}`。历史 review `PASS_WITH_P2` 仅限定为 Q05 当时 Open 的原始 S6 review，不是 D204 后当前 re-approval。

D204 docs/S6 fresh scoped documentation review 已 PASS / no findings；D205 current revision 随后由独立 fresh scoped reviewer 复审核定为 PASS / no findings。{matrix['counts']['normalized_acceptance_rows']} 条行级语义保持 schema v2（外部 {matrix['counts']['external_acceptance_rows']} + D203 addendum {matrix['counts']['project_addendum_acceptance_rows']}）；49 个 delivery wave 与 {matrix['counts']['wave_membership_records']} 条 atomic membership 保持原 schema。READER-060 保持引擎中立，D204-A 仅表示兼容回退 + gated 第二引擎 Spike；D205 仅将候选重筛为 Kokoro `zf_001` / `zm_010`、MOSS、ZipVoice HOLD，不选择第二引擎、exact profile 或 voice。Q03 nested evidence 只陈述底层报告能证明的静态事实。

## 目的

本目录把已接受的 G0 八域、`V2-REQ-BASE-001～005`、A1/A2/BASE 静态合同、S4/Q03 静态输入、D203 voice-pack / 后台 TTS 与 UI V2.0 R1 设计权威，收敛为 S6 规划包。原始 review `PASS_WITH_P2` 只作为 Q05 Open 的历史输入；D204 后 fresh scoped documentation reconfirm 已 PASS。每 future artifact change 仍须重新 review；当前 PASS 不是 G1、QA、runtime、设备或 Release PASS。

## 权威输入与读序

1. `AGENTS.md` 与 `docs/PROJECT_PLAN.md` §3/§5/§7：工作目录、写边界、交接和验证纪律。
2. `docs/V2_PLAN.md` §5.4、§5.10、§5.11.6～§5.11.13、§6、§8～§12、§13 当前 D203/D204/D205/R1/Q03 记录：外部 G0 445 / 376、D203 addendum 4 / normalized MVP 380、E0–E7、Q05 Closed/D204、D205 小引擎重筛、Q03 与 S6 ACTIVE。
3. `docs/TASKS.md` In Progress、`docs/DECISIONS.md` `V2-D057` / `V2-D198～D205` / Open Q03：当前状态真源。
4. `docs/UI_DESIGN_SYSTEM.md` V2 R1：五主题、RWD、九条交互、无障碍与 `UNVERIFIED_RUNTIME`。
5. `.planning/v2-g1/G1-S1～S5`、A1/A2、BASE-001/003/004、Q03-SBOM/NATIVE/NOTICE/TTS-PDF：只读压缩报告、README 与 manifest。

精确章节、路径、证据等级和“最多证明 / 不能证明”见 `source-index.json`。

## 产物入口

- `acceptance-matrix.json`：{matrix['counts']['external_source_nodes']} 个外部 source node、{matrix['counts']['project_addendum_source_nodes']} 个 D203 project addendum、{matrix['counts']['normalized_source_nodes']} 个归一化 source node；{matrix['counts']['external_acceptance_rows']} 条外部原子验收行 + {matrix['counts']['project_addendum_acceptance_rows']} 条 addendum，共 {matrix['counts']['normalized_acceptance_rows']} 条；外部 MVP {matrix['counts']['external_mvp_subitems']}、addendum {matrix['counts']['project_addendum_mvp_subitems']}、归一化 MVP {matrix['counts']['normalized_mvp_subitems']}；另含 {matrix['counts']['base_must_groups']} 个 BASE MUST 组和 {matrix['counts']['r1_cross_cutting_promises']} 条 R1 横向门。
- `row-semantics.json`：{matrix['counts']['explicit_semantic_records']} 条 schema v2 行级语义；含 actor、入口、动作组件、预期响应、源锚及显式状态 / 无障碍 / RWD gap。
- `wave-membership.json`：{matrix['counts']['wave_membership_records']} 条 atomic row membership、{matrix['counts']['wave_membership_edges']} 条 row→wave 边；每条记录带 reason、source capability、entry tags 与 action tags，builder 只做 join。
- `thresholds.json`：{matrix['counts']['thresholds']} 条只来自已接受需求、决策或 fixture 的可测阈值；`UNRESOLVED` 项明确保留。
- `g2-task-cards.json`：{matrix['counts']['g2_cards']} 张 planning umbrella、{matrix['counts']['g2_delivery_waves']} 个可执行 wave；每 wave 有 owner / promise_ids / 1 个结构化验收物，单 wave 最大边界项 {matrix['counts']['max_wave_boundary_entries']}，Q03 卡为 GATED。
- `risk-register.json`：P0/P1/P2/P3 = {risks['severity_counts']['P0']}/{risks['severity_counts']['P1']}/{risks['severity_counts']['P2']}/{risks['severity_counts']['P3']}，含跨域不变量、关闭证据和假绿门。
- `source-index.json`：权威章节与证据包的文件、行段、大小、SHA-256、证据上限。
- `CHECKPOINT.md`：30 万交接门后的自包含状态、完成 / 未完成、下一步与停止条件。
- `verify_s6_artifacts.py`：本地只读 verifier。
- `manifest.json`：除自身外的文件大小、SHA-256、LF / CRLF，由生成器最后写入。

## 数据字段定义

- `source_id` / `subitem_id`：接受来源的稳定节点与不可丢复合子项标识。
- `source_origin`：`EXTERNAL_G0` 或 `PROJECT_G0_ADDENDUM`；D203 的四条 Reader 行永不占外部 445 source ID。
- `promise_id`：S6 原子验收行稳定 ID；同一 source 行可拆出多个复合子项。
- `row_id`：`wave-membership.json` 的原子行 join key；每条 atomic row 恰有一条记录，`wave_ids[]` 保留一个或多个显式 wave。
- `source_capabilities` / `entry_tags` / `action_tags`：membership 与 wave 的能力、入口、动作相容证据；每个 capability 必须由具体文件 / FQCN 边界承接。
- `level`：`MUST / SHOULD / LATER / OUT / PROCESS`；等级来自接受矩阵，不做二次升降。
- `mvp`：只对 `MUST` 产品子项为真；外部 `376` 不含 BASE 组与 R1 横向门。
- `entry`：从显式行语义记录 join 的领域入口；精确组件未定处明确保留 G2 实例化缺口，不能据此声称 runtime。
- `operation`：由 `actor + entry_surface + action_verb + action_object + expected_response` 机械组合；不得以 requirement ID 或结果文本冒充动作。
- `observable_result`：接受源中的可观察结果。
- `states_accessibility_rwd`：由显式记录提供，带 `EXPLICIT_ROW_RECORD:<promise_id>` basis；未枚举分支必须写缺口并留给 G2 执行。
- `e0_e7_strength`：当前最高证据与最终要求；任何静态行均为 E0 输入，`runtime/device/packageExecuted=false`。
- `automation_fixture_evidence`：可用 fixture / static package 与不能证明项。
- `candidate_device_evidence`：S6 统一为 `NONE_AT_S6`；不得把历史 1.4 记录升级。
- `threshold_ids`：关联 `thresholds.json`。
- `uncovered`：真实未覆盖、需要 G2 或 Q03 关闭的证据。
- `owner` / `g2_card_ids`：可执行 owner 或明确的非 MVP / BLOCKED / PROCESS 状态。

## E0–E7 定义与证据上限

| 层级 | 证据 | 最多证明 |
|---|---|---|
| E0 | 静态契约 / fixture | 请求形状、schema、golden body、哈希与静态合同 |
| E1 | JVM property / state test | 纯状态、解析、身份、队列与缓存不变量 |
| E2 | Robolectric | Notification / WorkManager / Context 模拟接线 |
| E3 | Compose / UI test | UI 状态映射、语义节点与动作可达 |
| E4 | instrumentation | 真实 Room / WorkManager / ContentResolver / MediaSession 接线 |
| E5 | 可丢弃 profile 真机 | OEM、WAL/SHM、权限、重启与系统界面 |
| E6 | 独立写服 | 真实 Jellyfin 写受理、403、回读与清理 |
| E7 | 候选包核心旅程 | 用户承诺完整真实链路与跨界面等式 |

`E0–E4` 不能证明生产协议 / runtime / device；fake、static fixture、S5 静态覆盖、历史 1.4 记录、Worker `SUCCESS`、截图和“通知出现”都不能单独升级为 E7。核心用户旅程未执行时，不得用 `PASS_WITH_GAPS` 覆盖。

## R1 横向质量门摘要

- 五主题：Prism / Lumen / Nocturne / Monolith / 系统动态色；共享 IA、入口顺序、动作含义、状态语义、可访问名称与 48dp。
- RWD：Compact / Medium / Expanded / Large / ExtraLarge，外加折叠帧；按窗格有效宽度选择，变化时保留滚动、选择、焦点、进行中动作与返回路径。
- 无障碍：正文 ≥4.5:1、必要边界 ≥3:1、48×48dp、2dp 焦点环、18/18 可访问动作名称、200% 重排、Reduce Motion 0ms / 直接切换。
- 阅读正文局部主题不得污染外框、顶栏、按钮和弹层。
- Nocturne 基础圆角仅由 D201 窄覆盖 D057 为 8dp；Prism 12 / Lumen 16 / Monolith 8 / 动态色 12 与其他 D057 内容保持不变。

以上均为静态 / 合同输入。Compose、TalkBack、真实字体、动态色、折叠、权限弹窗与设备行为仍为 `UNVERIFIED_RUNTIME`。

## Q03 开放门

`V2-Q-G1-03` 保持 Open。至少以下均未关闭：

1. D204 已关闭 TTS engine strategy A，但第二引擎、exact profile / model / lexicon / rule、试听、许可与零 App 网络 / 后台锁屏 runtime 仍未关闭；Sherpa / Matcha 仅作兼容回退，不把 D204 写成 Q03 closure；
2. PDFium exact native revision、16 KB issue #56、Range / 206、取消与大文件；
3. candidate packaged SBOM、complete notices、source bundle / written offer、attestation / relink；
4. Media3 / decoder / libmpv / libass 双内核 load-order、符号解析、R8 与设备；
5. 真实候选包、设备、server runtime 与独立 QA。

Q03 与 Reader TTS 卡只能为 `GATED/BLOCKED` 输入；D204 只给出 engine strategy A，second engine、exact profile 与 Q03 均未通过，不能暗中冻结新引擎或 profile，也不能把 TTS 卡转为 READY。

## Q05 当前决策（Closed / Accepted / D204）

用户于 2026-11-04 原话“按推荐选择A。”，A 已接受，B/C 未选。`V2-D204` 只关闭 engine strategy：保留 Sherpa / Matcha 兼容回退，另立高表现力开源第二引擎 Android arm64 可行性 Spike；IndexTTS 2.5、CosyVoice 2、CosyVoice 3、GPT-SoVITS 均未选择，任一候选都没有已证实的官方 Android arm64 runtime。Matcha `zh-en` 只补样 / 兼容回退，Kokoro speaker 58 主观否决不整体淘汰家族；官方 v1.1-zh 模型页有 4 个样音可试听但本轮未执行。CosyVoice 2/3 仓库 / 模型权重许可页面冲突未解析。用户主观小说质量门仍未过，exact profile / voice、许可 / notice / hash、RAM / RTF / 首音、后台锁屏与 runtime 全部保持 Open。

## S6 完成与 scoped review 结论

本包保留以下历史静态交付门与原始 review `PASS_WITH_P2`；D204 后 `EV-S6` 当前状态为 `{EV_S6_STATUS}`：

- 外部 `445` 个 source node / `376` 个 MVP 子项保持可复算，D203 另列 4 个 project addendum / 4 个 MVP；归一化 source 449、MVP 380；
- 五个 BASE MUST 组与 R1 横向承诺均有 owner / card；
- 每个原子行有可观察结果、E0–E7 强度、fixture / device 边界、阈值引用、未覆盖和 G2 owner；
- G2 卡 `non_goals[] >= 2`，所有 MUST 承诺被至少一张卡承接；
- {matrix['counts']['normalized_acceptance_rows']} 条行语义均有动作组件与源锚；最终 live 口径为 {SELF_TEST_NEGATIVE_FIXTURE_COUNT} negative fixtures + {SELF_TEST_BASE_CHECK_COUNT} base checks + 1 计数输出 = {SELF_TEST_TOTAL_LIVE_CHECK_COUNT} checks。复核前为 55 + {SELF_TEST_BASE_CHECK_COUNT} + 1 = 66（相对基线 {SELF_TEST_BASELINE_CHECK_COUNT} 新增 6 negative）；本轮再以通用 evidence overclaim 与 READER-060 主引擎等价写法两条 negative 收敛，故最终相对基线累计新增 8 negative；全部真实执行并通过。
- Q03 / Q04 / UI / runtime / G1 状态等式成立；
- JSON、manifest、LF / CRLF 与本地 verifier 通过。

机械检查不能单独证明 460 条自然语言全部等价；D204 后 fresh scoped documentation review 已 PASS / no findings，当前记录不是 G1、runtime、device 或 independent QA PASS。每 future artifact change 仍须重新 review；原始 S6 P2 `__pycache__` 仍不得进入提交。

## 非目标

- 不运行 Gradle、ADB、设备、服务器、网络探测、独立 QA、Release 或生产实现。
- 不修改生产源码、Gradle、Manifest、resources、schema、其他证据目录、memory；本包只重建根 `.gitattributes` 白名单与 `G1-S6/`。
- 不选择 TTS 第二引擎、exact voice-pack profile、PDF 最终引擎、TTL / 排序 / 合并策略或任何未接受的产品取舍。
- 不把 S6 `PASS_WITH_P2`、静态 fixture、S5 覆盖、R1 设计权威或历史 1.4 记录写成 G1 / runtime / device / QA PASS。

## 停止条件

出现以下任一项立即停止并报告 `BLOCKED`，不得代签：下一步超出只读 / 静态 Second-engine L1 Spike scoping 与许可门；`__pycache__` 或其他非白名单产物进入提交；Q03 需要选型或用户拍板；把 D204-A 写成具体引擎已选或解锁 L2/L3；需要设备 / 服务器 / 网络 runtime；需要生产实现 / 独立 QA / Release；G0 计数、UI 权威或 Q03/Q04/Q05 状态漂移；manifest 不能复算。

## 下游 G2 消费方式

1. 按 `g2-task-cards.json.status` 过滤：`GATED_*` 不进入实现，`READY_FOR_G2_PLANNING` 仍需负责人批准。
2. 用 `covers_must_source_ids`、`covers_base_groups`、`covers_r1_promises` 锁定每卡承诺；不得删掉只挂在一张卡上的 MUST。
3. 从 `delivery_waves[].file_interface_boundaries` 读取具体文件 / FQCN，并用 `wave_dependencies`、`external_dependencies` 与 `stop_condition` 复建执行 DAG；不得把 planning umbrella 当实现波。
4. 从 `risk-register.json` 继承 P0–P3；卡内只能关闭有候选包 / fixture 证据的风险。
5. 实现完成后由负责人另派独立 QA；本卡输入不授权 QA、设备、服务器写或 Release。

## 复现与 verifier

```powershell
$env:PYTHONDONTWRITEBYTECODE='1'
python -X utf8 .planning/v2-g1/G1-S6/build_s6_artifacts.py
python -X utf8 .planning/v2-g1/G1-S6/verify_s6_artifacts.py
python -X utf8 .planning/v2-g1/G1-S6/verify_s6_artifacts.py --self-test
```

生成器只写 `G1-S6/`，不访问网络、设备或服务器。`row-semantics.json` 由显式 seed 与确定性、可回链源行的动作组合生成；`wave-membership.json` 是独立显式 join 表，builder 不得从行顺序、哈希或关键词重新分配。原始 review 只作为 Q05 Open 的历史；D204 后 fresh scoped documentation reconfirm 已 PASS。每 future artifact change 仍须重新 review，并重跑 verifier、{SELF_TEST_NEGATIVE_FIXTURE_COUNT} negative fixtures + {SELF_TEST_BASE_CHECK_COUNT} base checks + 1 计数输出（{SELF_TEST_TOTAL_LIVE_CHECK_COUNT} live checks）与新鲜度审计。
"""


def write_json(path: Path, value: Any) -> None:
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")


def checkpoint_text(
    matrix: dict[str, Any],
    risks: dict[str, Any],
    cards: list[dict[str, Any]],
) -> str:
    counts = matrix["counts"]
    wave_count = sum(card["delivery_wave_count"] for card in cards)
    max_boundary = max(card["max_wave_boundary_count"] for card in cards)
    risk_counts = risks["severity_counts"]
    return f"""# G1-S6 自包含 checkpoint（2026-11-04）

状态：`{STATUS}`

`EV-S6.status`：`{EV_S6_STATUS}`；历史 `PASS_WITH_P2` 仅适用于 Q05 当时 Open 的原始 review。

## 已完成

- 已完成本轮允许范围内的静态读取与压缩取证：G0 八域最终表、五个 BASE 组、A1/A2/BASE-001/003/004、S1–S5、Q03-SBOM/NATIVE/NOTICE/TTS-PDF、当前 DECISIONS/TASKS/UI R1。
- 已生成 {counts['normalized_source_nodes']} 个归一化 source node，其中外部 {counts['external_source_nodes']} + D203 addendum {counts['project_addendum_source_nodes']}；{counts['normalized_acceptance_rows']} 条原子验收行，其中外部 {counts['external_acceptance_rows']} + addendum {counts['project_addendum_acceptance_rows']}；归一化 MVP {counts['normalized_mvp_subitems']}（外部 {counts['external_mvp_subitems']} + addendum {counts['project_addendum_mvp_subitems']}）。复合节点未压平，流程 / LATER / OUT 保留可追踪。
- 已生成 {counts['explicit_semantic_records']} 条 schema v2 行语义记录；operation 由 actor / entry_surface / action_verb / action_object / expected_response 组合，MUSIC-003/014 与 NAV-004/005/006 已源特定修复，全部 action_object 元数据泄漏为 0；源中缺失的状态、无障碍与 RWD 分支显式标记 `UNSPECIFIED_IN_SOURCE`。
- 已生成 {counts['wave_membership_records']} 条独立 explicit row membership、{counts['wave_membership_edges']} 条 row→wave 边；builder 只按 `wave-membership.json` join，轮转推断已删除。MUSIC-003→本地扫描、MUSIC-014→queue-recovery、NAV-005→account-select/identity W2 均已修复，NAV-004/006 保持 W2。
- 已生成 {counts['base_must_groups']} 个 BASE MUST 组、{counts['r1_cross_cutting_promises']} 条 R1 横向承诺、{counts['thresholds']} 条阈值、{counts['g2_cards']} 张 planning umbrella、{wave_count} 个可执行 delivery wave；单 wave 最大边界项 {max_boundary}。
- {wave_count} 个 wave 均有专业 owner、非空真实 promise_ids、1 个结构化验收物、显式 capability / entry / action tags 与具体文件 / FQCN 边界；矩阵 {sum(1 for row in matrix['acceptance_rows'] + matrix['base_groups'] + matrix['r1_quality_rows'] if row.get('g2_wave_ids'))} 条 membership 已映射到至少一个 wave。
- card 级 `EV-S6` 自证引用为 0；对外证据保留具体证据 ID 或显式 `NO_EVIDENCE_YET` 缺口。
- 风险登记 P0/P1/P2/P3=`{risk_counts['P0']}/{risk_counts['P1']}/{risk_counts['P2']}/{risk_counts['P3']}`。
- `verify_s6_artifacts.py` 与 {counts['wave_membership_records']} 条 explicit membership、{counts['wave_membership_edges']} 条边的语义 / 能力 / 入口 / 动作 / 边界门禁及 {SELF_TEST_NEGATIVE_FIXTURE_COUNT} 个负向 self-test 已在 D204 review 修复后重新生成并执行；manifest、SHA-256 与 LF / CRLF 由最终产物复算。
- `EV-S6.status` 当前为 `{EV_S6_STATUS}`；原始 S6 scoped review 仍为 `PASS_WITH_P2` 且限定 Q05 当时 Open，唯一 P2 `G1-S6/__pycache__/*.pyc` 保持排除。D204 后 fresh scoped documentation reconfirm 已 PASS / no findings；这不是 G1、QA、runtime 或 Release PASS，future artifact change 仍须重新 review。

## 未完成 / 不可声明

- S6 通过不等于 G1 PASS；G1 仍为 `NOT_PASSED`，runtime 仍为 `UNVERIFIED`。
- Q05 已由 D204 关闭 engine strategy，但第二引擎、exact profile / 试听 / 许可、后台锁屏零网络 runtime 仍未关闭；Q03 仍 Open，PDFium 16 KB / Range / native revision / notice、packaged SBOM / source offer、attestation / relink 均未关闭。Sherpa / Matcha 兼容回退不等于 Q03 关闭。
- 未执行实现、Gradle、ADB、设备、服务器、网络、独立 QA、Release；不得写 G1/runtime/device/QA PASS。
- Base-003 TTL / capacity / prefetch、BASE-004 重复 / 部分导入、搜索排序 / 跨源呈现等产品策略仍未由用户拍板。

## 文件指针

- `README.md`
- `acceptance-matrix.json`
- `row-semantics.json`
- `wave-membership.json`
- `thresholds.json`
- `g2-task-cards.json`
- `risk-register.json`
- `source-index.json`
- `manifest.json`
- `build_s6_artifacts.py`
- `verify_s6_artifacts.py`

## 已修正失败

- 最终 fresh reviewer 三项 P2 RED：自测曾把 negative fixture 数误当总检查数；通用 evidence `can_prove` 未阻断 chosen engine / Android runtime / production overclaim；READER-060 只识别旧 Sherpa 句式。现统一 live 计数为 {SELF_TEST_NEGATIVE_FIXTURE_COUNT} negative + {SELF_TEST_BASE_CHECK_COUNT} base + 1 计数 = {SELF_TEST_TOTAL_LIVE_CHECK_COUNT}，并新增通用 overclaim 与三处主引擎等价注入门。
- 第二轮 fresh reviewer RED：根 `.gitattributes` 未自稳定、Q03 nested `can_prove` 越权承载 D204、EV-S6 仍以无条件 pass 作当前状态；现新增自稳定 / eol 负例、底层报告语义交叉核对与历史 Q05_OPEN 双字段门。
- 第五轮 reviewer RED：builder 按轮转分配导致 MUSIC-003 挂到 large-queue、MUSIC-014 挂 large-queue/playlist、NAV-005 挂 route/token；现改为独立 explicit membership，新增 8 个语义变异门与全量 capability mismatch 扫描，self-test 注入总数 29。
- 第四轮 reviewer RED：MUSIC-003 歌词入口错域、MUSIC-014 系统媒体控制错域、NAV-004/005/006 泛化同动作、action_object 源码/决策元数据泄漏，已转为 3 个新增真实变异门与全量扫描门；self-test 注入总数 21。
- 第三轮 reviewer RED：operation 结果包装、card `EV-S6` 自证、wave 缺 owner / promise / 结构化验收物、9 边界与悬空外部依赖均已转为 verifier 与注入负例断言。
- 第二轮 reviewer RED：32 行 `DOMAIN_SEMANTICS`、已知 4 条入口错位、旧 verifier 只检查 basis / `row_specific`、G2 大目录通配、8 条 NAV 来源数字噪声与名称门错挂 focus ring，均已转为行为断言。
- 首轮 reviewer RED：456/456 行引用 `EV-S6`、`EV-DOWNLOAD` 未定义、卡/证据 DAG 缺失、READER-057 EPUB owner 错挂 PDF、对比度统一写成 130 pair。
- READER 表含一条 `G0 状态轴`说明行；生成器已过滤非 `V2-REQ-*` 行。
- NAV 表将来源与解读合并为一列；生成器已按该表形读取。
- S6 自身证据行不自引用矩阵哈希；由 manifest 对实际文件字节统一校验。
- 无适用阈值的行使用显式 `NONE_IN_ACCEPTED_SOURCE`；G2 卡输出 `acceptance_criteria[]`；BASE 卡可只覆盖 BASE 而不强绑外部八域。

## Scoped review 结论与残余门

**结论**：原始 S6 fresh scoped review `PASS_WITH_P2` 历史保留，限定 Q05 当时 Open。D204 后独立 fresh scoped documentation review 已 PASS；D205 current revision 随后独立 fresh scoped reconfirm 为 PASS / no findings。该结论不表示 G1、QA、runtime 或 Release PASS。

**下一步入口**：D205 current revision 已复审 PASS，当前无未完成 docs/artifact review；下一步是在用户单独授权后做 exact archive 下载与只读内容校验。D205 候选不冻结 engine/profile；Q03、runtime、设备与 G1 仍需独立证据和授权，不得代签 QA 或推进 G2。

**停止条件**：下一步不得启动 Q03 runtime、第二引擎 / exact profile 实际选型、设备、生产实现、G2、独立 QA 或 Release；任何计数不等、显式语义 / 源锚不可复算、`__pycache__` 回流、D204/D205/Q03/Q04/UI/G1 状态漂移、L2/L3 解锁、manifest / LF 不复算，或需要写授权范围外文件时停止并报告 `BLOCKED`。

## 上下文水位

本文件不固化易漂移的 token 数；负责人收口时必须从当前会话状态工具读取实际水位。
达到 25 万收缩、30 万写 checkpoint 并停止；下一步若超出只读残余门审计或用户决策准备，必须由负责人另建对应专业会话并取得明确授权。
"""


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    v2_text = read_text(V2_PATH)
    v2_lines = v2_text.splitlines()
    semantics_by_id = load_row_semantics()
    semantics_by_id.update(build_d203_semantic_seeds(v2_lines))
    if READER_060_PROMISE_ID not in semantics_by_id:
        raise ValueError(f"{READER_060_PROMISE_ID} explicit seed is missing")
    semantics_by_id[READER_060_PROMISE_ID] = build_d204_reader_060_semantic_seed(
        v2_lines,
        semantics_by_id[READER_060_PROMISE_ID],
    )
    rebase_semantic_anchor_lines(v2_lines, semantics_by_id)

    nodes, rows, domain_counts = build_external_nodes(v2_lines, semantics_by_id)
    semantic_document = enrich_semantics(rows, semantics_by_id)
    write_json(SEMANTICS_PATH, semantic_document)
    base_rows = parse_base_groups(v2_lines)
    r1_rows = build_r1_rows()
    normalize_wave_membership_registry()
    membership_by_row = load_wave_membership()
    cards = build_card_outputs(nodes, rows, base_rows, r1_rows, membership_by_row)
    matrix = matrix_document(
        nodes,
        rows,
        base_rows,
        r1_rows,
        domain_counts,
        membership_by_row,
    )
    validate_reader_060_engine_neutral_outputs(
        semantic_document,
        matrix,
        cards,
    )
    thresholds = {
        "schema_version": "g1-s6-thresholds/v1",
        "status": STATUS,
        "rules": [
            "Every threshold must cite accepted requirement / decision / fixture.",
            "Fixture-only small values are not product defaults.",
            "UNRESOLVED means the accepted source does not provide a concrete value.",
            "Passing a static threshold does not prove runtime, device or production protocol.",
        ],
        "count": len(THRESHOLD_SPECS),
        "thresholds": build_threshold_rows(),
    }
    cards_doc = {
        "schema_version": "g1-s6-g2-task-cards/v1",
        "status": STATUS,
        "g2_gate": "NOT_AUTHORIZED",
        "rules": {
            "card_status_not_approval": True,
            "must_cover_all_must": True,
            "q03_cards_are_gated": True,
            "independent_qa_comes_after_implementation": True,
            "wave_membership_source": "wave-membership.json",
        },
        "count": len(cards),
        "cards": cards,
    }
    risks = risk_document(cards)
    source_index = build_source_index(matrix)

    write_json(OUT / "acceptance-matrix.json", matrix)
    write_json(OUT / "thresholds.json", thresholds)
    write_json(OUT / "g2-task-cards.json", cards_doc)
    write_json(OUT / "risk-register.json", risks)
    write_json(OUT / "source-index.json", source_index)
    (OUT / "README.md").write_text(readme_text(matrix, risks, cards), encoding="utf-8", newline="\n")
    (OUT / "CHECKPOINT.md").write_text(
        checkpoint_text(matrix, risks, cards),
        encoding="utf-8",
        newline="\n",
    )

    files = [
        "README.md",
        "CHECKPOINT.md",
        "acceptance-matrix.json",
        "row-semantics.json",
        "wave-membership.json",
        "thresholds.json",
        "g2-task-cards.json",
        "risk-register.json",
        "source-index.json",
        "build_s6_artifacts.py",
        "verify_s6_artifacts.py",
    ]
    entries = []
    for rel in files:
        path = OUT / rel
        if not path.exists():
            raise FileNotFoundError(path)
        data = path.read_bytes()
        lf = data.count(b"\n")
        crlf = data.count(b"\r\n")
        entries.append(
            {
                "path": rel,
                "bytes": len(data),
                "sha256": sha256_bytes(data),
                "lf": lf,
                "crlf": crlf,
                "eol": "LF" if crlf == 0 else "CRLF",
            }
        )
    manifest = {
        "schema_version": "g1-s6-manifest/v1",
        "task_id": "G1-S6",
        "status": STATUS,
        "generated_on": "2026-11-04",
        "algorithm": "SHA-256",
        "self_excluded": True,
        "eol_policy": "UTF-8 no BOM; LF only",
        "file_count_excluding_manifest": len(entries),
        "total_bytes_excluding_manifest": sum(item["bytes"] for item in entries),
        "entries": entries,
    }
    write_json(OUT / "manifest.json", manifest)


if __name__ == "__main__":
    main()
