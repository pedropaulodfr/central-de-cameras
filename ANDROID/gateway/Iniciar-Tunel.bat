@echo off
rem ===================================================================
rem  Central de Cameras - Tunel para acesso de fora da rede
rem
rem  Abre um tunel HTTPS publico (Cloudflare) apontando para o gateway
rem  que esta rodando na porta 8080. NAO precisa abrir porta no roteador
rem  e funciona mesmo atras de CGNAT.
rem
rem  O gateway precisa estar rodando antes (Iniciar-Gateway.bat).
rem ===================================================================
setlocal enabledelayedexpansion
chcp 65001 >nul
cd /d "%~dp0"

if not exist "cloudflared.exe" (
    echo.
    echo   ERRO: cloudflared.exe nao encontrado nesta pasta.
    echo.
    echo   Baixe em:
    echo     https://github.com/cloudflare/cloudflared/releases/latest
    echo     arquivo: cloudflared-windows-amd64.exe
    echo   e renomeie para cloudflared.exe nesta pasta.
    echo.
    pause
    exit /b 1
)

echo.
echo   ==========================================
echo    TUNEL PUBLICO (Cloudflare)
echo   ==========================================
echo.
echo   Aguarde... o endereco publico aparece abaixo.
echo   Copie o endereco https://....trycloudflare.com e cole no app.
echo.
echo   IMPORTANTE: este endereco muda cada vez que o tunel e aberto.
echo   Para um endereco fixo, use um tunel nomeado com dominio proprio.
echo.

cloudflared.exe tunnel --url http://127.0.0.1:8080 --no-autoupdate

echo.
echo   Tunel encerrado.
pause
endlocal
