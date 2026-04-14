@echo off
setlocal EnableExtensions EnableDelayedExpansion

set "SCRIPT_DIR=%~dp0"
cd /d "%SCRIPT_DIR%"

if not exist "VerInfo.txt" (
    echo VerInfo.txt not found.
    exit /b 1
)

set /p VERSION=<VerInfo.txt
set "VERSION=%VERSION: =%"
set "PACKAGE_NAME=AndroidTinyTools_%VERSION%.zip"
set "PACKAGE_PATH=%SCRIPT_DIR%%PACKAGE_NAME%"

if exist "%PACKAGE_PATH%" del /f /q "%PACKAGE_PATH%"

powershell -NoProfile -ExecutionPolicy Bypass -Command ^
    "$ErrorActionPreference = 'Stop';" ^
    "$source = Get-ChildItem -LiteralPath '.' -File | Where-Object { $_.Name -notin @('MakePackage.bat', '%PACKAGE_NAME%') } | Select-Object -ExpandProperty FullName;" ^
    "Compress-Archive -LiteralPath $source -DestinationPath '%PACKAGE_PATH%' -CompressionLevel Optimal -Force"

if errorlevel 1 (
    echo Package creation failed.
    exit /b 1
)

echo Package created: %PACKAGE_PATH%
exit /b 0
