---
id: TASK-6
title: >-
  Taxi tiered baseline: tier, bound and thresholds move from Java into the baseline CSV
status: In Progress
assignee: []
created_date: '2026-10-28 06:00'
labels:
  - taxi
  - calibration
  - bigdata2026
dependencies:
  - TASK-4
priority: high
ordinal: 6000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Camera-ready work for IEEE BigData 2026 (BigD904), PR-B of the camera-ready plan. WP0 (TASK-4, PR #7, merged as
`219e78d`) replaced the "Tokyo A+ 19/19" bar with the tiered bar in `validation_tiers.csv`, but the two operating-zone
thresholds (99.0 and 0.5) still live in Java (`TaxiValidationTiers`), the Tier A distance rows are graded two-sided at
±50%, and the Tokyo baseline still carries heuristic targets for three Tier D rows (Nearby 80, City Bounds 99, Empty
Trip Count 48).

North approved (decision C3, 2026-10-28) moving tier, bound and threshold into the operator-owned baseline, with the
edits listed in `06_wp0_results.md` §10. This is a grading-only change: no trip file may change.

Approved plan: `PFLOW/00_about/plans/2026-10-28-taxi-camera-ready-plan.md`, Part 1b (Tasks B1–B3). Spec:
`PFLOW/20_taxi/papers/bigdata2026/06_wp0_results.md` §3 and §10.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 Baseline rows carry optional `tier` and `bound` columns (9–10); `at_least` / `at_most` / `two_sided` grade both `validation.csv` and `validation_tiers.csv`; the 8-column Osaka baseline loads exactly the rows it does today
- [ ] #2 Every row of every shipped baseline has the header's field count (a comma in `notes` cannot shift columns)
- [ ] #3 No zone threshold remains in Java; with the zone on and no baseline zone row, the row is `NO_TARGET` and counts against Tier A
- [ ] #4 The shipped Tokyo baselines' tiers equal the `DEFS` tiers (drift test)
- [ ] #5 Optional `validation.baseline.file` key; empty keeps `taxi_baseline_<city.name>.csv`
- [ ] #6 Tokyo and FY2023 baselines edited per §10 (three rows retired, two zone rows added, distance rows one-sided at 0.5 and 15.7, FY2023 on the 5-type shares)
- [ ] #7 Grading-only: Tokyo trips sha256 `8DD1A432…` and Osaka trips `BD55AEC6…` unchanged, Osaka `validation.csv` byte-identical, `tier_gate_met` true for Tokyo
- [ ] #8 JUnit 5 tests for each new behaviour, written first and seen to fail; suite green
<!-- AC:END -->
