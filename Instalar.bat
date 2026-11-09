@echo off
rem ===================================================================
rem  Central de Cameras - instalador para Windows
rem  Uso:  Instalar.bat                 instala
rem        Instalar.bat -SomenteDiagnostico   so verifica
rem        Instalar.bat -Desinstalar    remove
rem ===================================================================
setlocal

rem executa o PowerShell de 64 bits com privilegios de administrador
set "PS64=%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe"
set "PS32=%SystemRoot%\SysWOW64\WindowsPowerShell\v1.0\powershell.exe"
if exist "%PS64%" (
    set "PSEXE=%PS64%"
) else (
    set "PSEXE=%PS32%"
)

echo.
echo   Central de Cameras - Instalador
echo   -------------------------------
echo.

rem tenta elevar privilegios; se o usuario cancelar, continua sem eles
net session >nul 2>&1
if %errorlevel% neq 0 (
    echo   Solicitando privilegios de administrador...
    powershell -NoProfile -ExecutionPolicy Bypass -Command "Start-Process -FilePath '%~f0' -ArgumentList '%*' -Verb RunAs" 2>nul
    if errorlevel 1 (
        echo.
        echo   Nao foi possivel obter privilegios de administrador.
        echo   Rode manualmente: powershell -ExecutionPolicy Bypass -File Instalador.ps1
        pause
    )
    exit /b
)

echo   Verificando e instalando...
echo.
"%PSEXE%" -NoProfile -ExecutionPolicy Bypass -File "%~dp0Instalador.ps1" %*

set "ERRO=%errorlevel%"
echo.
if "%ERRO%"=="0" (
    echo   Instalacao concluida. Use o atalho na Area de Trabalho.
) else (
    echo   Houve pendencias acima. See o relatorio para detalhes.
)
echo.
pause
endlocal
