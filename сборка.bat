@echo off
chcp 65001 >nul
setlocal
cd /d "%~dp0"

echo ============================================
echo  Сборка мобильного приложения ServerMonitor
echo ============================================
echo.

if not exist "local.properties" (
    echo [!] Не найден local.properties — нужен путь к Android SDK.
    echo     Создайте файл с одной строкой, например:
    echo     sdk.dir=C\:\\Users\\Имя\\AppData\\Local\\Android\\Sdk
    echo.
)

echo Собираю debug-версию APK...
call "%~dp0gradlew.bat" -p "%~dp0." assembleDebug
if errorlevel 1 (
    echo.
    echo [!] Сборка не удалась — смотрите сообщения выше.
    pause
    exit /b 1
)

set "APK=app\build\outputs\apk\debug\app-debug.apk"
if not exist "%APK%" (
    echo [!] Сборка прошла, но файл %APK% не найден.
    pause
    exit /b 1
)

copy /y "%APK%" "ServerMonitorMobile.apk" >nul
echo.
echo Готово: %~dp0ServerMonitorMobile.apk
echo Скопируйте этот файл на телефон и откройте его для установки.
echo.
pause
