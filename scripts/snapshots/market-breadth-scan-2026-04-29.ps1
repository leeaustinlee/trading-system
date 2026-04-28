param(
  [string]$OutputPath = 'D:\ai\stock\market-breadth-scan.json',
  [string]$ResearchPath = 'D:\ai\stock\codex-research-latest.md'
)

$ErrorActionPreference = 'Stop'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

# =====================================================================
# Three-tier changePct strategy (added 2026-04-29 per 04-28 review)
# ---------------------------------------------------------------------
# 04-28 review found: top 5 candidates had changePct 9.79-9.95% (essentially
# limit-up). TW market historical 30% gap-down rate the next day for limit-up
# chasers. Even though they self-flagged TradabilityTag = "題材指標，不列主進場",
# the score formula still ranked them top 5 because (pct * 1.25) dominates.
#
# Tier 1 (主候選 / clean entry):   2.0% <= changePct <= $tier1MaxPct
#   - "可回測進場候選"
#   - normal score
# Tier 2 (次候選 / caution):       $tier1MaxPct < changePct <= $tier2MaxPct
#   - "漲幅過大，僅參考"
#   - score gets $tier2ScorePenalty subtracted so volume can't push them top
# Tier 3 (題材指標 / informational): changePct > $tier2MaxPct (or IsLimitRisk)
#   - "題材指標，不列主進場"  (existing behavior preserved)
#
# Manual test cases:
#   pct=2.5,nearHigh=0.98 -> Tier1, "可回測進場候選", score normal
#   pct=7.5,nearHigh=0.98 -> Tier2, "漲幅過大，僅參考", score -5
#   pct=9.85,nearHigh=0.99 -> Tier3, "題材指標，不列主進場" (informational)
#   pct=1.4 -> universe-filtered out (initial gate is >= 1.5)
# =====================================================================
$tier1MaxPct = 7.0
$tier2MaxPct = 8.8
$tier2ScorePenalty = 5

function Num($value) {
  if ($null -eq $value -or $value -eq '' -or $value -eq '-' -or $value -eq '--') { return $null }
  $s = ($value.ToString()).Trim() -replace ',', '' -replace '\+', ''
  try { return [double]$s } catch { return $null }
}

function Get-MinguoDate([datetime]$dt) {
  return ('{0:000}/{1:00}/{2:00}' -f ($dt.Year - 1911), $dt.Month, $dt.Day)
}

function Get-Theme([string]$code, [string]$name) {
  $text = "$code $name"
  if ($code -match '^00') { return 'ETF/槓桿' }
  if ($text -match '台積電|聯電|聯發科|世芯|創意|智原|力積電|昇陽半導體|南茂|京元電|日月光|矽創|愛普|矽力') { return '半導體/IC' }
  if ($text -match '南亞科|華邦電|旺宏|威剛|群聯|宇瞻|品安|十銓|創見') { return '記憶體/儲存' }
  if ($text -match '廣達|緯創|緯穎|英業達|技嘉|華碩|神達|勤誠|永擎|鴻海|仁寶|和碩|啟碁') { return 'AI伺服器/電腦週邊' }
  if ($text -match '台光電|金像電|南電|景碩|華通|臻鼎|欣興|瀚宇博|健鼎|定穎|尖點|台虹|高技|嘉聯益|耀華|聯茂|達邁|榮科') { return 'PCB/載板/材料' }
  if ($text -match '奇鋐|雙鴻|建準|健策|高力|尼得科|力致|泰碩') { return '散熱/機構' }
  if ($text -match '台玻|富喬|建榮|玻纖|玻璃') { return '玻纖/玻璃材料' }
  if ($text -match '長榮|陽明|萬海|慧洋|裕民|新興|航空|華航|長榮航') { return '航運/航空' }
  if ($text -match '富邦金|國泰金|中信金|玉山金|元大金|凱基金|兆豐金|金融') { return '金融' }
  if ($text -match '大研生醫|保瑞|藥華藥|康霈|生技|醫療|藥') { return '生技醫療' }
  if ($text -match '機器人|羅昇|所羅門|盟立|直得|東元|新代') { return '機器人/自動化' }
  if ($text -match '軍工|雷虎|龍德|寶一|晟田|亞航') { return '軍工/航太' }
  if ($text -match '富采|億光|晶電|隆達|光磊|面板|友達|群創') { return '光電/面板' }
  if ($text -match '仲琦|明泰|智邦|中磊|啟碁|正文') { return '網通/通訊' }
  return '其他強勢股'
}

function Convert-Row($market, $row) {
  if ($market -eq 'OTC' -and (-not $row.value -or $row.value.Count -lt 11)) { return $null }
  if ($market -eq 'TSE') {
    $code = $row.'證券代號'
    $name = $row.'證券名稱'
    $volume = Num $row.'成交股數'
    $amount = Num $row.'成交金額'
    $open = Num $row.'開盤價'
    $high = Num $row.'最高價'
    $low = Num $row.'最低價'
    $close = Num $row.'收盤價'
    $change = Num $row.'漲跌價差'
    $trades = Num $row.'成交筆數'
  } else {
    $v = $row.value
    $code = $v[0]
    $name = $v[1]
    $close = Num $v[2]
    $change = Num $v[3]
    $open = Num $v[4]
    $high = Num $v[5]
    $low = Num $v[6]
    $volume = Num $v[8]
    $amount = Num $v[9]
    $trades = Num $v[10]
  }

  if (-not $code -or -not $name -or $null -eq $close -or $null -eq $change -or $close -le 0) { return $null }
  $prev = $close - $change
  if ($prev -le 0) { return $null }
  $pct = ($change / $prev) * 100
  $nearHigh = if ($high -and $high -gt 0) { $close / $high } else { 0 }
  $rangePct = if ($low -and $low -gt 0) { (($high - $low) / $low) * 100 } else { 0 }
  $isCommon = $code -match '^\d{4}$'
  $theme = Get-Theme $code $name
  $amountYi = if ($amount) { $amount / 100000000 } else { 0 }
  $turnoverScore = [Math]::Log10([Math]::Max($amountYi, 0.01) + 1) * 3
  $score = ($pct * 1.25) + $turnoverScore
  if ($nearHigh -ge 0.99) { $score += 3 } elseif ($nearHigh -ge 0.97) { $score += 1.5 }
  if ($open -and $close -gt $open) { $score += 1 }
  if ($rangePct -gt 8 -and $nearHigh -lt 0.96) { $score -= 3 }
  if ($close -gt 800) { $score -= 2 }
  if (-not $isCommon -and $theme -ne 'ETF/槓桿') { $score -= 2 }

  # Tier 2 penalty: changePct in ($tier1MaxPct, $tier2MaxPct] gets a fixed
  # score deduction so high-volume "次候選" can't out-rank clean Tier 1 picks.
  # See header comment for rationale (04-28 limit-up-chaser review).
  $isTier2 = ($pct -gt $tier1MaxPct -and $pct -le $tier2MaxPct)
  if ($isTier2) { $score -= $tier2ScorePenalty }

  $isLimitRisk = ($pct -ge 9.2 -and $nearHigh -ge 0.995)

  if ($isLimitRisk -or $pct -gt $tier2MaxPct) {
    $tag = '題材指標，不列主進場'
  } elseif (($close * 1000) -gt 160000) {
    $tag = '高價研究參考，非主進場'
  } elseif ($isTier2 -and $nearHigh -ge 0.96) {
    $tag = '漲幅過大，僅參考'
  } elseif ($pct -ge 2 -and $pct -le $tier1MaxPct -and $nearHigh -ge 0.96) {
    $tag = '可回測進場候選'
  } else {
    $tag = '觀察'
  }

  return [PSCustomObject]@{
    Code = $code
    Name = $name
    Market = $market
    Theme = $theme
    IsCommonStock = $isCommon
    Close = [Math]::Round($close, 2)
    Change = [Math]::Round($change, 2)
    ChangePct = [Math]::Round($pct, 2)
    Open = if ($open) { [Math]::Round($open, 2) } else { $null }
    High = if ($high) { [Math]::Round($high, 2) } else { $null }
    Low = if ($low) { [Math]::Round($low, 2) } else { $null }
    NearHigh = [Math]::Round($nearHigh, 4)
    Volume = if ($volume) { [int64]$volume } else { 0 }
    Amount = if ($amount) { [int64]$amount } else { 0 }
    AmountYi = [Math]::Round($amountYi, 2)
    Trades = if ($trades) { [int64]$trades } else { 0 }
    Score = [Math]::Round($score, 2)
    BoardLotCost = [Math]::Round($close * 1000, 0)
    IsBoardLotAffordable = (($close * 1000) -le 160000)
    IsLimitRisk = $isLimitRisk
    IsTier2 = $isTier2
    TradabilityTag = $tag
  }
}

function Get-TwseAll {
  $url = 'https://www.twse.com.tw/exchangeReport/STOCK_DAY_ALL?response=open_data'
  $raw = Invoke-RestMethod -Uri $url -Headers @{ 'User-Agent'='Mozilla/5.0'; Referer='https://www.twse.com.tw/' }
  $rows = $raw | ConvertFrom-Csv
  foreach ($row in $rows) {
    $obj = Convert-Row 'TSE' $row
    if ($obj) { $obj }
  }
}

function Get-TpexAll {
  $today = Get-Date
  $date = Get-MinguoDate $today
  $url = "https://www.tpex.org.tw/web/stock/aftertrading/daily_close_quotes/stk_quote_result.php?l=zh-tw&o=json&d=$date&s=0,asc,0"
  $r = Invoke-RestMethod -Uri $url -Headers @{ 'User-Agent'='Mozilla/5.0'; Referer='https://www.tpex.org.tw/' }
  $table = $r.tables | Where-Object { $_.title -eq '上櫃股票行情' } | Select-Object -First 1
  if (-not $table) { return @() }
  foreach ($row in $table.data) {
    $obj = Convert-Row 'OTC' $row
    if ($obj) { $obj }
  }
}

$errors = @()
$all = @()
try { $all += @(Get-TwseAll) } catch { $errors += "TWSE: $($_.Exception.Message)" }
try { $all += @(Get-TpexAll) } catch { $errors += "TPEx: $($_.Exception.Message)" }

$stockUniverse = @($all | Where-Object { $_.IsCommonStock -and $_.Amount -ge 100000000 -and $_.ChangePct -ge 1.5 })
$hotStocks = @($stockUniverse | Sort-Object Score -Descending | Select-Object -First 30)
# Tier 1 main-entry pool: strict 2.0% <= changePct <= $tier1MaxPct (was <= 8.8).
# Tier 2 (次候選, $tier1MaxPct < pct <= $tier2MaxPct) is intentionally excluded
# from the primary pool so 9-10% chasers (and even 7-8.8%) can't be picked as
# 主候選 by FinalDecision. See header comment for the 04-28 review rationale.
$tradableUniverse = @($stockUniverse | Where-Object {
  -not $_.IsLimitRisk -and
  $_.ChangePct -ge 2.0 -and $_.ChangePct -le $tier1MaxPct -and
  $_.NearHigh -ge 0.96 -and
  $_.Amount -ge 300000000
})
# Tier 2 caution pool: surfaced for downstream visibility but flagged.
$tier2Universe = @($stockUniverse | Where-Object {
  -not $_.IsLimitRisk -and
  $_.ChangePct -gt $tier1MaxPct -and $_.ChangePct -le $tier2MaxPct -and
  $_.NearHigh -ge 0.96 -and
  $_.Amount -ge 300000000
})
$affordableTradableUniverse = @($tradableUniverse | Where-Object { $_.IsBoardLotAffordable })
$themeRows = @($stockUniverse | Group-Object Theme | ForEach-Object {
  $items = @($_.Group | Sort-Object Score -Descending)
  $themeName = $_.Name
  $tradableItems = @($affordableTradableUniverse | Where-Object { $_.Theme -eq $themeName })
  $turnover = ($items | Measure-Object AmountYi -Sum).Sum
  $avgPct = ($items | Measure-Object ChangePct -Average).Average
  $avgNear = ($items | Measure-Object NearHigh -Average).Average
  [PSCustomObject]@{
    Theme = $_.Name
    Count = $items.Count
    TradableCount = $tradableItems.Count
    TotalAmountYi = [Math]::Round($turnover, 2)
    AvgChangePct = [Math]::Round($avgPct, 2)
    AvgNearHigh = [Math]::Round($avgNear, 4)
    Score = [Math]::Round(($items.Count * 1.8) + ($turnover * 0.08) + ($avgPct * 1.5) + (($avgNear - 0.95) * 20) + ($tradableItems.Count * 2.5), 2)
    TopStocks = @($items | Select-Object -First 3)
    TradableStocks = @($tradableItems | Sort-Object Score -Descending | Select-Object -First 3)
  }
} | Sort-Object Score -Descending)

$strongThemes = @($themeRows | Where-Object { $_.Theme -ne '其他強勢股' } | Select-Object -First 3)
$limitIndicators = @($hotStocks | Where-Object { $_.IsLimitRisk } | Select-Object -First 8)
$themeCandidates = New-Object System.Collections.ArrayList
foreach ($theme in $strongThemes) {
  foreach ($s in @($theme.TradableStocks | Select-Object -First 3)) { [void]$themeCandidates.Add($s) }
}

$finalMap = @{}
foreach ($s in @($themeCandidates)) {
  if (-not $s) { continue }
  if ($s.Theme -eq 'ETF/槓桿') { continue }
  if (-not $finalMap.ContainsKey($s.Code)) { $finalMap[$s.Code] = $s }
}
if ($finalMap.Count -lt 5) {
  foreach ($s in @($affordableTradableUniverse | Sort-Object Score -Descending | Select-Object -First 30)) {
    if (-not $s) { continue }
    if ($s.Theme -eq 'ETF/槓桿') { continue }
    if (-not $finalMap.ContainsKey($s.Code)) { $finalMap[$s.Code] = $s }
    if ($finalMap.Count -ge 5) { break }
  }
}
$finalCandidates = @($finalMap.Values | Sort-Object @{ Expression = { $strongThemes.Theme -contains $_.Theme }; Descending = $true }, Score -Descending | Select-Object -First 5)
$etfCandidates = @($all | Where-Object { $_.Theme -eq 'ETF/槓桿' -and $_.Amount -ge 100000000 -and $_.ChangePct -ge 1.0 } | Sort-Object Score -Descending | Select-Object -First 5)

$result = [PSCustomObject]@{
  generated_at = (Get-Date).ToString('yyyy-MM-dd HH:mm:ss')
  timezone = 'Asia/Taipei'
  source = 'TWSE STOCK_DAY_ALL + TPEx daily_close_quotes'
  universe_count = $all.Count
  stock_filter = "普通股四碼全市場掃描；三段式漲幅策略：Tier1 主候選漲幅 2%-$($tier1MaxPct)%（成交金額 >= 3 億、收高比 >= 0.96），Tier2 次候選 $($tier1MaxPct)%-$($tier2MaxPct)% 標記漲幅過大僅參考且分數 -$($tier2ScorePenalty)，Tier3 漲幅 > $($tier2MaxPct)% 或鎖漲停只作題材指標不列主進場；固定觀察池不參與候選產生。"
  tier_thresholds = [PSCustomObject]@{
    tier1_max_pct = $tier1MaxPct
    tier2_max_pct = $tier2MaxPct
    tier2_score_penalty = $tier2ScorePenalty
  }
  errors = $errors
  strong_themes = $strongThemes
  hot_stocks = @($hotStocks | Select-Object -First 15)
  limit_indicators = $limitIndicators
  super_strong_5 = @($limitIndicators | Select-Object -First 5)
  tradable_pool = @($tradableUniverse | Sort-Object Score -Descending | Select-Object -First 20)
  tier2_pool = @($tier2Universe | Sort-Object Score -Descending | Select-Object -First 10)
  affordable_tradable_pool = @($affordableTradableUniverse | Sort-Object Score -Descending | Select-Object -First 20)
  etf_candidates = $etfCandidates
  final_candidates_5 = $finalCandidates
  final_candidates_930 = @($finalCandidates | Select-Object -First 3)
}

$result | ConvertTo-Json -Depth 12 | Set-Content -Path $OutputPath -Encoding UTF8

$lines = New-Object System.Collections.ArrayList
[void]$lines.Add("# Codex 全市場盤面掃描")
[void]$lines.Add("")
[void]$lines.Add("更新時間：$($result.generated_at)")
[void]$lines.Add("資料來源：$($result.source)")
[void]$lines.Add("掃描範圍：上市 + 上櫃，全市場，不使用固定觀察池產生候選。")
[void]$lines.Add("")
[void]$lines.Add("## 強勢族群 2-3 組")
foreach ($t in $strongThemes) {
  $names = @($t.TopStocks | Select-Object -First 3 | ForEach-Object { "$($_.Code) $($_.Name)($($_.ChangePct)%)" }) -join '、'
  $tradableNames = @($t.TradableStocks | Select-Object -First 3 | ForEach-Object { "$($_.Code) $($_.Name)($($_.ChangePct)%)" }) -join '、'
  if (-not $tradableNames) { $tradableNames = '暫無，等隔日回測或輪動股' }
  [void]$lines.Add("- $($t.Theme)：族群分數 $($t.Score)，成交 $($t.TotalAmountYi) 億，題材指標：$names；可進場候選：$tradableNames")
}
[void]$lines.Add("")
[void]$lines.Add("## 超強勢 5 檔：09:00 開盤追強觀察")
foreach ($c in $limitIndicators | Select-Object -First 5) {
  [void]$lines.Add("- $($c.Code) $($c.Name)：$($c.Theme)，收 $($c.Close)，漲 $($c.ChangePct)%，成交 $($c.AmountYi) 億。用途：隔日 08:30 列開盤追強觀察；若 09:00 仍鎖強且買得到，可小倉追強；若打開或爆量開高走低，等 09:30 再決定。")
}
[void]$lines.Add("")
[void]$lines.Add("## 明日中短線候選 5 檔：09:30 確認進場")
$i = 1
foreach ($c in $finalCandidates) {
  [void]$lines.Add("$i. $($c.Code) $($c.Name)：$($c.Theme)，收 $($c.Close)，一張約 $($c.BoardLotCost) 元，漲 $($c.ChangePct)%，成交 $($c.AmountYi) 億，收高比 $($c.NearHigh)，分數 $($c.Score)，標籤：$($c.TradabilityTag)。")
  $i++
}
[void]$lines.Add("")
[void]$lines.Add("## ETF/槓桿只作資金曝險參考")
foreach ($c in $etfCandidates | Select-Object -First 3) {
  [void]$lines.Add("- $($c.Code) $($c.Name)：收 $($c.Close)，漲 $($c.ChangePct)%，成交 $($c.AmountYi) 億。")
}
[void]$lines.Add("")
if ($errors.Count -gt 0) { [void]$lines.Add("資料警示：$($errors -join '；')") }
[void]$lines.Add("來源：Codex")
$lines -join "`r`n" | Set-Content -Path $ResearchPath -Encoding UTF8

Write-Output "market_scan_written $OutputPath candidates=$($finalCandidates.Count) themes=$($strongThemes.Count) errors=$($errors.Count)"





