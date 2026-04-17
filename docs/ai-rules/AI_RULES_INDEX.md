# Austin 台股 AI 共用規則入口

本文件是 Codex 與 Claude 的共同入口。每次分析、排程、通知、持倉更新前，必須先讀本文件，再依照下列順序讀取相關檔案。若規則衝突，以 `Austin台股操盤AI設定-Level3.md` 為最高優先，其次為本文件，再其次為 `CODEX.md` / `CLAUDE.md`。

## 必讀檔案順序

1. `D:\ai\stock\AI_RULES_INDEX.md`
2. `D:\ai\stock\Austin台股操盤AI設定-Level3.md`
3. Codex 讀：`D:\ai\stock\CODEX.md`
4. Claude 讀：`D:\ai\stock\CLAUDE.md`
5. 盤中監控狀態：`D:\ai\stock\intraday-state.json`
6. 每日損益紀錄：`D:\ai\stock\daily-pnl.csv`
7. 資金與持倉總表：`D:\ai\stock\capital-summary.md`
8. 共同市場快照：`D:\ai\stock\market-snapshot.json`
9. 共同市場資料協議：`D:\ai\stock\market-data-protocol.md`
10. 雙 AI 中短線工作流：`D:\ai\stock\dual-ai-workflow.md`
11. Claude 研究 Prompt：`D:\ai\stock\claude-research-prompt.md`
12. 最終通知流程：`D:\ai\stock\final-notification-flow.md`
13. Codex 研究交接：`D:\ai\stock\codex-research-latest.md`
14. 行情 Gate + 自我優化引擎：`D:\ai\stock\market-gate-self-optimization-engine.md`
15. 交易決策引擎：`D:\ai\stock\trade-decision-engine.md`
16. 5 分鐘監控決策引擎：`D:\ai\stock\five-minute-monitor-decision-engine.md`
17. Claude 現價複核 Prompt：`D:\ai\stock\claude-live-price-check-prompt.md`

## 腳本與資料位置

| 用途 | 檔案 |
|---|---|
| 固定時段通知主程式 | `D:\ai\stock\daily-stock-notify.ps1` |
| Codex 盤中持倉關鍵價監控 | `D:\ai\stock\monitor-tw-stock.ps1` |
| 每小時盤中 Gate | `D:\ai\stock\hourly-intraday-gate.ps1` |
| 每小時盤中 Gate 結果 | `D:\ai\stock\hourly-gate-latest.json` |
| 5 分鐘監控通知閘門結果 | `D:\ai\stock\monitor-gate-latest.json` |
| 測試或無通知目標時的 log | `D:\ai\stock\logs\` |
| 資金與持倉總表 | `D:\ai\stock\capital-summary.md` |
| 共同市場快照 | `D:\ai\stock\market-snapshot.json` |
| 共同市場資料協議 | `D:\ai\stock\market-data-protocol.md` |
| 雙 AI 中短線工作流 | `D:\ai\stock\dual-ai-workflow.md` |
| Claude 研究 Prompt | `D:\ai\stock\claude-research-prompt.md` |
| 最終通知流程 | `D:\ai\stock\final-notification-flow.md` |
| Codex 研究交接 | `D:\ai\stock\codex-research-latest.md` |
| 行情 Gate + 自我優化引擎 | `D:\ai\stock\market-gate-self-optimization-engine.md` |
| 交易決策引擎 | `D:\ai\stock\trade-decision-engine.md` |
| 5 分鐘監控決策引擎 | `D:\ai\stock\five-minute-monitor-decision-engine.md` |
| Claude 現價複核 Prompt | `D:\ai\stock\claude-live-price-check-prompt.md` |

## 每日固定流程

最終 LINE 通知只由 Codex 發送。每個時段走三段式流程：Codex 先抓資料與消息，Claude 做研究，Codex 最終決策後發 LINE。

| 階段 | 時間 | 負責 | 任務 / 產物 | 內容 |
|---|---:|---|---|---|
| 盤前資料預抓 | 08:10 | Codex | `CodexDataPrep-0810` | 更新共同快照與盤前候選，不發通知 |
| 盤前研究 | 08:20 | Claude | `claude-research-latest.md` | 研究盤前候選，不發通知 |
| 盤前通知 | 08:30 | Codex | `AustinStockPremarket0830` | 整合 Claude 研究後發 LINE |
| 開盤資料預抓 | 09:10 | Codex | `CodexDataPrep-0910` | 更新開盤盤型與候選，不發通知 |
| 開盤研究 | 09:20 | Claude | `claude-research-latest.md` | 研究開盤候選，不發通知 |
| 今日操作 | 09:30 | Codex | `AustinStockStrategy0930` | 最終決定買進 / 觀望 / 等回測 |
| 盤中資料預抓 | 10:40 | Codex | `CodexDataPrep-1040` | 更新盤中行情與持股，不發通知 |
| 盤中研究 | 10:50 | Claude | `claude-research-latest.md` | 更新研究，不發通知 |
| 盤中通知 | 11:00 | Codex | `AustinStockMidday1100` | 續抱 / 減碼 / 禁止新倉 |
| 每小時盤中 Gate | 10:05-13:05 每小時 | Codex | `AustinStockHourlyIntradayGate` | 判斷 5 分鐘監控是否 ON / OFF_SOFT / OFF_HARD，只有重要變化才發 LINE |
| 今日檢討 | 14:00 | Codex | `AustinStockAftermarket1400` | Codex-only，更新每日損益、進出場分數、操作總結，發 LINE |
| 盤後資料預抓 | 15:10 | Codex | `CodexDataPrep-1510` | 抓收盤、法人與候選 5 檔，不發通知 |
| 盤後研究 | 15:20 | Claude | `claude-research-latest.md` | 研究候選 5 檔，不發通知 |
| 盤後通知 | 15:30 | Codex | `AustinStockAftermarket1530` | 盤後候選、風險與資金配置 |
| 明日資料預抓 | 17:40 | Codex | `CodexDataPrep-1740` | 更新 T86 與候選籌碼，不發通知 |
| 明日研究 | 17:50 | Claude | `claude-research-latest.md` | 明日策略研究，不發通知 |
| 明日建議 | 18:00 | Codex | `AustinStockTomorrow1800` | 明日最終觀察清單與進退場策略 |

停用：所有 `ClaudeBridge-*` 直接通知任務、`AustinStockT86Confirm1810`。
## 雙 AI 中短線決策架構

- Codex 角色：抓市場題材、即時行情、最新消息、法人籌碼、資金總表，並做最終交易決策。
- Claude 角色：針對 Codex 候選股做基本面、籌碼面、技術面、風險面的深度研究，提出買進 / 觀望 / 等回測 / 排除建議。
- Claude 不直接給下單張數；最終買進、賣出、減碼、停損、停利與張數，只由 Codex 根據資金總表與風控輸出。
- 買股後，每天更新每檔持股的目標價、停損價、移動停利、續抱條件與出場條件。
- 空手時，Codex 持續抓題材與候選股，Claude 做研究，Codex 等 09:30 或盤後確認後再決定是否進場。
- 詳細流程以 `D:\ai\stock\dual-ai-workflow.md` 為準。
## 中短線目標三階段篩選

1. 15:30 盤後初版：依照題材延續性、成交量、波動、收盤結構、全市場法人資金流，篩出「明日超強勢 5 檔 + 中短線候選 5 檔」。候選清單不是立即進場建議。
2. 18:10 個股籌碼確認：補確認候選 5 檔 T86 外資、投信、自營商買賣超。若 T86 仍非今日資料，候選股只保留觀察，不提高進場優先權。
3. 08:30 盤前重篩：用美股、SOX、TSM ADR、台指期、日韓股市、重大新聞重篩候選 5 檔。
4. 09:30 最終決定：根據開盤盤型、台積電狀態、前 5-30 分鐘量價與均價結構，最後只保留最多 3 檔中短線觀察或進場計畫。
5. 未進入 09:30 最終清單者，不臨時追價。

## 中短線進場總規則

- 主策略改為中短線 3-10 個交易日，當沖只保留例外。
- 中短線必須通過題材、籌碼、量價、風險四項檢查，至少 4 項合格才可進場。
- 進場價距離停損不得超過 6%，否則等待回測。
- 盤型為震盪時不追價，只能等回測支撐。
- 台積電失去主導時降低出手頻率。
- 單檔資金以 3-5 萬為原則。
- 同時最多 3 檔。
- 保留 30%-40% 現金。
- 槓桿 ETF 最多佔可操作資金 50%，融資與權證預設禁止。
- 當沖只有在強勢盤、台積電站回開盤與均價、目標股突破早盤高且 Decision Score >= 4 時才允許；單筆不超過 3 萬，一天最多 1 筆。

## 最終交易決策引擎

- 所有 09:30 今日操作、盤中進場、換股、追強、出場前，必須套用 `D:\ai\stock\trade-decision-engine.md`。
- 最終決策必須整合：行情 Gate、決策鎖、時間衰減、個股估值風控、候選股即時行情。
- 若行情等級 = C、決策鎖 = 鎖定、或 10:30 後且行情不是 A，強制不交易，不得硬選股票。
- 個股必須通過風報比、是否主流、是否跌破開盤/昨收、是否假突破、是否接近日高且停損合理等檢查。
- 最終交易計畫最多 2 檔；若沒有符合條件，必須明確寫「現在不進場」。
- 最終 LINE 通知必須使用繁體中文行動語句，不得顯示 `market_grade`、`decision_lock`、`time_decay_stage`、`decision`、`risk_reward_ratio` 等程式參數名稱。
- 若 Claude 有參與研究，通知署名使用 `來源：Codex + Claude`；Codex-only 任務使用 `來源：Codex`。



## Claude MIS API 報價規則

- Claude 查台股報價時，先讀 `D:\ai\stock\market-snapshot.json`。
- 若 snapshot 不存在、超過 10 分鐘或缺少標的，Claude 可用 Chrome 瀏覽器 JS `fetch` 查 TWSE MIS API。
- MIS API URL：`https://mis.twse.com.tw/stock/api/getStockInfo.jsp?ex_ch=...&json=1&delay=0`
- 上市 / ETF 使用 `tse_代號.tw`，上櫃使用 `otc_代號.tw`；大盤 `tse_t00.tw`，櫃買 `otc_o00.tw`。
- 若 `z = '-'`，不得當 0，需用最佳買賣價中間價估算。
- 只有 MIS API 查不到時才可用其他來源，且必須標示來源與時間。
- 不得用新聞頁價格取代 MIS API 報價。
## 共同市場資料源

- 固定報告腳本每次執行時，必須同步更新 `D:\ai\stock\market-snapshot.json`。
- Codex 與 Claude 做盤前、09:30、盤中、盤後、18:00 分析前，必須先讀 `market-snapshot.json` 與 `capital-summary.md`。
- 盤中報價若超過 10 分鐘，視為過期；不得直接給進場建議，只能要求重新抓取。
- T86 個股籌碼必須檢查資料日期；若不是今日，不得當成今日買賣超。
- Claude 若沙盒無法抓最新報價或新聞，必須使用共同市場快照，不得自行猜測。
## Codex / Claude 分工

| 項目 | Codex | Claude |
|---|---|---|
| 固定時段分析 | 抓題材、行情、最新消息，讀 Claude 研究後發最終 LINE | 只寫 `claude-research-latest.md`，不發 LINE |
| 盤中持倉關鍵價監控 | 唯一負責 | 不執行 |
| 持倉更新 | 必須更新三份 md | 必須依同一規則讀取三份 md |
| LINE 署名 | `來源：Codex` | 不發 LINE，只在研究檔標示 `來源：Claude` |
| 規則依據 | 本文件 + dual-ai-workflow.md + Level3 + CODEX.md | 本文件 + dual-ai-workflow.md + claude-research-prompt.md + Level3 + CLAUDE.md |

## 盤中監控規則

- 任務：`AustinTaiwanStockMonitor`
- 每小時 Gate 任務：`AustinStockHourlyIntradayGate`，每日 10:05 開始，每 1 小時執行，約到 13:05；腳本內會自動跳過非盤中與週末。
- 每小時 Gate 腳本：`D:\ai\stock\hourly-intraday-gate.ps1`
- 每小時 Gate 結果：`D:\ai\stock\hourly-gate-latest.json`
- 腳本：`D:\ai\stock\monitor-tw-stock.ps1`
- 狀態檔：`D:\ai\stock\intraday-state.json`
- 通知閘門結果：`D:\ai\stock\monitor-gate-latest.json`
- 只有正在持有需要盤中監控的部位時才啟動。
- 無持倉、無候選股、無關鍵價或無重要事件時，不發 5 分鐘噪音通知。
- 每輪監控內部必須先產生 JSON 欄位：`market_grade`、`market_phase`、`decision`、`monitor_mode`、`should_notify`、`trigger_event`、`monitor_reason`、`next_check_focus`、`summary_for_log`；LINE 通知只輸出繁體中文行動語句，不顯示參數名稱。
- 只有 `should_notify = true` 時才發 LINE；若 `should_notify = false`，只更新狀態檔與閘門 JSON。
- `monitor_mode = OFF` 時不得發 LINE。
- `monitor_mode = WATCH` 只在市場升降級、候選接近進場、候選失效、watchlist 變動、明確洗盤轉強或假突破時發 LINE。
- `monitor_mode = ACTIVE` 只在接近進場、突破、停損、停利、持倉管理或市場結構變化時發 LINE。
- 監控只看關鍵價：停損、第一停利、第二停利、跌破 5 日線、爆量長黑、突破加碼點。
- 每小時 Gate 只負責開關判斷與摘要；真正 5 分鐘事件通知仍由 `monitor-tw-stock.ps1` 根據 `should_notify` 控制。
- 每小時 Gate 內部必須輸出：`market_grade`、`market_grade_desc`、`market_phase`、`decision`、`hourly_gate`、`should_run_5m_monitor`、`should_notify`、`trigger_event`、`hourly_reason`、`next_check_focus`、`reopen_conditions`、`summary_for_log`、`line_message`；LINE 通知只用中文欄位。
- 每小時 Gate 與 5 分鐘監控內部必須額外輸出：`decision_lock`、`cooldown_minutes`、`last_event_time`、`last_event_type`、`time_decay_stage`；LINE 通知不得直接顯示這些參數名稱。
- `hourly_gate = ON` 代表允許五分鐘監控；`OFF_SOFT` 代表暫停，若市場改善可重新開啟；`OFF_HARD` 代表今日原則停止五分鐘監控，除非市場結構明顯翻轉。
- `should_run_5m_monitor = true` 時啟用 `AustinTaiwanStockMonitor`；false 時停用。
- 每小時 Gate 只有市場等級變化、Gate 狀態變化、決策變化、重新符合監控條件或持倉監控策略改變時才發 LINE；狀態延續不發噪音通知。
- 決策優先順序固定為：`decision_lock` > 時間衰減 > `market_grade` > `trigger_event` > 個股條件。
- `decision_lock = LOCKED` 時禁止 `decision = ENTER`；無持倉時不得啟用五分鐘監控。
- `decision_lock = LOCKED` 啟動條件：`market_grade = C`、`decision = REST`、`hourly_gate = OFF_HARD`、或無持倉且無候選股。
- `decision_lock = RELEASED` 只允許在市場等級 C 升回 B/A、出現新主流、台積電轉強、候選洗盤轉強、或 `trigger_event = MARKET_UPGRADE / REOPEN_READY` 時發生。
- 時間衰減：09:00-10:00 = `EARLY`，10:00-10:30 = `MID`，10:30 後 = `LATE`。
- 10:30 後若無持倉、無明確進場條件且 `market_grade != A`，強制 `decision = REST`、`hourly_gate = OFF_HARD`、`monitor_mode = OFF`、`decision_lock = LOCKED`。
- Cooldown 預設 10 分鐘；同事件且未超過 cooldown 不重複通知，除非事件升級或 `MARKET_DOWNGRADE`。
- 盤中監控只由 Codex 發送，Claude 不執行。

## 持倉與紀錄更新規則

用戶告知進場、出場、減碼、停損、停利時，必須同步更新：

1. `D:\ai\stock\Austin台股操盤AI設定-Level3.md`
2. `D:\ai\stock\CODEX.md`
3. `D:\ai\stock\CLAUDE.md`
4. 若影響固定通知文案，也要更新 `D:\ai\stock\daily-stock-notify.ps1`
5. 若是每日損益結算，更新 `D:\ai\stock\daily-pnl.csv`
6. 若影響可操作資金、現金、持股市值或已實現損益，更新 `D:\ai\stock\capital-summary.md`

## 資金總表與每日損益

- 資金總表：`D:\ai\stock\capital-summary.md`
- 每日損益紀錄：`D:\ai\stock\daily-pnl.csv`
- 14:00 交易檢討時先更新當日交易與粗估損益。
- 15:30 盤後選股前，必須先讀資金總表，依可操作現金與持股曝險決定單檔金額。
- 收盤後若券商實際損益與估算不同，以券商實際數字覆蓋。

## 目前重要持倉摘要

- `00631L 元大台灣50正2`：成本 22.81，剩 3,320 股。2026-04-16 已減碼，券商確定版畫面總損益 +19,515 元，剩餘以移動停利管理。
- `00675L 富邦台灣加權正2`：2026-04-16 已出清 70 股，券商已實現損益 +2,736 元，已併入今日總損益。

## Claude 研究輸出方法

Claude 不直接發 LINE，不使用 `claude-outbox.json`，也不執行任何 `ClaudeBridge-*` 通知任務。

Claude 固定只輸出研究檔：

`D:\ai\stock\claude-research-latest.md`

可選備份：

`D:\ai\stock\claude-research-YYYYMMDD-HHMM.md`

Codex 會在 08:30 / 09:30 / 11:00 / 15:30 / 18:00 讀取 Claude 研究檔、共同市場快照與資金總表後，做最終 LINE 通知。


`D:\ai\stock\_archive\20260416-deprecated\bridge\`
## 禁止事項

- 不得把 15:30 盤後候選 5 檔當成進場建議。
- 不得跳過 09:30 最終確認。
- 不得在震盪盤硬交易。
- 不得讓 Claude 執行盤中監控。
- 不得在沒有持倉或沒有關鍵價觸發時啟動 5 分鐘監控。
- 不得把前一日 T86 誤判為今日個股籌碼；必須標示資料日期。











## 全市場強勢族群掃描規則

- 候選股不得由固定觀察池產生。
- Codex 必須先執行或讀取 `D:\ai\stock\market-breadth-scan.json`，資料來源為上市 `TWSE STOCK_DAY_ALL` 與上櫃 `TPEx daily_close_quotes`。
- 掃描流程：先看全市場普通股成交金額、漲幅、收在高點附近程度，再依題材/族群分類。
- 每次盤後與明日建議至少輸出 2-3 組強勢族群，每組挑 2-3 檔代表股，再由 Codex 產出最終候選 5 檔。
- 盤前 08:30 使用前一交易日全市場掃描結果加上隔夜消息重篩；08:30 先輸出超強勢開盤追強條件；09:30 用開盤量價最後收斂為最多 3 檔。
- 固定持倉與核心股只作風險參考，不得自動補進候選名單。


## 隔日可進場性濾網

- 漲停或接近漲停且收在最高點的股票，列為「超強勢 5 檔」；08:30 給 09:00 開盤追強條件，若仍鎖強且買得到可小倉追強，若打開或爆量開高走低則放棄。
- 明日中短線候選需優先挑同族群內尚未鎖死、成交金額足夠、漲幅約 2%-8.8%、收高比 >= 0.96 的股票。
- 依 Austin 目前約 15 萬可操作資金，最終候選優先挑一張成本 <= 16 萬的標的；高價股只作研究參考或零股策略，不作預設進場。
- 09:30 若候選開高超過 3%，不追；只等回測均價/前高支撐不破，或第二段量增突破。



## 10 檔雙軌候選規則

- 下午盤後輸出 10 檔：超強勢 5 檔 + 中短線候選 5 檔。
- 超強勢 5 檔用途：隔日 08:30 盤前通知開盤追強條件；09:00 若仍鎖強、買得到、題材續強，可小倉追強。
- 中短線候選 5 檔用途：隔日 09:30 依開盤量價、均價、族群延續性挑最多 3 檔。
- 超強勢股若開盤打開、爆量開高走低、跌破前日收盤或族群轉弱，不追；轉入 09:30 複核。
- 任何 08:30 追強條件都不是無條件市價追；必須符合買得到、量價延續、風險可控。



## 09:30 現價確認規則

- 09:30 最終選股前，Codex 必須用 TWSE MIS API 重新抓超強勢 5 檔與候選 5 檔即時報價。
- 不得只用 15:30 收盤價、08:30 盤前資料或 Claude 研究結論直接給進場。
- 每檔需核對即時買賣價、開盤價、昨收、日高/日低、現價相對開盤與昨收、是否接近日高。
- 方向正確條件：現價高於開盤與昨收，且仍接近日高；若跌破開盤、跌破昨收、爆量開高走低、即時報價失敗，禁止進場。
- 09:30 最終 LINE 必須列出每檔現價確認結果，再決定最多 3 檔。


## Claude 研究現價複核與 10 分鐘方向比對

- Claude 每次研究前，必須先重新查超強勢 5 檔與中短線候選 5 檔現價，不得只使用盤後收盤價或舊 snapshot。
- 優先讀取 `D:\ai\stock\market-snapshot.json` 的 `candidate_live_quotes`；若 generated_at 超過 10 分鐘、缺少候選股或報價為空，Claude 必須用 Chrome JS fetch 查 TWSE MIS API。
- Claude 研究輸出每檔必須列出：現價/買賣價、開盤價、昨收、日高/日低、現價相對開盤%、現價相對昨收%、是否接近日高。
- Claude 必須做「10 分鐘方向比對」：把現價與上一份 snapshot 或上一筆研究中同檔價格相比，標示 `續強 / 轉弱 / 震盪 / 資料不足`。
- 若 10 分鐘比對顯示跌破開盤、跌破昨收、遠離日高、爆量開高走低，Claude 應把該股降評為觀望或排除。
- 若 10 分鐘比對顯示現價高於開盤與昨收、仍接近日高、族群同步續強，Claude 可標示為可進入 Codex 09:30 最終決策。
- Claude 不直接下單；Claude 只輸出方向與風險，最終進場/張數仍由 Codex 決定。



## Claude 現價複核 Prompt 檔案

- Claude 每次研究前需讀取：D:\ai\stock\claude-live-price-check-prompt.md。


## 行情 Gate + 自我優化規則

- 所有選股、進場、出場、追強、換股前，必須先套用 `D:\ai\stock\market-gate-self-optimization-engine.md`。
- 優先順序：行情 Gate + 自我優化引擎 > 交易決策引擎 > 個股研究。
- 行情等級只能選 A / B / C：A 主升盤可正常交易；B 強勢震盪最多 1 檔且倉位 <= 3 成；C 震盪/出貨盤禁止交易。
- 若行情等級 = C，最終決策必須是「③ 今日建議休息」，不得再輸出進場標的。
- 每次決策必須回答 YES / NO：台積電明確趨勢、主流族群一致、強勢股續強、接近日高但未創高、洗盤轉強、爆量開高走低。
- 每次決策必須判斷行情階段：開盤洗盤期、主升發動期、高檔震盪期、出貨/鈍化期。
- 每日 14:00 / 15:30 / 18:00 檢討需輸出今日評分、錯誤原因與明日優化建議。

## 5 分鐘監控決策引擎規則

- 5 分鐘監控通知必須套用 `D:\ai\stock\five-minute-monitor-decision-engine.md`。
- 通知目標不是報價，而是先判斷這一輪是否值得發 LINE：監控要 OFF/WATCH/ACTIVE、是否 `should_notify`、觸發事件是什麼。
- 5 分鐘監控內部必須先判斷市場等級、行情階段、監控模式與決策，再列個股；LINE 通知不得顯示英文參數名稱。
- market_grade = C 時，decision 預設今日建議休息，monitor_mode 預設 OFF。
- market_grade = B 時，decision 預設只可觀察；除非非常明確洗盤轉強，否則不可進場。
- 11:00 後無持倉且無明確轉強，monitor_mode 優先降為 OFF。
- 若沒有新事件，`should_notify = false`、`trigger_event = NONE`，程式不得發 LINE。
- 5 分鐘監控也必須套用 Decision Lock、時間衰減與 Cooldown；當 `decision_lock = LOCKED` 且無持倉時，`monitor_mode` 必須降為 OFF。


