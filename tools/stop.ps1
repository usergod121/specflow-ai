# stop.bat 的真身：停引擎 + 收干净这台机器上属于这个项目的东西。
#
# 双击 stop.bat 就够了。参数（都是可选的）：
#   -Project <目录>   哪个项目（默认：上次 start 用的那个）
#   -Purge            连流程数据（.specflow\flow）一起删——会先问一遍
#   -Yes              不问了，直接删（脚本里连着跑时用）

param(
    [string]$Project = '',
    [switch]$Purge,
    [switch]$Yes
)

. "$PSScriptRoot\_common.ps1"

$root = Get-RepoRoot
$jar = Join-Path $root 'target\specflow.jar'
$java = Find-Java
$did = 0

Write-Host ''
Write-Host '  specflow' -ForegroundColor White -NoNewline
Write-Host '  收场' -ForegroundColor DarkGray
Write-Host '  ---------------------------------------------' -ForegroundColor DarkGray

# ---------- 1. 停引擎 ----------
Write-Step '停引擎'
$stopped = $false
$state = Read-EngineState
if ($state -and (Test-ProcessAlive -ProcessId $state.pid)) {
    if (Stop-EngineTree -ProcessId $state.pid) {
        Write-Ok "已停（pid $($state.pid)，端口 $($state.port)）"
        $stopped = $true
    } else {
        Write-Bad "pid $($state.pid) 停不掉（可能有权限问题）：任务管理器里手工结束它"
    }
    if (-not $Project) { $Project = $state.project }
}
# 状态文件可能已经不在了（引擎窗口被关过、或换过一次目录）：按 jar 全路径再扫一遍
foreach ($one in (Find-EngineProcesses)) {
    if ($state -and $one.ProcessId -eq $state.pid) { continue }
    if (Stop-EngineTree -ProcessId $one.ProcessId) {
        Write-Ok "还有一个引擎在跑（pid $($one.ProcessId)），也停了"
        $stopped = $true
    }
}
if (-not $stopped) { Write-Note '没有引擎在跑' }
Remove-EngineState

# ---------- 2. 收 docker ----------
$project = Resolve-Project -Given $Project

if ($Purge -and -not $Yes) {
    Write-Host ''
    Write-Host "  要删的是：$project\.specflow\flow" -ForegroundColor Yellow
    Write-Host '  （五节点的状态、事件流、用例、快照都在里面；project.yaml / env.yaml 留着）' -ForegroundColor DarkGray
    $answer = Read-Host '  确定连流程数据一起删？输入 yes 继续，别的都算不删'
    if ($answer -ne 'yes') {
        $Purge = $false
        Write-Note '好，只收环境，数据留着'
    }
}

Write-Step "收 Docker（项目：$project）"
if (-not (Test-Path -LiteralPath $jar)) {
    Write-Warn2 '没有 target\specflow.jar：收不了容器（先跑一次 start.bat，或者 mvn -o package）'
} elseif (-not $java) {
    Write-Warn2 '没找到 Java 17+：收不了容器'
} else {
    $envArgs = (Get-EngineEncoding) + @('-jar', $jar, 'env', 'down', '-p', $project)
    if ($Purge) { $envArgs += '--purge' }
    $swept = Invoke-Native -File $java -Arguments $envArgs
    foreach ($row in ($swept.output -split "`r?`n")) {
        if ($row.Trim()) { Write-Host "   $($row.TrimEnd())" }
    }
    if ($swept.code -ne 0) {
        Write-Warn2 "收环境没全成功（退出码 $($swept.code)）：上面的原文说了卡在哪一步"
    }
}

# ---------- 3. 说清楚留下了什么 ----------
Write-Host ''
Write-Host '  ---------------------------------------------' -ForegroundColor DarkGray
if ($project -and (Test-Path -LiteralPath (Join-Path $project '.specflow\flow'))) {
    Write-Host "  留着   $project\.specflow\flow（下次开还是这一条流程）" -ForegroundColor DarkGray
    Write-Host '         连它一起删：stop.bat -Purge' -ForegroundColor DarkGray
}
if ($env:USERPROFILE) {
    Write-Host "  日志   $env:USERPROFILE\.specflow\logs\engine.log" -ForegroundColor DarkGray
}
Write-Host '  再开   双击 start.bat' -ForegroundColor White
Write-Host '  ---------------------------------------------' -ForegroundColor DarkGray
Write-Host ''
