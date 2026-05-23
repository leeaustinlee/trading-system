# Windows Task Scheduler 設定說明

## 腳本位置

```
D:\ai\stock\run-claude-research.ps1
```

## 5 個排程任務設定

在 Windows 工作排程器新增以下 5 個工作，設定與現有 Codex 排程相同方式。

---

### 1. ClaudeResearch-0820（盤前研究）

| 欄位 | 值 |
|---|---|
| 名稱 | ClaudeResearch-0820 |
| 觸發程序 | 每週一至週五 08:20 |
| 動作 | PowerShell |
| 引數 | `-NoProfile -ExecutionPolicy Bypass -File "D:\ai\stock\run-claude-research.ps1" -ResearchType premarket` |

---

### 2. ClaudeResearch-0920（開盤研究）

| 欄位 | 值 |
|---|---|
| 名稱 | ClaudeResearch-0920 |
| 觸發程序 | 每週一至週五 09:20 |
| 動作 | PowerShell |
| 引數 | `-NoProfile -ExecutionPolicy Bypass -File "D:\ai\stock\run-claude-research.ps1" -ResearchType opening` |

---

### 3. ClaudeResearch-1115（盤中研究）

| 欄位 | 值 |
|---|---|
| 名稱 | ClaudeResearch-1115 |
| 觸發程序 | 每週一至週五 11:15 |
| 動作 | PowerShell |
| 引數 | `-NoProfile -ExecutionPolicy Bypass -File "D:\ai\stock\run-claude-research.ps1" -ResearchType midday` |

---

### 4. ClaudeResearch-1518（盤後研究）

| 欄位 | 值 |
|---|---|
| 名稱 | ClaudeResearch-1518 |
| 觸發程序 | 每週一至週五 15:18 |
| 動作 | PowerShell |
| 引數 | `-NoProfile -ExecutionPolicy Bypass -File "D:\ai\stock\run-claude-research.ps1" -ResearchType postmarket` |

---

### 5. ClaudeResearch-1818（明日研究）

| 欄位 | 值 |
|---|---|
| 名稱 | ClaudeResearch-1818 |
| 觸發程序 | 每週一至週五 18:18 |
| 動作 | PowerShell |
| 引數 | `-NoProfile -ExecutionPolicy Bypass -File "D:\ai\stock\run-claude-research.ps1" -ResearchType tomorrow` |

---

## 快速新增指令（以系統管理員身份執行 PowerShell）

```powershell
$tasks = @(
  @{Name='ClaudeResearch-0820'; Hour=8;  Minute=20; Type='premarket'},
  @{Name='ClaudeResearch-0920'; Hour=9;  Minute=20; Type='opening'},
  @{Name='ClaudeResearch-1115'; Hour=11; Minute=15; Type='midday'},
  @{Name='ClaudeResearch-1518'; Hour=15; Minute=18; Type='postmarket'},
  @{Name='ClaudeResearch-1818'; Hour=18; Minute=18; Type='tomorrow'}
)

foreach ($t in $tasks) {
  $action  = New-ScheduledTaskAction -Execute 'powershell.exe' `
    -Argument "-NoProfile -ExecutionPolicy Bypass -File `"D:\ai\stock\run-claude-research.ps1`" -ResearchType $($t.Type)"
  $trigger = New-ScheduledTaskTrigger -Weekly `
    -DaysOfWeek Monday,Tuesday,Wednesday,Thursday,Friday `
    -At "$($t.Hour):$($t.Minute.ToString('D2'))"
  Register-ScheduledTask -TaskName $t.Name -Action $action -Trigger $trigger `
    -RunLevel Highest -Force
  Write-Host "Registered: $($t.Name)"
}
```

---

## 驗證

手動測試一個任務：

```powershell
& "D:\ai\stock\run-claude-research.ps1" -ResearchType premarket
```

成功後確認 `D:\ai\stock\claude-research-latest.md` 有被更新。
Log 檔在 `D:\ai\stock\logs\` 下。

## Theme-first MVP-3 governance submit rule

若 request JSON 含 `prompt_governance_contract` / `theme_governance_trace` / `leadership_symbols` / `peer_shadow_candidates` / divergence or taxonomy gap signals，Claude submit JSON 必須包含並填寫：

- `leadership_analysis`
- `divergence_analysis`
- `taxonomy_gap_analysis`
- `peer_shadow_analysis`

`peer_shadow_candidates` 與 universe 外 symbol 只能作 observability / shadow-only 分析；不得進 `scores`、`thesis`、`final_enter_candidates`。如需提及 universe 外 symbol，標示 `OUTSIDE_ALLOWED_UNIVERSE_SHADOW_ONLY`。
