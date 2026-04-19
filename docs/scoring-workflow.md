# BC Sniper v2.0 評分工作流程
> **共同入口**：Claude、Codex、Java 每次執行評分相關操作前，必須先讀此文件。
> **系統版本**：`v2.0-bc-sniper`（對應 `score_config.scoring.version`）
> **Java App**：`http://localhost:8080`

---

## 一、評分管線總覽

```
候選股 (candidate_stock)
        │
        ▼
[1] Java 結構評分          ← 自動（FinalDecisionService 觸發）
    java_structure_score 0-10
        │
        ▼
[2] Claude 深度研究         ← Claude 執行後呼叫 API 回填
    claude_score 0-10
    claude_confidence 0-1
    claude_thesis
    claude_risk_flags[]
        │
        ▼
[3] Codex 審核              ← Codex 執行後呼叫 API 回填
    codex_score 0-10
    codex_confidence 0-1
    codex_review_issues[]
        │
        ▼
[4] 共識評分（自動）
    consensus_base  = min(java, claude, codex)
    penalty         = |j-c|×0.25 + |j-x|×0.20 + |c-x|×0.20
    consensus_score = max(base - penalty, 0)
        │
        ▼
[5] 加權評分（自動）
    ai_weighted_score = java×0.40 + claude×0.35 + codex×0.25
        │
        ▼
[6] VetoEngine（自動）
    is_vetoed / veto_reasons_json
        │
        ▼
[7] 最終排序分（自動）
    final_rank_score = min(ai_weighted, consensus)
    若 is_vetoed → final_rank_score = 0
        │
        ▼
[8] 分級 & 決策（FinalDecisionEngine）
    A+(≥8.8, RR≥2.5) → 可進場
    A (≥8.2)         → 觀望
    B (≥7.4)         → 觀望
    C (其餘)         → 休息
```

---

## 二、Java 結構分（自動，無需 AI 介入）

**觸發時機**：`POST /api/decisions/final/evaluate` 或盤後自動排程。

**計算公式**（JavaStructureScoringEngine）：

| 維度 | 滿分 | 規則 |
|---|---|---|
| RR 風報比 | 4 | `min(RR × 1.3, 4.0)` |
| 計畫品質 | 2 | 納入最終計畫 +1；有停損 +1 |
| 進場型態 | 2 | BREAKOUT=2, REVERSAL=1.5, PULLBACK=1 |
| 估值風險 | 1 | VALUE_LOW=1, VALUE_FAIR=0.8, VALUE_HIGH=0.4, VALUE_STORY=0.2 |
| 加分 | 1 | 有題材標籤 +0.5；候選分 ≥ 7 +0.5 |
| **合計** | **10** | `min(sum, 10)` |

---

## 三、Claude 深度研究評分

### 3.1 評分標準（0-10）

Claude 對每一檔候選股，從以下四個面向加總評分：

| 面向 | 滿分 | 評分細節 |
|---|---|---|
| **基本面** | 2.5 | 營收成長性、獲利能見度、題材真實性 |
| **籌碼面** | 2.5 | 法人買超連續性、融資融券比率、主力成本帶 |
| **技術面** | 2.5 | 型態完整性（底部/突破/回測）、5/20 日線位置、量能結構 |
| **風險面** | 2.5 | 法說/除權息/財報風險、高檔追價風險、大盤環境 |

> **評分原則**：
> - 9-10：少見，需基本面、籌碼面、技術面三者全部強勢，且無明顯風險
> - 7-8：正常優質標的，可進入決策
> - 5-6：中等，建議等更好的進場點
> - 3-4：疑慮多，建議觀望
> - 0-2：明確排除

### 3.2 輸出格式

Claude 必須輸出以下 JSON，寫入 `D:\ai\stock\claude-research-latest.md`，
同時呼叫 API 回填（見 3.3）：

```json
{
  "symbol": "2330",
  "tradingDate": "2026-04-18",
  "claudeScore": 8.5,
  "claudeConfidence": 0.85,
  "claudeThesis": "AI CoWoS 訂單能見度高，法人連三買，技術面整理完畢...",
  "claudeRiskFlags": ["高檔追價風險", "本益比偏高"]
}
```

`claudeRiskFlags` 標準代碼（可複選）：
- `"高檔追價風險"` — 已距低點漲幅 > 20%
- `"法說前風險"` — 法說會在 5 個交易日內
- `"除權息前風險"` — 除息/除權在 10 個交易日內
- `"籌碼疑慮"` — 融資增加、法人出貨跡象
- `"大盤轉弱"` — 大盤等級 B 或 C
- `"量能不足"` — 近 5 日均量低於 3000 張
- `"財報風險"` — 近期財報表現不如預期

### 3.3 回填 API

```http
PUT http://localhost:8080/api/candidates/{symbol}/ai-scores
Content-Type: application/json

{
  "tradingDate": "2026-04-18",
  "claudeScore": 8.5,
  "claudeConfidence": 0.85,
  "claudeThesis": "AI CoWoS 訂單能見度高...",
  "claudeRiskFlags": ["高檔追價風險"]
}
```

**成功回應**：`200 OK`，回傳更新後的 `stock_evaluation` 資料（含自動重算的 `final_rank_score`）。

---

## 四、Codex 審核評分

### 4.1 評分標準（0-10）

Codex 對 Claude 研究的標的做「第二層風控審核」，重點在於：

| 審核項目 | 扣分邏輯 |
|---|---|
| 即時報價確認 | 現價跌破開盤或昨收 → -2 |
| 籌碼即時確認 | T86 當日法人方向與研究結論相反 → -2 |
| 市場同步性 | 主流族群當日未同步上漲 → -1 |
| 進場時機 | 距突破點過遠（> 3%）→ -1 |
| 事件風險 | 重大事件（法說/財報）在 3 日內 → -1 |

> 從 10 分起扣，每項獨立，最低 0 分。

### 4.2 輸出格式

```json
{
  "symbol": "2330",
  "tradingDate": "2026-04-18",
  "codexScore": 7.2,
  "codexConfidence": 0.78,
  "codexReviewIssues": ["法說前布局風險", "進場位置略遠"]
}
```

`codexReviewIssues` 標準代碼：
- `"現價跌破開盤"` — 即時報價確認失敗
- `"現價跌破昨收"` — 方向不明確
- `"法人方向相反"` — T86 當日法人賣超
- `"族群未同步"` — 同題材個股今日未跟漲
- `"進場位置略遠"` — 距突破點 > 3%
- `"法說前布局風險"` — 法說在 3 日內
- `"財報發布在即"` — 財報在 3 日內

### 4.3 回填 API

```http
PUT http://localhost:8080/api/candidates/{symbol}/ai-scores
Content-Type: application/json

{
  "tradingDate": "2026-04-18",
  "codexScore": 7.2,
  "codexConfidence": 0.78,
  "codexReviewIssues": ["法說前布局風險"]
}
```

> Claude 與 Codex 可以用同一個 API 分開呼叫，系統會 merge 而非覆蓋。

---

## 五、題材評分（Claude 提供）

### 5.1 題材熱度與延續評分

盤後 15:30，Claude 對每個題材 snapshot 輸出 `themeHeatScore` 與 `themeContinuationScore`：

| 欄位 | 範圍 | 說明 |
|---|---|---|
| `themeHeatScore` | 0-10 | 題材今日熱度（新聞量、社群聲量、法說話題） |
| `themeContinuationScore` | 0-10 | 題材延續性（是否仍有新催化劑、法人持續介入） |
| `driverType` | 字串 | 題材驅動類型，如「法說」「政策」「訂單」「技術突破」 |
| `riskSummary` | 字串 | 一句話風險摘要 |

**最終題材分計算**（自動）：
```
final_theme_score = market_behavior × 0.55
                  + themeHeatScore  × 0.25
                  + themeContinuationScore × 0.20
```

### 5.2 回填 API

```http
PUT http://localhost:8080/api/themes/snapshots/{themeTag}/claude-scores
Content-Type: application/json

{
  "tradingDate": "2026-04-18",
  "themeHeatScore": 8.5,
  "themeContinuationScore": 7.0,
  "driverType": "法說",
  "riskSummary": "高檔追價風險，本週法說後可能獲利了結"
}
```

---

## 六、Veto 規則一覽（全部 14 條）

系統自動執行，**任一條觸發 → is_vetoed = true，final_rank_score = 0**。

### v1.0 原有規則

| 代碼 | 條件 | 來源設定 key |
|---|---|---|
| `MARKET_GRADE_C` | 市場等級 = C | — |
| `DECISION_LOCKED` | 決策鎖啟動 | — |
| `TIME_DECAY_LATE` | 10:30 後且市場非 A | `scoring.late_stop_market_grade` |
| `RR_BELOW_MIN` | RR < 2.2（A市場）或 < 2.5（B市場） | `scoring.rr_min_grade_a/b` |
| `NOT_IN_FINAL_PLAN` | 未標記納入最終計畫 | — |
| `NO_STOP_LOSS` | 無停損設定 | — |
| `HIGH_VAL_WEAK_MARKET` | VALUE_HIGH/STORY 在非 A 市場 | — |

### v2.0 BC Sniper 新增規則

| 代碼 | 條件 | 來源設定 key |
|---|---|---|
| `NO_THEME` | 無題材標籤（require_theme=true 時） | `veto.require_theme` |
| `CODEX_SCORE_LOW` | codexScore < 6.5 | `veto.codex_score_min` |
| `THEME_NOT_IN_TOP` | themeRank > 2 | `veto.theme_rank_max` |
| `THEME_SCORE_TOO_LOW` | finalThemeScore < 7.5 | `veto.final_theme_score_min` |
| `SCORE_DIVERGENCE_HIGH` | \|java - claude\| ≥ 2.5 | `veto.score_divergence_max` |
| `VOLUME_SPIKE_NO_BREAKOUT` | 爆量但未突破近期高點 | — |
| `ENTRY_TOO_EXTENDED` | 進場位置距突破點太遠 | — |

---

## 七、分級門檻

| 等級 | final_rank_score | RR 條件 | 可進場？ | 設定 key |
|---|---|---|---|---|
| **A+** | ≥ 8.8 | ≥ 2.5 | ✅ 唯一可進場等級 | `scoring.grade_ap_min`, `scoring.rr_min_ap` |
| A | ≥ 8.2 | — | ❌ 觀望 | `scoring.grade_a_min` |
| B | ≥ 7.4 | — | ❌ 觀望 | `scoring.grade_b_min` |
| C | < 7.4 | — | ❌ 休息 | — |

**最終決策邏輯**（FinalDecisionEngine）：
- A+ 數量 ≥ 2 → ENTER，選前 2 名
- A+ 數量 = 1 → ENTER，選 1 名
- A+ 數量 = 0 → REST

---

## 八、評分設定一覽（score_config 表）

所有門檻均可透過 `GET /api/score-config` 查詢、`PUT /api/score-config/{key}` 調整，無需改程式碼。

### 權重

| Key | 預設 | 說明 |
|---|---|---|
| `scoring.java_weight` | 0.40 | Java 結構評分權重 |
| `scoring.claude_weight` | 0.35 | Claude 研究評分權重 |
| `scoring.codex_weight` | 0.25 | Codex 審核評分權重 |

### 共識懲罰係數

| Key | 預設 | 說明 |
|---|---|---|
| `consensus.penalty_jc` | 0.25 | Java vs Claude 分歧懲罰 |
| `consensus.penalty_jx` | 0.20 | Java vs Codex 分歧懲罰 |
| `consensus.penalty_cx` | 0.20 | Claude vs Codex 分歧懲罰 |

### 候選股數量

| Key | 預設 | 說明 |
|---|---|---|
| `candidate.scan.maxCount` | 8 | 全市場掃描後最大候選數 |
| `candidate.research.maxCount` | 3 | 送 Claude 深度研究的數量 |
| `candidate.notify.maxCount` | 5 | 盤後 LINE 通知最大數 |
| `decision.final.maxCount` | 2 | 最終進場最大數 |

---

## 九、每日評分觸發時序

```
15:30 盤後
  Codex → 抓今日題材 + 個股量價
  Codex → POST /api/candidates/batch（批次存入候選股）
  Java  → 自動計算 market_behavior_score（ThemeSelectionEngine）
  Claude → 題材研究 → PUT /api/themes/snapshots/{tag}/claude-scores（每個題材）
  Claude → 個股研究（前 3 名）→ PUT /api/candidates/{symbol}/ai-scores
  Codex  → 個股審核（前 3 名）→ PUT /api/candidates/{symbol}/ai-scores

18:10 T86 補充
  Codex → 確認法人方向，視需要更新 codexScore
  Codex → PUT /api/candidates/{symbol}/ai-scores（只更新 codex 欄位）

08:30 盤前
  Codex → POST /api/decisions/final/evaluate
       （觸發完整管線：java → consensus → veto → weighted → grade → decision）

09:30 最終決策
  Codex → GET /api/dashboard/current（確認最新 final_rank_score）
  Codex → 即時報價確認 → 若失敗則不進場
  Codex → 提交 /api/ai/tasks/{id}/codex-result（含 A+ 名單或 REST 原因），Java 負責 LINE 通知
```

---

## 十、快速查詢 API

```http
# 今日候選股（含所有評分欄位）
GET http://localhost:8080/api/candidates/current

# 今日題材快照（含 final_theme_score、rankingOrder）
GET http://localhost:8080/api/themes/snapshots?date=2026-04-18

# 觸發完整評分管線
POST http://localhost:8080/api/decisions/final/evaluate

# 儀表板（候選股 + 市場 + 決策 + 持倉一次看）
GET http://localhost:8080/api/dashboard/current

# 查詢 / 調整評分設定
GET  http://localhost:8080/api/score-config
PUT  http://localhost:8080/api/score-config/{key}
Body: { "value": "8.5" }
```

---

## 十一、評分品質保證規則

1. **Claude 評分不得只看新聞**：必須同時參考量能、籌碼、型態，任何面向缺乏資料時，該面向評為 5 分（中性），並在 `claudeThesis` 說明原因。
2. **Codex 評分必須有即時報價**：未確認當前報價前，不得送出 codexScore。報價取得失敗時，填入 `codexScore: null`，不得填估計值。
3. **分歧保護**：若 `|java - claude| ≥ 2.5`，系統自動觸發 `SCORE_DIVERGENCE_HIGH` veto。這代表研究品質有問題，Claude 應重新審視。
4. **題材先決**：`veto.require_theme = true` 時，無題材標籤的股票直接 veto。Codex 選股時必須確保每檔都有對應題材。
5. **A+ 才能進場**：不論任何情況，`final_rank_score < 8.8` 或 `RR < 2.5` 的標的一律不進場。若所有候選都未達 A+，系統輸出 REST，當日不操作。

