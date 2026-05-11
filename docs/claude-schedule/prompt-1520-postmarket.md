# Claude 排程研究 — 15:18 POSTMARKET task-scoped 研究（沿用 legacy 檔名）

執行時間：每週一至週五 15:18（Asia/Taipei，Hermes 現行排程）
對應 Codex 通知：15:30 `AustinStockAftermarket1530`

---

## 0. 核心原則（本版最重要）

你正在執行的是 POSTMARKET task-scoped research。

本輪正式研究 universe 只有一個來源：
- `D:/ai/stock/claude-research-request.json` 內的 `allowed_symbols`

你必須遵守：
- 只能分析 `allowed_symbols` 內的股票
- 不可自行新增 `allowed_symbols` 以外的 symbol
- 不可把 fresh scan / watchlist / 固定觀察股混入本輪 scoring
- 若 `allowed_symbols` 只有 5 檔，就只分析 5 檔
- 若 `allowed_symbols` 有 10 檔，才可在文中區分 `super strong` 與 `final candidates`
- `scores` / `thesis` 的 key 必須是 `allowed_symbols` 子集

一句話：

「本輪只研究 request.allowed_symbols，不多也不少。」

---

## 1. 環境與路徑解析

本 prompt 會同時被兩種環境執行，請先判斷當下環境並自動翻譯路徑：

| 執行環境 | stock 根目錄路徑 |
|---|---|
| 本機 Windows PowerShell / WSL Claude CLI | `D:/ai/stock/` 或 `/mnt/d/ai/stock/` |
| Cowork cloud sandbox Claude | `/sessions/happy-gracious-pasteur/mnt/stock/` |

底下章節出現的 `D:/ai/stock/...` 一律請翻譯成你當下環境能讀寫的對應路徑，兩邊指向同一份檔案。若其中一條路徑 read 失敗，改用另一條。

---

## 2. 第一步：必讀檔案

1. `D:/ai/stock/AI_RULES_INDEX.md`
2. `D:/ai/stock/dual-ai-workflow.md`
3. `D:/ai/stock/market-snapshot.json`
4. `D:/ai/stock/capital-summary.md`
5. `D:/ai/stock/claude-research-request.json`
6. `D:/ai/stock/market-gate-self-optimization-engine.md`
7. `D:/ai/stock/trade-decision-engine.md`

補充：
- `codex-research-latest.md` 可作背景參考
- 但本輪正式 universe 與 market context 以 `claude-research-request.json` 為準

---

## 3. 第二步：先讀 request，確認正式 contract

先讀 `D:/ai/stock/claude-research-request.json`，至少取出：

- `taskId`
- `taskType`
- `tradingDate` 或 `trading_date`
- `allowed_symbols`
- `market_context_payload`（若存在）
- `market_context`（若只有字串）
- `output_path`
- `submit_filename_hint`

你必須先做以下檢查：

1. `taskType` 必須 = `POSTMARKET`
2. `taskId` 不可缺失
3. `allowed_symbols` 不可為空
4. 若 `market_context_payload.scoringUniverse.symbols` 存在，必須以它作為本輪正式 scoring universe
5. 若 `market_context_payload.marketGrade` 存在，必須直接沿用，不要自行改寫成另一個 A/B/C

若上述任一條件不成立：
- 不要亂補股票
- 不要硬做雙五研究
- 在 markdown 明確標示「POSTMARKET request contract 不完整，本輪僅保守摘要，不提交正式 scoring」

---

## 4. 第三步：盤後市場總結

以 request market context 為正式來源，整理：
- marketGrade
- 漲跌家數 / 指數漲跌
- 台積電與大盤是否仍支持明日延續
- 今日主流題材與風險

注意：
- `marketGrade` 若 request 已提供，直接使用該值
- 不要再自行產生另一個互相矛盾的 marketGrade

---

## 5. 第四步：只研究 allowed_symbols

### 5.1 Universe 呈現規則

若 `allowed_symbols` 數量 = 5：
- 直接做 5 檔研究
- 不要硬拆成「超強勢 5 + 候選 5」

若 `allowed_symbols` 數量 > 5，且 request `market_context_payload.scoringUniverse` 提供：
- 可依 `superStrongSymbols`
- 與 `finalCandidateSymbols`
- 分組呈現

若 request 沒有提供分組資訊：
- 直接依 `allowed_symbols` 順序逐檔研究

### 5.2 每檔研究框架

每檔至少包含：

```text
股票：代號 / 名稱
今日收盤 / 漲幅 / 成交量（資料不足要標示）
題材定位：主題、族群、是否主流、延續性
基本面：近期營收、法說、重大事件（若不足需標示）
籌碼面：外資 / 投信 / 自營商 / 主力 / 融資券（資料不足需標示）
技術面：均線、量價、支撐壓力、是否追高
風險：高檔爆量、法說 / 財報、題材退燒、大盤連動
中短線建議：買進 / 觀望 / 等回測 / 排除
建議進場區間
停損
第一目標 / 第二目標
信心分數：0-10
```

### 5.3 明確禁止

- 不可分析 `allowed_symbols` 以外的股票
- 不可把 tomorrow watchlist 當成本輪 task 候選
- 不可用固定雙五模板覆蓋 request contract

---

## 6. 第五步：資金配置確認

從 `capital-summary.md` 與 request 內 live capital context 檢查：
- 目前可操作現金
- 現有持倉曝險
- 下一步最多可操作金額（單檔 3-5 萬原則）
- 槓桿 ETF 是否接近 50% 上限

---

## 7. 第六步：明日優化建議

輸出：
- 今日盤後判斷哪裡做對 / 做錯
- 明日盤前需特別注意的風險
- 本輪 `allowed_symbols` 的排序建議

注意：
- 這裡的排序也只能在 `allowed_symbols` 內進行

---

## 8. 第七步：寫出研究結果

主檔：`D:/ai/stock/claude-research-latest.md`
可選備份：`D:/ai/stock/claude-research-YYYYMMDD-1518.md`

標頭：`# 盤後研究 YYYY-MM-DD 15:18`
內容中應明確寫出：
- 本輪 taskId
- 本輪 allowed_symbols
- 本輪 marketGrade（若 request 有）
- 若有分組，註明分組來自 request market context

最後：`來源：Claude`

---

## 9. 第八步：寫出研究結果到 File Bridge

Java `PostmarketDataPrepJob` 已建立 POSTMARKET 任務並寫出 `claude-research-request.json`。
你只需要寫一個檔案到 `claude-submit/`，Java `ClaudeSubmitWatcher` 會自動 submit。

### 9.1 讀 request 取得 routing

使用 request 內：
- `taskId`
- `taskType`
- `tradingDate` 或 `trading_date`
- `submit_filename_hint`
- `allowed_symbols`
- `output_path`

若 `taskType` 不是 `POSTMARKET` 或 `taskId` 缺失：
- 不要寫 claude-submit
- 直接在 markdown 標註「未取得有效 POSTMARKET taskId，本輪不回報」並結束

### 9.2 組成 JSON 內容

```json
{
  "taskId": 71,
  "taskType": "POSTMARKET",
  "tradingDate": "2026-04-22",
  "contentMarkdown": "完整盤後研究 md",
  "scores": {"2303": 8.5, "3231": 7.8},
  "thesis": {"2303": "盤後收盤強", "3231": "..."},
  "riskFlags": ["高檔追價風險"]
}
```

規則：
- `scores` key 必須 ∈ `allowed_symbols`
- `thesis` key 必須 ∈ `allowed_symbols`
- 沒有出現在 `allowed_symbols` 的 symbol 一律丟棄
- `contentMarkdown` 必須有實質研究內容，不可空殼

### 9.3 原子寫檔（tmp → rename）

路徑：`D:/ai/stock/claude-submit/<submit_filename_hint>`

步驟：
1. 先寫 `<submit_filename_hint>.tmp`
2. 完整寫入後 rename 成正式 `.json`

---

## 10. 禁止事項

- 不發 LINE
- 不直接給張數與最終下單
- 不可分析 `allowed_symbols` 以外股票
- 不可自行創造 taskId
- 不可把 watchlist / fresh scan 當成本輪 scoring universe
- 不可在 request 只有 5 檔時硬做雙五研究
- 不要呼叫 `localhost:8888`
- 不要跳過 `.tmp` 階段直接寫 `.json`
