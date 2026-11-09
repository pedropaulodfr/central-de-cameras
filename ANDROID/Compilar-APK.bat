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

set "APK=app\build\outputs\apk\debug\app-debug.apk"
if not exist "%APK%" (
    echo   ERRO: APK nao foi gerado.
    pause
    exit /b 1
)

if not exist "dist" mkdir dist
copy /y "%APK%" "dist\CentralCameras.apk" >nul

echo.
echo   ==========================================
echo    APK gerado com sucesso
echo   ==========================================
echo.
echo   Arquivo: %CD%\dist\CentralCameras.apk
echo.
echo   Para instalar no celular conectado por USB:
echo       adb install -r dist\CentralCameras.apk
echo.
pause
endlocal
