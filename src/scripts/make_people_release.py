#!/usr/bin/env python3
"""
make_people_release.py -- turn one people-flow generator run into an immutable,
checksummed "release" folder that gufm can name in its own provenance records.

WHY THIS EXISTS
  gufm trains on PFLOW person + trip files. Those files sit in the generator's
  working tree (data/census/person/agent/<pref>/, data/census/person/trip/<pref>/),
  which the next run overwrites. Without a frozen copy and a sidecar, nobody can say
  which data a model was trained on. A release copies the files once, checks them,
  records where they came from (release.json) and fingerprints every byte
  (MANIFEST.sha256). A release is never rewritten: building the same name again is
  refused, and a half-built release exists only as <name>.partial, which is removed
  on any failure (a warning names it if Windows will not let it go) and renamed to
  <name> only after the manifest verifies (the rename is retried on PermissionError,
  which a sync client or scanner holding a handle can cause).

LAYOUT
  <releases>/<name>/
      release.json                          provenance sidecar
      MANIFEST.sha256                       "<sha256>  <posix-relative-path>", sorted
      person/<pref>/person_<city>.csv       <pref> = first 2 digits of the 5-digit city code
      trip/<pref>/trip_<city>.csv
  Release name: v<major>.<minor>[-<tag>]-<yyyymmdd>-<area>-s<sample%>
  e.g. v3.1-gufm-20260709-tokyo62-s50, v3.2-20261105-kanagawa-s10
  The name must start with meta["era"] + "-" and end with "-s" + meta["sample_pct"].
  <releases> must not sit under a folder named "output" (output/ is local-only and
  excluded from Dropbox sync) nor inside the person or trip source dir.

FORMAT
  All CSVs are headerless.
    person_<city>.csv  9 columns  PERSON_COLUMNS (person_id at index 5)
    trip_<city>.csv    9 columns "v2_9col" or 12 columns "v3_12col" (pid at index 0);
                       chosen by meta["row_semantics"]. "v3_12col" is the v3.2 layout
                       (... trip_id, subtrip_id, rep_mode). The v3.3 12-column layout
                       ends differently (trip_id, arr_time, duration); column count
                       cannot tell them apart, so that layout has no key here yet.
  Gates, checked on the copied files; any failure aborts and leaves no release:
    columns       every non-empty row has the expected number of fields
    pid_coverage  every pid in a city's trip file is a person_id in that city's person file
  The caller supplies REQUIRED_META (plus optional "anomalies", "updates"); the tool
  computes date, lane, location, columns, totals and gates. Pure stdlib.
  meta["area"] is the list of 5-digit city codes and must equal the cities found in the
  source dirs; meta["sample_pct"] is an int from 1 to 100. location is
  <releases parent folder>/<releases folder>/<name>, e.g. 10_people/releases/<name>.

USAGE
  python make_people_release.py --person-dir DIR --trip-dir DIR --releases DIR
      --name v3.1-gufm-20260709-tokyo62-s50 --meta meta.json
  Prints the release path; exit 0 on success, 1 on refusal. Source dirs are flat
  (person_<city>.csv directly inside), e.g. data/census/person/agent/13.
"""

import argparse
import csv
import datetime
import hashlib
import json
import os
import re
import shutil
import stat
import sys
import time
from pathlib import Path, PurePosixPath

PERSON_COLUMNS = ["household_id", "family_type", "gcode", "home_lon", "home_lat",
                  "person_id", "age", "gender_id", "labor_id"]
_TRIP_9 = ["pid", "starttime", "slon", "slat", "elon", "elat",
           "mode_code", "activity_type", "occupation"]
TRIP_COLUMNS = {"v2_9col": _TRIP_9, "v3_12col": _TRIP_9 + ["trip_id", "subtrip_id", "rep_mode"]}

REQUIRED_META = ("era", "area", "sample_pct", "row_semantics", "generating_code",
                 "entry_points", "run_window", "host", "produced_by")
OPTIONAL_META = ("anomalies", "updates")
COMPUTED_META = ("date", "lane", "location", "columns", "totals", "gates")

NAME_RE = re.compile(r"v\d+\.\d+(-[a-z]+)?-\d{8}-[a-z0-9]+-s\d{1,3}")
CITY_RE = re.compile(r"\d{5}")
MANIFEST = "MANIFEST.sha256"
SIDECAR = "release.json"
OS_JUNK_NAMES = {".ds_store", "thumbs.db", "desktop.ini"}  # compared lower-case
RENAME_ATTEMPTS = 5
RENAME_BACKOFF_S = 0.5  # doubles after each failed attempt
PERSON_PID_INDEX = PERSON_COLUMNS.index("person_id")
TRIP_PID_INDEX = _TRIP_9.index("pid")


def validate_name(name: str) -> None:
    if not NAME_RE.fullmatch(name):
        raise ValueError(
            f"release name {name!r} does not match "
            "v<major>.<minor>[-<tag>]-<yyyymmdd>-<area>-s<sample%>, e.g. v3.2-20261105-kanagawa-s10")


def _check_meta(meta: dict, name: str) -> None:
    missing = [k for k in REQUIRED_META if meta.get(k) in (None, "", [], {})]
    if missing:
        raise ValueError(f"missing required meta field(s): {', '.join(missing)}")
    computed = [k for k in COMPUTED_META if k in meta]
    if computed:
        raise ValueError(f"meta field(s) are computed by the tool, remove: {', '.join(computed)}")
    unknown = [k for k in meta if k not in REQUIRED_META and k not in OPTIONAL_META]
    if unknown:
        raise ValueError(f"unknown meta field(s): {', '.join(unknown)}")
    if meta["row_semantics"] not in TRIP_COLUMNS:
        raise ValueError(f"row_semantics {meta['row_semantics']!r} is not one of {sorted(TRIP_COLUMNS)}")
    area, pct = meta["area"], meta["sample_pct"]
    if not (isinstance(area, list) and len(set(area)) == len(area)
            and all(isinstance(c, str) and CITY_RE.fullmatch(c) for c in area)):
        raise ValueError("meta area must be a list of distinct 5-digit city codes")
    if isinstance(pct, bool) or not isinstance(pct, int) or not 1 <= pct <= 100:
        raise ValueError(f"meta sample_pct must be an integer from 1 to 100, got {pct!r}")
    if not (name.startswith(f"{meta['era']}-") and name.endswith(f"-s{pct}")):
        raise ValueError(f"release name {name!r} must start with '{meta['era']}-' and end with "
                         f"'-s{pct}' (meta era and sample_pct)")


def _check_area(area: list, cities: set) -> None:
    given = set(area)
    if given != cities:
        raise ValueError("meta area does not match the cities in the source dirs: "
                         f"missing from area {sorted(cities - given)}, "
                         f"extra in area {sorted(given - cities)}")


def _check_destination(releases_dir: Path, person_dir: Path, trip_dir: Path) -> None:
    """Refuse a releases dir under output/ or inside a source dir (both resolved)."""
    resolved = releases_dir.resolve()
    if any(part.lower() == "output" for part in resolved.parts):
        raise ValueError(f"releases dir {releases_dir} is under output/, which is local-only "
                         "and not synced; use 10_people/releases")
    for source in (person_dir, trip_dir):
        if resolved.is_relative_to(source.resolve()):
            raise ValueError(f"releases dir {releases_dir} is inside source dir {source}; "
                             "a release must not be written into its own input")


def _discover(directory: Path, kind: str) -> dict:
    """{city: path} for <kind>_<5-digit-city>.csv directly inside directory."""
    pattern = re.compile(rf"{kind}_(\d{{5}})\.csv")
    found = {}
    if directory.is_dir():
        for f in sorted(directory.iterdir()):
            m = pattern.fullmatch(f.name)
            if m and f.is_file():
                found[m.group(1)] = f
    if not found:
        raise ValueError(f"no {kind}_<city>.csv files in {directory}")
    return found


def _scan(path: Path, expected: int, pid_index: int, expect_label: str):
    """Return (data rows, set of pids). Every non-empty row must have `expected` fields."""
    rows, pids = 0, set()
    with path.open("r", encoding="utf-8", newline="") as fh:
        for lineno, row in enumerate(csv.reader(fh), 1):
            if not row:
                continue
            if len(row) != expected:
                where = "" if rows == 0 else f" (line {lineno})"
                raise ValueError(f"{path.name} has {len(row)} columns, {expect_label} {expected}{where}")
            rows += 1
            pids.add(row[pid_index])
    if rows == 0:
        raise ValueError(f"{path.name} has no data rows")
    return rows, pids


def _sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def _write_text(path: Path, text: str) -> None:
    with open(path, "w", encoding="utf-8", newline="\n") as fh:
        fh.write(text)


def _clear_read_only_and_retry(func, path, _exc) -> None:
    """rmtree error handler: copy2 keeps the read-only bit, which blocks deletes on Windows."""
    os.chmod(path, stat.S_IWRITE)
    func(path)


def _remove_partial(partial: Path) -> None:
    # onerror was replaced by onexc in Python 3.12 (and warns there)
    handler = {"onexc" if sys.version_info >= (3, 12) else "onerror": _clear_read_only_and_retry}
    try:
        shutil.rmtree(partial, **handler)
    except OSError:
        pass
    if partial.exists():
        print(f"warning: could not remove {partial}; delete it by hand before re-running",
              file=sys.stderr)


def _rename_with_retry(src: Path, dst: Path) -> None:
    """Rename, retrying on PermissionError (Dropbox, antivirus or an indexer may hold a handle)."""
    delay = RENAME_BACKOFF_S
    for attempt in range(1, RENAME_ATTEMPTS + 1):
        try:
            src.rename(dst)
            return
        except PermissionError:
            if attempt == RENAME_ATTEMPTS:
                raise
            time.sleep(delay)
            delay *= 2


def _copy_into(partial: Path, kind: str, files: dict) -> None:
    for city, src in files.items():
        dst = partial / kind / city[:2] / src.name
        dst.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(src, dst)


def _check_copies(partial: Path, person_files: dict, trip_files: dict, semantics: str):
    """Run the columns and pid_coverage gates on the copied files; return (persons, trip rows).

    persons is the sum of per-city distinct person ids (person_id is globally unique in the
    generator output), so no set of all ids is held in memory.
    """
    persons, trips = 0, 0
    for city in sorted(person_files):
        _, person_ids = _scan(partial / "person" / city[:2] / person_files[city].name,
                              len(PERSON_COLUMNS), PERSON_PID_INDEX, "expected")
        n_trips, trip_pids = _scan(partial / "trip" / city[:2] / trip_files[city].name,
                                   len(TRIP_COLUMNS[semantics]), TRIP_PID_INDEX, f"{semantics} expects")
        unknown = trip_pids - person_ids
        if unknown:
            raise ValueError(f"{city}: {len(unknown)} trip pid(s) not in {person_files[city].name}, "
                             f"e.g. {sorted(unknown)[:3]}")
        persons += len(person_ids)
        trips += n_trips
    return persons, trips


def _sidecar(location: str, meta: dict, n_person_files: int, n_trip_files: int, persons: int, trips: int) -> dict:
    sidecar = {
        "era": meta["era"],
        "date": datetime.date.today().isoformat(),
        "area": sorted(meta["area"]),
        "sample_pct": meta["sample_pct"],
        "lane": "cheap",
        "location": location,
        "generating_code": meta["generating_code"],
        "entry_points": meta["entry_points"],
        "run_window": meta["run_window"],
        "row_semantics": meta["row_semantics"],
        "columns": {"person": PERSON_COLUMNS, "trip": TRIP_COLUMNS[meta["row_semantics"]]},
        "totals": {"person_files": n_person_files, "trip_files": n_trip_files,
                   "persons": persons, "trips": trips},
        "gates": {"columns": "PASS", "pid_coverage": "PASS"},
    }
    if "anomalies" in meta:
        sidecar["anomalies"] = meta["anomalies"]
    sidecar["host"] = meta["host"]
    sidecar["produced_by"] = meta["produced_by"]
    if "updates" in meta:
        sidecar["updates"] = meta["updates"]
    return sidecar


def _write_manifest(release_dir: Path) -> None:
    entries = sorted((p.relative_to(release_dir).as_posix(), p) for p in release_dir.rglob("*")
                     if p.is_file() and p.name != MANIFEST)
    _write_text(release_dir / MANIFEST, "".join(f"{_sha256(p)}  {rel}\n" for rel, p in entries))


def build_release(person_dir: Path, trip_dir: Path, releases_dir: Path, name: str, meta: dict) -> Path:
    validate_name(name)
    _check_meta(meta, name)
    person_dir, trip_dir, releases_dir = Path(person_dir), Path(trip_dir), Path(releases_dir)
    _check_destination(releases_dir, person_dir, trip_dir)
    person_files, trip_files = _discover(person_dir, "person"), _discover(trip_dir, "trip")
    if set(person_files) != set(trip_files):
        raise ValueError("person and trip files cover different cities: "
                         f"person only {sorted(set(person_files) - set(trip_files))}, "
                         f"trip only {sorted(set(trip_files) - set(person_files))}")
    _check_area(meta["area"], set(person_files))
    resolved = releases_dir.resolve()
    location = PurePosixPath(resolved.parent.name, resolved.name, name).as_posix()

    final, partial = releases_dir / name, releases_dir / (name + ".partial")
    if final.exists():
        raise FileExistsError(f"{final} already exists; releases are never overwritten")
    if partial.exists():
        raise FileExistsError(f"{partial} is left over from an interrupted run; "
                              "inspect it and remove it by hand")
    releases_dir.mkdir(parents=True, exist_ok=True)
    partial.mkdir()
    try:
        _copy_into(partial, "person", person_files)
        _copy_into(partial, "trip", trip_files)
        persons, trips = _check_copies(partial, person_files, trip_files, meta["row_semantics"])
        sidecar = _sidecar(location, meta, len(person_files), len(trip_files), persons, trips)
        _write_text(partial / SIDECAR, json.dumps(sidecar, indent=2, ensure_ascii=False) + "\n")
        _write_manifest(partial)
        problems = verify_release(partial)
        if problems:
            raise ValueError("release failed verification: " + "; ".join(problems))
        _rename_with_retry(partial, final)
    except BaseException:
        _remove_partial(partial)
        raise
    return final


def _is_os_junk(name: str) -> bool:
    """Files an OS or file manager adds on its own (Finder, Explorer, AppleDouble)."""
    return name.lower() in OS_JUNK_NAMES or name.startswith("._")


def verify_release(release_dir: Path) -> list:
    """Problems found in a release folder against its MANIFEST.sha256; empty list = OK."""
    release_dir = Path(release_dir)
    manifest = release_dir / MANIFEST
    if not manifest.is_file():
        return [f"missing {MANIFEST}"]
    problems, listed = [], {}
    for n, line in enumerate(manifest.read_text(encoding="utf-8").splitlines(), 1):
        if not line.strip():
            continue
        digest, sep, rel = line.partition("  ")
        if not sep or not re.fullmatch(r"[0-9a-fA-F]{64}", digest) or not rel:
            problems.append(f"malformed manifest line {n}")
        elif rel.startswith("/") or "\\" in rel or ":" in rel or ".." in PurePosixPath(rel).parts:
            problems.append(f"unsafe manifest path: {rel}")
        elif rel in listed:
            problems.append(f"duplicate manifest entry: {rel}")
        else:
            listed[rel] = digest.lower()
    if SIDECAR not in listed:
        problems.append(f"manifest does not list {SIDECAR}")
    for rel, digest in sorted(listed.items()):
        f = release_dir / rel
        if not f.is_file():
            problems.append(f"missing file: {rel}")
        elif _sha256(f) != digest:
            problems.append(f"checksum mismatch: {rel}")
    actual = {p.relative_to(release_dir).as_posix() for p in release_dir.rglob("*")
              if p.is_file() and not _is_os_junk(p.name)}
    for rel in sorted(actual - set(listed) - {MANIFEST}):
        problems.append(f"unlisted file: {rel}")
    return problems


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description="Build an immutable, checksummed people release for gufm.")
    ap.add_argument("--person-dir", required=True, type=Path, help="dir holding person_<city>.csv")
    ap.add_argument("--trip-dir", required=True, type=Path, help="dir holding trip_<city>.csv")
    ap.add_argument("--releases", required=True, type=Path, help="releases root (not under output/)")
    ap.add_argument("--name", required=True, help="e.g. v3.1-gufm-20260709-tokyo62-s50")
    ap.add_argument("--meta", required=True, type=Path, help="JSON file with the required meta fields")
    args = ap.parse_args(argv)
    try:
        meta = json.loads(args.meta.read_text(encoding="utf-8-sig"))
        if not isinstance(meta, dict):
            raise ValueError(f"{args.meta} must hold a JSON object")
        release = build_release(args.person_dir, args.trip_dir, args.releases, args.name, meta)
    except (ValueError, OSError, csv.Error) as exc:
        print(f"refused: {exc}", file=sys.stderr)
        return 1
    print(release)
    return 0


if __name__ == "__main__":
    sys.exit(main())
