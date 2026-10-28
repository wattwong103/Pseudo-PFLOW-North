---
id: TASK-7
title: >-
  Taxi camera-ready PR-C1: non-road exclusion layer, stand classes, Mitaka and Haneda zones, export labels
status: In Progress
assignee: []
created_date: '2026-10-28 00:00'
labels:
  - taxi
  - calibration
  - bigdata2026
dependencies: []
priority: high
ordinal: 7000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
This is R0, part 1, of the camera-ready roadmap. It fixes the defects that the 2026-10-28 scientific review of the zone
set found (`PFLOW/20_taxi/papers/bigdata2026/07_final_config.md` §8) before the freeze:

- **Pickups on the Imperial Palace grounds:** small zone 100 carries 2.8% of simulated pickups, against 0% in PT.
- **Stand waits chosen by zone-name substring:** seven major-station hubs get the 6-minute entertainment wait.
- **Mitaka city has no zone,** because Z61 sits in Musashino.
- **Haneda's point is the aerodrome centroid.**
- **Export zone labels** take the first containing zone in file order.

Approved plan: `PFLOW/00_about/plans/2026-10-28-taxi-prc-spatial-fixes-plan.md` (rulings P2–P6, P10). North asked for
these fixes on 2026-10-28 and delegated the judgement while away.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 `spatial.exclusion.areas.path` loads OSM non-road polygons (≥ 0.2 km², kept outside git). Points inside are
  invalid; an empty key leaves the layer off.
- [ ] #2 An optional 12th `zones.csv` column, `stand_class`, picks the stand wait. 11-column files keep the name rule.
  An unknown class stops the run.
- [ ] #3 Z07 moves to the T1/T2 terminal stations; Z61 is renamed "Mitaka Station"; Z67 is added inside Mitaka city
  (13204). The weights are rederived.
- [ ] #4 Export labels use the containing zone with the nearest centre.
- [ ] #5 Adopt check (seeds 12345, 1, 2) against `73ff800`: Tier A holds. A Tier B pass→fail is reported under Q4
  (North decides). Tier C is reported with PT-bootstrap intervals. The szone-100 share falls to about 0.
- [ ] #6 Osaka is unchanged (`BD55AEC6…`); JUnit taxi.sim is green; the guardian signs off; Codex and code-reviewer
  review the PR.
<!-- AC:END -->
