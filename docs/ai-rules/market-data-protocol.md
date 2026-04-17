# Codex / Claude 共同市場資料協議

用途：避免 Codex 與 Claude 因沙盒、網路權限、資料延遲而使用不同行情。所有固定分析與盤中判斷，都以同一份快照為共同資料源。

## 共同資料源

| 檔案 | 用途 |
|---|---|
| D:\ai\stock\market-snapshot.json | 即時報價、候選股、全市場法人、T86 個股籌碼快照 |
| D:\ai\stock\capital-summary.md | 資金、現金、持股市值、已實現與未實現損益 |
| D:\ai\stock\AI_RULES_INDEX.md | Codex / Claude 共用規則入口 |

## 產生方式

D:\ai\stock\daily-stock-notify.ps1 每次執行固定報告時，會同步更新 D:\ai\stock\market-snapshot.json。

資料內容包含：

- generated_at：快照產生時間，Asia/Taipei。
- report_type：本次報告類型。
- quotes：大盤、正二、台積電、鴻海、南亞科、華邦電、旺宏、台玻、聯電、緯創等報價。
- candidates_5：盤後 / 盤前候選 5 檔。
- candidates_930：09:30 最終候選。
- fund_summary：全市場三大法人資料。
- t86：個股三大法人買賣超，必須檢查 date。

## 使用規則

1. Codex 與 Claude 在做固定報告前，必須先讀 market-snapshot.json 與 capital-summary.md。
2. 盤中分析若 generated_at 超過 10 分鐘，必須視為過期，不得直接給進場建議。
3. 盤後分析若 t86.date 不是今日日期，必須明確標示「T86 尚非今日資料」，候選股不得因舊籌碼提高分數。
4. Claude 若沙盒不能抓最新行情，必須使用 market-snapshot.json，且不可自行猜測現價或題材強度。
5. Codex 若更新報價或候選股，必須讓快照成為 Claude 的共同參考依據。
6. 兩邊結論不同時，以資料新鮮度較高者優先；若資料時間相同，保守結論優先。

## Claude Prompt 建議

請 Claude 每次固定分析前先讀：

```text
請先讀 D:\ai\stock\AI_RULES_INDEX.md、D:\ai\stock\market-data-protocol.md、D:\ai\stock\market-snapshot.json、D:\ai\stock\capital-summary.md。
如果 market-snapshot.json 的 generated_at 超過 10 分鐘，請標示資料過期，不要給即時進場建議。
如果 T86 date 不是今日，請標示籌碼尚未更新。
最後訊息請署名：來源：Claude。
```

## 雙 AI 研究流程

- Codex 負責產生候選股、抓題材與最新消息，並把行情與籌碼寫入共同快照。
- Claude 負責讀取共同快照與 Codex 候選股，進行基本面、籌碼面、技術面與風險面研究。
- Claude 的輸出是研究意見，不是最終下單指令。
- Codex 必須綜合 Claude 研究、最新消息、資金總表與風控規則後，才輸出最終買賣決策。
- 若 Claude 因沙盒無法取得最新新聞，必須明確標示資料限制。

## Claude MIS API 報價規則

Claude 查台股即時報價時，優先順序如下：

1. 先讀 `D:\ai\stock\market-snapshot.json`。
2. 若 snapshot 不存在、`generated_at` 超過 10 分鐘、或缺少要查的標的，Claude 可用 Chrome 瀏覽器 JS `fetch` 直接查 TWSE MIS API。
3. 只有 MIS API 查不到或代號不支援時，才使用 Yahoo 股市、證交所其他 API、櫃買中心、券商頁面或新聞來源，且必須標示來源與時間。
4. 不得用新聞頁價格取代 MIS API 報價。

Chrome JS fetch 範例：

```javascript
const symbols = 'tse_t00.tw|otc_o00.tw|tse_2330.tw|tse_00631L.tw|tse_00981A.tw';
const url = `https://mis.twse.com.tw/stock/api/getStockInfo.jsp?ex_ch=${symbols}&json=1&delay=0`;
const res = await fetch(url, {
  headers: {
    'Referer': 'https://mis.twse.com.tw/stock/index.jsp',
    'User-Agent': 'Mozilla/5.0'
  }
});
const data = await res.json();
console.log(data.msgArray.map(q => ({
  code: q.c,
  name: q.n,
  last: q.z,
  bid: q.b,
  ask: q.a,
  open: q.o,
  high: q.h,
  low: q.l,
  prevClose: q.y,
  volume: q.v,
  time: q.t
})));
```

欄位處理：

- `z` 是最近成交價；若 `z` 為 `-`，不可當 0。
- 若 `z = '-'`，用最佳買賣價中間價估算：`mid = (best_bid + best_ask) / 2`。
- `b` 是買價序列，`a` 是賣價序列，底線 `_` 分隔；第一個值是最佳買價 / 最佳賣價。
- 大盤指數：`tse_t00.tw`。
- 櫃買指數：`otc_o00.tw`。
- 上市股票 / ETF：`tse_代號.tw`。
- 上櫃股票：`otc_代號.tw`。

Claude 研究輸出中需標示：

```text
報價來源：TWSE MIS API（Chrome JS fetch）
報價時間：YYYY-MM-DD HH:mm:ss
若 z 為 '-'，價格採 bid/ask 中間價估算
```

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

## 5 分鐘監控決策引擎規則

- 5 分鐘監控通知必須套用 `D:\ai\stock\five-minute-monitor-decision-engine.md`。
- 通知目標不是報價，而是行動指令：現在能不能交易、監控要 OFF/WATCH/ACTIVE、每檔股票的狀態與動作。
- 每則通知必須先輸出 market_grade、market_phase、monitor_mode、decision，再列個股。
- market_grade = C 時，decision 預設今日建議休息，monitor_mode 預設 OFF。
- market_grade = B 時，decision 預設只可觀察；除非非常明確洗盤轉強，否則不可進場。
- 11:00 後無持倉且無明確轉強，monitor_mode 優先降為 OFF。
