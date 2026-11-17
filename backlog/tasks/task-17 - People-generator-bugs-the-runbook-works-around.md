---
id: TASK-17
title: >-
  People flow: fix the three generator bugs that RUNBOOK-v3.2 works around (trip file name, first person row,
  PersonGenerator config layers)
status: In Progress
assignee: []
created_date: '2026-11-17 00:00'
labels:
  - people-flow
  - gufm
dependencies: []
priority: high
ordinal: 17000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
`10_people/RUNBOOK-v3.2.md` works around three defects on `pseudo-pflow-v3.2-unified` (`2eedad7`):

1. **Trip file name.** `TripGenerator.main` names its output `trip_` + characters 9-13 of the input name, so the
   `person_13101.csv` that ActivityGenerator writes becomes `trip_101.c.csv`; the runbook stages a copy named
   `activity_13101.csv`. Fix: the name comes from the 5-digit city code before `.csv`; any other name stops the run
   before any work, naming the file.
2. **First person row.** `PersonAccessor.load` reads line 1 and drops it, but person files are headerless, so the first
   person of every file never reaches ActivityGenerator. Fix (decision D3-A, North 2026-11-17): never skip; a header
   row now fails on its first number. With mfactor > 1 the sample in the first person's labor group shifts by one.
3. **PersonGenerator config.** `PersonGenerator.main` reads only the first `config.properties` on the classpath, so
   `config.local.properties` and `-Dconfig.file` cannot move its output, and the committed default writes
   `data/census/person/agent/` (gufm's input). Fix: the same layers as the other generators (`utils.ConfigLoader`),
   `${PFLOW_HOME}` resolved, a missing `legacy.stat.root` stops the run, and the resolved root is printed.

Planned as stacked on TASK-15/16; the safety hook blocks a local merge, and these fixes touch other lines, so this
branch is independent of both.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 A regression test per bug, each seen failing on `2eedad7` (the PersonGenerator test fails to compile there:
  testing the old main would write into `data/census/person/agent/`, so no test calls main).
- [ ] #2 The tests pass on the branch; with TASK-15 and TASK-16 applied too, `mvn -o test` is green.
- [ ] #3 A one-ward smoke run (13101, MFACTOR 50) in a run folder: step 1 takes its root from `-Dconfig.file`, step 3
  writes `trip_13101.csv` from `person_13101.csv` with no staging rename, person 1 reaches the activity file, and the
  `data/census/person/` hash and listing are unchanged.
- [ ] #4 The runbook text changes are proposed in the handoff (the runbook is not edited here).
- [ ] #5 Reviewed by code-reviewer and java-reviewer, then Codex; CRITICAL/HIGH fixed.
<!-- AC:END -->
