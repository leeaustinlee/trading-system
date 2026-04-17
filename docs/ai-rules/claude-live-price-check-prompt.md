# Claude 盤中現價複核 Prompt

請在每次研究前先執行本規則，特別是 09:20、10:50、15:20、17:50 研究。

## 必讀資料

1. `D:\ai\stock\AI_RULES_INDEX.md`
2. `D:\ai\stock\market-snapshot.json`
3. `D:\ai\stock\market-breadth-scan.json`
4. `D:\ai\stock\codex-research-latest.md`
5. `D:\ai\stock\capital-summary.md`

## 現價查詢優先順序

1. 先讀 `market-snapshot.json` 的 `candidate_live_quotes`。
2. 若 snapshot 超過 10 分鐘、缺候選股、或報價為空，請用 Chrome JS fetch 查 TWSE MIS API。
3. 上市股票使用 `tse_代號.tw`，上櫃股票使用 `otc_代號.tw`。
4. 若 `z = '-'`，用最佳買賣價中間價估算，不得當成 0。

## Chrome JS fetch 範例

```javascript
const symbols = [
  'tse_2303.tw','tse_8046.tw','tse_3714.tw','tse_6285.tw','tse_6213.tw',
  'tse_3645.tw','tse_4989.tw','tse_3035.tw','tse_8039.tw','tse_4916.tw'
].join('|');
const url = `https://mis.twse.com.tw/stock/api/getStockInfo.jsp?ex_ch=${symbols}&json=1&delay=0`;
const res = await fetch(url, {
  headers: {
    'Referer': 'https://mis.twse.com.tw/stock/index.jsp',
    'User-Agent': 'Mozilla/5.0'
  }
});
const data = await res.json();
const rows = data.msgArray.map(q => {
  const bid = (q.b || '').split('_').filter(Boolean)[0];
  const ask = (q.a || '').split('_').filter(Boolean)[0];
  const last = q.z && q.z !== '-' ? Number(q.z) : (Number(bid) + Number(ask)) / 2;
  const open = Number(q.o);
  const prev = Number(q.y);
  const high = Number(q.h);
  const low = Number(q.l);
  return {
    code: q.c,
    name: q.n,
    time: q.t,
    bid,
    ask,
    last,
    open,
    prev,
    high,
    low,
    volume: q.v,
    pctFromOpen: open ? ((last - open) / open * 100).toFixed(2) : null,
    pctFromPrev: prev ? ((last - prev) / prev * 100).toFixed(2) : null,
    nearHigh: high ? (last / high).toFixed(4) : null
  };
});
console.table(rows);
```

## 10 分鐘方向比對

請把本次現價與上一份 `market-snapshot.json` 或上一份研究中同檔價格比較：

- `續強`：現價高於開盤、現價高於昨收、接近日高，且較 10 分鐘前不弱。
- `轉弱`：現價跌破開盤或昨收，或較 10 分鐘前明顯下滑。
- `震盪`：現價高於昨收但低於開盤，或遠離日高但未破昨收。
- `資料不足`：無法取得上一筆價格或 MIS 報價異常。

## Claude 輸出格式

每檔請增加一段：

```text
現價複核：
- 報價來源：TWSE MIS API / market-snapshot.json
- 報價時間：HH:mm:ss
- 現價 / 買賣價：
- 開盤 / 昨收 / 今日高低：
- 現價 vs 開盤：
- 現價 vs 昨收：
- 10 分鐘方向比對：續強 / 轉弱 / 震盪 / 資料不足
- 結論：升評 / 維持 / 降評 / 排除
```

## 禁止事項

- 不得只用盤後收盤價判斷 09:30 是否可進。
- 不得用新聞頁價格取代 TWSE MIS API。
- 即時報價抓不到時，不得給進場建議。
- Claude 不給最終張數；張數與進場決策由 Codex 最終決定。
