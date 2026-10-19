import json
import shutil

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
        "area": "tokyo62",
        "sample_pct": 50,
        "row_semantics": "v2_9col",
        "generating_code": "pseudo-pflow-v3.2-unified @ 31168a4",
        "entry_points": ["pseudo.gen.TripGenerator"],
        "run_window": "2026-07-09",
        "host": "ws",
        "produced_by": "North",
    }


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
    assert side["totals"] == {"person_files": 2, "trip_files": 2, "persons": 3, "trips": 3}
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

def _no_release(tmp_path):
    return list((tmp_path / "releases").glob("*")) == []


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
