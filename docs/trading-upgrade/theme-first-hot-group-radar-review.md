# Theme-first + Hot Group Radar 架構檢討與 MVP-6 建議

日期：2026-05-24  
範圍：Theme-first Candidate Engine、Hot Group Radar、Research Universe、Replay / Lifecycle / Metrics、安全邊界與 MVP-6 roadmap

---

## 1. Executive Summary

目前 Theme-first + Hot Group Radar 的演進，已經把系統從「個股分數排序 / 技術面選股」推進到「題材主線理解 + 研究宇宙 + replay 可追溯 + AI governance 約束 + shadow-first 安全擴張」的架構。

核心變化：

- 以前：系統主要看個股量價、強勢股、候選股 ranking，容易漏掉「同一題材族群內尚未進入前 5 名、但已經具備主線擴散價值」的股票。
- 現在：系統開始先理解「市場正在炒什麼題材」，再把 leader、retained leader、peer shadow、research universe、lifecycle replay、candidate universe 分層管理。

這已經不是單純 AI stock picker，也不是傳統量化 ranking engine，而是逐步形成一套：

```text
AI Market Intelligence System
+ Theme Evolution Engine
+ Research Governance Platform
+ Market Memory Graph 的前身
```

目前最重要的成果不是「讓 Hot Group Radar 可以買股票」，而是相反：

> 已經證明 Hot Group Radar 能看見漏掉的題材擴散，但仍被安全邊界鎖在 shadow / research / observability，不會直接改變 BUY / SELL / ENTER。

---

## 2. 已完成能力總覽

### 2.1 MVP-1A：題材辨識與 leadership observability

完成能力：

- 建立 theme taxonomy。
- 開始修正股票不是孤立個體，而是屬於題材 / 族群。
- 建立 leadership observability。
- 能觀察：
  - 哪個題材正在變強。
  - 哪些股票是題材 leader。
  - 題材與候選股之間是否出現 divergence。
- 加入 divergence detection，讓系統看見：

```text
題材很強，但候選股沒有涵蓋
leader 很強，但 tradable candidate 沒有跟上
強勢股被歸到「其他」導致主題丟失
```

價值：讓系統第一次有「主線題材視角」，不再只看個股分數。

---

### 2.2 MVP-2A：retained leaders / leader-tradable split

完成能力：

- retained leaders。
- leadership_symbols。
- leader / tradable split。

關鍵修正：

```text
國巨可以是題材 leader，但不一定是可交易標的。
國巨即使因高價、風險、RR、漲多、流動性、停損距離等因素不適合買，
仍然必須被保留為題材證據。
```

價值：

- leader 不再被候選池截斷。
- 高風險 leader 不會被誤當成 tradable。
- leader 可以驅動 peer discovery。
- 解決「主線明明很強，但 leader 本身不能買，所以整個題材被忽略」的問題。

---

### 2.3 MVP-2B：peer shadow discovery / safety isolation

完成能力：

- peer shadow discovery。
- 從 leader 找同題材 peer。
- 但 peer shadow 不直接進 tradable candidate。
- 建立 safety isolation。

示例：

```text
國巨強 → 被動元件題材強 → 找同族群華新科 / 日電貿 / 凱美 / 立隆電 / 九豪
```

安全邊界：

```text
peer shadow = 研究 / 觀察 / 補題材脈絡
peer shadow ≠ 可以買
peer shadow ≠ allowed_symbols
peer shadow ≠ FinalDecision candidate
```

---

### 2.4 MVP-3：AI governance enforcement

完成能力：

- 強制 AI 分析：
  - leadership。
  - divergence。
  - taxonomy gap。
  - peer shadow。
- validator enforcement。
- AI 不能只回「買 / 不買」，必須解釋：

```text
為什麼 leader 沒進候選？
為什麼題材強但候選缺席？
peer shadow 是否只是研究？
是否有 taxonomy gap？
是否有 outside allowed universe？
```

價值：

- 避免 Claude / Codex 隨意把 shadow 當 tradable。
- 避免 AI 忽略 taxonomy gap。
- 避免 AI 自行擴大候選池。
- 讓每次 AI 決策都有 governance trace。
- 讓人可以追查 AI 為何排除 / 降評 / 保留某股票。

---

### 2.5 MVP-4 / MVP-4B：Theme-first candidate backbone

完成能力：

- theme-first candidate backbone。
- candidate universe 10 support。
- themeImportanceScore。
- tradableScore。
- shadowRankScore。
- role-aware ranking。
- 國巨 / 被動元件 replay fixture。
- FinalDecision safety regression。

角色分層：

```text
leader
retained leader
tradable candidate
peer shadow
research-only
watch-only
```

不同角色有不同分數：

```text
themeImportanceScore：題材重要性
tradableScore：是否真的可交易
shadowRankScore：研究 / 觀察排序
```

價值：

- 同時保留「題材理解」與「交易安全」。
- 可以看 10 檔 universe，但不代表 10 檔都能買。
- 讓題材脈絡與交易候選分離。
- 讓 FinalDecision regression 證明新增 theme-first 不會污染正式交易決策。

---

## 3. Replay / Research / Lifecycle 已完成能力

### 3.1 MVP-5A：Theme Replay Timeline Backend

完成能力：

- 建立 replay timeline。
- 能回放某題材如何演進。
- 能看題材節點、股票節點、關聯邊。
- replay-only，不影響正式交易。

可回答：

```text
這個題材是什麼時候開始？
leader 何時出現？
peer 何時擴散？
候選池什麼時候漏掉？
AI 當時有沒有看見？
```

---

### 3.2 MVP-5B：Research Universe Formalization

完成能力：

- 正式建立 Research Universe。
- 將 research-only / shadow-only / tradable universe 分開。
- 不允許研究名單直接變成交易名單。

價值：

- 讓系統可以安全地看更多股票。
- AI 可以研究更多，但不能直接買更多。
- 研究擴張不等於交易擴張。
- 避免「因為 AI 看到了，所以就能買」的危險。

---

### 3.3 MVP-5C：Theme Lifecycle Engine Replay-only

完成能力：

- lifecycle classification：
  - EMERGING
  - MAINSTREAM
  - OVERHEATED
  - DISTRIBUTION
  - DEAD
- replay-only。
- 只作為研究 / 解釋 / 回放，不控制 BUY / SELL。

用途：

```text
輔助研究
輔助 replay
輔助風險提示
輔助人工檢討
```

不是直接下單。

---

### 3.4 MVP-5D：Replay Metrics Engine

完成能力：

- replay metrics。
- research metrics。
- safety metrics。
- forbidden safety counters。

價值：

```text
有沒有更早看見題材？
有沒有補到漏掉的 leader？
有沒有發現 peer shadow？
有沒有違反 Research / Tradable separation？
有沒有讓 shadow 影響正式交易？
```

---

## 4. Hot Group Radar 已完成能力與解決的問題

Hot Group Radar 解決的是 Theme-first 之前最容易出現的盲點：

```text
市場正在炒一整個族群，
但正式候選池只看見部分股票，
甚至因為 leader 過熱 / 高價 / 不可交易而漏掉整個題材擴散。
```

### 4.1 已完成能力

- 被動元件 taxonomy。
- Hot Group Radar shadow engine。
- theme-members。
- explain-miss。
- candidate-feed。
- restart-safe evidence persistence。
- ADD_TO_CANDIDATE_POOL_SHADOW。
- shadow / read-only safety boundary。

### 4.2 解決問題

#### 問題 1：強勢題材被「其他強勢股」吞掉

被動元件這類題材若 taxonomy 不完整，股票可能被歸到：

```text
其他強勢股
OTHER
UNKNOWN
```

結果：題材明明在擴散，但系統看不出是一個主線。

#### 問題 2：leader 被風險 gate 排除後，整個族群消失

修正後語意：

```text
國巨不能買，不代表被動元件題材不存在。
```

#### 問題 3：peer 沒有進入前 5 名，因此完全被忽略

華新科、日電貿、凱美、立隆電、九豪這類股票，可能不是當下最強前 5，但它們是題材擴散的重要證據。

Hot Group Radar 可以把它們納入：

```text
theme peer
watch-only
research-only
ADD_TO_CANDIDATE_POOL_SHADOW 候選
```

但不直接變成可買標的。

#### 問題 4：重啟後 explain-miss 證據消失

restart-safe evidence persistence 解決：

```text
服務重啟後，仍然可以回答為什麼某檔漏掉。
```

---

## 5. 被動元件案例分析

本次案例股票：

```text
國巨
華新科
日電貿
凱美
立隆電
九豪
```

### 5.1 原本為什麼漏掉？

主要不是單一 bug，而是多個結構性原因疊加。

#### 原因 A：taxonomy gap

被動元件 / MLCC / 鋁電容 / 通路代理 / 材料設備等分類原本不夠完整。

#### 原因 B：候選池截斷

傳統 candidate pool 可能只保留：

```text
super strong 5
final candidates 5
```

如果被動元件內部有 6～10 檔一起動，但不是每檔都在前 5，就會被截斷。

#### 原因 C：leader 不等於 tradable

國巨可能是 leader，但不一定是最佳交易標的。

正確語意：

```text
leader = market evidence
tradable = risk-adjusted candidate
```

#### 原因 D：peer 沒有正式研究身份

以前可能只有：

```text
候選股 / 非候選股
```

現在有：

```text
retained leader
peer shadow
research universe
watch-only
candidate-feed shadow
```

#### 原因 E：AI 沒有被強制分析 divergence / taxonomy gap

MVP-3 governance 補上這個缺口，要求 AI 必須回答：

```text
為什麼被動元件題材強，候選池沒有完整反映？
為什麼國巨是 leader 卻沒有進入候選？
哪些 peer 被漏掉？
```

---

## 6. 被動元件案例後來如何被補起來？

### 6.1 taxonomy 補題材骨架

```text
被動元件/MLCC
被動元件/鋁電容
被動元件/通路代理
被動元件/材料設備
```

### 6.2 Hot Group Radar 看見整群異動

Hot Group Radar 不只看單檔強弱，而是看：

```text
同題材是否多檔同步轉強？
leader 是否帶動 peer？
是否有 taxonomy peer seed？
是否有候選池漏網？
```

### 6.3 explain-miss 解釋「為什麼漏掉」

explain-miss 能回答：

```text
這檔在 universe 嗎？
是不是 hot stock？
是不是 final candidate fail？
是不是被 limit risk 擋掉？
是不是 radar watch-only？
是不是 shadow-only？
```

### 6.4 peer shadow 補題材擴散

從國巨這類 leader 出發，系統可以補同題材 peer：

```text
華新科
日電貿
凱美
立隆電
九豪
```

但身份是：

```text
peer shadow / watch-only / research-only
```

不是直接進 tradable universe。

### 6.5 retained leader 保留主線記憶

國巨即使不適合買，也不會被丟掉。

它可以繼續作為：

```text
題材 leader
題材熱度證據
peer discovery seed
lifecycle replay node
market memory seed
```

---

## 7. 目前最大優勢

### 7.1 市場主線理解

系統開始理解：

```text
市場不是一檔股票一檔股票在動，
而是資金沿著題材、族群、供需事件、leader-peer 結構在流動。
```

### 7.2 題材擴散理解

透過 retained leader + peer shadow，系統能看見：

```text
leader 先動
同題材 peer 接著動
主流族群開始擴散
部分股票雖不在候選池，但正在形成研究價值
```

### 7.3 Replay 可追溯

Replay 讓每個案例可以被拆解：

```text
當天系統看到什麼？
漏掉什麼？
AI 有沒有分析？
taxonomy 是否正確？
peer shadow 是否出現？
FinalDecision 是否被污染？
```

### 7.4 AI governance

AI governance 讓 AI 不只是自由分析，而是被迫回答：

```text
leader 是否不可交易？
shadow 是否不可 promotion？
taxonomy gap 是否存在？
divergence 是否需要解釋？
outside allowed universe 是否只是 research？
```

### 7.5 Research / Tradable separation

核心安全原則：

```text
Research Universe 可以擴大視野。
Tradable Universe 必須嚴格控管。
```

### 7.6 Shadow-first rollout

所有新能力先 shadow：

```text
不改 BUY
不改 SELL
不改 ENTER
不改 FinalDecision risk gate
不直接寫 production score
不直接擴大 allowed_symbols
```

---

## 8. 目前核心缺口

### 8.1 真正的 Hot Group Radar news / narrative layer 尚未完成

目前系統可以看見「族群在動」，但還不能完整理解「為什麼動」。

缺少：

```text
漲價
缺貨
AI server 拉貨
外資報告
政策
財報
法說展望
供應鏈事件
KOL / podcast narrative
```

### 8.2 題材事件來源不足

需要 Theme Event Engine，處理：

```text
漲價
缺貨
產能吃緊
政策補助
財報優於預期
法說展望
外資報告
產業新聞
供應鏈轉單
匯率 / 原物料變化
```

### 8.3 Market Memory Graph 尚未成形

未來應能連結：

```text
題材
股票
leader
peer
事件
新聞
KOL
法人
籌碼
成交量
lifecycle
AI 決策
miss reason
後續報酬
```

### 8.4 Theme evolution visualization 尚未完成

需要視覺化：

```text
題材時間軸
leader → peer 擴散圖
股票角色變化
EMERGING → MAINSTREAM → OVERHEATED
miss / promotion / rejection trace
```

### 8.5 長期 replay metrics 樣本仍不足

不能太早宣稱：

```text
Theme-first 一定提升報酬
Hot Group Radar 一定提高勝率
peer shadow 可以直接 promotion
```

必須累積：

```text
T+1 / T+3 / T+5 / T+10 forward returns
missed winner rate
false positive rate
shadow promotion quality
theme lifecycle accuracy
```

---

## 9. Safety Boundary：目前最重要安全邊界

### 9.1 Research Universe ≠ Tradable Universe

Research Universe 是為了擴大視野，Tradable Universe 是為了控制實際交易風險。

Research Universe 可以包含：

```text
leader
peer shadow
outside allowed universe
watch-only
taxonomy seed
narrative mention
lifecycle replay node
```

Tradable Universe 必須通過：

```text
market gate
risk gate
RR
liquidity
stop-loss distance
not overextended
allowed_symbols
FinalDecision validation
```

### 9.2 leader ≠ 可交易

leader 是題材證據，不等於交易標的。

```text
leader = market evidence
tradable = risk-adjusted candidate
```

### 9.3 peer shadow 不可直接 promotion

正確流程：

```text
peer shadow
→ research universe
→ replay metrics / forward tracking
→ promotion review
→ candidate pool shadow
→ bounded soft boost 或人工批准
→ tradable candidate
```

中間不能跳過。

### 9.4 lifecycle 不可直接控制 BUY / SELL

lifecycle classification 目前應維持 replay / advisory。

### 9.5 Hot Group Radar 不可直接 BUY

Hot Group Radar 的價值是看見主線與漏網，不是下單。

### 9.6 narrative / KOL 只能 weak signal

Narrative / KOL 可作為：

```text
研究優先級
題材背景
shadow context
crowding / hype risk
```

不可作為：

```text
直接 BUY
硬改排序
override risk gate
```

---

## 10. 必須永遠維持 0 的 safety metrics

以下 metrics 應該永遠維持 0，只要不為 0 就是重大安全事件：

```text
riskGateBypassCount = 0
leadershipOnlyEnteredCount = 0
leaderTradableFalseEnterCount = 0
researchVsTradableSeparationViolationCount = 0
hotGroupRadarDirectBuyCount = 0
peerShadowDirectPromotionCount = 0
peerShadowEnteredWithoutReviewCount = 0
lifecycleDirectBuySellControlCount = 0
narrativeDirectBuyCount = 0
kolSignalDirectBuyCount = 0
themeScoreOverrideRiskGateCount = 0
themeScoreOverrideMarketGateCount = 0
themeScoreOverrideStopLossCount = 0
themeScoreOverrideRRCount = 0
shadowWriteCandidateStockCount = 0
shadowWriteFinalDecisionCount = 0
shadowWriteProductionScoreCount = 0
allowedSymbolsExpandedByShadowCount = 0
finalDecisionChangedByReplayOnlyFeatureCount = 0
researchOnlySymbolEnteredFinalCandidateCount = 0
```

這些不是「目前還沒做到」的功能，而是應該永遠禁止的行為。

---

## 11. MVP-6 Roadmap 分析

候選方向：

```text
1. Narrative / KOL Shadow Radar
2. Theme Event Engine
3. Market Memory Graph
4. Cross-theme Rotation Engine
5. Promotion Review Workflow
6. Replay Dashboard UI
7. Institutional Flow Layer
```

建議：

> MVP-6 應該先做 Promotion Review Workflow，並平行設計 Theme Event Engine / Narrative Shadow Radar，但不要先 productionize narrative boost。

原因：目前 shadow / research / replay 能力已經很多，下一個瓶頸不是「再看更多訊號」，而是「如何安全地決定哪些 shadow 值得升級」。

---

## 12. 建議的 MVP-6 分期

### 12.1 MVP-6A：Promotion Review Workflow

優先做。

目標：

```text
把 shadow / research / retained leader / explain-miss / replay metrics 整合成一個可審核流程。
```

建議能力：

- `/api/promotion-review/queue`
- `/api/promotion-review/{symbol}/evidence`
- `/api/promotion-review/{symbol}/decision`

Review status：

```text
PENDING_REVIEW
RESEARCH_ONLY
WATCH_ONLY
CANDIDATE_POOL_SHADOW
REJECTED
NEED_MORE_EVIDENCE
```

Promotion evidence：

```text
taxonomy match
retained leader relation
peer shadow source
theme lifecycle
replay metrics
turnover / liquidity
risk blockers
explain-miss reason
institutional flow if available
```

成功標準：

```text
shadow candidate 可以被人工審核、留下理由、進入更高階 shadow 狀態，
但不能直接變成 tradable candidate。
```

---

### 12.2 MVP-6B：Theme Event Engine Shadow

目標：補上題材事件原因。

事件類型：

```text
PRICE_HIKE
SHORTAGE
POLICY_SUPPORT
EARNINGS_BEAT
GUIDANCE_UP
FOREIGN_REPORT
SUPPLY_CHAIN_SHIFT
CAPACITY_TIGHT
ORDER_VISIBILITY
SECTOR_ROTATION
```

資料來源先從低風險開始：

```text
公開新聞
公司公告
法說摘要
財報摘要
TWSE 公告
產業報告摘要
人工輸入事件 fixture
```

安全邊界：

```text
event = evidence
event ≠ buy signal
event ≠ ranking override
event ≠ risk gate bypass
```

---

### 12.3 MVP-6C：Narrative / KOL Shadow Radar

目標：把 KOL / podcast / YouTube / 社群 narrative 變成弱訊號 context。

必須限制為：

```text
weak signal only
source credibility score
freshness score
crowding risk
hype risk
contradiction detection
no direct boost
```

---

### 12.4 MVP-6D：Replay Dashboard UI

建議 dashboard 顯示：

```text
theme timeline
leader-peer graph
research vs tradable separation
explain-miss list
promotion queue
lifecycle stage
safety metrics
```

---

### 12.5 MVP-6E：Institutional Flow Layer

建議先 shadow integration：

```text
外資 / 投信 / 自營商
連買連賣
買超與題材一致性
leader 強但法人倒貨
peer 補漲但籌碼不支持
```

不可直接作為買賣 gate，但可作為 promotion evidence。

---

### 12.6 MVP-6F：Cross-theme Rotation Engine

中長期方向。

需要先有：

```text
穩定 theme taxonomy
長期 replay metrics
market memory graph
institutional flow
event layer
```

---

## 13. 長期願景

這套系統長期不應只是：

```text
AI stock picker
技術面 ranking engine
傳統量化模型
```

它更像是以下幾種系統的融合：

### 13.1 AI Market Intelligence System

回答：

```text
今天市場主線是什麼？
主線是否延續？
誰是 leader？
誰是 lagging peer？
哪個題材正在輪動？
哪個題材只是短線噪音？
```

### 13.2 Market Narrative Replay System

回放：

```text
某題材從新聞出現、leader 發動、peer 擴散、法人進場、AI 研究、候選池變化，到最後成功或失敗的完整過程。
```

### 13.3 Theme Evolution Engine

追蹤：

```text
EMERGING → MAINSTREAM → OVERHEATED → DISTRIBUTION → DEAD
```

### 13.4 Research Governance Platform

管理：

```text
哪些股票只是研究？
哪些是 watch-only？
哪些 shadow candidate 值得 review？
哪些經過 review 後可進候選池？
哪些永遠不可交易？
AI 是否遵守 governance？
```

### 13.5 Market Memory Graph

最終形成：

```text
題材 - 股票 - 事件 - 新聞 - KOL - 法人 - 技術型態 - lifecycle - 決策 - 結果
```

---

## 14. 哪些絕對不能太早 productionize

### 14.1 Narrative / KOL direct boost

不可讓 KOL 或 narrative 直接加分到正式排序。

### 14.2 Hot Group Radar direct BUY

不可。

### 14.3 Peer shadow auto promotion

不可。

### 14.4 Lifecycle direct BUY / SELL

不可。

### 14.5 Theme score override risk gate

不可。

### 14.6 Bounded soft boost without replay proof

不可。

### 14.7 Institutional flow hard gate

不可。

### 14.8 Cross-theme rotation auto decision

不可。

---

## 15. 最終建議

### 第一優先：MVP-6A Promotion Review Workflow

先把目前已經長出來的 shadow / research / replay 能力接成一個可審核流程。

目標不是讓系統更會買，而是讓系統更安全地回答：

```text
這檔為什麼被 radar 看到？
它目前是 research-only 還是 candidate-pool-shadow？
要升級還缺什麼證據？
誰批准？
批准後仍然不直接 BUY。
```

### 第二優先：MVP-6B Theme Event Engine

補上題材因果來源。

讓系統能回答：

```text
被動元件為什麼動？
是漲價、缺貨、外資報告、財報，還是單純資金輪動？
```

初期只能 shadow / evidence，不影響正式交易。

### 第三優先：MVP-6C Narrative / KOL Shadow Radar

可以做，但必須非常保守。

KOL / narrative 是弱訊號，不是交易訊號。

最佳用途：

```text
提醒研究
補充題材背景
偵測 crowding / hype risk
提供 AI context
```

### 第四優先：Replay Dashboard UI

目前 backend 能力已經很多，下一步需要讓人更容易看懂：

```text
題材時間軸
leader-peer 擴散
explain-miss
promotion queue
safety metrics
```

### 第五優先：Market Memory Graph / Cross-theme Rotation / Institutional Flow

長期價值高，但需要建立在：

```text
穩定 taxonomy
穩定 event source
穩定 replay metrics
穩定 promotion workflow
```

之上。

---

## 16. 最後結論

目前 Theme-first + Hot Group Radar 已經完成的不是「多一個選股模型」，而是建立了一套更高階的市場理解架構：

```text
題材先行
leader 保留
peer shadow 發現
research/tradable 分離
AI governance 強制分析
replay 可追溯
lifecycle 可回放
Hot Group Radar 可解釋漏選
安全邊界不碰 BUY / SELL / ENTER
```

下一階段最正確的方向不是急著讓 Radar 買股票，而是：

> 先做 Promotion Review Workflow，把 shadow 發現變成可審核、可追蹤、可回放、可拒絕、可逐步升級的治理流程；再補 Theme Event / Narrative layer，最後才考慮經過 replay 證明的 bounded soft boost。
