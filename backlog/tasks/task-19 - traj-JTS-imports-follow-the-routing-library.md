---
id: TASK-19
title: >-
  traj: DrmNetworkLoader imports org.locationtech JTS like the routing library; Maven builds traj so a future
  move is caught
status: In Progress
assignee: []
created_date: '2026-11-17 00:00'
labels:
  - traj
  - build
dependencies:
  - TASK-16
priority: high
ordinal: 19000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
**Defect.** `98cd478` (2026-03-02) moved the in-source routing library (`src/jp/ac/ut/csis/pflow/**`) from
`com.vividsolutions.jts` to `org.locationtech.jts`, but `src/traj/DrmNetworkLoader.java` lines 14-18 kept the old
imports. Since `lib/pflowlib.jar` left (`14b4fcb`), traj does not compile:
`DrmNetworkLoader.java:257: incompatible types: com.vividsolutions.jts.geom.LineString cannot be converted to
org.locationtech.jts.geom.LineString` (`GeometryUtils.createPointList(line)`, full-geometry mode). `pom.xml` excluded
`traj/**`, so nothing noticed. Requested by the traj-runners session (North's choice) for its trajectory proof.

**Fix.** The five imports move to `org.locationtech.jts` (same class names). `pom.xml` stops excluding `traj/**`, so
`mvn compile` builds traj and would have failed on this. Stacked on TASK-16 (its POM block); merge #19 first.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 With `traj/**` no longer excluded and the old imports, `mvn -o compile` fails on `DrmNetworkLoader`; with the
  new imports it passes and `mvn -o test` stays green.
- [ ] #2 No other line of `DrmNetworkLoader.java` changes; no other traj file uses `com.vividsolutions`.
- [ ] #3 Reviewed by java-reviewer, then Codex.
<!-- AC:END -->
