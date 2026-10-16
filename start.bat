@echo off
chcp 65001 >nul
rem ============================================================
rem  specflow - double-click this to start (frontend + backend
rem  in one process; the UI is already inside the jar).
rem
rem  Optional:
rem    start.bat -Project D:\my\project   which project to open
rem    start.bat -Port 8790               port (default 8780)
rem    start.bat -ApiKey sk-xxxx          api key
rem    start.bat -Rebuild                 rebuild after java changes
rem    start.bat -Foreground              keep logs in this window
rem
rem  To stop and clean up: double-click stop.bat
rem  All user-facing messages are printed by tools\start.ps1
rem  (this file stays ASCII on purpose: cmd reads .bat in the
rem   OEM codepage and would garble anything else).
rem ============================================================
setlocal
set "PS=powershell"
where pwsh >nul 2>nul && set "PS=pwsh"
"%PS%" -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\start.ps1" %*
if errorlevel 1 (
  echo.
  echo  [specflow] failed - the reason is in the lines above.
  echo  press any key to close this window.
  pause >nul
)
endlocal
