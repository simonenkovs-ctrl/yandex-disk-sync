@echo off
chcp 65001 >nul
echo ============================================================
echo   Сборка Android APK - Яндекс Диск Синхронизатор
echo ============================================================
echo.

where gradlew.bat >nul 2>&1
if exist "gradlew.bat" (
    call gradlew.bat assembleDebug --no-daemon
    if %ERRORLEVEL% equ 0 (
        echo.
        echo [OK] Сборка успешно завершена!
        echo APK файл находится в: app\build\outputs\apk\debug\app-debug.apk
    ) else (
        echo.
        echo [ОШИБКА] Ошибка при сборке через Gradle.
    )
) else (
    echo [ИНФО] gradlew.bat не найден. Откройте проект в Android Studio для сборки.
)

pause
