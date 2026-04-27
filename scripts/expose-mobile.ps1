# ============================================================
#  trading-system: forward Windows :8080 -> WSL Spring Boot
#  Run as Administrator:
#     PS> Set-ExecutionPolicy -Scope Process Bypass
#     PS> .\scripts\expose-mobile.ps1
#  Re-run after WSL restart (WSL IP changes).
# ============================================================

$wslIp = (wsl hostname -I).Trim().Split(' ')[0]
if (-not $wslIp) {
    Write-Host "ERROR: cannot detect WSL IP. Is WSL running?" -ForegroundColor Red
    exit 1
}
Write-Host ("WSL IP: " + $wslIp)

netsh interface portproxy reset | Out-Null
netsh interface portproxy add v4tov4 listenport=8080 listenaddress=0.0.0.0 connectport=8080 connectaddress=$wslIp | Out-Null
Write-Host ("OK portproxy added: 0.0.0.0:8080 -> " + $wslIp + ":8080")

Get-NetFirewallRule -DisplayName 'TradingSystem 8080' -ErrorAction SilentlyContinue | Remove-NetFirewallRule
New-NetFirewallRule -DisplayName 'TradingSystem 8080' -Direction Inbound -Action Allow -Protocol TCP -LocalPort 8080 | Out-Null
Write-Host "OK firewall inbound 8080 allowed"

Write-Host ""
Write-Host "--- portproxy rules ---"
netsh interface portproxy show v4tov4

Write-Host ""
Write-Host "--- URLs your phone can use (LAN / Tailscale) ---" -ForegroundColor Cyan
Get-NetIPAddress -AddressFamily IPv4 |
    Where-Object { $_.PrefixOrigin -ne 'WellKnown' -and $_.IPAddress -notmatch '^(127\.|169\.254\.|172\.)' } |
    Select-Object @{N='URL';E={"http://" + $_.IPAddress + ":8080/"}}, InterfaceAlias |
    Format-Table -AutoSize
