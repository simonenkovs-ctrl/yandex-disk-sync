@echo off
setlocal
title Yandex Disk Sync

cd /d "%~dp0"

where pythonw >nul 2>nul
if %ERRORLEVEL% equ 0 (
    start "" pythonw main.py --foreground
    goto done
)

where pyw >nul 2>nul
if %ERRORLEVEL% equ 0 (
    start "" pyw main.py --foreground
    goto done
)

set LOCAL_PYW=%LOCALAPPDATA%\Python\pythoncore-3.15-64\pythonw.exe
if exist "%LOCAL_PYW%" (
    start "" "%LOCAL_PYW%" main.py --foreground
    goto done
)

set HARNESS_PYW=%LOCALAPPDATA%\Programs\DeepSeek Harness\resources\runtime\primary-runtime\dependencies\python\pythonw.exe
if exist "%HARNESS_PYW%" (
    start "" "%HARNESS_PYW%" main.py --foreground
    goto done
)

where python >nul 2>nul
if %ERRORLEVEL% equ 0 (
    start "" python main.py
    goto done
)

echo [ERROR] Could not start application with Python.
pause

:done
endlocal
