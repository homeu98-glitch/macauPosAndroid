@echo off
REM macau-pos 真機驗證 —— 用法： verify-device.bat [apk路徑]
REM 預設驗桌面上的 macau-pos.apk
setlocal

set "NODE_EXE=node"
where node >nul 2>nul
if errorlevel 1 (
  if exist "C:\Users\surface\.workbuddy\binaries\node\versions\22.22.2-3\node.exe" (
    set "NODE_EXE=C:\Users\surface\.workbuddy\binaries\node\versions\22.22.2-3\node.exe"
  ) else (
    echo [X] 找不到 node.exe，請先安裝 Node 22+ 或改本檔的 NODE_EXE。
    exit /b 1
  )
)

"%NODE_EXE%" "%~dp0verify-device.cjs" %*
exit /b %errorlevel%
