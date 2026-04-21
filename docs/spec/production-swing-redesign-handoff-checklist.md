# Production Swing Redesign Handoff Checklist

Last updated: 2026-04-21

## 1. For Claude

Read these files first:
- [production-swing-redesign-implementation-spec.md](D:\ai\stock\trading-system\docs\spec\production-swing-redesign-implementation-spec.md)
- [production-swing-redesign-progress-board.md](D:\ai\stock\trading-system\docs\spec\production-swing-redesign-progress-board.md)
- [spec.md](D:\ai\stock\trading-system\docs\spec.md)
- [scoring-workflow.md](D:\ai\stock\trading-system\docs\scoring-workflow.md)
- [workflow-correctness-ai-orchestration-spec.md](D:\ai\stock\trading-system\docs\workflow-correctness-ai-orchestration-spec.md)

Implementation rules:
- Java is the only execution authority
- Codex can veto only
- Claude thesis cannot force entry
- do not preserve old logic by renaming it
- each new layer must have its own persisted artifact

First implementation chunk:
- build Regime layer first
- then Ranking
- then Setup
- then Timing
- then Risk
- then Execution

For every chunk output:
- files changed
- new entities / repositories / migrations
- tests added
- config keys added
- legacy logic still remaining

## 2. For Codex Review

Review objective:
- verify that the code now follows
  - `Regime -> Theme -> Ranking -> Setup -> Timing -> Risk -> Execution -> Review`

Review checklist:
- no ranking logic remains inside `FinalDecisionService`
- no risk gating remains inside `FinalDecisionService`
- timing is required before any `ENTER`
- regime and theme are persisted as structured outputs
- execution log references upstream decisions
- trade review no longer relies on fake fallback values for setup/regime/timing
- AI outputs only affect thesis/catalyst/risk/veto fields

Reject implementation if:
- old final-rank-only entry path still exists
- score alone can produce `ENTER`
- Codex can still effectively override Java hard rules
- review still lacks delay/MFE/MAE/setup/regime data

## 3. Handoff Deliverables Per Phase

### P0 Deliverables

- Regime module code
- Ranking module code
- Setup module code
- Timing module code
- Risk module code
- Execution module code
- DB migrations for all new P0 tables
- tests for each module

### P1 Deliverables

- Theme strength upgrade
- attribution engine
- trade review redesign
- weekly learning rewrite
- workflow rewiring

### P2 Deliverables

- bounded learning
- benchmark analytics
- exit-regime integration

## 4. Definition of Ready Before Claude Starts

- spec files created
- progress board created
- target architecture frozen for P0
- review gates defined

## 5. Definition of Done Before Merge

- all P0 modules implemented
- legacy final decision blob no longer owns ranking/timing/risk
- integration tests pass
- review logs show real setup/regime/timing attribution
- Codex review has signed off architecture separation
