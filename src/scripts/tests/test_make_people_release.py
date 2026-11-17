import hashlib
import json
import os
import shutil
import stat
import time
from pathlib import Path

import pytest

from make_people_release import (
    PERSON_COLUMNS,
    TRIP_COLUMNS,
    build_release,
    main,
    validate_name,
    verify_release,
)

NAME = "v3.1-gufm-20260709-tokyo62-s50"


def make_run(
    tmp_path,
    persons={"13101": [1, 2], "13102": [3]},
    trips={"13101": [1, 2], "13102": [3]},
    trip_cols=9,
    person_cols=9,
):
    """Write headerless person_<city>.csv (pid at index 5) and trip_<city>.csv (pid at index 0)."""
    person_dir, trip_dir = tmp_path / "run" / "person", tmp_path / "run" / "trip"
    person_dir.mkdir(parents=True)
    trip_dir.mkdir(parents=True)
    for city, pids in persons.items():
        rows = [[f"{city}_{pid}", 4, city, 139.7, 35.6, pid, 30, 1, 23] for pid in pids]
        rows = [r + ["x"] * (person_cols - 9) for r in rows]
        (person_dir / f"person_{city}.csv").write_text(
            "".join(",".join(map(str, r)) + "\n" for r in rows), encoding="utf-8")
    for city, pids in trips.items():
        rows = [[pid, 28800, 139.7, 35.6, 139.8, 35.7, 1, 2, 21] for pid in pids]
        rows = [r + [1] * (trip_cols - 9) for r in rows]
        (trip_dir / f"trip_{city}.csv").write_text(
            "".join(",".join(map(str, r)) + "\n" for r in rows), encoding="utf-8")
    return person_dir, trip_dir


def july_meta():
    return {
        "era": "v3.1-gufm-20260709",
        "area": ["13101", "13102"],
        "sample_pct": 50,
        "row_semantics": "v2_9col",
        "generating_code": "pseudo-pflow-v3.2-unified @ 31168a4",
        "entry_points": ["pseudo.gen.TripGenerator"],
        "run_window": "2026-07-09",
        "host": "ws",
        "produced_by": "North",
    }


def _no_release(tmp_path):
    return list((tmp_path / "releases").glob("*")) == []


def _snapshot(*dirs):
    """Every entry under dirs, with the sha256 of each file (None for folders)."""
    return {p: hashlib.sha256(p.read_bytes()).hexdigest() if p.is_file() else None
            for d in dirs for p in sorted(d.rglob("*"))}


def test_validate_name():
    validate_name("v3.1-gufm-20260709-tokyo62-s50")
    validate_name("v3.2-20261105-kanagawa-s10")
    for bad in ("tokyo62-july", "v3.2-20261105-kanagawa-m2", "v3.2-2026115-x-s10"):
        with pytest.raises(ValueError, match="release name"):
            validate_name(bad)


def test_build_release_copies_files_and_writes_sidecar(tmp_path):
    p, t = make_run(tmp_path)
    rel = build_release(p, t, tmp_path / "releases", NAME, july_meta())
    assert rel == tmp_path / "releases" / NAME
    assert (rel / "person/13/person_13101.csv").exists() and (rel / "trip/13/trip_13102.csv").exists()
    side = json.loads((rel / "release.json").read_text(encoding="utf-8"))
    assert side["lane"] == "cheap" and side["era"] == "v3.1-gufm-20260709"
    assert side["totals"] == {"person_files": 2, "trip_files": 2, "persons": 3, "trip_persons": 3, "trips": 3}
    assert side["columns"] == {"person": PERSON_COLUMNS, "trip": TRIP_COLUMNS["v2_9col"]}
    assert side["gates"]["pid_coverage"] == "PASS" and side["gates"]["columns"] == "PASS"
    assert verify_release(rel) == []


def test_refuses_existing_release(tmp_path):
    p, t = make_run(tmp_path)
    rel = build_release(p, t, tmp_path / "releases", NAME, july_meta())
    before = (rel / "release.json").read_bytes()
    with pytest.raises(FileExistsError):
        build_release(p, t, tmp_path / "releases", NAME, july_meta())
    assert (rel / "release.json").read_bytes() == before


def test_verify_detects_modified_file(tmp_path):
    p, t = make_run(tmp_path)
    rel = build_release(p, t, tmp_path / "releases", NAME, july_meta())
    with open(rel / "trip/13/trip_13101.csv", "a") as f:
        f.write("9,0,0,0,0,0,0,0,0\n")
    assert verify_release(rel) == ["checksum mismatch: trip/13/trip_13101.csv"]


def test_manifest_paths_are_relative_posix(tmp_path):
    p, t = make_run(tmp_path)
    rel = build_release(p, t, tmp_path / "releases", NAME, july_meta())
    paths = [line.split("  ", 1)[1] for line in (rel / "MANIFEST.sha256").read_text().splitlines()]
    assert paths and all("\\" not in x and ":" not in x and not x.startswith("/") for x in paths)
    assert "release.json" in paths


def test_refuses_column_count_mismatch(tmp_path):
    p, t = make_run(tmp_path, trip_cols=12)
    with pytest.raises(ValueError, match="trip_13101.csv has 12 columns, v2_9col expects 9"):
        build_release(p, t, tmp_path / "releases", NAME, july_meta())
    assert list((tmp_path / "releases").glob("*")) == []


def test_refuses_person_column_mismatch(tmp_path):
    p, t = make_run(tmp_path, person_cols=10)
    with pytest.raises(ValueError, match="person_13101.csv has 10 columns, expected 9"):
        build_release(p, t, tmp_path / "releases", NAME, july_meta())
    assert _no_release(tmp_path)


def test_refuses_missing_pids(tmp_path):
    p, t = make_run(tmp_path, trips={"13101": [1, 99], "13102": [3]})
    with pytest.raises(ValueError, match="13101: 1 trip pid"):
        build_release(p, t, tmp_path / "releases", NAME, july_meta())
    assert list((tmp_path / "releases").glob("*")) == []


def test_failed_copy_leaves_no_release(tmp_path, monkeypatch):
    p, t = make_run(tmp_path)
    real, calls = shutil.copy2, {"n": 0}

    def flaky(src, dst, *a, **k):
        calls["n"] += 1
        if calls["n"] == 2:
            raise OSError("No space left on device")
        return real(src, dst, *a, **k)

    monkeypatch.setattr(shutil, "copy2", flaky)
    with pytest.raises(OSError):
        build_release(p, t, tmp_path / "releases", NAME, july_meta())
    assert not (tmp_path / "releases" / NAME).exists()
    assert not (tmp_path / "releases" / (NAME + ".partial")).exists()


def test_refuses_releases_dir_under_output(tmp_path):
    p, t = make_run(tmp_path)
    with pytest.raises(ValueError, match="local-only"):
        build_release(p, t, tmp_path / "output" / "releases", NAME, july_meta())


def test_missing_required_meta_field(tmp_path):
    p, t = make_run(tmp_path)
    meta = july_meta()
    del meta["generating_code"]
    with pytest.raises(ValueError, match="generating_code"):
        build_release(p, t, tmp_path / "releases", NAME, meta)


def test_cli_round_trip(tmp_path, capsys):
    p, t = make_run(tmp_path)
    meta_path = tmp_path / "meta.json"
    meta_path.write_text(json.dumps(july_meta()))
    rc = main(["--person-dir", str(p), "--trip-dir", str(t), "--releases", str(tmp_path / "releases"),
               "--name", NAME, "--meta", str(meta_path)])
    assert rc == 0 and NAME in capsys.readouterr().out


# --- Beyond the brief's 12: data-safety refusals and edge cases of the same contract. ---

def test_refuses_truncated_last_row(tmp_path):
    p, t = make_run(tmp_path)
    with open(t / "trip_13101.csv", "a") as f:
        f.write("2,28800,139.7\n")
    with pytest.raises(ValueError, match=r"trip_13101.csv has 3 columns, v2_9col expects 9 \(line 3\)"):
        build_release(p, t, tmp_path / "releases", NAME, july_meta())
    assert _no_release(tmp_path)


def test_refuses_empty_file(tmp_path):
    p, t = make_run(tmp_path)
    (t / "trip_13102.csv").write_text("")
    with pytest.raises(ValueError, match="trip_13102.csv has no data rows"):
        build_release(p, t, tmp_path / "releases", NAME, july_meta())
    assert _no_release(tmp_path)


def test_refuses_city_without_trip_file(tmp_path):
    p, t = make_run(tmp_path, trips={"13101": [1, 2]})
    with pytest.raises(ValueError, match="different cities"):
        build_release(p, t, tmp_path / "releases", NAME, july_meta())
    assert _no_release(tmp_path)


@pytest.mark.parametrize("change, match", [
    ({"lane": "expensive"}, "computed by the tool"),
    ({"notes": "x"}, "unknown meta field"),
    ({"host": ""}, "host"),
    ({"row_semantics": "v9"}, "row_semantics"),
])
def test_refuses_bad_meta(tmp_path, change, match):
    p, t = make_run(tmp_path)
    with pytest.raises(ValueError, match=match):
        build_release(p, t, tmp_path / "releases", NAME, {**july_meta(), **change})
    assert _no_release(tmp_path)


def test_optional_meta_fields_pass_through(tmp_path):
    p, t = make_run(tmp_path)
    meta = {**july_meta(), "anomalies": ["13101 short"], "updates": []}
    rel = build_release(p, t, tmp_path / "releases", NAME, meta)
    side = json.loads((rel / "release.json").read_text(encoding="utf-8"))
    assert side["anomalies"] == ["13101 short"] and side["updates"] == []


def test_refuses_leftover_partial_without_touching_it(tmp_path):
    p, t = make_run(tmp_path)
    leftover = tmp_path / "releases" / (NAME + ".partial")
    leftover.mkdir(parents=True)
    (leftover / "keep.txt").write_text("x")
    with pytest.raises(FileExistsError, match="interrupted"):
        build_release(p, t, tmp_path / "releases", NAME, july_meta())
    assert (leftover / "keep.txt").exists()


def test_verify_reports_missing_and_unlisted_files(tmp_path):
    p, t = make_run(tmp_path)
    rel = build_release(p, t, tmp_path / "releases", NAME, july_meta())
    (rel / "person/13/person_13102.csv").unlink()
    (rel / "stray.txt").write_text("x")
    assert verify_release(rel) == ["missing file: person/13/person_13102.csv", "unlisted file: stray.txt"]


def test_verify_without_manifest(tmp_path):
    assert verify_release(tmp_path) == ["missing MANIFEST.sha256"]


def test_cli_refusal_exits_1(tmp_path, capsys):
    p, t = make_run(tmp_path, trip_cols=12)
    meta_path = tmp_path / "meta.json"
    meta_path.write_text(json.dumps(july_meta()))
    rc = main(["--person-dir", str(p), "--trip-dir", str(t), "--releases", str(tmp_path / "releases"),
               "--name", NAME, "--meta", str(meta_path)])
    assert rc == 1 and "refused" in capsys.readouterr().err


# --- Fix round 1: Windows failure paths, sidecar vs folder, source safety. ---

def _v3_meta():
    return {**july_meta(), "row_semantics": "v3_12col"}


def test_cleanup_removes_read_only_copies(tmp_path):
    # copy2 keeps the read-only bit; on Windows rmtree cannot delete such files without a chmod.
    p, t = make_run(tmp_path, trips={"13101": [1, 99], "13102": [3]})
    files = list(p.glob("*.csv")) + list(t.glob("*.csv"))
    for f in files:
        os.chmod(f, stat.S_IREAD)
    try:
        with pytest.raises(ValueError, match="13101: 1 trip pid"):
            build_release(p, t, tmp_path / "releases", NAME, july_meta())
    finally:
        for f in files:
            os.chmod(f, stat.S_IREAD | stat.S_IWRITE)
    assert not (tmp_path / "releases" / (NAME + ".partial")).exists()


def test_warns_when_partial_cannot_be_removed(tmp_path, monkeypatch, capsys):
    p, t = make_run(tmp_path, trip_cols=12)

    def locked(*args, **kwargs):
        raise OSError("file in use")

    monkeypatch.setattr(shutil, "rmtree", locked)
    with pytest.raises(ValueError, match="trip_13101.csv has 12 columns"):
        build_release(p, t, tmp_path / "releases", NAME, july_meta())
    partial = tmp_path / "releases" / (NAME + ".partial")
    assert partial.exists()
    assert str(partial) in capsys.readouterr().err


@pytest.mark.parametrize("failures, succeeds", [(2, True), (5, False)])
def test_final_rename_retries_on_permission_error(tmp_path, monkeypatch, failures, succeeds):
    p, t = make_run(tmp_path)
    real, calls, sleeps = Path.rename, {"n": 0}, []

    def flaky(self, target):
        calls["n"] += 1
        if calls["n"] <= failures:
            raise PermissionError(13, "Access is denied")
        return real(self, target)

    monkeypatch.setattr(Path, "rename", flaky)
    monkeypatch.setattr(time, "sleep", sleeps.append)
    releases = tmp_path / "releases"
    if succeeds:
        rel = build_release(p, t, releases, NAME, july_meta())
        assert rel.exists() and verify_release(rel) == []
        assert sleeps == [0.5, 1.0]
    else:
        with pytest.raises(PermissionError):
            build_release(p, t, releases, NAME, july_meta())
        assert calls["n"] == 5 and sleeps == [0.5, 1.0, 2.0, 4.0]
        assert not (releases / NAME).exists() and not (releases / (NAME + ".partial")).exists()


def test_refuses_area_that_does_not_match_source_cities(tmp_path):
    p, t = make_run(tmp_path)
    meta = {**july_meta(), "area": ["13101", "13999"]}
    with pytest.raises(ValueError, match=r"missing from area \['13102'\].*extra in area \['13999'\]"):
        build_release(p, t, tmp_path / "releases", NAME, meta)
    assert _no_release(tmp_path)


@pytest.mark.parametrize("area", ["13101", ["1310"], ["13101", "13101", "13102"], [13101, 13102]])
def test_refuses_malformed_area(tmp_path, area):
    p, t = make_run(tmp_path)
    with pytest.raises(ValueError, match="area"):
        build_release(p, t, tmp_path / "releases", NAME, {**july_meta(), "area": area})
    assert _no_release(tmp_path)


@pytest.mark.parametrize("change", [{"era": "v3.2-20261105"}, {"sample_pct": 10}])
def test_refuses_name_that_contradicts_meta(tmp_path, change):
    p, t = make_run(tmp_path)
    with pytest.raises(ValueError, match="release name"):
        build_release(p, t, tmp_path / "releases", NAME, {**july_meta(), **change})
    assert _no_release(tmp_path)


@pytest.mark.parametrize("pct, name", [
    (0, "v3.1-gufm-20260709-tokyo62-s0"),
    (101, "v3.1-gufm-20260709-tokyo62-s101"),
    (50.0, NAME),
    ("50", NAME),
    (True, NAME),
])
def test_refuses_sample_pct_that_is_not_an_int_in_1_to_100(tmp_path, pct, name):
    p, t = make_run(tmp_path)
    with pytest.raises(ValueError, match="sample_pct must be an integer from 1 to 100"):
        build_release(p, t, tmp_path / "releases", name, {**july_meta(), "sample_pct": pct})
    assert _no_release(tmp_path)


def test_location_names_the_releases_folder_given(tmp_path):
    p, t = make_run(tmp_path)
    rel = build_release(p, t, tmp_path / "releases", NAME, july_meta())
    side = json.loads((rel / "release.json").read_text(encoding="utf-8"))
    assert side["location"] == f"{tmp_path.name}/releases/{NAME}"
    assert side["area"] == ["13101", "13102"]


def test_refuses_releases_dir_inside_source_dir(tmp_path):
    p, t = make_run(tmp_path)
    before = _snapshot(p, t)
    for bad in (p / "releases", t):
        with pytest.raises(ValueError, match="inside source dir"):
            build_release(p, t, bad, NAME, july_meta())
    assert _snapshot(p, t) == before


def test_persons_total_is_sum_of_per_city_counts(tmp_path):
    p, t = make_run(tmp_path, persons={"13101": [1, 2], "13102": [1]}, trips={"13101": [1], "13102": [1]})
    rel = build_release(p, t, tmp_path / "releases", NAME, july_meta())
    side = json.loads((rel / "release.json").read_text(encoding="utf-8"))
    assert side["totals"]["persons"] == 3


def test_trip_persons_total_counts_distinct_trip_pids_per_city(tmp_path):
    # 13101: pid 1 has two trips, pid 2 has one; 13102: pid 3 has one -> 3 distinct trip pids
    p, t = make_run(tmp_path, trips={"13101": [1, 1, 2], "13102": [3]})
    rel = build_release(p, t, tmp_path / "releases", NAME, july_meta())
    side = json.loads((rel / "release.json").read_text(encoding="utf-8"))
    assert side["totals"]["trip_persons"] == 3
    assert side["totals"]["trips"] == 4


def test_trip_persons_is_less_than_persons_when_a_person_has_no_trip(tmp_path):
    # persons 1, 2, 3 and 4 exist; only 1 (13101) and 3 (13102) travel
    p, t = make_run(tmp_path, persons={"13101": [1, 2], "13102": [3, 4]},
                    trips={"13101": [1], "13102": [3]})
    rel = build_release(p, t, tmp_path / "releases", NAME, july_meta())
    totals = json.loads((rel / "release.json").read_text(encoding="utf-8"))["totals"]
    assert totals["persons"] == 4 and totals["trip_persons"] == 2
    assert totals["trip_persons"] < totals["persons"]


def test_verify_reports_duplicate_manifest_entry(tmp_path):
    p, t = make_run(tmp_path)
    rel = build_release(p, t, tmp_path / "releases", NAME, july_meta())
    first = (rel / "MANIFEST.sha256").read_text().splitlines()[0]
    with open(rel / "MANIFEST.sha256", "a", newline="\n") as f:
        f.write(first + "\n")
    assert verify_release(rel) == [f"duplicate manifest entry: {first.split('  ', 1)[1]}"]


@pytest.mark.parametrize("junk", [".DS_Store", "Thumbs.db", "desktop.ini", "._person_13101.csv"])
def test_verify_ignores_os_junk_files(tmp_path, junk):
    p, t = make_run(tmp_path)
    rel = build_release(p, t, tmp_path / "releases", NAME, july_meta())
    (rel / junk).write_text("x")
    (rel / "person" / "13" / junk).write_text("x")
    assert verify_release(rel) == []


def test_cli_refuses_unparsable_csv(tmp_path, capsys):
    p, t = make_run(tmp_path)
    with open(t / "trip_13101.csv", "a") as f:
        f.write("3," + "x" * 200_000 + "\n")  # larger than csv's default field limit
    meta_path = tmp_path / "meta.json"
    meta_path.write_text(json.dumps(july_meta()))
    rc = main(["--person-dir", str(p), "--trip-dir", str(t), "--releases", str(tmp_path / "releases"),
               "--name", NAME, "--meta", str(meta_path)])
    assert rc == 1 and "refused" in capsys.readouterr().err
    assert _no_release(tmp_path)


def test_builds_v3_12col_release(tmp_path):
    p, t = make_run(tmp_path, trip_cols=12)
    rel = build_release(p, t, tmp_path / "releases", NAME, _v3_meta())
    side = json.loads((rel / "release.json").read_text(encoding="utf-8"))
    assert side["row_semantics"] == "v3_12col" and side["columns"]["trip"] == TRIP_COLUMNS["v3_12col"]
    assert verify_release(rel) == []


def test_refuses_nine_column_trips_labelled_v3_12col(tmp_path):
    p, t = make_run(tmp_path)
    with pytest.raises(ValueError, match="trip_13101.csv has 9 columns, v3_12col expects 12"):
        build_release(p, t, tmp_path / "releases", NAME, _v3_meta())
    assert _no_release(tmp_path)


def test_manifest_and_sidecar_use_lf_only(tmp_path):
    p, t = make_run(tmp_path)
    rel = build_release(p, t, tmp_path / "releases", NAME, july_meta())
    assert b"\r" not in (rel / "MANIFEST.sha256").read_bytes()
    assert b"\r" not in (rel / "release.json").read_bytes()


def test_build_leaves_sources_untouched(tmp_path):
    p, t = make_run(tmp_path)
    before = _snapshot(p, t)
    build_release(p, t, tmp_path / "releases", NAME, july_meta())
    assert _snapshot(p, t) == before


# --- Fix round 2: sources that change mid-release, cleanup past a locked file, unhashable meta. ---

@pytest.mark.parametrize("how, after_copy", [
    ("append", 1),  # size and mtime change
    ("append-keep-mtime", 1),  # size only
    ("touch", 1),  # mtime only
    ("delete", 4),  # vanished after the last copy
])
def test_refuses_source_that_changes_during_copy(tmp_path, monkeypatch, how, after_copy):
    p, t = make_run(tmp_path)
    target = t / "trip_13101.csv"
    real, calls = shutil.copy2, {"n": 0}

    def copy_then_disturb(src, dst, *a, **k):
        result = real(src, dst, *a, **k)
        calls["n"] += 1
        if calls["n"] == after_copy:
            before = target.stat()
            if how == "delete":
                target.unlink()
            else:
                if how.startswith("append"):
                    with open(target, "a") as f:
                        f.write("1,0,0,0,0,0,0,0,0\n")
                bump = 0 if how == "append-keep-mtime" else 2_000_000_000
                os.utime(target, ns=(before.st_atime_ns, before.st_mtime_ns + bump))
        return result

    monkeypatch.setattr(shutil, "copy2", copy_then_disturb)
    with pytest.raises(ValueError, match="source changed during release: trip_13101.csv"):
        build_release(p, t, tmp_path / "releases", NAME, july_meta())
    assert _no_release(tmp_path)


def test_cleanup_continues_past_a_locked_file(tmp_path, monkeypatch, capsys):
    p, t = make_run(tmp_path, trip_cols=12)
    real_unlink = os.unlink

    def unlink(path, *a, **k):
        if str(path).endswith("trip_13101.csv"):
            raise PermissionError(13, "Access is denied")
        return real_unlink(path, *a, **k)

    monkeypatch.setattr(os, "unlink", unlink)
    with pytest.raises(ValueError, match="trip_13101.csv has 12 columns"):
        build_release(p, t, tmp_path / "releases", NAME, july_meta())
    partial = tmp_path / "releases" / (NAME + ".partial")
    assert (partial / "trip/13/trip_13101.csv").exists()  # the locked one stays
    assert not (partial / "trip/13/trip_13102.csv").exists()  # the walk went on
    assert not (partial / "person").exists()
    assert str(partial) in capsys.readouterr().err


@pytest.mark.parametrize("change", [
    {"area": [["13101"]]},
    {"area": [{"code": "13101"}]},
    {"row_semantics": ["v2_9col"]},
    {"row_semantics": {"v2_9col": 1}},
])
def test_cli_refuses_unhashable_meta_values(tmp_path, capsys, change):
    p, t = make_run(tmp_path)
    meta_path = tmp_path / "meta.json"
    meta_path.write_text(json.dumps({**july_meta(), **change}))
    rc = main(["--person-dir", str(p), "--trip-dir", str(t), "--releases", str(tmp_path / "releases"),
               "--name", NAME, "--meta", str(meta_path)])
    assert rc == 1 and "refused" in capsys.readouterr().err
    assert _no_release(tmp_path)


# --- row_semantics content gate (TASK-15): column count cannot tell v3_12col from v3_3_12col ---

def _write_trips(trip_dir, city, rows):
    (trip_dir / f"trip_{city}.csv").write_text(
        "".join(",".join(map(str, r)) + "\n" for r in rows), encoding="utf-8")


def _v33_rows(pids):
    """v3.3 rows: 9 contract columns + trip_id (pid*1000+seq), arr_time, duration."""
    rows = []
    for pid in pids:
        rows.append([pid, 28800, 139.7, 35.6, 139.8, 35.7, 4, 2, 21, pid * 1000 + 1, 30600, 32400])
        rows.append([pid, 61200, 139.8, 35.7, 139.7, 35.6, 4, 1, 21, pid * 1000 + 2, 63000, 25200])
    return rows


def _v33_run(tmp_path):
    p, t = make_run(tmp_path, trip_cols=12)
    _write_trips(t, "13101", _v33_rows([1, 2]))
    _write_trips(t, "13102", _v33_rows([3]))
    return p, t


def _v33_meta():
    return {**july_meta(), "row_semantics": "v3_3_12col"}


def test_v3_3_12col_columns_end_with_trip_id_arr_time_duration():
    assert TRIP_COLUMNS["v3_3_12col"][:9] == TRIP_COLUMNS["v2_9col"]
    assert TRIP_COLUMNS["v3_3_12col"][9:] == ["trip_id", "arr_time", "duration"]


def test_builds_v3_3_12col_release(tmp_path):
    p, t = _v33_run(tmp_path)
    rel = build_release(p, t, tmp_path / "releases", NAME, _v33_meta())
    side = json.loads((rel / "release.json").read_text(encoding="utf-8"))
    assert side["row_semantics"] == "v3_3_12col"
    assert side["columns"]["trip"] == TRIP_COLUMNS["v3_3_12col"]
    assert side["totals"]["trips"] == 6 and side["gates"]["row_semantics"] == "PASS"
    assert verify_release(rel) == []


def test_v3_12col_release_records_row_semantics_gate(tmp_path):
    p, t = make_run(tmp_path, trip_cols=12)
    rel = build_release(p, t, tmp_path / "releases", NAME, _v3_meta())
    side = json.loads((rel / "release.json").read_text(encoding="utf-8"))
    assert side["gates"]["row_semantics"] == "PASS"


def test_v2_9col_release_has_no_row_content_rule(tmp_path):
    p, t = make_run(tmp_path)
    rel = build_release(p, t, tmp_path / "releases", NAME, july_meta())
    side = json.loads((rel / "release.json").read_text(encoding="utf-8"))
    assert side["gates"]["row_semantics"] == "n/a"


def test_refuses_v3_3_rows_labelled_v3_12col(tmp_path):
    p, t = _v33_run(tmp_path)
    with pytest.raises(ValueError, match=r"trip_13101.csv line 1: subtrip_id '30600' is not a leg index 0-9"):
        build_release(p, t, tmp_path / "releases", NAME, _v3_meta())
    assert _no_release(tmp_path)


def test_refuses_v3_12col_rows_labelled_v3_3_12col(tmp_path):
    p, t = make_run(tmp_path, trip_cols=12)  # trip_id 1, subtrip_id 1, rep_mode 1
    with pytest.raises(ValueError, match=r"trip_13101.csv line 1: trip_id 1 is not person_id 1 x 1000 \+ seq"):
        build_release(p, t, tmp_path / "releases", NAME, _v33_meta())
    assert _no_release(tmp_path)


@pytest.mark.parametrize("bad, match", [
    ({10: 10}, r"subtrip_id '10' is not a leg index 0-9"),
    ({11: 8}, r"rep_mode '8' is not a transport code 0-7"),
    ({11: 86400}, r"rep_mode '86400' is not a transport code 0-7"),
])
def test_v3_12col_gate_checks_subtrip_and_rep_mode(tmp_path, bad, match):
    p, t = make_run(tmp_path, trip_cols=12)
    row = [1, 28800, 139.7, 35.6, 139.8, 35.7, 1, 2, 21, 1, 0, 1]
    for i, v in bad.items():
        row[i] = v
    _write_trips(t, "13101", [[1, 28800, 139.7, 35.6, 139.8, 35.7, 1, 2, 21, 1, 0, 1], row])
    with pytest.raises(ValueError, match="line 2: " + match):
        build_release(p, t, tmp_path / "releases", NAME, _v3_meta())
    assert _no_release(tmp_path)


@pytest.mark.parametrize("trip_id, match", [
    (2000, r"trip_id 2000 is not person_id 2 x 1000 \+ seq"),   # seq 0
    (3001, r"trip_id 3001 is not person_id 2 x 1000 \+ seq"),   # another person's id
    ("2x01", r"trip_id '2x01' is not a plain non-negative integer"),
    ("+2001", r"trip_id '\+2001' is not a plain non-negative integer"),
    ("2_001", r"trip_id '2_001' is not a plain non-negative integer"),
    (" 2001", r"trip_id ' 2001' is not a plain non-negative integer"),
    ("2001.0", r"trip_id '2001.0' is not a plain non-negative integer"),
])
def test_v3_3_gate_checks_trip_id_encoding(tmp_path, trip_id, match):
    p, t = _v33_run(tmp_path)
    rows = _v33_rows([1, 2])
    rows[3][9] = trip_id  # person 2, second trip
    _write_trips(t, "13101", rows)
    with pytest.raises(ValueError, match="trip_13101.csv line 4: " + match):
        build_release(p, t, tmp_path / "releases", NAME, _v33_meta())
    assert _no_release(tmp_path)


def test_v3_3_gate_refuses_duplicate_trip_id(tmp_path):
    p, t = _v33_run(tmp_path)
    rows = _v33_rows([1, 2])
    rows[1][9] = rows[0][9]
    _write_trips(t, "13101", rows)
    with pytest.raises(ValueError, match=r"trip_13101.csv line 2: duplicate trip_id 1001") as err:
        build_release(p, t, tmp_path / "releases", NAME, _v33_meta())
    assert "not v3_3_12col rows" not in str(err.value)  # the layout is right; only the id repeats
    assert _no_release(tmp_path)


def test_v3_12col_accepts_web_api_routes_with_five_legs(tmp_path):
    # TripGenerator_WebAPI_refactor numbers every emitted leg: walk, bus, walk, train, walk = 0-4
    p, t = make_run(tmp_path, trip_cols=12)
    legs = [[1, 28800 + 60 * s, 139.7, 35.6, 139.8, 35.7, m, 2, 21, 1, s, 4] for s, m in enumerate([1, 5, 1, 4, 1])]
    _write_trips(t, "13101", legs + [[2, 28800, 139.7, 35.6, 139.8, 35.7, 1, 2, 21, 1, 0, 1]])
    rel = build_release(p, t, tmp_path / "releases", NAME, _v3_meta())
    assert json.loads((rel / "release.json").read_text(encoding="utf-8"))["totals"]["trips"] == 7


def test_v3_3_gate_refuses_a_v32_row_whose_trip_id_happens_to_fit(tmp_path):
    # Codex review: a v3.2 row with trip_id 1001 for pid 1 fits pid*1000+seq; its subtrip_id 0
    # read as arr_time is before the departure, which a v3.3 row never is.
    p, t = _v33_run(tmp_path)
    rows = _v33_rows([1, 2])
    rows[0] = [1, 28800, 139.7, 35.6, 139.8, 35.7, 4, 2, 21, 1001, 0, 4]
    _write_trips(t, "13101", rows)
    with pytest.raises(ValueError, match=r"trip_13101.csv line 1: arr_time 0 is before dep_time 28800"):
        build_release(p, t, tmp_path / "releases", NAME, _v33_meta())
    assert _no_release(tmp_path)


@pytest.mark.parametrize("col, value", [(10, "oops"), (11, "-1"), (1, "28800.0")])
def test_v3_3_gate_refuses_malformed_times(tmp_path, col, value):
    p, t = _v33_run(tmp_path)
    rows = _v33_rows([1, 2])
    rows[2][col] = value
    _write_trips(t, "13101", rows)
    with pytest.raises(ValueError, match=r"trip_13101.csv line 3: .* is not a plain (non-negative )?integer"):
        build_release(p, t, tmp_path / "releases", NAME, _v33_meta())
    assert _no_release(tmp_path)


def test_v3_3_stay_home_row_passes(tmp_path):
    p, t = _v33_run(tmp_path)
    _write_trips(t, "13102", [[3, 0, 139.7, 35.6, 139.7, 35.6, 0, 1, 21, 3001, 0, 86400]])
    build_release(p, t, tmp_path / "releases", NAME, _v33_meta())


# --- per-person chain (Codex re-check): a single row cannot always be told apart, a person's rows can ---

def _refuse_v33(tmp_path, rows_13101, match):
    p, t = _v33_run(tmp_path)
    _write_trips(t, "13101", rows_13101)
    with pytest.raises(ValueError, match=match):
        build_release(p, t, tmp_path / "releases", NAME, _v33_meta())
    assert _no_release(tmp_path)


def test_v3_3_gate_refuses_a_midnight_v32_row_that_fits_every_row_rule(tmp_path):
    # pid 1, dep 0, trip_id 1001, subtrip_id 0 read as arr_time 0, rep_mode 0 read as duration 0
    rows = [[1, 0, 139.7, 35.6, 139.7, 35.6, 0, 1, 21, 1001, 0, 0]] + _v33_rows([2])
    _refuse_v33(tmp_path, rows, r"line 2: person 1's last trip \(seq 1\) has duration 0, not 86400")


def test_v3_3_gate_checks_the_last_row_of_the_file(tmp_path):
    rows = _v33_rows([1, 2])
    rows[-1][11] = 25000
    _refuse_v33(tmp_path, rows, r"trip_13101.csv at end of file: person 2's last trip \(seq 2\) has duration 25000, not 25200")


def test_v3_3_gate_refuses_a_gap_in_a_persons_trip_numbers(tmp_path):
    rows = _v33_rows([1, 2])
    rows[1][9] = 1003
    _refuse_v33(tmp_path, rows, r"line 2: trip_id 1003 follows seq 1")


def test_v3_3_gate_refuses_a_first_trip_that_is_not_seq_1(tmp_path):
    rows = _v33_rows([1, 2])
    rows[2][9], rows[3][9] = 2002, 2003
    _refuse_v33(tmp_path, rows, r"line 3: person 2's first trip has seq 2")


def test_v3_3_gate_refuses_a_duration_that_is_not_the_gap_to_the_next_departure(tmp_path):
    rows = _v33_rows([1, 2])
    rows[0][11] = 30000
    _refuse_v33(tmp_path, rows, r"line 2: the row before has duration 30000, not the gap 32400")


# --- negative departures (TASK-20): a long commute to an early activity leaves before midnight ---------

# The row run_20261117_221700_chiyoda refused (trip_13101.csv line 814, person 38899): 13101 to 10202 by car,
# 4.9 h, for an activity at 03:45, so it departs at -4036 s (22:52 the evening before). Release #1 holds 214
# such rows; the generator writes them, and validate_trip.py only warns.
def test_v3_3_gate_accepts_a_negative_departure(tmp_path):
    p, t = _v33_run(tmp_path)
    rows = [[1, -4036, 139.741256, 35.692294, 139.001886, 36.332661, 3, 2, 21, 1001, 13500, 90436]] + _v33_rows([2])
    _write_trips(t, "13101", rows)
    rel = build_release(p, t, tmp_path / "releases", NAME, _v33_meta())
    side = json.loads((rel / "release.json").read_text(encoding="utf-8"))
    assert side["gates"]["row_semantics"] == "PASS"
    assert verify_release(rel) == []


def test_v3_3_gate_accepts_a_trip_that_departs_and_arrives_before_midnight(tmp_path):
    p, t = _v33_run(tmp_path)
    rows = [[1, -900, 139.7, 35.6, 139.8, 35.7, 1, 2, 21, 1001, -100, 87300]] + _v33_rows([2])
    _write_trips(t, "13101", rows)
    build_release(p, t, tmp_path / "releases", NAME, _v33_meta())


@pytest.mark.parametrize("col, value", [(1, "+4036"), (1, "--4036"), (1, "-"), (10, "-13500.0")])
def test_v3_3_gate_still_refuses_malformed_signed_times(tmp_path, col, value):
    p, t = _v33_run(tmp_path)
    rows = _v33_rows([1, 2])
    rows[0][col] = value
    _write_trips(t, "13101", rows)
    with pytest.raises(ValueError, match=r"trip_13101.csv line 1: .* is not a plain integer"):
        build_release(p, t, tmp_path / "releases", NAME, _v33_meta())
    assert _no_release(tmp_path)


@pytest.mark.parametrize("col, name", [(0, "pid"), (9, "trip_id"), (11, "duration")])
def test_v3_3_gate_still_refuses_a_sign_on_ids_and_duration(tmp_path, col, name):
    p, t = _v33_run(tmp_path)
    rows = _v33_rows([1, 2])
    rows[0][col] = "-" + str(rows[0][col])
    _write_trips(t, "13101", rows)
    with pytest.raises(ValueError, match=rf"trip_13101.csv line 1: {name} '-\d+' is not a plain non-negative integer"):
        build_release(p, t, tmp_path / "releases", NAME, _v33_meta())
    assert _no_release(tmp_path)


def test_v3_3_gate_refuses_an_arrival_before_a_negative_departure(tmp_path):
    p, t = _v33_run(tmp_path)
    rows = [[1, -100, 139.7, 35.6, 139.8, 35.7, 1, 2, 21, 1001, -200, 86500]] + _v33_rows([2])
    _write_trips(t, "13101", rows)
    with pytest.raises(ValueError, match=r"trip_13101.csv line 1: arr_time -200 is before dep_time -100"):
        build_release(p, t, tmp_path / "releases", NAME, _v33_meta())
    assert _no_release(tmp_path)


def test_v3_3_chain_works_across_midnight(tmp_path):
    # Person 1 leaves at -600 (23:50 the evening before), then at 28800; the first duration spans midnight.
    p, t = _v33_run(tmp_path)
    rows = [[1, -600, 139.7, 35.6, 139.8, 35.7, 3, 2, 21, 1001, 300, 29400],
            [1, 28800, 139.8, 35.7, 139.7, 35.6, 3, 1, 21, 1002, 30600, 57600]] + _v33_rows([2])
    _write_trips(t, "13101", rows)
    rel = build_release(p, t, tmp_path / "releases", NAME, _v33_meta())
    assert verify_release(rel) == []


def test_v3_3_chain_refuses_a_wrong_duration_across_midnight(tmp_path):
    p, t = _v33_run(tmp_path)
    rows = [[1, -600, 139.7, 35.6, 139.8, 35.7, 3, 2, 21, 1001, 300, 28800],  # should be 29400
            [1, 28800, 139.8, 35.7, 139.7, 35.6, 3, 1, 21, 1002, 30600, 57600]] + _v33_rows([2])
    _write_trips(t, "13101", rows)
    with pytest.raises(ValueError, match=r"line 2: the row before has duration 28800, not the gap 29400"):
        build_release(p, t, tmp_path / "releases", NAME, _v33_meta())


@pytest.mark.parametrize("value", ["−4036", " -4036", "-4036 "])
def test_v3_3_gate_refuses_a_unicode_minus_and_whitespace(tmp_path, value):
    p, t = _v33_run(tmp_path)
    rows = _v33_rows([1, 2])
    rows[0][1] = value
    _write_trips(t, "13101", rows)
    with pytest.raises(ValueError, match=r"trip_13101.csv line 1: dep_time .* is not a plain integer"):
        build_release(p, t, tmp_path / "releases", NAME, _v33_meta())
