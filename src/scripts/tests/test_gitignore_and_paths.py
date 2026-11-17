"""Tests for .gitignore alignment, retired scaffolding/dead assets, and portable PFLOW_HOME defaults."""

from __future__ import annotations

import importlib.util
import subprocess
from pathlib import Path

import pytest

REPO_ROOT = Path(__file__).resolve().parents[3]

RETIRED_PREFIXES = (
    ".ai-sdlc/",
    "backlog/",
    "src/truck/2024JPN_v25.04_100m/",
    "src/scripts/japan_shapefile/",
    "src/scripts/prefectures.geojson",
    "src/shared/gm-jp/coastl_jpn.",
    "src/scripts/Kobe/increased_results",
    "src/scripts/Kobe/origin_result",
    "src/scripts/Kobe/processed_",
    "src/scripts/feed_kobecity_",
    "config/archive/",
    "src/otp/",
    "src/archive/experimental/sim/",
    "src/archive/experimental/dcity/gtfs/",
)


def _git_lines(*args: str) -> list[str]:
    out = subprocess.check_output(
        ["git", *args], cwd=REPO_ROOT, text=True, encoding="utf-8"
    )
    return [line for line in out.splitlines() if line]


def _load_merge_activity():
    spec = importlib.util.spec_from_file_location(
        "merge_activity", REPO_ROOT / "src" / "scripts" / "merge_activity.py"
    )
    assert spec is not None and spec.loader is not None
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def test_no_tracked_configs_or_scripts_collide_with_gitignore() -> None:
    # .log files are untracked in the sibling notebook/log hygiene PR; all config/script
    # files (.csv, .json, .sh, .bat, .ps1, .tsv, .properties) must never match .gitignore.
    ignored_tracked = [
        p
        for p in _git_lines("ls-files", "-i", "-c", "--exclude-standard")
        if not p.endswith(".log")
    ]
    assert ignored_tracked == [], (
        f"Tracked files collide with .gitignore ({len(ignored_tracked)} files): {ignored_tracked[:10]}"
    )


def test_retired_scaffolding_and_dead_assets_untracked() -> None:
    tracked = _git_lines("ls-files")
    lingering = [p for p in tracked if p.startswith(RETIRED_PREFIXES)]
    assert lingering == [], f"Retired scaffolding or dead assets still tracked: {lingering[:15]}"


def test_ai_sdlc_and_backlog_are_gitignored() -> None:
    for probe in (".ai-sdlc/review-policy.md", "backlog/config.yml"):
        proc = subprocess.run(
            ["git", "check-ignore", "-q", probe], cwd=REPO_ROOT, check=False
        )
        assert proc.returncode == 0, f"{probe} should be matched by .gitignore"


def test_merge_activity_default_pflow_home_is_portable(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    mod = _load_merge_activity()

    monkeypatch.setenv("PFLOW_HOME", str(tmp_path / "custom_pflow"))
    assert mod.default_pflow_home() == str(tmp_path / "custom_pflow")

    monkeypatch.delenv("PFLOW_HOME", raising=False)
    # Force repo-relative guess check to miss so fallback branch executes
    monkeypatch.setattr(mod, "__file__", str(tmp_path / "a" / "b" / "c" / "merge_activity.py"))

    monkeypatch.setattr(mod.os, "name", "posix", raising=False)
    posix_default = mod.default_pflow_home()
    assert "D:/Dropbox" not in posix_default
    assert posix_default.endswith("/Dropbox/PFLOW")

    monkeypatch.setattr(mod.os, "name", "nt", raising=False)
    assert mod.default_pflow_home() == "H:/Dropbox/PFLOW"


def test_no_hardcoded_desktop_or_d_drive_paths_in_active_code() -> None:
    tracked = [
        p for p in _git_lines("ls-files") if p.endswith((".java", ".py"))
    ]
    offenders: list[str] = []
    for rel in tracked:
        if rel.startswith("src/scripts/tests/"):
            continue
        for lineno, line in enumerate(
            (REPO_ROOT / rel).read_text(encoding="utf-8", errors="replace").splitlines(), 1
        ):
            stripped = line.strip()
            if stripped.startswith(("//", "#", "*")):
                continue
            if "C:/Users/ksym2/" in stripped or '"D:/Dropbox/PFLOW"' in stripped:
                offenders.append(f"{rel}:{lineno}: {stripped}")
    assert offenders == [], f"Hardcoded machine paths in code: {offenders}"
