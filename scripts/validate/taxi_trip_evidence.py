"""Audit taxi exports independently; successful inspection is not calibration."""
import argparse
import codecs
import csv
import hashlib
import json
import math
import sys
from collections import Counter
from collections.abc import Iterator
from pathlib import Path

COLUMNS = (
    "id", "starttime", "start_lon", "start_lat", "end_lon", "end_lat",
    "transport_mode", "purpose", "occupation", "passenger_in", "taxi_id",
    "distance_km", "fare_yen", "is_night_trip", "starttime_h", "simulation_day",
)


def nonnegative_integer(value: str) -> int:
    number = int(value)
    if number < 0:
        raise ValueError("negative identifier, day or time")
    return number


def finite_number(value: str, low: float, high: float) -> float:
    number = float(value)
    if not math.isfinite(number) or not low <= number <= high:
        raise ValueError("non-finite or out-of-range numeric value")
    return number


def audit(path: Path) -> dict[str, object]:
    """Read each row once, retaining IDs for uniqueness and observation counts.

    Calendar-day taxi observations are not shift counts. No fleet denominator,
    city boundary, tax conversion or calibration target is inferred here.
    """
    ids: set[int] = set()
    taxis: set[int] = set()
    taxi_days: set[tuple[int, int]] = set()
    daily: Counter = Counter()
    occupied = 0
    loaded_km = empty_km = fare_yen = 0.0
    checksum = hashlib.sha256()
    with path.open("rb") as stream:
        def hashed_lines() -> Iterator[bytes]:
            for raw_line in stream:
                checksum.update(raw_line)
                yield raw_line

        reader = csv.DictReader(codecs.iterdecode(hashed_lines(), "utf-8-sig"))
        if tuple(reader.fieldnames or ()) != COLUMNS:
            raise ValueError("unexpected taxi CSV schema (requires 16 named columns)")
        for line, row in enumerate(reader, start=2):
            try:
                if None in row or any(value is None for value in row.values()):
                    raise ValueError("wrong column count")
                trip = nonnegative_integer(row["id"])
                taxi = nonnegative_integer(row["taxi_id"])
                day = nonnegative_integer(row["simulation_day"])
                second = nonnegative_integer(row["starttime"])
                if trip in ids:
                    raise ValueError("duplicate trip id")
                for key in ("passenger_in", "is_night_trip"):
                    if row[key] not in ("true", "false"):
                        raise ValueError(f"invalid boolean: {key}")
                loaded = row["passenger_in"] == "true"
                if (row["transport_mode"], row["occupation"], row["purpose"]) != (
                        "8", "0", "3" if loaded else "0"):
                    raise ValueError("mode/occupation/purpose contradict taxi occupancy")
                expected_time = f"{second // 3600:02}:{second // 60 % 60:02}:{second % 60:02}"
                if second >= 86400 or row["starttime_h"] != expected_time:
                    raise ValueError("inconsistent normalized pickup time")
                for key in ("start_lon", "end_lon"):
                    finite_number(row[key], -180, 180)
                for key in ("start_lat", "end_lat"):
                    finite_number(row[key], -90, 90)
                km = finite_number(row["distance_km"], 0, math.inf)
                fare = finite_number(row["fare_yen"], 0, math.inf)
                if not loaded and fare != 0:
                    raise ValueError("empty trip has nonzero fare")
            except (ValueError, TypeError) as exc:
                raise ValueError(f"row {line}: {exc}") from exc
            ids.add(trip)
            taxis.add(taxi)
            taxi_days.add((taxi, day))
            daily[day] += 1
            occupied += loaded
            loaded_km += km if loaded else 0
            empty_km += 0 if loaded else km
            fare_yen += fare
    if not ids:
        raise ValueError("no trip rows")
    total_km = loaded_km + empty_km
    if not all(math.isfinite(value) for value in (total_km, fare_yen)):
        raise ValueError("numeric overflow in totals")
    return {
        "schema": "taxi-trip-evidence-v1", "input": str(path.resolve()),
        "sha256": checksum.hexdigest(), "rows": len(ids), "occupied_trips": occupied,
        "empty_trips": len(ids) - occupied, "observed_taxis": len(taxis),
        "observed_taxi_calendar_days": len(taxi_days),
        "rows_by_simulation_day": dict(sorted(daily.items())),
        "occupied_distance_km": round(loaded_km, 6),
        "empty_distance_km": round(empty_km, 6),
        "loaded_distance_fraction": loaded_km / total_km if total_km else None,
        "mean_occupied_distance_km": loaded_km / occupied if occupied else None,
        "exported_fare_yen": round(fare_yen, 6),
        "limitations": [
            "Observed taxis exclude vehicles with no exported rows.",
            "Calendar days can split a single overnight shift; these are not vehicle-days worked.",
            "Distance is exported modeled distance; fare tax basis is not established by this file.",
            "Coordinate ranges alone do not establish land, road or city-boundary validity.",
            "Inspection does not establish source revision, input identity or calibration agreement.",
        ],
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("trips", type=Path)
    args = parser.parse_args()
    try:
        result = audit(args.trips)
        encoded = json.dumps(result, indent=2, ensure_ascii=True, allow_nan=False)
    except (OSError, ValueError, csv.Error) as exc:
        print(json.dumps({"error": str(exc)}, ensure_ascii=True), file=sys.stderr)
        return 2
    print(encoded)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
