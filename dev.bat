@echo off
chcp 65001 >nul
rem ============================================================
rem  specflow - dev mode (only needed when you edit the UI):
rem  engine + Vite dev server with hot reload. Vite proxies
rem  /api to the engine. Ctrl+C stops Vite only; the engine
rem  keeps running - stop it with stop.bat.
rem    dev.bat -Port 8790
rem  Messages come from tools\dev.ps1.
rem ============================================================
setlocal
set "PS=powershell"
where pwsh >nul 2>nul && set "PS=pwsh"
"%PS%" -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\dev.ps1" %*
if errorlevel 1 (
  echo.
  echo  [specflow] dev mode exited - see above.
  echo  press any key to close this window.
  pause >nul
)
endlocal
