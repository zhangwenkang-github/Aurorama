#!/usr/bin/env python3
"""Verify the Aurorama G1-L1 local evidence bundle."""

from __future__ import annotations

import copy
import hashlib
import json
import os
import re
import subprocess
import sys
import tempfile
import unittest
import zipfile
from collections import Counter
from pathlib import Path


class EvidenceError(ValueError):
    """Raised when the evidence bundle violates its contract."""


def validate_semantics(bundle: dict) -> dict:
    """Validate cross-file evidence semantics."""
    manifest = bundle["manifest"]
    build = bundle["build_attempt"]
    dependencies = bundle["dependency_inventory"]

    _validate_manifest_checksum(bundle)
    _validate_apk_artifacts(build)
    _validate_license_components(dependencies)
    _validate_artifact_stage(manifest)
    _validate_authorization(bundle)

    return {
        "apk_artifacts": len(build["apk_artifacts"]),
        "components": len(dependencies["components"]),
    }


def _fail(code: str, detail: str) -> None:
    raise EvidenceError(f"{code}: {detail}")


def _is_sha256(value: object) -> bool:
    return isinstance(value, str) and re.fullmatch(r"[0-9A-Fa-f]{64}", value) is not None


def _validate_manifest_checksum(bundle: dict) -> None:
    record = bundle.get("manifest_sha256", {})
    if record.get("path") != "manifest.json" or not _is_sha256(record.get("sha256")):
        _fail("MANIFEST_HASH_MISSING", "manifest.sha256 must bind manifest.json to one SHA-256")


def _validate_apk_artifacts(build: dict) -> None:
    artifacts = build.get("apk_artifacts", [])
    if build.get("exit_code") == 0 and not artifacts:
        _fail("APK_HASH_MISSING", "successful build must list at least one APK")
    for artifact in artifacts:
        if not _is_sha256(artifact.get("sha256")):
            _fail("APK_HASH_MISSING", artifact.get("path", "<unknown>"))
        if not isinstance(artifact.get("bytes"), int) or artifact["bytes"] <= 0:
            _fail("APK_SIZE_INVALID", artifact.get("path", "<unknown>"))


def _validate_license_components(dependencies: dict) -> None:
    allowed = {"EXACT", "FAMILY", "UNKNOWN"}
    for component in dependencies.get("components", []):
        status = component.get("license_provenance_status")
        if status not in allowed:
            _fail("LICENSE_STATUS_INVALID", str(component.get("id")))
        raw = component.get("raw_license_primary") or component.get("provenance_status")
        if status == "EXACT" and raw != "EXACT_POM":
            _fail(
                "LICENSE_OVERCLAIM",
                f"{component.get('id')} is {status} but raw provenance is {raw}",
            )


def _validate_artifact_stage(manifest: dict) -> None:
    stage = manifest.get("artifact_stage") or manifest.get("candidate_status")
    if stage != "CURRENT_APP_NOT_V2_CANDIDATE":
        _fail("CURRENT_APP_MISREPRESENTED", f"artifact stage is {stage}")


def _validate_authorization(bundle: dict) -> None:
    authorization = bundle.get("authorization", {})
    prohibited = (
        "network_used",
        "device_accessed",
        "account_accessed",
        "server_accessed",
        "production_code_modified",
        "runtime_executed",
    )
    for key in prohibited:
        if authorization.get(key) is not False:
            _fail("AUTHORIZATION_VIOLATION", f"{key} must be false")


def validate_command_evidence(evidence: dict) -> None:
    """Validate the trackable command record and merged build transcript."""
    record_path = evidence.get("record_path", "")
    if record_path != "commands.jsonl" or evidence.get("tracked_by_default") is not True:
        _fail("COMMAND_RECORD_UNTRACKED", record_path)
    console_path = evidence.get("console_path")
    console_text = evidence.get("console_text", "")
    if console_path != "build-console.txt" or not console_text.strip():
        _fail("CONSOLE_TRANSCRIPT_MISSING", str(console_path))
    if "BUILD SUCCESSFUL" not in console_text:
        _fail("CONSOLE_SUCCESS_MARKER_MISSING", console_path)
    command_match = re.search(r"^L1_BUILD_COMMAND=(.+)$", console_text, re.M)
    if not command_match or "--offline" not in command_match.group(1):
        _fail("CONSOLE_OFFLINE_MARKER_MISSING", console_path)
    if "--no-daemon" not in command_match.group(1):
        _fail("CONSOLE_NO_DAEMON_MARKER_MISSING", console_path)


def validate_git_attributes(text: str) -> None:
    """Validate the local LF policy and transcript whitespace exemption."""
    rules = {}
    for line in text.splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        pieces = stripped.split()
        rules[pieces[0]] = set(pieces[1:])
    jsonl = rules.get("*.jsonl", set())
    sha256 = rules.get("*.sha256", set())
    console = rules.get("build-console.txt", set())
    if "text" not in jsonl or "eol=lf" not in jsonl:
        _fail("ATTR_JSONL_EOL_MISSING", "*.jsonl")
    if "text" not in sha256 or "eol=lf" not in sha256:
        _fail("ATTR_SHA256_EOL_MISSING", "*.sha256")
    if (
        "text" not in console
        or "eol=lf" not in console
        or "whitespace=-blank-at-eol" not in console
    ):
        _fail("ATTR_CONSOLE_WHITESPACE_MISSING", "build-console.txt")


def baseline_bundle() -> dict:
    """Return the smallest valid semantic fixture used by negative tests."""
    return {
        "manifest": {
            "schema_id": "aurorama.g1-l1.manifest/v1",
            "status": "L1_PARTIAL",
            "candidate_status": "CURRENT_APP_NOT_V2_CANDIDATE",
            "q03_closed": False,
            "g1_passed": False,
            "runtime_verified": False,
        },
        "build_attempt": {
            "exit_code": 0,
            "apk_artifacts": [
                {
                    "path": "app/phone/build/outputs/apk/debug/app-phone-debug.apk",
                    "bytes": 1,
                    "sha256": "0" * 64,
                }
            ],
        },
        "dependency_inventory": {
            "components": [
                {
                    "id": "fixture",
                    "license_status": "EXACT",
                    "license_provenance_status": "EXACT",
                    "raw_license_primary": "EXACT_POM",
                }
            ]
        },
        "manifest_sha256": {
            "path": "manifest.json",
            "sha256": "1" * 64,
        },
        "authorization": {
            "network_used": False,
            "device_accessed": False,
            "account_accessed": False,
            "server_accessed": False,
            "production_code_modified": False,
        },
    }


class NegativeContractTests(unittest.TestCase):
    def assert_invalid(self, bundle: dict, code: str) -> None:
        with self.assertRaises(EvidenceError) as ctx:
            validate_semantics(bundle)
        self.assertIn(code, str(ctx.exception))

    def test_missing_apk_hash_fails(self) -> None:
        bundle = baseline_bundle()
        del bundle["build_attempt"]["apk_artifacts"][0]["sha256"]
        self.assert_invalid(bundle, "APK_HASH_MISSING")

    def test_unknown_license_as_exact_fails(self) -> None:
        bundle = baseline_bundle()
        component = bundle["dependency_inventory"]["components"][0]
        component["license_provenance_status"] = "EXACT"
        component["raw_license_primary"] = "UNKNOWN_BLOCKED"
        self.assert_invalid(bundle, "LICENSE_OVERCLAIM")

    def test_missing_manifest_hash_fails(self) -> None:
        bundle = baseline_bundle()
        bundle["manifest_sha256"]["sha256"] = ""
        self.assert_invalid(bundle, "MANIFEST_HASH_MISSING")

    def test_current_app_as_v2_candidate_fails(self) -> None:
        bundle = baseline_bundle()
        bundle["manifest"]["candidate_status"] = "V2_CANDIDATE"
        self.assert_invalid(bundle, "CURRENT_APP_MISREPRESENTED")

    def test_unauthorized_network_or_device_fails(self) -> None:
        network_bundle = copy.deepcopy(baseline_bundle())
        network_bundle["authorization"]["network_used"] = True
        self.assert_invalid(network_bundle, "AUTHORIZATION_VIOLATION")

        device_bundle = copy.deepcopy(baseline_bundle())
        device_bundle["authorization"]["device_accessed"] = True
        self.assert_invalid(device_bundle, "AUTHORIZATION_VIOLATION")

    def command_fixture(self) -> dict:
        return {
            "record_path": "commands.jsonl",
            "tracked_by_default": True,
            "console_path": "build-console.txt",
            "console_text": (
                "L1_BUILD_COMMAND=gradlew.bat :app:phone:assembleDebug "
                "--offline --no-daemon --console=plain\n"
                "BUILD SUCCESSFUL in 1s\n"
            ),
        }

    def assert_invalid_commands(self, fixture: dict, code: str) -> None:
        with self.assertRaises(EvidenceError) as ctx:
            validate_command_evidence(fixture)
        self.assertIn(code, str(ctx.exception))

    def test_command_record_not_trackable_fails(self) -> None:
        fixture = self.command_fixture()
        fixture["tracked_by_default"] = False
        self.assert_invalid_commands(fixture, "COMMAND_RECORD_UNTRACKED")

    def test_missing_console_transcript_fails(self) -> None:
        fixture = self.command_fixture()
        fixture["console_text"] = ""
        self.assert_invalid_commands(fixture, "CONSOLE_TRANSCRIPT_MISSING")

    def test_console_missing_build_successful_fails(self) -> None:
        fixture = self.command_fixture()
        fixture["console_text"] = fixture["console_text"].replace("BUILD SUCCESSFUL", "FAILED")
        self.assert_invalid_commands(fixture, "CONSOLE_SUCCESS_MARKER_MISSING")

    def test_console_missing_offline_marker_fails(self) -> None:
        fixture = self.command_fixture()
        fixture["console_text"] = fixture["console_text"].replace(" --offline", "")
        self.assert_invalid_commands(fixture, "CONSOLE_OFFLINE_MARKER_MISSING")

    def test_console_missing_no_daemon_marker_fails(self) -> None:
        fixture = self.command_fixture()
        fixture["console_text"] = fixture["console_text"].replace(" --no-daemon", "")
        self.assert_invalid_commands(fixture, "CONSOLE_NO_DAEMON_MARKER_MISSING")

    def attributes_fixture(self) -> str:
        return (
            "*.json text eol=lf\n"
            "*.jsonl text eol=lf\n"
            "*.sha256 text eol=lf\n"
            "*.txt text eol=lf\n"
            "build-console.txt text eol=lf whitespace=-blank-at-eol\n"
        )

    def assert_invalid_attributes(self, text: str, code: str) -> None:
        with self.assertRaises(EvidenceError) as ctx:
            validate_git_attributes(text)
        self.assertIn(code, str(ctx.exception))

    def test_jsonl_eol_attribute_missing_fails(self) -> None:
        fixture = self.attributes_fixture().replace("*.jsonl text eol=lf\n", "")
        self.assert_invalid_attributes(fixture, "ATTR_JSONL_EOL_MISSING")

    def test_sha256_eol_attribute_missing_fails(self) -> None:
        fixture = self.attributes_fixture().replace("*.sha256 text eol=lf\n", "")
        self.assert_invalid_attributes(fixture, "ATTR_SHA256_EOL_MISSING")

    def test_console_whitespace_exemption_missing_fails(self) -> None:
        fixture = self.attributes_fixture().replace(
            "build-console.txt text eol=lf whitespace=-blank-at-eol\n",
            "build-console.txt text eol=lf\n",
        )
        self.assert_invalid_attributes(fixture, "ATTR_CONSOLE_WHITESPACE_MISSING")


class GitBundleStateTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.repo = Path(self.temporary.name)
        self.evidence_path = "evidence"
        self.evidence = self.repo / self.evidence_path
        self.evidence.mkdir()
        (self.repo / "base.txt").write_text("base\n", encoding="utf-8")
        self.git("init", "-q")
        self.git("add", "base.txt")
        self.git(
            "-c",
            "user.name=L1 Selftest",
            "-c",
            "user.email=l1-selftest@example.invalid",
            "commit",
            "-q",
            "-m",
            "base",
        )
        (self.evidence / ".gitattributes").write_text(
            "*.json text eol=lf\n*.py text eol=lf\n",
            encoding="utf-8",
        )
        (self.evidence / "one.json").write_text("{}\n", encoding="utf-8")
        (self.evidence / "two.py").write_text("print('ok')\n", encoding="utf-8")

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def git(self, *args: str) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            ["git", *args],
            cwd=self.repo,
            check=True,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
        )

    def expected_files(self) -> list[str]:
        return sorted(
            path.relative_to(self.evidence).as_posix()
            for path in self.evidence.rglob("*")
            if path.is_file()
        )

    def assert_state_invalid(self, code: str) -> None:
        with self.assertRaises(EvidenceError) as ctx:
            validate_git_bundle_state(self.repo, self.evidence_path, self.expected_files())
        self.assertIn(code, str(ctx.exception))

    def test_committed_checkout_full_bundle_is_reverifiable(self) -> None:
        result = verify_bundle(Path(__file__).resolve().parent)
        self.assertEqual("PASS", result["status"])

    def test_all_untracked_candidates_pass(self) -> None:
        result = validate_git_bundle_state(
            self.repo,
            self.evidence_path,
            self.expected_files(),
        )
        self.assertEqual("ADD_CANDIDATES", result["mode"])
        self.assertEqual(3, result["candidate_count"])

    def test_all_staged_candidates_pass(self) -> None:
        self.git("add", self.evidence_path)
        result = validate_git_bundle_state(
            self.repo,
            self.evidence_path,
            self.expected_files(),
        )
        self.assertEqual("ADD_CANDIDATES", result["mode"])
        self.assertEqual(3, result["candidate_count"])

    def test_all_head_tracked_files_pass(self) -> None:
        self.git("add", self.evidence_path)
        self.git(
            "-c",
            "user.name=L1 Selftest",
            "-c",
            "user.email=l1-selftest@example.invalid",
            "commit",
            "-q",
            "-m",
            "evidence",
        )
        result = validate_git_bundle_state(
            self.repo,
            self.evidence_path,
            self.expected_files(),
        )
        self.assertEqual("TRACKED_HEAD", result["mode"])
        self.assertEqual(0, result["candidate_count"])

    def test_mixed_tracked_and_untracked_files_fail(self) -> None:
        self.git("add", f"{self.evidence_path}/one.json")
        self.git(
            "-c",
            "user.name=L1 Selftest",
            "-c",
            "user.email=l1-selftest@example.invalid",
            "commit",
            "-q",
            "-m",
            "partial evidence",
        )
        self.assert_state_invalid("EVIDENCE_GIT_STATE_MIXED")

    def test_missing_dry_run_candidate_fails(self) -> None:
        expected = self.expected_files()
        (self.evidence / "two.py").unlink()
        with self.assertRaises(EvidenceError) as ctx:
            validate_git_bundle_state(self.repo, self.evidence_path, expected)
        self.assertIn("EVIDENCE_DRY_RUN_INCOMPLETE", str(ctx.exception))

    def test_nested_ignored_candidate_fails(self) -> None:
        nested = self.evidence / "subdir"
        nested.mkdir()
        (nested / "hidden.log").write_text("ignored\n", encoding="utf-8")
        (self.repo / ".gitignore").write_text(
            f"/{self.evidence_path}/subdir/hidden.log\n",
            encoding="utf-8",
        )
        self.assert_state_invalid("EVIDENCE_FILE_IGNORED")

    def test_tracked_bundle_nested_file_missing_from_manifest_fails(self) -> None:
        with self.assertRaises(EvidenceError) as ctx:
            validate_manifest_membership(
                ["manifest.json", "manifest.sha256", ".gitattributes", "subdir/one.json"],
                ["manifest.json", "manifest.sha256", ".gitattributes"],
            )
        self.assertIn("MANIFEST_FILE_SET_MISMATCH", str(ctx.exception))

    def test_nested_ignored_file_fails(self) -> None:
        evidence_root = Path(__file__).resolve().parent
        with tempfile.TemporaryDirectory(dir=evidence_root, prefix=".selftest-") as temporary:
            nested = Path(temporary) / "subdir"
            nested.mkdir()
            (nested / "hidden.log").write_text("ignored\n", encoding="utf-8")
            with self.assertRaises(EvidenceError) as ctx:
                verify_bundle(evidence_root)
        self.assertIn("EVIDENCE_FILE_IGNORED", str(ctx.exception))

    def test_nested_untracked_file_in_tracked_bundle_fails(self) -> None:
        evidence_root = Path(__file__).resolve().parent
        with tempfile.TemporaryDirectory(dir=evidence_root, prefix=".selftest-") as temporary:
            nested = Path(temporary) / "subdir"
            nested.mkdir()
            (nested / "visible.json").write_text("{}\n", encoding="utf-8")
            with self.assertRaises(EvidenceError) as ctx:
                verify_bundle(evidence_root)
        self.assertIn("EVIDENCE_GIT_STATE_MIXED", str(ctx.exception))

    def test_mixed_nested_untracked_and_ignored_files_fail(self) -> None:
        evidence_root = Path(__file__).resolve().parent
        with tempfile.TemporaryDirectory(dir=evidence_root, prefix=".selftest-") as temporary:
            nested = Path(temporary) / "subdir"
            nested.mkdir()
            (nested / "visible.json").write_text("{}\n", encoding="utf-8")
            (nested / "hidden.log").write_text("ignored\n", encoding="utf-8")
            with self.assertRaises(EvidenceError) as ctx:
                verify_bundle(evidence_root)
        self.assertIn("EVIDENCE_FILE_IGNORED", str(ctx.exception))


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest().upper()


def load_json(path: Path) -> dict:
    with path.open("r", encoding="utf-8") as stream:
        value = json.load(stream)
    if not isinstance(value, dict):
        _fail("JSON_ROOT_INVALID", str(path.name))
    return value


def unique_ids(items: list[dict], label: str) -> None:
    ids = [item.get("id") for item in items]
    if any(not item for item in ids) or len(ids) != len(set(ids)):
        _fail("SCHEMA_ID_INVALID", f"{label} ids must be non-empty and unique")


def validate_manifest_membership(files: list[str], payload_files: list[str]) -> None:
    """Require every non-self evidence file to appear exactly once in the manifest."""
    self_files = {"manifest.json", "manifest.sha256"}
    expected = set(files) - self_files
    observed = list(payload_files)
    duplicates = sorted(path for path, count in Counter(observed).items() if count > 1)
    observed_set = set(observed)
    missing = sorted(expected - observed_set)
    extra = sorted(observed_set - expected)
    if duplicates or missing or extra:
        _fail(
            "MANIFEST_FILE_SET_MISMATCH",
            f"duplicates={duplicates}, missing={missing}, extra={extra}",
        )


def validate_git_bundle_state(
    repo_root: Path,
    evidence_path: str,
    expected_files: list[str],
) -> dict:
    """Validate a committed bundle or a pre-commit ordinary-add candidate."""
    normalized_root = evidence_path.replace("\\", "/").rstrip("/")
    expected = {f"{normalized_root}/{name}" for name in expected_files}

    ignored = subprocess.run(
        ["git", "check-ignore", "--no-index", "--", *sorted(expected)],
        cwd=repo_root,
        check=False,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    if ignored.returncode == 0:
        ignored_paths = sorted(line for line in ignored.stdout.splitlines() if line)
        _fail("EVIDENCE_FILE_IGNORED", ",".join(ignored_paths))
    if ignored.returncode != 1:
        _fail("GIT_IGNORE_CHECK_FAILED", ignored.stderr.strip())

    head = subprocess.run(
        ["git", "ls-tree", "-r", "--name-only", "-z", "HEAD", "--", normalized_root],
        cwd=repo_root,
        check=False,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    if head.returncode != 0:
        _fail("GIT_HEAD_READ_FAILED", head.stderr.strip())
    head_paths = {path for path in head.stdout.split("\0") if path}
    if head_paths and head_paths != expected:
        _fail(
            "EVIDENCE_GIT_STATE_MIXED",
            f"tracked={len(head_paths)}/{len(expected)}, "
            f"missing={sorted(expected - head_paths)}, extra={sorted(head_paths - expected)}",
        )

    with tempfile.TemporaryDirectory() as temporary:
        environment = os.environ.copy()
        environment["GIT_INDEX_FILE"] = str(Path(temporary) / "index")
        subprocess.run(
            ["git", "read-tree", "HEAD"],
            cwd=repo_root,
            env=environment,
            check=True,
            capture_output=True,
        )
        dry_run = subprocess.run(
            ["git", "add", "-n", "--", normalized_root],
            cwd=repo_root,
            env=environment,
            check=False,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
        )
        staged = subprocess.run(
            ["git", "add", "--", normalized_root],
            cwd=repo_root,
            env=environment,
            check=False,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
        )
        diff_check = subprocess.run(
            ["git", "diff", "--cached", "--check"],
            cwd=repo_root,
            env=environment,
            check=False,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
        )

    candidate_paths = {
        line[len("add '") : -1]
        for line in dry_run.stdout.splitlines()
        if line.startswith("add '") and line.endswith("'")
    }
    candidate_count = len(candidate_paths)
    if head_paths:
        mode = "TRACKED_HEAD"
    else:
        mode = "ADD_CANDIDATES"
        if (
            dry_run.returncode != 0
            or candidate_count != len(expected)
            or candidate_paths != expected
        ):
            missing = sorted(expected - candidate_paths)
            extra = sorted(candidate_paths - expected)
            _fail(
                "EVIDENCE_DRY_RUN_INCOMPLETE",
                f"{candidate_count}/{len(expected)}, missing={missing}, extra={extra}",
            )
    if staged.returncode != 0 or diff_check.returncode != 0:
        _fail("GIT_DIFF_CHECK_FAILED", diff_check.stdout + diff_check.stderr)
    return {
        "mode": mode,
        "candidate_count": candidate_count,
        "diff_check_exit": diff_check.returncode,
    }


def check_lf_and_paths(paths: list[Path]) -> None:
    text_suffixes = {".json", ".jsonl", ".md", ".py", ".sha256", ".txt"}
    absolute_path = re.compile(r"(?<![A-Za-z])[A-Za-z]:[\\/]")
    for path in paths:
        if path.name != ".gitattributes" and path.suffix.lower() not in text_suffixes:
            continue
        data = path.read_bytes()
        if b"\r\n" in data:
            _fail("LF_VIOLATION", path.name)
        text = data.decode("utf-8")
        if absolute_path.search(text):
            _fail("ABSOLUTE_PATH_IN_BODY", path.name)


def verify_bundle(root: Path) -> dict:
    required = {
        "manifest.json",
        "manifest.sha256",
        ".gitattributes",
        "source-index.json",
        "dependency-inventory.json",
        "packaged-inventory.json",
        "license-gap-matrix.json",
        "build-attempt.json",
        "engine-asset-gap-matrix.json",
        "commands.jsonl",
        "build-console.txt",
        "README.md",
        "CHECKPOINT.md",
        "build_l1_evidence.py",
        "verify_l1_evidence.py",
    }
    evidence_paths = [path for path in root.rglob("*") if path.is_file()]
    present = {path.relative_to(root).as_posix() for path in evidence_paths}
    missing = required - present
    if missing:
        _fail("EVIDENCE_FILE_MISSING", ",".join(sorted(missing)))

    manifest = load_json(root / "manifest.json")
    build = load_json(root / "build-attempt.json")
    dependencies = load_json(root / "dependency-inventory.json")
    packaged = load_json(root / "packaged-inventory.json")
    license_gaps = load_json(root / "license-gap-matrix.json")
    engines = load_json(root / "engine-asset-gap-matrix.json")
    source_index = load_json(root / "source-index.json")
    attributes_text = (root / ".gitattributes").read_text(encoding="utf-8")
    validate_git_attributes(attributes_text)
    attr_targets = ["commands.jsonl", "manifest.sha256", "build-console.txt"]
    eol_check = subprocess.run(
        ["git", "check-attr", "eol", "--", *attr_targets],
        cwd=root,
        check=False,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    for target in attr_targets:
        if f"{target}: eol: lf" not in eol_check.stdout:
            _fail("ATTR_EOL_NOT_EFFECTIVE", target)
    whitespace_check = subprocess.run(
        ["git", "check-attr", "whitespace", "--", "build-console.txt"],
        cwd=root,
        check=False,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    if "build-console.txt: whitespace: -blank-at-eol" not in whitespace_check.stdout:
        _fail("ATTR_CONSOLE_WHITESPACE_MISSING", "build-console.txt")
    command_lines = [
        json.loads(line)
        for line in (root / "commands.jsonl").read_text(encoding="utf-8").splitlines()
        if line.strip()
    ]
    if not command_lines:
        _fail("COMMAND_RECORD_EMPTY", "commands.jsonl")
    ignored = subprocess.run(
        ["git", "check-ignore", "--quiet", "--", "commands.jsonl"],
        cwd=root,
        check=False,
    )
    validate_command_evidence(
        {
            "record_path": "commands.jsonl",
            "tracked_by_default": ignored.returncode != 0,
            "console_path": "build-console.txt",
            "console_text": (root / "build-console.txt").read_text(encoding="utf-8"),
        }
    )
    if build.get("console_transcript", {}).get("path") != "build-console.txt":
        _fail("CONSOLE_TRANSCRIPT_MISSING", "build-attempt.json")
    console_path = root / "build-console.txt"
    console_record = build["console_transcript"]
    console_text = console_path.read_text(encoding="utf-8")
    if sha256_file(console_path) != console_record["sha256"].upper():
        _fail("CONSOLE_HASH_MISMATCH", "build-console.txt")
    if console_record["line_count"] != len(console_text.splitlines()):
        _fail("CONSOLE_LINE_COUNT_MISMATCH", "build-console.txt")
    exit_marker = re.search(r"^L1_BUILD_EXIT=(\d+)$", console_text, re.M)
    if not exit_marker or int(exit_marker.group(1)) != build["exit_code"]:
        _fail("CONSOLE_EXIT_MISMATCH", "build-console.txt")
    if (root / "commands.log").exists() or any(root.glob("*.log")):
        _fail("IGNORED_LOG_FILE_PRESENT", "commands.log")
    repo_root = root.parents[2]
    evidence_path = root.relative_to(repo_root).as_posix()
    git_state = validate_git_bundle_state(repo_root, evidence_path, sorted(present))

    checksum_parts = (root / "manifest.sha256").read_text(encoding="utf-8").strip().split()
    if len(checksum_parts) != 2 or checksum_parts[1] != "manifest.json":
        _fail("MANIFEST_HASH_MISSING", "manifest.sha256 format")
    actual_manifest_hash = sha256_file(root / "manifest.json")
    if checksum_parts[0].upper() != actual_manifest_hash:
        _fail("MANIFEST_HASH_MISMATCH", actual_manifest_hash)

    bundle = {
        "manifest": manifest,
        "build_attempt": build,
        "dependency_inventory": dependencies,
        "manifest_sha256": {
            "path": "manifest.json",
            "sha256": checksum_parts[0],
        },
        "authorization": manifest["authorization"],
    }
    validate_semantics(bundle)

    expected_schemas = {
        "manifest": "aurorama.g1-l1.manifest/v1",
        "source-index": "aurorama.g1-l1.source-index/v1",
        "dependency-inventory": "aurorama.g1-l1.dependency-inventory/v1",
        "packaged-inventory": "aurorama.g1-l1.packaged-inventory/v1",
        "license-gap-matrix": "aurorama.g1-l1.license-gap-matrix/v1",
        "build-attempt": "aurorama.g1-l1.build-attempt/v1",
        "engine-asset-gap-matrix": "aurorama.g1-l1.engine-asset-gap-matrix/v1",
    }
    actual_schemas = {
        "manifest": manifest.get("schema_id"),
        "source-index": source_index.get("schema_id"),
        "dependency-inventory": dependencies.get("schema_id"),
        "packaged-inventory": packaged.get("schema_id"),
        "license-gap-matrix": license_gaps.get("schema_id"),
        "build-attempt": build.get("schema_id"),
        "engine-asset-gap-matrix": engines.get("schema_id"),
    }
    if actual_schemas != expected_schemas:
        _fail("SCHEMA_INVALID", json.dumps(actual_schemas, sort_keys=True))

    validate_manifest_membership(
        sorted(present),
        [record.get("path", "") for record in manifest["payload"]["files"]],
    )
    payload_paths = []
    for record in manifest["payload"]["files"]:
        path = root / record["path"]
        if not path.is_file():
            _fail("PAYLOAD_FILE_MISSING", record["path"])
        if sha256_file(path) != record["sha256"].upper():
            _fail("PAYLOAD_HASH_MISMATCH", record["path"])
        if path.stat().st_size != record["bytes"]:
            _fail("PAYLOAD_SIZE_MISMATCH", record["path"])
        payload_paths.append(path)
    if manifest["payload"]["file_count"] != len(payload_paths):
        _fail("PAYLOAD_COUNT_MISMATCH", "manifest file_count")
    if manifest["payload"]["total_bytes"] != sum(path.stat().st_size for path in payload_paths):
        _fail("PAYLOAD_BYTES_MISMATCH", "manifest total_bytes")

    for record in source_index["inputs"]:
        path = root.parents[2] / record["path"]
        if not path.is_file():
            _fail("SOURCE_FILE_MISSING", record["path"])
        if sha256_file(path) != record["sha256"].upper():
            _fail("SOURCE_HASH_MISMATCH", record["path"])
        if path.stat().st_size != record["bytes"]:
            _fail("SOURCE_SIZE_MISMATCH", record["path"])

    package_by_path = {item["path"]: item for item in packaged["packages"]}
    for artifact in build["apk_artifacts"]:
        package = package_by_path.get(artifact["path"])
        if not package:
            _fail("PACKAGED_APK_MISSING", artifact["path"])
        if package["sha256"].upper() != artifact["sha256"].upper():
            _fail("PACKAGED_HASH_MISMATCH", artifact["path"])
        if package.get("is_v2_candidate") is not False:
            _fail("CURRENT_APP_MISREPRESENTED", artifact["path"])
        apk = root.parents[2] / artifact["path"]
        if sha256_file(apk) != artifact["sha256"].upper():
            _fail("APK_HASH_MISMATCH", artifact["path"])
        with zipfile.ZipFile(apk) as archive:
            observed = [
                (info.filename, info.CRC, info.compress_size, info.file_size)
                for info in archive.infolist()
            ]
        recorded = [
            (
                entry["path"],
                int(entry["crc32"], 16),
                entry["compressed_bytes"],
                entry["uncompressed_bytes"],
            )
            for entry in package["entries"]
        ]
        if observed != recorded:
            _fail("ZIP_INVENTORY_MISMATCH", artifact["path"])
        if len(package["native_libraries"]) != sum(
            entry["path"].startswith("lib/") and entry["path"].endswith(".so")
            for entry in package["entries"]
        ):
            _fail("NATIVE_INVENTORY_MISMATCH", artifact["path"])

    unique_ids(dependencies["components"], "dependency")
    unique_ids(license_gaps["rows"], "license gap")
    unique_ids(engines["rows"], "engine gap")
    if {row["id"] for row in engines["rows"]} != {"A1", "B1", "C1", "D1"}:
        _fail("ENGINE_GAP_ID_MISMATCH", "A1/B1/C1/D1 required")

    license_counts = Counter(
        component["license_provenance_status"] for component in dependencies["components"]
    )
    expected_license_counts = {
        "EXACT": license_counts["EXACT"],
        "FAMILY": license_counts["FAMILY"],
        "UNKNOWN": license_counts["UNKNOWN"],
    }
    if dependencies["counts"]["license_provenance"] != expected_license_counts:
        _fail("LICENSE_COUNT_MISMATCH", "dependency license counts")
    if license_gaps["coverage"]["external_license_provenance"] != {
        **expected_license_counts,
        "total": len(dependencies["components"]),
    }:
        _fail("LICENSE_COUNT_MISMATCH", "license gap coverage")

    for matrix, key in ((license_gaps, "license_gaps"), (engines, "engine_gaps")):
        rows = matrix["rows"]
        statuses = Counter(row["status"] for row in rows)
        if matrix["counts"]["rows"] != len(rows):
            _fail("GAP_COUNT_MISMATCH", key)
        if matrix["counts"]["gap_count"] != sum(
            row["status"] in {"PARTIAL", "BLOCKED", "UNKNOWN"} for row in rows
        ):
            _fail("GAP_COUNT_MISMATCH", key)
        if matrix["counts"].get("PARTIAL", 0) != statuses["PARTIAL"]:
            _fail("GAP_COUNT_MISMATCH", f"{key} PARTIAL")
        if matrix["counts"].get("BLOCKED", 0) != statuses["BLOCKED"]:
            _fail("GAP_COUNT_MISMATCH", f"{key} BLOCKED")

    if packaged["summary"]["packages"] != len(packaged["packages"]):
        _fail("PACKAGE_COUNT_MISMATCH", "packaged summary")
    if build["exit_code"] == 0:
        for artifact in build["apk_artifacts"]:
            if (
                artifact.get("produced_by_attempt") is not True
                and build.get("build_mode") != "INCREMENTAL_UP_TO_DATE"
            ):
                _fail("BUILD_ARTIFACT_PROVENANCE_INVALID", artifact["path"])

    if manifest["status"] not in {"L1_PARTIAL", "L1_COMPLETE", "L1_BLOCKED"}:
        _fail("STATUS_INVALID", str(manifest["status"]))
    check_lf_and_paths(evidence_paths)

    return {
        "status": "PASS",
        "scope_status": manifest["status"],
        "manifest_sha256": actual_manifest_hash,
        "command_events": len(command_lines),
        "git_state": git_state["mode"],
        "candidate_files": git_state["candidate_count"],
        "diff_check_exit": git_state["diff_check_exit"],
        "payload_files": len(payload_paths),
        "payload_bytes": sum(path.stat().st_size for path in payload_paths),
        "apk_count": len(build["apk_artifacts"]),
        "components": len(dependencies["components"]),
        "license_counts": expected_license_counts,
        "license_gap_count": license_gaps["counts"]["gap_count"],
        "engine_gap_count": engines["counts"]["gap_count"],
    }


if __name__ == "__main__":
    if "--self-test" in sys.argv:
        suite = unittest.TestSuite(
            unittest.defaultTestLoader.loadTestsFromTestCase(test_case)
            for test_case in (NegativeContractTests, GitBundleStateTests)
        )
        result = unittest.TextTestRunner(verbosity=2).run(suite)
        raise SystemExit(0 if result.wasSuccessful() else 1)
    evidence_root = Path(__file__).resolve().parent
    print(json.dumps(verify_bundle(evidence_root), ensure_ascii=False, sort_keys=True))
