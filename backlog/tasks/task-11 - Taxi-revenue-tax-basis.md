---
id: TASK-11
title: >-
  Taxi revenue validation on one tax basis: convert tax-inclusive simulated fares before grading (Tokyo)
status: In Progress
assignee: []
created_date: '2026-10-31 00:00'
labels:
  - taxi
  - calibration
  - bigdata2026
dependencies:
  - TASK-6
priority: high
ordinal: 11000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
**Where it came from.** The 2026-10-30 literature-review verification of the BigData 2026 camera-ready found the
mismatch. A fresh review of the drafted edits flagged it (F3 in
`PFLOW/20_taxi/papers/bigdata2026/09_litreview_verification.md`).

**What is wrong.**
- **The targets exclude tax.** THTA's revenue per active vehicle (¥56,139) is 税抜, and so are the Tokyo baseline
  rows "Total Daily Revenue per Taxi" and "Avg Fare", which the baseline notes say.
- **The simulation includes tax.** It sums tariff fares, and Tokyo's tariff includes consumption tax: in 2019 the
  Cabinet scaled the fares by 110/108 when the rate went from 8% to 10%.
- **So the two revenue rows compare different tax bases.** On one basis, revenue is about −19% on merged main against
  a ±15% band. Tier B becomes 6/7.

North decided on 2026-10-31: one basis, re-grade, and report the miss honestly. calibration-guardian ruled GO on
converting inside the validation, and NO-GO on editing the baseline targets. It is a measurement correction, not a
regression, and Q4 applies.

**Do not:**
- widen the tolerance
- re-tier the row
- retune the fares, legs or trips
- pick seeds

Plan: `C:/Users/north/.claude/plans/ticklish-sniffing-aho.md`. The write-up will be
`PFLOW/20_taxi/papers/bigdata2026/10_revenue_tax_basis.md`.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 New key `fare.consumption.tax.rate`. It defaults to 0. A negative, NaN, non-numeric or ≥1 value stops the
  run at config load, and the effective value is logged.
- [ ] #2 The rate divides only "Avg Fare" and "Total Daily Revenue per Taxi", each by exactly (1 + rate). The fares
  in the trips files do not change.
- [ ] #3 `dashboard.csv` keeps its existing revenue rows and adds the rate and the two converted values.
- [ ] #4 The Tokyo FY2024 configs (`taxi_config`, `1pct`, `5pct`, `tokyo_r8`) set 0.10. Every other city stays at 0.
  The FY2023 hold-out config (R5) must set it when it is written.
- [ ] #5 Tokyo s12345, s1 and s2 trips are byte-identical to `m-73ff800-*`. Tier A is 10/10 and Tier B 6/7 (revenue
  FAIL, avg fare PASS). Osaka's trips and validation are unchanged.
- [ ] #6 Guardian sign-off; Codex and code-reviewer reviews.
<!-- AC:END -->
