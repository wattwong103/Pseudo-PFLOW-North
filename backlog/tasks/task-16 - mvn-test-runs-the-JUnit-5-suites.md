---
id: TASK-16
title: >-
  Build: `mvn test` runs the existing JUnit 5 suites (taxi.sim, truck.sim) offline; docs/pipeline.md stops listing
  an archived script
status: In Progress
assignee: []
created_date: '2026-11-17 00:00'
labels:
  - build
  - tests
dependencies: []
priority: medium
ordinal: 16000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
**Defect.** `mvn -o test` exits 1 on `pseudo-pflow-v3.2-unified` (`2eedad7`): every test is JUnit 5, the POM declares
JUnit 4.13.1, and the POM excludes `taxi/**` and `truck/**`, so the classes the tests exercise are not built either.
The only way to run them was the hand runner `scripts/junit/RunJUnit5.java` in the workspace.

**Fix (decision D2-A, North 2026-11-17).** Test dependency `junit-jupiter-engine` 5.8.2 and `maven-surefire-plugin`
3.0.0-M7 (both already in the offline cache); build `taxi/` and `truck/` by default; keep `archive/experimental/dcity/aggr`
(taxi.sim imports `dcity.aggr`) while still excluding the rest of `archive/`; exclude three truck tests that no longer
compile against the frozen truck code. No source under `src/taxi` or `src/truck` changes, so no calibration gate.

**Docs.** `docs/pipeline.md` listed `run_audit_traj_only.bat` as a live repo-root script; it moved to the workspace
`scripts/archive/` on 2026-10-22, and the other audit scripts are in the workspace `scripts/`, not the repo root.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 `mvn -o test` exits 1 on `2eedad7` and exits 0 on the branch, with the test counts recorded.
- [ ] #2 No file under `src/taxi`, `src/truck` or `config/` changes.
- [ ] #3 `docs/pipeline.md` names where the audit scripts live and marks `run_audit_traj_only.bat` archived.
- [ ] #4 Reviewed by code-reviewer and java-reviewer, then Codex; CRITICAL/HIGH fixed.
<!-- AC:END -->
