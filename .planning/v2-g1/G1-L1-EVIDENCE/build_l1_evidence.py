#!/usr/bin/env python3
"""Build the Aurorama G1-L1 evidence artifacts from local files only."""

from __future__ import annotations

import hashlib
import json
import os
import re
import subprocess
import zipfile
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import unquote


ROOT = Path(__file__).resolve().parents[3]
EVIDENCE = Path(__file__).resolve().parent
APK_DIR = ROOT / "app" / "phone" / "build" / "outputs" / "apk" / "libre" / "debug"
SBOM_PATH = ROOT / ".planning/v2-g1/G1-S4-A-Q03-SBOM/sbom.cdx.json"
CONSOLE_PATH = EVIDENCE / "build-console.txt"

BUILD_STARTED = "2026-11-03T23:36:00.6687383Z"
BUILD_ENDED = "2026-11-03T23:40:57.0999766Z"
BUILD_SECONDS = 296.431


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def read_json(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def write_json(path: Path, value: object) -> None:
    path.write_text(
        json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
        newline="\n",
    )


def relative(path: Path) -> str:
    return path.resolve().relative_to(ROOT).as_posix()


def find_build_tool(name: str) -> Path:
    sdk = Path(os.environ["ANDROID_SDK_ROOT"])
    versions = sorted(
        (path for path in (sdk / "build-tools").iterdir() if path.is_dir()),
        key=lambda path: tuple(int(piece) for piece in re.findall(r"\d+", path.name)),
    )
    return versions[-1] / name


def parse_badging(apk: Path) -> dict:
    result = subprocess.run(
        [str(find_build_tool("aapt2.exe")), "dump", "badging", str(apk)],
        check=True,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    text = result.stdout
    package = re.search(r"^package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", text, re.M)
    native = re.search(r"^native-code: (.+)$", text, re.M)
    min_sdk = re.search(r"^(?:sdkVersion|minSdkVersion):'([^']+)'", text, re.M)
    target_sdk = re.search(r"^targetSdkVersion:'([^']+)'", text, re.M)
    if not all((package, native, min_sdk, target_sdk)):
        raise RuntimeError(f"Unable to parse aapt2 badging for {apk.name}")
    return {
        "package_name": package.group(1),
        "version_code": package.group(2),
        "version_name": package.group(3),
        "min_sdk": min_sdk.group(1),
        "target_sdk": target_sdk.group(1),
        "native_codes": re.findall(r"'([^']+)'", native.group(1)),
    }


def parse_signature(apk: Path) -> dict:
    result = subprocess.run(
        [str(find_build_tool("apksigner.bat")), "verify", "--verbose", "--print-certs", str(apk)],
        check=True,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    text = result.stdout
    dn = re.search(r"certificate DN: (.+)$", text, re.M)
    cert_sha = re.search(r"certificate SHA-256 digest: ([0-9a-f]+)$", text, re.M)
    schemes = {}
    for scheme in ("v1", "v2", "v3", "v3.1", "v3.2", "v4"):
        match = re.search(rf"Verified using {re.escape(scheme)} scheme[^:]*: (true|false)", text)
        schemes[scheme] = bool(match and match.group(1) == "true")
    return {
        "verified": text.startswith("Verifies"),
        "kind": "DEBUG_SIGNED",
        "certificate_dn": dn.group(1) if dn else None,
        "certificate_sha256": cert_sha.group(1).upper() if cert_sha else None,
        "schemes": schemes,
    }


def inventory_apk(apk: Path) -> dict:
    entries = []
    native = []
    license_notice = []
    with zipfile.ZipFile(apk) as archive:
        for info in archive.infolist():
            entry = {
                "path": info.filename,
                "crc32": f"{info.CRC:08X}",
                "compressed_bytes": info.compress_size,
                "uncompressed_bytes": info.file_size,
                "method": info.compress_type,
            }
            entries.append(entry)
            lower = info.filename.lower()
            if info.filename.startswith("lib/") and info.filename.endswith(".so"):
                item = dict(entry)
                item["sha256"] = sha256_bytes(archive.read(info))
                native.append(item)
            if any(token in lower for token in ("license", "notice", "copying", "copyright")):
                item = dict(entry)
                item["sha256"] = sha256_bytes(archive.read(info))
                license_notice.append(item)
    return {
        "path": relative(apk),
        "bytes": apk.stat().st_size,
        "sha256": sha256_file(apk).upper(),
        "mtime_utc": apk.stat().st_mtime_ns and datetime.fromtimestamp(
            apk.stat().st_mtime, timezone.utc
        ).isoformat(),
        "version": parse_badging(apk),
        "signing": parse_signature(apk),
        "zip_entry_count": len(entries),
        "entries": entries,
        "native_libraries": native,
        "license_notice_files": license_notice,
        "packaged_sbom_files": [
            item["path"] for item in entries if "sbom" in item["path"].lower()
        ],
        "is_v2_candidate": False,
        "artifact_stage": "CURRENT_APP_NOT_V2_CANDIDATE",
    }


def property_map(component: dict) -> dict[str, str]:
    return {
        item["name"]: item["value"]
        for item in component.get("properties", [])
    }


def parse_purl(purl: str) -> tuple[str, str, str]:
    match = re.fullmatch(r"pkg:maven/([^/]+)/([^@]+)@(.+)", purl)
    if not match:
        raise ValueError(f"Unsupported Maven PURL: {purl}")
    return unquote(match.group(1)), unquote(match.group(2)), unquote(match.group(3))


def parse_console(path: Path) -> dict:
    text = path.read_text(encoding="utf-8")
    required = (
        "L1_BUILD_COMMAND=",
        " --offline",
        " --no-daemon",
        "BUILD SUCCESSFUL",
    )
    for marker in required:
        if marker not in text:
            raise RuntimeError(f"Build console missing marker: {marker}")
    start = re.search(r"^L1_BUILD_START_UTC=(.+)$", text, re.M)
    end = re.search(r"^L1_BUILD_END_UTC=(.+)$", text, re.M)
    exit_code = re.search(r"^L1_BUILD_EXIT=(\d+)$", text, re.M)
    wall = re.search(r"^L1_BUILD_WALL_SECONDS=([0-9.]+)$", text, re.M)
    gradle_duration = re.search(r"^BUILD SUCCESSFUL in (.+)$", text, re.M)
    tasks = re.search(
        r"^(\d+) actionable tasks: (?:(\d+) executed(?:, )?)?(\d+) up-to-date$",
        text,
        re.M,
    )
    if not all((start, end, exit_code, wall, gradle_duration, tasks)):
        raise RuntimeError("Build console task, timing or exit markers are incomplete")
    return {
        "started_at_utc": start.group(1),
        "ended_at_utc": end.group(1),
        "exit_code": int(exit_code.group(1)),
        "wall_seconds": float(wall.group(1)),
        "gradle_duration": gradle_duration.group(1),
        "actionable_tasks": int(tasks.group(1)),
        "tasks_executed": int(tasks.group(2) or 0),
        "tasks_up_to_date": int(tasks.group(3)),
        "sha256": sha256_file(path).upper(),
        "bytes": path.stat().st_size,
        "line_count": len(text.splitlines()),
    }


def main() -> int:
    EVIDENCE.mkdir(parents=True, exist_ok=True)
    apk_paths = sorted(APK_DIR.glob("*.apk"))
    if len(apk_paths) != 4:
        raise RuntimeError(f"Expected four local debug APKs, found {len(apk_paths)}")

    packages = [inventory_apk(path) for path in apk_paths]
    representative = next(item for item in packages if "arm64-v8a" in item["path"])
    console = parse_console(CONSOLE_PATH)

    packaged = {
        "schema_id": "aurorama.g1-l1.packaged-inventory/v1",
        "artifact_stage": "CURRENT_APP_NOT_V2_CANDIDATE",
        "is_v2_candidate": False,
        "warning": "This is the current 1.4.0 integration build, not a 2.0 candidate.",
        "package_count": len(packages),
        "packages": packages,
        "summary": {
            "packages": len(packages),
            "total_zip_entries": sum(item["zip_entry_count"] for item in packages),
            "native_entries": sum(len(item["native_libraries"]) for item in packages),
            "license_notice_entries": sum(len(item["license_notice_files"]) for item in packages),
            "packaged_sbom_entries": sum(len(item["packaged_sbom_files"]) for item in packages),
            "representative_package": representative["path"],
            "representative_sha256": representative["sha256"],
        },
    }
    write_json(EVIDENCE / "packaged-inventory.json", packaged)

    sbom = read_json(SBOM_PATH)
    notice_rows = read_json(
        ROOT / ".planning/v2-g1/G1-S4-A-Q03-NOTICE/notice-source-offer-matrix.json"
    )["rows"]
    notice_by_component = {
        row["component"]: row for row in notice_rows
    }
    packaged_notice_paths = {
        item["path"] for item in representative["license_notice_files"]
    }
    components = []
    project_modules = []
    for component in sbom["components"]:
        if not component.get("purl"):
            project_modules.append(
                {
                    "name": component.get("name"),
                    "version": component.get("version") or None,
                    "license_status": "EXACT",
                    "license_ids": [
                        entry.get("license", {}).get("id")
                        for entry in component.get("licenses", [])
                        if entry.get("license", {}).get("id")
                    ],
                }
            )
            continue
        group, name, version = parse_purl(component["purl"])
        props = property_map(component)
        primary = props.get("aurorama:license.evidence.primary", "UNKNOWN_BLOCKED")
        if primary == "EXACT_POM":
            normalized = "EXACT"
        elif primary in {"FAMILY_ONLY", "MIXED_EXACT_FAMILY"}:
            normalized = "FAMILY"
        else:
            normalized = "UNKNOWN"

        notice_status = "NOT_FOUND_IN_CURRENT_APP"
        notice_pointer = None
        coordinate = f"{group}:{name}:{version}"
        if coordinate in notice_by_component:
            row = notice_by_component[coordinate]
            notice_status = f"Q03_{row['status']}"
            notice_pointer = ".planning/v2-g1/G1-S4-A-Q03-NOTICE/notice-source-offer-matrix.json"
        elif group.startswith("androidx.media3"):
            notice_status = "Q03_PROVEN_WRAPPER"
            notice_pointer = ".planning/v2-g1/G1-S4-A-Q03-NOTICE/notice-source-offer-matrix.json"
        elif group == "org.jellyfin.media3":
            notice_status = "Q03_PARTIAL"
            notice_pointer = ".planning/v2-g1/G1-S4-A-Q03-NOTICE/notice-source-offer-matrix.json"
        elif group == "dev.jdtech.mpv" or group == "io.github.peerless2012":
            notice_status = "Q03_PROVEN_WRAPPER"
            notice_pointer = ".planning/v2-g1/G1-S4-A-Q03-NOTICE/notice-source-offer-matrix.json"
        else:
            prefix = f"META-INF/{group.replace('.', '/')}/"
            found = sorted(path for path in packaged_notice_paths if path.startswith(prefix))
            if found:
                notice_status = "PRESENT_IN_CURRENT_APP"
                notice_pointer = found[0]

        raw_license_source = props.get("aurorama:license.source")
        license_source = raw_license_source
        source_reference_available = False
        if raw_license_source and raw_license_source.startswith(".planning/"):
            source_reference_available = (
                ROOT / raw_license_source.split("#", 1)[0]
            ).is_file()
        elif raw_license_source and re.match(r"^[A-Za-z]:[\\/]", raw_license_source):
            source_reference_available = Path(
                raw_license_source.split("#", 1)[0]
            ).is_file()
            license_source = "LOCAL_GRADLE_POM_CACHE"
        components.append(
            {
                "id": component["bom-ref"],
                "exact_coordinate": coordinate,
                "group": group,
                "name": name,
                "version": version,
                "purl": component["purl"],
                "relationship": props.get("aurorama:gradle.relationship"),
                "license_ids": [
                    entry.get("license", {}).get("id")
                    for entry in component.get("licenses", [])
                    if entry.get("license", {}).get("id")
                ],
                "license_provenance_status": normalized,
                "raw_license_primary": primary,
                "raw_license_evidence": props.get("aurorama:license.evidence"),
                "license_source_reference": license_source,
                "license_source_reference_available": source_reference_available,
                "corresponding_source_available": False,
                "notice_status": notice_status,
                "notice_pointer": notice_pointer,
                "is_v2_candidate_component": False,
            }
        )

    license_counts = Counter(item["license_provenance_status"] for item in components)
    notice_counts = Counter(item["notice_status"] for item in components)
    relationship_counts = Counter(item["relationship"] for item in components)
    dependencies = {
        "schema_id": "aurorama.g1-l1.dependency-inventory/v1",
        "artifact_stage": "CURRENT_APP_NOT_V2_CANDIDATE",
        "source": {
            "sbom": ".planning/v2-g1/G1-S4-A-Q03-SBOM/sbom.cdx.json",
            "sbom_sha256": sha256_file(SBOM_PATH).upper(),
            "catalog": "gradle/libs.versions.toml",
            "lockfile": None,
            "verification_metadata": None,
            "local_pom_cache_role": "not re-enumerated independently in this contracted pass",
        },
        "counts": {
            "external_components": len(components),
            "project_modules": len(project_modules),
            "relationship": dict(sorted(relationship_counts.items())),
            "license_provenance": {
                "EXACT": license_counts["EXACT"],
                "FAMILY": license_counts["FAMILY"],
                "UNKNOWN": license_counts["UNKNOWN"],
            },
            "license_source_reference_available": sum(
                item["license_source_reference_available"] for item in components
            ),
            "corresponding_source_available": sum(
                item["corresponding_source_available"] for item in components
            ),
            "notice_available_or_mapped": sum(
                item["notice_status"] not in {"NOT_FOUND_IN_CURRENT_APP"} for item in components
            ),
            "notice_status": dict(sorted(notice_counts.items())),
        },
        "components": components,
        "project_modules": project_modules,
        "gaps": [
            "No Gradle dependency lockfile or verification metadata is present.",
            "No corresponding-source bundle is packaged for any external component.",
            "246 components remain FAMILY and 4 mixed components remain visibly FAMILY.",
            "The current application package is not a 2.0 candidate.",
        ],
    }
    write_json(EVIDENCE / "dependency-inventory.json", dependencies)

    source_paths = [
        ("local L1 text attributes", EVIDENCE / ".gitattributes"),
        ("authoritative V2 plan", ROOT / "docs/V2_PLAN.md"),
        ("authoritative task board", ROOT / "docs/TASKS.md"),
        ("gradle wrapper properties", ROOT / "gradle/wrapper/gradle-wrapper.properties"),
        ("gradle wrapper jar", ROOT / "gradle/wrapper/gradle-wrapper.jar"),
        ("version catalog", ROOT / "gradle/libs.versions.toml"),
        ("build versions", ROOT / "buildSrc/src/main/kotlin/Versions.kt"),
        ("phone build", ROOT / "app/phone/build.gradle.kts"),
        ("Q03 SBOM", SBOM_PATH),
        ("Q03 native matrix", ROOT / ".planning/v2-g1/G1-S4-A-Q03-NATIVE/provenance-matrix.json"),
        ("Q03 notice matrix", ROOT / ".planning/v2-g1/G1-S4-A-Q03-NOTICE/notice-source-offer-matrix.json"),
        ("Q03 PDFium provenance", ROOT / ".planning/v2-g1/G1-S4-A-Q03-TTS-PDF/pdfium-provenance.json"),
        ("S4-C option matrix", ROOT / ".planning/v2-g1/G1-S4-C/option-matrix.json"),
        ("trackable command record", EVIDENCE / "commands.jsonl"),
        ("merged offline build console", CONSOLE_PATH),
    ]
    source_index = {
        "schema_id": "aurorama.g1-l1.source-index/v1",
        "worktree_identity": {
            "branch": "codex/v2-integration",
            "head": "89f09ca5da57c252e3a20779cde01fa7a80cc7d2",
            "initial_status_porcelain": "",
        },
        "inputs": [
            {
                "role": role,
                "path": relative(path),
                "bytes": path.stat().st_size,
                "sha256": sha256_file(path).upper(),
            }
            for role, path in source_paths
        ]
        + [
            {
                "role": "built APK artifact",
                "path": item["path"],
                "bytes": item["bytes"],
                "sha256": item["sha256"],
            }
            for item in packages
        ],
    }
    write_json(EVIDENCE / "source-index.json", source_index)

    build_attempt = {
        "schema_id": "aurorama.g1-l1.build-attempt/v1",
        "attempt_id": "g1-l1-offline-build-02",
        "command": ".\\gradlew.bat :app:phone:assembleDebug --offline --no-daemon --console=plain",
        "working_directory": "repository root",
        "environment": {
            "java_home": "<JAVA_HOME>",
            "java_version": "OpenJDK 25.0.3",
            "gradle_wrapper": "9.7.1",
            "offline": True,
            "daemon": False,
        },
        "started_at_utc": console["started_at_utc"],
        "ended_at_utc": console["ended_at_utc"],
        "wall_seconds": console["wall_seconds"],
        "gradle_duration": console["gradle_duration"],
        "exit_code": console["exit_code"],
        "gradle_result": "BUILD_SUCCESSFUL",
        "actionable_tasks": console["actionable_tasks"],
        "tasks_executed": console["tasks_executed"],
        "tasks_up_to_date": console["tasks_up_to_date"],
        "build_mode": "INCREMENTAL_UP_TO_DATE",
        "console_transcript": {
            "path": "build-console.txt",
            "bytes": console["bytes"],
            "line_count": console["line_count"],
            "sha256": console["sha256"],
            "stdout_stderr_merged": True,
            "line_endings": "LF",
            "absolute_paths_sanitized": True,
            "gradle_private_daemon_logs_included": False,
        },
        "failure_class": None,
        "network_used": False,
        "device_accessed": False,
        "apk_artifacts": [
            {
                "path": item["path"],
                "bytes": item["bytes"],
                "sha256": item["sha256"],
                "produced_by_attempt": False,
                "artifact_status": "PRESENT_AND_UP_TO_DATE",
                "is_v2_candidate": False,
            }
            for item in packages
        ],
    }
    write_json(EVIDENCE / "build-attempt.json", build_attempt)

    native_counts = read_json(
        ROOT / ".planning/v2-g1/G1-S4-A-Q03-NATIVE/provenance-matrix.json"
    )["counts"]
    pdfium = read_json(
        ROOT / ".planning/v2-g1/G1-S4-A-Q03-TTS-PDF/pdfium-provenance.json"
    )
    stable_pdf = next(item for item in pdfium["versions"] if item["version"] == "2.0.3")
    engine_rows = [
        {
            "id": "A1",
            "status": "PARTIAL",
            "finding": "Media3/decoder/libmpv/ass artifacts are present in the current offline debug build.",
            "local_artifact_present": True,
            "exact_coordinates": [
                "androidx.media3:media3-exoplayer:1.11.1",
                "androidx.media3:media3-session:1.11.1",
                "androidx.media3:media3-ui:1.11.1",
                "org.jellyfin.media3:media3-ffmpeg-decoder:1.9.0+1",
                "dev.jdtech.mpv:libmpv:1.0.0",
                "io.github.peerless2012:ass-kt:0.5.1",
            ],
            "abis": sorted({code for item in packages for code in item["version"]["native_codes"]}),
            "native_entries_per_package": sorted(
                {len(item["native_libraries"]) for item in packages}
            ),
            "static_16kb": {
                "M3-07": "PROVEN_STATIC_ONLY",
                "MPV-06": "PROVEN_STATIC_ONLY",
                "runtime": "BLOCKED",
            },
            "blocked_gaps": [
                "Media3 1.11.1 with decoder 1.9.0+1 runtime JNI/API compatibility",
                "Dual FFmpeg load order and symbol interposition",
                "Packaged native source/notice closure",
            ],
        },
        {
            "id": "B1",
            "status": "BLOCKED",
            "finding": "Sherpa-ONNX v2.0.1 is absent from local Gradle caches and has no official Maven coordinate.",
            "local_artifact_present": False,
            "fixed_release": {
                "version": "v2.0.1",
                "commit": "af2d8d81dbfa61c4cea4c568da78f3982edca005",
                "standard_aar_bytes": 49441341,
                "static_onnx_runtime_aar_bytes": 38577050,
            },
            "missing_artifact_gate": [
                "Pin release AAR and source archive by SHA-256.",
                "Required download domain if later authorized: github.com (k2-fsa/sherpa-onnx release assets).",
                "Prove zero-network capability before any system TTS fallback can be accepted.",
            ],
        },
        {
            "id": "C1",
            "status": "BLOCKED",
            "finding": "No auditable Mandarin voice, ONNX model, lexicon, rule data, or model card is present in the worktree.",
            "local_asset_present": False,
            "exact_asset_selection_gate": [
                "voice/model identity and upstream fixed release",
                "model card and training-data restrictions",
                "engine/model/lexicon/rule/espeak licenses and notice obligations",
                "byte size and SHA-256 for every packaged asset",
                "Mandarin quality, first-audio, RTF, memory, power and device gates in G2",
            ],
            "decision": "NOT_SELECTED",
        },
        {
            "id": "D1",
            "status": "BLOCKED",
            "finding": "Stable PDFium 2.0.3 AAR/source artifacts are absent locally; only prior metadata and remote hashes are available.",
            "local_artifact_present": False,
            "fixed_coordinates": [
                "io.legere:pdfiumandroid:2.0.3",
                "io.legere:pdfiumandroid-core:2.0.3",
                "io.legere:pdfiumandroid-api:2.0.3",
            ],
            "expected_aar_sha256": [
                item["sha256"].upper() for item in stable_pdf["aarArtifacts"]
            ],
            "native_revision": stable_pdf["nativeRevision"]["status"],
            "third_party_notice": "BLOCKED",
            "static_16kb": stable_pdf["static16kb"]["status"],
            "pdfrenderer_rollback": "RETAINED",
            "missing_download_gate": [
                "Required domain if later authorized: repo1.maven.org for the three pinned 2.0.3 AAR/JAR/POM/source artifacts.",
                "Local AAR hash, native revision, third-party notice and local 16 KB recheck.",
            ],
        },
    ]
    engine_matrix = {
        "schema_id": "aurorama.g1-l1.engine-asset-gap-matrix/v1",
        "decision_context": "V2-D202 recommendations consumed; exact voice/model not selected.",
        "counts": {
            "rows": len(engine_rows),
            "PARTIAL": sum(row["status"] == "PARTIAL" for row in engine_rows),
            "BLOCKED": sum(row["status"] == "BLOCKED" for row in engine_rows),
            "gap_count": sum(row["status"] in {"PARTIAL", "BLOCKED"} for row in engine_rows),
        },
        "rows": engine_rows,
        "global_boundaries": {
            "q03_closed": False,
            "g1_passed": False,
            "runtime_verified": False,
            "network_used": False,
            "device_accessed": False,
        },
    }
    write_json(EVIDENCE / "engine-asset-gap-matrix.json", engine_matrix)

    license_gap_rows = [
        {
            "id": "LG-MAVEN-LICENSE-EVIDENCE",
            "status": "PARTIAL",
            "count": components.__len__(),
            "detail": {
                "EXACT": license_counts["EXACT"],
                "FAMILY": license_counts["FAMILY"],
                "UNKNOWN": license_counts["UNKNOWN"],
            },
            "required_next": "Per-POM evidence for FAMILY components or explicit acceptance of provenance limits.",
        },
        {
            "id": "LG-PACKAGED-NOTICE",
            "status": "PARTIAL",
            "count": len(representative["license_notice_files"]),
            "detail": "License/notice-like files exist in the current APK, but no canonical 2.0 notice bundle exists.",
        },
        {
            "id": "LG-CORRESPONDING-SOURCE",
            "status": "BLOCKED",
            "count": 0,
            "detail": "No corresponding source bundle or written offer is present.",
        },
        {
            "id": "LG-NATIVE-PROVENANCE",
            "status": "BLOCKED",
            "count": native_counts["BLOCKED"],
            "detail": "Q03 native matrix remains 13 PROVEN / 6 PARTIAL / 12 BLOCKED; runtime is unverified.",
        },
        {
            "id": "LG-CANDIDATE-PACKAGED-SBOM",
            "status": "BLOCKED",
            "count": packaged["summary"]["packaged_sbom_entries"],
            "detail": "Current app package has no packaged SBOM and is not a 2.0 candidate.",
        },
        {
            "id": "LG-DEPENDENCY-LOCK-VERIFICATION",
            "status": "BLOCKED",
            "count": 0,
            "detail": "Gradle lockfile and verification metadata are absent.",
        },
    ]
    license_gap = {
        "schema_id": "aurorama.g1-l1.license-gap-matrix/v1",
        "counts": {
            "rows": len(license_gap_rows),
            "PARTIAL": sum(row["status"] == "PARTIAL" for row in license_gap_rows),
            "BLOCKED": sum(row["status"] == "BLOCKED" for row in license_gap_rows),
            "UNKNOWN": sum(row["status"] == "UNKNOWN" for row in license_gap_rows),
            "gap_count": sum(
                row["status"] in {"PARTIAL", "BLOCKED", "UNKNOWN"} for row in license_gap_rows
            ),
        },
        "rows": license_gap_rows,
        "coverage": {
            "external_license_provenance": {
                "EXACT": license_counts["EXACT"],
                "FAMILY": license_counts["FAMILY"],
                "UNKNOWN": license_counts["UNKNOWN"],
                "total": len(components),
            },
            "corresponding_source": {
                "available": 0,
                "total": len(components),
            },
            "notice_mapped": dependencies["counts"]["notice_available_or_mapped"],
            "notice_total": len(components),
        },
        "decision": "Q03 remains OPEN; no PARTIAL or UNKNOWN value is promoted to PASS.",
    }
    write_json(EVIDENCE / "license-gap-matrix.json", license_gap)

    payload_names = [
        ".gitattributes",
        "README.md",
        "CHECKPOINT.md",
        "source-index.json",
        "dependency-inventory.json",
        "packaged-inventory.json",
        "license-gap-matrix.json",
        "build-attempt.json",
        "engine-asset-gap-matrix.json",
        "commands.jsonl",
        "build-console.txt",
        "build_l1_evidence.py",
        "verify_l1_evidence.py",
    ]
    payload = []
    for name in payload_names:
        path = EVIDENCE / name
        if path.is_file():
            payload.append(
                {
                    "path": name,
                    "bytes": path.stat().st_size,
                    "sha256": sha256_file(path).upper(),
                }
            )
    manifest = {
        "schema_id": "aurorama.g1-l1.manifest/v1",
        "task_id": "V2-G1-L1-EVIDENCE",
        "status": "L1_PARTIAL",
        "scope": "LOCAL_STATIC_DEPENDENCY_PACKAGED_EVIDENCE_ONLY",
        "evidence_root": ".planning/v2-g1/G1-L1-EVIDENCE",
        "source": source_index["worktree_identity"],
        "candidate_status": "CURRENT_APP_NOT_V2_CANDIDATE",
        "authorization": {
            "l1_only": True,
            "network_used": False,
            "device_accessed": False,
            "account_accessed": False,
            "server_accessed": False,
            "production_code_modified": False,
            "runtime_executed": False,
            "q03_closed": False,
            "g1_passed": False,
        },
        "build": {
            "exit_code": 0,
            "apk_count": len(packages),
            "representative_apk": representative["path"],
            "representative_apk_sha256": representative["sha256"],
            "console_sha256": console["sha256"],
            "console_line_count": console["line_count"],
        },
        "coverage": {
            "dependency_license": dependencies["counts"]["license_provenance"],
            "notice_mapped": dependencies["counts"]["notice_available_or_mapped"],
            "corresponding_source": 0,
            "external_components": len(components),
            "engine_gaps": engine_matrix["counts"],
            "license_gaps": license_gap["counts"],
        },
        "payload": {
            "file_count": len(payload),
            "total_bytes": sum(item["bytes"] for item in payload),
            "files": payload,
        },
        "checksum_file": "manifest.sha256",
        "self_test_contract": [
            "APK_HASH_MISSING",
            "LICENSE_OVERCLAIM",
            "MANIFEST_HASH_MISSING",
            "CURRENT_APP_MISREPRESENTED",
            "AUTHORIZATION_VIOLATION",
            "COMMAND_RECORD_UNTRACKED",
            "CONSOLE_TRANSCRIPT_MISSING",
            "CONSOLE_SUCCESS_MARKER_MISSING",
            "CONSOLE_OFFLINE_MARKER_MISSING",
            "CONSOLE_NO_DAEMON_MARKER_MISSING",
            "ATTR_JSONL_EOL_MISSING",
            "ATTR_SHA256_EOL_MISSING",
            "ATTR_CONSOLE_WHITESPACE_MISSING",
            "EVIDENCE_DRY_RUN_INCOMPLETE",
            "EVIDENCE_FILE_IGNORED",
            "EVIDENCE_GIT_STATE_MIXED",
            "MANIFEST_FILE_SET_MISMATCH",
        ],
    }
    manifest_path = EVIDENCE / "manifest.json"
    write_json(manifest_path, manifest)
    (EVIDENCE / "manifest.sha256").write_text(
        f"{sha256_file(manifest_path).upper()}  manifest.json\n",
        encoding="utf-8",
        newline="\n",
    )
    print(
        json.dumps(
            {
                "status": "GENERATED",
                "payload_files": len(payload),
                "payload_bytes": manifest["payload"]["total_bytes"],
                "manifest_sha256": sha256_file(manifest_path).upper(),
                "representative_apk_sha256": representative["sha256"],
                "external_components": len(components),
            },
            ensure_ascii=False,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
