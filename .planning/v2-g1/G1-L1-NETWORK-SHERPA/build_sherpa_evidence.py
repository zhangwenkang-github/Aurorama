#!/usr/bin/env python3
"""Build static Sherpa-ONNX 2.0.1 evidence from allowlisted artifacts.

Artifacts are written only below --temp-dir. The evidence directory receives
metadata and short UTF-8 license/notice extracts, never AAR, TAR, JAR or ELF.
"""

from __future__ import annotations

import argparse
import hashlib
import io
import json
import os
import re
import shutil
import struct
import subprocess
import tarfile
import tempfile
import urllib.parse
import zipfile
from collections import Counter
from pathlib import Path
from typing import Any


EXPECTED_COMMIT = "af2d8d81dbfa61c4cea4c568da78f3982edca005"
ARTIFACTS = {
    "sherpa-standard-aar": {
        "url": "https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.1/sherpa-onnx-2.0.1.aar",
        "filename": "sherpa-onnx-2.0.1.aar",
        "expectedSize": 49_441_341,
        "expectedSha256": "a8d09d1d32f3eb8520482c6d3d43fae674681c5b050385967c70a3c2c9bf0ee7",
        "finalHost": "release-assets.githubusercontent.com",
        "kind": "aar",
    },
    "sherpa-static-onnxruntime-aar": {
        "url": "https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.1/sherpa-onnx-static-link-onnxruntime-2.0.1.aar",
        "filename": "sherpa-onnx-static-link-onnxruntime-2.0.1.aar",
        "expectedSize": 38_577_050,
        "expectedSha256": "07e042d97f3a6bd5b7fe74d4eef0513dc01a714b16c15a903250095aba43a575",
        "finalHost": "release-assets.githubusercontent.com",
        "kind": "aar",
    },
    "sherpa-source-v2.0.1": {
        "url": "https://github.com/k2-fsa/sherpa-onnx/archive/refs/tags/v2.0.1.tar.gz",
        "filename": "sherpa-onnx-2.0.1.tar.gz",
        "expectedSize": None,
        "expectedSha256": None,
        "finalHost": "codeload.github.com",
        "kind": "source",
    },
}
ABI_NAMES = {"arm64-v8a", "armeabi-v7a", "x86", "x86_64"}
LICENSE_NAME = re.compile(r"(^|/)(LICENSE|LICENSE\..*|LICENCE|COPYING|NOTICE|NOTICE\..*)$", re.I)
CMAKE_NAME = re.compile(r"(^|/)CMakeLists\.txt$")
TEXT_LIMIT = 65_536


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def write_json(path: Path, value: Any) -> None:
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")


def write_text(path: Path, value: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(value, encoding="utf-8", newline="\n")


def append_jsonl(path: Path, value: dict[str, Any]) -> None:
    with path.open("a", encoding="utf-8", newline="\n") as stream:
        stream.write(json.dumps(value, ensure_ascii=False, sort_keys=True) + "\n")


def run(command: list[str], *, cwd: Path | None = None, binary: bool = False) -> subprocess.CompletedProcess[bytes]:
    result = subprocess.run(command, cwd=cwd, check=False, capture_output=True)
    if result.returncode != 0:
        detail = result.stderr.decode("utf-8", errors="replace")[-1000:]
        raise RuntimeError(f"command failed ({result.returncode}): {command[0]}: {detail}")
    return result


def curl_headers(url: str, *, method: str, output: Path | None = None) -> dict[str, Any]:
    with tempfile.NamedTemporaryFile(prefix="sherpa-headers-", delete=False) as header_stream:
        header_path = Path(header_stream.name)
    command = [
        shutil.which("curl.exe") or "curl.exe",
        "-sS",
        "--max-redirs",
        "0",
        "--retry",
        "2",
        "--retry-connrefused",
        "--retry-delay",
        "1",
        "--proto",
        "=https",
        "-D",
        str(header_path),
    ]
    if method == "HEAD":
        command.append("-I")
    command.extend(["-o", str(output) if output else ("NUL" if os.name == "nt" else os.devnull), url])
    try:
        result = run(command)
        raw = header_path.read_bytes().decode("iso-8859-1", errors="replace")
    finally:
        header_path.unlink(missing_ok=True)
    blocks: list[dict[str, str]] = []
    current: dict[str, str] = {}
    status = None
    for line in raw.replace("\r\n", "\n").splitlines():
        if line.startswith("HTTP/"):
            if current:
                blocks.append(current)
            current = {}
            status = int(line.split()[1])
            current["_status"] = str(status)
        elif ":" in line and current:
            key, value = line.split(":", 1)
            current[key.strip().lower()] = value.strip()
    if current:
        blocks.append(current)
    if not blocks:
        raise RuntimeError("curl emitted no response headers")
    selected = blocks[-1]
    return {
        "status": int(selected["_status"]),
        "location": selected.get("location"),
        "contentLength": int(selected["content-length"]) if selected.get("content-length", "").isdigit() else None,
        "contentType": selected.get("content-type"),
        "requestHost": urllib.parse.urlparse(url).hostname,
    }


def validate_redirect(location: str, spec: dict[str, Any]) -> None:
    parsed = urllib.parse.urlparse(location)
    if parsed.scheme != "https" or parsed.hostname != spec["finalHost"]:
        raise RuntimeError(f"redirect host outside allowlist: {parsed.hostname}")
    if spec["kind"] == "aar":
        if not parsed.path.startswith("/github-production-release-asset/"):
            raise RuntimeError("release asset redirect path is not GitHub release-assets")
        disposition = urllib.parse.parse_qs(parsed.query).get("response-content-disposition", [""])[0]
        filename_match = re.search(r"filename=([^;]+)", disposition)
        filename = filename_match.group(1).strip().strip("\"'") if filename_match else ""
        if filename != spec["filename"]:
            raise RuntimeError(f"release asset filename mismatch: {filename!r}")
    else:
        expected_path = "/k2-fsa/sherpa-onnx/tar.gz/refs/tags/v2.0.1"
        if parsed.path != expected_path:
            raise RuntimeError(f"source redirect path mismatch: {parsed.path}")


def sanitized_location(location: str) -> str:
    parsed = urllib.parse.urlparse(location)
    if parsed.hostname == "release-assets.githubusercontent.com":
        disposition = urllib.parse.parse_qs(parsed.query).get("response-content-disposition", [""])[0]
        query = urllib.parse.urlencode({"response-content-disposition": disposition})
        return urllib.parse.urlunparse((parsed.scheme, parsed.netloc, parsed.path, "", query, ""))
    return urllib.parse.urlunparse((parsed.scheme, parsed.netloc, parsed.path, "", "", ""))


def fetch_artifact(evidence: Path, temp_root: Path, artifact_id: str, spec: dict[str, Any]) -> dict[str, Any]:
    original_url = spec["url"]
    target = temp_root / spec["filename"]

    head_original = curl_headers(original_url, method="HEAD")
    if head_original["status"] not in {301, 302, 307, 308} or not head_original["location"]:
        raise RuntimeError(f"{artifact_id}: HEAD did not return an allowlisted redirect")
    validate_redirect(head_original["location"], spec)
    head_final = curl_headers(head_original["location"], method="HEAD")
    if head_final["status"] != 200:
        raise RuntimeError(f"{artifact_id}: redirected HEAD status {head_final['status']}")

    get_original = curl_headers(original_url, method="GET")
    if get_original["status"] not in {301, 302, 307, 308} or not get_original["location"]:
        raise RuntimeError(f"{artifact_id}: GET did not return an allowlisted redirect")
    validate_redirect(get_original["location"], spec)
    get_final = curl_headers(get_original["location"], method="GET", output=target)
    if get_final["status"] != 200:
        raise RuntimeError(f"{artifact_id}: redirected GET status {get_final['status']}")

    observed_size = target.stat().st_size
    observed_hash = sha256_file(target)
    if spec["expectedSize"] is not None and observed_size != spec["expectedSize"]:
        raise RuntimeError(f"{artifact_id}: size mismatch {observed_size} != {spec['expectedSize']}")
    if spec["expectedSha256"] is not None and observed_hash != spec["expectedSha256"]:
        raise RuntimeError(f"{artifact_id}: SHA-256 mismatch {observed_hash}")

    redirect_location = sanitized_location(get_original["location"])
    final_host = urllib.parse.urlparse(get_original["location"]).hostname
    return {
        "id": artifact_id,
        "kind": spec["kind"],
        "url": original_url,
        "filename": spec["filename"],
        "observedSize": observed_size,
        "size": observed_size,
        "sha256": observed_hash,
        "expectedSize": spec["expectedSize"],
        "expectedSha256": spec["expectedSha256"],
        "network": {
            "head": {
                "requestHost": "github.com",
                "status": head_original["status"],
                "redirectStatus": head_original["status"],
                "location": sanitized_location(head_original["location"]),
                "finalHost": final_host,
                "finalUrl": redirect_location,
                "finalStatus": head_final["status"],
                "contentLength": head_final["contentLength"],
                "sameArtifactValidated": True,
            },
            "fetch": {
                "requestHost": "github.com",
                "originalGetStatus": get_original["status"],
                "redirectStatus": get_original["status"],
                "location": redirect_location,
                "finalHost": final_host,
                "finalUrl": redirect_location,
                "status": get_final["status"],
                "contentLength": get_final["contentLength"],
                "downloadedBytes": observed_size,
                "sameArtifactValidated": True,
            },
        },
        "temporaryPath": str(target),
    }


def artifact_from_successful_retry(temp_root: Path, artifact_id: str, spec: dict[str, Any]) -> dict[str, Any]:
    """Reuse the single successful allowlisted retry without another network call."""
    prefix = {
        "sherpa-standard-aar": "standard",
        "sherpa-static-onnxruntime-aar": "static",
        "sherpa-source-v2.0.1": "source",
    }[artifact_id]
    target = temp_root / spec["filename"]
    head_origin_path = temp_root / f"{prefix}-head.txt"
    final_head_path = temp_root / f"{prefix}-final-head.txt"
    final_get_path = temp_root / f"{prefix}-get.txt"
    for path in (target, head_origin_path, final_head_path, final_get_path):
        if not path.is_file():
            raise RuntimeError(f"successful retry input missing: {path.name}")

    def parse_headers(path: Path) -> dict[str, Any]:
        raw = path.read_bytes().decode("iso-8859-1", errors="replace").replace("\r\n", "\n")
        statuses = re.findall(r"^HTTP/\S+\s+(\d+)", raw, flags=re.M)
        if not statuses:
            raise RuntimeError(f"no status in {path.name}")
        location_match = re.search(r"^Location:\s*(.+)$", raw, flags=re.I | re.M)
        length_match = re.search(r"^Content-Length:\s*(\d+)$", raw, flags=re.I | re.M)
        return {
            "status": int(statuses[-1]),
            "location": location_match.group(1).strip() if location_match else None,
            "contentLength": int(length_match.group(1)) if length_match else None,
        }

    head_origin = parse_headers(head_origin_path)
    final_head = parse_headers(final_head_path)
    final_get = parse_headers(final_get_path)
    if head_origin["status"] not in {301, 302, 307, 308} or not head_origin["location"]:
        raise RuntimeError(f"{artifact_id}: saved HEAD redirect is invalid")
    validate_redirect(head_origin["location"], spec)
    if final_head["status"] != 200 or final_get["status"] != 200:
        raise RuntimeError(f"{artifact_id}: saved final HEAD/GET status is not 200")

    observed_size = target.stat().st_size
    observed_hash = sha256_file(target)
    if spec["expectedSize"] is not None and observed_size != spec["expectedSize"]:
        raise RuntimeError(f"{artifact_id}: size mismatch")
    if spec["expectedSha256"] is not None and observed_hash != spec["expectedSha256"]:
        raise RuntimeError(f"{artifact_id}: SHA-256 mismatch")
    redirect_location = sanitized_location(head_origin["location"])
    return {
        "id": artifact_id,
        "kind": spec["kind"],
        "url": spec["url"],
        "filename": spec["filename"],
        "observedSize": observed_size,
        "size": observed_size,
        "sha256": observed_hash,
        "expectedSize": spec["expectedSize"],
        "expectedSha256": spec["expectedSha256"],
        "network": {
            "head": {
                "requestHost": "github.com",
                "status": head_origin["status"],
                "redirectStatus": head_origin["status"],
                "location": redirect_location,
                "finalHost": spec["finalHost"],
                "finalUrl": redirect_location,
                "finalStatus": final_head["status"],
                "contentLength": final_head["contentLength"],
                "sameArtifactValidated": True,
            },
            "fetch": {
                "requestHost": spec["finalHost"],
                "requestMethod": "direct GET of the already validated redirect URL",
                "redirectStatus": head_origin["status"],
                "location": redirect_location,
                "finalHost": spec["finalHost"],
                "finalUrl": redirect_location,
                "status": final_get["status"],
                "contentLength": final_get["contentLength"],
                "downloadedBytes": observed_size,
                "sameArtifactValidated": True,
                "httpVersion": "HTTP/1.1",
                "retryPolicy": "--retry 2 --retry-delay 2 --retry-all-errors --max-time 240",
            },
        },
        "temporaryPath": str(target),
    }


def sanitize_component(value: str) -> str:
    value = re.sub(r"[^A-Za-z0-9._-]+", "__", value)
    return value.strip("_")[:120] or "license"


def extract_short_text(data: bytes) -> tuple[str, bool]:
    truncated = len(data) > TEXT_LIMIT
    sample = data[:TEXT_LIMIT]
    text = sample.decode("utf-8", errors="replace")
    if truncated:
        text += "\n\n[TRUNCATED BY AURORAMA EVIDENCE BUILDER AT 65536 BYTES]\n"
    return text, truncated


def jar_tool() -> Path:
    found = shutil.which("jar") or shutil.which("jar.exe")
    if found:
        return Path(found)
    fallback = Path(r"F:\Develop\Android\Android Studio\jbr\bin\jar.exe")
    if fallback.is_file():
        return fallback
    raise RuntimeError("jar tool unavailable")


def readelf_tool() -> Path | None:
    found = shutil.which("llvm-readelf") or shutil.which("llvm-readelf.exe")
    if found:
        return Path(found)
    fallback = shutil.which("readelf") or shutil.which("readelf.exe")
    return Path(fallback) if fallback else None


def parse_elf_program_headers(data: bytes) -> dict[str, Any]:
    result: dict[str, Any] = {
        "status": "UNPROVEN",
        "parseError": None,
        "elfClass": None,
        "endianness": None,
        "loadSegments": [],
    }
    try:
        if data[:4] != b"\x7fELF":
            raise ValueError("not ELF")
        elf_class = data[4]
        data_encoding = data[5]
        if data_encoding == 1:
            endian = "<"
            result["endianness"] = "little"
        elif data_encoding == 2:
            endian = ">"
            result["endianness"] = "big"
        else:
            raise ValueError("unsupported ELF data encoding")
        if elf_class == 2:
            result["elfClass"] = 64
            header = struct.unpack_from(endian + "HHIQQQIHHHHHH", data, 16)
            phoff = header[4]
            phentsize = header[8]
            phnum = header[9]
            ph_format = endian + "IIQQQQQQ"
        elif elf_class == 1:
            result["elfClass"] = 32
            header = struct.unpack_from(endian + "HHIIIIIHHHHHH", data, 16)
            phoff = header[4]
            phentsize = header[8]
            phnum = header[9]
            ph_format = endian + "IIIIIIII"
        else:
            raise ValueError("unsupported ELF class")
        expected = struct.calcsize(ph_format)
        if phentsize < expected:
            raise ValueError(f"program header entry too small: {phentsize}")
        for index in range(phnum):
            offset = phoff + index * phentsize
            values = struct.unpack_from(ph_format, data, offset)
            p_type = values[0]
            if elf_class == 2:
                p_flags, p_offset, p_vaddr, p_paddr, p_filesz, p_memsz, p_align = values[1:]
            else:
                p_offset, p_vaddr, p_paddr, p_filesz, p_memsz, p_flags, p_align = values[1:]
            if p_type != 1:
                continue
            flags = "".join(
                [("R" if p_flags & 4 else "-"), ("W" if p_flags & 2 else "-"), ("X" if p_flags & 1 else "-")]
            )
            congruence = p_align == 0 or (p_vaddr % p_align) == (p_offset % p_align)
            result["loadSegments"].append(
                {
                    "index": len(result["loadSegments"]),
                    "offset": p_offset,
                    "vaddr": p_vaddr,
                    "filesz": p_filesz,
                    "memsz": p_memsz,
                    "flags": flags,
                    "align": p_align,
                    "alignHex": f"0x{p_align:x}",
                    "offsetVaddrCongruent": congruence,
                    "atLeast16KiB": p_align >= 16_384,
                }
            )
        if not result["loadSegments"]:
            raise ValueError("no PT_LOAD segments")
        aligned = all(segment["atLeast16KiB"] and segment["offsetVaddrCongruent"] for segment in result["loadSegments"])
        result["status"] = "PASS" if aligned else "FAIL"
    except Exception as exc:
        result["parseError"] = str(exc)
        result["status"] = "UNPROVEN"
    return result


def readelf_crosscheck(so_path: Path, readelf: Path | None) -> dict[str, Any]:
    if readelf is None:
        return {"available": False, "status": "UNPROVEN", "loadSegmentCount": None}
    result = run([str(readelf), "-lW", str(so_path)])
    text = result.stdout.decode("utf-8", errors="replace")
    load_lines = [line for line in text.splitlines() if line.strip().startswith("LOAD ")]
    alignments = []
    for line in load_lines:
        parts = line.split()
        try:
            alignments.append(int(parts[-1], 16))
        except (ValueError, IndexError):
            pass
    return {
        "available": True,
        "status": "PASS_STATIC" if alignments and all(value >= 16_384 for value in alignments) else "FAIL",
        "loadSegmentCount": len(load_lines),
        "alignments": alignments,
    }


def dynamic_needed(so_path: Path, readelf: Path | None) -> list[str]:
    if readelf is None:
        return []
    result = run([str(readelf), "-dW", str(so_path)])
    text = result.stdout.decode("utf-8", errors="replace")
    needed = []
    for line in text.splitlines():
        match = re.search(r"\(NEEDED\).*\[(.+?)\]", line)
        if match:
            needed.append(match.group(1))
    return needed


def inspect_aar(
    evidence: Path,
    temp_root: Path,
    artifact: dict[str, Any],
    jar: Path,
    readelf: Path | None,
) -> tuple[dict[str, Any], list[dict[str, Any]], dict[str, dict[str, Any]]]:
    archive_path = Path(artifact["temporaryPath"])
    inventory: dict[str, Any] = {
        "id": artifact["id"],
        "sha256": artifact["sha256"],
        "size": artifact["size"],
        "entries": [],
        "entryCount": 0,
        "classesJar": None,
        "manifest": None,
        "abis": [],
        "licenseEntries": [],
        "embeddedRuntimeObservation": {},
    }
    license_evidence: list[dict[str, Any]] = []
    elf_by_abi: dict[str, dict[str, Any]] = {}
    with zipfile.ZipFile(archive_path) as archive:
        zip_rows = archive.infolist()
        inventory["entryCount"] = len(zip_rows)
        inventory["entries"] = [
            {
                "path": row.filename,
                "compressedBytes": row.compress_size,
                "bytes": row.file_size,
                "crc32": f"{row.CRC:08x}",
                "directory": row.is_dir(),
            }
            for row in zip_rows
        ]
        jar_tf = run([str(jar), "tf", str(archive_path)])
        jar_names = [line for line in jar_tf.stdout.decode("utf-8", errors="replace").splitlines() if line]
        if len(jar_names) != len(zip_rows):
            raise RuntimeError(f"{artifact['id']}: jar tf/ZIP entry-count mismatch")

        for required in ("classes.jar", "AndroidManifest.xml"):
            if required not in archive.namelist():
                raise RuntimeError(f"{artifact['id']}: missing {required}")

        classes_data = archive.read("classes.jar")
        inventory["classesJar"] = {
            "path": "classes.jar",
            "bytes": len(classes_data),
            "sha256": sha256_bytes(classes_data),
            "entryCount": len(zipfile.ZipFile(io.BytesIO(classes_data)).infolist()),
        }
        manifest_data = archive.read("AndroidManifest.xml")
        inventory["manifest"] = {
            "path": "AndroidManifest.xml",
            "bytes": len(manifest_data),
            "sha256": sha256_bytes(manifest_data),
            "format": "Android binary XML",
        }

        so_rows: dict[str, list[dict[str, Any]]] = {abi: [] for abi in sorted(ABI_NAMES)}
        for row in zip_rows:
            match = re.fullmatch(r"jni/([^/]+)/(.+)\.so", row.filename)
            if not match:
                continue
            abi, basename = match.groups()
            if abi not in ABI_NAMES:
                continue
            so_data = archive.read(row)
            extracted = temp_root / "elf" / artifact["id"] / abi / (basename + ".so")
            extracted.parent.mkdir(parents=True, exist_ok=True)
            extracted.write_bytes(so_data)
            elf = parse_elf_program_headers(so_data)
            crosscheck = readelf_crosscheck(extracted, readelf)
            needed = dynamic_needed(extracted, readelf)
            ascii_onnx_hits = len(re.findall(rb"onnxruntime", so_data, flags=re.I))
            so_entry = {
                "path": row.filename,
                "name": basename + ".so",
                "size": len(so_data),
                "bytes": len(so_data),
                "sha256": sha256_bytes(so_data),
                "status": elf["status"],
                "elfClass": elf["elfClass"],
                "endianness": elf["endianness"],
                "loadSegments": elf["loadSegments"],
                "parseError": elf["parseError"],
                "readelfCrossCheck": crosscheck,
                "neededLibraries": needed,
                "onnxruntimeAsciiHitCount": ascii_onnx_hits,
            }
            so_rows[abi].append(so_entry)
        inventory["abis"] = [{"abi": abi, "soFiles": so_rows[abi]} for abi in sorted(ABI_NAMES)]
        elf_by_abi[artifact["id"]] = {
            abi: so_rows[abi]
            for abi in sorted(ABI_NAMES)
        }

        for row in zip_rows:
            if row.is_dir() or not LICENSE_NAME.search(row.filename):
                continue
            data = archive.read(row)
            text, truncated = extract_short_text(data)
            stored_name = f"AAR-{artifact['id']}-{sanitize_component(row.filename)}"
            stored_path = evidence / "license-texts" / (stored_name + ".txt")
            write_text(stored_path, text)
            entry = {
                "originalPath": row.filename,
                "bytes": len(data),
                "sha256": sha256_bytes(data),
                "extractedPath": stored_path.name,
                "extractedBytes": stored_path.stat().st_size,
                "truncated": truncated,
            }
            inventory["licenseEntries"].append(entry)
            license_evidence.append(
                {
                    "source": artifact["id"],
                    "originalPath": row.filename,
                    "path": stored_path.relative_to(evidence).as_posix(),
                    "sha256": sha256_file(stored_path),
                }
            )

    shared = sorted(
        {
            needed
            for abi_rows in inventory["abis"]
            for so_file in abi_rows["soFiles"]
            for needed in so_file["neededLibraries"]
        }
    )
    standard_basenames = sorted({Path(row["path"]).name for row in inventory["entries"] if row["path"].endswith(".so")})
    inventory["sharedLibraries"] = shared
    inventory["embeddedRuntimeObservation"] = {
        "nativeLibraryBasenames": standard_basenames,
        "separateOnnxRuntimeSharedLibrary": any("onnxruntime" in name.lower() for name in standard_basenames),
        "onnxruntimeNeededOrAsciiHits": any(
            "onnxruntime" in library.lower()
            for library in shared
        )
        or any(so["onnxruntimeAsciiHitCount"] for abi_rows in inventory["abis"] for so in abi_rows["soFiles"]),
        "scope": "static filename / DT_NEEDED / ASCII evidence only",
    }
    return inventory, license_evidence, {abi: so_rows[abi] for abi in sorted(ABI_NAMES)}


def source_inventory(
    evidence: Path,
    artifact: dict[str, Any],
) -> tuple[dict[str, Any], list[dict[str, Any]]]:
    archive_path = Path(artifact["temporaryPath"])
    inventory: dict[str, Any] = {
        "id": artifact["id"],
        "sha256": artifact["sha256"],
        "size": artifact["size"],
        "totalMembers": 0,
        "fileCount": 0,
        "directoryCount": 0,
        "topLevel": [],
        "licenseFiles": [],
        "noticeFiles": [],
        "cmakeFiles": [],
        "thirdPartyPaths": [],
        "correspondingSourceCandidates": [],
        "archiveHasGitMetadata": False,
        "files": [],
    }
    license_evidence: list[dict[str, Any]] = []
    all_members: list[tarfile.TarInfo] = []
    with tarfile.open(archive_path, mode="r:gz") as archive:
        members = archive.getmembers()
        inventory["totalMembers"] = len(members)
        inventory["fileCount"] = sum(member.isfile() for member in members)
        inventory["directoryCount"] = sum(member.isdir() for member in members)
        top = sorted({member.name.split("/", 1)[0] + "/" for member in members if member.name.split("/", 1)[0]})
        inventory["topLevel"] = top
        inventory["archiveHasGitMetadata"] = any(member.name.startswith(tuple(item + ".git/" for item in top)) for member in members)
        inventory["files"] = [
            {"path": member.name, "bytes": member.size, "type": "file" if member.isfile() else "directory" if member.isdir() else "other"}
            for member in members
            if not member.isdir()
        ]
        for member in members:
            if not member.isfile():
                continue
            path = member.name
            relative = path.split("/", 1)[1] if "/" in path else path
            if LICENSE_NAME.search(path):
                entry = {"path": path, "bytes": member.size}
                inventory["noticeFiles" if "NOTICE" in Path(path).name.upper() else "licenseFiles"].append(entry)
            if CMAKE_NAME.search(path):
                inventory["cmakeFiles"].append({"path": path, "bytes": member.size})
            if relative.startswith("third_party/"):
                parts = relative.split("/")
                if len(parts) > 1:
                    inventory["thirdPartyPaths"].append(parts[1])
            if relative.startswith(("sherpa-onnx/csrc/", "sherpa-onnx/c-api/", "cmake/", ".gitmodules")):
                inventory["correspondingSourceCandidates"].append({"path": path, "bytes": member.size})
            if member.size <= TEXT_LIMIT * 4 and (
                LICENSE_NAME.search(path)
                or relative in {"LICENSE", "NOTICE", ".gitmodules"}
                or relative.endswith(("third_party/CMakeLists.txt", "sherpa-onnx/csrc/CMakeLists.txt"))
            ):
                all_members.append(member)

        inventory["thirdPartyPaths"] = sorted(set(inventory["thirdPartyPaths"]))
        for member in all_members[:80]:
            stream = archive.extractfile(member)
            if stream is None:
                continue
            data = stream.read(TEXT_LIMIT + 1)
            text, truncated = extract_short_text(data)
            stored_name = f"SOURCE-{sanitize_component(member.name)}.txt"
            stored_path = evidence / "license-texts" / stored_name
            write_text(stored_path, text)
            license_evidence.append(
                {
                    "source": artifact["id"],
                    "originalPath": member.name,
                    "path": stored_path.relative_to(evidence).as_posix(),
                    "sha256": sha256_file(stored_path),
                    "truncated": truncated,
                }
            )
    return inventory, license_evidence


def detect_license_family(evidence_files: list[dict[str, Any]], evidence_root: Path) -> str:
    families = set()
    for item in evidence_files:
        text = (evidence_root / item["path"]).read_text(encoding="utf-8", errors="replace")
        if "Apache License" in text and "Version 2.0" in text:
            families.add("Apache-2.0")
        if "MIT License" in text or re.search(r"Permission is hereby granted, free of charge", text):
            families.add("MIT")
        if "BSD 3-Clause" in text or "Redistribution and use in source and binary forms" in text:
            families.add("BSD")
    return "+".join(sorted(families)) if families else "UNRESOLVED"


def inspect_onnxruntime_inputs(source_row: dict[str, Any]) -> dict[str, Any]:
    """Read the Android ONNX Runtime package inputs from the frozen source tar."""
    abi_by_stem = {
        "aarch64": "arm64-v8a",
        "arm": "armeabi-v7a",
        "i686": "x86",
        "x86_64": "x86_64",
    }
    inputs: list[dict[str, Any]] = []
    archive_path = Path(source_row["temporaryPath"])
    with tarfile.open(archive_path, mode="r:gz") as archive:
        for member in archive.getmembers():
            name = member.name
            match = re.search(r"/cmake/onnxruntime-android-(aarch64|arm|i686|x86_64)(-static)?\.cmake$", name)
            if not match:
                continue
            stream = archive.extractfile(member)
            if stream is None:
                continue
            text = stream.read().decode("utf-8", errors="replace")
            url_match = re.search(r'set\(onnxruntime_URL\s+"([^"]+)"', text)
            hash_match = re.search(r'set\(onnxruntime_HASH\s+"SHA256=([0-9a-fA-F]{64})"', text)
            version_match = re.search(r"/v(\d+\.\d+\.\d+)/", url_match.group(1) if url_match else "")
            inputs.append(
                {
                    "abi": abi_by_stem[match.group(1)],
                    "linkage": "static" if match.group(2) else "shared",
                    "cmakePath": name,
                    "packageVersion": version_match.group(1) if version_match else None,
                    "packageUrl": url_match.group(1) if url_match else None,
                    "packageSha256": hash_match.group(1).lower() if hash_match else None,
                }
            )
    versions = sorted({row["packageVersion"] for row in inputs if row["packageVersion"]})
    return {
        "declaredPackageVersion": versions[0] if len(versions) == 1 else None,
        "declaredVersions": versions,
        "revisionEvidence": "Release version and package hash are embedded in the source CMake files; an upstream commit SHA is not embedded in the downloaded AAR/source inputs.",
        "androidInputs": sorted(inputs, key=lambda row: (row["abi"], row["linkage"])),
    }


def build_license_matrix(
    evidence: Path,
    license_evidence: list[dict[str, Any]],
    aar_rows: list[dict[str, Any]],
    source_row: dict[str, Any],
) -> dict[str, Any]:
    family = detect_license_family(license_evidence, evidence)
    standard = next(row for row in aar_rows if row["id"] == "sherpa-standard-aar")
    static = next(row for row in aar_rows if row["id"] == "sherpa-static-onnxruntime-aar")
    standard_onnx_needed = any(
        "onnxruntime" in lib.lower()
        for abi in standard["abis"]
        for so in abi["soFiles"]
        for lib in so["neededLibraries"]
    )
    static_onnx_hits = any(
        so["onnxruntimeAsciiHitCount"] > 0
        for abi in static["abis"]
        for so in abi["soFiles"]
    )
    static_separate_abis = sorted(
        abi["abi"]
        for abi in static["abis"]
        if any(so["name"] == "libonnxruntime.so" for so in abi["soFiles"])
    )
    onnx_inputs = inspect_onnxruntime_inputs(source_row)
    onnx_form = (
        "standard AAR embeds a separate libonnxruntime.so in all four ABIs; "
        f"static AAR has no separate ONNX Runtime library on {', '.join(sorted(ABI_NAMES - set(static_separate_abis)))} "
        f"and still carries libonnxruntime.so on {', '.join(static_separate_abis)}"
    )
    return {
        "schema": "aurorama.g1-sherpa-license-source-matrix.v1",
        "status": "STATIC_INPUT_ONLY",
        "runtimeVerificationStatus": "UNVERIFIED",
        "finalPackagedNoticeComplete": False,
        "q03Status": "OPEN",
        "g1Status": "NOT_PASSED",
        "rows": [
            {
                "id": "apache-2.0-code",
                "component": "sherpa-onnx source / AAR",
                "observedLicenseFamily": family,
                "evidence": "source archive license/notice inventory",
                "obligations": [
                    "retain license text for distributed Apache-2.0 code",
                    "state modified files when redistribution includes modifications",
                    "retain applicable copyright and attribution notices",
                ],
                "status": "OBSERVED" if "Apache-2.0" in family else "UNPROVEN",
            },
            {
                "id": "onnxruntime",
                "component": "ONNX Runtime linked by Sherpa-ONNX",
                "form": onnx_form if standard_onnx_needed and static_onnx_hits else "static observation incomplete",
                "evidence": "AAR DT_NEEDED/ASCII scans plus Android package URLs and SHA-256 values declared in source CMake; no execution",
                "obligations": [
                    "record exact ONNX Runtime revision/configuration in final SBOM",
                    "retain ONNX Runtime license and notices when distributed",
                    "include third-party dependencies pulled into the linked runtime",
                ],
                "status": "PARTIAL",
            },
            {
                "id": "third-party-notice",
                "component": "transitive native / source dependencies",
                "evidence": "source third_party inventory plus AAR license/notice entry scan",
                "obligations": [
                    "derive final transitive inventory from the exact build configuration",
                    "retain required MIT / BSD / Apache / other notices",
                    "resolve source-availability duties for copyleft or file-level copyleft components",
                ],
                "status": "INCOMPLETE",
            },
            {
                "id": "source-offer",
                "component": "Aurorama distribution corresponding source / upstream source pointers",
                "evidence": "source tarball is available and hashed; no final packaged source bundle",
                "obligations": [
                    "include or offer the exact corresponding source required by Aurorama GPL-3.0 distribution",
                    "map any native modifications and build scripts to the shipped binary",
                    "do not treat this static tarball as the final source bundle",
                ],
                "status": "OPEN",
            },
        ],
        "licenseNoticeEvidence": license_evidence,
        "sbomInput": {
            "readyForFinalPackagedSbom": False,
            "sherpaSource": {"id": source_row["id"], "sha256": source_row["sha256"], "bytes": source_row["size"]},
            "aarInputs": [{"id": row["id"], "sha256": row["sha256"], "bytes": row["size"]} for row in aar_rows],
            "onnxRuntimeObservedStatically": standard_onnx_needed or static_onnx_hits,
            "onnxRuntimePackageVersion": onnx_inputs["declaredPackageVersion"],
            "onnxRuntimeRevisionEvidence": onnx_inputs["revisionEvidence"],
            "onnxRuntimeAndroidInputs": onnx_inputs["androidInputs"],
            "thirdPartyClosure": "UNRESOLVED",
        },
        "missing": [
            "final packaged notice bundle",
            "upstream ONNX Runtime commit SHA and complete build configuration/notices",
            "complete transitive third-party notice and source-obligation closure",
            "final Aurorama GPL-3.0 corresponding-source bundle",
            "runtime load / offline / zero-network behavior",
        ],
    }


def generate_manifest(evidence: Path) -> None:
    payload = []
    for path in sorted(evidence.rglob("*")):
        if not path.is_file() or path.name in {"manifest.json", "manifest.sha256"} or "__pycache__" in path.parts:
            continue
        payload.append(
            {
                "path": path.relative_to(evidence).as_posix(),
                "bytes": path.stat().st_size,
                "sha256": sha256_file(path),
            }
        )
    manifest = {
        "schema": "aurorama.g1-l1.sherpa-static-audit.v1",
        "status": "STATIC_EVIDENCE_READY / G1_NOT_PASSED / Q03_OPEN / RUNTIME_UNVERIFIED",
        "selfExcluded": "manifest.json",
        "checksumFile": "manifest.sha256",
        "payload": payload,
    }
    write_json(evidence / "manifest.json", manifest)
    manifest_hash = sha256_file(evidence / "manifest.json")
    write_text(evidence / "manifest.sha256", f"{manifest_hash}  manifest.json\n")


def record_event(evidence: Path, event: str, command: str, exit_code: int, result: str) -> None:
    append_jsonl(
        evidence / "commands.jsonl",
        {"event": event, "command": command, "exit": exit_code, "result": result},
    )
    generate_manifest(evidence)


def build(evidence: Path, temp_root: Path, *, reuse_successful_retry: bool = False) -> None:
    if not temp_root.is_dir():
        raise RuntimeError("--temp-dir must exist")
    evidence.mkdir(parents=True, exist_ok=True)
    (evidence / "license-texts").mkdir(exist_ok=True)
    commands_path = evidence / "commands.jsonl"
    if commands_path.exists():
        raise RuntimeError("commands.jsonl already exists; refusing to mix audit runs")
    append_jsonl(
        commands_path,
        {
            "event": "tdd_red",
            "command": "python -B .planning/v2-g1/G1-L1-NETWORK-SHERPA/verify_sherpa_evidence.py --self-test",
            "exit": 1,
            "result": "BASE_RED before evidence generation: required files absent",
        },
    )

    jar = jar_tool()
    readelf = readelf_tool()
    artifacts = []
    for artifact_id, spec in ARTIFACTS.items():
        if reuse_successful_retry:
            row = artifact_from_successful_retry(temp_root, artifact_id, spec)
        else:
            row = fetch_artifact(evidence, temp_root, artifact_id, spec)
        append_jsonl(
            commands_path,
            {
                "event": "head",
                "command": f"curl.exe --max-redirs 0 -I <allowlisted {artifact_id} URL and validated redirect>",
                "exit": 0,
                "result": f"status={row['network']['head']['status']} final={row['network']['head']['finalHost']} content-length={row['network']['head']['contentLength']}",
            },
        )
        append_jsonl(
            commands_path,
            {
                "event": "get",
                "command": (
                    f"curl.exe --http1.1 --max-redirs 0 --max-time 240 --retry 2 --retry-delay 2 "
                    f"--retry-all-errors <validated {artifact_id} redirect>"
                    if reuse_successful_retry
                    else f"curl.exe --max-redirs 0 <allowlisted {artifact_id} URL and validated redirect>"
                ),
                "exit": 0,
                "result": f"status={row['network']['fetch']['status']} final={row['network']['fetch']['finalHost']} bytes={row['observedSize']}",
            },
        )
        append_jsonl(
            commands_path,
            {
                "event": "sha256",
                "command": f"python -B build_sherpa_evidence.py sha256 {spec['filename']}",
                "exit": 0,
                "result": row["sha256"],
            },
        )
        artifacts.append(row)

    artifact_index = {
        "schema": "aurorama.g1-sherpa-artifact-index.v1",
        "expectedCommit": EXPECTED_COMMIT,
        "commitEvidence": "External task input; the GitHub source archive does not embed a Git commit and no additional GitHub URL was queried.",
        "runtimeVerificationStatus": "UNVERIFIED",
        "allowedHosts": ["github.com", "release-assets.githubusercontent.com", "codeload.github.com"],
        "redirectPolicy": "HEAD and GET: no automatic redirects; one official redirect validated for host, scheme, and same artifact path/filename.",
        "artifacts": [
            {
                key: value
                for key, value in artifact.items()
                if key != "temporaryPath"
            }
            for artifact in artifacts
        ],
        "temporaryBinariesInEvidence": False,
    }
    write_json(evidence / "artifact-index.json", artifact_index)

    aar_rows = []
    license_evidence: list[dict[str, Any]] = []
    elf_by_artifact: dict[str, dict[str, dict[str, Any]]] = {}
    for artifact in artifacts:
        if artifact["kind"] != "aar":
            continue
        row, extracted, elf_rows = inspect_aar(evidence, temp_root, artifact, jar, readelf)
        aar_rows.append(row)
        license_evidence.extend(extracted)
        elf_by_artifact[artifact["id"]] = elf_rows
        append_jsonl(
            commands_path,
            {
                "event": "aar_inventory",
                "command": f"jar tf <temporary>/{artifact['filename']}; Python zipfile metadata and entry hashing",
                "exit": 0,
                "result": f"entries={row['entryCount']} abis={len(row['abis'])}",
            },
        )
    write_json(
        evidence / "aar-inventory.json",
        {
            "schema": "aurorama.g1-sherpa-aar-inventory.v1",
            "artifacts": aar_rows,
            "comparison": {
                "entryCounts": {
                    row["id"]: row["entryCount"] for row in aar_rows
                },
                "nativeLibraryNames": {
                    row["id"]: sorted({Path(entry["path"]).name for entry in row["entries"] if entry["path"].endswith(".so")})
                    for row in aar_rows
                },
                "abiNativeLibraryCounts": {
                    row["id"]: {abi["abi"]: len(abi["soFiles"]) for abi in row["abis"]}
                    for row in aar_rows
                },
                "onnxRuntimeLayout": "standard: separate libonnxruntime.so in every ABI; static: separate only for x86, embedded/no DT_NEEDED on arm64-v8a, armeabi-v7a and x86_64",
            },
            "scope": "ZIP/JAR static inventory only; no Java or native execution",
        },
    )

    source_row = next(artifact for artifact in artifacts if artifact["kind"] == "source")
    source, source_licenses = source_inventory(evidence, source_row)
    license_evidence.extend(source_licenses)
    write_json(
        evidence / "source-inventory.json",
        {
            **source,
            "scope": "tar.gz member inventory, license/notice scan and selected short text extraction; no build",
        },
    )
    append_jsonl(
        commands_path,
        {
            "event": "source_inventory",
            "command": "python -B build_sherpa_evidence.py tar-inventory <temporary>/sherpa-onnx-2.0.1.tar.gz",
            "exit": 0,
            "result": f"members={source['totalMembers']} files={source['fileCount']} licenses={len(source['licenseFiles'])} notices={len(source['noticeFiles'])}",
        },
    )

    elf_abis = []
    for abi in sorted(ABI_NAMES):
        libraries = []
        for artifact_id, abi_rows in elf_by_artifact.items():
            for library in abi_rows.get(abi, []):
                libraries.append({"artifact": artifact_id, **library})
        statuses = {library["status"] for library in libraries}
        if "FAIL" in statuses:
            status = "FAIL"
        elif not libraries or "UNPROVEN" in statuses:
            status = "UNPROVEN"
        else:
            status = "PASS"
        elf_abis.append(
            {
                "abi": abi,
                "status": status,
                "staticRule": "every PT_LOAD must have p_align >= 0x4000 and congruent p_offset/p_vaddr",
                "libraries": libraries,
            }
        )
    elf_inventory = {
        "schema": "aurorama.g1-sherpa-elf-page-alignment.v1",
        "scope": "static-elf-program-header-only",
        "runtimeVerificationStatus": "UNVERIFIED",
        "method": {
            "pageSizeBytes": 16384,
            "primary": "Reproducible Python parser of ELF header and PT_LOAD program headers.",
            "llvmReadelf": "NOT_FOUND_IN_KNOWN_TOOLCHAIN_ROOTS_OR_PATH",
            "crossCheck": f"GNU readelf -lW via {readelf.name}" if readelf else "unavailable",
            "algorithm": [
                "read ELF32/ELF64 class and endianness",
                "enumerate e_phnum program headers at e_phoff",
                "retain PT_LOAD entries",
                "require p_align >= 16384 and (p_vaddr % p_align) == (p_offset % p_align)",
            ],
            "limitation": "A static PT_LOAD alignment PASS is not a device/runtime 16 KiB page-size PASS.",
        },
        "abis": elf_abis,
    }
    write_json(evidence / "elf-inventory.json", elf_inventory)
    append_jsonl(
        commands_path,
        {
            "event": "elf_alignment",
            "command": "python -B build_sherpa_evidence.py elf-program-headers <temporary AAR payloads>; readelf -lW cross-check",
            "exit": 0,
            "result": ",".join(f"{row['abi']}={row['status']}" for row in elf_abis),
        },
    )

    matrix = build_license_matrix(evidence, license_evidence, aar_rows, source_row)
    write_json(evidence / "license-source-matrix.json", matrix)
    generate_manifest(evidence)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--evidence-root", type=Path, required=True)
    parser.add_argument("--temp-dir", type=Path)
    parser.add_argument("--reuse-successful-retry", action="store_true")
    parser.add_argument("--record-event")
    parser.add_argument("--command", default="")
    parser.add_argument("--exit-code", type=int, default=0)
    parser.add_argument("--result", default="")
    args = parser.parse_args()
    evidence = args.evidence_root.resolve()
    if args.record_event:
        record_event(evidence, args.record_event, args.command, args.exit_code, args.result)
        return 0
    if args.temp_dir is None:
        parser.error("--temp-dir is required when building evidence")
    build(evidence, args.temp_dir.resolve(), reuse_successful_retry=args.reuse_successful_retry)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
