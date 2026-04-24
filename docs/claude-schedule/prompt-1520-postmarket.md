# Claude 排程研究 — 15:20 盤後候選 5 檔研究

**執行時間**：每週一至週五 15:20（Asia/Taipei）
**對應 Codex 通知**：15:30 `AustinStockAftermarket1530`

---

## 環境與路徑解析（v2.1 新增）

本 prompt 會同時被兩種環境執行，請先判斷當下環境並自動翻譯路徑：

| 執行環境 | stock 根目錄路徑 |
|---|---|
| 本機 Windows PowerShell / WSL Claude CLI | `D:/ai/stock/` 或 `/mnt/d/ai/stock/` |
| Cowork cloud sandbox Claude | `/sessions/happy-gracious-pasteur/mnt/stock/` |

底下章節出現的 `D:/ai/stock/...` 一律請翻譯成你當下環境能讀寫的對應路徑，兩邊指向同一份檔案。若其中一條路徑 Read 失敗，改用另一條。

---

## 第一步：必讀

1. `D:/ai/stock/AI_RULES_INDEX.md`
2. `D:/ai/stock/dual-ai-workflow.md`
3. `D:/ai/stock/market-snapshot.json` — 收盤版（`generated_at` 可接受 30 分鐘內）
4. `D:/ai/stock/codex-research-latest.md` — Codex 15:10 DataPrep 交接（含收盤法人 + 候選 10 檔）
5. `D:/ai/stock/capital-summary.md`
6. `D:/ai/stock/claude-research-request.json`（Java PostmarketDataPrepJob 寫出，含漲跌家數）
7. `D:/ai/stock/market-gate-self-optimization-engine.md`
8. `D:/ai/stock/trade-decision-engine.md`

---

## 第二步：今日盤後市場總結

- 大盤今日表現（漲跌幅、漲跌家數、成交量）
- 主流族群：今日漲幅最強的 2-3 組族群
- 台積電收盤狀態
- 行情等級評估（今日 A/B/C）
- 今日行情自我評分（0-10）：是否有錯誤判斷

---

## 第三步：超強勢 5 檔研究

針對 Codex 選出的超強勢 5 檔（漲停或接近漲停收在最高點）：

```text
股票：代號 / 名稱
今日漲幅 / 收盤 / 成交量
是否漲停 / 收在最高點附近（收高比 >= 0.96）
題材：是否主流題材 + 延續性
籌碼：今日外資 / 投信 / 自營商方向（若有）
隔日風險：若明日開盤打開 / 爆量開高走低的應對
08:30 追強條件：（需同時符合）量價延續 + 仍鎖強 + 族群同步
建議：列入超強勢追強池 / 謹慎（溢價太高）/ 排除
```

---

## 第四步：中短線候選 5 檔深度研究

針對 Codex 選出的中短線候選 5 檔（漲幅 2%-8.8%，收高比 >= 0.96，非鎖死）：

```text
股票：代號 / 名稱
今日收盤 / 漲幅 / 成交量
題材定位：主題、族群、是否主流、延續性
基本面：近期營收、法說時程、重大事件
籌碼面：外資/投信/自營商、主力籌碼、融資券（資料不足需標示）
技術面：均線結構、量價、支撐壓力、是否追高
風險：高檔爆量、法說/財報、題材退燒、大盤連動
中短線建議：買進 / 觀望 / 等回測 / 排除
建議進場區間：
停損：
第一目標 / 第二目標：
信心分數：0-10
```

---

## 第五步：資金配置確認

從 `capital-summary.md`：
- 目前可操作現金
- 現有持倉曝險
- 下一步最多可操作金額（單檔 3-5 萬原則）
- 槓桿 ETF 目前佔比是否接近 50% 上限

---

## 第六步：明日優化建議

- 今日判斷哪裡做對 / 做錯
- 明日盤前需特別注意的風險
- 候選股排序建議（信心分數高低 + 風控）

---

## 第七步：寫出研究結果

**主檔**：`D:/ai/stock/claude-research-latest.md`
**可選備份**：`D:/ai/stock/claude-research-YYYYMMDD-1520.md`

標頭：`# 盤後研究 YYYY-MM-DD 15:20`
最後：`來源：Claude`

---

## 第八步：寫出研究結果到 File Bridge（v2.1 協定）

Java `PostmarketDataPrepJob`（15:05）已建立 POSTMARKET 任務並寫出 `claude-research-request.json`。你只需要寫一個檔案到 `claude-submit/`，Java `ClaudeSubmitWatcher` 會在 30 秒內自動 submit 並把 task 狀態推進到 `CLAUDE_DONE` — **不需要、也不可以呼叫 `localhost:8080`**（Cowork 雲端環境打不到；本機走 file bridge 更穩）。

### 8.1 讀 request 取得 taskId 與建議檔名

讀 `D:/ai/stock/claude-research-request.json`（路徑請依第 0 章翻譯），取出以下欄位：

| 欄位 | 意義 |
|---|---|
| `taskId` | 本輪 ai_task 的 ID（例如 `71`）。**不得自行創造** |
| `taskType` | 必須 = `"POSTMARKET"`（若不是代表當下不是 POSTMARKET 時段） |
| `tradingDate` | 例如 `"2026-04-22"` |
| `submit_filename_hint` | Java 建議檔名（例如 `claude-POSTMARKET-2026-04-22-1645-task-71.json`），**直接沿用** |
| `allowed_symbols` | `scores` / `thesis` 的 key 必須是這個清單的子集，其他 symbol 一律丟棄 |
| `output_path` | `claude-research-latest.md` 寫出位置 |

若 `taskType` 不是 `POSTMARKET` 或 `taskId` 缺失，不要寫 claude-submit，直接在 `claude-research-latest.md` 標註「未取得有效 POSTMARKET taskId，本輪不回報」並結束。

### 8.2 組成 JSON 內容

欄位結構（跟現有 processed 範本一致）：

```json
{
  "taskId": 71,
  "taskType": "POSTMARKET",
  "tradingDate": "2026-04-22",
  "contentMarkdown": "完整盤後研究 md（與 claude-research-latest.md 同內容）",
  "scores": {"2303": 8.5, "3231": 7.8, "4938": 7.2, "2330": 7.5, "2317": 7.0},
  "thesis": {"2303": "盤後收盤強", "3231": "...", "2330": "大盤領頭"},
  "riskFlags": ["高檔追價風險", "若明日台指期貼水擴大降評"]
}
```

規則：

- `scores` 是 `{symbol: 0~10 浮點數}`，key 必須 ∈ `allowed_symbols`
- `thesis` 是 `{symbol: string}`，key 應與 `scores` 對齊
- `riskFlags` 是字串陣列；沒有風險就給空陣列 `[]`
- `contentMarkdown` 必須有實質研究內容（不少於 500 字），系統會拒收空殼

### 8.3 原子寫檔（tmp → rename）

**這一步絕對不可省略 `.tmp` 階段，否則 watcher 可能讀到半成品被丟進 `failed/`。**

路徑：`D:/ai/stock/claude-submit/<submit_filename_hint>`（依第 0 章翻譯）

步驟：

1. 先寫 `.../<submit_filename_hint>.tmp`（例如 `claude-POSTMARKET-2026-04-22-1645-task-71.json.tmp`）
2. 完整寫入後，rename 成 `<submit_filename_hint>`（去掉 `.tmp` 結尾）

Claude Code 本地環境用 Bash：
```bash
mv /mnt/d/ai/stock/claude-submit/claude-POSTMARKET-*-task-71.json.tmp \
   /mnt/d/ai/stock/claude-submit/claude-POSTMARKET-*-task-71.json
```

Cowork 環境同理，把路徑前綴換成 `/sessions/happy-gracious-pasteur/mnt/stock/`。

### 8.4 驗證（選用）

寫完後確認：

- `claude-submit/` 有你剛寫的 `.json`（沒 `.tmp` 殘留）
- 30-60 秒後檢查（可省略）：`claude-submit/processed/` 會出現 `<hint>.processed.json`，代表 Java 已 submit 成功
- 若 `claude-submit/failed/` 出現 `<hint>.failed.json`，代表 schema 驗證失敗 — 開檔看原因

Scheduled 環境下寫完就可結束，不必等驗證（Austin 會在 15:30 LINE 通知檢查）。

---

## 禁止事項

- 不發 LINE
- 不直接給張數與最終下單
- 候選股不得由固定觀察池產生，必須依今日全市場掃描結果
- **不要呼叫 `localhost:8080`** — 走 file bridge（8.3）
- **不要跳過 `.tmp` 階段直接寫 `.json`** — watcher 可能讀到半成品
- **不要自行創造 taskId** — 必須用 `claude-research-request.json` 的值
