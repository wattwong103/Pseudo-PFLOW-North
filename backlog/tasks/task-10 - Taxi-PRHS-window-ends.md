---
id: TASK-10
title: >-
  Taxi PRHS windows: MLIT hour blocks are inclusive, so each Tokyo window ends one hour later
status: In Progress
assignee: []
created_date: '2026-10-31 07:00'
labels:
  - taxi
  - calibration
  - bigdata2026
dependencies: []
priority: high
ordinal: 10000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Found in passing by the 2026-10-30 literature-review verification (`PFLOW/20_taxi/papers/bigdata2026/09_litreview/b_grey_refs.md`,
entry 5). MLIT publishes the 自家用車活用事業 shortage windows for 特別区・武三 as inclusive hour blocks: "7時台～10時台" covers
07:00-10:59. The Tokyo configs encode them as `07:00-10:00`, and `PrhsWindow.contains` is `[start, end)`, so every window is one
hour short (Sat 00-04時台 is five hours in MLIT and four in the model).

Sources, read 2026-10-31:
- MLIT press release 2024-03-13, attachment https://www.mlit.go.jp/report/press/content/001730147.pdf p.2 (FY2024, four windows).
- Kanto Transport Bureau, Tokyo Transport Branch, 2026-02-27, https://wwwtb.mlit.go.jp/kanto/s_tokyo/date/to_t260227.pdf p.1
  (R8, Mon-Fri 7時台～10時台 only, 870 vehicles).

Fix: change the config ends to the hour after the last block (11:00, 20:00, 05:00, 14:00) in `taxi_config.properties` and
`taxi_config_tokyo_r8.properties`. The parser keeps its `[start, end)` semantics. PRHS share stays at 0.5%, so calibration is not
expected to move, but the Monday run gains PRHS trips in 10:00-10:59 and its trip hash changes.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 A JUnit test fails on the base: a pickup at 10:30 Monday is outside every Tokyo window
- [ ] #2 The test checks every hour block of every day in both Tokyo configs against MLIT's published blocks, and passes after the fix
- [ ] #3 calibration-guardian signs off before the commit
- [ ] #4 3-seed adopt check (12345, 1, 2): Tier A 10/10 and Tier B 7/7 on every seed; T, revenue and sigma headroom reported against `m-73ff800-*`
- [ ] #5 Osaka trips stay byte-identical (`BD55AEC6...`)
- [ ] #6 Scenario A (FY2024 windows, simulated Saturday) re-run before and after; PRHS trip and vehicle counts reported for the paper
<!-- AC:END -->
