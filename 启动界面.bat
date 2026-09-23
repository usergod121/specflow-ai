@echo off
rem 本文件是 GBK 编码：cmd.exe 按当前代码页逐字节读批处理，
rem 用 UTF-8 存中文会让它把中文字节当成命令去执行。行尾必须是 CRLF。
chcp 936 >nul
setlocal
cd /d "%~dp0"

set "ROOT=%~dp0"
set "ROOT=%ROOT:~0,-1%"
set "JAR=%ROOT%\target\specflow.jar"
set "KEYFILE=%ROOT%\.specflow\local.env"
set "PORT=8770"

echo.
echo   specflow 界面启动器
echo   ================================
echo.

rem ---- 找一个真正的 java ----
rem PATH 上的 java 常常是 ...\Oracle\Java\javapath\java.exe，那是个转发器：
rem 它把真正的 JVM 拉起来之后自己挡在前面。关窗口时只有转发器收到信号，
rem 真正跑服务的那个进程会活下来占着端口；而且下面按端口收尸时也要多绕一层。
rem 所以优先用 JAVA_HOME 里那个真的。
set "JAVA="
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA=%JAVA_HOME%\bin\java.exe"
if not defined JAVA for /f "delims=" %%i in ('where java 2^>nul') do if not defined JAVA set "JAVA=%%i"
if not defined JAVA goto no_java

rem ---- 没有 jar 才编译 ----
if exist "%JAR%" goto have_jar
echo   首次运行，正在编译，大约需要半分钟...
echo.
call :build
if errorlevel 1 goto build_failed

:have_jar
rem ---- API Key 只问一次 ----
if exist "%KEYFILE%" goto have_key
if not "%SPECFLOW_API_KEY%"=="" goto have_key
echo   调用模型需要一个 API Key。填一次就会记住，以后不用再填。
echo   （直接回车跳过也行：界面能开、能选文件，只是点「运行」时会提示缺密钥）
echo.
set /p SPECFLOW_API_KEY=  粘贴 API Key 后回车: 
if "%SPECFLOW_API_KEY%"=="" goto have_key
if not exist "%ROOT%\.specflow" mkdir "%ROOT%\.specflow"
rem 直接回显写入。API Key 是 sk- 加一串字母数字，不会碰到 & ^ 这些批处理的特殊字符。
> "%KEYFILE%" echo SPECFLOW_API_KEY=%SPECFLOW_API_KEY%
echo.
echo   已记到 .specflow\local.env，这个文件不进版本库，下次不用再填。
echo.

:have_key
rem ---- 收掉上一次没关干净的实例 ----
call :free_port
rem 旧实例刚死，它那份临时副本这时才删得动。必须在下面复制新副本之前清，
rem 而且下面只删「自己那份」——用通配符收尾会连刚复制出来的新副本一起删掉。
call :clean_stale

rem 先把 jar 复制出去再运行。Windows 上运行中的 jar 会被文件锁占住，
rem 直接跑 target 里那一份会导致下次 mvn package 报 Failed to delete。
set "RUNTIME=%TEMP%\specflow-runtime-%RANDOM%.jar"
copy /y "%JAR%" "%RUNTIME%" >nul
echo   正在启动，浏览器会自动打开。进去先是一个欢迎页，在那里挑项目。
echo   用完之后关掉这个窗口即可。
echo.
"%JAVA%" -jar "%RUNTIME%" web --port %PORT%
set "CODE=%ERRORLEVEL%"
del "%RUNTIME%" >nul 2>nul

if not "%CODE%"=="0" (
    echo.
    echo   [错误] 服务异常退出，代码 %CODE%。
    pause
    exit /b %CODE%
)
echo.
echo   界面已停止。
pause
exit /b 0

rem ---- 子过程 ----

rem 旧实例可能是个被遗弃的 JVM（关窗口时只有转发器收到信号），没有 pid 文件可查，
rem 所以直接按端口找。只杀 java.exe：端口也可能是别的程序在用。
:free_port
for /f "tokens=5" %%p in ('netstat -ano ^| findstr /c:":%PORT% " ^| findstr /i "LISTENING"') do call :kill_if_java %%p
exit /b 0

:kill_if_java
tasklist /FI "PID eq %1" 2>nul | findstr /i "java.exe" >nul
if errorlevel 1 exit /b 0
echo   端口 %PORT% 还被上一次的实例占着（pid %1），先收掉它
taskkill /F /PID %1 >nul 2>nul
exit /b 0

rem 清掉以前异常退出留下的副本。还开着的那个删不动（文件锁），跳过即可。
:clean_stale
del "%TEMP%\specflow-runtime-*.jar" >nul 2>nul
exit /b 0

:no_java
echo   [错误] 没找到 java 命令。
echo   请先安装 JDK 17，或者确认 java 已加入 PATH。
echo   自检方法：开一个命令行窗口，执行  java -version
pause
exit /b 1

:build_failed
echo   [错误] 编译失败，请把上面的信息截图。
pause
exit /b 1

:build
where mvn >nul 2>nul
if not errorlevel 1 (
    call mvn -q -DskipTests package
    exit /b %errorlevel%
)
set "MVN=E:\javaweb\01\apache-maven-3.9.11-bin\apache-maven-3.9.11\bin\mvn.cmd"
if exist "%MVN%" (
    call "%MVN%" -q -DskipTests package
    exit /b %errorlevel%
)
echo   [ERROR] 没找到 Maven。请安装 Maven，或把它加入 PATH。
exit /b 1
