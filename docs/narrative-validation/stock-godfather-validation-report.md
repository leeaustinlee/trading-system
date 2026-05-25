# Narrative Validation — 股癌近期樣本、Replay 與 Guardrail 驗證

日期：2026-05-25
範圍：2026-05-06 至 2026-05-20 股癌 EP659-EP663；用途是驗證 Narrative / KOL 弱訊號層是否能提早辨識主流題材、同時避免 FOMO。

## 1. 實作與資料狀態

- 已建立 samples：`docs/narrative-validation/stock-godfather-samples.md`。
- 已建立 API fixtures：`docs/narrative-validation/fixtures/gooaye-*-transcript.json` 與 `gooaye-*-structured-result.json`。
- 已透過 `/api/kol-signals` 灌入 5 筆 transcript，透過 `/{id}/structured-result` 灌入 structured result，並對各日期呼叫 `/aggregate` 重建 snapshot。
- 已補上 `NarrativeMirrorService`，把現有 KOL MVP pipeline 以 additive 方式 mirror 到 V44 的 `narrative_*` tables；此 mirror 僅作 observability / dashboard / shadow report。
- 發現並修正 structured-result replacement 的 JPA flush 問題：既有 signal 重新套 structured result 時，derived DELETE 可能晚於 INSERT，造成 unique key duplicate；現在 delete 後明確 `flush()`。

## 2. API 驗證結果

| date | signals | themes | dashboard top themes | shadow candidates | weakSignalOnly | guardrail text |
|---|---:|---:|---|---:|---|---|
| 2026-05-06 | 1 | 2 | 被動元件:0.1148/LOW, AI infrastructure:0.1008/LOW | 5 | False | True |
| 2026-05-09 | 1 | 2 | 被動元件:0.1092/LOW, 散熱:-0.0864/LOW | 0 | True | True |
| 2026-05-13 | 1 | 2 | 被動元件:0.1204/LOW, 光通訊:0.0812/LOW | 5 | False | True |
| 2026-05-16 | 2 | 9 | AI_PASSIVE_COMPONENT_PRICE_HIKE:0.1422/LOW, 被動元件:0.1232/LOW, TSMC_WEIGHT_CAP_FLOW:0.1008/LOW, AI power:0.098/LOW | 0 | True | True |
| 2026-05-20 | 2 | 12 | PASSIVE_COMPONENTS_PRICE_HIKE:0.2/LOW, 被動元件:0.18/LOW, AI_HIGH_END_PASSIVE_COMPONENTS:0.153/LOW, AI_OLD_COMPONENT_ROTATION:0.1246/LOW | 5 | False | True |

驗證結論：dashboard / context / shadow report 都有輸出，context 內含「never a BUY signal」guardrail；shadow item 的 narrativeContext 保持 weakSignalOnly。

## 3. Replay：題材出現後 1/3/5 交易日價格反應

說明：以各樣本日期當日或下一可得交易日收盤價為 base，計算後續 1/3/5 個交易日報酬與 5 日內最高 / 最低偏離。此表是 market reaction 檢查，不代表可成交策略績效。

| episode date | ep | symbol | name | base close | +1d | +3d | +5d | max high <=5d | min low <=5d |
|---|---|---:|---|---:|---:|---:|---:|---:|---:|
| 2026-05-06 | EP659 | 2327 | 國巨 | 338.00 | 9.9% | 15.8% | 24.3% | 27.2% | -2.1% |
| 2026-05-06 | EP659 | 2492 | 華新科 | 147.00 | 8.8% | 16.7% | 36.1% | 39.5% | -1.7% |
| 2026-05-06 | EP659 | 2472 | 立隆電 | 186.50 | 8.0% | 20.6% | 25.2% | 25.5% | -2.9% |
| 2026-05-06 | EP659 | 2308 | 台達電 | 2210.00 | 3.2% | 1.6% | -2.0% | 7.2% | -3.2% |
| 2026-05-06 | EP659 | 3017 | 奇鋐 | 2435.00 | -0.8% | 4.9% | 6.2% | 10.3% | -3.5% |
| 2026-05-06 | EP659 | 3653 | 健策 | 4305.00 | -10.0% | -6.7% | -18.0% | 0.0% | -18.9% |
| 2026-05-06 | EP659 | 6442 | 光聖 | 2060.00 | 1.7% | -5.3% | -12.4% | 3.2% | -16.3% |
| 2026-05-09 | EP660 | 2327 | 國巨 | 391.50 | 7.2% | 18.0% | 28.0% | 28.0% | -5.5% |
| 2026-05-09 | EP660 | 2492 | 華新科 | 171.50 | 9.9% | 28.3% | 26.8% | 29.7% | -11.7% |
| 2026-05-09 | EP660 | 2472 | 立隆電 | 225.00 | -0.9% | 2.9% | 4.4% | 13.8% | -9.6% |
| 2026-05-09 | EP660 | 2308 | 台達電 | 2245.00 | -2.2% | -4.0% | -10.0% | 1.1% | -11.6% |
| 2026-05-09 | EP660 | 3017 | 奇鋐 | 2555.00 | -1.4% | 0.0% | -5.7% | 8.6% | -10.0% |
| 2026-05-09 | EP660 | 3653 | 健策 | 4015.00 | -2.4% | -16.2% | -18.6% | 3.4% | -20.3% |
| 2026-05-09 | EP660 | 6442 | 光聖 | 1950.00 | -2.3% | -4.9% | -6.9% | 8.7% | -13.3% |
| 2026-05-13 | EP661 | 2327 | 國巨 | 420.00 | 10.0% | 19.3% | 23.8% | 29.8% | -3.8% |
| 2026-05-13 | EP661 | 2492 | 華新科 | 200.00 | 10.0% | 8.7% | 21.0% | 21.0% | -9.3% |
| 2026-05-13 | EP661 | 2472 | 立隆電 | 233.50 | -0.9% | 0.6% | 9.2% | 9.6% | -8.8% |
| 2026-05-13 | EP661 | 2308 | 台達電 | 2165.00 | -0.5% | -6.7% | -11.5% | 3.9% | -13.2% |
| 2026-05-13 | EP661 | 3017 | 奇鋐 | 2585.00 | -1.2% | -6.8% | -9.5% | 7.4% | -11.0% |
| 2026-05-13 | EP661 | 3653 | 健策 | 3530.00 | -4.7% | -7.4% | -14.2% | 7.6% | -20.7% |
| 2026-05-13 | EP661 | 6442 | 光聖 | 1805.00 | 2.8% | 0.6% | -1.9% | 6.9% | -6.4% |
| 2026-05-16 | EP662 | 2327 | 國巨 | 501.00 | -0.8% | 14.2% | 25.5% | 25.5% | -6.7% |
| 2026-05-16 | EP662 | 2492 | 華新科 | 217.50 | 1.1% | 22.3% | 34.5% | 34.5% | -5.3% |
| 2026-05-16 | EP662 | 2472 | 立隆電 | 235.00 | -1.3% | 13.2% | 10.6% | 16.0% | -8.5% |
| 2026-05-16 | EP662 | 2308 | 台達電 | 2020.00 | -5.2% | 0.5% | 3.7% | 5.2% | -6.9% |
| 2026-05-16 | EP662 | 3017 | 奇鋐 | 2410.00 | -0.8% | 3.5% | 5.6% | 8.3% | -4.6% |
| 2026-05-16 | EP662 | 3653 | 健策 | 3270.00 | -5.5% | 1.8% | 11.9% | 11.9% | -14.4% |
| 2026-05-16 | EP662 | 6442 | 光聖 | 1815.00 | -4.1% | -1.7% | 3.3% | 6.3% | -6.9% |
| 2026-05-20 | EP663 | 2327 | 國巨 | 520.00 | 10.0% | 21.0% | 21.0% | 21.0% | -3.3% |
| 2026-05-20 | EP663 | 2492 | 華新科 | 242.00 | 9.9% | 20.9% | 20.9% | 20.9% | -7.4% |
| 2026-05-20 | EP663 | 2472 | 立隆電 | 255.00 | 4.3% | 2.0% | 2.0% | 6.9% | -9.0% |
| 2026-05-20 | EP663 | 2308 | 台達電 | 1915.00 | 6.0% | 9.4% | 9.4% | 11.0% | -1.8% |
| 2026-05-20 | EP663 | 3017 | 奇鋐 | 2340.00 | 6.6% | 8.8% | 8.8% | 11.5% | -0.6% |
| 2026-05-20 | EP663 | 3653 | 健策 | 3030.00 | 9.9% | 20.8% | 20.8% | 20.8% | -7.6% |
| 2026-05-20 | EP663 | 6442 | 光聖 | 1770.00 | 0.8% | 5.9% | 5.9% | 9.0% | -2.3% |

主要觀察：

1. 被動元件的 timing edge 明顯。EP659（2026-05-06）後，國巨 2327 約 338 起算，5 個交易日約 +24.3%，5 日內高點約 +24.3%；華新科 2492 約 +36.1%；信昌電 6173 約 +57.4%。
2. EP661（2026-05-13）時，被動元件已是主線而非 early discovery；後續仍強，但 FOMO 風險同步升高。到 EP663（2026-05-20）時，國巨 / 華新科仍續強，但主持人已明確說不談估值、避免山頂誤讀，系統應標 CROWDED / anti-FOMO，而不是再放大倉位。
3. 散熱在 EP660 主要是 negative / crowded risk。奇鋐、健策在該窗口偏弱或波動大，Narrative layer 不應把它解讀為 bullish。
4. 光通訊多次被提到，但主持人保留台股同步性；2026-05-13 到 2026-05-20 間光聖偏弱，後續才反彈，較適合 Research Universe / WATCH，不適合直接進 tradable priority。
5. AI power / PMIC / server power 是可研究輪動線索，但目前樣本對台股個股 mapping 不夠直接，應保持低 confidence，不進 BUY path。

## 4. Counterfactual：如果系統當時有 Narrative layer

- 2026-05-06：應把被動元件加入候選宇宙或提高 research priority，尤其是 2327 / 2492 / 6173 / 2472；但仍需價格 gate、盤型 gate 與追高 gate 決定是否可交易。
- 2026-05-09：被動元件可以維持 priority，但散熱應因傳言與高位階下跌標 risk / avoid-chase。
- 2026-05-13：被動元件從 EMERGING 轉 EXPANDING；若已有持倉可作續抱 context，若空手則避免用 narrative 追高。光通訊只列 WATCH。
- 2026-05-16：被動元件供需證據更完整，但 crowding rising；AI power / PMIC 可進研究池。
- 2026-05-20：產業驗證最強，但交易上最容易 FOMO；應把 lifecycle 調高到 CROWDED，降低 entry aggressiveness，而不是給更大 positive boost。

## 5. Guardrail / Production safety

目前程式路徑仍符合安全邊界：

- Narrative / KOL 資料只透過 `/api/kol-signals/*` 與新增 `narrative_*` mirror 表輸出 dashboard、context、shadow report。
- `NarrativeMirrorService` payload 明確寫入 `productionDecisionAllowed=false`、`weakSignalOnly=true`，並列出 blockedUses：BUY_SIGNAL、FINAL_DECISION_OVERRIDE、PRICE_GATE_OVERRIDE、CHASED_HIGH_OVERRIDE、VETO_OVERRIDE、MARKET_GRADE_OVERRIDE、AUTO_TRADING。
- 未新增任何 FinalDecisionEngine、PriceGate、ChasedHighEntryEngine、Veto、MarketGrade 或資金配置注入。
- Shadow report 只計算 `kolBoostShadow` / `shadowScore`，不改 live score。

## 6. 建議下一步

1. 將 lifecycle scoring 從單日 snapshot 升級為 rolling theme memory，避免 2026-05-20 這種「證據最強但最擁擠」仍被判成 EMERGING。
2. 將 Gooaye / Podcast source 訊號切成：early discovery、confirmation、crowded warning、negative rumor 四類，避免同一個 positive direction 被誤用。
3. Candidate pipeline 可讀取 narrativeContext 作「研究優先序」與「人工提示」，但仍禁止直接加 live ENTER 分數。
4. 後續至少再補 10-20 集樣本，並加入非命中案例，才可評估 precision / recall。
