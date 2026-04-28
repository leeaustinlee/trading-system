# 候選股 P0 落地審查 2026-04-29

審查對象：commits `74f86c7..083b236` (4 commits)，3 subagent 合併 PR：

- A：`MomentumCandidateEngine` hard gate 接到 `CandidateController.batchUpsert` 之前
- B：`scripts/snapshots/market-breadth-scan-2026-04-29.ps1` 三段式 changePct 策略
- C：`tradabilityTag` 從 `candidate_stock.payload_json` 流到 `FinalDecisionEngine`，hard block / soft penalty

審查方式：純看 git diff，不依賴上下文。建構於 working-tree 已 staged 一份**反向 diff** 的混亂環境中（見 §G）。

---

## A. Verdict

- **Overall: SAFE_TO_PUSH**（前提是先處理 working-tree 異常 + 一條 NEEDS_FIX）
- **Tests on committed HEAD：14 個新測試 / 14 通過**；全套 770 測試中 5–7 個失敗，全部為 baseline 既有 flaky/state-bleed，已逐一驗證 baseline (`74f86c7`) 失敗一致，**非本 PR 引入**。
- **BLOCKERS：0**（commit 內容正確）
- **NEEDS_FIX：1**
- **STRATEGY_NOTES：3**

> 注意：本 review **不採信 working tree**。working tree 有一份staged 的反向 diff（恢復 pre-PR 狀態），會讓 `Read`、`grep`、`mvn clean test` 全部誤判為 BLOCKER。所有結論以 `git show HEAD:<path>` 為準，並對 git blob hash 做交叉驗證。

---

## B. Per-subagent scoring (a/b/c/d/e grid)

| Subagent | (a) Correctness | (b) Side-effects | (c) Test coverage | (d) Risk | (e) Reversibility | 結論 |
|---|---|---|---|---|---|---|
| **A**：Momentum Gate | OK | OK | OK | OK（fallback 寬鬆，PS 上游不會被誤殺；UI 手動 add 會被擋，見 NEEDS_FIX-1） | OK（`candidate.momentum_gate.enabled` 一行回退） | **OK** |
| **B**：PS 三段式 | OK（boundary 7.0 / 8.8 嚴謹） | OK（新檔，原 `market-breadth-scan.ps1` 未動） | N/A（PS 無單測；header 內建 manual cases） | OK | OK（刪檔即回退） | **OK** |
| **C**：Tradability Tag | OK（順序對：kill-switch > priceGate > ChasedHigh > **TAG** > bucket；`withTradabilityTag` 在 32-arg 還原路徑補回 tag） | OK（4 個 ctor 覆蓋 31/39/40/41-arg；既有 caller 全程相容） | OK（5 engine + 1 LINE Builder + 邊界 case：軟懲罰把 B 邊界踢到 C） | OK | OK（`final_decision.respect_tradability_tag.enabled` 一行回退） | **OK** |

---

## C. BLOCKERs

**無**。

---

## D. NEEDS_FIX

| # | 路徑 / 行 | 問題 | 建議修法 |
|---|---|---|---|
| 1 | `src/main/resources/static/index.html:2925` 配 `CandidateController.java:120` | Dashboard 手動「新增候選股」表單只送 `score / themeTag / reason` 等，**沒有 payloadJson、claudeScore、codexVetoed**。預設 `candidate.momentum_gate.enabled=true` 下，gate 算 priceMomentum=F、volume=F、theme=F、ma=T、aiSupport=T → matched=2/5 → `INSUFFICIENT_CONDITIONS` 退件。前端 `try { await api(...) }` 不會 throw（HTTP 200），只 close modal + reload，**user 沒提示，候選沒進 DB**，使用者體驗變成「按了沒反應」。 | 三個選項擇一：(1) 前端在收到 `accepted=0 && rejected>0` 時顯示 `rejections[0].reason`；(2) 後端對 `valuationMode/entryPriceZone` 等手動欄位齊全的 case 視為 manual override，bypass gate；(3) 文件明寫「dashboard 手動 add 必須先關 flag」並在 UI 加警示。建議 (1) 最低成本。 |

---

## E. Strengths

1. **Subagent A 的 fallback 哲學寫得對**：未填欄位採寬鬆中性、已填壞訊號採嚴格。`amountYi >= 1.0 → volumeRatio=1.5` 剛好打到 engine 1.5 門檻，避免 PS 上游沒填 `volumeRatio` 就把所有候選殺掉。Test 5 直接驗證「空欄位只能被軟性退件，不會被 HARD_VETO_*」是好測試。
2. **Subagent C 的順序很穩**：tag block 放在 PriceGate 之後、bucketing 之前，kill switch（`TRADING_DISABLED` priority 5）仍勝過一切。Soft penalty 邊界 case `softPenaltyDoesNotKickToB_ifWasJustB` 真的會發生（6.55+0.3=6.85→剛 B；扣 1.0 後 5.85→C），測試把「軟懲罰會降桶」這個語意鎖住。
3. **Tier 1 邊界 7.0**：PS `-le $tier1MaxPct` 與 `-gt $tier1MaxPct` 互斥，沒有重疊也沒有 gap；7.0 算 Tier 1 主候選、7.01 算 Tier 2。8.8 算 Tier 2、8.81 算 Tier 3。`IsLimitRisk` 與 Tier 3 的 OR 條件保證 9.2%+0.995 鎖漲停一定進 Tier 3。
4. **Legacy ctor 全覆蓋**：31-arg / 39-arg / 40-arg / 41-arg 四個 ctor 一致委派，每個 path 都把 `tradabilityTag` 以 `null` 預設帶過，行為「未指定 tag = 主候選 = 不觸發 block」明確且穩定。`withTradabilityTag` / `withFinalRankScore` 透明保留所有其他欄位，`applyScoringPipeline` 用 32-arg ctor 重建後立刻 `withTradabilityTag` 補回，pipeline 不漏 tag。
5. **PS Big5/UTF-8 不會打到**：`market-breadth-scan-2026-04-29.ps1` 用 `Set-Content -Encoding UTF8`（檔頭 `efbbbf` BOM 確認）；JSON 經 REST 傳到 Java 後一律 UTF-8 byte stream；MySQL `JSON` 型別 = UTF-8。`tag.contains("不列主進場")` 以 Java String code points 比對，不依賴 byte-level encoding。

---

## F. Strategy-level concerns（≤ 3）

1. **`themeTag` 對不上 `theme_snapshot`**：PS 寫的是中文族群名（`AI伺服器/電腦週邊`、`PCB/載板/材料`、`半導體/IC`），但 Java Theme Engine v2 的 `theme_snapshot` 用內部 tag（`AI_PCB`、`MEMORY` 等）。`buildEngineInput` 對 `themeRank` / `finalThemeScore` 的 DB 查詢幾乎一定 miss，回 `rank=99 / score=5.0`，**`theme` 條件對 PS-driven 候選永遠 FALSE**。只有當 PS 候選同時滿足 `priceMomentum + ma(neutral) + volume + aiSupport` 才 matched=4/5。實務上 04-28 的 5 檔 changePct ≥ 9.2% 都會通過 gate（priceMomentum=T + volume=T fallback），最終靠 `tradabilityTag="不列主進場"` 在 FinalDecisionEngine 被擋。**Gate 對 PS 候選實質只 enforce「Codex 沒 veto + claude>=4」**；momentum signal（newHigh20、ma5、consecUp、breakout）完全沒在 enforce。建議下一輪把 PS 改寫 `themeTag` 用 Theme Engine v2 內部 tag，否則 `theme` 條件只是擺設。

2. **「漲幅過大，僅參考」軟懲罰 -1.0 的 calibration**：候選分數來源是 PS `score = pct*1.25 + log10 * 3 + boosts`，pct=7.5 大概 score=18-22 區間（成交量大時更高）；Java side `finalRankScore` 是 0–10。-1.0 在 0–10 尺度等於 -10%，足以把 7.0 變 6.0、剛好掉到 C。但若 PS 上游把 score 直接送進 Java 而沒 normalise，-1.0 可能反而太輕。`FinalDecisionService.applyScoringPipeline` 是怎麼把 PS 的 score 對到 0–10 finalRankScore，建議 commit 內加一個 `Tier2 score 7.5 entry` 的 e2e 整合測試，把 PS scoring path 鎖住。

3. **手動候選新增 silent failure**：見 NEEDS_FIX-1。Austin 自己若用 dashboard 手動「我看到 2330 想加進去」，按下儲存 → 沒反應 → 列表沒新增。debugging 不容易。release 前最好加一行 UI alert。

---

## G. 環境警示（不影響本 PR 評估，但下一個 reviewer 必讀）

審查啟動時 `/mnt/d/ai/stock/trading-system` working tree 有一份 staged 的反向 diff（8 個檔案，-495 lines / +30 lines），把 PR 整個還原回 pre-PR 狀態。`git status` 顯示 "Changes to be committed"。實際情境推測是某個 user/agent staged 一份回滾但還沒 commit。

直接看 `Read` / `Grep` / `mvn clean test` 會誤判為「commit 不完整」。本 review 全程改用 `git show HEAD:<path>`、`git cat-file -p <blob>` 對照 commit 內容，並在 `git stash` 後 `mvn clean test` 確認 commit 本身可以乾淨編譯、新測試全綠。

**push 前必須**：

```bash
cd D:/ai/stock/trading-system
git status                    # 確認那一份 staged revert 是否仍在
git restore --staged .        # 取消 staged
git checkout -- .             # 同步 working tree 到 HEAD
mvn clean test -Dtest='CandidateControllerMomentumGateTests,FinalDecisionEngineTradabilityTagTests,LineMessageBuilderTests'
```

---

## 附錄：14 個新測試

- `CandidateControllerMomentumGateTests`（5）：goodCandidate pass、claudeLow veto、flag-off bypass、5 進 3 過 2 退、空欄位只 INSUFFICIENT。
- `FinalDecisionEngineTradabilityTagTests`（5）：tag block REST、軟懲罰仍 ENTER、無 tag 不影響、flag=false 失效、邊界 B→C。
- `LineMessageBuilderTests` 增加 1：TRADABILITY_TAG_BLOCK 進 Top 2、優先級 12 介於 hard gate (10) 與 CHASED_HIGH (15) 之間。
- 既有 `CandidateControllerMomentumGateTests` setUp 還順帶覆蓋 7-arg `CandidateScanService` ctor、theme snapshot mock fallback。
