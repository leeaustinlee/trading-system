# 新機器裝設檢查清單

> 從 git clone 到 server 跑起來的完整步驟。
> 適用：WSL / Linux / macOS。Windows 原生需改路徑（見下方）。

---

## 1. 前置（系統套件）

| 套件 | 版本 | 用途 |
|---|---|---|
| JDK | 17+ | Spring Boot 3.4 需要 |
| Maven | 3.9+ | build |
| MySQL | 8.0 | 資料庫 |
| curl / jq | 任意 | debug 用 |

WSL / Ubuntu：
```bash
sudo apt update && sudo apt install -y openjdk-17-jdk maven mysql-client curl jq
```

---

## 2. Clone 專案

```bash
cd /mnt/d/ai/stock     # 或任何你的工作目錄
git clone <repo-url> trading-system
cd trading-system
```

---

## 3. 建 MySQL 資料庫

```sql
CREATE DATABASE IF NOT EXISTS trading_system
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- （可選）建專用 user
-- CREATE USER 'trading'@'%' IDENTIFIED BY 'your_password';
-- GRANT ALL PRIVILEGES ON trading_system.* TO 'trading'@'%';
-- FLUSH PRIVILEGES;
```

### Migration 方式

本專案用 `spring.jpa.hibernate.ddl-auto=update`（非 Flyway），所以：

- **自動路線**：啟動 server 時 Hibernate 依 Entity 自動建表+加欄位
- **手動路線**（推薦正式環境）：依序手動跑 `sql/V1__*.sql` → `V10__*.sql`

```bash
cd sql
for f in V*.sql; do
  mysql -h 127.0.0.1 -P 3330 -u root -p trading_system < "$f"
  echo "applied $f"
done
```

---

## 4. 兩個必填 config 檔

這兩個檔案被 `.gitignore` 排除，**新機器一定要自己建**：

### 4.1 `.env`（專案根目錄）

```bash
cp .env.example .env
# 編輯 .env，至少填：
#   DB_PASSWORD=實際密碼
#   LINE_CHANNEL_ACCESS_TOKEN=... (正式機)
#   LINE_TO=群組或使用者 ID
```

### 4.2 `src/main/resources/application-local.yml`

```bash
cp src/main/resources/application-local.yml.example \
   src/main/resources/application-local.yml
```

**Windows 原生請改路徑**（範本為 WSL 路徑）：

```yaml
ai:
  file_bridge:
    processed_dir: D:/ai/stock/claude-submit/processed
    failed_dir:    D:/ai/stock/claude-submit/failed
    retry_dir:     D:/ai/stock/claude-submit/retry
trading:
  claude-submit:
    watch-dir: D:/ai/stock/claude-submit
  ai:
    claude:
      research-output-path: D:/ai/stock/claude-research-latest.md
      request-output-path:  D:/ai/stock/claude-research-request.json
```

---

## 5. 準備 Claude file bridge 資料夾

```bash
mkdir -p /mnt/d/ai/stock/claude-submit/{processed,failed,retry}
# Windows 原生：於 D:\ai\stock\ 建同名子目錄
```

---

## 6. Build + 啟動

```bash
# build
mvn clean package -DskipTests

# 啟動（WSL）
./restart.sh

# 或 Linux 背景跑
set -a && source .env && set +a
SPRING_PROFILES_ACTIVE=local nohup java -Xms512m -Xmx1536m \
  -jar target/trading-system-0.0.1-SNAPSHOT.jar > /tmp/trading.log 2>&1 & disown

# 驗證
curl http://localhost:8080/actuator/health
# {"status":"UP"}
```

---

## 7. 驗收

| 檢查 | 指令 | 預期 |
|---|---|---|
| Server health | `curl -s http://localhost:8080/actuator/health` | `{"status":"UP"}` |
| Migration 完整 | `curl -s http://localhost:8080/api/system/migration/health` | 每項 `ok=true` |
| Dashboard 可開 | 瀏覽器開 `http://localhost:8080/` | 看到操盤主控台 |
| 排程列表 | `curl -s http://localhost:8080/api/scheduler/jobs` | 16 個 job 列出 |
| AI 任務狀態 | `curl -s http://localhost:8080/api/orchestration/tasks/today` | 今日 AI 任務（首日可能為空） |
| TAIFEX probe | `curl -s http://localhost:8080/api/system/external/probe` | taifex.status=OK |

---

## 8. 跨機器資料同步（可選）

若要把舊機的歷史資料也搬過去：

```bash
# 匯出
mysqldump -h old-host -u root -p trading_system > trading_backup.sql

# 匯入新機
mysql -h new-host -u root -p trading_system < trading_backup.sql
```

---

## 9. 常見問題

| 症狀 | 解法 |
|---|---|
| server 起不來，log 顯示 `Table 'trading_system.xxx' doesn't exist` | 手動跑 `sql/V*.sql` 或等 `ddl-auto:update` 自動建表（首次啟動約 5 秒） |
| 健康檢查 stock_theme_mapping 衝突 | 跑 `sql/V8__*.sql` 確認 score_config 有 insert |
| TAIFEX probe WARN | 確認網路可連 `https://openapi.taifex.com.tw`；WebClient buffer 16MB（v2.2 後已預設） |
| Claude submit 檔案不被處理 | 確認 `ai.file_bridge.*` 路徑與實際 `claude-submit/` 對應 |
| LINE 不發 | `.env` 設 `LINE_ENABLED=true` 且填 `LINE_CHANNEL_ACCESS_TOKEN` + `LINE_TO` |

---

## 10. gitignore 掃雷

下列檔案不在 git 且**不需要搬**（自動產生）：

- `target/` — Maven build
- `logs/` — runtime log
- `.idea/`、`*.iml` — IDE
- `.claude/worktrees/` — agent 暫存

下列檔案不在 git 但**一定要**（見 §4）：

- `.env`
- `src/main/resources/application-local.yml`
