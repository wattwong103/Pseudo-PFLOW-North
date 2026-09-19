---
id: TASK-3
title: Independently audit Japan taxi trip exports
status: In Progress
assignee: []
created_date: '2026-09-20 00:00'
labels: [taxi, validation]
dependencies: []
priority: high
---

## Description

The user authorized building Japan taxi setup, public statistics and validation
for a paper revision, and selected Tokyo + Osaka first. This first bounded
change measures generated trip exports independently of simulation-reported
scores. Source data and research artifacts remain in the parent workspace.

## Acceptance Criteria

- [x] #1 A streaming, standard-library CLI checks the documented taxi CSV schema,
  identifiers, numeric domains, occupancy consistency and time representation.
- [x] #2 Counts and occupied/empty distance, fare, loaded-distance fraction and
  taxi counts are calculated from rows with explicit observation denominators.
- [x] #3 Missing/invalid input fails clearly; no city calibration grade or active
  fleet denominator is inferred from taxis with exported trips.
- [x] #4 Regression tests cover multi-day shifts, duplicate IDs, malformed
  booleans, non-finite/negative values, empty input and schema mistakes.
- [x] #5 Run the audit on existing Tokyo and Osaka exports, record checksums and
  limitations, and obtain independent cross-harness review before the PR.

## Plan

1. Add tests then `scripts/validate/taxi_trip_evidence.py`; reuse the existing
   skill helper for run_metadata/validation.csv score consistency.
2. Document the CLI and its exclusions. The simulator and protected per-city
   baselines are outside this change. Experimental runs require guardian review.
3. Run pytest, then the repository compile/JUnit checklist where supported;
   report unrelated environment/build blockers separately from Python results.
4. Audit available exports; record source/public-statistics setup separately in
   the parent workspace. Push a task branch and open a PR for human merge.

Follow-on tasks: explicit city configuration validation and repair; matched
public-statistics cohorts; Osaka stand-window export and held-out validation;
equal-information baselines and multi-seed cross-city experiments.

## Verification

`python -m pytest scripts/validate/test_taxi_trip_evidence.py -q`: 29 passed.
Independent inspections completed for historical and freshly compiled Tokyo and
Osaka exports. Current full-size Java suites: Tokyo19/19; Osaka12/17, diagnostic
only. Simulation sources and canonical inputs were not changed by this task.
Fresh same-build Tokyo smoke replay reproduces identical trip CSV bytes.

Repository checklist attempted: compile wrapper fails on the existing moved
`src/dcity/aggr` path; Maven fails on existing test-source/JUnit and research
module dependencies. Fresh isolated taxi-only compilation succeeds. Keep the
PR draft until required repository checks and cross-harness review are resolved.

Cross-harness Claude direct review approved with no CRITICAL/HIGH findings.
The configured `/ai-sdlc review-pr` command was unavailable; the direct read-only
fallback reviewed all four files. CLI/encoding/serialization regression tests
and integer-identity documentation address its non-blocking comments. The
separate guardian review's hash race was also fixed. Repository build issues
remain a follow-up, not changes to this Python-only diff.
