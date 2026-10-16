@echo off
chcp 65001 >nul
rem ============================================================
rem  specflow - double-click this to stop everything:
rem    1) stop the engine process
rem    2) tear down containers / volumes / networks of this
rem       project (main env + test sandboxes)
rem  Optional:
rem    stop.bat -Purge   also delete the run data
rem                      (.specflow\flow) - asks first
rem    stop.bat -Yes     do not ask
rem  All user-facing messages come from tools\stop.ps1.
rem ============================================================
setlocal
set "PS=powershell"
where pwsh >nul 2>nul && set "PS=pwsh"
"%PS%" -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\stop.ps1" %*
if errorlevel 1 (
  echo.
  echo  [specflow] something did not finish - see above.
  echo  press any key to close this window.
  pause >nul
)
endlocal
