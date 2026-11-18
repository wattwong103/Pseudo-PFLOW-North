"""Regression tests for confidential dataset paths, internal S3 URIs, and truck POI provenance."""

from __future__ import annotations

import os
import re
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[3]

FORBIDDEN_DOC_AND_SCRIPT_PATTERNS = [
    re.compile(r"s3://pseudo-pflow", re.IGNORECASE),
    re.compile(r"00_PTData|提供資料|34,391レコード"),
]

TELEPOINT_PATTERN = re.compile(r"telepoint", re.IGNORECASE)


def _tracked_files() -> list[str]:
    out = subprocess.check_output(
        ["git", "-c", f"core.excludesFile={os.devnull}", "ls-files"],
        cwd=REPO_ROOT,
        text=True,
        encoding="utf-8",
    )
    return [line for line in out.splitlines() if line]


def test_no_internal_s3_uris_or_confidential_pt_paths_in_docs_and_scripts() -> None:
    offenders: list[str] = []
    for rel in _tracked_files():
        if rel == "src/scripts/tests/test_confidential_paths.py":
            continue
        if not rel.endswith((".md", ".py", ".properties", ".java", ".sh", ".bat")):
            continue
        path = REPO_ROOT / rel
        if not path.is_file():
            continue
        text = path.read_text(encoding="utf-8", errors="replace")
        for lineno, line in enumerate(text.splitlines(), 1):
            for pat in FORBIDDEN_DOC_AND_SCRIPT_PATTERNS:
                if pat.search(line):
                    offenders.append(f"{rel}:{lineno} ({pat.pattern})")
    assert offenders == [], (
        f"Internal S3 URIs or confidential PT raw paths must not appear in tracked files: {offenders}"
    )


def test_truck_configs_and_java_do_not_misattribute_bundled_pois_to_telepoint() -> None:
    offenders: list[str] = []
    for rel in _tracked_files():
        if not rel.startswith(("config/truck/", "src/truck/")):
            continue
        if not rel.endswith((".md", ".properties", ".java", ".csv")):
            continue
        path = REPO_ROOT / rel
        if not path.is_file():
            continue
        text = path.read_text(encoding="utf-8", errors="replace")
        for lineno, line in enumerate(text.splitlines(), 1):
            if TELEPOINT_PATTERN.search(line):
                offenders.append(f"{rel}:{lineno}")
    assert offenders == [], (
        f"Bundled truck POIs are curated + Economic Census mesh derivatives, not Zenrin Telepoint: {offenders}"
    )


def test_markov_generators_require_explicit_raw_path_or_env_var() -> None:
    scripts = [
        ("scripts/markov/generate_markov_akita.py", "PFLOW_PT_RAW_AKITA"),
        ("scripts/markov/generate_markov_kyushu.py", "PFLOW_PT_RAW_KYUSHU"),
    ]
    clean_env = {k: v for k, v in os.environ.items() if not k.startswith("PFLOW_PT_RAW_")}

    for rel, env_var in scripts:
        script_path = REPO_ROOT / rel
        help_res = subprocess.run(
            [sys.executable, str(script_path), "--help"],
            env=clean_env,
            capture_output=True,
            text=True,
            check=False,
        )
        assert help_res.returncode == 0, f"{rel} --help failed: {help_res.stderr}"

        no_arg_res = subprocess.run(
            [sys.executable, str(script_path)],
            env=clean_env,
            capture_output=True,
            text=True,
            check=False,
        )
        assert no_arg_res.returncode != 0, f"{rel} should fail cleanly when --raw/{env_var} is omitted"
        assert env_var in no_arg_res.stderr or "--raw" in no_arg_res.stderr

        blank_env = {**clean_env, env_var: "   "}
        blank_res = subprocess.run(
            [sys.executable, str(script_path)],
            env=blank_env,
            capture_output=True,
            text=True,
            check=False,
        )
        assert blank_res.returncode == 2, f"{rel} should treat blank {env_var} as unset"
        assert env_var in blank_res.stderr or "--raw" in blank_res.stderr
