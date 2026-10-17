# start.bat 的真身：把引擎拉起来，等它健康，再开浏览器。
#
# 双击 start.bat 就够了。参数（都是可选的）：
#   -Project <目录>   打开哪个项目（默认：上次那个，其次 fixture）
#   -Port <端口>      用哪个端口（默认 8780；被占就往后找）
#   -ApiKey sk-...    密钥（默认从项目 local.env / 环境变量 / 这台机器上别的 specflow 里找）
#   -NoBrowser        不自动开浏览器
#   -Rebuild          先 mvn package 再起（改了 Java 代码时用）
#   -Foreground       引擎日志直接打在这个窗口里（调试用；Ctrl+C 就停）

param(
    [string]$Project = '',
    [int]$Port = 0,
    [string]$ApiKey = '',
    [switch]$NoBrowser,
    [switch]$Rebuild,
    [switch]$Foreground
)

. "$PSScriptRoot\_common.ps1"

$root = Get-RepoRoot
$jar = Join-Path $root 'target\specflow.jar'

Write-Host ''
Write-Host '  specflow' -ForegroundColor White -NoNewline
Write-Host '  五节点规格驱动引擎' -ForegroundColor DarkGray
Write-Host '  ---------------------------------------------' -ForegroundColor DarkGray

# ---------- 1. java ----------
Write-Step '找一个 Java 17+'
$java = Find-Java
if (-not $java) {
    Write-Bad '没找到 Java 17 或更高版本。装一个 JDK 17（或设好 JAVA_HOME），再双击一次。'
    exit 1
}
Write-Ok $java

# ---------- 2. 产物 ----------
if ($Rebuild -or -not (Test-Path -LiteralPath $jar)) {
    Write-Step '编前端 + Java（第一次会慢，要几分钟）'
    $mvn = Get-Command mvn.cmd -ErrorAction SilentlyContinue
    if (-not $mvn) { $mvn = Get-Command mvn -ErrorAction SilentlyContinue }
    if (-not $mvn) {
        Write-Bad '没找到 maven：装一个（或把 mvn 加进 PATH），或者手工 `mvn -o package` 出 target\specflow.jar 再双击。'
        exit 1
    }

    # 前端依赖：package 那一步会跑 `npm run build`，没有 node_modules 就直接失败。
    # 与其让它报一堆模块找不到，不如在这里一次说清。
    $front = Join-Path $root 'src\main\frontend'
    if (-not (Test-Path -LiteralPath (Join-Path $front 'node_modules'))) {
        $npm = Get-Command npm.cmd -ErrorAction SilentlyContinue
        if (-not $npm) { $npm = Get-Command npm -ErrorAction SilentlyContinue }
        if (-not $npm) {
            Write-Bad '前端依赖没装，而这里也没有 npm：装 Node 20+ 之后重试（或者把 src\main\frontend\node_modules 放回来）。'
            exit 1
        }
        Write-Step '装前端依赖（npm install，第一次要一会儿）'
        $install = Invoke-Native -File $npm.Source -Arguments @('install', '--no-audit', '--no-fund') -WorkDir $front
        if ($install.code -ne 0) {
            Write-Bad "npm install 没成功（退出码 $($install.code)）。最后几行："
            $install.output -split "`r?`n" | Select-Object -Last 20 | ForEach-Object { Write-Host "     $_" -ForegroundColor DarkGray }
            exit 1
        }
    }

    Push-Location $root
    try {
        # Maven 在中文 Windows 上按平台编码打日志，失败尾巴得能看懂
        $env:MAVEN_OPTS = '-Dfile.encoding=UTF-8'
        # clean 是必须的：target\classes\static 不会自己清，重打几次就会把旧的前端包一起塞进 jar，
        # 于是「我明明改好了」而浏览器拿到的还是上一版（这一类"验了旧包"真发生过）。
        $build = Invoke-Native -File $mvn.Source -Arguments @('-o', '-q', 'clean', 'package', '-DskipTests') -WorkDir $root
    } finally {
        Pop-Location
    }
    if (-not (Test-Path -LiteralPath $jar)) {
        Write-Bad "打包没成功（退出码 $($build.code)）。最后几行："
        $build.output -split "`r?`n" | Select-Object -Last 25 | ForEach-Object { Write-Host "     $_" -ForegroundColor DarkGray }
        exit 1
    }

    # **校验：界面真的打进 jar 了吗**。前端构建失败时 Maven 仍可能打出一个没有界面的包，
    # 双击进去是一片白——这种"看着成功其实空的"必须在这里拦住。
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $entries = @()
    $zip = [System.IO.Compression.ZipFile]::OpenRead($jar)
    try { $entries = $zip.Entries | ForEach-Object { $_.FullName } } finally { $zip.Dispose() }
    $hasIndex = @($entries | Where-Object { $_ -like '*static/index.html' }).Count -gt 0
    $bundles = @($entries | Where-Object { $_ -like '*static/assets/*.js' }).Count
    if (-not ($hasIndex -and $bundles -gt 0)) {
        Write-Bad "包里的界面是空的（index.html=$hasIndex，js 包 $bundles 个）：前端构建没成功。"
        Write-Note '先在 src\main\frontend 里手工 `npm run build` 看报错，再重试。'
        exit 1
    }
    Write-Ok "产物好了：target\specflow.jar（界面 $bundles 个资源，已 clean 重打）"
} else {
    $stamp = (Get-Item -LiteralPath $jar).LastWriteTime.ToString('MM-dd HH:mm')
    Write-Note "用现成的 target\specflow.jar（$stamp；改了 Java 或界面就加 -Rebuild）"
}

# ---------- 3. docker（只是提醒） ----------
Write-Step '看一眼 Docker'
$docker = Invoke-Native -File 'docker' -Arguments @('info', '--format', '{{.ServerVersion}}')
if ($docker.code -eq 0) {
    Write-Ok "Docker 在跑（$($docker.output.Trim())）"
} else {
    Write-Warn2 'Docker 没在跑：界面能开、能改代码，但 ① 起环境那一步会失败。要用就先开 Docker Desktop。'
}

# ---------- 4. 项目 ----------
Write-Step '挑一个项目'
$project = Resolve-Project -Given $Project
Save-Project -Project $project
Write-Ok $project
Show-ProjectState -Project $project

# 还没 .specflow 就铺一份（铺的是引擎自己那套最小配置；脚本不自己拼 YAML——免得两处各说一套）
if (-not (Test-Path -LiteralPath (Join-Path $project '.specflow'))) {
    Write-Step '这个项目还没有 .specflow：铺一份最小配置'
    $prepared = Invoke-Native -File $java -Arguments ((Get-EngineEncoding) + @('-jar', $jar, 'env', 'prepare', '-p', $project))
    if ($prepared.code -ne 0) {
        Write-Warn2 "铺配置没成功：$($prepared.output.Trim())"
    } else {
        Write-Ok $prepared.output.Trim()
    }
}

# ---------- 5. 密钥 ----------
Write-Step '看密钥'
$key = Resolve-ApiKey -Project $project -Given $ApiKey
if ($key.key) {
    switch ($key.source) {
        'env' { Write-Ok '用环境变量里的 SPECFLOW_API_KEY' }
        'project' { Write-Ok '用项目 .specflow\local.env 里的' }
        'command-line' { Write-Ok '用 -ApiKey 给的那一个（已写进项目 local.env）' }
        default { Write-Ok "从 $($key.source) 找到一份，已写进项目 local.env" }
    }
} else {
    Write-Warn2 '没找到密钥：界面能开，但点「跑」会提示缺密钥。两条路——'
    Write-Host '        1) 双击 start.bat 时带上：start.bat -ApiKey sk-xxxx' -ForegroundColor DarkGray
    Write-Host "        2) 往这个文件里加一行：$project\.specflow\local.env" -ForegroundColor DarkGray
    Write-Host '           SPECFLOW_API_KEY=sk-xxxx' -ForegroundColor DarkGray
}

# ---------- 6. 已经在跑？ ----------
$state = Read-EngineState
if ($state -and (Test-ProcessAlive -ProcessId $state.pid) -and (Wait-Health -Port $state.port -Seconds 3)) {
    Write-Note "引擎已经在跑（pid $($state.pid)，端口 $($state.port)）：直接把浏览器叫过来"
    if (-not $NoBrowser) { Start-Process "http://127.0.0.1:$($state.port)" }
    Write-Host ''
    Write-Host "  地址：http://127.0.0.1:$($state.port)   停：双击 stop.bat" -ForegroundColor White
    exit 0
}

# ---------- 7. 前台模式（调试用） ----------
if ($Foreground) {
    $front = if ($Port -gt 0) { $Port } else { 8780 }
    Write-Step "前台起引擎（端口 $front；日志就在这个窗口，Ctrl+C 停）"
    $frontArgs = (Get-EngineEncoding) + @('-jar', $jar, 'web', '--port', "$front", '--no-open', '-p', $project)
    & $java @frontArgs
    exit 0
}

# ---------- 8. 后台起 + 等健康 ----------
Write-Step '起引擎'
$engine = Start-Engine -Java $java -Jar $jar -Project $project -Port $Port
if (-not $engine.ok) {
    Write-Bad $engine.message
    exit 1
}
if ($engine.shifted) {
    Write-Warn2 "$($engine.wanted) 被占了，改用 $($engine.port)（地址里也要用这个端口）"
}
Write-Ok "起来了（$($engine.port)，pid $($engine.pid)）"

if (-not $NoBrowser) { Start-Process $engine.url }
Write-Host ''
Write-Host '  ---------------------------------------------' -ForegroundColor DarkGray
Write-Host "  地址   $($engine.url)" -ForegroundColor White
Write-Host "  项目   $project"
Write-Host "  日志   $($engine.outLog)"
Write-Host "         （引擎自己的日志：$env:USERPROFILE\.specflow\logs\engine.log）" -ForegroundColor DarkGray
Write-Host '  停掉   双击 stop.bat（会连容器和卷一起收干净）' -ForegroundColor White
Write-Host '  ---------------------------------------------' -ForegroundColor DarkGray
Write-Host ''
