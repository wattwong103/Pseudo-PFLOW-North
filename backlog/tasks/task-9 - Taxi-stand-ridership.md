---
id: TASK-9
title: >-
  Taxi camera-ready PR-C3: ridership-weighted (Huff) stand choice from MLIT S12 (flag, default nearest)
status: In Progress
assignee: []
created_date: '2026-10-28 00:00'
labels:
  - taxi
  - calibration
  - bigdata2026
dependencies:
  - TASK-8
priority: high
ordinal: 9000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
This is R0, part 3, of the camera-ready roadmap. The 2026-10-28 scientific review found that stand pickups follow
nearest-hub catchments, not station size: Nippori outdrew Ueno, and Sumida and Arakawa are over-represented through the
Z31 and ZS02 stands.

With `stand.choice=huff`, a taxi entering AT_STAND draws an eligible hub with probability ∝ station ridership / road km².
This is the Huff form, exponent 2, that Pseudo-PFLOW already uses for commuting. Ridership comes from MLIT 国土数値情報
S12-24 (2023 daily boardings + alightings), downloaded with North's approval. Each station counts toward its nearest
hub within 500 m only. The table is built outside git by `PFLOW/20_taxi/analysis/stand_ridership/stand_ridership.py`.

Plan: `PFLOW/00_about/plans/2026-10-28-taxi-prc-spatial-fixes-plan.md` (P9, P10). The default stays `nearest`; North
decides adoption together with C2's mechanisms. Stacked on TASK-8.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 `stand.choice` (nearest|huff; anything else stops the run) and `stand.ridership.path`. `nearest` is
  byte-identical to TASK-8.
- [ ] #2 `huffPick` follows the closed form, never picks an ineligible hub, and floors the distance at 0.1 km. A
  missing table stops the run, and with `huff` every eligible hub must have ridership.
- [ ] #3 The Huff arm on 3 seeds is reported with Tier A/B, PT, urban structure, and the stand-leg mean, P90 and
  count over 12 km.
- [ ] #4 Guardian sign-off; Codex and code-reviewer reviews; North decides adoption.
<!-- AC:END -->
