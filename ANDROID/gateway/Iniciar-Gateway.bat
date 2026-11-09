@echo off
rem ===================================================================
rem  Central de Cameras - Gateway remoto
rem  Inicia o servidor que entrega as cameras para o celular.
rem ===================================================================
setlocal enabledelayedexpansion
chcp 65001 >nul
cd /d "%~dp0"

echo.
echo   ==========================================
echo    CENTRAL DE CAMERAS - Gateway
echo   ==========================================
echo.

rem --- Node.js -------------------------------------------------------
where node >nul 2>&1
if errorlevel 1 (
    echo   ERRO: Node.js nao encontrado.
    echo   Instale de https://nodejs.org/ ^(versao LTS^)
    echo   ou rode: winget install OpenJS.NodeJS.LTS
    echo.
    pause
    exit /b 1
)

rem --- ffmpeg --------------------------------------------------------
where ffmpeg >nul 2>&1
if errorlevel 1 (
    echo   AVISO: ffmpeg nao encontrado no PATH.
    echo   Sem ele o gateway nao consegue abrir as cameras.
    echo   Instale com: winget install Gyan.FFmpeg
    echo.
)

rem --- relay compilado ------------------------------------------------
if not exist "RtspRelayCli.exe" (
    echo   ERRO: RtspRelayCli.exe nao encontrado nesta pasta.
    echo   Ele e o componente que contorna o defeito das cameras Yoosee.
    echo.
    pause
    exit /b 1
)

rem --- token persistente ----------------------------------------------
if not exist "token.txt" (
    echo   Gerando token de acesso...
    node -e "console.log(require('crypto').randomBytes(24).toString('hex'))" > "token.txt"
)
set /p TOKEN=<token.txt

echo   Token de acesso:
echo.
echo       !TOKEN!
echo.
echo   Use este token no aplicativo do celular.
echo.

rem --- inicia --------------------------------------------------------
echo   Iniciando gateway na porta 8080...
echo   Para encerrar, feche esta janela ou pressione Ctrl+C.
echo.

node server.js --port 8080 --token "!TOKEN!"

echo.
echo   Gateway encerrado.
pause
endlocal
