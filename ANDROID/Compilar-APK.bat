@echo off
rem ===================================================================
rem  Central de Cameras - compila o APK
rem  Requisitos: JDK 17 e Gradle 9.x no PATH (ou ajuste abaixo)
rem ===================================================================
setlocal
chcp 65001 >nul
cd /d "%~dp0"

if not defined JAVA_HOME (
    if exist "D:\Arquivos de Programas\Java\jdk-17" (
        set "JAVA_HOME=D:\Arquivos de Programas\Java\jdk-17"
    )
)

echo.
echo   Compilando o APK...
echo.

call gradle assembleDebug --console=plain
if errorlevel 1 (
    echo.
    echo   FALHA na compilacao.
    pause
    exit /b 1
)

if not exist "dist" mkdir dist

rem O projeto gera um APK por arquitetura (splits), por causa do libVLC.
set "SAIDA=app\build\outputs\apk\debug"
set "OK=0"

if exist "%SAIDA%\app-armeabi-v7a-debug.apk" (
    copy /y "%SAIDA%\app-armeabi-v7a-debug.apk" "dist\CentralCameras-arm32.apk" >nul
    set "OK=1"
)
if exist "%SAIDA%\app-arm64-v8a-debug.apk" (
    copy /y "%SAIDA%\app-arm64-v8a-debug.apk" "dist\CentralCameras-arm64.apk" >nul
    set "OK=1"
)

rem Caso o build nao use splits (configuracao antiga).
if "%OK%"=="0" (
    if exist "%SAIDA%\app-debug.apk" (
        copy /y "%SAIDA%\app-debug.apk" "dist\CentralCameras.apk" >nul
        set "OK=1"
    )
)

if "%OK%"=="0" (
    echo   ERRO: APK nao foi gerado.
    pause
    exit /b 1
)

echo.
echo   ==========================================
echo    APK gerado com sucesso
echo   ==========================================
echo.
echo   Arquivos em %CD%\dist:
dir /b dist\*.apk
echo.
echo   Qual instalar:
echo     - Celular 32 bits / antigo  -> CentralCameras-arm32.apk
echo     - Celular moderno 64 bits   -> CentralCameras-arm64.apk
echo.
echo   Na duvida, use o arm64. Se der erro "INSTALL_FAILED_NO_MATCHING_ABIS",
echo   use o arm32.
echo.
echo   Para instalar no celular conectado por USB:
echo       adb install -r dist\CentralCameras-arm64.apk
echo.
pause
endlocal
