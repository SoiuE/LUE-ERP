$ErrorActionPreference = 'Stop'
trap {
    Write-Host "启动失败：$($_.Exception.Message)" -ForegroundColor Red
    if ($_.ScriptStackTrace) { Write-Host $_.ScriptStackTrace -ForegroundColor DarkRed }
    break
}
$Root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$Backend = Join-Path $Root 'backend'
$Frontend = Join-Path $Root 'frontend'
$LogDir = Join-Path $Root 'logs'
$RuntimeDir = Join-Path $LogDir 'runtime'
[void](New-Item -ItemType Directory -Force -Path $RuntimeDir)

function Test-TcpPort([string]$HostName, [int]$Port) {
    $client = [System.Net.Sockets.TcpClient]::new()
    try {
        $task = $client.ConnectAsync($HostName, $Port)
        return $task.Wait(1000) -and $client.Connected
    } catch { return $false }
    finally { $client.Dispose() }
}

function Test-Http([string]$Uri, [bool]$IgnoreCertificate = $false) {
    $curl = Get-Command 'curl.exe' -ErrorAction SilentlyContinue
    if (-not $curl) { return $false }

    $arguments = @('--silent', '--output', 'NUL', '--write-out', '%{http_code}', '--max-time', '3')
    if ($IgnoreCertificate) { $arguments += '--insecure' }
    $arguments += $Uri
    try {
        $statusText = (& $curl.Source @arguments 2>$null | Out-String).Trim()
        if ($LASTEXITCODE -ne 0) { return $false }
        $statusCode = 0
        return [int]::TryParse($statusText, [ref]$statusCode) -and $statusCode -ge 200 -and $statusCode -lt 400
    } catch { return $false }
}

function Start-ServiceProcess([string]$Name, [string]$WorkingDirectory, [string]$Command, [string]$StateFile) {
    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $stdout = Join-Path $LogDir "$Name-$stamp.log"
    $stderr = Join-Path $LogDir "$Name-$stamp-error.log"
    $process = Start-Process -FilePath $env:ComSpec -ArgumentList @('/c', $Command) `
        -WorkingDirectory $WorkingDirectory -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput $stdout -RedirectStandardError $stderr
    Start-Sleep -Milliseconds 1200
    $process.Refresh()
    if ($process.HasExited) {
        throw "$Name 启动进程已退出。请查看日志：$stderr"
    }
    $process.Refresh()
    $state = [ordered]@{
        name = $Name
        pid = $process.Id
        startedUtcTicks = $process.StartTime.ToUniversalTime().Ticks
        stdout = $stdout
        stderr = $stderr
    }
    $state | ConvertTo-Json | Set-Content -LiteralPath $StateFile -Encoding UTF8
    Write-Host "$Name 已启动，日志：$stdout"
}

Write-Host 'LUE ERP 一键启动' -ForegroundColor Cyan
Write-Host "项目目录：$Root"

foreach ($command in @('java', 'mvn', 'node', 'npm')) {
    if (-not (Get-Command $command -ErrorAction SilentlyContinue)) {
        throw "缺少 $command。请先安装 README.md 中列出的运行环境后再启动。"
    }
}
if (-not (Test-Path (Join-Path $Backend '.env'))) {
    throw '未找到 backend\.env。请先按 README.md 配置数据库连接；脚本不会修改或覆盖该文件。'
}
if (-not (Test-Path (Join-Path $Frontend 'node_modules\vite'))) {
    throw '未找到前端依赖。请先在 frontend 目录运行 npm install，然后重新启动。'
}
if (-not (Test-TcpPort '127.0.0.1' 3306)) {
    throw '本机 MySQL 的 3306 端口无法连接。请先启动 MySQL 服务，再双击此脚本。'
}

$mavenRepo = Join-Path $Root '.m2\repository'
$backendCommand = if (Test-Path -LiteralPath $mavenRepo) {
    'mvn -Dmaven.repo.local="' + $mavenRepo + '" spring-boot:run'
} else {
    'mvn spring-boot:run'
}

$backendUrl = 'http://localhost:8080/api/health'
$frontendHttps = (Test-Path (Join-Path $Frontend '.mobile-https\server.key')) -and (Test-Path (Join-Path $Frontend '.mobile-https\server.crt'))
$frontendUrl = if ($frontendHttps) { 'https://localhost:5173/' } else { 'http://localhost:5173/' }
$backendState = Join-Path $RuntimeDir 'backend.json'
$frontendState = Join-Path $RuntimeDir 'frontend.json'

if (-not (Test-TcpPort '127.0.0.1' 8080)) {
    Start-ServiceProcess 'backend' $Backend $backendCommand $backendState
} elseif (-not (Test-Http $backendUrl)) {
    throw '8080 端口已被占用，但没有检测到 LUE ERP 后端健康检查。请关闭占用该端口的程序后重试。'
} else {
    Write-Host '后端已在运行，跳过启动。'
}

if (-not (Test-TcpPort '127.0.0.1' 5173)) {
    Start-ServiceProcess 'frontend' $Frontend 'npm run dev' $frontendState
} elseif (-not (Test-Http $frontendUrl $frontendHttps)) {
    throw '5173 端口已被占用，但前端页面没有响应。请关闭占用该端口的程序后重试。'
} else {
    Write-Host '前端已在运行，跳过启动。'
}

Write-Host '等待后端健康检查……'
$deadline = (Get-Date).AddMinutes(2)
while ((Get-Date) -lt $deadline -and -not (Test-Http $backendUrl)) { Start-Sleep -Seconds 2 }
if (-not (Test-Http $backendUrl)) {
    $lastError = Get-ChildItem -LiteralPath $LogDir -Filter 'backend-*-error.log' | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    $hint = if ($lastError) { " 后端错误日志：$($lastError.FullName)" } else { '' }
    throw "后端在 2 分钟内未通过健康检查。请确认数据库配置和 MySQL 状态。$hint"
}

$deadline = (Get-Date).AddSeconds(45)
while ((Get-Date) -lt $deadline -and -not (Test-Http $frontendUrl $frontendHttps)) { Start-Sleep -Seconds 1 }
if (-not (Test-Http $frontendUrl $frontendHttps)) {
    throw '前端在 45 秒内没有响应，请查看 logs 目录中的 frontend 日志。'
}

Write-Host "系统已就绪：$frontendUrl" -ForegroundColor Green
Write-Host '正在打开浏览器……'
Start-Process $frontendUrl
Write-Host "启动完成。此窗口保持打开；关闭窗口不会停止后台服务。需要停止系统时，双击根目录中的 一键停止ERP.bat。日志保存在：$LogDir" -ForegroundColor Cyan
