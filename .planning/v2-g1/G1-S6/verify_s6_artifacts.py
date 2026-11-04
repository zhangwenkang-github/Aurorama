#!/usr/bin/env python3
"""Read-only verifier for G1-S6 reviewed planning artifacts."""

from __future__ import annotations

import copy
import hashlib
import json
import re
import sys
from collections import Counter
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
OUT = Path(__file__).resolve().parent
EXPECTED_STATUS = (
    "S6_SCOPED_REVIEW_PASS_WITH_P2 / G1_NOT_PASSED / "
    "Q03_OPEN / Q05_OPEN / Q04_CLOSED_D201 / RUNTIME_UNVERIFIED"
)
SEMANTIC_SCHEMA = "g1-s6-row-semantics/v2"
MEMBERSHIP_SCHEMA = "g1-s6-wave-membership/v1"
UNSPECIFIED = "UNSPECIFIED_IN_SOURCE"
OPERATION_MODES = {
    "USER_ACTION",
    "ENGINEERING_CHECK",
    "NEGATIVE_SCOPE",
    "STATE_AUDIT",
}
ACTION_COMPONENTS = (
    "actor",
    "entry_surface",
    "action_verb",
    "action_object",
    "expected_response",
    "operation_mode",
    "action_source_excerpt",
)
D203_EXPECTED_SEMANTICS = {
    "S6-P-READER-087": {
        "entry": "阅读器 TTS 控制条 / 锁屏 / 系统媒体控制",
        "action_verb": "核对后台锁屏 TTS 生命周期",
        "action_object_prefix": "后台 / 锁屏 TTS 生命周期、通知与 MediaSession 真值",
        "waves": ["G2-READER-TTS-GATED-W3"],
        "response_tokens": [
            "离开阅读页",
            "后台",
            "锁屏",
            "Activity",
            "ViewModel",
            "销毁",
            "进程",
            "重启",
            "暂停",
            "最后稳定句子",
            "pack",
            "通知",
            "UI",
            "MediaSession",
        ],
    },
    "S6-P-READER-088": {
        "entry": "阅读器 TTS 音色列表 / 导入 / 逐书覆盖",
        "action_verb": "导入、选择并配置音色",
        "action_object_prefix": "voice-pack 追加导入、幂等身份与逐书覆盖",
        "waves": [
            "G2-Q03-SUPPLY-CHAIN-GATED-W1",
            "G2-Q03-SUPPLY-CHAIN-GATED-W2",
            "G2-READER-TTS-GATED-W4",
        ],
        "response_tokens": [
            "多音色",
            "追加",
            "替换",
            "相同 pack / profile",
            "同名异内容",
            "新 profile",
            "全局默认",
            "逐书覆盖",
            "设备级",
        ],
    },
    "S6-P-READER-089": {
        "entry": "阅读器 TTS 音色列表 / 删除 / 恢复",
        "action_verb": "删除或恢复音色并核对回退",
        "action_object_prefix": "voice-pack 删除、回退、恢复与引用清理",
        "waves": ["G2-READER-TTS-GATED-W5"],
        "response_tokens": [
            "非活动音色",
            "活动音色",
            "停止",
            "回退",
            "有效内置 profile",
            "引用",
            "文件",
            "派生数据",
            "待重试",
            "隐藏",
            "恢复",
            "悬空引用",
        ],
    },
    "S6-P-READER-090": {
        "entry": "阅读器 TTS / 音乐 / 系统媒体控制",
        "action_verb": "控制音频 owner 并核对系统控制",
        "action_object_prefix": "单音频 owner、音乐互斥与系统媒体控制真值",
        "waves": ["G2-READER-TTS-GATED-W6"],
        "response_tokens": [
            "至多一个活动音频 owner",
            "MediaSession",
            "暂停音乐",
            "保留队列",
            "不自动恢复",
            "先停 TTS",
            "audio focus",
            "noisy",
            "重启",
            "通知",
        ],
    },
}
D203_GENERIC_FORBIDDEN = (
    "书架 / 书籍操作菜单",
    "导入或导出并核对",
    "执行用户路径并核对",
)
GENERIC_ACTORS = {
    "按需求",
    "需求",
    "操作者",
    "执行者",
    "用户或工程门",
    "UNKNOWN",
    "TEMPLATE",
}
GENERIC_ACTION_VERBS = {
    "执行",
    "按需求 ID 执行",
    "执行并观察",
    "观察",
    "验证",
}
LEGACY_OPERATION = re.compile(
    r"^按(?:需求 ID|V2-REQ-[A-Z]+-\d{3})\s*执行并观察[：:]"
)
ID_OPERATION_PREFIX = re.compile(r"^\s*按\s*V2-REQ-[A-Z]+-\d{3}\b")
ACTION_METADATA_PATTERNS = (
    re.compile(r"\bV2-D\d{3}\b"),
    re.compile(r"(?<![A-Za-z0-9])D\d{3}(?![A-Za-z0-9])"),
    re.compile(r"\b\d{3,5}\s*[–—-]\s*\d{3,5}\b"),
    re.compile(r"(?<![\d.])(?:1[0-2]\d{2})(?![\d.]|p\b|[×x]\b|dp\b|ms\b)"),
)


def fail(message: str) -> None:
    print(f"FAIL: {message}")
    raise SystemExit(1)


def load_json(name: str) -> dict:
    path = OUT / name
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except Exception as exc:  # noqa: BLE001
        fail(f"{name} JSON parse failed: {exc}")


def load_json_optional(name: str) -> dict:
    path = OUT / name
    if not path.exists():
        return {}
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except Exception as exc:  # noqa: BLE001
        fail(f"{name} JSON parse failed: {exc}")


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest().upper()


def require_nonempty(row: dict, fields: list[str], row_id: str) -> None:
    for field in fields:
        if field not in row:
            fail(f"{row_id} missing field {field}")
        value = row[field]
        if value is None:
            fail(f"{row_id} field {field} is null")
        if isinstance(value, (str, list, dict)) and len(value) == 0:
            fail(f"{row_id} field {field} is empty")


def d203_addendum_errors(matrix: dict) -> list[str]:
    errors: list[str] = []
    expected_ids = {f"V2-REQ-READER-{number:03d}" for number in range(87, 91)}
    nodes = matrix.get("source_nodes", [])
    rows = matrix.get("acceptance_rows", [])
    external_nodes = [node for node in nodes if node.get("source_origin") == "EXTERNAL_G0"]
    addendum_nodes = [
        node for node in nodes if node.get("source_origin") == "PROJECT_G0_ADDENDUM"
    ]
    external_rows = [row for row in rows if row.get("source_origin") == "EXTERNAL_G0"]
    addendum_rows = [
        row for row in rows if row.get("source_origin") == "PROJECT_G0_ADDENDUM"
    ]
    if len(nodes) != 449 or len(external_nodes) != 445 or len(addendum_nodes) != 4:
        errors.append(
            f"D203 source origin counts normalized={len(nodes)} "
            f"external={len(external_nodes)} addendum={len(addendum_nodes)}"
        )
    if len(rows) != 460 or len(external_rows) != 456 or len(addendum_rows) != 4:
        errors.append(
            f"D203 row origin counts normalized={len(rows)} "
            f"external={len(external_rows)} addendum={len(addendum_rows)}"
        )
    if {node.get("source_id") for node in addendum_nodes} != expected_ids:
        errors.append("D203 source IDs must be exactly READER-087..090")
    if {row.get("parent_source_id") for row in addendum_rows} != expected_ids:
        errors.append("D203 row IDs must be exactly READER-087..090")
    if any(row.get("counted_in_external_445") for row in addendum_rows):
        errors.append("D203 rows must not be counted in external 445")
    if any(row.get("counted_in_external_376") for row in addendum_rows):
        errors.append("D203 MVP rows must not be counted in external 376")
    return errors


def d203_semantic_contract_errors(
    registry: dict,
    rows: list[dict],
    cards: list[dict],
    risks: dict,
) -> list[str]:
    errors: list[str] = []
    records = {
        record.get("promise_id"): record
        for record in registry.get("rows", [])
        if isinstance(record, dict)
    }
    rows_by_id = {row.get("promise_id"): row for row in rows}
    waves = {
        wave.get("id"): wave
        for card in cards
        for wave in card.get("delivery_waves", [])
    }
    for promise_id, expected in D203_EXPECTED_SEMANTICS.items():
        record = records.get(promise_id)
        row = rows_by_id.get(promise_id)
        if record is None or row is None:
            errors.append(f"{promise_id} D203 semantic/row record missing")
            continue
        if record.get("entry") != expected["entry"] or record.get(
            "entry_surface"
        ) != expected["entry"]:
            errors.append(
                f"{promise_id} entry mismatch: {record.get('entry')!r} != {expected['entry']!r}"
            )
        if record.get("entry_status") != "SOURCE_GROUNDED":
            errors.append(f"{promise_id} entry_status must be SOURCE_GROUNDED")
        if record.get("action_verb") != expected["action_verb"]:
            errors.append(
                f"{promise_id} action_verb mismatch: "
                f"{record.get('action_verb')!r} != {expected['action_verb']!r}"
            )
        if not str(record.get("action_object", "")).startswith(
            expected["action_object_prefix"]
        ):
            errors.append(
                f"{promise_id} action_object lacks specialized prefix "
                f"{expected['action_object_prefix']!r}"
            )
        operation = str(record.get("operation", ""))
        if expected["entry"] not in operation or expected["action_verb"] not in operation:
            errors.append(f"{promise_id} operation lacks specialized entry/action")
        for forbidden in D203_GENERIC_FORBIDDEN:
            if forbidden in record.get("entry", "") or forbidden in operation:
                errors.append(f"{promise_id} uses generic semantic {forbidden!r}")

        response = str(row.get("observable_result", ""))
        if record.get("expected_response") != response:
            errors.append(f"{promise_id} expected_response differs from matrix row")
        missing_tokens = [
            token for token in expected["response_tokens"] if token not in response
        ]
        if missing_tokens:
            errors.append(
                f"{promise_id} expected_response missing tokens: {missing_tokens}"
            )
        if row.get("entry") != expected["entry"]:
            errors.append(f"{promise_id} matrix entry mismatch")
        if row.get("operation") != operation:
            errors.append(f"{promise_id} matrix operation mismatch")
        if "G2-READER-TTS-GATED" not in row.get("g2_card_ids", []):
            errors.append(f"{promise_id} lacks G2-READER-TTS-GATED task card")
        if sorted(row.get("g2_wave_ids", [])) != sorted(expected["waves"]):
            errors.append(
                f"{promise_id} wave mismatch: "
                f"{row.get('g2_wave_ids')} != {expected['waves']}"
            )
        for wave_id in expected["waves"]:
            wave = waves.get(wave_id)
            if wave is None:
                errors.append(f"{promise_id} missing task-card wave {wave_id}")
            elif promise_id not in wave.get("promise_ids", []):
                errors.append(f"{promise_id} absent from task-card wave {wave_id}")

    risk = next(
        (item for item in risks.get("risks", []) if item.get("id") == "R-S6-P1-07"),
        None,
    )
    if risk is None:
        errors.append("R-S6-P1-07 missing")
    else:
        risk_text = "；".join(
            [
                str(risk.get("title", "")),
                str(risk.get("impact", "")),
                *[str(item) for item in risk.get("closure_evidence", [])],
            ]
        )
        if "V2-D203" not in risk.get("source", []):
            errors.append("R-S6-P1-07 must cite V2-D203")
        if "V2-Q-G1-05" not in risk.get("source", []):
            errors.append("R-S6-P1-07 must cite V2-Q-G1-05")
        if "G2-Q03-SUPPLY-CHAIN-GATED" not in str(risk.get("gate", "")):
            errors.append("R-S6-P1-07 gate must cover G2-Q03-SUPPLY-CHAIN-GATED")
        if "engine strategy" not in str(risk.get("title", "")):
            errors.append("R-S6-P1-07 title must name engine strategy")
        if "exact profile" not in str(risk.get("title", "")):
            errors.append("R-S6-P1-07 title must name exact profile")
        for token in ("试听", "model card", "许可", "notice", "hash"):
            if token not in risk_text:
                errors.append(f"R-S6-P1-07 closure/title lacks {token}")
        for stale in ("引擎、voice 与模型资产许可尚未选择", "用户引擎 / voice 决策"):
            if stale in risk_text:
                errors.append(f"R-S6-P1-07 retains stale engine-not-selected claim: {stale}")

    cards_by_id = {card.get("id"): card for card in cards}

    def card_text(card: dict) -> str:
        return "；".join(
            [
                str(card.get("objective", "")),
                *[str(item) for item in card.get("non_goals", [])],
                *[str(item) for item in card.get("acceptance_criteria", [])],
                *[str(item) for item in card.get("boundaries", [])],
                str(card.get("stop", "")),
                *[
                    str(wave.get("objective", ""))
                    for wave in card.get("delivery_waves", [])
                ],
            ]
        )

    q03 = cards_by_id.get("G2-Q03-SUPPLY-CHAIN-GATED")
    if q03 is None:
        errors.append("G2-Q03-SUPPLY-CHAIN-GATED card missing")
    else:
        q03_text = card_text(q03)
        for token in (
            "Sherpa-ONNX v2.0.1 + ONNX Runtime",
            "兼容回退候选",
            "V2-Q-G1-05",
            "exact voice-pack/profile",
            "逐资产许可 / model card / notice / hash / provenance",
            "packaged supply chain",
            "PDF 引擎选择仍按既有 Q03 门",
        ):
            if token not in q03_text:
                errors.append(f"G2-Q03 card lacks D203 token: {token}")
        if "任何引擎 / voice / PDF 选择必须先由用户关 Q03 决策" in q03_text:
            errors.append("G2-Q03 card retains stale engine/voice/PDF selection decision")
        if "R-S6-P1-07" not in q03.get("risks", []):
            errors.append("G2-Q03 card must map R-S6-P1-07")

    tts = cards_by_id.get("G2-READER-TTS-GATED")
    if tts is None:
        errors.append("G2-READER-TTS-GATED card missing")
    else:
        w1 = next(
            (
                wave
                for wave in tts.get("delivery_waves", [])
                if wave.get("id") == "G2-READER-TTS-GATED-W1"
            ),
            None,
        )
        if w1 is None:
            errors.append("G2-READER-TTS-GATED-W1 missing")
        else:
            w1_text = str(w1.get("objective", ""))
            for token in (
                "voice-pack / 后台锁屏",
                "Sherpa 兼容回退",
                "V2-Q-G1-05",
                "exact profile",
                "试听",
                "许可",
                "matrix",
            ):
                if token not in w1_text:
                    errors.append(f"G2-READER-TTS-GATED-W1 lacks D203 token: {token}")
            if "消费用户引擎 / voice / 模型许可决策" in w1_text:
                errors.append("G2-READER-TTS-GATED-W1 retains stale engine/voice decision")

    reader_core = cards_by_id.get("G2-READER-CORE")
    if reader_core is None:
        errors.append("G2-READER-CORE card missing")
    else:
        reader_text = card_text(reader_core)
        if "V2-Q-G1-05" not in reader_text:
            errors.append("G2-READER-CORE must gate TTS on V2-Q-G1-05")
        for stale in (
            "选择最终 TTS 引擎 / voice",
            "选中具体 PDF/TTS 引擎",
        ):
            if stale in reader_text:
                errors.append(f"G2-READER-CORE retains stale TTS engine selection: {stale}")
    return errors


def tts_w1_action_tag_errors(cards: list[dict], membership_doc: dict) -> list[str]:
    errors: list[str] = []
    expected_tag = "VERIFY_FIXED_ENGINE_AND_LICENSE_INPUTS"
    expected_rows = {
        "S6-P-READER-060",
        "S6-P-READER-061",
        "S6-P-READER-062",
        "S6-P-READER-063",
    }
    forbidden_engine_selection = (
        "SELECT_ENGINE",
        "CHOOSE_ENGINE",
        "PICK_ENGINE",
        "ENGINE_SELECTION",
    )

    tts = next(
        (card for card in cards if card.get("id") == "G2-READER-TTS-GATED"),
        None,
    )
    w1 = next(
        (
            wave
            for wave in (tts or {}).get("delivery_waves", [])
            if wave.get("id") == "G2-READER-TTS-GATED-W1"
        ),
        None,
    )
    if w1 is None:
        errors.append("G2-READER-TTS-GATED-W1 missing")
    else:
        action_tags = list(w1.get("action_tags", []))
        if expected_tag not in action_tags:
            errors.append(f"G2-READER-TTS-GATED-W1 lacks {expected_tag}")
        forbidden = [
            tag
            for tag in action_tags
            if any(token in tag for token in forbidden_engine_selection)
        ]
        if forbidden:
            errors.append(
                f"G2-READER-TTS-GATED-W1 uses engine-selection action tags: {forbidden}"
            )

    memberships = membership_doc.get("memberships", [])
    w1_memberships = [
        item
        for item in memberships
        if "G2-READER-TTS-GATED-W1" in item.get("wave_ids", [])
    ]
    actual_rows = {item.get("row_id") for item in w1_memberships}
    if actual_rows != expected_rows or len(w1_memberships) != 4:
        errors.append(
            "G2-READER-TTS-GATED-W1 membership rows must be exactly "
            "S6-P-READER-060..063"
        )
    for item in w1_memberships:
        row_id = item.get("row_id")
        action_tags = list(item.get("action_tags", []))
        if expected_tag not in action_tags:
            errors.append(f"{row_id} W1 membership lacks {expected_tag}")
        forbidden = [
            tag
            for tag in action_tags
            if any(token in tag for token in forbidden_engine_selection)
        ]
        if forbidden:
            errors.append(f"{row_id} W1 membership uses engine-selection tag {forbidden}")
        if "TTS engine and voice decision" in str(item.get("reason", "")):
            errors.append(f"{row_id} W1 membership reason retains engine decision")
    return errors


def has_cycle(graph: dict[str, list[str]]) -> bool:
    visiting: set[str] = set()
    visited: set[str] = set()

    def visit(node: str) -> bool:
        if node in visiting:
            return True
        if node in visited:
            return False
        visiting.add(node)
        for dep in graph.get(node, []):
            if dep in graph and visit(dep):
                return True
        visiting.remove(node)
        visited.add(node)
        return False

    return any(visit(node) for node in graph)


def canonical_anchor_text(value: str) -> str:
    return re.sub(r"[\s|`*]+", "", value)


def compose_operation(record: dict) -> str:
    actor = record.get("actor", "")
    entry = record.get("entry_surface", "")
    verb = record.get("action_verb", "")
    action_object = record.get("action_object", "")
    expected = record.get("expected_response", "")
    mode = record.get("operation_mode")
    if mode == "USER_ACTION":
        return f"{actor}在{entry}，{verb}“{action_object}”；应观察到：{expected}"
    if mode == "ENGINEERING_CHECK":
        return f"{actor}在{entry}审查并{verb}“{action_object}”；交付证据应显示：{expected}"
    if mode == "NEGATIVE_SCOPE":
        return f"{actor}在{entry}确认“{action_object}”保持关闭，并{verb}；应观察到：{expected}"
    if mode == "STATE_AUDIT":
        return f"{actor}从{entry}触发并{verb}“{action_object}”；应观察到：{expected}"
    return ""


def semantic_shape_metrics(registry: dict) -> dict[str, int]:
    records = registry.get("rows", [])
    legacy_hits = sum(
        1
        for record in records
        if isinstance(record.get("operation"), str)
        and LEGACY_OPERATION.search(record["operation"])
    )
    id_wrapper_hits = sum(
        1
        for record in records
        if LEGACY_OPERATION.search(str(record.get("operation", "")))
        or ID_OPERATION_PREFIX.search(str(record.get("operation", "")))
    )
    missing_components = sum(
        1
        for record in records
        if any(
            not isinstance(record.get(field), str) or not record.get(field, "").strip()
            for field in ACTION_COMPONENTS
        )
    )
    metadata_hits = sum(
        1
        for record in records
        if any(
            pattern.search(str(record.get("action_object", "")))
            for pattern in ACTION_METADATA_PATTERNS
        )
    )
    record_by_id = {
        record.get("promise_id"): record for record in records if record.get("promise_id")
    }
    reviewed_hits = 0
    music_003 = record_by_id.get("S6-P-MUSIC-003", {})
    if not (
        "本地" in music_003.get("entry_surface", "")
        and any(
            token in music_003.get("action_verb", "")
            for token in ("扫描", "提取", "写入")
        )
        and "元数据" in music_003.get("action_object", "")
        and "歌词" not in music_003.get("entry_surface", "")
    ):
        reviewed_hits += 1
    music_014 = record_by_id.get("S6-P-MUSIC-014", {})
    if not (
        "队列" in music_014.get("entry_surface", "")
        and any(
            token in music_014.get("action_verb", "")
            for token in ("编排", "重排", "加入队列", "调整队列")
        )
        and "队列" in music_014.get("action_object", "")
    ):
        reviewed_hits += 1
    nav = [record_by_id.get(f"S6-P-NAV-00{index}", {}) for index in (4, 5, 6)]
    if len(
        {
            (record.get("entry_surface"), record.get("action_verb"))
            for record in nav
        }
    ) != 3:
        reviewed_hits += 1
    unspecified = {
        dimension: sum(
            1
            for record in records
            if UNSPECIFIED
            in record.get("states_accessibility_rwd", {}).get(dimension, [])
        )
        for dimension in ("states", "accessibility", "rwd")
    }
    return {
        "record_count": len(records),
        "legacy_formula_hits": legacy_hits,
        "operation_id_wrapper_hits": id_wrapper_hits,
        "component_missing": missing_components,
        "action_object_metadata_hits": metadata_hits,
        "reviewed_semantic_hits": reviewed_hits,
        **{f"unspecified_{key}": value for key, value in unspecified.items()},
    }


KNOWN_SEMANTIC_RULES = {
    "S6-P-MUSIC-001": {
        "required": ("音乐",),
        "forbidden": ("搜索", "账户"),
    },
    "S6-P-NAV-001": {
        "required": ("导航", "一级"),
        "forbidden": ("音乐播放器", "视频播放器", "安装器"),
    },
    "S6-P-WRITE-006": {
        "required": ("写", "收藏", "进度", "歌单"),
        "forbidden": ("安装器", "应用更新"),
    },
    "S6-P-DOWNLOAD-057": {
        "required": ("下载",),
        "forbidden": ("账户", "安装器"),
    },
}


DOMAIN_ENTRY_CONTRADICTIONS = {
    "ARCH": ("音乐播放器", "视频播放器", "下载中心"),
    "READER": ("安装器", "账户页"),
    "WRITE": ("安装器", "应用更新"),
    "MUSIC": ("搜索", "安装器", "账户页"),
    "DOWNLOAD": ("账户页", "安装器"),
    "VIDEO": ("安装器", "账户页"),
    "NAV": ("安装器",),
}


def semantic_registry_errors(
    registry: dict,
    rows: list[dict],
    nodes: list[dict],
    source_index: dict,
) -> list[str]:
    errors: list[str] = []
    if not registry:
        return ["row-semantics.json is missing"]
    if registry.get("schema_version") != SEMANTIC_SCHEMA:
        errors.append(f"unexpected schema_version={registry.get('schema_version')!r}")
    records = registry.get("rows", [])
    if not isinstance(records, list):
        return ["row-semantics rows must be a list"]

    row_by_id = {row["promise_id"]: row for row in rows}
    node_by_id = {node["source_id"]: node for node in nodes}
    record_by_id = {
        record.get("promise_id"): record
        for record in records
        if isinstance(record, dict) and record.get("promise_id")
    }
    if len(record_by_id) != len(records):
        errors.append(f"duplicate or blank semantic record IDs: rows={len(records)} unique={len(record_by_id)}")

    missing = sorted(set(row_by_id) - set(record_by_id))
    extra = sorted(set(record_by_id) - set(row_by_id))
    if missing:
        errors.append(f"rows without explicit semantic record={len(missing)} sample={missing[:8]}")
    if extra:
        errors.append(f"orphan semantic records={len(extra)} sample={extra[:8]}")

    accepted_doc_paths = {
        item["path"] for item in source_index.get("document_sources", [])
    }
    document_lines: dict[str, list[str]] = {}
    value_placeholders = {
        "",
        "UNKNOWN",
        "TEMPLATE",
        "DOMAIN_TEMPLATE",
        "DOMAIN_SEMANTICS",
        "GENERIC",
    }

    for promise_id, record in record_by_id.items():
        row = row_by_id.get(promise_id)
        if row is None:
            continue
        requirement_id = row["parent_source_id"]
        node = node_by_id.get(requirement_id)
        if record.get("requirement_id") != requirement_id:
            errors.append(
                f"{promise_id} requirement_id mismatch: {record.get('requirement_id')} != {requirement_id}"
            )
        if node is None:
            errors.append(f"{promise_id} parent source not found: {requirement_id}")
            continue

        for field in (
            "entry",
            "semantic_basis",
            "missing_reason",
            *ACTION_COMPONENTS,
        ):
            value = record.get(field)
            if not isinstance(value, str) or value.strip() in value_placeholders:
                errors.append(f"{promise_id} invalid {field}")
        if record.get("semantic_basis") != "EXPLICIT_ROW_RECORD":
            errors.append(
                f"{promise_id} semantic_basis must be EXPLICIT_ROW_RECORD, got {record.get('semantic_basis')!r}"
            )

        entry_status = record.get("entry_status")
        if entry_status not in {
            "SOURCE_GROUNDED",
            "DOMAIN_SURFACE_ONLY",
            "UNSPECIFIED_IN_SOURCE",
        }:
            errors.append(f"{promise_id} invalid entry_status={entry_status!r}")
        if entry_status == UNSPECIFIED:
            if record.get("entry") != UNSPECIFIED or record.get(
                "entry_surface"
            ) != UNSPECIFIED:
                errors.append(f"{promise_id} unspecified entry must use UNSPECIFIED_IN_SOURCE")
            if "入口" not in record.get("missing_reason", ""):
                errors.append(f"{promise_id} unspecified entry lacks missing-entry reason")
        if record.get("entry") != record.get("entry_surface"):
            errors.append(f"{promise_id} entry and entry_surface differ")
        if record.get("operation_mode") not in OPERATION_MODES:
            errors.append(f"{promise_id} invalid operation_mode={record.get('operation_mode')!r}")
        if record.get("actor") in GENERIC_ACTORS:
            errors.append(f"{promise_id} actor is generic: {record.get('actor')!r}")
        if record.get("action_verb") in GENERIC_ACTION_VERBS:
            errors.append(f"{promise_id} action_verb is ID/result wrapper")
        if canonical_anchor_text(record.get("action_object", "")) == canonical_anchor_text(
            record.get("expected_response", "")
        ):
            errors.append(f"{promise_id} action_object duplicates expected_response")
        if row.get("observable_result") != record.get("expected_response"):
            errors.append(f"{promise_id} expected_response differs from observable_result")
        composed = compose_operation(record)
        if not composed:
            errors.append(f"{promise_id} operation cannot be composed")
        elif record.get("operation") != composed:
            errors.append(f"{promise_id} operation must equal component composition")
        operation = str(record.get("operation", ""))
        if LEGACY_OPERATION.search(operation):
            errors.append(f"{promise_id} operation uses legacy uniform formula")
        if ID_OPERATION_PREFIX.search(operation):
            errors.append(f"{promise_id} operation is an ID wrapper")

        states = record.get("states_accessibility_rwd")
        if not isinstance(states, dict):
            errors.append(f"{promise_id} states_accessibility_rwd must be an object")
        else:
            if states.get("record_id") != promise_id:
                errors.append(f"{promise_id} states record_id mismatch")
            if states.get("basis") != f"EXPLICIT_ROW_RECORD:{promise_id}":
                errors.append(f"{promise_id} states basis is not explicit")
            if states.get("semantic_source_id") != requirement_id:
                errors.append(f"{promise_id} states semantic_source_id mismatch")
            if "row_specific" in states:
                errors.append(f"{promise_id} must not self-certify row_specific")
            dimension_labels = {
                "states": "状态",
                "accessibility": "无障碍",
                "rwd": "RWD",
            }
            for dimension in ("states", "accessibility", "rwd"):
                values = states.get(dimension)
                if not isinstance(values, list) or not values:
                    errors.append(f"{promise_id} {dimension} must be a non-empty list")
                    continue
                if any(not isinstance(value, str) or not value.strip() for value in values):
                    errors.append(f"{promise_id} {dimension} contains a blank value")
                if UNSPECIFIED in values and len(values) != 1:
                    errors.append(
                        f"{promise_id} {dimension}=UNSPECIFIED_IN_SOURCE must stand alone"
                    )
                if UNSPECIFIED in values and dimension_labels[dimension] not in states.get(
                    "missing_reason", ""
                ):
                    errors.append(
                        f"{promise_id} {dimension} gap lacks explicit missing reason"
                    )

        anchor = record.get("semantic_source_anchor")
        if not isinstance(anchor, dict):
            errors.append(f"{promise_id} missing semantic_source_anchor")
            continue
        anchor_path = anchor.get("path")
        anchor_line = anchor.get("line")
        excerpt = anchor.get("excerpt")
        if anchor.get("requirement_id") != requirement_id:
            errors.append(f"{promise_id} anchor requirement_id mismatch")
        if anchor_path not in accepted_doc_paths:
            errors.append(f"{promise_id} anchor path is not an accepted source: {anchor_path!r}")
            continue
        if not isinstance(anchor_line, int) or anchor_line < 1:
            errors.append(f"{promise_id} invalid anchor line={anchor_line!r}")
            continue
        if not isinstance(excerpt, str) or len(canonical_anchor_text(excerpt)) < 8:
            errors.append(f"{promise_id} anchor excerpt too short")
            continue

        path = ROOT / anchor_path
        if path not in document_lines:
            document_lines[path] = path.read_text(encoding="utf-8").splitlines()
        lines = document_lines[path]
        if anchor_line > len(lines):
            errors.append(f"{promise_id} anchor line out of range: {anchor_line}")
            continue
        source_line = lines[anchor_line - 1]
        if canonical_anchor_text(excerpt) not in canonical_anchor_text(source_line):
            errors.append(f"{promise_id} anchor excerpt not found at {anchor_path}:{anchor_line}")
        action_excerpt = record.get("action_source_excerpt")
        if (
            not isinstance(action_excerpt, str)
            or len(canonical_anchor_text(action_excerpt)) < 8
            or canonical_anchor_text(action_excerpt) == canonical_anchor_text(requirement_id)
        ):
            errors.append(f"{promise_id} missing action_source_excerpt")
        elif canonical_anchor_text(action_excerpt) not in canonical_anchor_text(
            record.get("action_object", "")
        ):
            errors.append(f"{promise_id} action_object does not contain its source excerpt")
        elif canonical_anchor_text(action_excerpt) not in canonical_anchor_text(source_line):
            errors.append(f"{promise_id} action excerpt is absent from source line")

        row_context = row.get("semantic_context", {})
        if row_context.get("record_id") != promise_id:
            errors.append(f"{promise_id} output does not join semantic record ID")
        if row_context.get("basis") != "EXPLICIT_ROW_RECORD":
            errors.append(f"{promise_id} output semantic basis is not explicit")
        if row.get("entry") != record.get("entry"):
            errors.append(f"{promise_id} output entry differs from semantic registry")
        if row.get("operation") != record.get("operation"):
            errors.append(f"{promise_id} output operation differs from semantic registry")
        for component in ACTION_COMPONENTS:
            if row.get("semantic_context", {}).get(component) != record.get(component):
                errors.append(f"{promise_id} output semantic_context.{component} mismatch")
        if row.get("user_promise") != record.get("user_promise"):
            errors.append(f"{promise_id} output user_promise differs from semantic registry")
        if row.get("states_accessibility_rwd") != states:
            errors.append(f"{promise_id} output states differ from semantic registry")

        entry = record.get("entry", "")
        for forbidden in DOMAIN_ENTRY_CONTRADICTIONS.get(row["domain"], ()):
            if forbidden in entry:
                errors.append(f"{promise_id} entry contradicts domain {row['domain']}: {forbidden}")
        known = KNOWN_SEMANTIC_RULES.get(promise_id)
        if known:
            if not any(token in entry for token in known["required"]):
                errors.append(f"{promise_id} known misalignment: entry lacks required source surface")
            for forbidden in known["forbidden"]:
                if forbidden in entry:
                    errors.append(f"{promise_id} known misalignment: entry contains {forbidden}")

    if records:
        signatures = {
            (
                tuple(record.get("states_accessibility_rwd", {}).get("states", [])),
                tuple(
                    record.get("states_accessibility_rwd", {}).get(
                        "accessibility", []
                    )
                ),
                tuple(record.get("states_accessibility_rwd", {}).get("rwd", [])),
            )
            for record in records
        }
        if len(signatures) == 1:
            errors.append("states/accessibility/RWD use one uniform template")

    reviewed = {
        promise_id: record_by_id.get(promise_id)
        for promise_id in (
            "S6-P-MUSIC-003",
            "S6-P-MUSIC-014",
            "S6-P-NAV-004",
            "S6-P-NAV-005",
            "S6-P-NAV-006",
        )
    }
    music_scan = reviewed["S6-P-MUSIC-003"]
    if music_scan:
        scan_entry = music_scan.get("entry_surface", "")
        scan_verb = music_scan.get("action_verb", "")
        scan_object = music_scan.get("action_object", "")
        if "本地" not in scan_entry or not any(
            token in scan_entry for token in ("扫描", "曲库", "元数据")
        ):
            errors.append("S6-P-MUSIC-003 entry must be local scan/metadata extraction")
        if not any(token in scan_verb for token in ("扫描", "提取", "写入")):
            errors.append("S6-P-MUSIC-003 action must scan or extract metadata")
        if not any(token in scan_object for token in ("元数据", "本地曲库")):
            errors.append("S6-P-MUSIC-003 action_object lacks metadata scope")
        if "歌词面板" in scan_entry or "歌词面板" in scan_object:
            errors.append("S6-P-MUSIC-003 must not point to lyrics surface")

    music_queue = reviewed["S6-P-MUSIC-014"]
    if music_queue:
        queue_entry = music_queue.get("entry_surface", "")
        queue_verb = music_queue.get("action_verb", "")
        queue_object = music_queue.get("action_object", "")
        if "队列" not in queue_entry:
            errors.append("S6-P-MUSIC-014 entry must be queue orchestration")
        if not any(token in queue_verb for token in ("编排", "重排", "加入队列", "调整队列")):
            errors.append("S6-P-MUSIC-014 action must orchestrate the queue")
        if "队列" not in queue_object:
            errors.append("S6-P-MUSIC-014 action_object lacks queue scope")
        if any(token in queue_entry for token in ("通知", "蓝牙", "MediaSession")):
            errors.append("S6-P-MUSIC-014 must not point to system media controls")

    nav_rows = [reviewed[f"S6-P-NAV-00{i}"] for i in (4, 5, 6)]
    if all(nav_rows):
        nav_pairs = [
            (row.get("entry_surface", ""), row.get("action_verb", ""))
            for row in nav_rows
        ]
        if len(set(nav_pairs)) != 3:
            errors.append("S6-P-NAV-004/005/006 share a generic entry/action pair")
        expected_tokens = {
            "S6-P-NAV-004": ("身份", "投影"),
            "S6-P-NAV-005": ("账户", "选择"),
            "S6-P-NAV-006": ("离线", "在线"),
        }
        for row in nav_rows:
            promise_id = row["promise_id"]
            text = f"{row.get('entry_surface', '')} {row.get('action_verb', '')} {row.get('action_object', '')}"
            if not all(token in text for token in expected_tokens[promise_id]):
                errors.append(
                    f"{promise_id} lacks source-specific identity/account action semantics"
                )

    for promise_id, record in record_by_id.items():
        action_object = str(record.get("action_object", ""))
        hits = [
            pattern.pattern
            for pattern in ACTION_METADATA_PATTERNS
            if pattern.search(action_object)
        ]
        if hits:
            errors.append(
                f"{promise_id} action_object contains source metadata: {action_object[:120]}"
            )

    return errors


def wave_boundary_errors(cards: list[dict]) -> list[str]:
    errors: list[str] = []
    all_wave_ids: set[str] = set()
    wave_graph: dict[str, list[str]] = {}
    broad_pattern = re.compile(r"(?:\*|\{[^}]*\}|\.\.\./|\*\*/)")

    for card in cards:
        card_id = card["id"]
        if card.get("planning_umbrella") is not True:
            errors.append(f"{card_id} must be marked planning_umbrella=true")
        if card.get("execution_kind") == "IMPLEMENTABLE_WAVE":
            errors.append(f"{card_id} planning umbrella cannot be an implementation wave")
        waves = card.get("delivery_waves", [])
        if not waves:
            errors.append(f"{card_id} has no delivery waves")
            continue
        prior_id = None
        for index, wave in enumerate(waves, 1):
            wave_id = wave.get("id")
            if not isinstance(wave_id, str) or not wave_id:
                errors.append(f"{card_id} wave {index} missing ID")
                continue
            if wave_id in all_wave_ids:
                errors.append(f"duplicate wave ID {wave_id}")
            all_wave_ids.add(wave_id)
            if wave.get("execution_kind") != "IMPLEMENTABLE_WAVE":
                errors.append(f"{wave_id} execution_kind must be IMPLEMENTABLE_WAVE")
            if not wave.get("observable_acceptance"):
                errors.append(f"{wave_id} missing observable acceptance")
            if not wave.get("stop_condition"):
                errors.append(f"{wave_id} missing stop condition")

            boundaries = wave.get("file_interface_boundaries")
            if not isinstance(boundaries, list) or not boundaries:
                errors.append(f"{wave_id} missing file_interface_boundaries")
                boundaries = []
            if len(boundaries) > 8:
                errors.append(f"{wave_id} boundary count {len(boundaries)} > 8")
            expanded = 0
            for boundary in boundaries:
                if not isinstance(boundary, dict):
                    errors.append(f"{wave_id} boundary must be an object, not logical prose")
                    continue
                kind = boundary.get("kind")
                status = boundary.get("status")
                ref = boundary.get("path") or boundary.get("fqcn")
                if kind not in {"FILE", "INTERFACE"}:
                    errors.append(f"{wave_id} invalid boundary kind={kind!r}")
                if status not in {"FOUND", "PLANNED"}:
                    errors.append(f"{wave_id} invalid boundary status={status!r}")
                if not isinstance(ref, str) or not ref.strip():
                    errors.append(f"{wave_id} empty boundary reference")
                    continue
                if broad_pattern.search(ref):
                    errors.append(f"{wave_id} broad wildcard/glob boundary: {ref}")
                if kind == "FILE":
                    if not ref.endswith((".kt", ".kts", ".xml", ".toml", ".json", ".txt")):
                        errors.append(f"{wave_id} FILE boundary lacks explicit file extension: {ref}")
                    if status == "FOUND" and not (ROOT / ref).is_file():
                        errors.append(f"{wave_id} FOUND file does not exist: {ref}")
                    if status == "PLANNED" and (ROOT / ref).exists():
                        errors.append(f"{wave_id} PLANNED file already exists: {ref}")
                    expanded += 1
                elif kind == "INTERFACE":
                    if status == "FOUND":
                        errors.append(f"{wave_id} INTERFACE must be PLANNED until its symbol is frozen")
            if wave.get("existing_file_expansion_count") != expanded:
                errors.append(
                    f"{wave_id} existing_file_expansion_count={wave.get('existing_file_expansion_count')} "
                    f"does not match explicit FILE entries={expanded}"
                )

            deps = wave.get("wave_dependencies")
            if not isinstance(deps, list):
                errors.append(f"{wave_id} wave_dependencies must be a list")
                deps = []
            if index > 1 and deps != [prior_id]:
                errors.append(f"{wave_id} must depend on prior wave {prior_id}")
            wave_graph[wave_id] = deps
            prior_id = wave_id

    for wave_id, deps in wave_graph.items():
        for dep in deps:
            if dep not in all_wave_ids:
                errors.append(f"{wave_id} unknown wave dependency {dep}")
    if has_cycle(wave_graph):
        errors.append("wave DAG contains a cycle")
    return errors


def reference_contract_errors(
    cards: list[dict],
    source_index: dict,
    defined_decision_ids: set[str],
    all_promise_ids: set[str],
) -> list[str]:
    errors: list[str] = []
    card_ids = {card.get("id") for card in cards}
    evidence_ids = {
        item.get("id") for item in source_index.get("evidence_packages", [])
    }
    wave_by_id: dict[str, dict] = {}
    artifact_kinds = {
        "test",
        "fixture",
        "report",
        "device-evidence",
        "candidate-package",
        "static-ledger",
    }
    artifact_statuses = {"PLANNED_NOT_EXECUTED"}
    wave_graph: dict[str, list[str]] = {}

    def is_defined_reference(value: str) -> bool:
        if value.startswith("NO_EVIDENCE_YET"):
            return True
        if value.startswith("EV-"):
            return value in evidence_ids
        if value.startswith("V2-"):
            return value in defined_decision_ids
        return value in card_ids

    for card in cards:
        card_id = card.get("id", "<blank-card>")
        if not isinstance(card.get("role"), str) or not card.get("role", "").strip():
            errors.append(f"{card_id} missing professional owner role")
        for field in ("deps", "evidence"):
            values = card.get(field, [])
            if not isinstance(values, list):
                errors.append(f"{card_id}.{field} must be a list")
                continue
            if field == "evidence" and not values:
                errors.append(f"{card_id} has no card-level evidence or NO_EVIDENCE_YET")
            for value in values:
                if not isinstance(value, str) or not value.strip():
                    errors.append(f"{card_id}.{field} contains a blank reference")
                elif not is_defined_reference(value):
                    errors.append(f"{card_id}.{field} dangling reference {value}")
                if value == "EV-S6":
                    errors.append(f"{card_id}.{field} must not self-reference EV-S6")

        waves = card.get("delivery_waves", [])
        for index, wave in enumerate(waves, 1):
            wave_id = wave.get("id", f"{card_id}-W{index}-BLANK")
            if wave_id in wave_by_id:
                errors.append(f"duplicate delivery wave ID {wave_id}")
            wave_by_id[wave_id] = {**wave, "card_id": card_id}
            for field in (
                "owner",
                "entry",
                "operation",
                "observable_acceptance",
                "stop_condition",
            ):
                value = wave.get(field)
                if not isinstance(value, str) or not value.strip():
                    errors.append(f"{wave_id} missing {field}")
            promise_ids = wave.get("promise_ids")
            if not isinstance(promise_ids, list) or not promise_ids:
                errors.append(f"{wave_id} missing promise_ids")
                promise_ids = []
            if len(promise_ids) != len(set(promise_ids)):
                errors.append(f"{wave_id} contains duplicate promise_ids")
            for promise_id in promise_ids:
                if promise_id not in all_promise_ids:
                    errors.append(f"{wave_id} dangling promise_id {promise_id}")

            blockers = wave.get("blocking_conditions")
            if not isinstance(blockers, list) or not blockers:
                errors.append(f"{wave_id} missing blocking_conditions")

            external_dependencies = wave.get("external_dependencies", [])
            if not isinstance(external_dependencies, list):
                errors.append(f"{wave_id} external_dependencies must be a list")
                external_dependencies = []
            for dependency in external_dependencies:
                if not isinstance(dependency, str) or not dependency.strip():
                    errors.append(f"{wave_id} blank external dependency")
                elif not is_defined_reference(dependency):
                    errors.append(
                        f"{wave_id} dangling external dependency {dependency}"
                    )

            artifacts = wave.get("acceptance_artifacts")
            if not isinstance(artifacts, list) or not 1 <= len(artifacts) <= 2:
                errors.append(
                    f"{wave_id} acceptance_artifacts must contain 1-2 structured objects"
                )
                artifacts = []
            for artifact_index, artifact in enumerate(artifacts, 1):
                if not isinstance(artifact, dict):
                    errors.append(
                        f"{wave_id} acceptance_artifacts[{artifact_index}] is free text"
                    )
                    continue
                for field in (
                    "id",
                    "kind",
                    "locator",
                    "expected",
                    "evidence_level",
                    "status",
                ):
                    value = artifact.get(field)
                    if not isinstance(value, str) or not value.strip():
                        errors.append(
                            f"{wave_id} artifact {artifact_index} missing {field}"
                        )
                if artifact.get("kind") not in artifact_kinds:
                    errors.append(
                        f"{wave_id} artifact invalid kind={artifact.get('kind')!r}"
                    )
                if artifact.get("status") not in artifact_statuses:
                    errors.append(
                        f"{wave_id} artifact must remain NOT_EXECUTED at S6"
                    )
                if not re.fullmatch(
                    r"E[0-7](?:_[A-Z0-9_]+)?",
                    str(artifact.get("evidence_level", "")),
                ):
                    errors.append(
                        f"{wave_id} artifact invalid evidence_level={artifact.get('evidence_level')!r}"
                    )
                if not str(artifact.get("locator", "")).startswith("future-evidence://"):
                    errors.append(
                        f"{wave_id} artifact locator must be a future-evidence URI"
                    )

            deps = wave.get("wave_dependencies", [])
            if not isinstance(deps, list):
                errors.append(f"{wave_id} wave_dependencies must be a list")
                deps = []
            wave_graph[wave_id] = deps

    for wave_id, deps in wave_graph.items():
        for dep in deps:
            if dep not in wave_by_id:
                errors.append(f"{wave_id} dangling wave dependency {dep}")
    if has_cycle(wave_graph):
        errors.append("delivery wave DAG contains a cycle")
    return errors


def row_wave_errors(
    rows: list[dict],
    cards: list[dict],
    membership_doc: dict | None = None,
) -> list[str]:
    errors: list[str] = []
    card_capability_prefixes = {
        "G2-ARCH-CONTRACTS-MIGRATION": {"ARCH"},
        "G2-SEARCH": {"SEARCH"},
        "G2-UPDATE-TRUST": {"UPDATE"},
        "G2-PERSISTENT-SWR": {"CACHE"},
        "G2-UI-R1-FOUNDATION": {"UI"},
        "G2-READER-CORE": {"READER"},
        "G2-READER-TTS-GATED": {"TTS"},
        "G2-READER-PDF-GATED": {"PDF"},
        "G2-MUSIC-CORE": {"MUSIC"},
        "G2-LOCAL-PLAYLIST": {"PLAYLIST"},
        "G2-SERVER-WRITE": {"WRITE"},
        "G2-DOWNLOAD-STATE": {"DOWNLOAD"},
        "G2-VIDEO-CORE": {"VIDEO"},
        "G2-SYNCPLAY-GATED": {"SYNC"},
        "G2-NAV-IDENTITY": {"NAV"},
        "G2-Q03-SUPPLY-CHAIN-GATED": {"Q03"},
    }
    wave_by_id = {
        wave.get("id"): {
            **wave,
            "card_id": card.get("id"),
        }
        for card in cards
        for wave in card.get("delivery_waves", [])
    }
    if not membership_doc:
        return ["wave-membership.json is missing"]
    if membership_doc.get("schema_version") != MEMBERSHIP_SCHEMA:
        errors.append(
            f"unexpected wave membership schema={membership_doc.get('schema_version')!r}"
        )
    memberships = membership_doc.get("memberships")
    if not isinstance(memberships, list):
        return errors + ["wave membership records must be a list"]
    membership_by_id: dict[str, dict] = {}
    for membership in memberships:
        row_id = membership.get("row_id")
        if not isinstance(row_id, str) or not row_id:
            errors.append("blank membership row_id")
            continue
        if row_id in membership_by_id:
            errors.append(f"duplicate membership row {row_id}")
        membership_by_id[row_id] = membership

    row_by_id = {row.get("promise_id"): row for row in rows}
    missing_membership = sorted(set(row_by_id) - set(membership_by_id))
    if missing_membership:
        errors.append(
            f"membership missing for {len(missing_membership)} rows sample={missing_membership[:8]}"
        )
    orphan_membership = sorted(set(membership_by_id) - set(row_by_id))
    if orphan_membership:
        errors.append(
            f"orphan memberships={len(orphan_membership)} sample={orphan_membership[:8]}"
        )

    for wave_id, wave in wave_by_id.items():
        capabilities = wave.get("capability_tags")
        entry_tags = wave.get("entry_tags")
        action_tags = wave.get("action_tags")
        for label, values in (
            ("capability_tags", capabilities),
            ("entry_tags", entry_tags),
            ("action_tags", action_tags),
        ):
            if not isinstance(values, list) or not values:
                errors.append(f"{wave_id} missing {label}")
                continue
            if len(values) != len(set(values)):
                errors.append(f"{wave_id} duplicate {label}")
        boundaries = wave.get("file_interface_boundaries", [])
        boundary_capabilities: set[str] = set()
        for boundary in boundaries:
            tags = boundary.get("capability_tags")
            if not isinstance(tags, list):
                errors.append(f"{wave_id} boundary lacks capability_tags")
                continue
            for tag in tags:
                if tag not in (capabilities or []):
                    errors.append(
                        f"{wave_id} boundary carries undeclared capability {tag}"
                    )
                boundary_capabilities.add(tag)
        for capability in capabilities or []:
            if capability not in boundary_capabilities:
                errors.append(
                    f"{wave_id} capability has no boundary support: {capability}"
                )

    for row in rows:
        promise_id = row.get("promise_id", "<blank-row>")
        membership = membership_by_id.get(promise_id)
        wave_ids = row.get("g2_wave_ids")
        if not isinstance(wave_ids, list):
            errors.append(f"{promise_id} missing g2_wave_ids")
            continue
        if not wave_ids:
            errors.append(f"{promise_id} owner row has no delivery wave")
        if len(wave_ids) != len(set(wave_ids)):
            errors.append(f"{promise_id} duplicate wave membership")
        if membership is None:
            continue
        record_wave_ids = membership.get("wave_ids")
        if not isinstance(record_wave_ids, list) or not record_wave_ids:
            errors.append(f"{promise_id} membership wave_ids must be non-empty")
            continue
        if len(record_wave_ids) != len(set(record_wave_ids)):
            errors.append(f"{promise_id} duplicate wave membership")
        if record_wave_ids != wave_ids:
            errors.append(f"{promise_id} row and membership wave_ids differ")
        source_capabilities = membership.get("source_capabilities")
        if not isinstance(source_capabilities, list) or not source_capabilities:
            errors.append(f"{promise_id} membership lacks source capability")
            source_capabilities = []
        if len(source_capabilities) != len(set(source_capabilities)):
            errors.append(f"{promise_id} duplicate source capability")
        allowed_prefixes = {
            prefix
            for card_id in row.get("g2_card_ids", [])
            for prefix in card_capability_prefixes.get(card_id, set())
        }
        if allowed_prefixes:
            incompatible = [
                capability
                for capability in source_capabilities
                if capability.split(".", 1)[0] not in allowed_prefixes
            ]
            if incompatible:
                errors.append(
                    f"{promise_id} domain/capability mismatch: {incompatible}"
                )
        entry_tags = membership.get("entry_tags")
        if not isinstance(entry_tags, list) or not entry_tags:
            errors.append(f"{promise_id} membership lacks entry tags")
            entry_tags = []
        action_tags = membership.get("action_tags")
        if not isinstance(action_tags, list) or not action_tags:
            errors.append(f"{promise_id} membership lacks action tags")
            action_tags = []
        if not isinstance(membership.get("reason"), str) or not membership.get(
            "reason", ""
        ).strip():
            errors.append(f"{promise_id} membership lacks reason")
        declared_capabilities: set[str] = set()
        for wave_id in wave_ids:
            wave = wave_by_id.get(wave_id)
            if wave is None:
                errors.append(f"{promise_id} references unknown wave {wave_id}")
                continue
            wave_capabilities = set(wave.get("capability_tags", []))
            declared_capabilities.update(wave_capabilities)
            if not set(source_capabilities) & wave_capabilities:
                errors.append(
                    f"{promise_id} capability mismatch for {wave_id}: "
                    f"row={source_capabilities} wave={sorted(wave_capabilities)}"
                )
            if not set(entry_tags) & set(wave.get("entry_tags", [])):
                errors.append(f"{promise_id} entry mismatch for {wave_id}")
            if not set(action_tags) & set(wave.get("action_tags", [])):
                errors.append(f"{promise_id} action mismatch for {wave_id}")
            if promise_id not in wave.get("promise_ids", []):
                errors.append(f"{promise_id} not listed by referenced wave {wave_id}")
            if wave.get("card_id") not in row.get("g2_card_ids", []):
                errors.append(
                    f"{promise_id} wave {wave_id} card is absent from g2_card_ids"
                )
        unsupported_capabilities = sorted(
            set(source_capabilities) - declared_capabilities
        )
        if unsupported_capabilities:
            errors.append(
                f"{promise_id} source capabilities have no assigned wave: "
                f"{unsupported_capabilities}"
            )
    known_exact = {
        "S6-P-MUSIC-003": ["G2-MUSIC-CORE-W4"],
        "S6-P-MUSIC-014": ["G2-MUSIC-CORE-W2"],
        "S6-P-NAV-004": [
            "G2-NAV-IDENTITY-W2",
            "G2-UI-R1-FOUNDATION-W2",
        ],
        "S6-P-NAV-005": [
            "G2-NAV-IDENTITY-W2",
            "G2-UI-R1-FOUNDATION-W2",
        ],
        "S6-P-NAV-006": [
            "G2-NAV-IDENTITY-W2",
            "G2-UI-R1-FOUNDATION-W2",
        ],
    }
    for row_id, expected_waves in known_exact.items():
        membership = membership_by_id.get(row_id, {})
        if sorted(membership.get("wave_ids", [])) != sorted(expected_waves):
            errors.append(
                f"{row_id} known membership mismatch: "
                f"{membership.get('wave_ids')} != {expected_waves}"
            )
    membership_003 = membership_by_id.get("S6-P-MUSIC-003", {})
    if "MUSIC.LOCAL_SCAN_METADATA" not in membership_003.get(
        "source_capabilities", []
    ):
        errors.append("S6-P-MUSIC-003 lacks local scan/metadata capability")
    nav_005 = membership_by_id.get("S6-P-NAV-005", {})
    if "NAV.ACCOUNT_SELECT" not in nav_005.get("source_capabilities", []):
        errors.append("S6-P-NAV-005 lacks account-select capability")
    critical_boundaries = {
        "S6-P-MUSIC-003": {
            "wave_id": "G2-MUSIC-CORE-W4",
            "required": {
                "data/src/main/java/com/zhangwenkang/cinefin/local/LocalLibraryRepositoryImpl.kt",
                "data/src/main/java/com/zhangwenkang/cinefin/repository/MusicTrackMetadata.kt",
            },
        },
        "S6-P-MUSIC-014": {
            "wave_id": "G2-MUSIC-CORE-W2",
            "required": {
                "modes/music/src/main/java/com/zhangwenkang/cinefin/music/data/MusicQueueEdits.kt",
                "modes/music/src/main/java/com/zhangwenkang/cinefin/music/data/MusicQueueStore.kt",
            },
        },
        "S6-P-NAV-005": {
            "wave_id": "G2-NAV-IDENTITY-W2",
            "required": {
                "app/phone/src/main/java/com/zhangwenkang/cinefin/presentation/setup/users/UsersScreen.kt",
                "setup/src/main/java/com/zhangwenkang/cinefin/setup/presentation/users/UsersViewModel.kt",
                "setup/src/main/java/com/zhangwenkang/cinefin/setup/domain/UserSessionSelection.kt",
            },
        },
    }
    for row_id, rule in critical_boundaries.items():
        wave = wave_by_id.get(rule["wave_id"], {})
        refs = {
            boundary.get("path") or boundary.get("fqcn")
            for boundary in wave.get("file_interface_boundaries", [])
        }
        missing = sorted(rule["required"] - refs)
        if missing:
            errors.append(
                f"{row_id} supporting boundary missing in {rule['wave_id']}: {missing}"
            )

    for row in rows:
        if row.get("level") != "MUST":
            continue
        row_wave_ids = row.get("g2_wave_ids", [])
        if not any(
            wave_by_id.get(wave_id, {}).get("card_id") in row.get("g2_card_ids", [])
            for wave_id in row_wave_ids
        ):
            errors.append(f"{row['promise_id']} MUST lacks a card-compatible wave")

    counts = membership_doc.get("counts", {})
    expected_counts = {
        "external_acceptance_rows": sum(
            1 for row in rows if str(row.get("promise_id", "")).startswith("S6-P-")
            and row.get("g2_card_ids")
            and row.get("source_origin") != "PROJECT_G0_ADDENDUM"
            and row.get("promise_id") not in {
                "S6-P-BASE-001",
                "S6-P-BASE-002",
                "S6-P-BASE-003",
                "S6-P-BASE-004",
                "S6-P-BASE-005",
            }
        ),
        "membership_records": len(rows),
        "row_wave_edges": sum(len(row.get("g2_wave_ids", [])) for row in rows),
        "multi_wave_memberships": sum(
            len(row.get("g2_wave_ids", [])) > 1 for row in rows
        ),
    }
    for key, expected in expected_counts.items():
        if counts.get(key) != expected:
            errors.append(
                f"membership counts.{key}={counts.get(key)} expected={expected}"
            )
    return errors


def nav_promise_noise_errors(rows: list[dict]) -> list[str]:
    errors: list[str] = []
    source_range = re.compile(r"\b1\d{3}\s*[–-]\s*1\d{3}\b")
    decision_ref = re.compile(r"\bV2-D\d{3}\b|\bD\d{3}\b")
    for row in rows:
        if row["domain"] != "NAV":
            continue
        promise = row.get("user_promise", "")
        if source_range.search(promise) or decision_ref.search(promise):
            errors.append(f"{row['promise_id']} user_promise contains source/count noise")
    return errors


def a11y_threshold_errors(thresholds: list[dict]) -> list[str]:
    errors: list[str] = []
    by_id = {item["id"]: item for item in thresholds}
    focus = by_id.get("T-FOCUS-RING", {})
    if "R1-A11Y-NAMES" in focus.get("requirement_ids", []):
        errors.append("T-FOCUS-RING must not reference R1-A11Y-NAMES")
    named = [
        item
        for item in thresholds
        if "R1-A11Y-NAMES" in item.get("requirement_ids", [])
        and ("名称" in item.get("metric", "") or "name" in item.get("metric", "").lower())
    ]
    if not named:
        errors.append("R1-A11Y-NAMES lacks an accessible-name threshold")
    return errors


def run_self_test() -> None:
    matrix = load_json("acceptance-matrix.json")
    source_index = load_json("source-index.json")
    thresholds_doc = load_json("thresholds.json")
    cards_doc = load_json("g2-task-cards.json")
    risks_doc = load_json("risk-register.json")
    registry = load_json("row-semantics.json")
    membership_doc = load_json("wave-membership.json")
    rows = matrix["acceptance_rows"]
    base_rows = matrix["base_groups"]
    r1_rows = matrix["r1_quality_rows"]
    nodes = matrix["source_nodes"]
    cards = cards_doc["cards"]
    risks = risks_doc["risks"]
    all_promise_ids = {
        row["promise_id"] for row in rows + base_rows + r1_rows
    }
    authority_text = "\n".join(
        (ROOT / item["path"]).read_text(encoding="utf-8")
        for item in source_index.get("document_sources", [])
    )
    defined_decision_ids = set(
        re.findall(r"V2-(?:D\d{3}|Q-[A-Z0-9-]+)", authority_text)
    )
    results: list[tuple[str, bool, str]] = []

    def expect_failure(label: str, errors: list[str], needle: str | None = None) -> None:
        matched = bool(errors)
        detail = errors[0] if errors else "fixture unexpectedly passed"
        if needle:
            matched = any(needle in error for error in errors)
            detail = next(
                (error for error in errors if needle in error),
                f"expected error containing {needle!r}; got={errors[:3]}",
            )
        results.append((label, matched, detail))

    base_semantics = semantic_registry_errors(registry, rows, nodes, source_index)
    if base_semantics:
        results.append(("BASE-sanity", False, base_semantics[0]))
    else:
        results.append(("BASE-sanity", True, "shipped registry is valid"))

    base_contract = reference_contract_errors(
        cards,
        source_index,
        defined_decision_ids,
        all_promise_ids,
    )
    if base_contract:
        results.append(("BASE-wave-contract", False, base_contract[0]))
    else:
        results.append(("BASE-wave-contract", True, "shipped wave contract is valid"))

    shipped_rows = rows + base_rows + r1_rows
    base_row_waves = row_wave_errors(shipped_rows, cards, membership_doc)
    if base_row_waves:
        results.append(("BASE-row-wave-membership", False, base_row_waves[0]))
    else:
        results.append(
            (
                "BASE-row-wave-membership",
                True,
                "shipped explicit row-wave membership and capability join is valid",
            )
        )

    base_addendum_errors = d203_addendum_errors(matrix)
    if base_addendum_errors:
        results.append(("BASE-d203-origin", False, base_addendum_errors[0]))
    else:
        results.append(
            ("BASE-d203-origin", True, "external 445/376 and addendum 4 remain separated")
        )

    base_d203_contract = d203_semantic_contract_errors(
        registry,
        rows,
        cards,
        risks_doc,
    )
    if base_d203_contract:
        results.append(("BASE-d203-semantics", False, base_d203_contract[0]))
    else:
        results.append(
            ("BASE-d203-semantics", True, "specialized D203 entry/action/card/wave/risk contract is valid")
        )

    base_w1_tags = tts_w1_action_tag_errors(cards, membership_doc)
    if base_w1_tags:
        results.append(("BASE-tts-w1-action-tags", False, base_w1_tags[0]))
    else:
        results.append(
            (
                "BASE-tts-w1-action-tags",
                True,
                "W1 and four memberships verify the fixed engine without SELECT_ENGINE",
            )
        )

    origin_mutation = copy.deepcopy(matrix)
    next(
        row
        for row in origin_mutation["acceptance_rows"]
        if row.get("source_origin") == "PROJECT_G0_ADDENDUM"
    )["source_origin"] = "EXTERNAL_G0"
    expect_failure(
        "NEG-D203-ORIGIN-MIXED-INTO-EXTERNAL",
        d203_addendum_errors(origin_mutation),
        "D203 row origin counts",
    )

    def mutate_d203_semantics(
        promise_id: str,
        *,
        entry: str | None = None,
        action_verb: str | None = None,
        operation: str | None = None,
        expected_response: str | None = None,
    ) -> tuple[dict, list[dict], list[dict], dict]:
        mutation_registry = copy.deepcopy(registry)
        mutation_rows = copy.deepcopy(rows)
        mutation_cards = copy.deepcopy(cards)
        mutation_risks = copy.deepcopy(risks_doc)
        record = next(
            item
            for item in mutation_registry["rows"]
            if item["promise_id"] == promise_id
        )
        row = next(
            item for item in mutation_rows if item["promise_id"] == promise_id
        )
        if entry is not None:
            record["entry"] = entry
            record["entry_surface"] = entry
            row["entry"] = entry
        if action_verb is not None:
            record["action_verb"] = action_verb
        if operation is not None:
            record["operation"] = operation
            row["operation"] = operation
        if expected_response is not None:
            record["expected_response"] = expected_response
            row["observable_result"] = expected_response
        return mutation_registry, mutation_rows, mutation_cards, mutation_risks

    expect_failure(
        "NEG-D203-087-GENERIC-BOOK-ENTRY",
        d203_semantic_contract_errors(
            *mutate_d203_semantics(
                "S6-P-READER-087",
                entry="书架 / 书籍操作菜单",
            )
        ),
        "S6-P-READER-087 entry mismatch",
    )
    expect_failure(
        "NEG-D203-088-GENERIC-BOOK-IMPORT",
        d203_semantic_contract_errors(
            *mutate_d203_semantics(
                "S6-P-READER-088",
                entry="书架 / 书籍操作菜单",
                action_verb="导入或导出并核对",
                operation="用户在书架 / 书籍操作菜单，导入或导出并核对音色",
            )
        ),
        "S6-P-READER-088 entry mismatch",
    )
    expect_failure(
        "NEG-D203-089-GENERIC-BOOK-ACTION",
        d203_semantic_contract_errors(
            *mutate_d203_semantics(
                "S6-P-READER-089",
                entry="阅读器 TTS 音色列表 / 删除 / 恢复",
                action_verb="导入或导出并核对",
                operation="用户在阅读器 TTS 音色列表 / 删除 / 恢复，导入或导出并核对音色",
            )
        ),
        "S6-P-READER-089 action_verb mismatch",
    )
    expect_failure(
        "NEG-D203-090-WRONG-AUDIO-ACTION",
        d203_semantic_contract_errors(
            *mutate_d203_semantics(
                "S6-P-READER-090",
                entry="阅读器 TTS / 音乐 / 系统媒体控制",
                action_verb="打开并控制播放",
                operation="用户在阅读器 TTS / 音乐 / 系统媒体控制，打开并控制播放",
            )
        ),
        "S6-P-READER-090 action_verb mismatch",
    )
    missing_088_response = mutate_d203_semantics(
        "S6-P-READER-088",
        expected_response="用户可导入多个音色并在列表中选择。",
    )
    expect_failure(
        "NEG-D203-088-MISSING-EXPECTED",
        d203_semantic_contract_errors(*missing_088_response),
        "S6-P-READER-088 expected_response missing tokens",
    )

    missing_089_wave = copy.deepcopy(
        (
            registry,
            rows,
            cards,
            risks_doc,
        )
    )
    card_089 = next(item for item in missing_089_wave[2] if item["id"] == "G2-READER-TTS-GATED")
    wave_089 = next(item for item in card_089["delivery_waves"] if item["id"] == "G2-READER-TTS-GATED-W5")
    wave_089["promise_ids"].remove("S6-P-READER-089")
    expect_failure(
        "NEG-D203-089-TASKCARD-WAVE",
        d203_semantic_contract_errors(*missing_089_wave),
        "S6-P-READER-089 absent from task-card wave",
    )

    stale_risk = copy.deepcopy(
        (
            registry,
            rows,
            cards,
            risks_doc,
        )
    )
    risk_107 = next(
        item for item in stale_risk[3]["risks"] if item["id"] == "R-S6-P1-07"
    )
    risk_107["title"] = "TTS 引擎、voice 与模型资产许可尚未选择"
    risk_107["closure_evidence"] = ["用户引擎 / voice 决策"]
    expect_failure(
        "NEG-RISK-P1-07-ENGINE-UNSELECTED",
        d203_semantic_contract_errors(*stale_risk),
        "R-S6-P1-07",
    )

    stale_q03 = copy.deepcopy((registry, rows, cards, risks_doc))
    q03_card = next(
        item
        for item in stale_q03[2]
        if item["id"] == "G2-Q03-SUPPLY-CHAIN-GATED"
    )
    q03_card["boundaries"].append(
        "任何引擎 / voice / PDF 选择必须先由用户关 Q03 决策。"
    )
    expect_failure(
        "NEG-Q03-STALE-ENGINE-DECISION",
        d203_semantic_contract_errors(*stale_q03),
        "G2-Q03 card retains stale engine/voice/PDF selection decision",
    )

    stale_tts_w1 = copy.deepcopy((registry, rows, cards, risks_doc))
    tts_card = next(
        item for item in stale_tts_w1[2] if item["id"] == "G2-READER-TTS-GATED"
    )
    tts_w1 = next(
        item
        for item in tts_card["delivery_waves"]
        if item["id"] == "G2-READER-TTS-GATED-W1"
    )
    tts_w1["objective"] = "消费用户引擎 / voice / 模型许可决策，不自选。"
    expect_failure(
        "NEG-TTS-W1-STALE-DECISION",
        d203_semantic_contract_errors(*stale_tts_w1),
        "G2-READER-TTS-GATED-W1 retains stale engine/voice decision",
    )

    stale_w1_tags = copy.deepcopy((cards, membership_doc))
    stale_w1_card = next(
        item
        for item in stale_w1_tags[0]
        if item["id"] == "G2-READER-TTS-GATED"
    )
    stale_w1_wave = next(
        item
        for item in stale_w1_card["delivery_waves"]
        if item["id"] == "G2-READER-TTS-GATED-W1"
    )
    stale_w1_wave["action_tags"] = [
        "SELECT_ENGINE" if tag == "VERIFY_FIXED_ENGINE_AND_LICENSE_INPUTS" else tag
        for tag in stale_w1_wave["action_tags"]
    ]
    for item in stale_w1_tags[1]["memberships"]:
        if "G2-READER-TTS-GATED-W1" not in item.get("wave_ids", []):
            continue
        item["action_tags"] = [
            "SELECT_ENGINE"
            if tag == "VERIFY_FIXED_ENGINE_AND_LICENSE_INPUTS"
            else tag
            for tag in item["action_tags"]
        ]
    expect_failure(
        "NEG-TTS-W1-ACTION-SELECT-ENGINE",
        tts_w1_action_tag_errors(*stale_w1_tags),
        "G2-READER-TTS-GATED-W1 uses engine-selection action tags",
    )

    stale_reader_core = copy.deepcopy((registry, rows, cards, risks_doc))
    reader_core_card = next(
        item for item in stale_reader_core[2] if item["id"] == "G2-READER-CORE"
    )
    reader_core_card["non_goals"].append(
        "不在本卡锁定 PDFium 或选择最终 TTS 引擎 / voice。"
    )
    expect_failure(
        "NEG-READER-CORE-STALE-TTS-ENGINE",
        d203_semantic_contract_errors(*stale_reader_core),
        "G2-READER-CORE retains stale TTS engine selection",
    )

    missing_q03_risk = copy.deepcopy((registry, rows, cards, risks_doc))
    q03_card = next(
        item
        for item in missing_q03_risk[2]
        if item["id"] == "G2-Q03-SUPPLY-CHAIN-GATED"
    )
    q03_card["risks"].remove("R-S6-P1-07")
    expect_failure(
        "NEG-Q03-MISSING-RISK-P1-07",
        d203_semantic_contract_errors(*missing_q03_risk),
        "G2-Q03 card must map R-S6-P1-07",
    )

    def relocate_membership(
        row_id: str,
        wave_ids: list[str],
        capability: str,
        entry_tag: str,
        action_tags: list[str],
    ) -> tuple[list[dict], list[dict], dict]:
        mutation_rows = copy.deepcopy(shipped_rows)
        mutation_cards = copy.deepcopy(cards)
        mutation_membership = copy.deepcopy(membership_doc)
        membership_record = next(
            item for item in mutation_membership["memberships"] if item["row_id"] == row_id
        )
        membership_record.update(
            {
                "wave_ids": wave_ids,
                "source_capabilities": [capability],
                "entry_tag": entry_tag,
                "action_tags": action_tags,
                "reason": "negative fixture relocation",
            }
        )
        mutation_row = next(item for item in mutation_rows if item["promise_id"] == row_id)
        mutation_row["g2_wave_ids"] = wave_ids
        for card in mutation_cards:
            for wave in card.get("delivery_waves", []):
                if row_id in wave.get("promise_ids", []):
                    wave["promise_ids"].remove(row_id)
        for wave_id in wave_ids:
            wave = next(
                wave
                for card in mutation_cards
                for wave in card.get("delivery_waves", [])
                if wave["id"] == wave_id
            )
            wave["promise_ids"].append(row_id)
        return mutation_rows, mutation_cards, mutation_membership

    music_scan_queue = relocate_membership(
        "S6-P-MUSIC-003",
        ["G2-MUSIC-CORE-W3"],
        "MUSIC.LOCAL_SCAN_METADATA",
        "LOCAL_LIBRARY",
        ["SCAN_METADATA"],
    )
    expect_failure(
        "NEG-MUSIC-003-QUEUE-W3",
        row_wave_errors(*music_scan_queue),
        "capability mismatch",
    )

    music_queue_playlist = list(
        relocate_membership(
        "S6-P-MUSIC-014",
        ["G2-LOCAL-PLAYLIST-W1"],
        "MUSIC.QUEUE_EDIT",
        "ACTIVE_QUEUE",
        ["ORCHESTRATE_QUEUE"],
        )
    )
    playlist_wave = next(
        wave
        for card in music_queue_playlist[1]
        for wave in card.get("delivery_waves", [])
        if wave.get("id") == "G2-LOCAL-PLAYLIST-W1"
    )
    playlist_wave["id"] = "G2-LOCAL-PLAYLIST-W3"
    music_queue_playlist[0][
        next(
            index
            for index, row in enumerate(music_queue_playlist[0])
            if row["promise_id"] == "S6-P-MUSIC-014"
        )
    ]["g2_wave_ids"] = ["G2-LOCAL-PLAYLIST-W3"]
    next(
        item
        for item in music_queue_playlist[2]["memberships"]
        if item["row_id"] == "S6-P-MUSIC-014"
    )["wave_ids"] = ["G2-LOCAL-PLAYLIST-W3"]
    expect_failure(
        "NEG-MUSIC-014-PLAYLIST-W3",
        row_wave_errors(*music_queue_playlist),
        "capability mismatch",
    )

    nav_account_route = relocate_membership(
        "S6-P-NAV-005",
        ["G2-UI-R1-FOUNDATION-W1", "G2-NAV-IDENTITY-W1"],
        "NAV.ACCOUNT_SELECT",
        "ACCOUNT_SELECTOR",
        ["SELECT_ACCOUNT"],
    )
    expect_failure(
        "NEG-NAV-005-ROUTE-TOKEN-W1",
        row_wave_errors(*nav_account_route),
        "capability mismatch",
    )

    music_queue_large = relocate_membership(
        "S6-P-MUSIC-014",
        ["G2-MUSIC-CORE-W3"],
        "MUSIC.QUEUE_EDIT",
        "ACTIVE_QUEUE",
        ["ORCHESTRATE_QUEUE"],
    )
    expect_failure(
        "NEG-MUSIC-014-LARGE-QUEUE-W3",
        row_wave_errors(*music_queue_large),
        "capability mismatch",
    )

    missing_membership = copy.deepcopy(membership_doc)
    missing_membership["memberships"] = missing_membership["memberships"][1:]
    expect_failure(
        "NEG-MEMBERSHIP-MISSING",
        row_wave_errors(shipped_rows, cards, missing_membership),
        "membership missing",
    )

    duplicate_membership = copy.deepcopy(membership_doc)
    duplicate_membership["memberships"].append(
        copy.deepcopy(duplicate_membership["memberships"][0])
    )
    expect_failure(
        "NEG-MEMBERSHIP-DUPLICATE-ROW",
        row_wave_errors(shipped_rows, cards, duplicate_membership),
        "duplicate membership row",
    )

    duplicate_wave = copy.deepcopy(membership_doc)
    first_membership = duplicate_wave["memberships"][0]
    first_membership["wave_ids"].append(first_membership["wave_ids"][0])
    expect_failure(
        "NEG-MEMBERSHIP-DUPLICATE-WAVE",
        row_wave_errors(shipped_rows, cards, duplicate_wave),
        "duplicate wave membership",
    )

    cross_capability = relocate_membership(
        "S6-P-NAV-005",
        ["G2-DOWNLOAD-STATE-W2"],
        "NAV.ACCOUNT_SELECT",
        "ACCOUNT_SELECTOR",
        ["SELECT_ACCOUNT"],
    )
    expect_failure(
        "NEG-MEMBERSHIP-CROSS-CAPABILITY",
        row_wave_errors(*cross_capability),
        "capability mismatch",
    )

    missing = copy.deepcopy(registry)
    missing["rows"] = missing["rows"][1:]
    expect_failure(
        "NEG-ROW-RECORD-MISSING",
        semantic_registry_errors(missing, rows, nodes, source_index),
        "rows without explicit semantic record",
    )

    uniform = copy.deepcopy(registry)
    for item in uniform["rows"]:
        item["operation"] = (
            "按需求 ID 执行并观察：" + str(item.get("expected_response", ""))
        )
    expect_failure(
        "NEG-UNIFORM-OPERATION-FORMULA",
        semantic_registry_errors(uniform, rows, nodes, source_index),
        "legacy uniform formula",
    )

    wrapped = copy.deepcopy(registry)
    wrapped_record = next(
        item for item in wrapped["rows"] if item["promise_id"] == "S6-P-ARCH-001"
    )
    wrapped_record.update(
        {
            "actor": "用户",
            "action_verb": "核对",
            "action_object": wrapped_record.get("expected_response", ""),
            "operation_mode": "USER_ACTION",
        }
    )
    wrapped_record["operation"] = compose_operation(wrapped_record)
    expect_failure(
        "NEG-ID-RESULT-WRAPPER",
        semantic_registry_errors(wrapped, rows, nodes, source_index),
        "action_object duplicates expected_response",
    )

    wrong = copy.deepcopy(registry)
    record = next(item for item in wrong["rows"] if item["promise_id"] == "S6-P-MUSIC-001")
    record["entry"] = "顶栏 → 搜索"
    record["entry_surface"] = "顶栏 → 搜索"
    expect_failure(
        "NEG-KNOWN-MUSIC-MISALIGNMENT",
        semantic_registry_errors(wrong, rows, nodes, source_index),
        "known misalignment",
    )

    music_scan = copy.deepcopy(registry)
    music_scan_record = next(
        item for item in music_scan["rows"] if item["promise_id"] == "S6-P-MUSIC-003"
    )
    music_scan_record["entry_surface"] = "Now Playing 歌词面板"
    music_scan_record["entry"] = "Now Playing 歌词面板"
    music_scan_record["action_verb"] = "选择并验证"
    music_scan_record["operation"] = compose_operation(music_scan_record)
    expect_failure(
        "NEG-MUSIC-003-LYRICS-SURFACE",
        semantic_registry_errors(music_scan, rows, nodes, source_index),
        "S6-P-MUSIC-003 entry must be local scan/metadata extraction",
    )

    nav_generic = copy.deepcopy(registry)
    for promise_id in (
        "S6-P-NAV-004",
        "S6-P-NAV-005",
        "S6-P-NAV-006",
    ):
        nav_record = next(
            item for item in nav_generic["rows"] if item["promise_id"] == promise_id
        )
        nav_record["entry_surface"] = "账户与身份选择"
        nav_record["entry"] = "账户与身份选择"
        nav_record["action_verb"] = "切换入口并返回"
        nav_record["operation"] = compose_operation(nav_record)
    expect_failure(
        "NEG-NAV-004-006-GENERIC-ACTION",
        semantic_registry_errors(nav_generic, rows, nodes, source_index),
        "S6-P-NAV-004/005/006 share a generic entry/action pair",
    )

    metadata_leak = copy.deepcopy(registry)
    metadata_record = next(
        item for item in metadata_leak["rows"] if item["promise_id"] == "S6-P-NAV-004"
    )
    metadata_record["action_object"] = (
        metadata_record.get("action_object", "") + " V2-D189 1155"
    )
    metadata_record["operation"] = compose_operation(metadata_record)
    expect_failure(
        "NEG-ACTIONOBJECT-METADATA",
        semantic_registry_errors(metadata_leak, rows, nodes, source_index),
        "action_object contains source metadata",
    )

    fake = copy.deepcopy(registry)
    fake["rows"][0]["semantic_basis"] = "DOMAIN_TEMPLATE"
    expect_failure(
        "NEG-TEMPLATE-BASIS",
        semantic_registry_errors(fake, rows, nodes, source_index),
        "semantic_basis",
    )

    broad = copy.deepcopy(cards)
    broad[0]["delivery_waves"][0]["file_interface_boundaries"] = [
        {
            "kind": "FILE",
            "path": "app/phone/src/main/java/com/zhangwenkang/cinefin/**",
            "status": "FOUND",
        }
    ]
    broad[0]["delivery_waves"][0]["existing_file_expansion_count"] = 1
    expect_failure("NEG-BROAD-GLOB", wave_boundary_errors(broad), "broad wildcard")

    over_limit = copy.deepcopy(cards)
    wave = over_limit[0]["delivery_waves"][0]
    wave["file_interface_boundaries"] = [
        {
            "kind": "FILE",
            "path": f"planned/boundary-{index}.kt",
            "status": "PLANNED",
        }
        for index in range(9)
    ]
    wave["existing_file_expansion_count"] = 9
    expect_failure(
        "NEG-BOUNDARY-OVER-8",
        wave_boundary_errors(over_limit),
        "boundary count 9 > 8",
    )

    empty = copy.deepcopy(cards)
    empty[0]["delivery_waves"][0]["file_interface_boundaries"] = [
        {"kind": "INTERFACE", "fqcn": "", "status": "PLANNED"}
    ]
    empty[0]["delivery_waves"][0]["existing_file_expansion_count"] = 0
    expect_failure(
        "NEG-EMPTY-INTERFACE",
        wave_boundary_errors(empty),
        "empty boundary reference",
    )

    id_wrapper = copy.deepcopy(registry)
    id_record = next(
        item for item in id_wrapper["rows"] if item["promise_id"] == "S6-P-ARCH-001"
    )
    id_record["operation"] = "按 V2-REQ-ARCH-001 执行"
    expect_failure(
        "NEG-OPERATION-ID-ONLY",
        semantic_registry_errors(id_wrapper, rows, nodes, source_index),
        "operation is an ID wrapper",
    )

    noisy = copy.deepcopy(rows)
    nav = next(item for item in noisy if item["promise_id"] == "S6-P-NAV-001")
    nav["user_promise"] = "1076–1079（一级容器上下文）"
    expect_failure(
        "NEG-NAV-SOURCE-NOISE",
        nav_promise_noise_errors(noisy),
        "source/count noise",
    )

    wrong_threshold = copy.deepcopy(thresholds_doc["thresholds"])
    next(item for item in wrong_threshold if item["id"] == "T-FOCUS-RING")[
        "requirement_ids"
    ].append("R1-A11Y-NAMES")
    expect_failure(
        "NEG-A11Y-NAME-MISLINK",
        a11y_threshold_errors(wrong_threshold),
        "T-FOCUS-RING",
    )

    card_self_ref = copy.deepcopy(cards)
    card_self_ref[0].setdefault("evidence", []).append("EV-S6")
    expect_failure(
        "NEG-CARD-EV-S6",
        reference_contract_errors(
            card_self_ref,
            source_index,
            defined_decision_ids,
            all_promise_ids,
        ),
        "must not self-reference EV-S6",
    )

    dangling_evidence = copy.deepcopy(cards)
    dangling_evidence[0].setdefault("evidence", []).append("EV-NOT-DEFINED")
    expect_failure(
        "NEG-DANGLING-CARD-EVIDENCE",
        reference_contract_errors(
            dangling_evidence,
            source_index,
            defined_decision_ids,
            all_promise_ids,
        ),
        "dangling reference EV-NOT-DEFINED",
    )

    dangling_dependency = copy.deepcopy(cards)
    dangling_dependency[0]["delivery_waves"][0].setdefault(
        "external_dependencies", []
    ).append("G2-NOT-DEFINED")
    expect_failure(
        "NEG-DANGLING-EXTERNAL-DEPENDENCY",
        reference_contract_errors(
            dangling_dependency,
            source_index,
            defined_decision_ids,
            all_promise_ids,
        ),
        "dangling external dependency G2-NOT-DEFINED",
    )

    missing_owner = copy.deepcopy(cards)
    missing_owner[0]["delivery_waves"][0].pop("owner", None)
    expect_failure(
        "NEG-WAVE-MISSING-OWNER",
        reference_contract_errors(
            missing_owner,
            source_index,
            defined_decision_ids,
            all_promise_ids,
        ),
        "missing owner",
    )

    missing_promises = copy.deepcopy(cards)
    missing_promises[0]["delivery_waves"][0]["promise_ids"] = []
    expect_failure(
        "NEG-WAVE-MISSING-PROMISES",
        reference_contract_errors(
            missing_promises,
            source_index,
            defined_decision_ids,
            all_promise_ids,
        ),
        "missing promise_ids",
    )

    free_text_artifact = copy.deepcopy(cards)
    free_text_artifact[0]["delivery_waves"][0]["acceptance_artifacts"] = [
        "只看文字即可"
    ]
    expect_failure(
        "NEG-FREE-TEXT-ARTIFACT",
        reference_contract_errors(
            free_text_artifact,
            source_index,
            defined_decision_ids,
            all_promise_ids,
        ),
        "is free text",
    )

    wave_cycle = copy.deepcopy(cards)
    search_card = next(item for item in wave_cycle if item["id"] == "G2-SEARCH")
    first_wave, second_wave = search_card["delivery_waves"][:2]
    first_wave["wave_dependencies"] = [second_wave["id"]]
    second_wave["wave_dependencies"] = [first_wave["id"]]
    expect_failure(
        "NEG-WAVE-DAG-CYCLE",
        reference_contract_errors(
            wave_cycle,
            source_index,
            defined_decision_ids,
            all_promise_ids,
        ),
        "delivery wave DAG contains a cycle",
    )

    negative_injections = sum(
        1 for check_id, _, _ in results if check_id.startswith("NEG-")
    )
    print(f"NEGATIVE_FIXTURE_INJECTIONS={negative_injections}")
    if any(not passed for _, passed, _ in results):
        print("SELF_TEST_RED")
        for check_id, passed, detail in results:
            print(f"{'PASS' if passed else 'FAIL'} {check_id}: {detail}")
        raise SystemExit(1)
    print("SELF_TEST_GREEN")
    for check_id, _, detail in results:
        print(f"PASS {check_id}: {detail}")


def main() -> None:
    if "--self-test" in sys.argv:
        run_self_test()
        return

    matrix = load_json("acceptance-matrix.json")
    thresholds = load_json("thresholds.json")
    cards_doc = load_json("g2-task-cards.json")
    risks = load_json("risk-register.json")
    source_index = load_json("source-index.json")
    semantics = load_json_optional("row-semantics.json")
    membership = load_json("wave-membership.json")
    manifest = load_json("manifest.json")

    for name, obj in [
        ("acceptance-matrix.json", matrix),
        ("thresholds.json", thresholds),
        ("g2-task-cards.json", cards_doc),
        ("risk-register.json", risks),
        ("source-index.json", source_index),
        ("wave-membership.json", membership),
        ("manifest.json", manifest),
    ]:
        if obj.get("status") != EXPECTED_STATUS:
            fail(f"{name} status mismatch: {obj.get('status')}")

    nodes = matrix["source_nodes"]
    rows = matrix["acceptance_rows"]
    base_rows = matrix["base_groups"]
    r1_rows = matrix["r1_quality_rows"]
    cards = cards_doc["cards"]
    risk_rows = risks["risks"]

    feedback_results: list[tuple[str, bool, str]] = []

    def feedback_check(check_id: str, passed: bool, detail: str) -> None:
        feedback_results.append((check_id, passed, detail))

    addendum_errors = d203_addendum_errors(matrix)
    feedback_check(
        "P1-1c-d203-addendum-origin",
        not addendum_errors,
        f"d203_addendum_errors={addendum_errors}",
    )
    d203_contract_errors = d203_semantic_contract_errors(
        semantics,
        rows,
        cards,
        risks,
    )
    feedback_check(
        "P1-1d-d203-specialized-semantics",
        not d203_contract_errors,
        f"d203_contract_errors={d203_contract_errors}",
    )
    w1_tag_errors = tts_w1_action_tag_errors(cards, membership)
    feedback_check(
        "P1-1e-tts-w1-fixed-engine-tags",
        not w1_tag_errors,
        f"w1_tag_errors={w1_tag_errors}",
    )

    card_ids = {card["id"] for card in cards}
    evidence_entries = {
        item["id"]: item for item in source_index.get("evidence_packages", [])
    }
    evidence_ids = set(evidence_entries)
    authority_text = "\n".join(
        (ROOT / item["path"]).read_text(encoding="utf-8")
        for item in source_index.get("document_sources", [])
    )
    defined_decision_ids = set(re.findall(r"V2-(?:D\d{3}|Q-[A-Z0-9-]+)", authority_text))

    self_ref_rows = [
        row["promise_id"]
        for row in rows
        if "EV-S6" in row.get("automation_fixture_evidence", {}).get("evidence_ids", [])
    ]
    undefined_row_evidence = [
        (row["promise_id"], evidence_id)
        for row in rows
        for evidence_id in row.get("automation_fixture_evidence", {}).get("evidence_ids", [])
        if evidence_id not in evidence_ids
    ]
    feedback_check(
        "P1-1a-row-self-evidence",
        not self_ref_rows and not undefined_row_evidence,
        f"rows_with_EV-S6={len(self_ref_rows)}; sample={self_ref_rows[:5]}; undefined_row_evidence={undefined_row_evidence[:5]}",
    )

    semantic_errors = semantic_registry_errors(semantics, rows, nodes, source_index)
    feedback_check(
        "P1-1b-explicit-row-semantics",
        not semantic_errors,
        f"semantic_errors={len(semantic_errors)}; sample={semantic_errors[:5]}",
    )

    undefined_card_refs = []
    card_self_refs = []
    for card in cards:
        for field in ("deps", "evidence"):
            for value in card.get(field, []):
                if value == "EV-S6":
                    card_self_refs.append((card["id"], field, value))
                if value.startswith("EV-") and value not in evidence_ids:
                    undefined_card_refs.append((card["id"], field, value))
                if value.startswith("V2-") and value not in defined_decision_ids:
                    undefined_card_refs.append((card["id"], field, value))
    feedback_check(
        "P1-2a-card-evidence-defined",
        not undefined_card_refs and not card_self_refs,
        f"undefined_card_evidence_refs={undefined_card_refs}; card_EV-S6_refs={card_self_refs}",
    )

    missing_card_deps = [card["id"] for card in cards if "card_dependencies" not in card]
    card_graph = {card["id"]: card.get("card_dependencies", []) for card in cards}
    undefined_card_deps = [
        (card_id, dep)
        for card_id, deps in card_graph.items()
        for dep in deps
        if dep not in card_ids
    ]
    feedback_check(
        "P1-2b-card-dag",
        not missing_card_deps and not undefined_card_deps and not has_cycle(card_graph),
        f"missing={missing_card_deps}; undefined={undefined_card_deps}; cycle={has_cycle(card_graph)}",
    )

    missing_evidence_deps = [
        evidence_id for evidence_id, item in evidence_entries.items() if "depends_on" not in item
    ]
    evidence_graph = {
        evidence_id: item.get("depends_on", [])
        for evidence_id, item in evidence_entries.items()
    }
    undefined_evidence_deps = [
        (source_id, dep)
        for source_id, deps in evidence_graph.items()
        for dep in deps
        if dep not in evidence_ids
    ]
    feedback_check(
        "P1-3a-evidence-dag",
        not missing_evidence_deps
        and not undefined_evidence_deps
        and not has_cycle(evidence_graph),
        f"missing={missing_evidence_deps}; undefined={undefined_evidence_deps}; cycle={has_cycle(evidence_graph)}",
    )

    boundary_errors = wave_boundary_errors(cards)
    feedback_check(
        "P1-2c-wave-boundaries-and-dag",
        not boundary_errors,
        f"boundary_errors={len(boundary_errors)}; sample={boundary_errors[:6]}",
    )

    all_promise_ids = {
        row["promise_id"] for row in rows + base_rows + r1_rows
    }
    contract_errors = reference_contract_errors(
        cards,
        source_index,
        defined_decision_ids,
        all_promise_ids,
    )
    feedback_check(
        "P1-2d-wave-row-contract",
        not contract_errors,
        f"wave_contract_errors={len(contract_errors)}; sample={contract_errors[:8]}",
    )
    row_waves = row_wave_errors(rows + base_rows + r1_rows, cards, membership)
    feedback_check(
        "P1-2e-matrix-row-wave-map",
        not row_waves,
        f"row_wave_errors={len(row_waves)}; sample={row_waves[:8]}",
    )

    owner_mismatches = []
    for row in rows:
        owner = row.get("owner", "")
        if owner.startswith("G2_CARD:"):
            owner_card = owner.split(":", 1)[1]
            if owner_card not in card_ids or owner_card not in row.get("g2_card_ids", []):
                owner_mismatches.append(
                    (row["promise_id"], owner_card, row.get("g2_card_ids", []))
                )
    feedback_check(
        "P2-1-owner-card-consistency",
        not owner_mismatches,
        f"owner_mismatches={owner_mismatches[:8]}",
    )

    hash_failures = []
    for item in source_index.get("document_sources", []):
        path = ROOT / item["path"]
        expected = item.get("sha256")
        actual = sha256(path.read_bytes()) if path.exists() else None
        if actual != expected:
            hash_failures.append((item["id"], expected, actual))
    for item in source_index.get("evidence_packages", []):
        if item.get("sha256_at_generation") is None:
            continue
        path = ROOT / item["path"]
        expected = item.get("sha256_at_generation")
        actual = sha256(path.read_bytes()) if path.exists() else None
        if actual != expected:
            hash_failures.append((item["id"], expected, actual))
    registry_index = source_index.get("semantic_registry", {})
    registry_path = OUT / registry_index.get("path", "__missing__")
    registry_actual = sha256(registry_path.read_bytes()) if registry_path.is_file() else None
    if registry_actual != registry_index.get("sha256"):
        hash_failures.append(
            ("SEMANTIC-REGISTRY", registry_index.get("sha256"), registry_actual)
        )
    if registry_index.get("record_count") != len(rows):
        hash_failures.append(
            (
                "SEMANTIC-REGISTRY-COUNT",
                len(rows),
                registry_index.get("record_count"),
            )
        )
    membership_index = source_index.get("wave_membership_registry", {})
    membership_path = OUT / membership_index.get("path", "__missing__")
    membership_actual = (
        sha256(membership_path.read_bytes()) if membership_path.is_file() else None
    )
    if membership_actual != membership_index.get("sha256"):
        hash_failures.append(
            (
                "WAVE-MEMBERSHIP-REGISTRY",
                membership_index.get("sha256"),
                membership_actual,
            )
        )
    if membership_index.get("membership_record_count") != len(
        membership.get("memberships", [])
    ):
        hash_failures.append(
            (
                "WAVE-MEMBERSHIP-COUNT",
                len(membership.get("memberships", [])),
                membership_index.get("membership_record_count"),
            )
        )
    expected_origin_counts = {
        key: matrix["counts"].get(key)
        for key in (
            "external_source_nodes",
            "project_addendum_source_nodes",
            "normalized_source_nodes",
            "external_acceptance_rows",
            "project_addendum_acceptance_rows",
            "normalized_acceptance_rows",
            "external_mvp_subitems",
            "project_addendum_mvp_subitems",
            "normalized_mvp_subitems",
        )
    }
    if source_index.get("source_origin_counts") != expected_origin_counts:
        hash_failures.append(
            (
                "SOURCE-ORIGIN-COUNTS",
                expected_origin_counts,
                source_index.get("source_origin_counts"),
            )
        )
    feedback_check(
        "P1-3b-source-index-byte-hashes",
        not hash_failures,
        f"source_hash_failures={hash_failures[:8]}",
    )

    threshold_by_id = {item["id"]: item for item in thresholds["thresholds"]}
    contrast_status = {
        key: threshold_by_id.get(key, {}).get("status")
        for key in ("T-TEXT-CONTRAST", "T-BOUNDARY-CONTRAST", "T-TOTAL-CONTRAST")
    }
    feedback_check(
        "P2-2-contrast-thresholds-split",
        "110/110" in (contrast_status["T-TEXT-CONTRAST"] or "")
        and "20/20" in (contrast_status["T-BOUNDARY-CONTRAST"] or "")
        and "130/130" in (contrast_status["T-TOTAL-CONTRAST"] or ""),
        f"contrast_status={contrast_status}",
    )

    nav_noise = nav_promise_noise_errors(rows)
    feedback_check(
        "P2-3-nav-user-promise-clean",
        not nav_noise,
        f"nav_promise_noise={nav_noise}",
    )

    a11y_errors = a11y_threshold_errors(thresholds["thresholds"])
    feedback_check(
        "P2-4-accessible-name-threshold",
        not a11y_errors,
        f"a11y_threshold_errors={a11y_errors}",
    )

    if any(not passed for _, passed, _ in feedback_results):
        print("FEEDBACK_RED")
        for check_id, passed, detail in feedback_results:
            print(f"{'PASS' if passed else 'FAIL'} {check_id}: {detail}")
        raise SystemExit(1)
    print("FEEDBACK_GREEN")
    for check_id, _, detail in feedback_results:
        print(f"PASS {check_id}: {detail}")

    source_ids = [node["source_id"] for node in nodes]
    if len(nodes) != 449 or len(set(source_ids)) != 449:
        fail(f"source nodes expected 449 unique, got {len(nodes)} / {len(set(source_ids))}")
    external_nodes = [node for node in nodes if node.get("source_origin") == "EXTERNAL_G0"]
    addendum_nodes = [
        node for node in nodes if node.get("source_origin") == "PROJECT_G0_ADDENDUM"
    ]
    if len(external_nodes) != 445 or len(addendum_nodes) != 4:
        fail(
            f"source origin counts external={len(external_nodes)} addendum={len(addendum_nodes)}"
        )
    domain_counts = Counter(node["domain"] for node in nodes)
    expected_domains = {
        "ARCH": 72,
        "UX": 38,
        "READER": 90,
        "WRITE": 13,
        "MUSIC": 62,
        "DOWNLOAD": 68,
        "VIDEO": 97,
        "NAV": 9,
    }
    if dict(domain_counts) != expected_domains:
        fail(f"domain source counts mismatch: {dict(domain_counts)}")
    external_domain_counts = Counter(node["domain"] for node in external_nodes)
    expected_external_domains = {**expected_domains, "READER": 86}
    if dict(external_domain_counts) != expected_external_domains:
        fail(f"external domain source counts mismatch: {dict(external_domain_counts)}")
    expected_addendum_ids = {
        f"V2-REQ-READER-{number:03d}" for number in range(87, 91)
    }
    if {node["source_id"] for node in addendum_nodes} != expected_addendum_ids:
        fail("D203 project addendum IDs mismatch")
    reader_ids = sorted(
        node["source_id"]
        for node in nodes
        if node["domain"] == "READER"
    )
    expected_reader_ids = [
        f"V2-REQ-READER-{number:03d}" for number in range(1, 91)
    ]
    if reader_ids != expected_reader_ids:
        fail("READER source IDs are not continuous V2-REQ-READER-001..090")
    if matrix["counts"].get("normalized_source_nodes") != 449:
        fail("normalized_source_nodes must be 449")
    if matrix["counts"].get("external_source_nodes") != 445:
        fail("external_source_nodes must remain 445")
    if matrix["counts"].get("project_addendum_source_nodes") != 4:
        fail("project_addendum_source_nodes must be 4")
    if matrix["counts"].get("compound_source_nodes") != 11:
        fail("compound_source_nodes must remain 11")
    if matrix["counts"].get("process_source_nodes") != 7:
        fail("process_source_nodes must remain 7")
    if matrix["counts"].get("reader_matrix_source_nodes") != 90:
        fail("reader_matrix_source_nodes must be 90")
    reader_single_must = sum(
        1
        for node in nodes
        if node["domain"] == "READER"
        and not node["is_compound"]
        and not node["is_process"]
        and node["levels"] == ["MUST"]
    )
    if reader_single_must != 67 or matrix["counts"].get("reader_single_level_must") != 67:
        fail(
            f"reader single-level MUST expected 67, got {reader_single_must} / "
            f"{matrix['counts'].get('reader_single_level_must')}"
        )
    if len(base_rows) != 5:
        fail(f"BASE groups expected 5, got {len(base_rows)}")
    if len({row['source_ids'][0] for row in base_rows}) != 5:
        fail("BASE source IDs not unique")

    promise_ids = [row["promise_id"] for row in rows]
    if len(promise_ids) != len(set(promise_ids)):
        fail("duplicate promise_id in acceptance rows")
    external_rows = [row for row in rows if row.get("source_origin") == "EXTERNAL_G0"]
    addendum_rows = [
        row for row in rows if row.get("source_origin") == "PROJECT_G0_ADDENDUM"
    ]
    if len(rows) != 460 or len(external_rows) != 456 or len(addendum_rows) != 4:
        fail(
            f"acceptance rows expected normalized=460 external=456 addendum=4, "
            f"got {len(rows)}/{len(external_rows)}/{len(addendum_rows)}"
        )
    if {row["parent_source_id"] for row in addendum_rows} != expected_addendum_ids:
        fail("D203 addendum acceptance row IDs mismatch")
    mvp_rows = [row for row in rows if row["mvp"]]
    external_mvp_rows = [
        row for row in mvp_rows if row.get("source_origin") == "EXTERNAL_G0"
    ]
    addendum_mvp_rows = [
        row for row in mvp_rows if row.get("source_origin") == "PROJECT_G0_ADDENDUM"
    ]
    if len(mvp_rows) != 380:
        fail(f"MVP subitems expected 380, got {len(mvp_rows)}")
    if len(external_mvp_rows) != 376 or len(addendum_mvp_rows) != 4:
        fail(
            f"MVP origin counts external={len(external_mvp_rows)} "
            f"addendum={len(addendum_mvp_rows)}"
        )
    if matrix["counts"].get("normalized_mvp_subitems") != 380:
        fail("normalized_mvp_subitems must be 380")
    mvp_domains = Counter(row["domain"] for row in mvp_rows)
    expected_mvp_domains = {
        "ARCH": 69,
        "UX": 21,
        "READER": 70,
        "WRITE": 12,
        "MUSIC": 50,
        "DOWNLOAD": 64,
        "VIDEO": 88,
        "NAV": 6,
    }
    if dict(mvp_domains) != expected_mvp_domains:
        fail(f"MVP domain counts mismatch: {dict(mvp_domains)}")
    if matrix["counts"].get("reader_mvp_subitems") != 70:
        fail("reader_mvp_subitems must be 70")
    external_mvp_domains = Counter(row["domain"] for row in external_mvp_rows)
    if dict(external_mvp_domains) != {**expected_mvp_domains, "READER": 66}:
        fail(f"external MVP domain counts mismatch: {dict(external_mvp_domains)}")
    if {row["parent_source_id"] for row in addendum_mvp_rows} != expected_addendum_ids:
        fail("D203 addendum MVP IDs mismatch")

    required = [
        "promise_id",
        "parent_source_id",
        "subitem_id",
        "domain",
        "source_ids",
        "level",
        "mvp",
        "user_promise",
        "entry",
        "operation",
        "observable_result",
        "states_accessibility_rwd",
        "e0_e7_strength",
        "automation_fixture_evidence",
        "candidate_device_evidence",
        "threshold_ids",
        "uncovered",
        "g2_card_ids",
        "g2_wave_ids",
        "owner",
    ]
    for row in rows:
        require_nonempty(row, required, row["promise_id"])
        if row["level"] in {"MUST", "SHOULD"} and not row["g2_card_ids"]:
            fail(f"{row['promise_id']} has no G2 card")
        if row["level"] == "MUST" and not row["owner"].startswith(("G2_CARD:", "BLOCKED_Q03_")):
            fail(f"{row['promise_id']} MUST owner is not actionable/gated: {row['owner']}")
        if row["candidate_device_evidence"].get("status") != "NONE_AT_S6":
            fail(f"{row['promise_id']} unexpectedly claims candidate evidence")
        if row["e0_e7_strength"].get("runtime_executed") is not False:
            fail(f"{row['promise_id']} runtime_executed must be false")

    for row in base_rows:
        require_nonempty(
            row,
            [
                "promise_id",
                "source_ids",
                "level",
                "user_promise",
                "entry",
                "operation",
                "observable_result",
                "e0_e7_strength",
                "candidate_device_evidence",
                "uncovered",
                "g2_card_ids",
                "g2_wave_ids",
                "owner",
            ],
            row["promise_id"],
        )
    if sum(1 for row in r1_rows if row["source_ids"][0].startswith("R1-THEME-")) != 5:
        fail("R1 theme rows expected 5")
    if sum(1 for row in r1_rows if row["source_ids"][0] in {f"R1-I{i:02d}" for i in range(1, 10)}) != 9:
        fail("R1 interaction rows expected 9")
    if sum(1 for row in r1_rows if row["source_ids"][0].startswith("R1-RWD-")) != 6:
        fail("R1 RWD rows expected 6")
    for required_pid in {
        "R1-A11Y-CONTRAST",
        "R1-A11Y-TOUCH-48",
        "R1-A11Y-NAMES",
        "R1-A11Y-REFOLD-200",
        "R1-A11Y-REDUCE-MOTION",
        "R1-READER-BODY-LOCAL-THEME",
    }:
        if required_pid not in {row["promise_id"] for row in r1_rows}:
            fail(f"required R1 row missing: {required_pid}")

    card_id_list = [card["id"] for card in cards]
    if len(card_id_list) != len(set(card_id_list)):
        fail("duplicate G2 card ID")
    for card in cards:
        if len(card.get("non_goals", [])) < 2:
            fail(f"{card['id']} non_goals < 2")
        if not card.get("acceptance_criteria"):
            fail(f"{card['id']} missing acceptance criteria")
        if card.get("status") != "READY_FOR_G2_PLANNING" and not card.get("status", "").startswith("GATED_"):
            fail(f"{card['id']} invalid status")
    for card_id in card_id_list:
        card = next(item for item in cards if item["id"] == card_id)
        if not (
            any(card_id in row["g2_card_ids"] for row in rows)
            or card.get("covers_base_groups")
            or card.get("covers_r1_promises")
        ):
            fail(f"{card_id} covers no accepted external, BASE or R1 row")
    q03_gated = {
        "G2-READER-TTS-GATED",
        "G2-READER-PDF-GATED",
        "G2-SYNCPLAY-GATED",
        "G2-Q03-SUPPLY-CHAIN-GATED",
    }
    if not q03_gated.issubset(card_ids):
        fail(f"Q03 gated cards missing: {sorted(q03_gated - card_ids)}")
    for card_id in q03_gated:
        card = next(item for item in cards if item["id"] == card_id)
        if not card["status"].startswith("GATED_"):
            fail(f"{card_id} must remain gated")

    risk_ids = [risk["id"] for risk in risk_rows]
    if len(risk_ids) != len(set(risk_ids)):
        fail("duplicate risk ID")
    for risk in risk_rows:
        require_nonempty(
            risk,
            ["id", "severity", "title", "source", "impact", "gate", "closure_evidence"],
            risk["id"],
        )
        if risk["severity"] not in {"P0", "P1", "P2", "P3"}:
            fail(f"{risk['id']} invalid severity")
    if risks["severity_counts"].get("P0", 0) < 1:
        fail("at least one P0 risk required")

    threshold_ids = [item["id"] for item in thresholds["thresholds"]]
    if len(threshold_ids) != len(set(threshold_ids)):
        fail("duplicate threshold ID")
    accepted_ids = set(source_ids) | {row["promise_id"] for row in r1_rows} | {
        base["source_ids"][0] for base in base_rows
    } | {"V2-Q-G1-03", "V2-D057", "V2-D201"}
    for item in thresholds["thresholds"]:
        require_nonempty(
            item,
            ["id", "requirement_ids", "metric", "operator", "unit", "environment", "evidence_level", "status", "source"],
            item["id"],
        )
        for req_id in item["requirement_ids"]:
            if req_id not in accepted_ids:
                fail(f"{item['id']} references unknown requirement {req_id}")

    boundaries = matrix["global_boundaries"]
    if boundaries != {
        "g1": "NOT_PASSED",
        "q03": "OPEN",
        "q05": "OPEN_TTS_ENGINE_STRATEGY",
        "q04": "CLOSED_D201",
        "ui_design_system": "V2_R1_ACCEPTED_DESIGN_AUTHORITY",
        "runtime": "UNVERIFIED",
        "s6": "SCOPED_REVIEW_PASS_WITH_P2",
        "d203": "ACCEPTED_VOICE_PACK_BACKGROUND_TTS",
        "sherpa_quality": "SHERPA_QUALITY_INSUFFICIENT_COMPAT_FALLBACK",
        "c1_exact_profile": "UNFROZEN_WAIT_ENGINE_STRATEGY_AND_EXPRESSIVE_PROFILE",
        "l2_l3": "LOCKED",
    }:
        fail(f"global boundaries mismatch: {boundaries}")

    if len(source_index["evidence_packages"]) < 15:
        fail("source index evidence package coverage too small")
    for doc in source_index["document_sources"] + source_index["evidence_packages"]:
        if "path" in doc:
            path = ROOT / doc["path"]
        else:
            path = OUT / doc["path"]
        if not path.exists():
            fail(f"source index path missing: {doc['path']}")

    listed = manifest["entries"]
    listed_paths = [item["path"] for item in listed]
    if "manifest.json" in listed_paths:
        fail("manifest must exclude itself")
    if len(listed_paths) != len(set(listed_paths)):
        fail("manifest duplicate paths")
    for item in listed:
        path = OUT / item["path"]
        if not path.exists():
            fail(f"manifest file missing: {item['path']}")
        data = path.read_bytes()
        if len(data) != item["bytes"]:
            fail(f"{item['path']} bytes mismatch")
        if sha256(data) != item["sha256"]:
            fail(f"{item['path']} SHA-256 mismatch")
        lf = data.count(b"\n")
        crlf = data.count(b"\r\n")
        if lf != item["lf"] or crlf != item["crlf"]:
            fail(f"{item['path']} LF/CRLF mismatch")
        if crlf != 0:
            fail(f"{item['path']} contains CRLF")

    expected_files = {
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
    }
    if set(listed_paths) != expected_files:
        fail(f"manifest file set mismatch: {sorted(set(listed_paths) ^ expected_files)}")

    manifest_bytes = (OUT / "manifest.json").read_bytes()
    semantic_metrics = semantic_shape_metrics(semantics)
    all_waves = [
        wave
        for card in cards
        for wave in card.get("delivery_waves", [])
    ]
    print("PASS")
    print(f"normalized_source_nodes={len(nodes)}")
    print(f"external_source_nodes={len(external_nodes)}")
    print(f"project_addendum_source_nodes={len(addendum_nodes)}")
    print(f"reader_matrix_source_nodes={sum(1 for node in nodes if node['domain'] == 'READER')}")
    print(f"reader_single_level_must={reader_single_must}")
    print(f"normalized_acceptance_rows={len(rows)}")
    print(f"external_acceptance_rows={len(external_rows)}")
    print(f"project_addendum_acceptance_rows={len(addendum_rows)}")
    print(f"explicit_semantic_records={len(semantics.get('rows', []))}")
    print(
        "semantic_operation_audit="
        + json.dumps(semantic_metrics, ensure_ascii=False, separators=(",", ":"))
    )
    print(f"normalized_mvp_subitems={len(mvp_rows)}")
    print(f"external_mvp_subitems={len(external_mvp_rows)}")
    print(f"project_addendum_mvp_subitems={len(addendum_mvp_rows)}")
    print(f"reader_mvp_subitems={sum(1 for row in mvp_rows if row['domain'] == 'READER')}")
    print("domain_sources=" + json.dumps(dict(sorted(domain_counts.items())), ensure_ascii=False, separators=(",", ":")))
    print("domain_mvp=" + json.dumps(dict(sorted(mvp_domains.items())), ensure_ascii=False, separators=(",", ":")))
    print(f"base_groups={len(base_rows)}")
    print(f"r1_cross_cutting_promises={len(r1_rows)}")
    print(f"g2_cards={len(cards)}")
    print(f"g2_delivery_waves={sum(len(card.get('delivery_waves', [])) for card in cards)}")
    print(
        "waves_with_owner_promises_structured_artifacts="
        f"{sum(1 for wave in all_waves if wave.get('owner') and wave.get('promise_ids') and all(isinstance(item, dict) for item in wave.get('acceptance_artifacts', [])))}"
    )
    print(
        "matrix_rows_with_wave_map="
        f"{sum(1 for row in rows + base_rows + r1_rows if row.get('g2_wave_ids'))}"
    )
    print(f"wave_membership_records={len(membership.get('memberships', []))}")
    print(
        "row_wave_edges="
        f"{sum(len(row.get('g2_wave_ids', [])) for row in rows + base_rows + r1_rows)}"
    )
    print(
        "row_wave_capability_mismatch="
        f"{sum('capability mismatch' in error for error in row_waves)}"
    )
    print(f"card_EV-S6_refs={len(card_self_refs)}")
    print(
        "max_wave_boundary_entries="
        f"{max(len(wave.get('file_interface_boundaries', [])) for card in cards for wave in card.get('delivery_waves', []))}"
    )
    print("risk_counts=" + json.dumps(risks["severity_counts"], ensure_ascii=False, separators=(",", ":")))
    print(f"thresholds={len(thresholds['thresholds'])}")
    print(f"manifest_files_excluding_manifest={len(listed)}")
    print(f"manifest_total_bytes_excluding_manifest={manifest['total_bytes_excluding_manifest']}")
    print(f"manifest_bytes={len(manifest_bytes)}")
    print(f"manifest_sha256={sha256(manifest_bytes)}")
    print("eol=PASS_LF_ONLY")
    print("runtime_executed=false")
    print("g1_passed=false")
    print("q03=OPEN")
    print("q05=OPEN")
    print("q04=CLOSED_D201")


if __name__ == "__main__":
    main()
