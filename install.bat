@echo off
setlocal
title Установка Яндекс Диск Синхронизатора

echo ========================================================
echo       Установка Яндекс Диск Синхронизатора
echo ========================================================
echo.

set SOURCE_DIR=%~dp0
set TARGET_DIR=%LOCALAPPDATA%\YandexDiskSync

echo 1. Копирование файлов программы в %TARGET_DIR%...
if not exist "%TARGET_DIR%" mkdir "%TARGET_DIR%"

if exist "%SOURCE_DIR%YandexDiskSync.exe" (
    copy /y "%SOURCE_DIR%YandexDiskSync.exe" "%TARGET_DIR%\YandexDiskSync.exe" >nul
) else if exist "%SOURCE_DIR%main.py" (
    xcopy /e /y /i "%SOURCE_DIR%*" "%TARGET_DIR%\" >nul
)

if exist "%SOURCE_DIR%app.ico" (
    copy /y "%SOURCE_DIR%app.ico" "%TARGET_DIR%\app.ico" >nul
)
if exist "%SOURCE_DIR%uninstall.bat" (
    copy /y "%SOURCE_DIR%uninstall.bat" "%TARGET_DIR%\uninstall.bat" >nul
)

echo 2. Создание ярлыков на Рабочем столе и в меню Пуск...
powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "$ws = New-Object -ComObject WScript.Shell; " ^
  "$exe = if (Test-Path '%TARGET_DIR%\YandexDiskSync.exe') { '%TARGET_DIR%\YandexDiskSync.exe' } else { 'python.exe' }; " ^
  "$args = if (Test-Path '%TARGET_DIR%\YandexDiskSync.exe') { '' } else { '\"%TARGET_DIR%\main.py\"' }; " ^
  "$desktop = [Environment]::GetFolderPath('Desktop'); " ^
  "$sDesktop = $ws.CreateShortcut(\"$desktop\Яндекс Диск Синхронизация.lnk\"); " ^
  "$sDesktop.TargetPath = $exe; " ^
  "$sDesktop.Arguments = $args; " ^
  "$sDesktop.WorkingDirectory = '%TARGET_DIR%'; " ^
  "if (Test-Path '%TARGET_DIR%\app.ico') { $sDesktop.IconLocation = '%TARGET_DIR%\app.ico,0' }; " ^
  "$sDesktop.Save(); " ^
  "$programs = [Environment]::GetFolderPath('Programs'); " ^
  "$sStart = $ws.CreateShortcut(\"$programs\Яндекс Диск Синхронизация.lnk\"); " ^
  "$sStart.TargetPath = $exe; " ^
  "$sStart.Arguments = $args; " ^
  "$sStart.WorkingDirectory = '%TARGET_DIR%'; " ^
  "if (Test-Path '%TARGET_DIR%\app.ico') { $sStart.IconLocation = '%TARGET_DIR%\app.ico,0' }; " ^
  "$sStart.Save(); "

echo 3. Настройка автозапуска при включении компьютера...
set /p AUTOSTART="Запускать программу автоматически при старте Windows? (Y/N, по умолчанию Y): "
if /i "%AUTOSTART%"=="" set AUTOSTART=Y
if /i "%AUTOSTART%"=="Y" (
    if exist "%TARGET_DIR%\YandexDiskSync.exe" (
        reg add "HKCU\Software\Microsoft\Windows\CurrentVersion\Run" /v "YandexDiskSync" /t REG_SZ /d "\"%TARGET_DIR%\YandexDiskSync.exe\"" /f >nul
    ) else (
        reg add "HKCU\Software\Microsoft\Windows\CurrentVersion\Run" /v "YandexDiskSync" /t REG_SZ /d "pythonw \"%TARGET_DIR%\main.py\"" /f >nul
    )
    echo    [+] Автозагрузка успешно включена.
) else (
    echo    [-] Автозагрузка пропущена.
)

echo.
echo ========================================================
echo          УСТАНОВКА УСПЕШНО ЗАВЕРШЕНА!
echo ========================================================
echo.
echo Ярлык создан на вашем Рабочем столе.
echo.
set /p LAUNCH="Запустить Яндекс Диск Синхронизатор прямо сейчас? (Y/N, по умолчанию Y): "
if /i "%LAUNCH%"=="" set LAUNCH=Y
if /i "%LAUNCH%"=="Y" (
    if exist "%TARGET_DIR%\YandexDiskSync.exe" (
        start "" "%TARGET_DIR%\YandexDiskSync.exe"
    ) else (
        start "" python "%TARGET_DIR%\main.py"
    )
)

endlocal
