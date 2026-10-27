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
  (The checksums were first recorded on 2026-10-28, under Verification.)
- [x] #6 The tests pin every reported count, total, ratio and limitation, and
  every validation check. Each of the 18 one-line mutants from the 2026-10-28
  reviews fails a test. Not pinned: the six-decimal rounding of the three
  totals, because the golden totals are exact.

## Plan

1. Add tests then `scripts/validate/taxi_trip_evidence.py`; reuse the existing
   skill helper for run_metadata/validation.csv score consistency.
   *Not done: the auditor reads only the trip file (see Verification,
   2026-10-28).*
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

### 2026-09-20 (as first written; the diff alone cannot confirm these claims)

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

### 2026-10-28 re-check after #7 (Claude Code)

This branch merges cleanly with `pseudo-pflow-v3.2-unified` at `219e78d`.
`TaxiDataExporter.java` has not changed since the merge-base, so the
16-column contract still holds.

The auditor (`add138d`, unchanged since) was run on the three exports from
the clean build of `9e75801`, seed 12345 (`PFLOW/output/taxi_wp0/`, local).
It accepted all three. Each hash below equals the reference trip hash
recorded for its run:

| Run | Rows | sha256 |
|---|---:|---|
| Tokyo, zone on, e-Stat zones | 1,345,212 | `8dd1a43282070ca4cb0c7f5d3f8b4da868771bc6b5432eb92b21459b99711bf2` |
| Tokyo, zone off, heuristic zones | 1,295,767 | `f9769fbe8733e0cca2b61f85a1d571c0fb973180bfe64a014fcc8b218eb48d6d` |
| Osaka | 468,113 | `bd55aec66aacb634a832c57e4ef1c1e37f377ceb33c53c9aa5c042ebc93de40e` |

The two runs that the base already contained (Tokyo zone off and Osaka) have
the same row counts as reported on 2026-09-20.

On the Tokyo zone-on run, the auditor reproduces the engine's
`validation_tiers.csv` from rows alone:
- loaded-distance fraction 0.490990, against σ̂ 49.0990
- fare per occupied trip 1877.0498, against the average fare
- mean occupied km 4.3532, against the average trip distance

It counts 24,626 taxis with rows, against the active fleet of 24,697. Divided
by that roster figure, the totals give the engine's trips per taxi (26.5847),
km per taxi (235.706) and revenue per taxi (49,900.86). The roster
denominator stays outside the tool, as the documentation says.

The 2026-09-20 figures of 19/19 (Tokyo) and 12/17 (Osaka) used the old bar.
Since #7 the taxi bar is tiered. Tokyo zone on scores Tier A 10/10 and Tier B
7/7, with a legacy grade of A 18/19. Osaka fails the tier gate because it
runs a 5-type fleet against a 3-type baseline.

Plan step 1 was skipped. Since #7, the workspace evidence step
(`pflow-run-evidence`) checks run metadata and `validation_tiers.csv`. This
auditor checks the trip file only, so the two are complementary and no
follow-up is needed here.

The repository compile and Maven blockers above were not re-checked.

Review fix (`7f643af`): the 2026-10-28 review found that 12 of 15 one-line
mutants of the auditor passed all 29 tests. The new tests catch all 15, and
`pytest` gives 36 passed.

Codex review of `7f643af` and `085e2db`: REQUEST_CHANGES.
- MEDIUM: the golden fixture had `empty_trips` equal to `observed_taxis`, so
  swapping them went unnoticed, and the limitations list was not asserted.
- LOW: the overflow check was tested on distance only.

Fixed in the next commit: a third taxi makes every count distinct, the
limitations list is asserted exactly, and the overflow test covers fare too.
`pytest` gives 37 passed, and 18 of 19 mutants fail a test. The one that
passes removes the rounding of the empty-km total. It is parked (LOW), and
AC #6 is narrowed to match.
