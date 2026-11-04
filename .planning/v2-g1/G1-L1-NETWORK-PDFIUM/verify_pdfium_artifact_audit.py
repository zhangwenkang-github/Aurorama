#!/usr/bin/env python3
"""Verify the Aurorama G1-L1 PDFium artifact audit without network access."""

from __future__ import annotations

import argparse
import copy
import hashlib
import json
import re
import sys
import tempfile
from pathlib import Path
from typing import Any, Callable, Iterable
from urllib.parse import urlsplit


SCHEMA_INDEX = "aurorama.g1-l1-network-pdfium.artifact-index/v1"
SCHEMA_ELF = "aurorama.g1-l1-network-pdfium.elf-inventory/v1"
SCHEMA_LICENSE = "aurorama.g1-l1-network-pdfium.license-source-matrix/v1"
SCHEMA_MANIFEST = "aurorama.g1-l1-network-pdfium.manifest/v1"
ALLOWED_HOST = "repo1.maven.org"
MODULES = ("pdfiumandroid", "pdfiumandroid-core", "pdfiumandroid-api")
VERSION = "2.0.3"
KINDS = ("AAR", "SOURCES_JAR", "POM")
EXPECTED_AAR_SHA256 = {
    "pdfiumandroid": "1F07A9160C9A5975D6EFFF72404C5E49217D46F2651BFE02C67C8AFB133E02D7",
    "pdfiumandroid-core": "F80B8D2166F226E0ED0A50B7014CEA91E297952C5E0AC158F10FF62C74D7E318",
    "pdfiumandroid-api": "A47A0CC8210D3D435996CCB47A3C666361F8C7CB774EFBE58AB02AC478731F3F",
}
EXPECTED_AAR_SIZE = {
    "pdfiumandroid": 377_258,
    "pdfiumandroid-core": 9_787_161,
    "pdfiumandroid-api": 44_512,
}
EXPECTED_HEAD = "506fe75fddd40188ec116ca19496a09778868725"
ALLOWED_FILES = {".gitattributes", ".json", ".jsonl", ".md", ".py", ".sha256", ".txt"}
FORBIDDEN_SUFFIXES = {
    ".aar",
    ".apk",
    ".class",
    ".dex",
    ".jar",
    ".pom",
    ".so",
    ".zip",
}
SHA256_RE = re.compile(r"^[0-9A-F]{64}$")
URL_RE = re.compile(r"https?://[^\s\"']+")


class AuditError(RuntimeError):
    pass


def fail(message: str) -> None:
    raise AuditError(message)


def require(condition: bool, message: str) -> None:
    if not condition:
        fail(message)


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest().upper()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest().upper()


def load_json(path: Path) -> dict[str, Any]:
    require(path.is_file(), f"missing file: {path.name}")
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        fail(f"invalid UTF-8 JSON {path.name}: {exc}")
    require(isinstance(value, dict), f"JSON root is not an object: {path.name}")
    return value


def require_sha(value: Any, label: str) -> str:
    require(isinstance(value, str) and SHA256_RE.fullmatch(value) is not None, f"invalid SHA-256: {label}")
    return value


def approved_url(value: Any, label: str) -> str:
    require(isinstance(value, str), f"missing URL: {label}")
    parsed = urlsplit(value)
    require(parsed.scheme == "https", f"non-HTTPS URL: {label}")
    require(parsed.hostname == ALLOWED_HOST, f"unapproved host in {label}: {parsed.hostname}")
    expected_prefix = f"https://{ALLOWED_HOST}/maven2/io/legere/"
    require(value.startswith(expected_prefix), f"URL outside approved Maven root: {label}")
    return value


def expected_url(module: str, suffix: str) -> str:
    return f"https://{ALLOWED_HOST}/maven2/io/legere/{module}/{VERSION}/{module}-{VERSION}{suffix}"


def expected_artifact_url(module: str, kind: str) -> str:
    suffix = {"AAR": ".aar", "SOURCES_JAR": "-sources.jar", "POM": ".pom"}[kind]
    return expected_url(module, suffix)


def expected_checksum_url(module: str, kind: str) -> str:
    return expected_artifact_url(module, kind) + ".sha256"


def artifact_key(module: str, kind: str) -> str:
    return f"{module}:{kind}"


def check_http_record(
    record: dict[str, Any],
    expected_url_value: str,
    label: str,
    allowed_statuses: tuple[int, ...] = (200,),
) -> None:
    http = record.get("http")
    require(isinstance(http, dict), f"missing HTTP record: {label}")
    approved_url(record.get("url"), f"{label}.url")
    require(record["url"] == expected_url_value, f"unexpected URL: {label}")
    require(http.get("curl_exit_code") == 0, f"curl failed: {label}")
    require(http.get("status") in allowed_statuses, f"unexpected HTTP status: {label}")
    require(http.get("final_url") == expected_url_value, f"final URL changed: {label}")
    require(http.get("redirect_hops") == [], f"redirect encountered: {label}")
    require(
        http.get("content_length_header") is None or isinstance(http.get("content_length_header"), int),
        f"invalid content-length record: {label}",
    )
    if isinstance(http.get("content_length_header"), int):
        require(http["content_length_header"] >= 0, f"negative content-length: {label}")
    require(isinstance(http.get("download_bytes"), int), f"download size missing: {label}")
    require(http["download_bytes"] >= 0, f"negative download size: {label}")
    if http.get("status") == 200:
        require(isinstance(http.get("content_length_header"), int), f"content-length missing: {label}")


def check_artifact_index(index: dict[str, Any]) -> None:
    require(index.get("schema_id") == SCHEMA_INDEX, "artifact-index schema mismatch")
    auth = index.get("authorization")
    require(isinstance(auth, dict), "authorization missing")
    require(auth.get("approved_hosts") == [ALLOWED_HOST], "authorization host allowlist mismatch")
    require(auth.get("https_only") is True, "authorization must be HTTPS-only")
    require(auth.get("redirects_followed") is False, "redirects must not be followed")
    require(auth.get("max_redirections") == 0, "max-redirections must be zero")
    require(auth.get("binary_retained_in_evidence") is False, "binary retention is forbidden")
    source = index.get("source_baseline")
    require(isinstance(source, dict), "source baseline missing")
    require(source.get("head") == EXPECTED_HEAD, "source HEAD mismatch")
    require(source.get("branch") == "codex/v2-integration", "source branch mismatch")
    require(source.get("initial_status_porcelain") == "", "initial worktree was not clean")

    artifacts = index.get("artifacts")
    checksum_files = index.get("checksum_files")
    require(isinstance(artifacts, list), "artifacts must be a list")
    require(isinstance(checksum_files, list), "checksum_files must be a list")
    require(len(artifacts) == 9, "expected nine primary artifacts")
    require(len(checksum_files) == 9, "expected nine checksum records")

    artifacts_by_key: dict[str, dict[str, Any]] = {}
    for artifact in artifacts:
        require(isinstance(artifact, dict), "artifact record must be an object")
        module = artifact.get("module")
        kind = artifact.get("kind")
        require(module in MODULES, f"unexpected module: {module}")
        require(kind in KINDS, f"unexpected artifact kind: {kind}")
        key = artifact_key(module, kind)
        require(artifact.get("key") == key, f"artifact key mismatch: {key}")
        require(key not in artifacts_by_key, f"duplicate artifact key: {key}")
        check_http_record(artifact, expected_artifact_url(module, kind), key)
        size = artifact.get("size")
        require(isinstance(size, int) and size > 0, f"invalid artifact size: {key}")
        require(artifact["http"]["download_bytes"] == size, f"download bytes differ from local size: {key}")
        require(artifact["http"]["content_length_header"] == size, f"content-length differs from local size: {key}")
        digest = require_sha(artifact.get("sha256"), f"{key}.sha256")
        if kind == "AAR":
            require(digest == EXPECTED_AAR_SHA256[module], f"AAR SHA-256 mismatch: {key}")
            require(size == EXPECTED_AAR_SIZE[module], f"AAR size mismatch: {key}")
        artifact["_validated_sha256"] = digest
        artifacts_by_key[key] = artifact

    for module in MODULES:
        for kind in KINDS:
            require(artifact_key(module, kind) in artifacts_by_key, f"missing artifact: {module} {kind}")

    checksums_by_key: dict[str, dict[str, Any]] = {}
    for record in checksum_files:
        require(isinstance(record, dict), "checksum record must be an object")
        key = record.get("key")
        require(isinstance(key, str) and key.endswith(":sha256"), "invalid checksum key")
        require(key not in checksums_by_key, f"duplicate checksum key: {key}")
        for_module = record.get("for_module")
        for_kind = record.get("for_kind")
        require(for_module in MODULES, f"invalid checksum module: {key}")
        require(for_kind in KINDS, f"invalid checksum kind: {key}")
        check_http_record(record, expected_checksum_url(for_module, for_kind), key, (200, 404))
        digest = record.get("sha256")
        if digest is not None:
            require_sha(digest, f"{key}.sha256")
        checksums_by_key[key] = record

    for key, artifact in artifacts_by_key.items():
        checksum_key = artifact.get("checksum_key")
        require(checksum_key in checksums_by_key, f"checksum link missing: {key}")
        checksum = checksums_by_key[checksum_key]
        require(checksum.get("for_module") == artifact["module"], f"checksum module link mismatch: {key}")
        require(checksum.get("for_kind") == artifact["kind"], f"checksum kind link mismatch: {key}")
        if checksum["http"]["status"] == 200:
            require(checksum.get("published_sha256") == artifact["sha256"], f"published checksum mismatch: {key}")
            require(checksum.get("checksum_status") == "MATCH", f"checksum status mismatch: {key}")
        else:
            require(checksum.get("published_sha256") is None, f"missing checksum was guessed: {key}")
            require(
                checksum.get("checksum_status") == "MISSING_REMOTE_404",
                f"404 checksum status mismatch: {key}",
            )

    inventory = index.get("aar_inventory")
    require(isinstance(inventory, dict), "AAR inventory missing")
    for module in MODULES:
        entry = inventory.get(module)
        require(isinstance(entry, dict), f"AAR inventory missing module: {module}")
        require(entry.get("zip_test") == "OK", f"AAR ZIP integrity failed: {module}")
        require(entry.get("duplicate_entry_count") == 0, f"duplicate ZIP entries: {module}")
        for required_entry in ("AndroidManifest.xml", "classes.jar"):
            detail = entry.get(required_entry)
            require(isinstance(detail, dict) and detail.get("present") is True, f"missing {required_entry}: {module}")
            require(detail.get("size", 0) > 0, f"zero-byte {required_entry}: {module}")
            require_sha(detail.get("sha256"), f"{module}:{required_entry}")
        native_entries = entry.get("native_entries")
        license_entries = entry.get("license_notice_entries")
        require(isinstance(native_entries, list), f"native entries missing: {module}")
        require(isinstance(license_entries, list), f"license entries missing: {module}")
        for native in native_entries:
            require(native.get("path", "").startswith("jni/"), f"native entry outside jni/: {module}")
            require(native.get("abi") in {"arm64-v8a", "armeabi-v7a", "x86", "x86_64"}, f"unknown ABI: {module}")
            require(native.get("size", 0) > 0, f"zero-byte native entry: {module}")
            require_sha(native.get("sha256"), f"{module}:{native.get('path')}")
    require(
        sum(len(entry["native_entries"]) for entry in inventory.values()) > 0,
        "no native entries found across AARs",
    )
    relations = index.get("module_relations")
    require(isinstance(relations, dict), "module relations missing")
    require(isinstance(relations.get("pom_dependencies"), dict), "POM dependency relations missing")
    require(isinstance(relations.get("native_identity"), list), "native identity relation missing")


def check_elf_inventory(elf: dict[str, Any]) -> None:
    require(elf.get("schema_id") == SCHEMA_ELF, "ELF inventory schema mismatch")
    scope = elf.get("scope")
    require(scope == "STATIC_ELF_PROGRAM_HEADER_ONLY", "ELF scope must remain static")
    runtime = elf.get("runtime")
    require(isinstance(runtime, dict), "ELF runtime status missing")
    require(runtime.get("page_size_16kb_runtime_status") == "UNVERIFIED", "16 KB runtime must remain UNVERIFIED")
    require(runtime.get("runtime_executed") is False, "runtime execution recorded")
    require(elf.get("static_pass_is_runtime_pass") is False, "static result was promoted to runtime PASS")

    files = elf.get("files")
    require(isinstance(files, list) and files, "ELF file inventory is empty")
    derived: dict[str, list[str]] = {}
    for record in files:
        require(isinstance(record, dict), "ELF record must be an object")
        abi = record.get("abi")
        require(abi in {"arm64-v8a", "armeabi-v7a", "x86", "x86_64"}, f"unknown ELF ABI: {abi}")
        require_sha(record.get("sha256"), f"ELF SHA-256: {record.get('path')}")
        require(record.get("size", 0) > 0, f"invalid ELF size: {record.get('path')}")
        parse = record.get("parse")
        require(isinstance(parse, dict), f"ELF parse missing: {record.get('path')}")
        load_segments = record.get("load_segments")
        require(isinstance(load_segments, list) and load_segments, f"ELF has no PT_LOAD: {record.get('path')}")
        observed = "PASS"
        if parse.get("status") != "OK":
            observed = "UNPROVEN"
        else:
            for segment in load_segments:
                require(isinstance(segment, dict), "invalid PT_LOAD record")
                require(isinstance(segment.get("p_align"), int), "p_align missing")
                require(isinstance(segment.get("p_offset"), int), "p_offset missing")
                require(isinstance(segment.get("p_vaddr"), int), "p_vaddr missing")
                if segment.get("align_16kb_ok") is not True or segment.get("offset_vaddr_congruent_ok") is not True:
                    observed = "FAIL"
        status = record.get("alignment_status")
        require(status == observed, f"ELF alignment status does not derive from segments: {record.get('path')}")
        derived.setdefault(abi, []).append(status)

    summaries = elf.get("abi_summary")
    require(isinstance(summaries, list), "ABI summary missing")
    summary_by_abi = {row.get("abi"): row for row in summaries if isinstance(row, dict)}
    require(set(summary_by_abi) == set(derived), "ABI summary set mismatch")
    for abi, statuses in derived.items():
        expected = "FAIL" if "FAIL" in statuses else "UNPROVEN" if "UNPROVEN" in statuses else "PASS"
        row = summary_by_abi[abi]
        require(row.get("status") == expected, f"ABI summary mismatch: {abi}")
        require(row.get("elf_file_count") == len(statuses), f"ABI file count mismatch: {abi}")


def check_license_source_matrix(matrix: dict[str, Any]) -> None:
    require(matrix.get("schema_id") == SCHEMA_LICENSE, "license/source schema mismatch")
    revision = matrix.get("revision")
    require(isinstance(revision, dict), "revision record missing")
    require(revision.get("maven_artifact_version") == VERSION, "Maven version is not 2.0.3")
    require(revision.get("maven_version_status") == "PROVEN", "Maven artifact version is not proven")
    native_revision = revision.get("native_source_revision")
    require(native_revision in {"UNPROVEN", "PROVEN"}, "invalid native revision status")
    if native_revision == "PROVEN":
        revision_value = revision.get("native_source_revision_value")
        require(isinstance(revision_value, str) and revision_value.strip(), "proven native revision has no value")
        require(revision.get("native_revision_evidence_file"), "proven native revision has no evidence")

    source = matrix.get("source_audit")
    require(isinstance(source, dict), "source audit missing")
    section_presence = {
        "native_source_present": source.get("native_source_present"),
        "deps_present": source.get("deps_present"),
        "gn_present": source.get("gn_present"),
    }
    for name, value in section_presence.items():
        require(isinstance(value, bool), f"source presence must be boolean: {name}")
    if not (
        section_presence["native_source_present"]
        and section_presence["deps_present"]
        and section_presence["gn_present"]
    ):
        require(source.get("status") == "SOURCE_INCOMPLETE", "incomplete native source was overclaimed")
    else:
        require(source.get("status") in {"COMPLETE", "PARTIAL"}, "invalid source status")

    obligations = matrix.get("obligations")
    require(isinstance(obligations, list) and obligations, "obligations missing")
    obligation_ids = set()
    for row in obligations:
        require(isinstance(row, dict), "obligation must be an object")
        obligation_id = row.get("id")
        require(isinstance(obligation_id, str) and obligation_id, "obligation id missing")
        require(obligation_id not in obligation_ids, f"duplicate obligation: {obligation_id}")
        obligation_ids.add(obligation_id)
        status = row.get("status")
        require(status in {"SATISFIED", "PARTIAL", "MISSING", "MISSING_REMOTE_404", "NOT_APPLICABLE"}, "invalid obligation status")
        require(isinstance(row.get("evidence"), list), f"obligation evidence missing: {obligation_id}")
        if status != "SATISFIED":
            require(isinstance(row.get("gap"), str) and row["gap"].strip(), f"gap missing: {obligation_id}")
    require(
        "NATIVE_CORRESPONDING_SOURCE" in obligation_ids,
        "native corresponding-source obligation missing",
    )
    require(
        "DEPS_AND_GN_BUILD_METADATA" in obligation_ids,
        "DEPS/GN build obligation missing",
    )


def check_commands(root: Path, index: dict[str, Any]) -> None:
    path = root / "commands.jsonl"
    require(path.is_file(), "commands.jsonl missing")
    records: list[dict[str, Any]] = []
    for line_number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if not line.strip():
            continue
        try:
            record = json.loads(line)
        except json.JSONDecodeError as exc:
            fail(f"invalid commands.jsonl line {line_number}: {exc}")
        require(isinstance(record, dict), f"command record must be object: line {line_number}")
        records.append(record)

    network = [record for record in records if record.get("phase") == "NETWORK_GET"]
    expected_urls = {
        expected_artifact_url(module, kind)
        for module in MODULES
        for kind in KINDS
    } | {
        expected_checksum_url(module, kind)
        for module in MODULES
        for kind in KINDS
    }
    observed_urls = set()
    for record in network:
        command = record.get("command")
        require(isinstance(command, str) and "curl.exe" in command, "network record did not use curl.exe")
        require("--max-redirs 0" in command, "network record does not pin max redirects to zero")
        require("--proto =https" in command, "network record does not require HTTPS")
        require(",https" not in command and " --insecure " not in f" {command} ", "insecure TLS option found")
        for url in URL_RE.findall(command):
            approved_url(url, "commands.jsonl")
            observed_urls.add(url)
        require(record.get("redirect_followed") is False, "network command followed a redirect")
        require(record.get("exit_code") == 0, "network command exit code is not zero")
    require(observed_urls == expected_urls, "commands.jsonl network URL set mismatch")

    recorded_status = {}
    for artifact in index["artifacts"] + index["checksum_files"]:
        recorded_status[artifact["url"]] = artifact["http"]["status"]
    for record in network:
        urls = URL_RE.findall(record["command"])
        require(len(urls) == 1, "network command must contain one URL")
        require(record.get("http_status") == recorded_status.get(urls[0]), "command HTTP status mismatch")


def allowed_evidence_path(path: Path) -> bool:
    if path.name in {".gitattributes", "manifest.json", "manifest.sha256"}:
        return True
    return path.suffix.lower() in ALLOWED_FILES and path.suffix.lower() not in FORBIDDEN_SUFFIXES


def check_file_policy(root: Path) -> list[Path]:
    require(root.is_dir(), "evidence root missing")
    files = sorted(path for path in root.rglob("*") if path.is_file())
    for path in files:
        relative = path.relative_to(root)
        require(not path.is_symlink(), f"symlink not allowed in evidence: {relative}")
        require(allowed_evidence_path(path), f"binary or unsupported file in evidence: {relative}")
        require("__pycache__" not in relative.parts, f"cache file in evidence: {relative}")
        data = path.read_bytes()
        require(b"\x00" not in data, f"NUL byte in evidence text: {relative}")
        try:
            text = data.decode("utf-8")
        except UnicodeDecodeError as exc:
            fail(f"non-UTF-8 evidence file {relative}: {exc}")
        require("\r" not in text, f"CRLF/CR found in evidence text: {relative}")
        require("F:\\Develop\\" not in text and "C:\\Users\\" not in text, f"absolute personal path in evidence: {relative}")
    attributes = root / ".gitattributes"
    require(attributes in files, ".gitattributes missing")
    attr_text = attributes.read_text(encoding="utf-8")
    for pattern in ("*.json text eol=lf", "*.jsonl text eol=lf", "*.md text eol=lf", "*.py text eol=lf"):
        require(pattern in attr_text, f".gitattributes missing rule: {pattern}")
    return files


def check_manifest(root: Path, files: Iterable[Path]) -> None:
    manifest_path = root / "manifest.json"
    manifest = load_json(manifest_path)
    require(manifest.get("schema_id") == SCHEMA_MANIFEST, "manifest schema mismatch")
    require(manifest.get("scope") == "NETWORK_ARTIFACT_STATIC_AUDIT_ONLY", "manifest scope mismatch")
    require(manifest.get("status") == "STATIC_AUDIT_COMPLETE / Q03_OPEN / G1_NOT_PASSED / RUNTIME_UNVERIFIED", "manifest status overclaim")
    source = manifest.get("source_baseline")
    require(source == {"branch": "codex/v2-integration", "head": EXPECTED_HEAD, "initial_status_porcelain": ""}, "manifest source baseline mismatch")
    payload = manifest.get("payload")
    require(isinstance(payload, dict), "manifest payload missing")
    listed = payload.get("files")
    require(isinstance(listed, list), "manifest file list missing")
    current_files = {
        path.relative_to(root).as_posix(): path
        for path in files
        if path.name not in {"manifest.json", "manifest.sha256"}
    }
    listed_by_path = {row.get("path"): row for row in listed if isinstance(row, dict)}
    require(set(listed_by_path) == set(current_files), "manifest file set mismatch")
    total_bytes = 0
    for relative, path in current_files.items():
        row = listed_by_path[relative]
        require(row.get("bytes") == path.stat().st_size, f"manifest byte count mismatch: {relative}")
        require(row.get("sha256") == sha256_file(path), f"manifest hash mismatch: {relative}")
        total_bytes += path.stat().st_size
    require(payload.get("file_count") == len(current_files), "manifest file count mismatch")
    require(payload.get("total_bytes") == total_bytes, "manifest total bytes mismatch")
    checksum_path = root / "manifest.sha256"
    require(checksum_path.is_file(), "manifest.sha256 missing")
    checksum_text = checksum_path.read_text(encoding="utf-8")
    expected_checksum = f"{sha256_file(manifest_path)}  manifest.json\n"
    require(checksum_text == expected_checksum, "manifest.sha256 mismatch")


def verify(root: Path) -> dict[str, Any]:
    files = check_file_policy(root)
    check_manifest(root, files)
    index = load_json(root / "artifact-index.json")
    elf = load_json(root / "elf-inventory.json")
    license_matrix = load_json(root / "license-source-matrix.json")
    check_artifact_index(index)
    check_elf_inventory(elf)
    check_license_source_matrix(license_matrix)
    check_commands(root, index)
    return {
        "schema_id": "aurorama.g1-l1-network-pdfium.verify-result/v1",
        "status": "PASS",
        "scope": "STATIC_ARTIFACT_AUDIT_ONLY",
        "artifact_count": len(index["artifacts"]),
        "checksum_count": len(index["checksum_files"]),
        "elf_file_count": len(elf["files"]),
        "elf_runtime": "UNVERIFIED",
        "source_status": license_matrix["source_audit"]["status"],
        "q03": "OPEN",
        "g1": "NOT_PASSED",
    }


def expect_failure(name: str, operation: Callable[[], None]) -> str:
    try:
        operation()
    except AuditError:
        return name
    fail(f"negative case unexpectedly passed: {name}")


def self_test(root: Path) -> list[str]:
    index = load_json(root / "artifact-index.json")
    elf = load_json(root / "elf-inventory.json")
    license_matrix = load_json(root / "license-source-matrix.json")
    passed = []

    candidate = copy.deepcopy(index)
    next(row for row in candidate["artifacts"] if row["kind"] == "AAR")["sha256"] = "0" * 64
    passed.append(expect_failure("HASH_MISMATCH", lambda: check_artifact_index(candidate)))

    candidate = copy.deepcopy(index)
    next(row for row in candidate["artifacts"] if row["kind"] == "AAR")["size"] += 1
    passed.append(expect_failure("SIZE_MISMATCH", lambda: check_artifact_index(candidate)))

    candidate = copy.deepcopy(index)
    candidate["artifacts"][0]["http"]["redirect_hops"] = [
        {"status": 302, "location": "https://unapproved.example/artifact"}
    ]
    passed.append(expect_failure("UNAPPROVED_REDIRECT", lambda: check_artifact_index(candidate)))

    for label, kind in (
        ("MISSING_AAR", "AAR"),
        ("MISSING_POM", "POM"),
        ("MISSING_SOURCE", "SOURCES_JAR"),
    ):
        candidate = copy.deepcopy(index)
        next(row for row in candidate["artifacts"] if row["kind"] == kind)["http"]["status"] = 404
        passed.append(expect_failure(label, lambda value=candidate: check_artifact_index(value)))

    candidate = copy.deepcopy(elf)
    candidate["files"][0]["alignment_status"] = "UNPROVEN"
    passed.append(expect_failure("ELF_ALIGNMENT_UNPROVEN", lambda: check_elf_inventory(candidate)))

    candidate = copy.deepcopy(elf)
    candidate["runtime"]["page_size_16kb_runtime_status"] = "PASS"
    passed.append(expect_failure("STATIC_AS_RUNTIME", lambda: check_elf_inventory(candidate)))

    with tempfile.TemporaryDirectory(prefix="aurorama-pdfium-binary-negative-") as temp:
        bad_root = Path(temp)
        (bad_root / ".gitattributes").write_text(
            "*.json text eol=lf\n*.jsonl text eol=lf\n*.md text eol=lf\n*.py text eol=lf\n",
            encoding="utf-8",
            newline="\n",
        )
        (bad_root / "forbidden.aar").write_bytes(b"PK\\x03\\x04")
        passed.append(expect_failure("BINARY_IN_EVIDENCE", lambda: check_file_policy(bad_root)))

    return passed


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parent)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args(argv)
    try:
        if args.self_test:
            passed = self_test(args.root)
            print(json.dumps({"status": "PASS", "count": len(passed), "cases": passed}, ensure_ascii=False))
        else:
            print(json.dumps(verify(args.root), ensure_ascii=False, sort_keys=True))
        return 0
    except AuditError as exc:
        print(f"VERIFY FAIL: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
