# Review policy and working rules — Pseudo-PFLOW

Injected into every Claude Code session by the ai-sdlc plugin; Codex reads it on instruction. Parents: the
workspace contract (`~/Dropbox/AGENTS.md`) and `PFLOW/CLAUDE.md` (studio conventions, calibration-guardian).

## Branch and merge rules
- Every change starts from a backlog task with acceptance criteria (`backlog task create ...`).
- Integration branch: `pseudo-pflow-v3.2-unified` (where development happens); `master` is the GitHub default.
  Both are protected: never commit or push to them directly; `.githooks/` refuses it. PRs target
  `pseudo-pflow-v3.2-unified`.
- Open a PR and stop. The human merges. Never merge, close, force-push or hard-reset.
- `.ai-sdlc/**` is operator-owned. Calibration baselines (`mfs/kanto/validation_baseline.csv`, per-city
  `taxi_config.properties`, `TruckSimulationConstants`) are read-only without `calibration-guardian` sign-off,
  and never edited to make a test pass.
- Never commit scaffolding (`.claude/`, `.codex/`, `.grok/`, `AGENTS.md`, `CLAUDE.md`, `openspec/`), simulation
  outputs, or machine paths; paths go through `PathResolver` / `${PFLOW_HOME}`.

## Pre-commit checklist (run from Pseudo-PFLOW/, JDK 21)
```bash
bash ../scripts/compile_all.sh                 # javac against lib/ + ~/.m2 — must finish without errors
mvn -q -DskipTests=false test                  # JUnit unit tests under src/test/java
```
## Calibration gate — the test gate for simulation code (operator decision 2026-09-14)
- Any change under `src/truck/sim/`, `mfs/kanto/`, or `TruckSimulationConstants`: run the `pflow-truck-calibrate`
  skill. Bar: **A+ = 51/51**. Fast path re-grades the latest run (`java -cp bin truck.sim.StandaloneValidator "$RUN"`);
  a change to generation logic needs a full run. The grade line in `validation.csv` is the evidence.
- Any change under `src/taxi/sim/`, `config/taxi/{city}/`, or per-city baselines: run `pflow-taxi-calibrate`.
  Bar: **Tokyo A+ = 19/19** in-sample; hold-out (FY2023, PRHS) reported separately, never averaged in.
- A slipped grade is a stop-the-world event: the PR is BLOCKED until the grade is restored or `calibration-guardian`
  approves a baseline change through an ADR.
- Trajectory (`src/traj/`) changes: `bash ../scripts/compile_traj.sh` plus a short trajectory run on a sample
  (`tools/test_traj.bat` equivalent) with the failure report unchanged.

## Review calibration
- Reviewers report `{approved, findings[], summary}`; CRITICAL and HIGH block. `java-reviewer` for Java changes;
  `calibration-guardian` is consulted before anything touching `truck.sim`, `taxi.sim`, `mfs/`, or baselines.
- Cross-harness: Claude implemented → Codex reviews (`../.codex/agents/reviewer.toml`); Codex implemented →
  Claude reviews with `/ai-sdlc review-pr`.

## Session end
- Status line: DONE / DONE_WITH_CONCERNS / BLOCKED with evidence (grade lines, test counts).
- Handoff: `backlog doc create "handoff-YYYY-MM-DD-<harness>"`; the weekly report under `../report/` stays.
