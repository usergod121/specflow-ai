# specflow 启动脚本共用的一层：找 java、挑端口、等健康、记状态。
#
# 只用 Windows PowerShell 5.1 就有的东西（这台机器上没有 pwsh 7），
# 所以别在这里写 `??`、三元、-Parallel 这类 7 才有的语法。
# 这个文件本身是 UTF-8 带 BOM 的——5.1 读无 BOM 的 UTF-8 会按 ANSI 解，中文会乱。

# 这一层**故意不设** $ErrorActionPreference='Stop'：
# Windows PowerShell 5.1 里，原生命令（java / docker / mvn / taskkill）往 stderr 写一个字
# 就会被 Stop 当成终止错误——而 `java -version` 恰恰是往 stderr 打的，
# 于是脚本会在第一步就死掉，屏幕上只留一句 NativeCommandError。
# 所以：原生命令统一走 Invoke-Native（自己看退出码），cmdlet 要终止的地方各自写 -ErrorAction Stop。
$ErrorActionPreference = 'Continue'

# 编码必须在这里钉死，不然中文会乱。
# 起因是实测看到的：捕获 java 的输出时中文变成 `��Ŀ��`——Java 18 以后 stdout 默认按
# 「原生编码」写字节，而 PowerShell 那边按另一个编码去解。两处都钉成 UTF-8：
# 下面那两个 -D 让 JVM 写 UTF-8，这一行让 PowerShell 按 UTF-8 解。
try {
    [Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)
} catch {
    # 没有控制台时（被管道接走）设不了，不影响
}

# 所有 java 调用都带上它：输出编码不再取决于这台机器的代码页。
# 做成函数而不是变量：这个文件是被点源进来的，函数在调用方那边一定看得见。
function Get-EngineEncoding { return @('-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8') }

# 跑一个原生命令，把「输出 + 退出码」一起拿回来（原样，不做总结）。
function Invoke-Native {
    param(
        [string]$File,
        [string[]]$Arguments = @(),
        [string]$WorkDir = ''
    )
    $output = ''
    $code = 0
    if ($WorkDir) { Push-Location -LiteralPath $WorkDir }
    try {
        $output = (& $File @Arguments 2>&1 | Out-String)
        $code = $LASTEXITCODE
    } catch {
        $output = "$output$($_.Exception.Message)"
        $code = -1
    } finally {
        if ($WorkDir) { Pop-Location }
    }
    return @{ code = $code; output = $output }
}

function Write-Step { param([string]$Text) Write-Host "-> $Text" -ForegroundColor Cyan }
function Write-Ok   { param([string]$Text) Write-Host "   OK  $Text" -ForegroundColor Green }
function Write-Note { param([string]$Text) Write-Host "   ..  $Text" -ForegroundColor DarkGray }
function Write-Warn2{ param([string]$Text) Write-Host "   !   $Text" -ForegroundColor Yellow }
function Write-Bad  { param([string]$Text) Write-Host "   X   $Text" -ForegroundColor Red }

function Get-RepoRoot { return (Split-Path -Parent $PSScriptRoot) }

function Get-RunDir {
    $dir = Join-Path (Get-RepoRoot) '.run'
    if (-not (Test-Path -LiteralPath $dir)) {
        New-Item -ItemType Directory -Path $dir -Force | Out-Null
    }
    return $dir
}

function Get-StateFile { return (Join-Path (Get-RunDir) 'engine.json') }
function Get-ProjectFile { return (Join-Path (Get-RunDir) 'last-project.txt') }
function Get-OutLog { return (Join-Path (Get-RunDir) 'engine.out.log') }
function Get-ErrLog { return (Join-Path (Get-RunDir) 'engine.err.log') }

function Read-EngineState {
    $file = Get-StateFile
    if (-not (Test-Path -LiteralPath $file)) { return $null }
    try {
        return (Get-Content -LiteralPath $file -Raw -Encoding UTF8 | ConvertFrom-Json)
    } catch {
        return $null
    }
}

function Save-EngineState {
    param([int]$ProcessId, [int]$Port, [string]$Project, [string]$Java)
    $state = [ordered]@{
        pid       = $ProcessId
        port      = $Port
        project   = $Project
        java      = $Java
        startedAt = (Get-Date).ToString('yyyy-MM-dd HH:mm:ss')
    }
    $state | ConvertTo-Json | Set-Content -LiteralPath (Get-StateFile) -Encoding UTF8
}

function Remove-EngineState {
    $file = Get-StateFile
    if (Test-Path -LiteralPath $file) { Remove-Item -LiteralPath $file -Force }
}

function Test-ProcessAlive {
    param([int]$ProcessId)
    if ($ProcessId -le 0) { return $false }
    return [bool](Get-Process -Id $ProcessId -ErrorAction SilentlyContinue)
}

# 挑 java：JAVA_HOME 优先，其次 PATH；两个都不到 17 就报 null（版本错的表现很难猜，宁可现在就拦住）。
function Find-Java {
    $candidates = @()
    if ($env:JAVA_HOME) { $candidates += (Join-Path $env:JAVA_HOME 'bin\java.exe') }
    $onPath = Get-Command java -ErrorAction SilentlyContinue
    if ($onPath) { $candidates += $onPath.Source }
    foreach ($one in $candidates) {
        if (-not (Test-Path -LiteralPath $one)) { continue }
        $probe = Invoke-Native -File $one -Arguments @('-version')
        if ($probe.output -match 'version "(\d+)') {
            if ([int]$Matches[1] -ge 17) { return $one }
        }
    }
    return $null
}

function Test-PortFree {
    param([int]$Port)
    $listener = $null
    try {
        $listener = New-Object System.Net.Sockets.TcpListener -ArgumentList ([System.Net.IPAddress]::Loopback), $Port
        $listener.Start()
        return $true
    } catch {
        return $false
    } finally {
        if ($listener) { $listener.Stop() }
    }
}

# 想要的端口被占就往后找一个空的（并如实说换了哪个）——静默换端口会让人对着旧地址发呆。
function Get-FreePort {
    param([int]$Wanted, [int]$Tries = 40)
    for ($i = 0; $i -lt $Tries; $i++) {
        $candidate = $Wanted + $i
        if (Test-PortFree -Port $candidate) {
            return @{ port = $candidate; shifted = ($i -gt 0) }
        }
    }
    return @{ port = 0; shifted = $true }
}

function Wait-Health {
    param([int]$Port, [int]$Seconds = 90)
    $url = "http://127.0.0.1:$Port/api/actuator/health"
    $deadline = (Get-Date).AddSeconds($Seconds)
    while ((Get-Date) -lt $deadline) {
        try {
            $health = Invoke-RestMethod -Uri $url -TimeoutSec 3
            if ($health.status -eq 'UP') { return $true }
        } catch {
            # 还没起来：接着等
        }
        Start-Sleep -Milliseconds 600
    }
    return $false
}

# 起引擎：挑端口 → 后台拉起 → 等它健康 → 记状态。
#
# 为什么抽成函数、而不是让 dev.ps1 去 `& start.ps1`：PowerShell 里脚本用 `&` 互相调用时，
# 被调脚本里的 `exit` 结束的是**整个会话**——实测 dev.bat 走到那一步就没了，
# Vite 一行都没跑。抽成函数之后两边各叫各的，谁也不用去动别人的退出码。
#
# @return @{ ok; message; port; pid; url; wanted; shifted; outLog; errLog }
function Start-Engine {
    param(
        [string]$Java,
        [string]$Jar,
        [string]$Project,
        [int]$Port = 0
    )
    $wanted = if ($Port -gt 0) { $Port } else { 8780 }
    if ($wanted -eq 8770 -and -not (Test-PortFree -Port 8770)) {
        return @{ ok = $false; message = '8770 是你原来那份 specflow 实例的端口，别用这个。去掉 -Port，或者换一个（比如 8780）。' }
    }
    $pick = Get-FreePort -Wanted $wanted
    if ($pick.port -eq 0) {
        return @{ ok = $false; message = "从 $wanted 往后找了 40 个端口都被占了，没法起。" }
    }
    $port = $pick.port
    $outLog = Get-OutLog
    $errLog = Get-ErrLog
    Remove-Item -LiteralPath $outLog, $errLog -Force -ErrorAction SilentlyContinue

    # Start-Process 是把参数拼成一条命令行交给系统，带空格的路径要自己加引号
    $engineArgs = (Get-EngineEncoding) + @('-jar', $Jar, 'web', '--port', "$port", '--no-open', '-p', $Project)
    $quoted = ($engineArgs | ForEach-Object { if ("$_" -match '\s') { '"' + $_ + '"' } else { "$_" } }) -join ' '

    $process = Start-Process -FilePath $Java -ArgumentList $quoted -PassThru -WindowStyle Hidden `
        -RedirectStandardOutput $outLog -RedirectStandardError $errLog

    if (Wait-Health -Port $port -Seconds 90) {
        Save-EngineState -ProcessId $process.Id -Port $port -Project $Project -Java $Java
        return @{
            ok = $true; port = $port; pid = $process.Id; url = "http://127.0.0.1:$port"
            wanted = $wanted; shifted = $pick.shifted; outLog = $outLog; errLog = $errLog
        }
    }

    Stop-EngineTree -ProcessId $process.Id | Out-Null
    Remove-EngineState
    $tail = ''
    if (Test-Path -LiteralPath $outLog) { $tail += (Get-Content -LiteralPath $outLog -Tail 20 -Encoding UTF8 | Out-String) }
    if (Test-Path -LiteralPath $errLog) { $tail += (Get-Content -LiteralPath $errLog -Tail 20 -Encoding UTF8 | Out-String) }
    return @{ ok = $false; message = "引擎 90 秒没起来（或起完立刻退了）。原始输出：`n$tail" }
}

# 先好好说（taskkill 不带 /F），不成再动手。JVM 起来之后要留一点时间收尾。
function Stop-EngineTree {
    param([int]$ProcessId)
    if (-not (Test-ProcessAlive -ProcessId $ProcessId)) { return $true }
    Invoke-Native -File 'taskkill' -Arguments @('/PID', "$ProcessId", '/T') | Out-Null
    for ($i = 0; $i -lt 12; $i++) {
        Start-Sleep -Milliseconds 250
        if (-not (Test-ProcessAlive -ProcessId $ProcessId)) { return $true }
    }
    Invoke-Native -File 'taskkill' -Arguments @('/PID', "$ProcessId", '/T', '/F') | Out-Null
    Start-Sleep -Milliseconds 600
    return (-not (Test-ProcessAlive -ProcessId $ProcessId))
}

# 按「这个仓库的 jar 全路径」找引擎进程。
# 只认全路径，不认文件名：这台机器上还跑着另一份 specflow（E:\specflow-ai），
# 按文件名匹配会把它一起杀掉——那是用户自己开着的实例。
function Find-EngineProcesses {
    $jar = Join-Path (Get-RepoRoot) 'target\specflow.jar'
    $found = @()
    $all = Get-CimInstance Win32_Process -Filter "Name = 'java.exe' OR Name = 'javaw.exe'" -ErrorAction SilentlyContinue
    foreach ($one in $all) {
        $cmd = [string]$one.CommandLine
        if (-not $cmd) { continue }
        if ($cmd -like '*specflow-ai*') { continue }
        if ($cmd -like "*$jar*") { $found += $one }
    }
    return $found
}

function Resolve-Project {
    param([string]$Given)
    if ($Given) {
        if (-not (Test-Path -LiteralPath $Given)) {
            throw "没有这个目录：$Given"
        }
        return (Resolve-Path -LiteralPath $Given).Path
    }
    $saved = ''
    $file = Get-ProjectFile
    if (Test-Path -LiteralPath $file) { $saved = (Get-Content -LiteralPath $file -Raw).Trim() }
    if ($saved -and (Test-Path -LiteralPath $saved)) { return $saved }
    $fixture = Join-Path (Get-RepoRoot) 'fixture'
    if (Test-Path -LiteralPath $fixture) { return $fixture }
    return (Get-Location).Path
}

function Save-Project {
    param([string]$Project)
    Set-Content -LiteralPath (Get-ProjectFile) -Value $Project -Encoding UTF8
}

# 密钥从哪儿来（按优先级），取到就写进项目的 local.env：
#   1. start.bat -ApiKey sk-...
#   2. 环境变量 SPECFLOW_API_KEY
#   3. 项目自己的 .specflow\local.env
#   4. 这台机器上别的 specflow 用过的那几份（同盘兄弟目录 / 用户目录）
# 密钥只写进项目的 local.env（那个文件不进版本库）；脚本不回显、不打印它。
function Resolve-ApiKey {
    param([string]$Project, [string]$Given)

    $localEnv = Join-Path $Project '.specflow\local.env'
    $result = @{ source = 'missing'; key = '' }

    $readFrom = {
        param([string]$File)
        if (-not (Test-Path -LiteralPath $File)) { return '' }
        foreach ($row in (Get-Content -LiteralPath $File -ErrorAction SilentlyContinue)) {
            $line = $row.Trim()
            if ($line -match '^SPECFLOW_API_KEY\s*=\s*(sk-\S+)$') { return $Matches[1] }
        }
        return ''
    }

    if ($Given) { $result.key = $Given; $result.source = 'command-line' }
    elseif ($env:SPECFLOW_API_KEY) { $result.key = $env:SPECFLOW_API_KEY.Trim(); $result.source = 'env' }
    else {
        $own = & $readFrom $localEnv
        if ($own) { $result.key = $own; $result.source = 'project' }
    }

    if (-not $result.key) {
        $elsewhere = @()
        $parent = Split-Path -Parent (Get-RepoRoot)
        if ($parent -and (Test-Path -LiteralPath $parent)) {
            foreach ($dir in (Get-ChildItem -LiteralPath $parent -Directory -ErrorAction SilentlyContinue)) {
                if ($dir.Name -like '*specflow*') {
                    $elsewhere += (Join-Path $dir.FullName '.specflow\local.env')
                }
            }
        }
        if ($env:USERPROFILE) { $elsewhere += (Join-Path $env:USERPROFILE '.specflow\local.env') }
        foreach ($file in $elsewhere) {
            $found = & $readFrom $file
            if ($found) {
                $result.key = $found
                $result.source = $file
                break
            }
        }
    }

    if ($result.key -and $result.source -ne 'project' -and $result.source -ne 'env') {
        # 写进项目自己那份（留着原有内容，只补/换那一行）
        $lines = @()
        if (Test-Path -LiteralPath $localEnv) {
            $lines = @(Get-Content -LiteralPath $localEnv -ErrorAction SilentlyContinue |
                Where-Object { $_ -notmatch '^\s*SPECFLOW_API_KEY\s*=' })
        } else {
            $dir = Split-Path -Parent $localEnv
            if (-not (Test-Path -LiteralPath $dir)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }
            $lines = @('# 密钥只放在这里（这个文件不进版本库、不回显、不进副本）')
        }
        $lines += "SPECFLOW_API_KEY=$($result.key)"
        Set-Content -LiteralPath $localEnv -Value $lines -Encoding UTF8
    }

    return $result
}

function Show-ProjectState {
    param([string]$Project)
    $specflow = Join-Path $Project '.specflow'
    if (Test-Path -LiteralPath $specflow) {
        Write-Note "项目配置：$specflow"
    } else {
        Write-Warn2 "这个项目还没有 .specflow（引擎会自己铺一份最小配置）"
    }
}
