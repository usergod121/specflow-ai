<#
  一条命令跑完三套件。

      powershell -ExecutionPolicy Bypass -File check.ps1

  三套件：
    1) mvn clean test                     Java 用例（含真起容器的那些；没有 Docker 时条件跳过）
    2) node src/test/js/web.test.js       前端纯逻辑（从 index.html / flowchart.js 抽真源码执行）
    3) node src/test/js/browser.test.js   浏览器闭环（CDP 驱动本机 Chrome，跑完自己清理）

  为什么要收成一条：三套件各自绿、合起来不绿是常事——最典型的是改了 index.html 却没有重新打包，
  浏览器那一套跑的还是 jar 里的旧界面（看着全绿，其实什么都没验）。所以这个脚本**先重新打包
  再起服务**，把那个坑堵掉。

  它只做三件事：跑、报数、收拾现场（杀自己起的服务和 Chrome、删自己的临时日志）。
  不碰仓库里的文件，不碰 8770（那是用户自己的实例，脚本里直接挡掉）。

  参数：
    -Port <n>      指定端口；不给就临时挑一个空闲的
    -SkipBrowser   只跑前两套
    -SkipPackage   不重新打包（默认打包，见上面那个坑）
    -KeepServer    跑完不杀服务（排查界面时用）
#>
[CmdletBinding()]
param(
    [int]$Port = 0,
    [switch]$SkipBrowser,
    [switch]$SkipPackage,
    [switch]$KeepServer
)

$root = if ($PSScriptRoot) { $PSScriptRoot } else { (Get-Location).Path }
Set-Location $root

$results = New-Object System.Collections.ArrayList
function Note($name, $ok, $detail) {
    [void]$results.Add([pscustomobject]@{ 套件 = $name; 结果 = $(if ($ok) { 'ok' } else { 'FAIL' }); 说明 = $detail })
}
function Head($text) {
    Write-Host ''
    Write-Host "==== $text ====" -ForegroundColor Cyan
}

# ---------- 1. Java ----------

Head '1/3  mvn clean test'
& mvn clean test
$javaOk = ($LASTEXITCODE -eq 0)
$detail = 'mvn 退出码 ' + $LASTEXITCODE
if ($javaOk) {
    # surefire 的 txt 报告是逐类的，汇总一次给人看：只报「跑了多少 / 失败 / 错误 / 跳过」
    $run = 0; $fail = 0; $err = 0; $skip = 0; $classes = 0
    Get-ChildItem 'target/surefire-reports/*.txt' -ErrorAction SilentlyContinue | ForEach-Object {
        $text = Get-Content $_.FullName -Raw
        if ($text -match 'Tests run: (\d+), Failures: (\d+), Errors: (\d+), Skipped: (\d+)') {
            $run += [int]$Matches[1]; $fail += [int]$Matches[2]
            $err += [int]$Matches[3]; $skip += [int]$Matches[4]; $classes++
        }
    }
    $detail = "$classes 个测试类：跑了 $run，失败 $fail，错误 $err，跳过 $skip"
}
Note 'Java（mvn clean test）' $javaOk $detail

# ---------- 2. 前端纯逻辑 ----------

Head '2/3  node src/test/js/web.test.js'
& node 'src/test/js/web.test.js'
$webOk = ($LASTEXITCODE -eq 0)
Note '前端纯逻辑（web.test.js）' $webOk ('node 退出码 ' + $LASTEXITCODE)

# ---------- 3. 浏览器闭环 ----------

$server = $null
$logFile = $null
if (-not $SkipBrowser) {
    Head '3/3  浏览器闭环'

    if (-not $SkipPackage) {
        Write-Host '先重新打包（界面改了却不打包，浏览器那套跑的是 jar 里的旧界面）'
        & mvn -q -DskipTests package
        if ($LASTEXITCODE -ne 0) {
            Note '重新打包' $false ('mvn package 退出码 ' + $LASTEXITCODE)
        }
    }

    $jar = 'target/specflow.jar'
    if (-not (Test-Path $jar)) {
        Note '浏览器闭环（browser.test.js）' $false "没有 $jar，先跑一次 mvn package"
    } else {
        if ($Port -le 0) {
            # 让系统给一个空闲端口，比在 3081..3130 里猜更稳
            $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
            $listener.Start()
            $Port = ([System.Net.IPEndPoint]$listener.LocalEndpoint).Port
            $listener.Stop()
        }
        if ($Port -eq 8770) {
            throw '8770 是用户自己那个实例，换一个端口'
        }

        $logFile = Join-Path $env:TEMP ("sf-check-$Port.log")
        Write-Host "起服务：java -jar $jar web --port $Port --no-open -p ."
        $server = Start-Process -FilePath 'java' -PassThru -WindowStyle Hidden `
            -ArgumentList @('-jar', $jar, 'web', '--port', "$Port", '--no-open', '-p', '.') `
            -RedirectStandardOutput $logFile -RedirectStandardError "$logFile.err"

        $ready = $false
        $deadline = (Get-Date).AddSeconds(90)
        while ((Get-Date) -lt $deadline) {
            try {
                $probe = Invoke-WebRequest -Uri "http://127.0.0.1:$Port/" -UseBasicParsing -TimeoutSec 3
                if ($probe.StatusCode -eq 200) { $ready = $true; break }
            } catch {
                Start-Sleep -Milliseconds 400
            }
        }

        if (-not $ready) {
            Note '浏览器闭环（browser.test.js）' $false "服务 90 秒没起来，日志见 $logFile"
        } else {
            # 第三个参数必须是绝对路径：有一条链要往项目根里放坏模板
            & node 'src/test/js/browser.test.js' "http://127.0.0.1:$Port/" (Get-Item $root).FullName
            Note '浏览器闭环（browser.test.js）' ($LASTEXITCODE -eq 0) ('node 退出码 ' + $LASTEXITCODE)
        }
    }
}

# ---------- 收拾现场 ----------

if ($server -and -not $KeepServer) {
    # 按端口 owner 杀，而不是只杀 Start-Process 那个句柄：java 起的是同一个进程，
    # 但「按端口杀」顺带证明了端口真的被放掉了
    Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue |
        Select-Object -ExpandProperty OwningProcess -Unique |
        ForEach-Object { Stop-Process -Id $_ -Force -ErrorAction SilentlyContinue }
    if (-not $server.HasExited) { Stop-Process -Id $server.Id -Force -ErrorAction SilentlyContinue }
    if ($logFile) {
        Remove-Item $logFile, "$logFile.err" -Force -ErrorAction SilentlyContinue
    }
    Write-Host "服务已停（端口 $Port 按 owner 杀掉）"
}

# ---------- 汇总 ----------

Write-Host ''
Write-Host '==== 汇总 ====' -ForegroundColor Cyan
$results | Format-Table -AutoSize
$failed = @($results | Where-Object { $_.结果 -ne 'ok' })
if ($failed.Count -eq 0) {
    Write-Host '三套件全绿' -ForegroundColor Green
    exit 0
}
Write-Host ("有 " + $failed.Count + " 套件没过：" + (($failed | ForEach-Object { $_.套件 }) -join '、')) -ForegroundColor Red
exit 1
