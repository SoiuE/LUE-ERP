$ErrorActionPreference = 'Stop'

try {
    $utf8 = New-Object System.Text.UTF8Encoding($false)
    [Console]::InputEncoding = $utf8
    [Console]::OutputEncoding = $utf8
    $OutputEncoding = $utf8
} catch {}

$Root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$Backend = Join-Path $Root 'backend'
$Frontend = Join-Path $Root 'frontend'
$Deploy = Join-Path $Root 'deploy'
$LogDir = Join-Path $Root 'logs'
$RuntimeDir = Join-Path $LogDir 'runtime'
$BackendEnvFile = Join-Path $Backend '.env'
$DeployEnvFile = Join-Path $Deploy '.env'
$ComposeFile = Join-Path $Deploy 'docker-compose.yml'
$StartedThisRun = [System.Collections.Generic.List[string]]::new()
[void](New-Item -ItemType Directory -Force -Path $RuntimeDir)

function Add-PathDirectory([string]$Directory) {
    if ([string]::IsNullOrWhiteSpace($Directory) -or -not (Test-Path -LiteralPath $Directory -PathType Container)) {
        return
    }
    $normalized = $Directory.TrimEnd('\')
    $present = @($env:Path -split ';') | Where-Object { $_ -and $_.TrimEnd('\') -ieq $normalized }
    if (-not $present) { $env:Path = "$Directory;$env:Path" }
}

function Import-LatestWindowsPath {
    # Explorer and already-open terminals can retain an older PATH after tools are installed.
    foreach ($scope in @('Machine', 'User')) {
        $pathValue = [Environment]::GetEnvironmentVariable('Path', $scope)
        foreach ($directory in @($pathValue -split ';')) { Add-PathDirectory $directory }
    }
}

function Add-CommonToolPaths {
    foreach ($homePath in @($env:JAVA_HOME, $env:MAVEN_HOME, $env:M2_HOME)) {
        if ($homePath) { Add-PathDirectory (Join-Path $homePath 'bin') }
    }

    $driveRoot = [System.IO.Path]::GetPathRoot($Root)
    $appRoot = Join-Path $driveRoot 'APP'
    if (Test-Path -LiteralPath $appRoot -PathType Container) {
        Add-PathDirectory (Join-Path $appRoot 'node')
        Get-ChildItem -LiteralPath $appRoot -Directory -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -like 'apache-maven-*' -or $_.Name -like 'jdk-*' } |
            Sort-Object Name -Descending |
            ForEach-Object { Add-PathDirectory (Join-Path $_.FullName 'bin') }
    }

    Add-PathDirectory (Join-Path $env:ProgramFiles 'nodejs')
    if ($env:ChocolateyInstall) { Add-PathDirectory (Join-Path $env:ChocolateyInstall 'bin') }
    if ($env:USERPROFILE) { Add-PathDirectory (Join-Path $env:USERPROFILE 'scoop\shims') }
}

function Initialize-ToolPath {
    Import-LatestWindowsPath
    Add-CommonToolPaths
}

function Read-EnvFile([string]$Path) {
    $values = @{}
    if (-not (Test-Path -LiteralPath $Path)) { return $values }
    foreach ($line in Get-Content -LiteralPath $Path) {
        $trimmed = $line.Trim()
        if (-not $trimmed -or $trimmed.StartsWith('#')) { continue }
        $separator = $trimmed.IndexOf('=')
        if ($separator -lt 1) { continue }
        $name = $trimmed.Substring(0, $separator).Trim()
        $value = $trimmed.Substring($separator + 1).Trim()
        if ($value.Length -ge 2 -and (($value[0] -eq '"' -and $value[$value.Length - 1] -eq '"') -or
                ($value[0] -eq "'" -and $value[$value.Length - 1] -eq "'"))) {
            $value = $value.Substring(1, $value.Length - 2)
        }
        $values[$name] = $value
    }
    return $values
}

function Get-Setting($Values, [string]$Name, [string]$Default = '') {
    if ($Values.ContainsKey($Name) -and -not [string]::IsNullOrWhiteSpace([string]$Values[$Name])) {
        return [string]$Values[$Name]
    }
    return $Default
}

function Test-TcpPort([string]$HostName, [int]$Port) {
    $client = [System.Net.Sockets.TcpClient]::new()
    try {
        $task = $client.ConnectAsync($HostName, $Port)
        return $task.Wait(1500) -and $client.Connected
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

function Wait-Http([string]$Uri, [int]$TimeoutSeconds, [bool]$IgnoreCertificate = $false) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        if (Test-Http $Uri $IgnoreCertificate) { return $true }
        Start-Sleep -Seconds 2
    }
    return (Test-Http $Uri $IgnoreCertificate)
}

function Wait-AnyHttp([string[]]$Uris, [int]$TimeoutSeconds, [bool]$IgnoreCertificate = $false) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        foreach ($uri in $Uris) {
            if (Test-Http $uri $IgnoreCertificate) { return $true }
        }
        Start-Sleep -Seconds 2
    }
    return $false
}

function Start-ServiceProcess([string]$Name, [string]$WorkingDirectory, [string]$Command, [string]$StateFile) {
    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $stdout = Join-Path $LogDir "$Name-$stamp.log"
    $stderr = Join-Path $LogDir "$Name-$stamp-error.log"
    $process = Start-Process -FilePath $env:ComSpec -ArgumentList @('/d', '/c', $Command) `
        -WorkingDirectory $WorkingDirectory -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput $stdout -RedirectStandardError $stderr
    Start-Sleep -Milliseconds 1200
    $process.Refresh()
    if ($process.HasExited) { throw "$Name 启动进程已退出。请查看日志：$stderr" }
    $state = [ordered]@{
        name = $Name
        pid = $process.Id
        startedUtcTicks = $process.StartTime.ToUniversalTime().Ticks
        stdout = $stdout
        stderr = $stderr
    }
    $state | ConvertTo-Json | Set-Content -LiteralPath $StateFile -Encoding UTF8
    [void]$StartedThisRun.Add($Name)
    Write-Host "$Name 已启动，日志：$stdout"
}

function Stop-StartedService([string]$Name) {
    $stateFile = Join-Path $RuntimeDir "$Name.json"
    if (-not (Test-Path -LiteralPath $stateFile)) { return }
    try {
        $state = Get-Content -Raw -LiteralPath $stateFile | ConvertFrom-Json
        $process = Get-Process -Id ([int]$state.pid) -ErrorAction SilentlyContinue
        if ($process -and $process.StartTime.ToUniversalTime().Ticks -eq [long]$state.startedUtcTicks) {
            & taskkill.exe /PID ([int]$state.pid) /T /F 2>$null | Out-Null
        }
        Remove-Item -LiteralPath $stateFile -Force -ErrorAction SilentlyContinue
    } catch {
        Write-Warning "清理本次启动的 $Name 时发生问题：$($_.Exception.Message)"
    }
}

function Resolve-MySqlTarget([string]$JdbcUrl) {
    if ($JdbcUrl -notmatch '^jdbc:mysql://(?<host>\[[^\]]+\]|[^:/?#]+)(?::(?<port>\d+))?') {
        throw 'backend\.env 中的 LUE_DB_URL 不是可识别的 MySQL JDBC 地址。'
    }
    $hostName = $Matches.host.Trim('[', ']')
    $port = if ($Matches.port) { [int]$Matches.port } else { 3306 }
    return [pscustomobject]@{ Host = $hostName; Port = $port }
}

function Test-LocalHostName([string]$HostName) {
    return $HostName -in @('127.0.0.1', 'localhost', '::1')
}

function Escape-CurlConfigValue([string]$Value) {
    if ($Value.Contains("`r") -or $Value.Contains("`n")) { throw '对象存储凭据不能包含换行符。' }
    return $Value.Replace('\', '\\').Replace('"', '\"')
}

function Ensure-ImageBucket([string]$Endpoint, [string]$Bucket, [string]$AccessKey,
        [string]$SecretKey, [string]$Region) {
    $curl = Get-Command 'curl.exe' -ErrorAction SilentlyContinue
    if (-not $curl) { throw '缺少 curl.exe，无法检查图片存储 bucket。' }
    $bucketUrl = $Endpoint.TrimEnd('/') + '/' + $Bucket
    $credential = (Escape-CurlConfigValue $AccessKey) + ':' + (Escape-CurlConfigValue $SecretKey)
    $safeUrl = Escape-CurlConfigValue $bucketUrl
    $safeSignature = Escape-CurlConfigValue "aws:amz:${Region}:s3"
    $common = @(
        'silent',
        'show-error',
        'fail',
        'output = "NUL"',
        "aws-sigv4 = `"$safeSignature`"",
        "user = `"$credential`"",
        "url = `"$safeUrl`""
    )
    (($common + 'head') -join [Environment]::NewLine) | & $curl.Source --config - 2>$null
    if ($LASTEXITCODE -eq 0) { return }
    (($common + 'request = "PUT"') -join [Environment]::NewLine) | & $curl.Source --config - 2>$null
    if ($LASTEXITCODE -ne 0) { throw "无法创建或访问图片存储 bucket：$Bucket" }
    Write-Host "图片存储 bucket 已准备：$Bucket"
}

function Start-LocalObjectStorage {
    if (-not (Get-Command 'docker' -ErrorAction SilentlyContinue)) {
        throw '图片存储未运行，且未找到 Docker。请安装并启动 Docker Desktop。'
    }
    & docker info --format '{{.ServerVersion}}' 2>$null | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Docker Desktop 引擎未运行，无法启动图片存储。' }

    $existing = (& docker ps -a --filter 'name=^/lue-erp-object-storage$' --format '{{.Names}}' 2>$null | Out-String).Trim()
    if ($existing -eq 'lue-erp-object-storage') {
        & docker start lue-erp-object-storage 2>$null | Out-Null
        if ($LASTEXITCODE -ne 0) { throw '无法启动现有图片存储容器。' }
        return
    }
    if (-not (Test-Path -LiteralPath $DeployEnvFile)) {
        throw '图片存储未运行，且未找到 deploy\.env。请先创建本机部署配置。'
    }
    & docker compose --env-file $DeployEnvFile -f $ComposeFile up -d minio minio-init
    if ($LASTEXITCODE -ne 0) { throw 'Docker 图片存储服务启动失败。' }
}

function Start-LocalRedis {
    if (-not (Get-Command 'docker' -ErrorAction SilentlyContinue)) {
        throw 'Redis 未运行，且未找到 Docker。请安装并启动 Docker Desktop。'
    }
    & docker info --format '{{.ServerVersion}}' 2>$null | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Docker Desktop 引擎未运行，无法启动 Redis。' }

    $existing = (& docker ps -a --filter 'name=^/lue-erp-redis$' --format '{{.Names}}' 2>$null | Out-String).Trim()
    if ($existing -eq 'lue-erp-redis') {
        & docker start lue-erp-redis 2>$null | Out-Null
        if ($LASTEXITCODE -ne 0) { throw '无法启动现有 Redis 容器。' }
        return
    }
    if (-not (Test-Path -LiteralPath $DeployEnvFile)) {
        throw 'Redis 未运行，且未找到 deploy\.env。请先创建本机部署配置。'
    }
    & docker compose --env-file $DeployEnvFile -f $ComposeFile up -d redis
    if ($LASTEXITCODE -ne 0) { throw 'Docker Redis 服务启动失败。' }
}

function Get-LanAddresses {
    try {
        return [System.Net.Dns]::GetHostAddresses([System.Net.Dns]::GetHostName()) |
            Where-Object { $_.AddressFamily -eq [System.Net.Sockets.AddressFamily]::InterNetwork -and
                -not [System.Net.IPAddress]::IsLoopback($_) -and -not $_.ToString().StartsWith('169.254.') } |
            ForEach-Object { $_.ToString() } | Sort-Object -Unique
    } catch { return @() }
}

try {
    Initialize-ToolPath
    Write-Host 'LUE ERP 一键启动' -ForegroundColor Cyan
    Write-Host "项目目录：$Root"

    foreach ($command in @('java', 'mvn', 'node', 'npm', 'curl.exe')) {
        if (-not (Get-Command $command -ErrorAction SilentlyContinue)) {
            throw "找不到 $command。启动器已刷新系统 PATH 并检查常见安装目录，请确认该工具仍在电脑上。"
        }
    }
    if (-not (Test-Path -LiteralPath $BackendEnvFile)) {
        throw '未找到 backend\.env。请先按 README.md 配置数据库连接；脚本不会修改或覆盖该文件。'
    }
    if (-not (Test-Path (Join-Path $Frontend 'node_modules\vite'))) {
        throw '未找到前端依赖。请先在 frontend 目录运行 npm install，然后重新启动。'
    }

    $backendEnv = Read-EnvFile $BackendEnvFile
    $databaseUrl = Get-Setting $backendEnv 'LUE_DB_URL' 'jdbc:mysql://127.0.0.1:3306/lue_erp'
    $database = Resolve-MySqlTarget $databaseUrl
    if (-not (Test-TcpPort $database.Host $database.Port)) {
        throw "MySQL 无法连接：$($database.Host):$($database.Port)。请先启动并配置数据库。"
    }
    Write-Host "MySQL 已连接：$($database.Host):$($database.Port)"

    $redisHost = Get-Setting $backendEnv 'LUE_REDIS_HOST' 'localhost'
    $redisPortText = Get-Setting $backendEnv 'LUE_REDIS_PORT' '6379'
    $redisPort = 0
    if (-not [int]::TryParse($redisPortText, [ref]$redisPort) -or $redisPort -lt 1 -or $redisPort -gt 65535) {
        throw 'backend\.env 中的 LUE_REDIS_PORT 不是有效端口。'
    }
    if (-not (Test-TcpPort $redisHost $redisPort)) {
        if (-not (Test-LocalHostName $redisHost)) { throw "远程 Redis 无法连接：${redisHost}:$redisPort" }
        Write-Host '本机 Redis 未运行，正在通过 Docker 启动……'
        Start-LocalRedis
        $redisDeadline = (Get-Date).AddSeconds(60)
        while ((Get-Date) -lt $redisDeadline -and -not (Test-TcpPort $redisHost $redisPort)) {
            Start-Sleep -Seconds 2
        }
        if (-not (Test-TcpPort $redisHost $redisPort)) { throw "Redis 启动后仍无法连接：${redisHost}:$redisPort" }
    }
    Write-Host "Redis 已连接：${redisHost}:$redisPort"

    $storageType = (Get-Setting $backendEnv 'LUE_IMAGE_STORAGE_TYPE' 'none').ToLowerInvariant()
    if ($storageType -eq 'minio') {
        $storageEndpoint = Get-Setting $backendEnv 'LUE_MINIO_ENDPOINT' 'http://127.0.0.1:9000'
        $storageBucket = Get-Setting $backendEnv 'LUE_MINIO_BUCKET' 'lue-erp-images'
        $storageAccessKey = Get-Setting $backendEnv 'LUE_MINIO_ACCESS_KEY'
        $storageSecretKey = Get-Setting $backendEnv 'LUE_MINIO_SECRET_KEY'
        $storageRegion = Get-Setting $backendEnv 'LUE_MINIO_REGION' 'us-east-1'
        if (-not $storageAccessKey -or -not $storageSecretKey) { throw '图片存储已启用，但访问凭据未配置。' }
        try { $storageUri = [Uri]$storageEndpoint } catch { throw 'LUE_MINIO_ENDPOINT 不是有效地址。' }
        if (-not $storageUri.IsAbsoluteUri -or $storageUri.Scheme -notin @('http', 'https') -or -not $storageUri.Host) {
            throw 'LUE_MINIO_ENDPOINT 必须是完整的 HTTP 或 HTTPS 地址。'
        }
        $storagePort = if ($storageUri.IsDefaultPort) { if ($storageUri.Scheme -eq 'https') { 443 } else { 80 } } else { $storageUri.Port }
        if (-not (Test-TcpPort $storageUri.Host $storagePort)) {
            if (-not (Test-LocalHostName $storageUri.Host)) {
                throw "远程图片存储无法连接：$($storageUri.Host):$storagePort"
            }
            Write-Host '本机图片存储未运行，正在启动……'
            Start-LocalObjectStorage
        }
        $storageHealthUrls = @(
            ($storageEndpoint.TrimEnd('/') + '/health/ready')
            ($storageEndpoint.TrimEnd('/') + '/minio/health/ready')
        )
        Write-Host '等待图片存储完成初始化……'
        if (-not (Wait-AnyHttp $storageHealthUrls 240 ($storageUri.Scheme -eq 'https'))) {
            throw "图片存储未通过健康检查：$($storageHealthUrls -join ' 或 ')"
        }
        Ensure-ImageBucket $storageEndpoint $storageBucket $storageAccessKey $storageSecretKey $storageRegion
        Write-Host "图片存储已就绪：$storageEndpoint"
    } elseif ($storageType -ne 'none') {
        throw "不支持的图片存储类型：$storageType"
    } else {
        Write-Warning '图片存储未启用，文件上传功能不可用。'
    }

    $mavenRepo = Join-Path $Root '.m2\repository'
    $backendCommand = if (Test-Path -LiteralPath $mavenRepo) {
        'mvn --offline -Dmaven.repo.local="' + $mavenRepo + '" spring-boot:run'
    } else { 'mvn --offline spring-boot:run' }

    $backendUrl = 'http://localhost:8080/actuator/health'
    $frontendCommand = 'npm run dev'
    $frontendUrl = 'http://localhost:5173/'
    $backendState = Join-Path $RuntimeDir 'backend.json'
    $frontendState = Join-Path $RuntimeDir 'frontend.json'

    if (-not (Test-TcpPort '127.0.0.1' 8080)) {
        Start-ServiceProcess 'backend' $Backend $backendCommand $backendState
    } elseif (-not (Test-Http $backendUrl)) {
        throw '8080 端口已被占用，但没有检测到 LUE ERP 后端健康检查。请关闭占用该端口的程序后重试。'
    } else { Write-Host '后端已在运行，跳过启动。' }

    Write-Host '等待后端健康检查……'
    if (-not (Wait-Http $backendUrl 120)) {
        $lastError = Get-ChildItem -LiteralPath $LogDir -Filter 'backend-*-error.log' |
            Sort-Object LastWriteTime -Descending | Select-Object -First 1
        $hint = if ($lastError) { " 后端错误日志：$($lastError.FullName)" } else { '' }
        throw "后端在 2 分钟内未通过健康检查。请查看日志并确认 MySQL、Redis 和后端配置。$hint"
    }

    if (-not (Test-TcpPort '127.0.0.1' 5173)) {
        Start-ServiceProcess 'frontend' $Frontend $frontendCommand $frontendState
    } elseif (-not (Test-Http $frontendUrl)) {
        throw '5173 端口已被占用，但前端页面没有响应。请关闭占用该端口的程序后重试。'
    } else { Write-Host '前端已在运行，跳过启动。' }

    if (-not (Wait-Http $frontendUrl 45)) {
        throw '前端在 45 秒内没有响应，请查看 logs 目录中的 frontend 日志。'
    }

    Write-Host "系统已就绪：$frontendUrl" -ForegroundColor Green
    foreach ($address in Get-LanAddresses) {
        Write-Host "局域网访问：http://${address}:5173/" -ForegroundColor Green
    }
    try { Start-Process $frontendUrl } catch { Write-Warning "无法自动打开浏览器：$($_.Exception.Message)" }
    Write-Host "启动完成。需要停止系统时，双击根目录中的 一键停止ERP.bat。日志保存在：$LogDir" -ForegroundColor Cyan
    exit 0
} catch {
    Write-Host "启动失败：$($_.Exception.Message)" -ForegroundColor Red
    if ($_.ScriptStackTrace) { Write-Host $_.ScriptStackTrace -ForegroundColor DarkRed }
    if ($StartedThisRun.Count -gt 0) {
        Write-Host '正在清理本次启动的前后端进程……' -ForegroundColor Yellow
        for ($index = $StartedThisRun.Count - 1; $index -ge 0; $index--) {
            Stop-StartedService $StartedThisRun[$index]
        }
    }
    exit 1
}
