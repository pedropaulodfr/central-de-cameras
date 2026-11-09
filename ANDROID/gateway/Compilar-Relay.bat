@echo off
rem ===================================================================
rem  Compila o RtspRelayCli.exe
rem
rem  O executavel e um Main em volta do RtspRelay.cs do projeto. Ele existe
rem  porque o gateway (Node.js) precisa de um processo separado que abra a
rem  porta local e informe o numero da porta.
rem
rem  O .exe ja vem compilado no repositorio; rode este script apenas se
rem  alterar o RtspRelay.cs ou o RtspRelayCli.cs.
rem ===================================================================
setlocal
chcp 65001 >nul
cd /d "%~dp0"

set "CSC=%SystemRoot%\Microsoft.NET\Framework64\v4.0.30319\csc.exe"
if not exist "%CSC%" (
    set "CSC=%SystemRoot%\Microsoft.NET\Framework\v4.0.30319\csc.exe"
)
if not exist "%CSC%" (
    echo   ERRO: csc.exe nao encontrado.
    echo   Instale o .NET Framework 4.x.
    pause
    exit /b 1
)

echo.
echo   Compilando RtspRelayCli.exe...
echo.

"%CSC%" /nologo /target:exe /out:"RtspRelayCli.exe" /platform:anycpu ^
    "RtspRelayCli.cs" "..\..\RtspRelay.cs"

if errorlevel 1 (
    echo.
    echo   FALHA na compilacao.
    pause
    exit /b 1
)

echo.
echo   OK: RtspRelayCli.exe gerado em %CD%
echo.
pause
endlocal
