---
id: TASK-8
title: >-
  Taxi camera-ready PR-C2: cruise distance decay (flag, default off) and de-overlap weights tooling
status: In Progress
assignee: []
created_date: '2026-10-28 00:00'
labels:
  - taxi
  - calibration
  - bigdata2026
dependencies:
  - TASK-7
priority: high
ordinal: 8000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
This is R0, part 2, of the camera-ready roadmap. It addresses the core–periphery bias that the 2026-10-28 scientific
review traced to two causes: the cruise draw has no distance term, and overlapping zone circles multiply target mass.

Plan: `PFLOW/00_about/plans/2026-10-28-taxi-prc-spatial-fixes-plan.md` (P7, P8, P10). The mechanisms are implemented
and tested, but **switched off for Tokyo pending North's decision**. All arms keep Tier A and B. They shrink the
periphery bias but lower every PT R² rung (`PFLOW/20_taxi/papers/bigdata2026/08_spatial_fixes.md` §C2). Stacked on
TASK-7.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 `cruise.distance.decay.km` (default 0 = off) multiplies cruise-target scores by exp(−road km/λ) from the
  taxi's current position to each zone anchor. Off is byte-identical to TASK-7.
- [ ] #2 The workspace derivation has `--deoverlap` (nearest containing eligible centre), with tests.
- [ ] #3 Arms on 3 seeds (de-overlap, decay λ = 5.1, both) are reported with Tier A/B, PT and urban-structure tables.
- [ ] #4 Guardian sign-off; Codex and code-reviewer reviews; North decides whether to switch either mechanism on.
<!-- AC:END -->
