@echo off
setlocal
title Сборка Яндекс Диск Синхронизатора в EXE

cd /d "%~dp0"

echo ========================================================
echo   Сборка Яндекс Диск Синхронизатора (Автономный EXE)
echo ========================================================
echo.

REM Определение рабочего интерпретатора Python
set PY_CMD=

where python >nul 2>nul
if %ERRORLEVEL% equ 0 (
    python -c "import sys; exit(0)" >nul 2>nul
    if %ERRORLEVEL% equ 0 set PY_CMD=python
)

if "%PY_CMD%"=="" (
    where py >nul 2>nul
    if %ERRORLEVEL% equ 0 set PY_CMD=py
)

if "%PY_CMD%"=="" (
    if exist "%LOCALAPPDATA%\Python\pythoncore-3.15-64\python.exe" (
        set PY_CMD="%LOCALAPPDATA%\Python\pythoncore-3.15-64\python.exe"
    )
)

if "%PY_CMD%"=="" (
    if exist "%LOCALAPPDATA%\Programs\DeepSeek Harness\resources\runtime\primary-runtime\dependencies\python\python.exe" (
        set PY_CMD="%LOCALAPPDATA%\Programs\DeepSeek Harness\resources\runtime\primary-runtime\dependencies\python\python.exe"
    )
)

if "%PY_CMD%"=="" (
    echo [ОШИБКА] Python не найден на вашем компьютере!
    echo Установите Python с официального сайта: https://www.python.org/downloads/
    pause
    exit /b 1
)

echo Найден Python: %PY_CMD%
echo.

echo [Шаг 1 из 4] Установка / обновление PyInstaller...
%PY_CMD% -m pip install --upgrade pyinstaller
if %ERRORLEVEL% neq 0 (
    echo [ОШИБКА] Не удалось установить PyInstaller через pip.
    echo Проверьте подключение к интернету.
    pause
    exit /b 1
)

echo.
echo [Шаг 2 из 4] Компиляция приложения в один автономный EXE файл...
%PY_CMD% -m PyInstaller --noconfirm --clean --onefile --windowed --icon="app.ico" --name="YandexDiskSync" --add-data="app.ico;." main.py
if %ERRORLEVEL% neq 0 (
    echo [ОШИБКА] Сбой компиляции PyInstaller!
    pause
    exit /b 1
)

echo.
echo [Шаг 3 из 4] Подготовка пакета для переноса на другой ПК...
if not exist "dist" mkdir "dist"
if exist "dist\YandexDiskSync.exe" (
    copy /y "..\install.bat" "dist\install.bat" >nul 2>nul
    copy /y "..\uninstall.bat" "dist\uninstall.bat" >nul 2>nul
    copy /y "app.ico" "dist\app.ico" >nul 2>nul
    copy /y "README.md" "dist\README.txt" >nul 2>nul
)

echo.
echo [Шаг 4 из 4] Создание ZIP-архива для флешки / отправки...
powershell -NoProfile -Command "Compress-Archive -Path 'dist\YandexDiskSync.exe', 'dist\install.bat', 'dist\uninstall.bat', 'app.ico', 'README.md' -DestinationPath 'dist\YandexDiskSync_Windows_Ready.zip' -Force"

echo.
echo ========================================================
echo             СБОРКА УСПЕШНО ЗАВЕРШЕНА!
echo ========================================================
echo.
echo Теперь файлы и папка dist созданы:
echo 1. Готовый EXE файл:
echo    %~dp0dist\YandexDiskSync.exe
echo.
echo 2. Готовый ZIP-архив для переноса на другой ПК:
echo    %~dp0dist\YandexDiskSync_Windows_Ready.zip
echo.
echo Этот EXE файл полностью автономный.
echo На другом компьютере НЕ нужен Python!
echo ========================================================
echo.
pause
endlocal
