# Narrative Intelligence Layer MVP 手動資料流驗證

本文件記錄如何在不做 crawler、不做 ASR、不接 live score、不接自動交易的前提下，手動灌入一份 KOL / podcast / YouTube / 新聞市場敘事 sample，驗證 Narrative pipeline 是否能產生 dashboard rows、candidate narrativeContext 與 shadow report。

## Guardrail

- Narrative / KOL / podcast / news signal 仍是 `weakSignalOnly=true`。
- 只允許用於：dashboard context、candidate narrativeContext、shadow report。
- 禁止用於：BUY signal、FinalDecision override、live score input、auto trading。
- `FinalDecisionEngine`、PriceGate、RR、market grade、veto、tradability、capital rules 仍是權威。
- Shadow report 必須標示：`computedOnDemand=true; not persisted; shadow only; production decision unchanged`。

## 本次 sample

檔案：

- `docs/fixtures/narrative-2026-05-21-transcript-sample.json`
- `docs/fixtures/narrative-2026-05-21-structured-result-sample.json`

來源屬性：

- `sourceType=TEST_FIXTURE_MARKET_NARRATIVE`
- 這不是 crawler 或 ASR 產物。
- 因本切片不做 crawler/ASR，內容明確標記為 test fixture；主題貼近真實市場敘事：AI power、BBU、PCB/載板/材料、散熱。
- 2026-05-21 既有候選股中有 `4989 榮科`，themeTag 為 `PCB/載板/材料`，因此可驗證 shadow report 中的 narrative match；其他主題若沒有候選股對應，不應硬塞分數。

## API 使用順序

確認服務：

```bash
curl -sS http://127.0.0.1:8888/actuator/health
```

1. 建立 transcript / raw narrative signal：

```bash
SIGNAL_ID=$(curl -sS -X POST http://127.0.0.1:8888/api/narrative/transcripts \
  -H 'Content-Type: application/json' \
  --data-binary @docs/fixtures/narrative-2026-05-21-transcript-sample.json \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])')
```

Expected：回傳 `id`、`signalStatus=RAW`、`duplicate=false`（若重跑可能為 `duplicate=true`）。

2. Submit structured result：

```bash
curl -sS -X POST http://127.0.0.1:8888/api/kol-signals/${SIGNAL_ID}/structured-result \
  -H 'Content-Type: application/json' \
  --data-binary @docs/fixtures/narrative-2026-05-21-structured-result-sample.json
```

Expected：

```json
{"id": <SIGNAL_ID>, "signalStatus": "STRUCTURED", "weakSignalOnly": true}
```

3. Aggregate daily snapshot：

```bash
curl -sS -X POST 'http://127.0.0.1:8888/api/kol-signals/aggregate?date=2026-05-21'
```

Expected：至少包含 `PCB/載板/材料` 與 `AI power/BBU` 的 daily snapshots，欄位含 `sourceCount`、`evidenceCount`、`netShadowBoost`、`crowdingRisk`。

4. 驗證 narrative dashboard：

```bash
curl -sS 'http://127.0.0.1:8888/api/narrative/dashboard?date=2026-05-21'
```

Expected：`rows` 不再是空陣列，且每列含：

- `theme`
- `lifecycle`
- `attention`
- `freshness`：MVP 目前以 daily snapshot 當日 aggregate 表示 freshness；尚未獨立成 response field。
- `crowding`
- `direction`
- `sourceCount`
- `evidenceCount`
- `shadowBoost`

5. 驗證 shadow report：

```bash
curl -sS 'http://127.0.0.1:8888/api/narrative/shadow/report?date=2026-05-21'
```

Expected：

- `note` 包含 `computedOnDemand=true; not persisted; shadow only; production decision unchanged`。
- themeTag 對到 `PCB/載板/材料` 的候選股（本次為 `4989 榮科`）會看到 `kolBoostShadow > 0`。
- 該 item 會包含 `narrativeContext`，內含 `weakSignalOnly=true`、`theme`、`lifecycle`、`attention`、`crowding`、`direction`、`sourceCount`、`evidenceCount`、`shadowBoost`、`guardrail`。
- 若候選股 themeTag 沒有 daily snapshot 對應，`kolBoostShadow=0` 且 `narrativeContext=null`；不得硬塞分數。

## 目前仍是 shadow only

本流程不會：

- 改 candidate 原始分數。
- 改 FinalDecision / BUY / ENTER。
- 改 PriceGate / RR / market grade / veto / tradability / capital rules。
- 啟動 crawler、ASR 或 auto trading。
