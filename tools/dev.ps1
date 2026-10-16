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

# 先把引擎起起来（复用 start 那套：挑端口、等健康、记状态）。Vite 要代理到它。
Write-Step '起引擎'
$startArgs = @{ NoBrowser = $true }
if ($Project) { $startArgs.Project = $Project }
if ($Port -gt 0) { $startArgs.Port = $Port }
& "$PSScriptRoot\start.ps1" @startArgs
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$state = Read-EngineState
if (-not $state) { Write-Bad '引擎状态读不出来，先单独跑一次 start.bat 看看'; exit 1 }

Write-Ok "引擎在 $($state.port)，Vite 会把 /api 代理过去"
Write-Step '起 Vite（改前端保存即生效）'
Write-Host ''
$env:SPECFLOW_PORT = "$($state.port)"
Push-Location $frontend
try {
    & $npm.Source run dev
} finally {
    Pop-Location
    Write-Host ''
    Write-Note "Vite 停了；引擎还在后台跑（端口 $($state.port)），要收它双击 stop.bat"
}
