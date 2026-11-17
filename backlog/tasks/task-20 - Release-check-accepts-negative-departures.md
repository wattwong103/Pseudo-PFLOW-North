---
id: TASK-20
title: >-
  People flow: the v3_3_12col release check accepts a trip that departs (or arrives) before midnight
status: In Progress
assignee: []
created_date: '2026-11-17 00:00'
labels:
  - people-flow
  - gufm
dependencies:
  - TASK-15
priority: high
ordinal: 20000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
**Defect (found 2026-11-17 by the post-merge runbook check at `821af5b`).** The `v3_3_12col` row check from TASK-15
(#20) requires digit-only `dep_time` and `arr_time`, so it refused real generator output.

In `output/pseudo/run_20261117_221700_chiyoda`, `trip_13101.csv` line 814 is person 38899:
- home 13101, work 10202 (about 95 km);
- a car trip of 4.9 h for an activity at 03:45;
- TripGenerator sets departure = activity start minus travel time, so departure = -4036 s (22:52 the evening before).

Such rows are established data:
- release #1 (`v3.1-gufm-20260709-tokyo62-s50`) holds 214 of 13,601,992;
- `scripts/validate/validate_trip.py` treats a departure outside 0..172800 as a warning.

**Fix (North, 2026-11-17: "Yes, fix it").** `dep_time` and `arr_time` take an optional leading `-`. pid, trip_id and
duration stay digit-only. The arrival >= departure rule and the per-person chain are unchanged; their arithmetic already
handles negative times. `PersonAccessor.loadTrips` already reads the row; a test pins that.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 The real row (and a trip departing and arriving before midnight) releases; `+4036`, `--4036`, `-` and
  `-13500.0` are still refused; a sign on pid, trip_id or duration is still refused. The acceptance tests fail before.
- [ ] #2 A Java test pins that `loadTrips` reads the real row.
- [ ] #3 `pytest src/scripts/tests scripts/validate` and `mvn -o test` pass.
- [ ] #4 The dry-run release of `run_20261117_221700_chiyoda` that was refused now builds and verifies.
- [ ] #5 Reviewed by python-reviewer, then Codex.
<!-- AC:END -->
