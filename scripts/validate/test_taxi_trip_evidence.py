"""Regression checks for evidence derived from taxi exports, not reported scores."""
import csv
import hashlib
import importlib.util
import json
from pathlib import Path

import pytest

SPEC = importlib.util.spec_from_file_location(
    "taxi_trip_evidence", Path(__file__).with_name("taxi_trip_evidence.py"))
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


def row(**changes):
    result = dict(zip(MODULE.COLUMNS, [
        "1", "82800", "139.7", "35.6", "139.8", "35.7", "8", "3", "0",
        "true", "10", "4.00", "2000.00", "true", "23:00:00", "0"]))
    return result | changes


def write_csv(tmp_path, rows, columns=None):
    path = tmp_path / "trips.csv"
    with path.open("w", newline="", encoding="utf-8") as stream:
        writer = csv.DictWriter(stream, fieldnames=columns or MODULE.COLUMNS)
        writer.writeheader()
        writer.writerows(rows)
    return path


def test_counts_keep_calendar_days_distinct_from_shifts(tmp_path):
    path = write_csv(tmp_path, [row(), row(
        id="2", passenger_in="false", purpose="0", distance_km="6.00",
        fare_yen="0", starttime="3600", starttime_h="01:00:00", simulation_day="1")])
    result = MODULE.audit(path)
    assert result["rows"] == 2
    assert result["observed_taxis"] == 1
    assert result["observed_taxi_calendar_days"] == 2
    assert result["loaded_distance_fraction"] == pytest.approx(.4)
    assert result["occupied_trips"] == 1
    assert result["exported_fare_yen"] == 2000
    assert result["sha256"] and "active_fleet" not in result


@pytest.mark.parametrize("changes", [
    {"passenger_in": "1"}, {"is_night_trip": "TRUE"}, {"distance_km": "nan"},
    {"fare_yen": "inf"}, {"distance_km": "-1"}, {"fare_yen": "-1"},
    {"start_lon": "181"}, {"end_lat": "-91"}, {"start_lat": "nan"},
    {"starttime": "86400"}, {"starttime_h": "22:00:00"},
    {"simulation_day": "-1"}, {"id": "-1"}, {"taxi_id": "-1"},
    {"purpose": "0"}, {"transport_mode": "3"}, {"occupation": "1"},
    {"passenger_in": "false", "purpose": "0"},
])
def test_rejects_invalid_rows(tmp_path, changes):
    with pytest.raises(ValueError, match="row 2"):
        MODULE.audit(write_csv(tmp_path, [row(**changes)]))


def test_duplicate_id_across_midnight_rejected(tmp_path):
    with pytest.raises(ValueError, match="duplicate"):
        MODULE.audit(write_csv(tmp_path, [row(), row(id="01", simulation_day="1")]))


def test_empty_and_wrong_schema_rejected(tmp_path):
    with pytest.raises(ValueError, match="no trip rows"):
        MODULE.audit(write_csv(tmp_path, []))
    path = write_csv(tmp_path, [], columns=["id", "distance_km"])
    with pytest.raises(ValueError, match="schema"):
        MODULE.audit(path)


def test_zero_distance_is_undefined_fraction_and_extra_cell_rejected(tmp_path):
    path = write_csv(tmp_path, [row(distance_km="0")])
    assert MODULE.audit(path)["loaded_distance_fraction"] is None
    with path.open("a", encoding="utf-8") as stream:
        stream.write(",".join(row(id="2").values()) + ",unexpected\n")
    with pytest.raises(ValueError, match="row 3"):
        MODULE.audit(path)


def test_hash_uses_consumed_bytes_without_reopening(tmp_path, monkeypatch):
    path = write_csv(tmp_path, [row()])
    original = b"\xef\xbb\xbf" + path.read_bytes()
    path.write_bytes(original)
    real_open = Path.open
    opens = []

    def open_once(self, *args, **kwargs):
        opens.append(self)
        assert len(opens) == 1, "a second open could hash a different version"
        return real_open(self, *args, **kwargs)

    monkeypatch.setattr(Path, "open", open_once)
    assert MODULE.audit(path)["sha256"] == hashlib.sha256(original).hexdigest()


def test_cli_missing_file_is_machine_readable_error(tmp_path, monkeypatch, capsys):
    monkeypatch.setattr(MODULE.sys, "argv", ["audit", str(tmp_path / "missing.csv")])
    assert MODULE.main() == 2
    captured = capsys.readouterr()
    assert not captured.out
    assert "error" in json.loads(captured.err)


def test_cli_empty_only_export_has_no_occupied_mean(tmp_path, monkeypatch, capsys):
    path = write_csv(tmp_path, [row(passenger_in="false", purpose="0", fare_yen="0")])
    monkeypatch.setattr(MODULE.sys, "argv", ["audit", str(path)])
    assert MODULE.main() == 0
    result = json.loads(capsys.readouterr().out)
    assert result["mean_occupied_distance_km"] is None
    assert result["loaded_distance_fraction"] == 0


@pytest.mark.parametrize("contents", [b"", b"\xff", (
    ",".join(MODULE.COLUMNS) + "\n1,2\n").encode()])
def test_cli_malformed_input_returns_error(tmp_path, monkeypatch, capsys, contents):
    path = tmp_path / "invalid.csv"
    path.write_bytes(contents)
    monkeypatch.setattr(MODULE.sys, "argv", ["audit", str(path)])
    assert MODULE.main() == 2
    captured = capsys.readouterr()
    assert not captured.out and "error" in json.loads(captured.err)


def test_cli_serialization_failure_returns_error(monkeypatch, capsys):
    monkeypatch.setattr(MODULE.sys, "argv", ["audit", "unused.csv"])
    monkeypatch.setattr(MODULE, "audit", lambda path: {"value": float("nan")})
    assert MODULE.main() == 2
    assert "error" in json.loads(capsys.readouterr().err)
