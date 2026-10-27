---
id: TASK-5
title: >-
  Taxi camera-ready PR-A: zone coordinates from the audit, six added zones, circle-area e-Stat density
status: In Progress
assignee: []
created_date: '2026-10-28 00:00'
labels:
  - taxi
  - calibration
  - bigdata2026
dependencies: []
priority: high
ordinal: 5000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Camera-ready work for IEEE BigData 2026 (BigD904). This freezes the final Tokyo zone configuration before the 10-seed
runs (WP2), so that every paper table uses one correct configuration.

The 2026-10-28/29 zone audit (`PFLOW/20_taxi/analysis/zone_audit/zone_audit_2026-10-29.md`) found the following,
using OSM and e-Stat only (never PT or GPS):
- **Z08 "Ueno" sat on Skytree**, a copy of Z31 11 m away.
- **Z03 "Shinjuku" sat 1.57 km south of Shinjuku station**, in Shibuya-ku. This left Nishi-Shinjuku (about 65,000
  jobs) outside every zone.
- **Four more centres were 0.5–0.8 km from their named place.**
- **ZS02 "Ueno Station Area" sits on Nippori station.**
- **Mitaka (13204) had no zone.**

Separately, the e-Stat density divided by the area of populated cells only, which inflated coastal zones
(06_wp0_results.md §9 #9).

Approved plan: `PFLOW/00_about/plans/2026-10-28-taxi-camera-ready-plan.md`, Part 1a. The decisions (C1, C5, G1) were
taken by North on 2026-10-28.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 Z03, Z04, Z07, Z08, Z15 and Z41 move to their OSM reference points. ZS02 is renamed "Nippori Station Area".
  Z61–Z66 are added (Mitaka, Omori, Kinshicho, Kojimachi/Hanzomon, Akabane, Kita-Senju), each mixed, r = 1.5 km,
  transport_hub = 0.
- [ ] #2 All weight columns of the eligible zones are rederived from e-Stat, with density per km² of zone circle. The
  derivation script's tests cover the circle-area denominator and the coastal case.
- [ ] #3 Every moved and added zone is service-eligible, and the hub list is unchanged (Review Focus 1).
- [ ] #4 Adopt rule on seeds 12345, 1 and 2:
  - Tier A holds.
  - No Tier B row goes from pass to fail.
  - Drift over 2% (T, D, σ̂, revenue) goes to the guardian.
  - A fall of more than 0.02 in mean ward R² is reported to North before commit.
- [ ] #5 Osaka trips are unchanged (`BD55AEC6…`). JUnit `taxi.sim` is green.
- [ ] #6 calibration-guardian has signed off. A Codex cross-harness review has run, and its CRITICAL and HIGH findings
  are fixed.
<!-- AC:END -->
