"""Repository hygiene tests for Jupyter notebooks, logs, and precompiled native binaries."""

from __future__ import annotations

import json
import re
import subprocess
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[3]

# Patterns that must never appear as literal credentials or internal server IPs in notebooks.
FORBIDDEN_NOTEBOOK_PATTERNS = [
    re.compile(r"""['"]Password['"]\s*:\s*['"][^'"]+['"]"""),
    re.compile(r"""['"]UserID['"]\s*:\s*['"][^'"]+['"]"""),
    re.compile(r"\b157\.82\.\d+\.\d+\b"),
]


def _tracked_files() -> list[str]:
    out = subprocess.check_output(
        ["git", "ls-files"], cwd=REPO_ROOT, text=True, encoding="utf-8"
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


def test_notebooks_have_stripped_outputs_and_no_credentials() -> None:
    notebooks = [p for p in _tracked_files() if p.endswith(".ipynb")]
    assert notebooks, "Expected tracked notebooks under src/scripts/"

    output_violations: list[str] = []
    secret_violations: list[str] = []

    for rel in notebooks:
        nb = json.loads((REPO_ROOT / rel).read_text(encoding="utf-8"))
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
        f"Tracked notebooks must have stripped outputs/execution_count: {output_violations[:10]}"
    )
    assert secret_violations == [], (
        f"Tracked notebooks contain hardcoded credentials or internal IPs: {secret_violations}"
    )
