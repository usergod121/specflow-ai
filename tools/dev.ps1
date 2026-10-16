# dev.bat 的真身：改界面时用（Vite 热更新 + 后端引擎）。
#
# 平时不用它——start.bat 起的那一个进程里已经带着前端产物了。
# 只有「要改前端、想要保存即刷新」时才用这个：
#   dev.bat              起引擎 + Vite dev server（Vite 把 /api 代理给引擎）
#   dev.bat -Port 8790   换端口
# Ctrl+C 只停 Vite；引擎还在跑，收它用 stop.bat。

param(
    [string]$Project = '',
    [int]$Port = 0
)

. "$PSScriptRoot\_common.ps1"

$root = Get-RepoRoot
$jar = Join-Path $root 'target\specflow.jar'
$frontend = Join-Path $root 'src\main\frontend'

Write-Host ''
Write-Host '  specflow' -ForegroundColor White -NoNewline
Write-Host '  开发模式（前端热更新）' -ForegroundColor DarkGray
Write-Host '  ---------------------------------------------' -ForegroundColor DarkGray

$npm = Get-Command npm.cmd -ErrorAction SilentlyContinue
if (-not $npm) { $npm = Get-Command npm -ErrorAction SilentlyContinue }
if (-not $npm) {
    Write-Bad '没找到 npm：装 Node.js 之后再来（只要 Node 20+ 就够）'
    exit 1
}

if (-not (Test-Path -LiteralPath $jar)) {
    Write-Bad '还没有 target\specflow.jar：先双击一次 start.bat（它会替你编一份），再回来。'
    exit 1
}

$java = Find-Java
if (-not $java) {
    Write-Bad '没找到 Java 17 或更高版本：装一个 JDK 17（或设好 JAVA_HOME）。'
    exit 1
}

if (-not (Test-Path -LiteralPath (Join-Path $frontend 'node_modules'))) {
    Write-Step '装前端依赖（第一次要一会儿）'
    $install = Invoke-Native -File $npm.Source -Arguments @('install') -WorkDir $frontend
    if ($install.code -ne 0) {
        Write-Bad 'npm install 没成功：'
        $install.output -split "`r?`n" | Select-Object -Last 20 | ForEach-Object { Write-Host "     $_" -ForegroundColor DarkGray }
        exit 1
    }
    Write-Ok '依赖装好了'
}

# 引擎先起来（同一套：挑端口 → 后台拉起 → 等健康 → 记状态）。Vite 要把 /api 代理到它。
# 这里调的是函数、而不是 `& start.ps1`：被调脚本里的 `exit` 会结束整个会话，
# 那样 Vite 一行都跑不到——这个 bug 是实测 dev.bat 时撞出来的。
Write-Step '起引擎'
$project = Resolve-Project -Given $Project
Save-Project -Project $project
$engine = Start-Engine -Java $java -Jar $jar -Project $project -Port $Port
if (-not $engine.ok) {
    Write-Bad $engine.message
    exit 1
}
Write-Ok "引擎在 $($engine.url)（pid $($engine.pid)），项目 $project"

Write-Step '起 Vite（改前端保存即生效）'
Write-Host ''
$env:SPECFLOW_PORT = "$($engine.port)"
Push-Location $frontend
try {
    & $npm.Source run dev
} finally {
    Pop-Location
    Write-Host ''
    Write-Note "Vite 停了；引擎还在后台跑（$($engine.url)），要收它双击 stop.bat"
}
