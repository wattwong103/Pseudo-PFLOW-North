"""Repository hygiene tests for Jupyter notebooks, docs/configs, logs, and native binaries."""

from __future__ import annotations

import json
import os
import re
import subprocess
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[3]

# Patterns that must never appear as literal credentials, internal server IPs/mounts,
# or confidential/commercial dataset paths in notebooks.
FORBIDDEN_NOTEBOOK_PATTERNS = [
    re.compile(
        r"""['"]?(?:password|passwd|userid|api[_-]?key|secret|webapisessionid)['"]?\s*[:=]\s*['"][^'"]+['"]""",
        re.IGNORECASE,
    ),
    re.compile(r"\b157\.82\.\d+\.\d+\b"),
    re.compile(r"(?:/Users/|C:\\\\Users\\\\|/home/(?:mdxuser|owner|ubuntu)/)"),
    re.compile(r"(?:/mnt/(?:large|free)/|C:/+large/|00_PTData|秘匿|納品物|Konzatsu|Suma_filtere)"),
    re.compile(r"(?:Softbank/|21015_(?:toyama|shizuoka)_od_meshod)", re.IGNORECASE),
]

# Non-placeholder api.password / api.userID assignments or internal IPs in docs/configs/code.
DOC_CRED_PATTERN = re.compile(
    r"api\.(?:password|userID)\s*=\s*(?!<REDACTED>|\$\{|\s*$)\S+", re.IGNORECASE
)
INTERNAL_IP_PATTERN = re.compile(r"\b157\.82\.\d+\.\d+\b")


def _tracked_files() -> list[str]:
    out = subprocess.check_output(
        ["git", "-c", f"core.excludesFile={os.devnull}", "ls-files"],
        cwd=REPO_ROOT,
        text=True,
        encoding="utf-8",
    )
    return [line for line in out.splitlines() if line]


def test_no_scratch_notebooks_or_checkpoints_tracked() -> None:
    tracked = _tracked_files()
    bad = [
        p
        for p in tracked
        if ".ipynb_checkpoints" in p
        or (p.endswith(".ipynb") and Path(p).name.lower().startswith("untitled"))
    ]
    assert bad == [], f"Scratch or checkpoint notebooks must not be tracked: {bad}"


def test_no_logs_or_precompiled_native_binaries_tracked() -> None:
    tracked = _tracked_files()
    bad = [
        p
        for p in tracked
        if p.endswith((".log", ".dylib", ".so", ".dll", ".class"))
    ]
    assert bad == [], f"Log files or precompiled binaries must not be tracked: {bad}"


def test_notebooks_have_stripped_outputs_and_no_credentials_or_personal_paths() -> None:
    notebooks = [p for p in _tracked_files() if p.endswith(".ipynb")]
    assert notebooks, "Expected tracked notebooks under src/scripts/"

    output_violations: list[str] = []
    secret_violations: list[str] = []

    for rel in notebooks:
        nb = json.loads((REPO_ROOT / rel).read_text(encoding="utf-8"))
        widgets = nb.get("metadata", {}).get("widgets", {})
        for kind, wstate in widgets.items():
            if isinstance(wstate, dict) and wstate.get("state"):
                output_violations.append(f"{rel}#metadata.widgets[{kind}] non-empty state")

        for idx, cell in enumerate(nb.get("cells", [])):
            if cell.get("cell_type") == "code":
                if cell.get("outputs"):
                    output_violations.append(f"{rel}#cell[{idx}] has outputs")
                if cell.get("execution_count") is not None:
                    output_violations.append(f"{rel}#cell[{idx}] has execution_count")
            source = "".join(cell.get("source", []))
            for pat in FORBIDDEN_NOTEBOOK_PATTERNS:
                if pat.search(source):
                    secret_violations.append(f"{rel}#cell[{idx}] matches {pat.pattern}")

    assert output_violations == [], (
        f"Tracked notebooks must have stripped outputs/widgets: {output_violations[:10]}"
    )
    assert secret_violations == [], (
        f"Tracked notebooks contain hardcoded credentials, IPs, or personal paths: {secret_violations[:10]}"
    )


def test_no_plaintext_api_credentials_or_internal_ips_in_docs_or_sources() -> None:
    offenders: list[str] = []
    for rel in _tracked_files():
        if rel == "src/scripts/tests/test_notebook_hygiene.py":
            continue
        if not rel.endswith((".md", ".properties", ".example", ".java", ".py")):
            continue
        text = (REPO_ROOT / rel).read_text(encoding="utf-8", errors="replace")
        for lineno, line in enumerate(text.splitlines(), 1):
            if DOC_CRED_PATTERN.search(line) or INTERNAL_IP_PATTERN.search(line):
                offenders.append(f"{rel}:{lineno}")
    assert offenders == [], f"Plaintext credential or internal IP found in: {offenders}"
