# Trading Rules (v1)

- 最終進場判斷點：09:30
- 盤中禁止臨時追高
- 最多選 2 檔
- 風報比不足排除
- `VALUE_HIGH` / `VALUE_STORY` 在非 A 盤原則不做
- 接近日高且無合理停損，不做
- 市場 C 或 decision=REST 可啟用 decision lock
- LATE + 非 A + 無持倉 => 預設 REST
