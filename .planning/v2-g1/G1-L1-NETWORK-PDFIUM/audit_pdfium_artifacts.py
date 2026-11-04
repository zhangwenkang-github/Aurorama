#!/usr/bin/env python3
"""Acquire and statically audit the approved io.legere PDFium 2.0.3 artifacts."""

from __future__ import annotations

import argparse
import hashlib
import io
import json
import os
import re
import struct
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
import zipfile
from collections import defaultdict
from datetime import datetime, timezone
from pathlib import Path, PurePosixPath
from typing import Any
from urllib.parse import urlsplit


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
EXPECTED_BRANCH = "codex/v2-integration"
OUTPUT_PREFIX = ".planning/v2-g1/G1-L1-NETWORK-PDFIUM/"
PT_LOAD = 1
PAGE_16KB = 16 * 1024
SHA256_RE = re.compile(r"^[0-9A-F]{64}$")
SHA256_TEXT_RE = re.compile(r"^([0-9a-fA-F]{64})(?:\s+\*?[^\r\n]+)?\s*$")
ELF_MACHINES = {
    3: "x86",
    40: "ARM",
    62: "x86_64",
    183: "AArch64",
}


class AuditStop(RuntimeError):
    pass


def stop(message: str) -> None:
    raise AuditStop(message)


def require(condition: bool, message: str) -> None:
    if not condition:
        stop(message)


def utc_now() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest().upper()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest().upper()


def write_text(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8", newline="\n")


def write_json(path: Path, value: Any) -> None:
    write_text(path, json.dumps(value, indent=2, sort_keys=True, ensure_ascii=False) + "\n")


def run_process(args: list[str], cwd: Path | None = None) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        args,
        cwd=cwd,
        text=True,
        encoding="utf-8",
        errors="strict",
        capture_output=True,
        check=False,
    )


def approved_url(url: str) -> None:
    parsed = urlsplit(url)
    require(parsed.scheme == "https", f"non-HTTPS URL: {url}")
    require(parsed.hostname == ALLOWED_HOST, f"unapproved URL host: {parsed.hostname}")
    require(
        url.startswith(f"https://{ALLOWED_HOST}/maven2/io/legere/"),
        f"URL outside approved Maven group: {url}",
    )


def artifact_url(module: str, kind: str) -> str:
    suffix = {"AAR": ".aar", "SOURCES_JAR": "-sources.jar", "POM": ".pom"}[kind]
    return f"https://{ALLOWED_HOST}/maven2/io/legere/{module}/{VERSION}/{module}-{VERSION}{suffix}"


def checksum_url(module: str, kind: str) -> str:
    return artifact_url(module, kind) + ".sha256"


def artifact_key(module: str, kind: str) -> str:
    return f"{module}:{kind}"


def checksum_key(module: str, kind: str) -> str:
    return f"{module}:{kind}:sha256"


def parse_git_status_ignoring_output(raw_status: str, output_prefix: str) -> list[str]:
    retained = []
    for line in raw_status.splitlines():
        if not line.strip():
            continue
        path = line[3:]
        if " -> " in path:
            path = path.split(" -> ", 1)[1]
        normalized = path.replace("\\", "/").strip('"')
        if normalized.startswith(output_prefix):
            continue
        retained.append(line)
    return retained


def preflight(repo: Path, output: Path) -> dict[str, Any]:
    require(repo.is_dir(), "repository root missing")
    resolved_repo = repo.resolve()
    resolved_output = output.resolve()
    require(resolved_output.is_relative_to(resolved_repo), "output escapes repository")
    relative_output = resolved_output.relative_to(resolved_repo).as_posix() + "/"
    require(relative_output.startswith(OUTPUT_PREFIX), "output outside write whitelist")
    head = run_process(["git", "-C", str(repo), "rev-parse", "HEAD"])
    require(head.returncode == 0, f"git rev-parse failed: {head.stderr.strip()}")
    branch = run_process(["git", "-C", str(repo), "branch", "--show-current"])
    require(branch.returncode == 0, f"git branch failed: {branch.stderr.strip()}")
    status = run_process(
        ["git", "-C", str(repo), "status", "--porcelain=v1", "--untracked-files=all"]
    )
    require(status.returncode == 0, f"git status failed: {status.stderr.strip()}")
    require(head.stdout.strip() == EXPECTED_HEAD, f"HEAD drift: {head.stdout.strip()}")
    require(branch.stdout.strip() == EXPECTED_BRANCH, f"branch drift: {branch.stdout.strip()}")
    retained = parse_git_status_ignoring_output(status.stdout, relative_output.rstrip("/"))
    # Initial clean was checked by the parent before this directory existed. During the
    # same round, another allowlisted artifact line may create sibling untracked files.
    tracked_drift = [line for line in retained if not line.startswith("?? ")]
    require(not tracked_drift, f"tracked drift outside whitelist: {'; '.join(tracked_drift)}")
    return {
        "head": EXPECTED_HEAD,
        "branch": EXPECTED_BRANCH,
        "initial_status_porcelain": "",
    }


def parse_headers(raw: bytes) -> dict[str, Any]:
    text = raw.decode("iso-8859-1", errors="replace")
    blocks = re.split(r"\r?\n\r?\n", text.strip())
    statuses = []
    content_lengths = []
    locations = []
    for block in blocks:
        headers: dict[str, list[str]] = defaultdict(list)
        for line in block.splitlines():
            if line.startswith("HTTP/"):
                match = re.match(r"HTTP/\S+\s+(\d{3})", line)
                if match:
                    statuses.append(int(match.group(1)))
                continue
            if ":" in line:
                name, value = line.split(":", 1)
                headers[name.strip().lower()].append(value.strip())
        for value in headers.get("content-length", []):
            try:
                content_lengths.append(int(value))
            except ValueError:
                pass
        locations.extend(headers.get("location", []))
    return {
        "header_statuses": statuses,
        "content_length": content_lengths[-1] if content_lengths else None,
        "locations": locations,
    }


def curl_get(key: str, url: str, temp_root: Path) -> tuple[dict[str, Any], bytes, str]:
    approved_url(url)
    safe_key = re.sub(r"[^A-Za-z0-9_.-]+", "_", key)
    header_path = temp_root / f"{safe_key}.headers"
    body_path = temp_root / f"{safe_key}.body"
    write_out = (
        "%{http_code}\t%{size_download}\t%{url_effective}\t%{num_redirects}\t"
        "%{redirect_url}\t%{content_type}"
    )
    args = [
        "curl.exe",
        "--silent",
        "--show-error",
        "--location",
        "--max-redirs",
        "0",
        "--proto",
        "=https",
        "--proto-redir",
        "=https",
        "--noproxy",
        "*",
        "--connect-timeout",
        "15",
        "--max-time",
        "90",
        "--header",
        "Accept-Encoding: identity",
        "--dump-header",
        str(header_path),
        "--output",
        str(body_path),
        "--write-out",
        write_out,
        url,
    ]
    command = (
        "curl.exe --silent --show-error --location --max-redirs 0 --proto =https "
        "--proto-redir =https --noproxy \"*\" --connect-timeout 15 --max-time 90 "
        "--header \"Accept-Encoding: identity\" "
        f"--dump-header \"<TEMP>/{safe_key}.headers\" --output \"<TEMP>/{safe_key}.body\" "
        f"--write-out \"{write_out}\" \"{url}\""
    )
    completed = run_process(args)
    headers = parse_headers(header_path.read_bytes() if header_path.exists() else b"")
    body = body_path.read_bytes() if body_path.exists() else b""
    fields = completed.stdout.rstrip("\r\n").split("\t")
    if len(fields) < 6:
        stop(f"curl metadata parse failed for {url}: rc={completed.returncode}, stdout={completed.stdout!r}")
    try:
        http_status = int(fields[0])
        download_bytes = int(fields[1])
        redirect_count = int(fields[3])
    except ValueError:
        stop(f"curl numeric metadata parse failed for {url}: {fields!r}")
    final_url = fields[2]
    redirect_url = fields[4]
    content_type = fields[5] or None
    redirect_hops = [
        {"status": status, "location": location}
        for status, location in zip(headers["header_statuses"][:-1], headers["locations"])
    ]
    if headers["locations"] and not redirect_hops:
        redirect_hops = [
            {"status": headers["header_statuses"][-1] if headers["header_statuses"] else None, "location": location}
            for location in headers["locations"]
        ]
    if completed.returncode != 0:
        stop(
            f"curl failed for {url}: rc={completed.returncode}, status={http_status}, "
            f"redirect={redirect_url or headers['locations']}, stderr={completed.stderr.strip()}"
        )
    approved_url(final_url)
    if redirect_count != 0 or redirect_hops or redirect_url:
        stop(
            f"redirect encountered (not followed) for {url}: "
            f"count={redirect_count}, target={redirect_url or headers['locations']}"
        )
    if download_bytes != len(body):
        stop(f"downloaded byte count mismatch for {url}: curl={download_bytes}, file={len(body)}")
    record = {
        "url": url,
        "http": {
            "method": "GET",
            "curl_exit_code": completed.returncode,
            "status": http_status,
            "header_statuses": headers["header_statuses"],
            "content_length_header": headers["content_length"],
            "download_bytes": download_bytes,
            "final_url": final_url,
            "redirect_count": redirect_count,
            "redirect_hops": redirect_hops,
            "content_type": content_type,
        },
        "size": len(body) if http_status == 200 else 0,
        "sha256": sha256_bytes(body) if http_status == 200 else None,
    }
    command_record = {
        "schema_id": "aurorama.g1-l1-network-pdfium.command/v1",
        "phase": "NETWORK_GET",
        "command": command,
        "exit_code": completed.returncode,
        "http_status": http_status,
        "download_bytes": download_bytes,
        "final_url": final_url,
        "redirect_followed": False,
    }
    return record, body, json.dumps(command_record, sort_keys=True, ensure_ascii=False)


def parse_published_sha(body: bytes, url: str) -> str:
    try:
        text = body.decode("ascii")
    except UnicodeDecodeError:
        stop(f"checksum file is not ASCII: {url}")
    match = SHA256_TEXT_RE.match(text)
    require(match is not None, f"malformed .sha256 response: {url}")
    value = match.group(1).upper()
    require(SHA256_RE.fullmatch(value) is not None, f"invalid checksum: {url}")
    return value


def download_artifact(
    module: str,
    kind: str,
    temp_root: Path,
) -> tuple[dict[str, Any], dict[str, Any], Path, bytes, list[str]]:
    key = artifact_key(module, kind)
    url = artifact_url(module, kind)
    record, body, command_line = curl_get(key, url, temp_root)
    require(
        record["http"]["status"] == 200,
        f"artifact GET returned HTTP {record['http']['status']}: {url}",
    )
    require(record["size"] > 0, f"zero-byte artifact: {url}")
    safe_key = re.sub(r"[^A-Za-z0-9_.-]+", "_", key)
    retained_path = temp_root / f"{safe_key}.body"
    if kind == "AAR":
        require(
            record["sha256"] == EXPECTED_AAR_SHA256[module],
            f"AAR SHA-256 mismatch: {key}: {record['sha256']}",
        )
        require(
            record["size"] == EXPECTED_AAR_SIZE[module],
            f"AAR initial size mismatch: {key}: {record['size']}",
        )

    checksum_record, checksum_body, checksum_command = curl_get(
        checksum_key(module, kind), checksum_url(module, kind), temp_root
    )
    checksum_http_status = checksum_record["http"]["status"]
    require(checksum_http_status in {200, 404}, f"unexpected checksum HTTP status: {checksum_url(module, kind)}")
    checksum = {
        "key": checksum_key(module, kind),
        "for_module": module,
        "for_kind": kind,
        "url": checksum_url(module, kind),
        "http": checksum_record["http"],
        "size": checksum_record["size"],
        "sha256": checksum_record["sha256"],
        "published_sha256": None,
        "checksum_status": "MISSING_REMOTE_404" if checksum_http_status == 404 else None,
    }
    if checksum_http_status == 200:
        published = parse_published_sha(checksum_body, checksum_url(module, kind))
        require(published == record["sha256"], f"published SHA-256 mismatch: {key}")
        checksum["published_sha256"] = published
        checksum["checksum_status"] = "MATCH"
    artifact = {
        "key": key,
        "module": module,
        "kind": kind,
        "coordinate": f"io.legere:{module}:{VERSION}",
        "url": url,
        "http": record["http"],
        "size": record["size"],
        "sha256": record["sha256"],
        "expected_sha256": EXPECTED_AAR_SHA256[module] if kind == "AAR" else None,
        "expected_size": EXPECTED_AAR_SIZE[module] if kind == "AAR" else None,
        "checksum_key": checksum["key"],
    }
    return artifact, checksum, retained_path, body, [command_line, checksum_command]


def safe_zip_names(zf: zipfile.ZipFile) -> tuple[list[str], int]:
    names = zf.namelist()
    for name in names:
        path = PurePosixPath(name)
        require(not path.is_absolute() and ".." not in path.parts, f"unsafe ZIP path: {name}")
    return names, len(names) - len(set(names))


def entry_detail(data: bytes) -> dict[str, Any]:
    return {"present": True, "size": len(data), "sha256": sha256_bytes(data)}


def license_like(path: str) -> bool:
    name = path.rsplit("/", 1)[-1].upper()
    return (
        name in {"LICENSE", "LICENSE.TXT", "LICENCE", "NOTICE", "NOTICE.TXT", "COPYING"}
        or name.startswith(("LICENSE-", "NOTICE-", "COPYING-"))
    )


def license_signals(texts: list[str]) -> dict[str, bool]:
    combined = "\n".join(texts).lower()
    return {
        "apache_2_0": "apache license" in combined and "version 2.0" in combined,
        "bsd_3_clause": (
            "redistribution and use in source and binary forms" in combined
            and "neither the name" in combined
            and ("bsd" in combined or "3-clause" in combined)
        ),
        "written_offer": "written offer" in combined or "source offer" in combined,
        "third_party": "third-party" in combined or "third party" in combined,
    }


def decode_license(data: bytes) -> str:
    require(b"\x00" not in data, "license/notice entry contains NUL bytes")
    return data.decode("utf-8", errors="replace")


def extract_license_text(
    output: Path,
    module: str,
    scope: str,
    entry_path: str,
    data: bytes,
    extracted: list[dict[str, Any]],
) -> str | None:
    if len(data) > 65_536:
        return None
    text = decode_license(data)
    basename = re.sub(r"[^A-Za-z0-9._-]+", "_", PurePosixPath(entry_path).name)
    relative = Path("licenses") / module / scope / f"{sha256_bytes(data)[:12]}-{basename}.txt"
    write_text(output / relative, text)
    extracted.append(
        {
            "source_entry": entry_path,
            "scope": scope,
            "evidence_path": relative.as_posix(),
            "size": len(data),
            "sha256": sha256_bytes(data),
        }
    )
    return text


def parse_elf(data: bytes, path: str, abi: str) -> dict[str, Any]:
    base = {
        "path": path,
        "abi": abi,
        "size": len(data),
        "sha256": sha256_bytes(data),
        "alignment_status": "UNPROVEN",
        "parse": {"status": "ERROR", "detail": None},
        "load_segments": [],
    }
    if len(data) < 52 or data[:4] != b"\x7fELF":
        base["parse"]["detail"] = "not an ELF file"
        return base
    elf_class = data[4]
    data_encoding = data[5]
    if elf_class not in {1, 2} or data_encoding not in {1, 2}:
        base["parse"]["detail"] = "unsupported ELF class or encoding"
        return base
    endian = "<" if data_encoding == 1 else ">"
    try:
        machine = struct.unpack_from(endian + "H", data, 18)[0]
        if elf_class == 2:
            phoff = struct.unpack_from(endian + "Q", data, 32)[0]
            phentsize = struct.unpack_from(endian + "H", data, 54)[0]
            phnum = struct.unpack_from(endian + "H", data, 56)[0]
            expected_phentsize = 56
            ph_format = endian + "IIQQQQQQ"
        else:
            require(len(data) >= 42, f"truncated ELF32 header: {path}")
            phoff = struct.unpack_from(endian + "I", data, 28)[0]
            phentsize = struct.unpack_from(endian + "H", data, 42)[0]
            phnum = struct.unpack_from(endian + "H", data, 44)[0]
            expected_phentsize = 32
            ph_format = endian + "IIIIIIII"
        if phnum == 0xFFFF:
            base["parse"]["detail"] = "PN_XNUM requires section-header resolution"
            return base
        require(phentsize == expected_phentsize, f"unexpected program-header size: {path}")
        require(phoff >= 0 and phoff + phnum * phentsize <= len(data), f"program headers exceed file: {path}")
        segments = []
        for index in range(phnum):
            values = struct.unpack_from(ph_format, data, phoff + index * phentsize)
            p_type = values[0]
            if p_type != PT_LOAD:
                continue
            if elf_class == 2:
                p_offset, p_vaddr, _p_paddr, p_filesz, p_memsz, p_align = values[2:]
            else:
                (
                    _p_type,
                    p_offset,
                    p_vaddr,
                    _p_paddr,
                    p_filesz,
                    p_memsz,
                    _p_flags,
                    p_align,
                ) = values
            alignment_ok = p_align >= PAGE_16KB
            congruent_ok = p_align != 0 and (p_offset % p_align) == (p_vaddr % p_align)
            segments.append(
                {
                    "p_offset": p_offset,
                    "p_vaddr": p_vaddr,
                    "p_filesz": p_filesz,
                    "p_memsz": p_memsz,
                    "p_align": p_align,
                    "align_16kb_ok": alignment_ok,
                    "offset_vaddr_congruent_ok": congruent_ok,
                }
            )
        if not segments:
            base["parse"] = {"status": "OK", "class": "ELF64" if elf_class == 2 else "ELF32"}
            base["parse"]["detail"] = "no PT_LOAD segments"
            return base
        alignment_status = "PASS"
        for segment in segments:
            if not segment["align_16kb_ok"] or not segment["offset_vaddr_congruent_ok"]:
                alignment_status = "FAIL"
        base["parse"] = {
            "status": "OK",
            "class": "ELF64" if elf_class == 2 else "ELF32",
            "endianness": "little" if data_encoding == 1 else "big",
            "machine": ELF_MACHINES.get(machine, f"EM_{machine}"),
            "program_header_count": phnum,
            "program_header_entry_size": phentsize,
        }
        base["load_segments"] = segments
        base["alignment_status"] = alignment_status
    except (struct.error, ValueError) as exc:
        base["parse"] = {"status": "ERROR", "detail": str(exc)}
        base["load_segments"] = []
        base["alignment_status"] = "UNPROVEN"
    return base


def printable_markers(data: bytes) -> list[str]:
    strings = re.findall(rb"[\x20-\x7e]{6,240}", data)
    selected = []
    for value in strings:
        text = value.decode("ascii", errors="strict")
        if re.search(r"(?i)pdfium|chromium|revision|commit|git", text):
            cleaned = text.strip()
            if cleaned and cleaned not in selected:
                selected.append(cleaned)
        if len(selected) >= 24:
            break
    return selected


def inspect_aar(
    module: str,
    path: Path,
    output: Path,
    extracted_licenses: list[dict[str, Any]],
) -> dict[str, Any]:
    raw = path.read_bytes()
    with zipfile.ZipFile(io.BytesIO(raw), "r") as zf:
        names, duplicate_count = safe_zip_names(zf)
        corrupt = zf.testzip()
        require(corrupt is None, f"AAR corruption at {corrupt}: {module}")
        manifest_data = zf.read("AndroidManifest.xml") if "AndroidManifest.xml" in names else None
        require(manifest_data is not None, f"AndroidManifest.xml missing: {module}")
        classes_data = zf.read("classes.jar") if "classes.jar" in names else None
        require(classes_data is not None, f"classes.jar missing: {module}")
        native_entries = []
        elf_records = []
        license_entries = []
        direct_license_texts = []
        for name in sorted(names):
            if name.endswith("/"):
                continue
            data = zf.read(name)
            if name.startswith("jni/") and name.endswith(".so"):
                parts = PurePosixPath(name).parts
                require(len(parts) >= 3 and parts[0] == "jni", f"invalid native path: {name}")
                abi = parts[1]
                entry = {
                    "path": name,
                    "abi": abi,
                    "size": len(data),
                    "sha256": sha256_bytes(data),
                }
                native_entries.append(entry)
                elf_record = parse_elf(data, name, abi)
                elf_record["source_module"] = module
                elf_record["revision_markers"] = printable_markers(data)
                elf_records.append(elf_record)
            if name.startswith("META-INF/") and license_like(name):
                entry = {
                    "path": name,
                    "size": len(data),
                    "sha256": sha256_bytes(data),
                    "text_extraction": None,
                }
                text = extract_license_text(
                    output, module, "aar", name, data, extracted_licenses
                )
                if text is not None:
                    entry["text_extraction"] = extracted_licenses[-1]["evidence_path"]
                    direct_license_texts.append(text)
                license_entries.append(entry)

        class_names = []
        class_license_entries = []
        class_license_texts = []
        with zipfile.ZipFile(io.BytesIO(classes_data), "r") as class_zip:
            class_names_raw, class_duplicate_count = safe_zip_names(class_zip)
            require(class_zip.testzip() is None, f"nested classes.jar corrupt: {module}")
            for name in class_names_raw:
                if name.endswith(".class"):
                    class_names.append(name)
                if name.startswith("META-INF/") and license_like(name):
                    data = class_zip.read(name)
                    entry = {
                        "path": f"classes.jar!/{name}",
                        "size": len(data),
                        "sha256": sha256_bytes(data),
                        "text_extraction": None,
                    }
                    text = extract_license_text(
                        output, module, "classes", name, data, extracted_licenses
                    )
                    if text is not None:
                        entry["text_extraction"] = extracted_licenses[-1]["evidence_path"]
                        class_license_texts.append(text)
                    class_license_entries.append(entry)
    all_license_texts = direct_license_texts + class_license_texts
    return {
        "inventory": {
            "entry_count": len(names),
            "zip_test": "OK",
            "duplicate_entry_count": duplicate_count,
            "AndroidManifest.xml": entry_detail(manifest_data),
            "classes.jar": {
                **entry_detail(classes_data),
                "class_entry_count": len(class_names),
                "duplicate_entry_count": class_duplicate_count,
            },
            "native_entries": native_entries,
            "license_notice_entries": license_entries,
            "classes_jar_license_notice_entries": class_license_entries,
        },
        "elf_records": elf_records,
        "class_names": class_names,
        "license_signals": license_signals(all_license_texts),
    }


def normalized_xml_tag(tag: str) -> str:
    return tag.rsplit("}", 1)[-1]


def child_text(node: ET.Element, tag: str) -> str | None:
    for child in node:
        if normalized_xml_tag(child.tag) == tag and child.text:
            return child.text.strip()
    return None


def inspect_pom(module: str, path: Path) -> dict[str, Any]:
    try:
        tree = ET.parse(path)
    except ET.ParseError as exc:
        stop(f"POM parse failed ({module}): {exc}")
    root = tree.getroot()
    project: dict[str, Any] = {}
    for child in root:
        tag = normalized_xml_tag(child.tag)
        if tag in {"modelVersion", "groupId", "artifactId", "version", "packaging", "name", "description", "url"}:
            project[tag] = (child.text or "").strip()
    licenses = []
    for node in root.iter():
        if normalized_xml_tag(node.tag) != "license":
            continue
        licenses.append(
            {
                "name": child_text(node, "name"),
                "url": child_text(node, "url"),
                "distribution": child_text(node, "distribution"),
            }
        )
    dependencies = []
    for node in root.iter():
        if normalized_xml_tag(node.tag) != "dependency":
            continue
        dependency = {
            key: child_text(node, key)
            for key in ("groupId", "artifactId", "version", "scope", "type", "optional")
        }
        if dependency.get("groupId") or dependency.get("artifactId"):
            dependencies.append(dependency)
    scm = None
    for node in root:
        if normalized_xml_tag(node.tag) == "scm":
            scm = {
                key: child_text(node, key)
                for key in ("connection", "developerConnection", "tag", "url")
            }
            break
    return {
        "project": project,
        "licenses": licenses,
        "dependencies": dependencies,
        "scm": scm,
    }


def inspect_sources(
    module: str,
    path: Path,
    output: Path,
    extracted_licenses: list[dict[str, Any]],
) -> dict[str, Any]:
    raw = path.read_bytes()
    with zipfile.ZipFile(io.BytesIO(raw), "r") as zf:
        names, duplicate_count = safe_zip_names(zf)
        require(zf.testzip() is None, f"sources.jar corrupt: {module}")
        java_sources = [name for name in names if name.endswith(".java")]
        kotlin_sources = [name for name in names if name.endswith(".kt")]
        native_sources = [
            name
            for name in names
            if PurePosixPath(name).suffix.lower() in {".c", ".cc", ".cpp", ".cxx", ".h", ".hh", ".hpp", ".m", ".mm"}
        ]
        deps_entries = [name for name in names if PurePosixPath(name).name == "DEPS"]
        gn_entries = [
            name
            for name in names
            if PurePosixPath(name).name in {"BUILD.gn", "BUILD.gn.expected", ".gn"}
            or PurePosixPath(name).suffix == ".gn"
        ]
        license_entries = []
        license_texts = []
        revision_texts = []
        for name in names:
            if name.endswith("/"):
                continue
            normalized = name.lower()
            if (
                license_like(name)
                or name.endswith("META-INF/MANIFEST.MF")
                or name.endswith("pom.properties")
            ):
                data = zf.read(name)
                if license_like(name):
                    entry = {
                        "path": name,
                        "size": len(data),
                        "sha256": sha256_bytes(data),
                        "text_extraction": None,
                    }
                    text = extract_license_text(
                        output, module, "sources", name, data, extracted_licenses
                    )
                    if text is not None:
                        entry["text_extraction"] = extracted_licenses[-1]["evidence_path"]
                        license_texts.append(text)
                    license_entries.append(entry)
                if len(data) <= 65_536 and (
                    name.endswith("META-INF/MANIFEST.MF") or name.endswith("pom.properties")
                ):
                    revision_texts.append(data.decode("utf-8", errors="replace"))
            if "pdfium" in normalized and ("revision" in normalized or "version" in normalized):
                data = zf.read(name)
                if len(data) <= 65_536:
                    revision_texts.append(data.decode("utf-8", errors="replace"))
        return {
            "entry_count": len(names),
            "duplicate_entry_count": duplicate_count,
            "java_source_count": len(java_sources),
            "kotlin_source_count": len(kotlin_sources),
            "native_source_count": len(native_sources),
            "native_source_examples": native_sources[:30],
            "deps_entries": deps_entries,
            "gn_entries": gn_entries,
            "license_notice_entries": license_entries,
            "license_signals": license_signals(license_texts),
            "revision_text_excerpts": [
                line.strip()[:240]
                for text in revision_texts
                for line in text.splitlines()
                if re.search(r"(?i)version|revision|commit|scm|pdfium", line)
            ][:30],
        }


def derive_abi_summary(files: list[dict[str, Any]]) -> list[dict[str, Any]]:
    grouped: dict[str, list[str]] = defaultdict(list)
    for record in files:
        grouped[record["abi"]].append(record["alignment_status"])
    summary = []
    for abi in sorted(grouped):
        statuses = grouped[abi]
        status = "FAIL" if "FAIL" in statuses else "UNPROVEN" if "UNPROVEN" in statuses else "PASS"
        summary.append(
            {
                "abi": abi,
                "status": status,
                "elf_file_count": len(statuses),
            }
        )
    return summary


def build_module_relations(
    pom_by_module: dict[str, dict[str, Any]],
    class_names_by_module: dict[str, set[str]],
    all_elf_files: list[dict[str, Any]],
) -> dict[str, Any]:
    dependencies = {}
    for module, pom in pom_by_module.items():
        dependencies[module] = pom["dependencies"]
    class_relations = []
    for left_index, left in enumerate(MODULES):
        for right in MODULES[left_index + 1 :]:
            overlap = sorted(class_names_by_module[left] & class_names_by_module[right])
            class_relations.append(
                {
                    "left": left,
                    "right": right,
                    "overlap_count": len(overlap),
                    "overlap_examples": overlap[:20],
                }
            )
    native_groups: dict[tuple[str, str], list[str]] = defaultdict(list)
    for record in all_elf_files:
        native_groups[(record["abi"], record["sha256"])].append(
            f"{record['source_module']}:{record['path']}"
        )
    native_identity = [
        {
            "abi": abi,
            "sha256": digest,
            "entries": entries,
            "duplicate_across_modules": len({entry.split(":", 1)[0] for entry in entries}) > 1,
        }
        for (abi, digest), entries in sorted(native_groups.items())
    ]
    return {
        "pom_dependencies": dependencies,
        "class_identity": class_relations,
        "native_identity": native_identity,
    }


def build_license_source_matrix(
    source_baseline: dict[str, Any],
    pom_by_module: dict[str, dict[str, Any]],
    aar_by_module: dict[str, dict[str, Any]],
    sources_by_module: dict[str, dict[str, Any]],
) -> dict[str, Any]:
    native_source_present = any(row["native_source_count"] > 0 for row in sources_by_module.values())
    deps_present = all(row["deps_entries"] for row in sources_by_module.values())
    gn_present = all(row["gn_entries"] for row in sources_by_module.values())
    combined_signals = {
        key: any(
            aar_by_module[module]["license_signals"].get(key, False)
            or sources_by_module[module]["license_signals"].get(key, False)
            for module in MODULES
        )
        for key in ("apache_2_0", "bsd_3_clause", "written_offer", "third_party")
    }
    source_status = (
        "COMPLETE"
        if native_source_present and deps_present and gn_present
        else "SOURCE_INCOMPLETE"
    )
    module_rows = []
    for module in MODULES:
        pom = pom_by_module[module]
        aar = aar_by_module[module]
        sources = sources_by_module[module]
        module_rows.append(
            {
                "module": module,
                "pom_licenses": pom["licenses"],
                "aar_license_notice_entry_count": len(aar["inventory"]["license_notice_entries"]),
                "classes_jar_license_notice_entry_count": len(
                    aar["inventory"]["classes_jar_license_notice_entries"]
                ),
                "sources_license_notice_entry_count": len(sources["license_notice_entries"]),
                "java_source_count": sources["java_source_count"],
                "native_source_count": sources["native_source_count"],
                "deps_present": bool(sources["deps_entries"]),
                "gn_present": bool(sources["gn_entries"]),
                "source_status": (
                    "COMPLETE"
                    if sources["native_source_count"] > 0
                    and sources["deps_entries"]
                    and sources["gn_entries"]
                    else "SOURCE_INCOMPLETE"
                ),
            }
        )
    obligations = [
        {
            "id": "POM_BINDING_LICENSE",
            "status": "SATISFIED"
            if all(pom_by_module[module]["licenses"] for module in MODULES)
            else "MISSING",
            "evidence": ["artifact-index.json#/poms", "license-source-matrix.json#/modules"],
            "gap": None
            if all(pom_by_module[module]["licenses"] for module in MODULES)
            else "At least one POM has no declared license.",
        },
        {
            "id": "JAVA_SOURCES_INCLUDED",
            "status": "SATISFIED"
            if all(
                sources_by_module[module]["java_source_count"]
                + sources_by_module[module]["kotlin_source_count"]
                > 0
                for module in MODULES
            )
            else "MISSING",
            "evidence": ["artifact-index.json#/sources_jars"],
            "gap": None
            if all(
                sources_by_module[module]["java_source_count"]
                + sources_by_module[module]["kotlin_source_count"]
                > 0
                for module in MODULES
            )
            else "At least one sources.jar has no Java/Kotlin source.",
        },
        {
            "id": "BUNDLED_LICENSE_TEXT",
            "status": "SATISFIED"
            if any(
                aar_by_module[module]["inventory"]["license_notice_entries"]
                or aar_by_module[module]["inventory"]["classes_jar_license_notice_entries"]
                for module in MODULES
            )
            else "MISSING",
            "evidence": ["licenses/", "artifact-index.json#/aar_inventory"],
            "gap": None
            if any(
                aar_by_module[module]["inventory"]["license_notice_entries"]
                or aar_by_module[module]["inventory"]["classes_jar_license_notice_entries"]
                for module in MODULES
            )
            else "No short META-INF license/notice text found in AAR or nested classes.jar.",
        },
        {
            "id": "PDFIUM_BSD_NOTICE",
            "status": "SATISFIED" if combined_signals["bsd_3_clause"] else "MISSING",
            "evidence": ["licenses/", "license-source-matrix.json#/notice_signals"],
            "gap": None
            if combined_signals["bsd_3_clause"]
            else "No complete BSD-3-Clause PDFium notice was found in inspected artifact text.",
        },
        {
            "id": "THIRD_PARTY_NOTICES",
            "status": "PARTIAL" if combined_signals["third_party"] else "MISSING",
            "evidence": ["licenses/", "license-source-matrix.json#/notice_signals"],
            "gap": "Artifact text cannot establish a complete PDFium/Chromium third-party notice bundle."
            if combined_signals["third_party"]
            else "No third-party notice bundle was identified.",
        },
        {
            "id": "NATIVE_CORRESPONDING_SOURCE",
            "status": "SATISFIED" if native_source_present else "MISSING",
            "evidence": ["artifact-index.json#/sources_jars"],
            "gap": None
            if native_source_present
            else "sources.jar files contain no PDFium C/C++ native source.",
        },
        {
            "id": "DEPS_AND_GN_BUILD_METADATA",
            "status": "SATISFIED" if deps_present and gn_present else "MISSING",
            "evidence": ["artifact-index.json#/sources_jars"],
            "gap": None
            if deps_present and gn_present
            else "DEPS and GN build metadata are not present for all modules.",
        },
        {
            "id": "EXACT_NATIVE_SOURCE_REVISION",
            "status": "PARTIAL",
            "evidence": ["artifact-index.json#/poms", "elf-inventory.json#/revision_markers"],
            "gap": "Maven artifact version 2.0.3 is proven; the PDFium upstream source revision is not proven by artifact metadata.",
        },
        {
            "id": "CORRESPONDING_SOURCE_OFFER",
            "status": "SATISFIED" if combined_signals["written_offer"] else "MISSING",
            "evidence": ["licenses/", "license-source-matrix.json#/notice_signals"],
            "gap": None
            if combined_signals["written_offer"]
            else "No written source offer or corresponding-source bundle was found in the approved artifacts.",
        },
        {
            "id": "VERSION_TRACEABILITY",
            "status": "PARTIAL",
            "evidence": ["artifact-index.json#/poms", "elf-inventory.json#/files"],
            "gap": "Binding Maven version and native hashes are pinned, but they do not identify an exact PDFium source commit.",
        },
    ]
    return {
        "schema_id": "aurorama.g1-l1-network-pdfium.license-source-matrix/v1",
        "generated_at_utc": utc_now(),
        "source_baseline": source_baseline,
        "revision": {
            "maven_artifact_version": VERSION,
            "maven_version_status": "PROVEN",
            "binding_scm": {
                module: pom_by_module[module]["scm"] for module in MODULES
            },
            "native_source_revision": "UNPROVEN",
            "native_source_revision_value": None,
            "native_revision_evidence_file": None,
            "diagnosis": "The Maven version and native file hashes are reproducible, but no artifact text proves the exact upstream PDFium commit.",
        },
        "source_audit": {
            "status": source_status,
            "native_source_present": native_source_present,
            "deps_present": deps_present,
            "gn_present": gn_present,
            "missing": [
                *([] if native_source_present else ["PDFium C/C++ native source"]),
                *([] if deps_present else ["DEPS"]),
                *([] if gn_present else ["GN build files"]),
            ],
        },
        "notice_signals": combined_signals,
        "modules": module_rows,
        "obligations": obligations,
        "overall": {
            "binding_license": "POM declares licenses; PDFium native text requires a separate notice/source conclusion.",
            "source_obligation": "SOURCE_INCOMPLETE" if source_status != "COMPLETE" else "SOURCE_INCLUDED",
            "notice_obligation": "PARTIAL" if combined_signals["third_party"] else "MISSING",
            "q03_closed": False,
            "g1_passed": False,
        },
    }


def build_readme(
    source_baseline: dict[str, Any],
    artifacts: list[dict[str, Any]],
    checksums: list[dict[str, Any]],
    aar_inventory: dict[str, Any],
    abi_summary: list[dict[str, Any]],
    license_matrix: dict[str, Any],
) -> str:
    artifact_lines = []
    for module in MODULES:
        artifact = next(row for row in artifacts if row["key"] == artifact_key(module, "AAR"))
        aar = aar_inventory[module]
        artifact_lines.append(
            f"| `{module}` | `{artifact['sha256']}` | {artifact['size']:,} | "
            f"{len(aar['native_entries'])} | {len(aar['license_notice_entries'])} |"
        )
    abi_lines = [
        f"| `{row['abi']}` | `{row['status']}` | {row['elf_file_count']} |"
        for row in abi_summary
    ] or ["| `none` | `UNPROVEN` | 0 |"]
    checksum_200 = sum(1 for row in checksums if row["http"]["status"] == 200)
    checksum_404 = sum(1 for row in checksums if row["http"]["status"] == 404)
    return f"""# Aurorama G1-L1 PDFium 2.0.3 artifact 静态审计

状态：`STATIC_AUDIT_COMPLETE / Q03_OPEN / G1_NOT_PASSED / RUNTIME_UNVERIFIED`

本目录只记录用户批准的 `repo1.maven.org` 上 `io.legere` 三个 2.0.3 artifact 的静态证据。
二进制 AAR、sources 与 POM 只落在系统临时目录，审计后删除；证据树不含 AAR/JAR/POM/SO。

## 基线、边界与网络

- 事实 worktree 分支 `{source_baseline['branch']}`，HEAD `{source_baseline['head']}`，开工 clean。
- 仅 HTTPS、`--max-redirs 0`、`--noproxy "*"`、精确 host `{ALLOWED_HOST}`；没有跟随 redirect。
- 仅 GET 以下坐标：`io.legere:pdfiumandroid:2.0.3`、`io.legere:pdfiumandroid-core:2.0.3`、
  `io.legere:pdfiumandroid-api:2.0.3` 的 AAR、sources.jar、POM 及对应 `.sha256`。
- 未运行 Gradle、ADB、设备、账号、服务器、PDF runtime、Range、独立 QA 或 Release。

## Artifact 与 AAR 内容

| Module | AAR SHA-256 | Bytes | Native 文件 | META-INF license/notice |
|---|---|---:|---:|---:|
{chr(10).join(artifact_lines)}

- 9 个主 artifact 均 HTTP 200；9 个远端 `.sha256` 中 {checksum_200} 个可用，
  {checksum_404} 个为 HTTP 404 / `MISSING_REMOTE_404`，未猜值。
- 每个 AAR 均检查 `AndroidManifest.xml`、`classes.jar`、JNI `.so` 与直接
  `META-INF` license/notice 路径；文件哈希只记录在 JSON inventory。
- aggregate 与 core/api 的 POM 依赖、classes.jar 重叠和 native SHA 跨模块关系记录在
  `artifact-index.json` 的 `module_relations`。

## ELF 静态 16 KB 对齐

| ABI | 静态 LOAD 对齐 | ELF 数 |
|---|---|---|
{chr(10).join(abi_lines)}

规则：每个 `PT_LOAD` 必须 `p_align >= 16384` 且
`p_offset % p_align == p_vaddr % p_align`。`PASS` 仅为静态 ELF program-header
结论；不是 Android 设备上 16 KB page-size runtime PASS，也不检查
`GNU_RELRO` 边界。

## Revision、notice 与 source

- Maven artifact version `2.0.3`：`PROVEN`。
- exact PDFium upstream source revision：`UNPROVEN`；native SHA 可固定文件，但不能替代 commit。
- source audit：`{license_matrix['source_audit']['status']}`；缺失项：
  `{', '.join(license_matrix['source_audit']['missing']) or 'none'}`。
- notice obligation：`{license_matrix['overall']['notice_obligation']}`。
- source jar 若只含绑定层 Java/Kotlin，不含 PDFium native source、DEPS、GN，
  不得臆测 corresponding source 已交付。

## 复算

```powershell
python .planning/v2-g1/G1-L1-NETWORK-PDFIUM/verify_pdfium_artifact_audit.py --self-test
python .planning/v2-g1/G1-L1-NETWORK-PDFIUM/verify_pdfium_artifact_audit.py
```

verify 的 9 个负例覆盖 hash、size、redirect、缺失 AAR/POM/source、
ELF UNPROVEN、静态冒充 runtime 与 binary 入证据。

## 非结论

本审计不关闭 Q03、不通过 G1、不证明 runtime load、16 KB 设备启动、Range 首开、
检索/批注、内存、性能或设备兼容。fresh reviewer 必须独立复核；任何 runtime/device
继续受 `L2/L3 LOCKED` 约束。
"""


def build_checkpoint(
    source_baseline: dict[str, Any],
    abi_summary: list[dict[str, Any]],
    license_matrix: dict[str, Any],
) -> str:
    abi_text = "；".join(f"{row['abi']}={row['status']}" for row in abi_summary) or "none"
    return f"""# G1-L1 PDFium artifact 审计检查点（2026-11-04）

状态：`STATIC_AUDIT_COMPLETE / Q03_OPEN / G1_NOT_PASSED / RUNTIME_UNVERIFIED`

## ACTIVE

- 固定分支 `{source_baseline['branch']}`、HEAD `{source_baseline['head']}`；开工 clean。
- 仅按用户批准的 `repo1.maven.org` / `io.legere` 三个 2.0.3 坐标完成 AAR、
  sources.jar、POM、`.sha256` GET；无 redirect、设备、服务器、runtime、QA、commit 或 push。
- AAR SHA-256 与给定值、文件 size 与初始 HEAD length 均已核对；本地哈希与可用远端
  `.sha256` 一致，404 项保留为缺失。
- ELF 静态结果：{abi_text}。
- source：`{license_matrix['source_audit']['status']}`；notice：
  `{license_matrix['overall']['notice_obligation']}`；exact PDFium source revision `UNPROVEN`。

## 未完成 / 不相容主张

- ELF 静态对齐不是 16 KB runtime PASS；未启动 Android 或加载 native。
- 未验证 PDF 渲染、Range、页面 seek、搜索、批注、内存、性能或设备。
- 未取得 PDFium native corresponding source、DEPS、GN、complete third-party notice
  bundle 或 written source offer。
- Q03 仍未关闭，G1 仍未通过，L2/L3 仍锁定。

## 下一步

角色：fresh scoped reviewer（全新只读会话）。

第一条只读动作：读取 `manifest.json`、`manifest.sha256`、`artifact-index.json`、
`elf-inventory.json`、`license-source-matrix.json`，再运行 verify。

停止条件：manifest/hash/LF/计数不可复算，AAR hash/size 漂移，出现 redirect、binary
入证据、source/notice 过度主张，或把静态 ELF PASS 写成 runtime/G1/Q03 PASS。
"""


def finalize_manifest(output: Path, source_baseline: dict[str, Any], index: dict[str, Any]) -> None:
    files = sorted(
        path
        for path in output.rglob("*")
        if path.is_file() and path.name not in {"manifest.json", "manifest.sha256"}
    )
    payload = []
    total_bytes = 0
    for path in files:
        size = path.stat().st_size
        total_bytes += size
        payload.append(
            {
                "path": path.relative_to(output).as_posix(),
                "bytes": size,
                "sha256": sha256_file(path),
            }
        )
    manifest = {
        "schema_id": "aurorama.g1-l1-network-pdfium.manifest/v1",
        "scope": "NETWORK_ARTIFACT_STATIC_AUDIT_ONLY",
        "status": "STATIC_AUDIT_COMPLETE / Q03_OPEN / G1_NOT_PASSED / RUNTIME_UNVERIFIED",
        "source_baseline": source_baseline,
        "authorization": {
            "approved_hosts": [ALLOWED_HOST],
            "approved_coordinates": [
                f"io.legere:{module}:{VERSION}" for module in MODULES
            ],
            "https_only": True,
            "redirects_followed": False,
            "binary_retained_in_evidence": False,
            "device_accessed": False,
            "account_accessed": False,
            "server_accessed": False,
            "runtime_executed": False,
            "gradle_executed": False,
            "adb_accessed": False,
            "qa_started": False,
            "commit_created": False,
            "push_performed": False,
            "q03_closed": False,
            "g1_passed": False,
        },
        "artifact_hashes": {
            row["key"]: {"size": row["size"], "sha256": row["sha256"]}
            for row in index["artifacts"]
        },
        "elf_abi_summary": index["elf_abi_summary"],
        "static_16kb_is_runtime_pass": False,
        "payload": {
            "file_count": len(payload),
            "total_bytes": total_bytes,
            "files": payload,
        },
        "checksum_file": "manifest.sha256",
        "verify_command": "python .planning/v2-g1/G1-L1-NETWORK-PDFIUM/verify_pdfium_artifact_audit.py",
    }
    write_json(output / "manifest.json", manifest)
    write_text(
        output / "manifest.sha256",
        f"{sha256_file(output / 'manifest.json')}  manifest.json\n",
    )


def audit(repo: Path, output: Path) -> dict[str, Any]:
    source_baseline = preflight(repo, output)
    output.mkdir(parents=True, exist_ok=True)
    command_records: list[str] = []
    artifacts: list[dict[str, Any]] = []
    checksum_files: list[dict[str, Any]] = []
    poms: dict[str, dict[str, Any]] = {}
    sources_jars: dict[str, dict[str, Any]] = {}
    aar_inventory: dict[str, dict[str, Any]] = {}
    aar_audits: dict[str, dict[str, Any]] = {}
    class_names: dict[str, set[str]] = {}
    all_elf: list[dict[str, Any]] = []
    extracted_licenses: list[dict[str, Any]] = []

    with tempfile.TemporaryDirectory(prefix="aurorama-pdfium-2.0.3-") as temp_name:
        temp_root = Path(temp_name)
        downloaded: dict[tuple[str, str], Path] = {}
        for module in MODULES:
            for kind in KINDS:
                artifact, checksum, path, _body, lines = download_artifact(module, kind, temp_root)
                artifacts.append(artifact)
                checksum_files.append(checksum)
                downloaded[(module, kind)] = path
                command_records.extend(lines)

        for module in MODULES:
            pom = inspect_pom(module, downloaded[(module, "POM")])
            poms[module] = pom
            sources = inspect_sources(
                module,
                downloaded[(module, "SOURCES_JAR")],
                output,
                extracted_licenses,
            )
            sources_jars[module] = sources
            aar = inspect_aar(
                module,
                downloaded[(module, "AAR")],
                output,
                extracted_licenses,
            )
            aar_audits[module] = aar
            aar_inventory[module] = aar["inventory"]
            class_names[module] = set(aar["class_names"])
            all_elf.extend(aar["elf_records"])

    abi_summary = derive_abi_summary(all_elf)
    relations = build_module_relations(poms, class_names, all_elf)
    index = {
        "schema_id": "aurorama.g1-l1-network-pdfium.artifact-index/v1",
        "generated_at_utc": utc_now(),
        "source_baseline": source_baseline,
        "authorization": {
            "approved_hosts": [ALLOWED_HOST],
            "https_only": True,
            "redirects_followed": False,
            "max_redirections": 0,
            "unknown_host_redirect_action": "STOP",
            "binary_retained_in_evidence": False,
            "approved_coordinates": [
                f"io.legere:{module}:{VERSION}" for module in MODULES
            ],
        },
        "acquisition": {
            "tool": "curl.exe",
            "method": "GET",
            "temporary_directory_system_only": True,
            "temporary_binaries_removed": True,
            "primary_artifact_count": len(artifacts),
            "checksum_record_count": len(checksum_files),
        },
        "artifacts": sorted(artifacts, key=lambda row: (row["module"], row["kind"])),
        "checksum_files": sorted(
            checksum_files, key=lambda row: (row["for_module"], row["for_kind"])
        ),
        "poms": {module: poms[module] for module in MODULES},
        "sources_jars": {module: sources_jars[module] for module in MODULES},
        "aar_inventory": aar_inventory,
        "module_relations": relations,
        "elf_parser": {
            "implementation": "PYTHON_STRUCT_ELF_PROGRAM_HEADER_V1",
            "llvm_readelf_available": False,
            "rule": "Every PT_LOAD: p_align >= 16384 and p_offset % p_align == p_vaddr % p_align",
        },
        "elf_abi_summary": abi_summary,
        "limitations": {
            "static_only": True,
            "runtime_page_size_16kb_verified": False,
            "gnu_relro_page_boundary_verified": False,
            "pdf_render_range_device_not_executed": True,
        },
    }
    write_json(output / "artifact-index.json", index)

    elf_inventory = {
        "schema_id": "aurorama.g1-l1-network-pdfium.elf-inventory/v1",
        "generated_at_utc": utc_now(),
        "scope": "STATIC_ELF_PROGRAM_HEADER_ONLY",
        "parser": index["elf_parser"],
        "files": sorted(all_elf, key=lambda row: (row["source_module"], row["path"])),
        "abi_summary": abi_summary,
        "runtime": {
            "page_size_16kb_runtime_status": "UNVERIFIED",
            "runtime_executed": False,
            "device_accessed": False,
            "pdf_render_executed": False,
            "range_executed": False,
        },
        "static_pass_is_runtime_pass": False,
        "gnu_relro_boundary": {
            "status": "UNPROVEN",
            "reason": "This task checks PT_LOAD alignment only; it does not validate the GNU_RELRO end boundary or Android runtime page size.",
        },
    }
    write_json(output / "elf-inventory.json", elf_inventory)

    license_matrix = build_license_source_matrix(
        source_baseline, poms, aar_audits, sources_jars
    )
    write_json(output / "license-source-matrix.json", license_matrix)

    write_text(output / "commands.jsonl", "\n".join(command_records) + "\n")
    write_text(
        output / "README.md",
        build_readme(
            source_baseline,
            index["artifacts"],
            index["checksum_files"],
            aar_inventory,
            abi_summary,
            license_matrix,
        ),
    )
    write_text(
        output / "CHECKPOINT.md",
        build_checkpoint(source_baseline, abi_summary, license_matrix),
    )
    finalize_manifest(output, source_baseline, index)
    return {
        "status": "STATIC_AUDIT_COMPLETE",
        "artifact_count": len(artifacts),
        "elf_files": len(all_elf),
        "abi_summary": abi_summary,
        "source_status": license_matrix["source_audit"]["status"],
        "q03": "OPEN",
        "g1": "NOT_PASSED",
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        result = audit(args.repo.resolve(), args.output.resolve())
        print(json.dumps(result, ensure_ascii=False, sort_keys=True))
        return 0
    except AuditStop as exc:
        print(f"AUDIT STOP: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
