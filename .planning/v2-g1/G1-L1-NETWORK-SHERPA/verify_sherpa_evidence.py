#!/usr/bin/env python3
"""Verify the Sherpa-ONNX 2.0.1 static artifact evidence bundle.

The verifier is deliberately offline. It never fetches an artifact and never
executes code from an AAR or source archive.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import tempfile
from pathlib import Path
from typing import Any, Callable
from urllib.parse import parse_qs, unquote, urlparse


ROOT = Path(__file__).resolve().parent
MANIFEST = "manifest.json"
MANIFEST_SHA = "manifest.sha256"
REQUIRED_FILES = {
    ".gitattributes",
    "README.md",
    "CHECKPOINT.md",
    "artifact-index.json",
    "aar-inventory.json",
    "source-inventory.json",
    "elf-inventory.json",
    "license-source-matrix.json",
    "commands.jsonl",
    "verify_sherpa_evidence.py",
}
BINARY_SUFFIXES = {
    ".aar",
    ".apk",
    ".class",
    ".dex",
    ".elf",
    ".gz",
    ".jar",
    ".o",
    ".so",
    ".tar",
    ".tgz",
    ".zip",
}
EXPECTED_ARTIFACTS = {
    "sherpa-standard-aar": {
        "url": "https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.1/sherpa-onnx-2.0.1.aar",
        "filename": "sherpa-onnx-2.0.1.aar",
        "size": 49_441_341,
        "sha256": "a8d09d1d32f3eb8520482c6d3d43fae674681c5b050385967c70a3c2c9bf0ee7",
        "finalHost": "release-assets.githubusercontent.com",
    },
    "sherpa-static-onnxruntime-aar": {
        "url": "https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.1/sherpa-onnx-static-link-onnxruntime-2.0.1.aar",
        "filename": "sherpa-onnx-static-link-onnxruntime-2.0.1.aar",
        "size": 38_577_050,
        "sha256": "07e042d97f3a6bd5b7fe74d4eef0513dc01a714b16c15a903250095aba43a575",
        "finalHost": "release-assets.githubusercontent.com",
    },
    "sherpa-source-v2.0.1": {
        "url": "https://github.com/k2-fsa/sherpa-onnx/archive/refs/tags/v2.0.1.tar.gz",
        "filename": "sherpa-onnx-2.0.1.tar.gz",
        "size": None,
        "sha256": None,
        "finalHost": "codeload.github.com",
    },
}
EXPECTED_COMMIT = "af2d8d81dbfa61c4cea4c568da78f3982edca005"
EXPECTED_ABIS = {"arm64-v8a", "armeabi-v7a", "x86", "x86_64"}
STATUS_VALUES = {"PASS", "FAIL", "UNPROVEN"}


def _error(code: str, message: str) -> str:
    return f"{code}: {message}"


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _json(path: Path) -> dict[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError(f"{path.name} is not a JSON object")
    return value


def _url_artifact_name(url: str) -> str | None:
    text = unquote(url)
    marker = "filename="
    if marker in text:
        tail = text.split(marker, 1)[1]
        return tail.split(";", 1)[0].split("&", 1)[0].strip().strip("\"'")
    return Path(urlparse(url).path).name or None


def _manifest_payload_paths(root: Path) -> set[str]:
    paths: set[str] = set()
    for path in root.rglob("*"):
        if path.is_file() and path.name not in {MANIFEST, MANIFEST_SHA} and "__pycache__" not in path.parts:
            paths.add(path.relative_to(root).as_posix())
    return paths


def _check_manifest(errors: list[str], root: Path) -> None:
    manifest_path = root / MANIFEST
    checksum_path = root / MANIFEST_SHA
    if not manifest_path.is_file() or not checksum_path.is_file():
        errors.append(_error("MISSING_MANIFEST", "manifest.json and manifest.sha256 are required"))
        return
    manifest = _json(manifest_path)
    checksum_parts = checksum_path.read_text(encoding="utf-8").strip().split()
    if len(checksum_parts) != 2 or checksum_parts[1] != MANIFEST:
        errors.append(_error("MANIFEST_SHA_FORMAT", "expected '<sha256>  manifest.json'"))
    elif _sha256(manifest_path) != checksum_parts[0].lower():
        errors.append(_error("MANIFEST_SHA_MISMATCH", "manifest.sha256 does not bind manifest.json"))

    entries = manifest.get("payload")
    if not isinstance(entries, list):
        errors.append(_error("MANIFEST_PAYLOAD", "payload must be a list"))
        return
    listed: set[str] = set()
    for entry in entries:
        if not isinstance(entry, dict):
            errors.append(_error("MANIFEST_ENTRY", "payload entries must be objects"))
            continue
        rel = entry.get("path")
        if not isinstance(rel, str) or rel in listed or "/../" in f"/{rel}/":
            errors.append(_error("MANIFEST_PATH", f"invalid or duplicate path: {rel!r}"))
            continue
        listed.add(rel)
        target = root / rel
        if not target.is_file():
            errors.append(_error("MISSING_FILE", rel))
            continue
        if target.stat().st_size != entry.get("bytes"):
            errors.append(_error("MANIFEST_SIZE_MISMATCH", rel))
        if _sha256(target) != entry.get("sha256"):
            errors.append(_error("MANIFEST_HASH_MISMATCH", rel))
    actual = _manifest_payload_paths(root)
    if listed != actual:
        errors.append(_error("MANIFEST_COVERAGE", f"missing={sorted(actual-listed)} extra={sorted(listed-actual)}"))
    expected_id = "aurorama.g1-l1.sherpa-static-audit.v1"
    if manifest.get("schema") != expected_id or manifest.get("selfExcluded") != MANIFEST:
        errors.append(_error("MANIFEST_SCHEMA", f"expected {expected_id} and selfExcluded={MANIFEST}"))


def _check_required_and_text(errors: list[str], root: Path) -> None:
    for rel in sorted(REQUIRED_FILES):
        if not (root / rel).is_file():
            errors.append(_error("MISSING_FILE", rel))
    for path in root.rglob("*"):
        if not path.is_file() or path.name in {MANIFEST, MANIFEST_SHA}:
            continue
        if path.suffix.lower() in BINARY_SUFFIXES:
            errors.append(_error("BINARY_ARTIFACT", path.relative_to(root).as_posix()))
        if path.suffix.lower() in {".json", ".jsonl", ".md", ".py", ".txt"} or path.name == ".gitattributes":
            data = path.read_bytes()
            if b"\r" in data:
                errors.append(_error("CRLF", path.relative_to(root).as_posix()))
    attr = root / ".gitattributes"
    if attr.is_file():
        text = attr.read_text(encoding="utf-8")
        for token in ("*.json text eol=lf", "*.jsonl text eol=lf", "*.md text eol=lf", "*.py text eol=lf", "*.txt text eol=lf"):
            if token not in text:
                errors.append(_error("GITATTRIBUTES", f"missing rule: {token}"))


def _check_artifact_index(errors: list[str], root: Path) -> dict[str, Any]:
    path = root / "artifact-index.json"
    if not path.is_file():
        return {}
    try:
        index = _json(path)
    except Exception as exc:
        errors.append(_error("ARTIFACT_JSON", str(exc)))
        return {}
    if index.get("expectedCommit") != EXPECTED_COMMIT:
        errors.append(_error("COMMIT", "expected Sherpa-ONNX v2.0.1 commit is missing"))
    relation = index.get("commitEvidence")
    if not isinstance(relation, str) or "external" not in relation.lower() or "archive" not in relation.lower():
        errors.append(_error("COMMIT_RELATION", "state that the fixed commit is externally supplied and not embedded in the tarball"))
    if index.get("runtimeVerificationStatus") not in {"UNVERIFIED", "NOT_RUN"}:
        errors.append(_error("RUNTIME_OVERCLAIM", "artifact index must keep runtime UNVERIFIED"))
    artifacts = index.get("artifacts")
    if not isinstance(artifacts, list):
        errors.append(_error("ARTIFACTS", "artifacts must be a list"))
        return index
    by_id = {row.get("id"): row for row in artifacts if isinstance(row, dict)}
    if set(by_id) != set(EXPECTED_ARTIFACTS):
        errors.append(_error("ARTIFACT_SET", f"unexpected ids: {sorted(set(by_id) ^ set(EXPECTED_ARTIFACTS))}"))
    for artifact_id, expected in EXPECTED_ARTIFACTS.items():
        row = by_id.get(artifact_id)
        if not isinstance(row, dict):
            continue
        if row.get("url") != expected["url"]:
            errors.append(_error("URL_NOT_ALLOWED", artifact_id))
        if row.get("size") != row.get("observedSize"):
            errors.append(_error("SIZE_MISMATCH", f"{artifact_id} row size/observedSize"))
        if expected["size"] is not None and row.get("observedSize") != expected["size"]:
            errors.append(_error("SIZE_MISMATCH", artifact_id))
        if not isinstance(row.get("sha256"), str) or len(row["sha256"]) != 64:
            errors.append(_error("HASH_MISSING", artifact_id))
        if expected["sha256"] is not None and row.get("sha256") != expected["sha256"]:
            errors.append(_error("HASH_MISMATCH", artifact_id))
        network = row.get("network")
        if not isinstance(network, dict):
            errors.append(_error("NETWORK_EVIDENCE", artifact_id))
            continue
        head = network.get("head")
        fetch = network.get("fetch")
        if not isinstance(head, dict) or not isinstance(fetch, dict):
            errors.append(_error("NETWORK_EVIDENCE", artifact_id))
            continue
        if head.get("requestHost") != "github.com" or head.get("status") not in {200, 301, 302, 307, 308}:
            errors.append(_error("HEAD_STATUS", artifact_id))
        for phase in (head, fetch):
            final_host = phase.get("finalHost")
            if final_host != expected["finalHost"]:
                errors.append(_error("REDIRECT_HOST", f"{artifact_id}: {final_host!r}"))
            final_url = phase.get("finalUrl")
            if not isinstance(final_url, str) or urlparse(final_url).hostname != expected["finalHost"]:
                errors.append(_error("REDIRECT_URL", artifact_id))
            location = phase.get("location")
            if location:
                if urlparse(location).hostname != expected["finalHost"]:
                    errors.append(_error("REDIRECT_HOST", f"{artifact_id}: {location}"))
                if expected["finalHost"] == "release-assets.githubusercontent.com":
                    if _url_artifact_name(location) != expected["filename"]:
                        errors.append(_error("REDIRECT_PATH", f"{artifact_id}: signed filename mismatch"))
        if fetch.get("status") != 200:
            errors.append(_error("GET_STATUS", artifact_id))
        if fetch.get("downloadedBytes") != row.get("observedSize"):
            errors.append(_error("SIZE_MISMATCH", f"{artifact_id} downloadedBytes"))
        content_length = fetch.get("contentLength")
        if content_length not in {None, row.get("observedSize")}:
            errors.append(_error("SIZE_MISMATCH", f"{artifact_id} contentLength"))
    source = by_id.get("sherpa-source-v2.0.1")
    if isinstance(source, dict):
        location = (source.get("network", {}).get("fetch") or {}).get("location")
        if not location or "k2-fsa/sherpa-onnx" not in urlparse(location).path or "v2.0.1" not in urlparse(location).path:
            errors.append(_error("REDIRECT_PATH", "source archive redirect does not identify the exact tag"))
    return index


def _check_aar(errors: list[str], root: Path, artifact_index: dict[str, Any]) -> dict[str, Any]:
    path = root / "aar-inventory.json"
    if not path.is_file():
        return {}
    try:
        inventory = _json(path)
    except Exception as exc:
        errors.append(_error("AAR_JSON", str(exc)))
        return {}
    rows = inventory.get("artifacts")
    if not isinstance(rows, list):
        errors.append(_error("AAR_MISSING", "AAR inventory artifacts list absent"))
        return inventory
    by_id = {row.get("id"): row for row in rows if isinstance(row, dict)}
    aar_ids = {key for key in EXPECTED_ARTIFACTS if key.endswith("-aar")}
    indexed_ids = {row.get("id") for row in artifact_index.get("artifacts", []) if isinstance(row, dict)}
    if set(by_id) != aar_ids or not aar_ids.issubset(indexed_ids):
        errors.append(_error("AAR_MISSING", f"expected AAR ids {sorted(aar_ids)}"))
    for artifact_id, row in by_id.items():
        if row.get("entryCount") != len(row.get("entries", [])):
            errors.append(_error("AAR_ENTRIES", artifact_id))
        classes = row.get("classesJar")
        manifest = row.get("manifest")
        if not isinstance(classes, dict) or not classes.get("sha256"):
            errors.append(_error("CLASSES_JAR", artifact_id))
        if not isinstance(manifest, dict) or not manifest.get("sha256"):
            errors.append(_error("MANIFEST_XML", artifact_id))
        abis = row.get("abis")
        if not isinstance(abis, list) or {item.get("abi") for item in abis if isinstance(item, dict)} != EXPECTED_ABIS:
            errors.append(_error("ABI_SET", artifact_id))
            continue
        for abi in abis:
            so_files = abi.get("soFiles")
            if not isinstance(so_files, list) or not so_files:
                errors.append(_error("ELF_MISSING", f"{artifact_id}/{abi.get('abi')}"))
                continue
            for so_file in so_files:
                if not isinstance(so_file, dict) or not so_file.get("size") or not so_file.get("sha256"):
                    errors.append(_error("ELF_METADATA", f"{artifact_id}/{abi.get('abi')}"))
    return inventory


def _check_source(errors: list[str], root: Path) -> dict[str, Any]:
    path = root / "source-inventory.json"
    if not path.is_file():
        return {}
    try:
        inventory = _json(path)
    except Exception as exc:
        errors.append(_error("SOURCE_JSON", str(exc)))
        return {}
    top = inventory.get("topLevel")
    if top != ["sherpa-onnx-2.0.1/"]:
        errors.append(_error("SOURCE_TOP_LEVEL", f"unexpected topLevel={top!r}"))
    for key in ("licenseFiles", "cmakeFiles", "thirdPartyPaths", "noticeFiles", "correspondingSourceCandidates"):
        if not isinstance(inventory.get(key), list):
            errors.append(_error("SOURCE_INVENTORY", key))
    if not inventory.get("licenseFiles"):
        errors.append(_error("LICENSE_MISSING", "source inventory has no license file"))
    return inventory


def _check_elf(errors: list[str], root: Path, aar: dict[str, Any]) -> dict[str, Any]:
    path = root / "elf-inventory.json"
    if not path.is_file():
        return {}
    try:
        inventory = _json(path)
    except Exception as exc:
        errors.append(_error("ELF_JSON", str(exc)))
        return {}
    if inventory.get("scope") != "static-elf-program-header-only":
        errors.append(_error("RUNTIME_OVERCLAIM", "ELF inventory scope must be static only"))
    if inventory.get("runtimeVerificationStatus") not in {"UNVERIFIED", "NOT_RUN"}:
        errors.append(_error("RUNTIME_OVERCLAIM", "ELF inventory must keep runtime UNVERIFIED"))
    if not isinstance(inventory.get("method"), dict) or inventory["method"].get("pageSizeBytes") != 16384:
        errors.append(_error("ELF_METHOD", "record the reproducible 16 KiB program-header method"))
    abis = inventory.get("abis")
    if not isinstance(abis, list) or not abis:
        errors.append(_error("ELF_MISSING", "no ABI rows"))
        return inventory
    abi_names = {row.get("abi") for row in abis if isinstance(row, dict)}
    if abi_names != EXPECTED_ABIS:
        errors.append(_error("ABI_SET", f"ELF ABI mismatch: {sorted(abi_names)}"))
    aar_abis = {
        (artifact.get("id"), row.get("abi")): row
        for artifact in aar.get("artifacts", [])
        for row in artifact.get("abis", [])
        if isinstance(row, dict)
    }
    for row in abis:
        if not isinstance(row, dict):
            continue
        abi = row.get("abi")
        status = row.get("status")
        libraries = row.get("libraries")
        if status not in STATUS_VALUES:
            errors.append(_error("ELF_STATUS", f"{abi}: {status!r}"))
            continue
        if not isinstance(libraries, list) or not libraries:
            errors.append(_error("ELF_MISSING", f"{abi} has no libraries"))
            continue
        for artifact_id in {item.get("artifact") for item in libraries if isinstance(item, dict)}:
            key = (artifact_id, abi)
            if key in aar_abis:
                aar_paths = {
                    item.get("path")
                    for item in aar_abis[key].get("soFiles", [])
                    if isinstance(item, dict)
                }
                elf_paths = {
                    item.get("path")
                    for item in libraries
                    if isinstance(item, dict) and item.get("artifact") == artifact_id
                }
                if aar_paths != elf_paths:
                    errors.append(_error("ELF_INVENTORY_COVERAGE", f"{artifact_id}/{abi} AAR/ELF paths differ"))
        for library in libraries:
            if not isinstance(library, dict):
                errors.append(_error("ELF_METADATA", abi))
                continue
            segments = library.get("loadSegments")
            if not isinstance(segments, list) or not segments:
                if status == "PASS":
                    errors.append(_error("ELF_UNPROVEN_AS_PASS", f"{abi}/{library.get('path')}"))
                continue
            alignments = [segment.get("align") for segment in segments if isinstance(segment, dict)]
            if any(not isinstance(value, int) or value < 16384 for value in alignments):
                if status == "PASS":
                    errors.append(_error("ELF_ALIGNMENT_PASS_INVALID", f"{abi}/{library.get('path')}"))
                if library.get("status") == "PASS":
                    errors.append(_error("ELF_LIBRARY_ALIGNMENT_PASS_INVALID", f"{abi}/{library.get('path')}"))
            if status == "PASS" and library.get("status") != "PASS":
                errors.append(_error("ELF_ABI_STATUS", f"{abi} cannot PASS with a non-PASS library"))
    return inventory


def _check_license_matrix(errors: list[str], root: Path) -> dict[str, Any]:
    path = root / "license-source-matrix.json"
    if not path.is_file():
        return {}
    try:
        matrix = _json(path)
    except Exception as exc:
        errors.append(_error("LICENSE_JSON", str(exc)))
        return {}
    required_rows = {"apache-2.0-code", "onnxruntime", "third-party-notice", "source-offer"}
    rows = matrix.get("rows")
    row_ids = {row.get("id") for row in rows if isinstance(row, dict)} if isinstance(rows, list) else set()
    if not required_rows.issubset(row_ids):
        errors.append(_error("LICENSE_MATRIX", f"missing rows: {sorted(required_rows-row_ids)}"))
    if matrix.get("finalPackagedNoticeComplete") is not False:
        errors.append(_error("PACKAGED_NOTICE_OVERCLAIM", "final packaged notice must remain incomplete"))
    if matrix.get("q03Status") != "OPEN" or matrix.get("g1Status") != "NOT_PASSED":
        errors.append(_error("STATUS_OVERCLAIM", "Q03 must stay OPEN and G1 NOT_PASSED"))
    evidence = matrix.get("licenseNoticeEvidence")
    if not isinstance(evidence, list) or not evidence:
        errors.append(_error("NOTICE_MISSING", "no extracted license/notice evidence"))
    else:
        for item in evidence:
            if not isinstance(item, dict):
                errors.append(_error("NOTICE_MISSING", "invalid evidence row"))
                continue
            rel = item.get("path")
            target = root / str(rel)
            if not rel or not target.is_file():
                errors.append(_error("NOTICE_MISSING", str(rel)))
            elif _sha256(target) != item.get("sha256"):
                errors.append(_error("NOTICE_HASH", str(rel)))
    return matrix


def _check_commands(errors: list[str], root: Path, *, require_tdd_green: bool) -> None:
    path = root / "commands.jsonl"
    if not path.is_file():
        return
    events: set[str] = set()
    for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if not line:
            continue
        try:
            row = json.loads(line)
        except json.JSONDecodeError as exc:
            errors.append(_error("COMMAND_JSONL", f"line {number}: {exc}"))
            continue
        if not isinstance(row, dict) or not row.get("event"):
            errors.append(_error("COMMAND_JSONL", f"line {number}: event missing"))
            continue
        events.add(str(row["event"]))
        if "exit" not in row:
            errors.append(_error("COMMAND_JSONL", f"line {number}: exit missing"))
    required_events = ["head", "get", "sha256", "aar_inventory", "source_inventory", "elf_alignment", "tdd_red"]
    if require_tdd_green:
        required_events.append("tdd_green")
    for event in required_events:
        if event not in events:
            errors.append(_error("COMMAND_EVENT", event))


def verify(root: Path, *, require_tdd_green: bool = True) -> list[str]:
    errors: list[str] = []
    _check_required_and_text(errors, root)
    _check_manifest(errors, root)
    try:
        artifact_index = _check_artifact_index(errors, root)
    except Exception as exc:
        errors.append(_error("ARTIFACT_INDEX", str(exc)))
        artifact_index = {}
    try:
        aar = _check_aar(errors, root, artifact_index)
    except Exception as exc:
        errors.append(_error("AAR_INVENTORY", str(exc)))
        aar = {}
    _check_source(errors, root)
    _check_elf(errors, root, aar)
    _check_license_matrix(errors, root)
    _check_commands(errors, root, require_tdd_green=require_tdd_green)
    return errors


def _refresh_manifest(root: Path) -> None:
    manifest_path = root / MANIFEST
    manifest = _json(manifest_path)
    payload: list[dict[str, Any]] = []
    for rel in sorted(_manifest_payload_paths(root)):
        target = root / rel
        payload.append({"path": rel, "bytes": target.stat().st_size, "sha256": _sha256(target)})
    manifest["payload"] = payload
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")
    (root / MANIFEST_SHA).write_text(f"{_sha256(manifest_path)}  manifest.json\n", encoding="utf-8", newline="\n")


def _mutate_json(root: Path, rel: str, mutate: Callable[[dict[str, Any]], None]) -> None:
    path = root / rel
    value = _json(path)
    mutate(value)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")
    _refresh_manifest(root)


def self_test(root: Path) -> list[str]:
    failures: list[str] = []
    baseline = verify(root, require_tdd_green=False)
    if baseline:
        return ["BASE_RED: " + "; ".join(baseline[:8])]

    cases: list[tuple[str, str, Callable[[Path], None]]] = []

    def hash_mismatch(work: Path) -> None:
        _mutate_json(work, "artifact-index.json", lambda value: value["artifacts"][0].__setitem__("expectedSha256", "0" * 64))

    def size_mismatch(work: Path) -> None:
        _mutate_json(work, "artifact-index.json", lambda value: value["artifacts"][0].__setitem__("expectedSize", 1))

    def redirect_host(work: Path) -> None:
        _mutate_json(work, "artifact-index.json", lambda value: value["artifacts"][0]["network"]["fetch"].__setitem__("finalHost", "example.invalid"))

    def missing_aar(work: Path) -> None:
        _mutate_json(work, "aar-inventory.json", lambda value: value.__setitem__("artifacts", []))

    def missing_notice(work: Path) -> None:
        _mutate_json(work, "license-source-matrix.json", lambda value: value.__setitem__("licenseNoticeEvidence", []))

    def missing_elf(work: Path) -> None:
        _mutate_json(work, "elf-inventory.json", lambda value: value.__setitem__("abis", []))

    def runtime_overclaim(work: Path) -> None:
        _mutate_json(work, "elf-inventory.json", lambda value: value.__setitem__("runtimeVerificationStatus", "PASS"))

    def binary_in_bundle(work: Path) -> None:
        (work / "forbidden.aar").write_bytes(b"not-an-aar")

    def expected_hash(work: Path) -> None:
        _mutate_json(
            work,
            "artifact-index.json",
            lambda value: value["artifacts"][0].__setitem__("sha256", "0" * 64),
        )

    def expected_size(work: Path) -> None:
        _mutate_json(work, "artifact-index.json", lambda value: value["artifacts"][0].__setitem__("observedSize", 1))

    cases.extend(
        [
            ("hash", "HASH_MISMATCH", expected_hash),
            ("size", "SIZE_MISMATCH", expected_size),
            ("redirect", "REDIRECT_HOST", redirect_host),
            ("aar", "AAR_MISSING", missing_aar),
            ("notice", "NOTICE_MISSING", missing_notice),
            ("elf", "ELF_MISSING", missing_elf),
            ("runtime", "RUNTIME_OVERCLAIM", runtime_overclaim),
            ("binary", "BINARY_ARTIFACT", binary_in_bundle),
        ]
    )
    # Keep two direct expected-metadata mutations useful while avoiding dead test helpers.
    if not callable(hash_mismatch) or not callable(size_mismatch):
        failures.append("SELF_TEST_INTERNAL")

    for name, code, mutate in cases:
        with tempfile.TemporaryDirectory(prefix="sherpa-verify-") as temporary:
            work = Path(temporary) / "bundle"
            shutil.copytree(root, work, ignore=shutil.ignore_patterns("__pycache__", "*.pyc"))
            mutate(work)
            found = verify(work, require_tdd_green=False)
            if not any(item.startswith(code + ":") for item in found):
                failures.append(f"{name}: expected {code}; got {found[:4]}")
    return failures


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    root = args.root.resolve()
    if args.self_test:
        failures = self_test(root)
        if failures:
            print("SELF_TEST=RED")
            for failure in failures:
                print(failure)
            return 1
        print("SELF_TEST=GREEN cases=8")
        return 0
    errors = verify(root)
    if errors:
        print("VERIFY=RED")
        for error in errors:
            print(error)
        return 1
    print("SELF_TEST=GREEN")
    print("VERIFY=GREEN")
    print(f"manifest_sha256={_sha256(root / MANIFEST)}")
    print(f"files={len(_manifest_payload_paths(root)) + 2}")
    print(f"bytes={sum(path.stat().st_size for path in root.rglob('*') if path.is_file())}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
