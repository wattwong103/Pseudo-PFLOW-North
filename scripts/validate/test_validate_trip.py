"""validate_trip on v3.3 12-column rows (TASK-15, Codex review: malformed fields were skipped silently)."""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from validate_trip import validate_file  # noqa: E402

GOOD = "1,28800,139.7,35.6,139.8,35.7,4,2,21,1001,30600,32400"


def _run(tmp_path, *lines):
    f = tmp_path / "trip_13101.csv"
    f.write_text("".join(line + "\n" for line in lines), encoding="utf-8")
    return validate_file(str(f))


def test_good_v33_row_has_no_field_errors(tmp_path):
    result = _run(tmp_path, GOOD)
    assert not [e for e in result["errors"] if "malformed" in e or "arr_time" in e or "duration" in e]


def test_malformed_v33_fields_are_errors(tmp_path):
    result = _run(tmp_path, GOOD, "1,61200,139.8,35.7,139.7,35.6,4,1,21,1002,oops,-1")
    assert any("1 rows with malformed trip_id/arr_time/duration" in e for e in result["errors"]), result["errors"]
    assert result["status"] == "FAIL"


def test_negative_duration_is_reported_even_when_arr_time_parses(tmp_path):
    result = _run(tmp_path, GOOD, "1,61200,139.8,35.7,139.7,35.6,4,1,21,1002,63000,-1")
    assert any("negative duration" in e for e in result["errors"]), result["errors"]
