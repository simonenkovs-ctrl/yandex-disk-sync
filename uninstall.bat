@echo off
setlocal
title Удаление Яндекс Диск Синхронизатора

echo ========================================================
echo       Удаление Яндекс Диск Синхронизатора
echo ========================================================
echo.

set /p CONFIRM="Вы уверены, что хотите удалить приложение? (Y/N): "
if /i not "%CONFIRM%"=="Y" (
    echo Удаление отменено.
    pause
    exit /b
)

echo 1. Отключение автозапуска...
reg delete "HKCU\Software\Microsoft\Windows\CurrentVersion\Run" /v "YandexDiskSync" /f >nul 2>nul

echo 2. Удаление ярлыков...
powershell -NoProfile -Command ^
  "$desktop = [Environment]::GetFolderPath('Desktop'); " ^
  "Remove-Item \"$desktop\Яндекс Диск Синхронизация.lnk\" -ErrorAction SilentlyContinue; " ^
  "$programs = [Environment]::GetFolderPath('Programs'); " ^
  "Remove-Item \"$programs\Яндекс Диск Синхронизация.lnk\" -ErrorAction SilentlyContinue; "

echo 3. Удаление файлов приложения...
set TARGET_DIR=%LOCALAPPDATA%\YandexDiskSync
if exist "%TARGET_DIR%" (
    taskkill /f /im YandexDiskSync.exe >nul 2>nul
    timeout /t 1 >nul
    rmdir /s /q "%TARGET_DIR%" >nul 2>nul
)

echo.
echo Приложение успешно удалено с компьютера.
pause
endlocal
