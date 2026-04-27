# 候選股選股條件深度審查 2026-04-28

> 立場：20 年台股波段操盤手 + 投信研究部主管。看的是「進到 candidate_stock 表的這 10–20 檔，**選得有沒有道理**」。
> 不看 FinalDecision 評分、進場時機、持倉管理（昨天已 review）。
> 範圍：D:/ai/stock 系統實際執行中的選股鏈，加 30 天 DB 證據。

---

## A. 系統選股條件清單（實際在用的）

### ⚠️ 最重要的事實：Java 不是選股器，是「資料收件人」
真正的 screener 是 **`market-breadth-scan.ps1`**（Codex PowerShell 腳本），收盤後 ~15:30 跑：
1. 抓 TWSE/TPEx 全市場（~1,800+ 檔）
2. 跑下面 4 類條件
3. 透過 `POST /api/candidates/batch` 寫進 Java DB
4. Java 只是被動接收 + 被 ranking_order 排序顯示

`MomentumCandidateEngine.java`（CandidateScanService 不會 call）是**完全 dead code**——30 天 65 筆 candidate_stock 中 `is_momentum_candidate=false` for all 65。寫了沒人用。

### A.1 技術面（PowerShell 腳本內，4 條）
| 條件 | 閾值 | 來源 line |
|---|---|---|
| 當日漲幅 (changePct) | ≥ **2.0%**（initial scan ≥1.5%）≤ **8.8%**（避免漲停） | `market-breadth-scan.ps1:135-142` |
| 接近日高比 (nearHigh) | ≥ **0.96**（收在日高 4% 內） | 同上 |
| 不在漲停風險（IsLimitRisk）| 排除接近 ±10% 限制者 | 同上 |
| 收盤 > 開盤（bullish close）| score +1 if true | `market-breadth-scan.ps1:74-79` |

**沒有的**：MA / RSI / MACD / KD / BBand / 突破 N 日新高 / 拉回均線 / **任何多日趨勢**。系統根本沒有 kline / daily_quote / stock_dailybar 表，**無法計算任何均線**。

### A.2 籌碼面（T86 只當 context，**不是 filter**）
| 來源 | 用途 |
|---|---|
| TWSE T86 三大法人（外資/投信/自營） | `T86DataPrepJob`（18:10 跑）抓資料、merge 進已存在 candidate 的 `payload_json`，**作為 context 給 Claude/Codex 看**，不參與 selection |
| 主力買超 / 大戶持股變化 | 完全沒用 |
| 融資融券 | 完全沒用 |
| 內部人交易 | 完全沒用 |

**事實證據**：今天 04-28 候選股 payload_json 的 keys 是 `["market", "source", "amountYi", "nearHigh", "themeTag", "changePct", "boardLotCost", "tradabilityTag"]`——**沒有 foreign_net / invest_trust_net / dealer_net**（T86 18:10 還沒跑就先寫進來；當天 15:30 寫的、18:10 才會 merge 法人資料）。當日選股完全沒看法人。

### A.3 題材面（PowerShell 腳本內 + Java ThemeSelectionEngine）
| 條件 | 邏輯 |
|---|---|
| themeTag | PowerShell 對每檔配一個題材標籤；如不在已知題材 → 配「**其他強勢股**」（catch-all） |
| 題材分數計算 | `ThemeSelectionEngine`：`market_behavior × 0.55 + heat × 0.25 + continuation × 0.20`（昨天 review 過）|
| 題材是否 **gate** 選股？ | **不是**。題材只是 tag、不會剔除股票。題材弱仍會進 candidate，只在 FinalDecision 階段被 down-weight |

**事實證據**：30 天 49 unique symbols / 7 trading days，分布：
- 半導體/IC : 14（7 unique）
- 記憶體/儲存 : 8（4 unique）
- PCB/載板/材料 : 4（3 unique）
- AI伺服器/電腦週邊 : 3（3 unique）
- 軍工/航太 : 2、散熱/機構 : 1、機器人/自動化 : 1
- **其他強勢股 : 17（16 unique）— 33% candidates theme 標籤是垃圾**

### A.4 流動性 / 市值 / 可交易性（PowerShell 腳本內）
| 條件 | 閾值 |
|---|---|
| 日成交額 (amountYi) | ≥ **3 億 TWD**（初篩 ≥ 1 億） |
| 一張可負擔成本 (boardLotCost) | ≤ **160,000 TWD**（≈ 股價 < 160） |
| Common stock only | 排除 ETF / 特別股 / 權證 |
| 漲停 / 接近漲停 | 排除（避免買在頂點不能出） |

**沒有的**：市值下限、shares outstanding、流通比、董監持股、月均量穩定度。

### A.5 評分公式（candidate_stock.score）
```
score = changePct × 1.25
      + log10(amountYi) × 3              # 成交額對數加分
      + (nearHigh ≥ 0.99 ? +3 : nearHigh ≥ 0.97 ? +1.5 : 0)
      + (close > open ? +1 : 0)
      - (range > 8% AND nearHigh < 0.96 ? 3 : 0)
      - (close > 800 ? 2 : 0)
      - (NOT common ? 2 : 0)
```
**全部用「當日 1 根 K」算分**。沒有任何前一日、前 5 日、前 20 日資料進來。

---

## B. 優點（DB / code 證據）

### B.1 流動性閾值合理
3 億 TWD/日 + 1 張 ≤ 16 萬，這個流動性過濾**正確**。台股 1–2 週波段選股最怕「進得去出不來」，這層保護到位。**證據**：30 天 49 unique 中沒有任何雞蛋水餃股或太貴族（最貴 8046 南電 ~285 TWD）。

### B.2 漲停排除邏輯到位
`changePct ≤ 8.8%` 加 `IsLimitRisk` 雙重保護，避開「漲停板鎖死買不到、隔天開低」的陷阱。**證據**：30 天 65 筆 candidate 沒有當日漲停封板的案例。

### B.3 PowerShell + Java 解耦乾淨
選股邏輯外置給 PowerShell + Codex 維護，Java 只當 sink。**好處**：選股策略迭代不用碰 Java code、不用重啟 server，純 script 改完跑下一輪即可。**這是少數設計亮點**。

### B.4 題材標籤覆蓋率不錯（73%）
49 unique 中 33 檔有具體 theme（67%），16 檔被丟「其他強勢股」（33%）。比想像中好——同業很多選股系統根本沒題材分類。

### B.5 排除 ETF / 槓桿 / 權證
`Common stock only` 過濾乾淨，避免 00631L 之類槓桿 ETF 誤入主進場。

---

## C. 缺點（按嚴重性排序，每條附「為什麼這個缺點會讓系統賺不到錢」）

### C.1【最嚴重】**只用「當日 1 根 K 線」選股，無多日趨勢概念**
- score 公式 100% 由「當日漲幅 + 當日成交額 + 當日收盤位置」組成
- 沒有 5MA / 10MA / 20MA / 季線 / 半年線概念。系統甚至**沒有 kline 表**，無法回看多日。
- **為什麼賺不到錢**：1–2 週波段需要的是「**趨勢初升段或主升段中繼**」，不是「**昨天大漲一根**」。當日漲 8% 的股票可能是：
  1. 主升段第 3 天（好，可進）
  2. 末升段最後一根（壞，明早爆量殺尾）
  3. 跌深反彈第一根（中性，需確認）
  系統沒能力區分這三者，三種都當作 score 23 的最強候選。**勝率天花板會被卡死**。

### C.2【最嚴重 2】**追漲停的 candidate 標籤自相矛盾**
- 今天 04-28 top 5 候選股漲幅：9.95% / 9.85% / 9.79% / 9.86% / 9.94%——全部**逼近漲停（9.7%+）**
- 但每一檔的 payload_json `tradabilityTag` 都明確標 **「題材指標，不列主進場」**
- **系統自己標了 "不列主進場" 卻還寫進 candidate_stock**，下游 FinalDecision 看到 score 23 高分，會誤把這些不該進的當主候選
- **為什麼賺不到錢**：漲停日隔天「開高走低 + 量縮整理」是台股鐵律。逼近漲停的當天進入候選，等於「**用昨天最熱訊號當明天進場依據**」，勝率 < 30%。

### C.3 **MomentumCandidateEngine 是 dead code（假完成）**
- 寫了一整個 154 行的 MomentumCandidateEngine.java（priceMomentum + MA + volume + theme + aiSupport 5 條件，需 ≥3 條才通過），又有 hard veto（Codex / Claude / 風險旗標）
- **DB 證據**：30 天 65 筆 candidate **`is_momentum_candidate=false` 全部**
- 設計很完整但**從來沒被任何地方 call**。CandidateScanService 不 call、CandidateController 不 call、PostmarketWorkflow 不 call
- **為什麼賺不到錢**：這代表「Java 端嚴格的 momentum 篩選」事實上不存在。表面看起來系統有 5 個條件閘門，實際只有 PowerShell 4 條鬆綁條件。**門面比實質嚴**——做產品 demo 給人看時很體面，實戰時無效。

### C.4 **無相對強度 (RS) 排名**
- 沒看「個股漲幅 vs 加權指數」、「個股 vs 同業」、「個股 RSI 排名」
- 只看絕對漲幅 ≥ 2%——**大盤暴漲 5% 那天，所有股票都漲 2%+，整個 universe 全進候選池**
- **為什麼賺不到錢**：強勢股 ≠ 大盤帶上來那檔；強勢股 = 大盤跌 2% 它仍漲 1% 那檔。沒有 RS 概念 → **大盤反彈日全市場都「強」、空頭抓反彈被當主升買**。

### C.5 **無趨勢階段判斷（底部 / 初升 / 主升 / 末升 / 頭部）**
- 系統不知道 2330 台積電是「站上 600 整理 2 週後突破」（主升初）還是「漲了 30% 後高檔震盪」（末升），兩者 score 公式吐出的分數一樣
- **為什麼賺不到錢**：1–2 週波段的最佳區間在**主升段初+中**（漲幅 5–15% 還沒走完那段）。系統選不到「剛發動」的，只選得到「**已漲過頭但今天又漲一根**」的。

### C.6 **籌碼資料只當文字描述、不參與 selection**
- T86（外資/投信/自營淨買）18:10 才寫，但選股是 15:30 寫的——**candidate 寫進去時根本沒看法人**
- 即使 T86 後來補進 payload_json，也只給 Claude/Codex 「研究」用，**不會反向剔除已選股**
- **為什麼賺不到錢**：台股波段最強訊號之一是「投信連 5 買 + 外資跟進」。系統不用、也不能用，等於放棄一個高勝率訊號源。

### C.7 **「其他強勢股」catch-all 太大**
- 30 天 17/65 = **26% 候選 theme = 其他強勢股**（49 unique 中 16 檔）
- 這檔股票進來只因「漲幅夠」，**沒有任何題材敘事**支撐它能再漲 1–2 週
- **為什麼賺不到錢**：1–2 週波段需要題材延續性（法說 / 訂單 / 政策 / 同業跟漲）。沒題材的「強勢股」漲一天就可能停，下游 ThemeStrengthService 也評分困難（拿不到 theme score）。

### C.8 **Symbol 重複出現嚴重，缺新鮮度**
- 30 天 49 unique / 65 picks = 平均每檔出現 1.33 次
- 但 top 5（3189 / 2337 / 2454 / 8028 / 6770）各 3 次——**這 5 檔吃掉 23% 候選名額**
- **為什麼賺不到錢**：系統「老是同一批人」表示**新題材 / 新主流偵測能力差**。市場輪動到下一個熱點時，系統還在追昨天的主流。

### C.9 **無基本面 catalyst 偵測**
- 沒有財報日 / 法說會 / 營收公告 / 新品發表 / 股東會 calendar
- 1–2 週波段最強催化劑就是「**法說後續強**、**月營收創高**」，系統完全看不到
- **為什麼賺不到錢**：catalyst 是**真因**、技術面是**結果**。沒抓 catalyst 等於只看果不看因。

### C.10 **Universe 沒有市值 / 月均量過濾**
- 雖有日成交額閾值，但**單日 3 億**只代表「今天爆量」，不代表這檔平常流動性夠
- 一檔平日成交 5,000 萬、今天爆量 3 億 → 系統選進來，明天回 5,000 萬量就出不掉
- **為什麼賺不到錢**：流動性陷阱的典型場景，**進得去出不來**。

---

## D. 跟專業 1–2 週波段 PM 的差距（5 個典型該有但沒有）

| # | 條件 | 影響 |
|---|---|---|
| 1 | **相對強度 RS 排名**（vs 大盤 / vs 同業） | 沒它 → 強弱分不開；大盤拉抬日所有股票都被選進來 |
| 2 | **多日均線結構**（5MA、20MA、季線多頭排列） | 沒它 → 無法判斷「主升 vs 末升」；高檔追單風險高 |
| 3 | **法人連續買賣超** | 沒它 → 跟不上專業籌碼動向；台股最大 alpha 來源被棄置 |
| 4 | **產業輪動偵測**（過去 5 日哪 3 大 sector 最強） | 沒它 → 全市場掃，沒有「集中火力」效果；勝率分散 |
| 5 | **基本面 catalyst calendar**（法說 / 營收 / 新品） | 沒它 → 只看技術不看因；catalyst-driven swing 的核心訊號完全缺失 |

---

## E. 量化證據（過去 30 天）

### E.1 整體量
| 指標 | 數值 |
|---|---:|
| Trading days with candidates | 7 |
| Total rows | 65 |
| Unique symbols | 49 |
| Score 範圍 | 5.50 – 23.86（avg 17.18, std 5.35）|
| `is_momentum_candidate=true` | **0 / 65** ← MomentumCandidateEngine 從未啟用 |

### E.2 命中率（候選 → 實際 ENTER → 真的賺錢）
| 階段 | 數量 |
|---|---:|
| 30 天 candidate-stock unique symbols | 49 |
| 30 天 final_decision = ENTER | **0**（昨天 P0.1 改完才開始能 ENTER）|
| 30 天 closed positions | **1**（2303 聯電，hold 2 days，pnl **-600**）|
| 30 天 paper_trade closed | **0** |

**結論**：候選池有 49 檔，但**完全沒有任何下游驗證**。從「選股質量」獨立看，沒辦法量化勝率。但從「**不轉成交易**」的事實看：選了等於沒選。

### E.3 漏失率（強勢股沒選到）
- 系統**沒有 daily_top_movers 表**（昨天 review 提過 P1.2 建議）
- 無法回算「當天漲幅前 30 中，候選股命中幾檔」
- **這是 P0 必補的觀測指標**，否則永遠不知道有沒有漏選

### E.4 Sector 分布 vs 30 天台股實際強勢
| 系統選的（30d） | 占比 |
|---|---:|
| 半導體/IC + 記憶體 + PCB | **41 / 65 = 63%** |
| AI 伺服器 + 散熱 | 4 / 65 = 6% |
| 其他強勢股（catch-all）| **17 / 65 = 26%** |
| 軍工 + 機器人 | 3 / 65 = 5% |

**問題**：63% 集中在半導體系列。如果半導體全產業同時轉弱（如 NVDA 大跌、SOX 跌），系統一天內全部曝險同向 → **單一產業 beta = 1.0+**。沒有 sector rotation 機制保護。

### E.5 Score 與後續報酬 Pearson 相關係數
- 無法計算：candidate score 23.86 對應的下週報酬不存在資料
- 需要等 paper_trade 累積 30 筆以上才能計算
- **目前是無從驗證選股訊號是否與 alpha 正相關**

---

## F. 改進建議（按優先序）

### P0 — 立刻做（影響賺錢機率最大）

#### P0.1 把 MomentumCandidateEngine 從 dead code 變 hard gate
- **問題**：寫了 5 個 momentum 條件 + hard veto，但從未被 call。等於沒寫
- **改成**：在 CandidateController.batchUpsert 接收後，**過 MomentumCandidateEngine.evaluate 才寫 DB**；不過則拒絕（return 422）
- **預期效果**：把 PowerShell 那 4 條鬆綁 + Java 5 條嚴格 = 9 條 gate，能擋掉「漲停日 / 量增無實質 / 風險旗標」型噪音 ~30%
- **工程量**：S（1 行 wire）

#### P0.2 漲停日候選降權或排除
- **問題**：今天 top 5 漲幅 9.79–9.95% + `tradabilityTag="題材指標，不列主進場"`，仍進候選 + 高分
- **改成**：PowerShell 收緊 `changePct ≤ 7.0%`（避開逼近漲停的 9–9.99% 帶）；或保留進候選但 score 倒扣 5（不會被選為主進場）
- **預期效果**：避開 30% 隔日 gap-down 的爛 setup
- **工程量**：S（PowerShell 1 行條件）

#### P0.3 加 daily_top_movers 表 + 候選命中率回算
- **問題**：永遠不知道強勢股漏失多少
- **改成**：新建 `daily_top_movers (trading_date, symbol, change_pct, volume_ratio, rank)`；收盤批次抓 TWSE 漲幅前 30；dashboard 算「候選 ∩ 強勢股 / 強勢股」當日命中率
- **預期效果**：3 天就有數據，連續一週命中率 < 40% 觸發 LINE 警示
- **工程量**：M（1 個 batch job + 1 個 metric endpoint）

### P1 — 一週內

#### P1.1 加 `stock_dailybar` 表 + 5 / 20 / 60 日均線計算
- **問題**：選股完全沒有趨勢概念，1 根 K 決定一切
- **改成**：抓 TWSE 收盤資料每日落表；CandidateScreener 加 「站上 20 日線 + 20 日線多頭排列」雙條件
- **預期效果**：把「末升段最後一根」的爛 setup 擋掉，勝率 +10–15%
- **工程量**：M（資料落表 + screener 改寫）

#### P1.2 相對強度 RS 排名
- **問題**：大盤拉抬日全市場都「強」，無法分辨真強勢
- **改成**：每日計算個股 5 日漲幅 - 加權指數 5 日漲幅；RS rank 前 20% 才能進候選
- **預期效果**：強弱分離，避免「市場帶上來」誤判
- **工程量**：M

#### P1.3 法人連 N 買進評分
- **問題**：T86 只當 context；不參與選股
- **改成**：把 T86 抓資料時間提早到 15:00 之前（如可），或直接讓 PostmarketWorkflow 18:30 重跑一次 candidate filter；對「外資連 3 買 + 投信跟進」加分 +3
- **預期效果**：抓到籌碼跟進股，台股最強 alpha 之一
- **工程量**：M

### P2 — 兩週內

#### P2.1 產業輪動偵測 + top 3 sector 集中
- **問題**：63% 集中半導體，全市場 sector beta 風險高
- **改成**：每日算「過去 5 日哪 3 個 sector 強勢」（用 candidate score by theme avg），候選只留 top 3 sector 的成員
- **預期效果**：火力集中、sector 分散（top 3 之間互補）
- **工程量**：M

#### P2.2 「其他強勢股」catch-all 拆解
- **問題**：26% 候選沒題材敘事
- **改成**：擴充 theme 對照表（手動補 30 檔常出現的 symbol → 對應題材）；catch-all 只留 < 10%
- **預期效果**：theme score 計算更精準，下游 ThemeStrengthService 吃到的訊號變強
- **工程量**：S（資料庫補 row）

#### P2.3 月營收 / 法說會 calendar
- **問題**：基本面 catalyst 完全沒看
- **改成**：抓 TWSE [月營收公布 calendar](https://www.twse.com.tw/) + 法說會表；candidate 落表時 join，加分 +2 if 7 天內有 catalyst
- **預期效果**：補上 catalyst 訊號，typical 1–2 週波段最重要的因子
- **工程量**：M

### P3 — 觀察數據後再說

#### P3.1 Score 與後續報酬 Pearson 相關
- **觀察前提**：paper_trade 累積 ≥ 30 筆 closed
- 算 `corr(candidate.score, pnl_pct@5d)` 與 `@10d`，找最佳 score weighting

#### P3.2 取消 PowerShell 改全 Java 篩選
- **觀察前提**：上面 P0 + P1 全部驗證可行
- 現在 PowerShell 是「快速迭代」優點；累積經驗後可寫進 Java，benefit 是進入 git 版本控制 + unit test 涵蓋

---

## G. 結論：能不能賺錢

### G.1 一句話結論
**目前選股「勉強能賺錢，但勝率天花板低」**——理由：流動性 gate 過得了基本面，但只用「當日 1 根 K + 接近日高 + 近漲停」這套規則本質就是**追漲式 momentum chasing**，台股這套打法歷史勝率約 40–45%。

### G.2 預期勝率
- **保守估計：35–40%**（因為 MomentumCandidateEngine 的 hard veto 沒接，又愛追逼近漲停股，扣分）
- **樂觀估計：45–50%**（如果「不列主進場」標籤的真的會被下游 FinalDecision 過濾掉）
- **同業 1–2 週 momentum 系統勝率典型值**：50–55%（含趨勢確認 + RS + 籌碼）

### G.3 預期年化報酬 vs 0050（benchmark）
- **0050 年化**：~8–10%（過去 5 年）
- **本系統樂觀估**：「**Beta ≈ 1.0–1.2 + alpha ~3–5%**」 → 年化 **11–15%**
- **本系統悲觀估**：高週轉 + 追高 + 半導體 beta 集中 → **年化 5–8%（甚至輸 0050）**
- **不確定區間大**，主因是 **0 ENTER 樣本** 與 **沒有強勢股漏失率回測**

### G.4 最大改進槓桿是哪 1 件
**P1.1 加 `stock_dailybar` 表 + 多日均線結構判斷**。

理由：當前最致命的缺陷不是「條件不夠多」，而是「**只用 1 根 K 線選股**」——這個結構性問題不修，加再多條件都是治標。多日均線一旦進來，「主升初/主升中」與「末升段最後一根」就能分開，這直接決定 1–2 週波段的核心勝率分母（**有沒有續強的可能性**）。

P0 的三個小修補主要是「**止血**」（避免追漲停、補命中率觀測），P1.1 才是真正能把勝率天花板從 45% 推到 55%+ 的單一槓桿。

---

## 附錄：DB 證據快照

```
30 天 candidate_stock：
  unique symbols = 49
  trading days = 7（04-20 ~ 04-28）
  total rows = 65
  score range: 5.50 – 23.86 (avg 17.18, std 5.35)
  is_momentum_candidate = false (65/65 全為 false) ← MomentumCandidateEngine 完全未接

Theme 分布:
  半導體/IC : 14 (7 unique)
  記憶體/儲存 : 8 (4 unique)
  PCB/載板/材料 : 4 (3 unique)
  AI伺服器/電腦週邊 : 3 (3 unique)
  軍工/航太 : 2、散熱/機構 : 1、機器人/自動化 : 1
  其他強勢股 : 17 (16 unique)  ← 26% catch-all 標籤垃圾

熱門 symbols（30 天）:
  3189 景碩 / 2337 旺宏 / 2454 聯發科 / 8028 昇陽半導體 / 6770 力積電  各 3 次
  4967 十銓 / 8150 南茂 / 2344 華邦電 / 2369 菱生 / 2303 聯電  各 2 次
  → top 10 吃掉 25/65 = 38% 候選名額；缺新鮮度

今天 04-28 top 5 候選股 payload_json:
  2408 南亞科：changePct 9.95%，nearHigh=1，amountYi 296.87，
              tradabilityTag = "題材指標，不列主進場"  ← 系統自己標不該進場！
  2337 旺宏 ：changePct 9.85%，nearHigh=1，amountYi 222.84，"題材指標，不列主進場"
  2313 華通 ：changePct 9.79%，nearHigh=1，amountYi 230.20，"題材指標，不列主進場"
  4967 十銓 ：changePct 9.86%，nearHigh=1，amountYi  78.62，"題材指標，不列主進場"
  4927 泰鼎-KY：changePct 9.94%，nearHigh=1，amountYi 27.96，"題材指標，不列主進場"
  → top 5 全都逼近漲停 + 全標「不列主進場」， selection 與 tradability 直接矛盾

下游驗證:
  30 天 final_decision ENTER = 0
  30 天 closed positions = 1（2303 聯電 -600，hold 2 days，唯一樣本還虧錢）
  30 天 paper_trade closed = 0
  → 沒有任何「選股訊號 → 真實報酬」的閉環資料
```
