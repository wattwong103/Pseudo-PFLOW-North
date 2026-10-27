---
id: TASK-4
title: >-
  Taxi WP0: operating-zone fix, measured tiered validation, e-Stat zone weights
status: In Progress
assignee: []
created_date: '2026-10-28 00:05'
labels:
  - taxi
  - calibration
  - bigdata2026
dependencies: []
priority: high
ordinal: 4000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Camera-ready work for IEEE BigData 2026 (BigD904). In the Tokyo reference run (`run_20260606_000528`, reproduced byte
for byte from the clean commit `d266741` on 2026-10-28), 31.13% of passenger trips have both ends outside the Special
Wards operating zone (JIS 13101–13123, 13203, 13204). Only 18.46% of taxi homes lie in the zone, because homes are drawn
over Tokyo and Kanagawa land.

"Trips Within City Bounds" (constant 99.5) and "Nearby Trip Ratio" (echo of the config value) are not measured.

The June data-derived zone weights used licensed data, so the weights must now be derived from public e-Stat data only.

Approved plan: `PFLOW/00_about/plans/2026-10-27-taxi-wp0-operating-zone-plan.md` (North, after calibration-guardian
review). Spec: `PFLOW/20_taxi/papers/bigdata2026/05_camera_ready_plan.md`.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 Tier A holds on the final Tokyo run: pickups inside the operating zone ≥ 99% and both ends outside ≤ 0.5%, on both `polbnda` and H30 polygons
- [ ] #2 No Tier B (THTA regulatory) row moves from pass to fail without an ADR approved by North; no Tier-3 parameter is retuned
- [ ] #3 Every validation row is measured; no row echoes a config value or a constant
- [ ] #4 Osaka (zone disabled): trip-file sha256 identical to the pre-change run; 15 non-spatial rows identical
- [ ] #5 e-Stat-only zone weights are adopted or rejected on the plan's 3-seed rule, with evidence recorded
- [ ] #6 JUnit 5 tests for each new behaviour, written first and seen to fail; pytest for the weight script
<!-- AC:END -->
