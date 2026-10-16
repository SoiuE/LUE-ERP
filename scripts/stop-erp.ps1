$ErrorActionPreference = 'Stop'
$Root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$RuntimeDir = Join-Path $Root 'logs\runtime'

foreach ($name in @('frontend', 'backend')) {
    $stateFile = Join-Path $RuntimeDir "$name.json"
    if (-not (Test-Path -LiteralPath $stateFile)) { continue }
    try {
        $state = Get-Content -Raw -LiteralPath $stateFile | ConvertFrom-Json
        $process = Get-Process -Id ([int]$state.pid) -ErrorAction SilentlyContinue
        if (-not $process) {
            Remove-Item -LiteralPath $stateFile -Force
            Write-Host "$name 已停止。"
            continue
        }
        $startedTicks = $process.StartTime.ToUniversalTime().Ticks
        if ($startedTicks -ne [long]$state.startedUtcTicks) {
            Remove-Item -LiteralPath $stateFile -Force
            Write-Host "$name 的记录 PID 已被其他程序使用，未终止该程序。"
            continue
        }
        & taskkill.exe /PID ([int]$state.pid) /T /F | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "无法停止 $name (PID $($state.pid))" }
        Remove-Item -LiteralPath $stateFile -Force
        Write-Host "$name 已停止。"
    } catch {
        Write-Warning "$name 停止时发生问题：$($_.Exception.Message)"
    }
}
Write-Host '一键停止完成。'
