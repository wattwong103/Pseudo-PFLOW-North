#!/usr/bin/env python3
"""
merge_activity.py -- parameterized replacement for Merge_ActivityData.ipynb.

Concatenates the three per-city activity generator outputs
    activity/<pref>/person_<pref><city>_{labor,nolabor,student}.csv
into a single merged file
    activity_merged/<pref>/person_<pref><city>.csv

WHY THIS EXISTS
  The people-flow .bat (scripts/run_pseudo_pflow_tokyo.bat) runs
  Commuter -> Student -> NonCommuter -> TripGenerator with NO merge in between.
  TripGenerator (src/pseudo/gen/TripGenerator.java:282-300) does NOT concatenate
  the three activity files itself -- it just iterates whatever CSVs already exist
  in activity_merged/<pref>/. If activities are regenerated at a new mfactor but
  activity_merged is not rebuilt, TripGenerator silently consumes the OLD merged
  data. This script rebuilds activity_merged and must run between step 3 and step 4.

OUTPUT NAMING (critical)
  Files MUST be named person_<pref><city>.csv (person_-prefixed). TripGenerator
  recovers the 5-digit city code via file.getName().substring(7,12) and writes
  trip_<city>.csv from it. "person_13101.csv".substring(7,12) == "13101".
  (The old notebook wrote activity_<city>.csv, which would break that contract.)

FORMAT
  All activity CSVs are headerless, 10 columns:
    pid, age, gender, occupation, starttime, duration, activity_type, lon, lat, city_code
  The merge is a plain row concatenation (labor + nolabor + student), so this is
  pure-stdlib -- no pandas required.

USAGE
  python merge_activity.py [--pref 13] [--pflow-home PATH] [--min-bytes 137]
  Env: PFLOW_HOME (default: two levels up from this repo, or D:/Dropbox/PFLOW)
"""

import argparse
import os
import re
import sys

PART_SUFFIXES = ("_labor.csv", "_nolabor.csv", "_student.csv")
# person_<5-digit-city>_<part>.csv  ->  capture the 5-digit city code
CITY_RE = re.compile(r"^person_(\d{5})_(?:labor|nolabor|student)\.csv$")


def default_pflow_home():
    env = os.environ.get("PFLOW_HOME")
    if env:
        return env
    # this file lives at <PFLOW_HOME>/Pseudo-PFLOW/src/scripts/merge_activity.py
    here = os.path.abspath(os.path.dirname(__file__))
    guess = os.path.abspath(os.path.join(here, "..", "..", ".."))
    if os.path.isdir(os.path.join(guess, "data", "census", "person")):
        return guess
    return "D:/Dropbox/PFLOW"


def merge_pref(pflow_home, pref, min_bytes):
    base = os.path.join(pflow_home, "data", "census", "person")
    act_dir = os.path.join(base, "activity", str(pref))
    out_dir = os.path.join(base, "activity_merged", str(pref))

    if not os.path.isdir(act_dir):
        sys.exit(f"ERROR: activity dir not found: {act_dir}")
    os.makedirs(out_dir, exist_ok=True)

    # Clean slate: remove stale merged files for this pref so no 2%-era file survives
    # for a city that ends up skipped this round.
    removed = 0
    for f in os.listdir(out_dir):
        if f.startswith("person_") and f.endswith(".csv"):
            os.remove(os.path.join(out_dir, f))
            removed += 1
    print(f"[{pref}] removed {removed} stale merged file(s) from {out_dir}")

    # Discover city codes from the _labor.csv files present.
    cities = set()
    for f in os.listdir(act_dir):
        m = CITY_RE.match(f)
        if m:
            cities.add(m.group(1))

    if not cities:
        sys.exit(f"ERROR: no person_<city>_{{labor,nolabor,student}}.csv found in {act_dir}")

    written, skipped = 0, []
    for city in sorted(cities):
        parts = [os.path.join(act_dir, f"person_{city}{sfx}") for sfx in PART_SUFFIXES]

        missing = [p for p in parts if not os.path.exists(p)]
        if missing:
            skipped.append((city, "missing " + ", ".join(os.path.basename(m) for m in missing)))
            continue

        too_small = [os.path.basename(p) for p in parts if os.path.getsize(p) < min_bytes]
        if too_small:
            skipped.append((city, f"< {min_bytes} bytes: " + ", ".join(too_small)))
            continue

        out_path = os.path.join(out_dir, f"person_{city}.csv")
        total_lines = 0
        with open(out_path, "w", encoding="utf-8", newline="") as out:
            for p in parts:
                with open(p, "r", encoding="utf-8", newline="") as inp:
                    for line in inp:
                        out.write(line)
                        if line.strip():
                            total_lines += 1
        written += 1
        print(f"[{pref}] person_{city}.csv <- labor+nolabor+student ({total_lines} rows)")

    print(f"\n[{pref}] DONE: wrote {written} merged file(s) to {out_dir}")
    if skipped:
        print(f"[{pref}] SKIPPED {len(skipped)} city(ies) (NOT silently -- listed below):")
        for city, why in skipped:
            print(f"    - {city}: {why}")


def main():
    ap = argparse.ArgumentParser(description="Merge activity generator outputs into activity_merged/")
    ap.add_argument("--pref", default="13", help="prefecture code (default: 13 = Tokyo)")
    ap.add_argument("--pflow-home", default=None, help="PFLOW repo root (default: env PFLOW_HOME or auto)")
    ap.add_argument("--min-bytes", type=int, default=137,
                    help="skip a city if any of its 3 activity files is smaller than this (default: 137)")
    args = ap.parse_args()

    pflow_home = args.pflow_home or default_pflow_home()
    print(f"PFLOW_HOME = {pflow_home}")
    merge_pref(pflow_home, args.pref, args.min_bytes)


if __name__ == "__main__":
    main()
