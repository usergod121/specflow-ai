@echo off
rem 一条命令跑完三套件。真正的逻辑在 check.ps1 里，参数原样转过去。
rem 单独给一个 .cmd 是因为双击/直接敲 .\check.ps1 会被执行策略挡住，而这里的理由
rem 是「跑本项目自己的测试」，用 -ExecutionPolicy Bypass 是这台机器上唯一不折腾的做法。
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0check.ps1" %*
exit /b %ERRORLEVEL%
