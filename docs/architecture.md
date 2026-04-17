# Architecture

## 分層原則
- `client`：只打外部 API，不做交易判斷
- `engine`：只做規則與決策
- `service`：組裝流程，串接 engine/repository/notify
- `scheduler`：只做時間觸發
- `notify`：只做訊息組裝與送出
- `ai`：只做 prompt 與 adapter，不介入交易規則

## 主流程
1. scheduler 觸發作業
2. service 呼叫 client 抓資料
3. service 呼叫 engine 做決策
4. 結果寫入 repository
5. notify 依事件輸出 LINE
6. controller/API 提供 UI 查詢
