# 最終通知流程（Codex Only）

目的：Austin 只接收 Codex 的最終 LINE 通知。Claude 不直接通知，只負責在 Codex 最終通知前產出研究檔。

## 定案原則

每個固定時段都走三段式流程：

1. Codex 資料預抓：先抓即時報價、題材、法人/籌碼，更新 `market-snapshot.json` 與 `codex-research-latest.md`。
2. Claude 深度研究：讀 Codex 交接、共同市場快照與資金總表，輸出 `claude-research-latest.md`。
3. Codex 最終決策：讀 Claude 研究、最新消息、資金總表與共同快照後，發送唯一 LINE 通知給 Austin。

Claude 不發 LINE，不寫 `claude-outbox.json` 作為通知；所有 LINE 最終通知均由 Codex 發送，署名 `來源：Codex`。

## 每日時間軸

| 階段 | 時間 | 執行者 | 任務 / 產物 | 說明 |
|---|---:|---|---|---|
| 盤前資料預抓 | 08:10 | Codex | `CodexDataPrep-0810` | `premarket -NoSlack`，更新共同快照與候選資料 |
| 盤前研究 | 08:20 | Claude | `claude-research-latest.md` | 研究盤前候選股，不通知 |
| 盤前最終通知 | 08:30 | Codex | `AustinStockPremarket0830` | 整合 Claude 研究後發 LINE |
| 開盤資料預抓 | 09:10 | Codex | `CodexDataPrep-0910` | `strategy930 -NoSlack`，更新開盤盤型與候選資料 |
| 開盤研究 | 09:20 | Claude | `claude-research-latest.md` | 研究開盤候選與風險，不通知 |
| 今日操作通知 | 09:30 | Codex | `AustinStockStrategy0930` | 最終決定買進 / 觀望 / 等回測 |
| 盤中資料預抓 | 10:40 | Codex | `CodexDataPrep-1040` | `midday -NoSlack`，更新盤中狀態 |
| 盤中研究 | 10:50 | Claude | `claude-research-latest.md` | 更新持股與候選研究，不通知 |
| 盤中最終通知 | 11:00 | Codex | `AustinStockMidday1100` | 判斷續抱 / 減碼 / 禁止新倉 |
| 今日檢討通知 | 14:00 | Codex | `AustinStockAftermarket1400` | Codex-only，更新每日損益、進出場分數、操作總結，發 LINE |
| 盤後資料預抓 | 15:10 | Codex | `CodexDataPrep-1510` | `aftermarket_full -NoSlack`，抓收盤、法人與候選 5 檔 |
| 盤後研究 | 15:20 | Claude | `claude-research-latest.md` | 研究候選 5 檔，不通知 |
| 盤後最終通知 | 15:30 | Codex | `AustinStockAftermarket1530` | 發盤後候選、風險與資金配置 |
| 明日建議資料預抓 | 17:40 | Codex | `CodexDataPrep-1740` | `t86_confirm -NoSlack`，更新 T86 與候選籌碼 |
| 明日研究 | 17:50 | Claude | `claude-research-latest.md` | 研究 T86 後候選與明日策略，不通知 |
| 明日建議通知 | 18:00 | Codex | `AustinStockTomorrow1800` | 發明日最終觀察清單與進退場策略 |

## 目前 Windows 排程狀態

啟用中的 Codex 資料預抓：

- `CodexDataPrep-0810`
- `CodexDataPrep-0910`
- `CodexDataPrep-1040`
- `CodexDataPrep-1510`
- `CodexDataPrep-1740`

啟用中的 Codex 最終 LINE 通知：

- `AustinStockPremarket0830`
- `AustinStockStrategy0930`
- `AustinStockMidday1100`
- `AustinStockAftermarket1530`
- `AustinStockTomorrow1800`
- `AustinStockAftermarket1400`

停用中的舊通知 / ClaudeBridge：

- `ClaudeBridge-0840`
- `ClaudeBridge-0940`
- `ClaudeBridge-1110`
- `ClaudeBridge-1410`
- `ClaudeBridge-1540`
- `ClaudeBridge-1820`
- `AustinStockAftermarket1400`
- `AustinStockT86Confirm1810`

## Codex 通知前檢查

Codex 在每次最終通知前必須檢查：

1. `market-snapshot.json` 是否存在且盤中未超過 10 分鐘。
2. `capital-summary.md` 是否可讀，且資金配置未超限。
3. `codex-research-latest.md` 是否已更新本階段交接。
4. `claude-research-latest.md` 是否存在、非空，且未超過 30 分鐘。
5. 若 Claude 研究不存在、過期或空白，Codex 仍可通知，但必須明確標示「未取得有效 Claude 研究」。
6. 最終下單、張數、停損、停利，只由 Codex 決定。

## Claude 輸出規則

Claude 每次只輸出研究檔：

- 主檔：`D:\ai\stock\claude-research-latest.md`
- 可選備份：`D:\ai\stock\claude-research-YYYYMMDD-HHMM.md`

Claude 不得：

- 發 LINE。
- 寫 `claude-outbox.json` 作為通知。
- 直接給 Austin 最終買進張數。
- 在報價過期時給即時進場建議。

## 最終通知格式

```text
時間
【標題】Level 4

📊 市場狀態

📎 Claude 研究摘要

🎯 Codex 最終決策
最終結論：買進 / 觀望 / 等回測 / 禁止進場 / 續抱 / 減碼 / 出場
標的：
可用資金：
建議投入金額：
建議股數：
進場價：
停損價：
第一目標：
第二目標：
失敗條件：

來源：Codex
```




## 09:30 現價確認規則

- 09:30 最終選股前，Codex 必須用 TWSE MIS API 重新抓超強勢 5 檔與候選 5 檔即時報價。
- 不得只用 15:30 收盤價、08:30 盤前資料或 Claude 研究結論直接給進場。
- 每檔需核對即時買賣價、開盤價、昨收、日高/日低、現價相對開盤與昨收、是否接近日高。
- 方向正確條件：現價高於開盤與昨收，且仍接近日高；若跌破開盤、跌破昨收、爆量開高走低、即時報價失敗，禁止進場。
- 09:30 最終 LINE 必須列出每檔現價確認結果，再決定最多 3 檔。


## 交易決策引擎規則

- 進場、出場、續抱、換股、追強與休息判斷，必須套用 `D:\ai\stock\trade-decision-engine.md`。
- 最終交易決策只能輸出三種之一：① 可進場、② 只可觀察、③ 今日建議休息。
- 若可進場，最多 2 檔，必須提供進場策略、停損、兩段停利、倉位與一句理由。
- 必須判斷盤型、行情階段、洗盤轉強、假突破、資金換股。
- 出貨盤、強勢股轉弱、主流族群不一致、第一筆交易虧損、即時報價失敗，均不得進場。

## 行情 Gate + 自我優化規則

- 所有選股、進場、出場、追強、換股前，必須先套用 `D:\ai\stock\market-gate-self-optimization-engine.md`。
- 優先順序：行情 Gate + 自我優化引擎 > 交易決策引擎 > 個股研究。
- 行情等級只能選 A / B / C：A 主升盤可正常交易；B 強勢震盪最多 1 檔且倉位 <= 3 成；C 震盪/出貨盤禁止交易。
- 若行情等級 = C，最終決策必須是「③ 今日建議休息」，不得再輸出進場標的。
- 每次決策必須回答 YES / NO：台積電明確趨勢、主流族群一致、強勢股續強、接近日高但未創高、洗盤轉強、爆量開高走低。
- 每次決策必須判斷行情階段：開盤洗盤期、主升發動期、高檔震盪期、出貨/鈍化期。
- 每日 14:00 / 15:30 / 18:00 檢討需輸出今日評分、錯誤原因與明日優化建議。
