# Week 1 P0-2 Notification Delivery Truth Plan

目的：修正「notification_log 有 row = 已送達」的假信心。此任務只補通知 truth layer，不改任何交易 BUY/SELL/ENTER/EXIT 決策。

## 背景問題

目前 TelegramTemplateService / LineTemplateService 在 sender.send(...) 之後都會寫 notification_log，但 notification_log 沒有 attempted / delivered / failed / provider status 欄位。

因此：
- notification_log row 只能證明系統嘗試產生通知紀錄。
- 不能證明 Telegram / LINE 真的送達。
- sender disabled、credential missing、HTTP failure 時，仍可能留下與成功通知難以區分的 row。

## W1-2 目標

把 notification_log 擴充成 delivery-aware truth record，至少能回答：

1. 系統是否建立了通知？
2. 是否有嘗試送 provider？
3. provider 是 Telegram 還是 LINE？
4. provider 回傳成功或失敗？
5. 若失敗，是 disabled / missing credentials / HTTP error / exception / empty message？
6. notification API 是否能顯示這些欄位？

## 最小落地範圍

### DB / Entity / DTO

新增 notification_log 欄位：
- provider VARCHAR(30)
- delivery_status VARCHAR(30)
  - CREATED
  - SKIPPED
  - ATTEMPTED
  - DELIVERED
  - FAILED
- attempted BOOLEAN
- delivered BOOLEAN
- attempted_at DATETIME NULL
- delivered_at DATETIME NULL
- provider_http_status INT NULL
- provider_message_id VARCHAR(120) NULL
- error_code VARCHAR(80) NULL
- error_body TEXT NULL
- retry_count INT DEFAULT 0

Migration 要 idempotent，避免本機重跑 recovery 時 ADD COLUMN 重複失敗。

### Sender result

新增一個輕量 DTO/record，例如 NotificationDeliveryResult：
- provider
- attempted
- delivered
- status
- httpStatus
- providerMessageId
- errorCode
- errorBody
- retryCount

TelegramSender / LineSender：
- 保留原 boolean send(String message) API，避免破壞既有 caller。
- 新增 sendWithResult(String message) 或 sendDetailed(String message)。
- boolean send(...) 可委派給 sendWithResult(...).delivered()。

### Template logging

TelegramTemplateService / LineTemplateService：
- 改用 sendWithResult。
- 寫 notification_log 時帶 delivery truth 欄位。
- cooldown duplicate skip 不應新增 delivered row；若未產生通知就維持原本不寫 log。
- notification log persist failure 不得影響交易流程。

### API

NotificationResponse 加上 delivery truth 欄位。
既有欄位維持，避免破壞 dashboard。

### Tests

至少新增/更新：
- TelegramSenderTests：disabled / missing credentials / HTTP success / HTTP failure result。
- LineSenderRetryTests 或新增 LineSenderDeliveryResultTests：disabled / success / failure / 429 retry count。
- NotificationServiceTests：create with delivery fields maps to entity/response。
- TelegramTemplateService 或 NotificationFacade tests：send false 時 notification row delivered=false / status=FAILED 或 SKIPPED。
- API response 包含 delivery fields（若已有 controller tests 可補）。

## 安全限制

- 不改任何交易決策。
- 不改 NotificationFacade 對主流程 swallow exception 的保護。
- 不讓通知失敗 throw 到 scheduler / trading workflow。
- 不新增重送排程；本步只記錄 truth，不改行為。
- 不改 Telegram / LINE 發送內容格式，除非測試必要。

## 驗收標準

- git diff 不包含 trading decision engine semantic changes。
- `mvn -q -DskipTests compile` PASS。
- Targeted notification tests PASS。
- Full `mvn -q test` PASS 或明確標示既有 flaky。
- Live API `/api/notifications?limit=3` 顯示 delivery truth 欄位。

## 下一步銜接

完成後可做 W1-3 Scheduler Health Level，把 scheduler SUCCESS 拆成 SUCCESS_REAL / SUCCESS_WITH_FALLBACK / DEGRADED / EMPTY_DATA / SKIPPED / FAILED。
