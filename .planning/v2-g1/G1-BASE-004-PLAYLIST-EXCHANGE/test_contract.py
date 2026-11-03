#!/usr/bin/env python3
"""Static contract harness for G1-BASE-004.

This file is evidence, not a production parser. It validates the versioned JSON
schema with a small local JSON Schema subset and exercises synthetic fixtures.
"""

from __future__ import annotations

import base64
import hashlib
import json
import math
import re
import sys
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parent
FIXTURES = ROOT / "fixtures"
SCHEMA_PATH = ROOT / "schemas" / "local-playlist-exchange.v1.schema.json"
ROUNDTRIP_PATH = ROOT / "roundtrip-matrix.json"
EXPECTATIONS_PATH = FIXTURES / "expected-results.json"
RESULTS_PATH = ROOT / "test-results.json"
MANIFEST_PATH = ROOT / "manifest.json"
TEXT_SUFFIXES = {".json", ".md", ".m3u8", ".py", ".log"}
INPUT_BASELINE = "6fc0a02995bb24c11237fe3b8753f21f06a57f72"
INPUT_BRANCH = "codex/v2-integration"
SECRET_PATTERNS = [
    re.compile(r"\bBearer\s+[A-Za-z0-9._~+/=-]{12,}"),
    re.compile(r"(?i)(?:api[_-]?key|access_token|auth_token|client_secret)=([^&\s\"']+)"),
    re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"),
    re.compile(r"\bgh[pousr]_[A-Za-z0-9]{20,}\b"),
    re.compile(r"\bsk-[A-Za-z0-9]{20,}\b"),
]


class JsonSchemaSubset:
    """Validate the JSON Schema keywords used by this evidence schema."""

    def __init__(self, schema: dict[str, Any]) -> None:
        self.root = schema

    def errors(self, instance: Any) -> list[str]:
        errors: list[str] = []
        self._validate(instance, self.root, "$", errors)
        return errors

    def _resolve(self, ref: str) -> dict[str, Any]:
        if not ref.startswith("#/"):
            raise ValueError(f"unsupported external ref: {ref}")
        node: Any = self.root
        for raw in ref[2:].split("/"):
            token = raw.replace("~1", "/").replace("~0", "~")
            node = node[token]
        if not isinstance(node, dict):
            raise ValueError(f"ref is not an object schema: {ref}")
        return node

    def _type_ok(self, value: Any, expected: str) -> bool:
        return {
            "object": isinstance(value, dict),
            "array": isinstance(value, list),
            "string": isinstance(value, str),
            "integer": isinstance(value, int) and not isinstance(value, bool),
            "number": isinstance(value, (int, float)) and not isinstance(value, bool),
            "boolean": isinstance(value, bool),
            "null": value is None,
        }.get(expected, True)

    def _validate(
        self,
        instance: Any,
        schema: dict[str, Any],
        path: str,
        errors: list[str],
    ) -> None:
        if "$ref" in schema:
            self._validate(instance, self._resolve(schema["$ref"]), path, errors)

        for sub in schema.get("allOf", []):
            self._validate(instance, sub, path, errors)

        if "oneOf" in schema:
            matches = []
            branch_errors: list[list[str]] = []
            for sub in schema["oneOf"]:
                local: list[str] = []
                self._validate(instance, sub, path, local)
                branch_errors.append(local)
                if not local:
                    matches.append(sub)
            if len(matches) != 1:
                errors.append(f"{path}: oneOf matched {len(matches)} branches")

        if "anyOf" in schema:
            valid = False
            for sub in schema["anyOf"]:
                local: list[str] = []
                self._validate(instance, sub, path, local)
                valid = valid or not local
            if not valid:
                errors.append(f"{path}: anyOf matched no branch")

        if "not" in schema:
            local: list[str] = []
            self._validate(instance, schema["not"], path, local)
            if not local:
                errors.append(f"{path}: prohibited by not")

        if "const" in schema and instance != schema["const"]:
            errors.append(f"{path}: expected const {schema['const']!r}")
        if "enum" in schema and instance not in schema["enum"]:
            errors.append(f"{path}: value not in enum")

        expected_type = schema.get("type")
        if expected_type is not None:
            types = expected_type if isinstance(expected_type, list) else [expected_type]
            if not any(self._type_ok(instance, item) for item in types):
                errors.append(f"{path}: expected type {types}, got {type(instance).__name__}")
                return

        if isinstance(instance, dict):
            required = schema.get("required", [])
            for key in required:
                if key not in instance:
                    errors.append(f"{path}: missing required property {key}")
            properties = schema.get("properties", {})
            for key, value in instance.items():
                if key in properties:
                    self._validate(value, properties[key], f"{path}.{key}", errors)
                else:
                    additional = schema.get("additionalProperties", True)
                    if additional is False:
                        errors.append(f"{path}: additional property {key} is forbidden")
                    elif isinstance(additional, dict):
                        self._validate(value, additional, f"{path}.{key}", errors)

        if isinstance(instance, list):
            if len(instance) < schema.get("minItems", 0):
                errors.append(f"{path}: fewer than minItems")
            if "maxItems" in schema and len(instance) > schema["maxItems"]:
                errors.append(f"{path}: more than maxItems")
            if schema.get("uniqueItems"):
                canon = [canonical_json(item) for item in instance]
                if len(canon) != len(set(canon)):
                    errors.append(f"{path}: duplicate array item")
            item_schema = schema.get("items")
            if isinstance(item_schema, dict):
                for index, item in enumerate(instance):
                    self._validate(item, item_schema, f"{path}[{index}]", errors)

        if isinstance(instance, str):
            if len(instance) < schema.get("minLength", 0):
                errors.append(f"{path}: shorter than minLength")
            if "pattern" in schema and re.search(schema["pattern"], instance) is None:
                errors.append(f"{path}: does not match pattern")

        if isinstance(instance, (int, float)) and not isinstance(instance, bool):
            if "minimum" in schema and instance < schema["minimum"]:
                errors.append(f"{path}: below minimum")
            if "maximum" in schema and instance > schema["maximum"]:
                errors.append(f"{path}: above maximum")


def load_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def canonical_json(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest().upper()


def b64url_encode(value: Any) -> str:
    raw = canonical_json(value).encode("utf-8")
    return base64.urlsafe_b64encode(raw).decode("ascii").rstrip("=")


def b64url_decode(value: str) -> Any:
    padded = value + "=" * (-len(value) % 4)
    raw = base64.urlsafe_b64decode(padded.encode("ascii"))
    return json.loads(raw.decode("utf-8"))


def identity_key(entry: dict[str, Any]) -> tuple[Any, ...]:
    identity = entry["identity"]
    kind = identity["sourceKind"]
    if kind == "SERVER":
        domain = identity["matchDomain"]
        base = (
            kind,
            domain,
            identity["identityVersion"],
            identity["serverId"],
            identity["userId"],
            identity["libraryId"],
            identity["itemId"],
        )
        if domain == "SOURCE":
            return base + (identity["sourceToken"],)
        return base + (None,)
    if kind == "LOCAL":
        return (
            kind,
            identity["identityVersion"],
            identity["localItemId"],
            identity["persistentUri"],
        )
    return (kind, identity["identityVersion"], identity["legacyRecordKey"])


def analyze_document(
    document: dict[str, Any],
    supported_features: set[str],
) -> dict[str, Any]:
    issues: set[str] = set()
    if document.get("schemaVersion") != 1:
        return {
            "decision": "REJECT",
            "entryCount": 0,
            "missingPreserved": 0,
            "duplicateIdentities": 0,
            "identityConflicts": 0,
            "crossSourceCollisions": 0,
            "titleCollisions": 0,
            "writesCommitted": 0,
            "issueCodes": ["VERSION_UNSUPPORTED"],
        }

    required = set(document.get("requiredFeatures", []))
    if not required.issubset(supported_features):
        return {
            "decision": "REJECT",
            "entryCount": 0,
            "missingPreserved": 0,
            "duplicateIdentities": 0,
            "identityConflicts": 0,
            "crossSourceCollisions": 0,
            "titleCollisions": 0,
            "writesCommitted": 0,
            "issueCodes": ["FEATURE_UNSUPPORTED"],
        }

    entries = document.get("entries", [])
    entry_ids = [entry["entryId"] for entry in entries]
    positions = [entry["position"] for entry in entries]
    order_ok = (
        len(entry_ids) == len(set(entry_ids))
        and len(positions) == len(set(positions))
        and sorted(positions) == list(range(len(entries)))
        and positions == list(range(len(entries)))
    )
    if not order_ok:
        issues.add("ENTRY_ORDER_INVALID")

    missing = sum(
        1 for entry in entries if entry["availability"]["status"] == "MISSING"
    )
    if missing:
        issues.add("MISSING_TRACK_PRESERVED")

    if any(
        entry["identity"]["sourceKind"] == "UNKNOWN" for entry in entries
    ):
        issues.add("UNKNOWN_PROVENANCE_PRESERVED")

    groups: dict[tuple[Any, ...], list[dict[str, Any]]] = defaultdict(list)
    for entry in entries:
        groups[identity_key(entry)].append(entry)

    duplicate_count = sum(len(group) - 1 for group in groups.values())
    conflict_count = 0
    if duplicate_count:
        issues.add("DUPLICATE_IDENTITY_PRESERVED")
    for group in groups.values():
        if len(group) < 2:
            continue
        display_signatures = {canonical_json(item["display"]) for item in group}
        locator_signatures = {canonical_json(item["locator"]) for item in group}
        if len(display_signatures) > 1 or len(locator_signatures) > 1:
            conflict_count += 1
    if conflict_count:
        issues.add("IDENTITY_CONFLICT")
        issues.add("USER_DECISION_REQUIRED")

    server_by_item: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for entry in entries:
        identity = entry["identity"]
        if identity["sourceKind"] == "SERVER":
            server_by_item[identity["itemId"]].append(entry)
    cross_source = 0
    for group in server_by_item.values():
        if len({identity_key(entry) for entry in group}) > 1:
            cross_source += 1
    if cross_source:
        issues.add("CROSS_SOURCE_SAME_ITEM_PRESERVED")

    titles: dict[str, set[tuple[Any, ...]]] = defaultdict(set)
    for entry in entries:
        title = entry["display"]["title"].strip().casefold()
        titles[title].add(identity_key(entry))
    title_collisions = sum(1 for identities in titles.values() if len(identities) > 1)
    if title_collisions:
        issues.add("TITLE_COLLISION_NOT_MERGED")

    if "ENTRY_ORDER_INVALID" in issues:
        decision = "REJECT"
    elif conflict_count:
        decision = "REQUIRE_USER_CHOICE"
    elif issues:
        decision = "ACCEPT_WITH_WARNINGS"
    else:
        decision = "ACCEPT_ATOMIC"

    return {
        "decision": decision,
        "entryCount": len(entries),
        "missingPreserved": missing,
        "duplicateIdentities": duplicate_count,
        "identityConflicts": conflict_count,
        "crossSourceCollisions": cross_source,
        "titleCollisions": title_collisions,
        "writesCommitted": 0,
        "issueCodes": sorted(issues),
    }


def unknown_m3u8_entry(
    title: str,
    duration_ms: int,
    uri: str,
    line_number: int,
) -> dict[str, Any]:
    digest = sha256_bytes(uri.encode("utf-8"))[:16].lower()
    locator_kind = "LOCAL" if uri.startswith("content://") else "SERVER"
    return {
        "entryId": f"m3u8:{line_number}:{digest}",
        "position": -1,
        "identity": {
            "identityVersion": 1,
            "mediaKind": "AUDIO",
            "sourceKind": "UNKNOWN",
            "legacyRecordKey": f"m3u8:{line_number}:uri-sha256:{digest}",
            "provenanceComplete": False,
            "matchDomain": "NO_MERGE",
        },
        "display": {
            "title": title,
            "durationMs": duration_ms,
        },
        "availability": {
            "status": "UNRESOLVED",
            "reasonCode": "GENERIC_M3U8_IDENTITY_MISSING",
        },
        "locator": {
            "kind": locator_kind,
            "uri": uri,
        },
    }


def parse_m3u8(path: Path) -> dict[str, Any]:
    try:
        text = path.read_text(encoding="utf-8-sig")
    except UnicodeDecodeError:
        return {
            "entries": [],
            "issues": {"CORRUPT_ENCODING"},
            "comments": 0,
            "acceptedEntries": 0,
            "rejectedEntries": 0,
            "identityLost": 0,
            "documentMeta": None,
            "contractPresent": False,
        }

    lines = text.splitlines()
    issues: set[str] = set()
    entries: list[dict[str, Any]] = []
    comments = 0
    rejected = 0
    document_meta: dict[str, Any] | None = None
    contract_present = False
    pending_entry: dict[str, Any] | None = None
    current: dict[str, Any] | None = None

    if not lines or lines[0].strip() != "#EXTM3U":
        issues.add("MISSING_EXTM3U")

    for index, raw_line in enumerate(lines, start=1):
        line = raw_line.strip()
        if not line:
            continue
        if line == "#EXTM3U":
            continue
        if line.startswith("#AURORAMA-CONTRACT:"):
            contract_present = True
            value = line.split(":", 1)[1]
            if value != "aurorama.local-playlist.exchange;schema=1":
                issues.add("CONTRACT_MARKER_INVALID")
            continue
        if line.startswith("#AURORAMA-DOCUMENT-V1:"):
            try:
                decoded = b64url_decode(line.split(":", 1)[1])
                if not isinstance(decoded, dict):
                    raise ValueError("document marker is not an object")
                document_meta = decoded
            except (ValueError, json.JSONDecodeError, UnicodeDecodeError):
                issues.add("DOCUMENT_MARKER_CORRUPT")
            continue
        if line.startswith("#AURORAMA-ENTRY-V1:"):
            try:
                decoded = b64url_decode(line.split(":", 1)[1])
                if not isinstance(decoded, dict):
                    raise ValueError("entry marker is not an object")
                pending_entry = decoded
                entries.append(decoded)
            except (ValueError, json.JSONDecodeError, UnicodeDecodeError):
                issues.add("ENTRY_MARKER_CORRUPT")
                pending_entry = None
            continue
        if line.startswith("#EXTINF:"):
            payload = line.split(":", 1)[1]
            if "," not in payload:
                issues.add("BROKEN_EXTINF")
                rejected += 1
                current = None
                continue
            duration_text, title = payload.split(",", 1)
            try:
                seconds = float(duration_text)
                if not math.isfinite(seconds) or seconds < 0:
                    raise ValueError("duration outside contract")
            except ValueError:
                issues.add("BROKEN_EXTINF")
                rejected += 1
                current = None
                continue
            current = {
                "title": title,
                "durationMs": round(seconds * 1000),
            }
            continue
        if line.startswith("#EXT-X-VERSION:"):
            continue
        if line.startswith("#EXT-X-"):
            issues.add("HLS_PLAYLIST_UNSUPPORTED")
            continue
        if line.startswith("#"):
            comments += 1
            issues.add("COMMENT_IGNORED")
            continue

        if pending_entry is not None:
            expected_uri = pending_entry.get("locator", {}).get("uri")
            if expected_uri is not None and expected_uri != line:
                issues.add("ENTRY_MARKER_URI_CONFLICT")
            pending_entry = None
            current = None
            continue
        if current is None:
            continue
        entries.append(
            unknown_m3u8_entry(
                title=current["title"],
                duration_ms=current["durationMs"],
                uri=line,
                line_number=index,
            )
        )
        current = None

    if current is not None:
        issues.add("MISSING_URI")
        rejected += 1

    if rejected:
        issues.add("PARTIAL_IMPORT_REQUIRES_CONFIRMATION")

    identity_lost = sum(
        1 for entry in entries if entry["identity"]["sourceKind"] == "UNKNOWN"
    )
    if identity_lost and not contract_present:
        issues.add("GENERIC_M3U8_IDENTITY_LOST")

    return {
        "entries": entries,
        "issues": issues,
        "comments": comments,
        "acceptedEntries": len(entries),
        "rejectedEntries": rejected,
        "identityLost": identity_lost,
        "documentMeta": document_meta,
        "contractPresent": contract_present,
    }


def build_custom_m3u8(document: dict[str, Any]) -> str:
    meta = {key: value for key, value in document.items() if key != "entries"}
    lines = [
        "#EXTM3U",
        "#AURORAMA-CONTRACT:aurorama.local-playlist.exchange;schema=1",
        f"#AURORAMA-DOCUMENT-V1:{b64url_encode(meta)}",
    ]
    for entry in document["entries"]:
        lines.append(f"#AURORAMA-ENTRY-V1:{b64url_encode(entry)}")
        locator = entry["locator"]
        if locator["kind"] == "NONE":
            continue
        duration_ms = entry["display"].get("durationMs", 0)
        seconds = f"{duration_ms / 1000:.3f}"
        lines.append(f"#EXTINF:{seconds},{entry['display']['title']}")
        lines.append(locator["uri"])
    return "\n".join(lines) + "\n"


def reconstruct_custom_document(parsed: dict[str, Any]) -> dict[str, Any]:
    if parsed["documentMeta"] is None:
        raise ValueError("custom M3U8 has no document marker")
    document = dict(parsed["documentMeta"])
    document["entries"] = parsed["entries"]
    return document


def schema_property_declarations(schema: Any) -> int:
    if isinstance(schema, dict):
        count = len(schema.get("properties", {}))
        for value in schema.values():
            count += schema_property_declarations(value)
        return count
    if isinstance(schema, list):
        return sum(schema_property_declarations(value) for value in schema)
    return 0


def manifest_source() -> dict[str, str]:
    return {
        "branch": INPUT_BRANCH,
        "inputBaseline": INPUT_BASELINE,
    }


def text_files_for_checks() -> list[Path]:
    files = []
    for path in ROOT.rglob("*"):
        if (
            path.is_file()
            and path.suffix.lower() in TEXT_SUFFIXES
            and path.name != MANIFEST_PATH.name
            and "__pycache__" not in path.parts
        ):
            files.append(path)
    return sorted(files)


def find_sensitive_values(files: list[Path]) -> list[str]:
    hits = []
    for path in files:
        text = path.read_text(encoding="utf-8")
        for pattern in SECRET_PATTERNS:
            if pattern.search(text):
                hits.append(path.relative_to(ROOT).as_posix())
                break
    return hits


def write_json(path: Path, value: Any) -> None:
    payload = json.dumps(value, ensure_ascii=False, indent=2) + "\n"
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(payload, encoding="utf-8", newline="\n")
    temporary.replace(path)


def generate_manifest() -> dict[str, Any]:
    files = []
    for path in sorted(ROOT.rglob("*")):
        if (
            not path.is_file()
            or path == MANIFEST_PATH
            or "__pycache__" in path.parts
            or path.suffix.lower() not in TEXT_SUFFIXES
        ):
            continue
        data = path.read_bytes()
        files.append(
            {
                "path": path.relative_to(ROOT).as_posix(),
                "bytes": len(data),
                "sha256": sha256_bytes(data),
                "eol": "LF" if b"\r" not in data else "CRLF_OR_MIXED",
            }
        )
    manifest = {
        "format": "aurorama.base004.manifest",
        "schemaVersion": 1,
        "source": manifest_source(),
        "policy": {
            "textEncoding": "UTF-8",
            "textEol": "LF",
            "manifestExcludes": ["manifest.json"],
            "absolutePathsIncluded": False,
        },
        "counts": {
            "files": len(files),
            "bytes": sum(item["bytes"] for item in files),
        },
        "files": files,
    }
    write_json(MANIFEST_PATH, manifest)
    return manifest


def main() -> int:
    checks: list[dict[str, Any]] = []

    def check(name: str, condition: bool, detail: str = "") -> None:
        checks.append(
            {
                "id": name,
                "status": "PASS" if condition else "FAIL",
                "detail": detail,
            }
        )

    schema = load_json(SCHEMA_PATH)
    validator = JsonSchemaSubset(schema)
    expectations = load_json(EXPECTATIONS_PATH)
    supported_features = set(expectations["supportedFeatures"])
    expected_by_id = {case["id"]: case for case in expectations["cases"]}

    check(
        "schema-json-parse",
        isinstance(schema, dict)
        and schema.get("$schema") == "https://json-schema.org/draft/2020-12/schema",
    )

    valid_json_ids = {
        "positive-mixed-json",
        "missing-track",
        "duplicate-identity",
        "identity-conflict",
        "cross-source-same-item",
        "title-collision",
        "unknown-required-feature",
    }
    parsed_documents: dict[str, dict[str, Any]] = {}

    for case_id, expected in expected_by_id.items():
        if expected["kind"] != "JSON":
            continue
        fixture = FIXTURES / expected["fixture"]
        if expected["schemaValidity"] == "UNPARSEABLE":
            try:
                load_json(fixture)
                parse_failed = False
            except json.JSONDecodeError:
                parse_failed = True
            check(
                f"{case_id}-rejected-corrupt-json",
                parse_failed
                and expected["writesCommitted"] == 0
                and expected["decision"] == "REJECT"
                and "CORRUPT_JSON" in expected["issueCodes"],
            )
            continue

        document = load_json(fixture)
        parsed_documents[case_id] = document
        schema_errors = validator.errors(document)
        if case_id in valid_json_ids:
            check(
                f"{case_id}-schema-valid",
                not schema_errors,
                "; ".join(schema_errors[:3]),
            )
        else:
            check(
                f"{case_id}-schema-invalid",
                bool(schema_errors),
                "; ".join(schema_errors[:3]),
            )

        actual = analyze_document(document, supported_features)
        for field in (
            "decision",
            "entryCount",
            "missingPreserved",
            "duplicateIdentities",
            "identityConflicts",
            "crossSourceCollisions",
            "titleCollisions",
            "writesCommitted",
        ):
            check(
                f"{case_id}-{field}",
                actual[field] == expected[field],
                f"expected={expected[field]!r} actual={actual[field]!r}",
            )
        check(
            f"{case_id}-issues",
            actual["issueCodes"] == sorted(expected["issueCodes"]),
            f"expected={sorted(expected['issueCodes'])!r} actual={actual['issueCodes']!r}",
        )

    mixed = parsed_documents["positive-mixed-json"]
    json_roundtrip = json.loads(canonical_json(mixed))
    check("json-to-json-deep-equal", json_roundtrip == mixed)
    check(
        "unknown-fields-preserved",
        json_roundtrip.get("futureTopLevelField") == {"preserve": True}
        and json_roundtrip["extensions"]["futureOptional"]["source"]
        == "synthetic-fixture",
    )
    check(
        "title-is-not-identity",
        identity_key(mixed["entries"][0]) != identity_key(mixed["entries"][2]),
    )

    generic = parse_m3u8(FIXTURES / "generic-lossy.m3u8")
    generic_expected = expected_by_id["generic-m3u8-lossy"]
    check(
        "generic-m3u8-entry-count",
        len(generic["entries"]) == generic_expected["entryCount"],
    )
    check(
        "generic-m3u8-lossy-issues",
        generic["issues"] == set(generic_expected["issueCodes"]),
        repr(sorted(generic["issues"])),
    )
    check(
        "generic-m3u8-unknown-no-merge",
        generic["identityLost"] == 2
        and all(
            entry["identity"]["matchDomain"] == "NO_MERGE"
            for entry in generic["entries"]
        ),
    )

    partial = parse_m3u8(FIXTURES / "partial-import.m3u8")
    partial_expected = expected_by_id["partial-m3u8"]
    check(
        "partial-m3u8-counts",
        partial["acceptedEntries"] == partial_expected["acceptedEntries"]
        and partial["rejectedEntries"] == partial_expected["rejectedEntries"],
        (
            f"accepted={partial['acceptedEntries']} "
            f"rejected={partial['rejectedEntries']}"
        ),
    )
    check(
        "partial-m3u8-issues",
        partial["issues"] == set(partial_expected["issueCodes"]),
        repr(sorted(partial["issues"])),
    )
    check(
        "partial-m3u8-zero-write",
        partial_expected["writesCommitted"] == 0
        and partial_expected["decision"] == "REQUIRE_USER_CHOICE",
    )

    custom_text = build_custom_m3u8(mixed)
    custom_path = ROOT / ".custom-roundtrip.tmp.m3u8"
    custom_path.write_text(custom_text, encoding="utf-8", newline="\n")
    try:
        custom = parse_m3u8(custom_path)
        reconstructed = reconstruct_custom_document(custom)
    finally:
        custom_path.unlink(missing_ok=True)
    check(
        "custom-m3u8-roundtrip-deep-equal",
        reconstructed == mixed,
        "custom markers, EXTINF, and URI lines must reconstruct the fixture",
    )
    check(
        "custom-m3u8-roundtrip-no-loss",
        not custom["issues"]
        and custom["identityLost"] == 1,
        (
            f"issues={sorted(custom['issues'])} identityLost="
            f"{custom['identityLost']}"
        ),
    )

    matrix = load_json(ROUNDTRIP_PATH)
    allowed_statuses = set(matrix["statusVocabulary"])
    matrix_rows = [
        row for lane in matrix["lanes"] for row in lane["rows"]
    ]
    status_counts = Counter(row["status"] for row in matrix_rows)
    check(
        "roundtrip-matrix-statuses",
        set(status_counts).issubset(allowed_statuses),
        repr(dict(status_counts)),
    )
    check(
        "roundtrip-generic-loss-explicit",
        any(
            lane["id"] == "JSON_TO_M3U8_TO_JSON_GENERIC"
            and any(row["status"] == "LOSS" for row in lane["rows"])
            for lane in matrix["lanes"]
        ),
    )

    json_fixtures = sorted(
        path
        for path in FIXTURES.glob("*.json")
        if path.name != EXPECTATIONS_PATH.name
    )
    m3u8_fixtures = sorted(FIXTURES.glob("*.m3u8"))
    text_files = text_files_for_checks()
    cr_files = [
        path.relative_to(ROOT).as_posix()
        for path in text_files
        if b"\r" in path.read_bytes()
    ]
    check("evidence-lf-only", not cr_files, repr(cr_files))
    sensitive_hits = find_sensitive_values(text_files)
    check("sensitive-value-scan", not sensitive_hits, repr(sensitive_hits))
    check(
        "local-server-playlist-boundary",
        all(
            document["playlist"]["identityDomain"] == "LOCAL_DRAFT"
            and document["playlist"]["serverPlaylistId"] is None
            for document in parsed_documents.values()
        ),
    )
    source = manifest_source()
    check(
        "manifest-source-frozen",
        source["branch"] == INPUT_BRANCH
        and source["inputBaseline"] == INPUT_BASELINE
        and re.fullmatch(r"[0-9a-f]{40}", source["inputBaseline"]) is not None
        and "head" not in source,
    )

    failed = [item for item in checks if item["status"] != "PASS"]
    status = "PASS" if not failed else "FAIL"
    results = {
        "format": "aurorama.base004.test-results",
        "schemaVersion": 1,
        "status": status,
        "counts": {
            "checks": len(checks),
            "passed": len(checks) - len(failed),
            "failed": len(failed),
            "jsonFixtureFiles": len(json_fixtures),
            "m3u8FixtureFiles": len(m3u8_fixtures),
            "fixtureFiles": len(json_fixtures) + len(m3u8_fixtures),
            "expectationFiles": 1,
            "testCases": len(expected_by_id),
            "schemaPropertyDeclarations": schema_property_declarations(schema),
            "roundtripLanes": len(matrix["lanes"]),
            "roundtripRows": len(matrix_rows),
            "roundtripStatusCounts": dict(sorted(status_counts.items())),
        },
        "checks": checks,
        "failedChecks": failed,
        "sensitiveHits": sensitive_hits,
        "crlfFiles": cr_files,
    }
    write_json(RESULTS_PATH, results)

    for item in checks:
        print(f"[{item['status']}] {item['id']}")
        if item["status"] != "PASS" and item["detail"]:
            print(f"  {item['detail']}")

    if failed:
        print(f"FAILED: {len(failed)} / {len(checks)} checks")
        return 1

    manifest = generate_manifest()
    manifest_hash = sha256_bytes(MANIFEST_PATH.read_bytes())
    print(f"PASS: {len(checks)} checks")
    print(
        "COUNT: "
        f"fixtures={results['counts']['fixtureFiles']} "
        f"json={results['counts']['jsonFixtureFiles']} "
        f"m3u8={results['counts']['m3u8FixtureFiles']} "
        f"cases={results['counts']['testCases']} "
        f"roundtrip_rows={results['counts']['roundtripRows']}"
    )
    print(
        "MANIFEST: "
        f"files={manifest['counts']['files']} "
        f"bytes={MANIFEST_PATH.stat().st_size} "
        f"sha256={manifest_hash}"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
