<#
.SYNOPSIS
  race.js 또는 retry_mix.js 를 한 회차(round) 실행하고, 결과/로그/대시보드를 회차 전용 폴더에 정리한다.
  race와 retry_mix는 항상 따로따로 돌린다 - 결과는 race/ 또는 retry_mix/ 둘 중 하나에만 들어간다.

.EXAMPLE
  # race만: 10,000 동시요청
  ./run-round.ps1 -Round "race-10k" -RaceVus 10000

.EXAMPLE
  # race만: 20,000 동시요청
  ./run-round.ps1 -Round "race-20k" -RaceVus 20000

.EXAMPLE
  # retry_mix만: 동일 유저 5,000명 x 4회
  ./run-round.ps1 -Round "retry-5k" -RaceVus 0 -RetryUsers 5000
#>
param(
  [Parameter(Mandatory = $true)][string]$Round,
  [int]$RaceVus = 10000,
  [string]$ApiMode = "stub",
  [string]$BaseUrl = "http://localhost:3000",
  [string]$CampaignId = "1",
  [long]$UserIdBase = 900000,
  [string]$RampUp = "15s",
  [int]$RetryUsers = 0,
  [int]$RetryAttempts = 4,
  [int]$RetryBackoff = 1
)

# 주의: 아래에서 일부러 $ErrorActionPreference를 "Stop"으로 두지 않는다.
# k6(네이티브 exe)는 진행상황/로그를 stderr로도 찍는데, PowerShell 5.1에서
# "2>&1 | Tee-Object"로 네이티브 명령의 stderr를 받으면 각 줄이 NativeCommandError로
# 래핑되면서 -Stop과 만나 스크립트가 중간에 죽는다(exit code가 0이어도). 그래서
# 스트림 리다이렉션 대신 Start-Transcript로 콘솔 출력을 그대로 파일에 받는다.

if ($RaceVus -gt 0 -and $RetryUsers -gt 0) {
  Write-Error "race와 retry_mix는 같이 돌리지 않습니다. -RaceVus 또는 -RetryUsers 둘 중 하나만 0보다 크게 지정하세요."
  exit 1
}

if ($RaceVus -gt 0) {
  $category = "race"
  $scriptName = "race.js"
} elseif ($RetryUsers -gt 0) {
  $category = "retry_mix"
  $scriptName = "retry_mix.js"
} else {
  Write-Error "RaceVus와 RetryUsers가 둘 다 0입니다. 둘 중 하나는 0보다 커야 합니다."
  exit 1
}

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$logsDir = Join-Path $scriptDir "$category\logs"
$dashboardsDir = Join-Path $scriptDir "$category\dashboards"
New-Item -ItemType Directory -Force -Path $logsDir | Out-Null
New-Item -ItemType Directory -Force -Path $dashboardsDir | Out-Null

$summaryPath = Join-Path $logsDir "$Round.summary.json"
$dashboardPath = Join-Path $dashboardsDir "$Round.html"
$logPath = Join-Path $logsDir "$Round.log"

Write-Host "=== [$category/$Round] $scriptName 실행 (RACE_VUS=$RaceVus, RETRY_USERS=$RetryUsers, RAMP_UP=$RampUp) ==="
Write-Host "로그       : $logPath"
Write-Host "대시보드   : $dashboardPath"

$env:K6_WEB_DASHBOARD = "true"
$env:K6_WEB_DASHBOARD_EXPORT = $dashboardPath

Start-Transcript -Path $logPath -Force | Out-Null
try {
  & k6 run `
    --summary-export=$summaryPath `
    -e API_MODE=$ApiMode `
    -e BASE_URL=$BaseUrl `
    -e CAMPAIGN_ID=$CampaignId `
    -e USER_ID_BASE=$UserIdBase `
    -e RAMP_UP=$RampUp `
    -e RACE_VUS=$RaceVus `
    -e RETRY_USERS=$RetryUsers `
    -e RETRY_ATTEMPTS=$RetryAttempts `
    -e RETRY_BACKOFF=$RetryBackoff `
    -e ROUND_LABEL=$Round `
    (Join-Path $scriptDir "$category\$scriptName")
} finally {
  Stop-Transcript | Out-Null
}

Remove-Item Env:\K6_WEB_DASHBOARD -ErrorAction SilentlyContinue
Remove-Item Env:\K6_WEB_DASHBOARD_EXPORT -ErrorAction SilentlyContinue

Write-Host ""
Write-Host "=== [$category/$Round] 완료 ==="
Write-Host "  로그       : $logPath"
Write-Host "  summary    : $summaryPath"
Write-Host "  대시보드   : $dashboardPath"
