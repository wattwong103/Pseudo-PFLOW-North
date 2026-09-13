---
id: TASK-2
title: >-
  Standing allowlist: cross-folder writes (vault, scratchpad, memory, sibling
  projects)
status: In Progress
assignee: []
created_date: '2026-09-13 20:32'
labels:
  - standing
  - governance
dependencies: []
priority: low
ordinal: 2000
permittedExternalPaths:
  - /Users/north-mac/Dropbox
  - /private/tmp/claude-501
  - /Users/north-mac/.claude/projects
  - H:/Dropbox
  - D:/Dropbox
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Always active via the tracked sentinel .worktrees/.active-task; its permittedExternalPaths frontmatter is the only mechanism the ai-sdlc v0.20.1 Write/Edit hook offers for writes outside this repo. Keep status In Progress. Pipeline per-worktree sentinels take precedence. PC owners: append the machine's Claude temp and ~/.claude/projects roots. Approved by the operator 2026-09-14.
<!-- SECTION:DESCRIPTION:END -->
