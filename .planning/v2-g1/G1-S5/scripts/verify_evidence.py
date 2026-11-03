#!/usr/bin/env python3
"""Verify G1-S5 manifest integrity and coverage without external packages."""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
REPO = ROOT.parents[2]
MANIFEST = ROOT / "manifest.json"
ATTRIBUTES = ROOT / ".gitattributes"
EVIDENCE_ROOT_REL = ".planning/v2-g1/G1-S5/"
EXPECTED_BRANCH = "codex/v2-integration"
BASELINE_POLICY = "ancestor"
EXPECTED_ATTRIBUTES = b"* text eol=lf\n"
EXPECTED_LINE_ENDING_POLICY = {
    "scope": EVIDENCE_ROOT_REL,
    "attributesFile": ".gitattributes",
    "rule": "* text eol=lf",
    "text": "set",
    "eol": "lf",
}


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def git(*args: str, strip_output: bool = True) -> str:
    output = subprocess.run(
        ["git", *args], cwd=REPO, check=True, capture_output=True,
        text=True, encoding="utf-8",
    ).stdout
    return output.strip() if strip_output else output.rstrip("\r\n")


def git_exit(*args: str) -> tuple[int, str]:
    result = subprocess.run(
        ["git", *args], cwd=REPO, check=False, capture_output=True,
        text=True, encoding="utf-8",
    )
    return result.returncode, result.stderr.strip()


def fail(code: str, detail: str) -> None:
    print(json.dumps({"status": "FAIL", "code": code, "detail": detail}, ensure_ascii=False))
    raise SystemExit(1)


def assert_git_baseline(branch: str, head: str, baseline: str) -> bool:
    if branch != EXPECTED_BRANCH:
        fail("GIT_BASELINE_DRIFT", f"branch={branch}")
    if not re.fullmatch(r"[0-9a-f]{40}", head or ""):
        fail("GIT_BASELINE_INVALID", f"head={head}")
    if not re.fullmatch(r"[0-9a-f]{40}", baseline or ""):
        fail("GIT_BASELINE_INVALID", f"sourceBaseline={baseline}")
    code, error = git_exit("merge-base", "--is-ancestor", baseline, head)
    if code not in (0, 1):
        fail("GIT_BASELINE_CHECK", error or f"git exit {code}")
    if code != 0:
        fail(
            "GIT_BASELINE_DRIFT",
            f"branch={branch} head={head} sourceBaseline={baseline}",
        )
    return True


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--expected-dirty",
        action="append",
        default=[],
        metavar="REPO_PATH",
        help="allow one exact dirty path outside the evidence root; repeat as needed",
    )
    return parser.parse_args(argv)


def normalize_expected_dirty(values: list[str]) -> set[str]:
    allowed = set()
    for value in values:
        rel = value.strip().replace("\\", "/")
        path = Path(rel)
        if not rel or path.is_absolute() or ".." in path.parts or rel.endswith("/"):
            fail("EXPECTED_DIRTY_INVALID", value)
        if rel == EVIDENCE_ROOT_REL.rstrip("/") or rel.startswith(EVIDENCE_ROOT_REL):
            fail("EXPECTED_DIRTY_IN_SCOPE", value)
        allowed.add(rel)
    return allowed


def worktree_outside_scope(status_output: str, allowed_dirty: set[str]) -> list[str]:
    outside = []
    for line in status_output.splitlines():
        if len(line) < 4:
            continue
        changed = [part.strip() for part in line[3:].replace("\\", "/").split(" -> ")]
        if any(
            not rel.startswith(EVIDENCE_ROOT_REL) and rel not in allowed_dirty
            for rel in changed
        ):
            outside.append(line)
    return outside


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    allowed_dirty = normalize_expected_dirty(args.expected_dirty)
    if not MANIFEST.is_file():
        fail("MANIFEST_MISSING", str(MANIFEST))
    try:
        manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
    except Exception as exc:
        fail("MANIFEST_JSON", str(exc))

    branch = git("branch", "--show-current")
    head = git("rev-parse", "HEAD")
    git_baseline = manifest.get("gitBaseline", {})
    if git_baseline.get("branch") != EXPECTED_BRANCH:
        fail("GIT_BASELINE_MANIFEST", str(git_baseline.get("branch")))
    if git_baseline.get("headPolicy") != BASELINE_POLICY:
        fail("GIT_BASELINE_POLICY", str(git_baseline.get("headPolicy")))
    baseline_head = str(git_baseline.get("head", ""))
    assert_git_baseline(branch, head, baseline_head)
    outside = []
    status_output = git(
        "status", "--porcelain=v1", "--untracked-files=all", strip_output=False
    )
    outside = worktree_outside_scope(status_output, allowed_dirty)
    if outside:
        fail("WORKTREE_OUTSIDE_SCOPE", " | ".join(outside))

    if manifest.get("lineEndingPolicy") != EXPECTED_LINE_ENDING_POLICY:
        fail("LINE_ENDING_POLICY_MANIFEST", json.dumps(
            manifest.get("lineEndingPolicy"), ensure_ascii=False
        ))
    if not ATTRIBUTES.is_file() or ATTRIBUTES.read_bytes() != EXPECTED_ATTRIBUTES:
        fail("LINE_ENDING_ATTRIBUTES", ".gitattributes bytes differ from canonical rule")

    entries = manifest.get("entries", [])
    if not entries:
        fail("ENTRIES_EMPTY", "manifest entries")
    ids = [entry.get("id") for entry in entries]
    paths = [entry.get("path") for entry in entries]
    if len(ids) != len(set(ids)):
        fail("DUPLICATE_ID", "manifest evidence ids")
    if len(paths) != len(set(paths)):
        fail("DUPLICATE_PATH", "manifest evidence paths")
    control_entries = [
        entry for entry in entries if entry.get("kind") == "line-ending-policy"
    ]
    if len(control_entries) != 1:
        fail("LINE_ENDING_CONTROL_COUNT", f"expected 1, got {len(control_entries)}")
    control = control_entries[0]
    if control.get("id") != "CONTROL-GITATTRIBUTES" or control.get("path") != ".gitattributes":
        fail("LINE_ENDING_CONTROL", json.dumps(control, ensure_ascii=False))

    actual_files = {
        path.relative_to(ROOT).as_posix()
        for path in ROOT.rglob("*")
        if path.is_file() and path.name != "manifest.json" and "__pycache__" not in path.parts
    }
    if actual_files != set(paths):
        fail("UNINDEXED_OR_MISSING_FILE",
             f"unindexed={sorted(actual_files-set(paths))}; missing={sorted(set(paths)-actual_files)}")

    directory_files = sorted(actual_files | {"manifest.json"})
    for rel in directory_files:
        path = ROOT / rel
        data = path.read_bytes()
        try:
            data.decode("utf-8")
        except UnicodeDecodeError as exc:
            fail("TEXT_ENCODING", f"{rel}: {exc}")
        if b"\r" in data:
            fail("LINE_ENDING_BYTES", rel)

    attribute_output = git(
        "check-attr", "text", "eol", "--",
        *(EVIDENCE_ROOT_REL + rel for rel in directory_files),
    )
    attribute_values = {}
    for line in attribute_output.splitlines():
        path, attribute, value = line.rsplit(": ", 2)
        attribute_values[(path, attribute)] = value
    for rel in directory_files:
        git_path = EVIDENCE_ROOT_REL + rel
        if attribute_values.get((git_path, "text")) != "set":
            fail("EOL_TEXT_ATTRIBUTE", rel)
        if attribute_values.get((git_path, "eol")) != "lf":
            fail("EOL_ATTRIBUTE", rel)

    total_bytes = 0
    max_image = {"width": 0, "height": 0, "path": ""}
    for entry in entries:
        rel = entry["path"]
        if rel.startswith("/") or ".." in Path(rel).parts:
            fail("UNSAFE_PATH", rel)
        path = (ROOT / rel).resolve()
        if ROOT.resolve() not in path.parents or not path.is_file():
            fail("PATH_INVALID", rel)
        if path.stat().st_size == 0:
            fail("EMPTY_FILE", rel)
        if sha256(path) != entry["sha256"]:
            fail("SHA_MISMATCH", rel)
        if path.stat().st_size != entry["bytes"]:
            fail("BYTE_MISMATCH", rel)
        total_bytes += path.stat().st_size
        suffix = path.suffix.lower()
        if suffix == ".svg":
            try:
                root = ET.parse(path).getroot()
            except Exception as exc:
                fail("SVG_XML_INVALID", f"{rel}: {exc}")
            size = {"width": int(float(root.attrib["width"])), "height": int(float(root.attrib["height"]))}
            if entry.get("pixelSize") != size:
                fail("SVG_SIZE_MISMATCH", rel)
            title = root.find("{http://www.w3.org/2000/svg}title")
            desc = root.find("{http://www.w3.org/2000/svg}desc")
            if title is None or not "".join(title.itertext()).strip():
                fail("SVG_TITLE_MISSING", rel)
            if desc is None or not "".join(desc.itertext()).strip():
                fail("SVG_DESC_MISSING", rel)
            if size["width"] * size["height"] > max_image["width"] * max_image["height"]:
                max_image = {**size, "path": rel}
        elif suffix == ".json":
            try:
                json.loads(path.read_text(encoding="utf-8-sig"))
            except Exception as exc:
                fail("JSON_INVALID", f"{rel}: {exc}")
        elif suffix == ".csv":
            try:
                with path.open("r", encoding="utf-8-sig", newline="") as handle:
                    rows = list(csv.reader(handle))
            except Exception as exc:
                fail("CSV_INVALID", f"{rel}: {exc}")
            if len(rows) < 2 or not all(len(row) == len(rows[0]) for row in rows):
                fail("CSV_SHAPE", rel)
        if suffix in (".svg", ".html"):
            text = path.read_text(encoding="utf-8")
            if 'href="http' in text or 'src="http' in text or "data:image" in text:
                fail("EXTERNAL_OR_INLINE_MEDIA", rel)
            forbidden = ["TO" + "DO", "PLACE" + "HOLDER", "lorem" + " ipsum"]
            if any(token.lower() in text.lower() for token in forbidden):
                fail("PROHIBITED_TEMPLATE_TOKEN", rel)

    coverage = manifest.get("coverage", {})
    if coverage.get("themes", {}).get("covered") != 5:
        fail("THEME_COUNT", "expected 5")
    if coverage.get("interactions", {}).get("covered") != 9:
        fail("INTERACTION_COUNT", "expected 9")
    if coverage.get("rwd", {}).get("frames") != 6:
        fail("RWD_COUNT", "expected 6")
    if coverage.get("states", {}).get("mappings") != 60:
        fail("STATE_COUNT", "expected 60")
    if coverage.get("a11y", {}).get("applicableChecks") != 12:
        fail("A11Y_COUNT", "expected 12")
    if coverage.get("a11y", {}).get("accessibleNames") != 18:
        fail("A11Y_NAME_COUNT", "expected 18")

    theme_entries = [entry for entry in entries if entry.get("kind") == "theme-frame"]
    interaction_entries = [entry for entry in entries if entry.get("kind") == "interaction-low-fi"]
    rwd_entries = [entry for entry in entries if entry.get("kind") == "rwd-frame"]
    state_entries = [entry for entry in entries if entry.get("kind") == "state-frame"]
    a11y_entries = [entry for entry in entries if entry.get("kind") == "a11y-frame"]
    if len(theme_entries) != 5 or len(interaction_entries) != 9:
        fail("EVIDENCE_ENTRY_COUNTS", f"themes={len(theme_entries)} interactions={len(interaction_entries)}")
    if len(rwd_entries) != 6 or len(state_entries) != 3 or len(a11y_entries) != 3:
        fail("EVIDENCE_SUPPORT_COUNTS",
             f"rwd={len(rwd_entries)} states={len(state_entries)} a11y={len(a11y_entries)}")

    contrast = json.loads((ROOT / "data/contrast-matrix.json").read_text(encoding="utf-8"))
    if contrast.get("failCount") != 0 or contrast.get("pairCount") != 130:
        fail("CONTRAST_RESULT", json.dumps({
            "pairCount": contrast.get("pairCount"),
            "failCount": contrast.get("failCount"),
        }))
    minimum = min(row["ratio"] for row in contrast["rows"])
    if minimum < 2.99:
        fail("CONTRAST_MINIMUM", str(minimum))

    states = json.loads((ROOT / "data/state-matrix.json").read_text(encoding="utf-8"))
    if len(states) != 60 or len({row["id"] for row in states}) != 60:
        fail("STATE_DATA", "expected 60 unique mappings")
    rwd = json.loads((ROOT / "data/rwd-matrix.json").read_text(encoding="utf-8"))
    if len(rwd) != 6 or any(row.get("horizontalOverflow", 0) for row in rwd):
        fail("RWD_DATA", "frames or horizontal overflow")

    snapshot = manifest.get("sourceSnapshot", {}).get("files", [])
    if len(snapshot) < 6:
        fail("SOURCE_SNAPSHOT", "missing source hashes")
    for source in snapshot:
        rel = source["path"].replace("\\", "/")
        if rel in allowed_dirty:
            continue
        path = REPO / rel
        if not path.is_file() or sha256(path) != source["sha256"]:
            fail("SOURCE_HASH_MISMATCH", rel)

    manifest_bytes = MANIFEST.stat().st_size
    result = {
        "status": "PASS",
        "manifest": f"{EVIDENCE_ROOT_REL}manifest.json",
        "manifestSha256": sha256(MANIFEST),
        "sourceBaseline": baseline_head,
        "head": head,
        "baselinePolicy": BASELINE_POLICY,
        "entries": len(entries),
        "evidenceEntries": len(entries) - len(control_entries),
        "controlEntries": len(control_entries),
        "files": len(actual_files),
        "totalBytes": total_bytes,
        "directoryFiles": len(directory_files),
        "directoryBytes": total_bytes + manifest_bytes,
        "expectedDirty": sorted(allowed_dirty),
        "lineEnding": {
            "policy": EXPECTED_LINE_ENDING_POLICY["rule"],
            "lfFiles": len(directory_files),
            "crlfFiles": 0,
        },
        "themes": f"{len(theme_entries)}/5",
        "interactions": f"{len(interaction_entries)}/9",
        "rwd": f"{len(rwd_entries)}/6",
        "stateMappings": f"{len(states)}/60",
        "a11yChecks": f"{coverage['a11y']['applicableChecks']}/12",
        "contrast": f"{contrast['passCount']}/{contrast['pairCount']}",
        "minContrast": minimum,
        "maxImage": max_image,
        "duplicateIds": 0,
        "missingPaths": 0,
        "jsonErrors": 0,
        "svgErrors": 0,
    }
    print(json.dumps(result, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())
