---
id: TASK-15
title: >-
  People flow: bring the v3.3 trip rows (one row per logical trip, unique trip_id) onto v3.2-unified, with a
  release-tool label and content gate
status: In Progress
assignee: []
created_date: '2026-11-17 00:00'
labels:
  - people-flow
  - gufm
dependencies: []
priority: high
ordinal: 15000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
**Why.** The v3.3 trip writer lives only on the local branch `pseudo-pflow-v3.3` (`b59d7dd`, `0c2a3f1`), which has
no upstream and no copy on origin. North chose (2026-11-17, decision D1-A) to bring it onto `pseudo-pflow-v3.2-unified`
as is, and to make the release tool able to label and check its rows.

**What changes for gufm.** Every run made from v3.2-unified after the merge writes one row per logical trip: a
mixed-mode trip (access, rail, egress) becomes one row (origin of the first leg, destination of the last, departure of
the first, representative mode, `MIX` written as TRAIN). Columns 1-9 keep the gufm contract. Columns 10-12 become
`trip_id` (person_id x 1000 + seq, unique), `arr_time`, `duration` instead of `trip_id, subtrip_id, rep_mode`.
Release #1 and `data/census/person/` do not change. This contradicts plan decision D2 / gufm ADR-17 ("v3.2-unified,
subtrip rows"); the ADR update is a gufm-side task.

**Release tool.** Column count cannot tell the two 12-column layouts apart, so a v3.3 run would have been labelled
`v3_12col`. A new `row_semantics` key `v3_3_12col` names the v3.3 layout, and a `row_semantics` gate checks every
row against its label: `v3_12col` needs subtrip_id in 0-2 and rep_mode a transport code 0-7; `v3_3_12col` needs
trip_id = pid x 1000 + seq (seq 1-999), unique in its file.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 `b59d7dd` and `0c2a3f1` are cherry-picked (`-x`) onto `origin/pseudo-pflow-v3.2-unified` and pushed on
  `task/TASK-15-v3.3-trip-rows`; `mvn -o -DskipTests compile` is clean.
- [ ] #2 `PersonAccessorTripFormatTest` is JUnit 5 (the vintage engine is not cached) and its 8 tests pass (hand
  runner `scripts/junit/RunJUnit5.java` until TASK-16 makes `mvn test` run).
- [ ] #3 `make_people_release.py` accepts `row_semantics` `v3_3_12col` and refuses either 12-column label on the
  other's rows; the new tests fail before the change and pass after; the whole release-tool suite passes.
- [ ] #4 The PR states what changes for gufm (above) and proposes the `10_people/README.md` and runbook text.
- [ ] #5 Reviewed by code-reviewer, java-reviewer and python-reviewer, then Codex; CRITICAL/HIGH fixed.
<!-- AC:END -->
