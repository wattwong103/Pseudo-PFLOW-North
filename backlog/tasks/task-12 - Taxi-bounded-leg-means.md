---
id: TASK-12
title: >-
  Taxi camera-ready: leg samplers solve the log-normal location for the bounded mean (truncation-aware closed form)
status: In Progress
assignee: []
created_date: '2026-11-08 00:00'
labels:
  - taxi
  - calibration
  - bigdata2026
dependencies:
  - TASK-11
priority: high
ordinal: 12000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
**Defect.** Both leg samplers set the log-normal location so that the **unbounded** mean equals the configured
mean, μ = ln m − s²/2. Each draw outside the bounds is then re-drawn: [0.5, 15] km for loaded legs, [0.3, 12] km for
empty legs (B7.5). The re-draw removes the long tail, so the realized means fall short of the published means the
closed form promises.

| Leg | Configured | Bounded mean, analytic | Realized at `290beba`, s12345 |
|---|---:|---:|---:|
| Empty | 5.1 | 4.377 | 4.38 (first draws) |
| Loaded | 4.6 | 4.434 | 4.356 |

As a result, the loaded-km ratio by design is 50.3%, not 47.4%. Found 2026-11-08 by the BigD904 cause analysis
(`PFLOW/20_taxi/papers/bigdata2026/16_causes.md` §4).

**Fix.** For each leg type, solve μ so that the mean of the log-normal **truncated to the sampler's bounds** equals
the configured mean (one-dimensional root-finding on the closed-form truncated mean). The configured keys keep the
published values (4.6, 5.1). The dashboard KS diagnostic of the first-draw empty legs uses the same μ.

**Pilot** (config-only, exploratory: means 4.798 / 6.464, which give bounded means 4.6 / 5.1; 3 seeds):
- the same tested rows pass and fail;
- σ̂ −2.2% (was +3.4%); loaded km −1.7% (was −5.3%); trips −4.7% (was +3.8%); revenue −24.0% (was −20.1%);
- PT R² ward / kzone / szone 0.915 / 0.718 / 0.554 (was 0.918 / 0.723 / 0.557).

North chose the code fix and a full re-run (2026-11-08).

**Behaviour change for configs:** a configured mean at or outside its bounds now stops the run at start
(`IllegalArgumentException`); before, it silently produced a different bounded mean. All shipped configs pass.
**calibration-guardian (2026-11-08): APPROVE WITH CONDITIONS, amber.** A correction, not tuning: inputs and bounds
are unchanged and μ has no free parameter. Keep τ (30 s + 30 s) frozen. Report trips and revenue as they come.

**Stop rule for the re-run (guardian condition 4):**
- In this cycle, do not change τ, s_L, s_E, the bounds, m_E or the stand waits in response to the re-run.
- If any Tier A or B row flips on any seed, report it and return to calibration-guardian.
- Watch the Max Check (15.28 against 15.7) and trips (−4.7% inside 10%).
- All paper numbers come from one committed SHA on a clean tree; pilot and pre-fix numbers are never mixed in.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 A new `TruncatedLognormal` gives the bounded mean (Simpson quadrature in log space). Its `muForBoundedMean(m, s, lo, hi)`
  returns μ whose bounded mean equals m to 1e-9 relative. It reduces to ln m − s²/2 for very wide bounds, and it
  rejects m outside (lo, hi), s ≤ 0, or lo ≥ hi.
- [ ] #2 The loaded sampler (`TaxiSimulation`), the empty sampler (`ShiftSimulator`) and the empty-leg KS diagnostic
  use that μ. The bounds are unchanged.
- [ ] #3 Test: a large seeded sample from the shared re-draw loop (`TruncatedLognormal.draw`, which both samplers
  call) has a mean within 0.5% of the configured mean, and both samplers' locations equal the solver's.
- [ ] #4 Full JUnit passes. Tokyo s12345 on the branch head is recorded: new trip hash, Tier A/B, σ̂, and the loaded
  and empty means. Osaka is recorded too.
- [ ] #5 calibration-guardian signs off before the commit. Reviewed by code-reviewer and java-reviewer, plus one
  cross-harness review (agy; Codex has no credits as of 2026-11-08).
- [ ] #6 Config comments stop claiming that "-σ²/2 makes the arithmetic mean equal" the configured mean.
<!-- AC:END -->
