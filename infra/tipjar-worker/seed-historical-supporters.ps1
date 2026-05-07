# One-shot KV reset using curl.exe (built into Windows 10+).
# PowerShell's Invoke-RestMethod mangles the body for KV PUT requests
# (sends bytes as space-separated decimals), so we shell out to curl
# which sends the raw bytes correctly.

$ErrorActionPreference = "Continue"
Set-Location -Path $PSScriptRoot

$accountId   = "533c789ac46b951522b39e12c3544718"
$namespaceId = "fca1bc38b42741e6a8cac11f54de5abd"
$kvKey       = "supporters"

$cleanJson = '{"supporters":[{"name":"Nickydicky8","amountUsd":5,"message":"Thanks so much!","timestamp":"2026-05-07T14:30:00Z"},{"name":"Cedric","amountUsd":10,"message":"Just downloaded Stash to replace Spotify. This is amazing bro. Thanks for your work.","timestamp":"2025-01-01T00:00:00Z"},{"name":"Slowcab","amountUsd":5,"message":"Amazing work! Keep sticking it to the man!","timestamp":"2025-01-01T00:00:00Z"},{"name":"RucaNebas","amountUsd":5,"message":"Awesome application! I hope continuous improvement and support","timestamp":"2025-01-01T00:00:00Z"}]}'

# --- Token ---------------------------------------------------------
$tokPlain = $env:CLOUDFLARE_API_TOKEN
if ([string]::IsNullOrWhiteSpace($tokPlain)) {
    $tok = Read-Host "Paste your Cloudflare API token" -AsSecureString
    $tokPlain = [System.Net.NetworkCredential]::new("", $tok).Password
}

# --- Stage payload to a temp file, PUT via curl.exe ---------------
$tmp = [System.IO.Path]::GetTempFileName()
[System.IO.File]::WriteAllText($tmp, $cleanJson, (New-Object System.Text.UTF8Encoding $false))

$putUri = "https://api.cloudflare.com/client/v4/accounts/$accountId/storage/kv/namespaces/$namespaceId/values/$kvKey"
Write-Host ""
Write-Host "Writing clean supporters JSON to KV via curl.exe..." -ForegroundColor Blue
$resp = & curl.exe -s -X PUT $putUri `
    -H "Authorization: Bearer $tokPlain" `
    -H "Content-Type: text/plain" `
    --data-binary "@$tmp"
Remove-Item $tmp -Force

Write-Host "  Cloudflare response: $resp" -ForegroundColor DarkGray
if ($resp -match '"success":true') {
    Write-Host "  KV updated." -ForegroundColor Green
} else {
    Write-Host "  KV write looks failed - aborting." -ForegroundColor Red
    exit 1
}

# --- Verify --------------------------------------------------------
Write-Host ""
Write-Host "Verifying via the live Worker URL..." -ForegroundColor Blue
Start-Sleep -Seconds 3
$verifyJson = & curl.exe -s "https://stash-tipjar.rawnaldclark.workers.dev?bust=$(Get-Random)"
Write-Host "  raw response:" -ForegroundColor DarkGray
Write-Host "  $verifyJson"
try {
    $parsed = $verifyJson | ConvertFrom-Json
    $count = $parsed.supporters.Count
    Write-Host ""
    Write-Host "  live JSON now has $count supporters" -ForegroundColor Green
    foreach ($s in $parsed.supporters) {
        $name = $s.name
        $amt  = $s.amountUsd
        Write-Host ("    - {0} `${1}" -f $name, $amt) -ForegroundColor DarkGray
    }
} catch {
    Write-Host ""
    Write-Host "  parse failed - the Worker may still be serving the old 500 from cache." -ForegroundColor Yellow
    Write-Host "  Wait 60s and try: curl https://stash-tipjar.rawnaldclark.workers.dev" -ForegroundColor DarkGray
}
