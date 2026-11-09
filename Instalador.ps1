<#
    Instalador.ps1 - Central de Cameras
    -------------------------------------------------------------------------
    Instala o programa em outro PC Windows:
      1. verifica dependencias (VLC, ffmpeg, .NET, PowerShell)
      2. copia os arquivos para %LOCALAPPDATA%\CentralCameras
      3. gera o icone multi-tamanho e o inicializador sem console
      4. cria atalhos na Area de Trabalho e na Inicializacao
      5. testa o acesso as cameras e mostra um relatorio

    Uso:
      powershell -ExecutionPolicy Bypass -File .\Instalador.ps1
      powershell -ExecutionPolicy Bypass -File .\Instalador.ps1 -Pasta "D:\Apps\CentralCameras"
      powershell -ExecutionPolicy Bypass -File .\Instalador.ps1 -SomenteDiagnostico
      powershell -ExecutionPolicy Bypass -File .\Instalador.ps1 -Desinstalar
#>
[CmdletBinding()]
param(
    # Pasta de destino da instalacao. Padrao: %LOCALAPPDATA%\CentralCameras
    [string]$Pasta = '',
    # Nao instala nada: apenas verifica e mostra o relatorio
    [switch]$SomenteDiagnostico,
    # Nao cria o atalho de inicializacao automatica
    [switch]$SemInicializacao,
    # Nao testa o acesso as cameras (teste pode demorar)
    [switch]$SemTeste,
    # Remove a instalacao, os atalhos e os dados gravados
    [switch]$Desinstalar
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$NomeApp = 'Central de Cameras'
$NomePasta = 'CentralCameras'
$origem = $PSScriptRoot
if (-not $origem) { $origem = (Get-Location).Path }

if (-not $Pasta) { $Pasta = Join-Path $env:LOCALAPPDATA $NomePasta }
$Pasta = [Environment]::ExpandEnvironmentVariables($Pasta)

$arquivosApp = @(
    'CameraViewer.ps1',
    'RtspRelay.cs',
    'Ui.cs',
    'Ptz.cs',
    'cameras.json',
    'Testar-Cameras.ps1',
    'icone.ico'
)

# ---------------------------------------------------------------------------
# saida formatada
# ---------------------------------------------------------------------------
$corOk = 'Green'; $corAviso = 'Yellow'; $corErro = 'Red'; $corInfo = 'Cyan'
$falhas = New-Object System.Collections.Generic.List[string]
$avisos = New-Object System.Collections.Generic.List[string]

function Escrever([string]$Texto, [string]$Cor = 'Gray', [string]$Prefixo = '') {
    Write-Host "$Prefixo$Texto" -ForegroundColor $Cor
}
function Titulo([string]$Texto) {
    Write-Host ''
    Write-Host "=== $Texto " -ForegroundColor White -NoNewline
    Write-Host ('=' * [Math]::Max(3, 58 - $Texto.Length)) -ForegroundColor DarkGray
}
function Ok([string]$m)   { Escrever "OK    $m" $corOk }
function Aviso([string]$m) { Escrever "AVISO $m" $corAviso; $avisos.Add($m) }
function Erro([string]$m) { Escrever "FALHA $m" $corErro; $falhas.Add($m) }
function Item([string]$m) { Escrever "      $m" 'DarkGray' }

# ---------------------------------------------------------------------------
# desinstalacao
# ---------------------------------------------------------------------------
if ($Desinstalar) {
    Titulo 'Desinstalando'
    $ws = New-Object -ComObject WScript.Shell
    $desktop = [Environment]::GetFolderPath('Desktop')
    $inicio = [Environment]::GetFolderPath('Startup')
    foreach ($p in @((Join-Path $desktop "$NomeApp.lnk"), (Join-Path $inicio "$NomeApp.lnk"))) {
        if (Test-Path $p) { Remove-Item $p -Force; Ok "atalho removido: $p" }
    }
    $vbs = Join-Path $Pasta 'Iniciar.vbs'
    if (Test-Path $vbs) { Remove-Item $vbs -Force -ErrorAction SilentlyContinue }
    if (Test-Path $Pasta) {
        Remove-Item $Pasta -Recurse -Force -ErrorAction SilentlyContinue
        Ok "pasta removida: $Pasta"
    }
    $menu = Join-Path ([Environment]::GetFolderPath('StartMenu')) 'Programs'
    Escrever ''
    Escrever 'Desinstalacao concluida. As gravacoes e fotos na pasta antiga foram apagadas junto.' $corInfo
    exit 0
}

# ---------------------------------------------------------------------------
# 1. dependencias
# ---------------------------------------------------------------------------
Titulo 'Verificando dependencias'

# PowerShell
$versaoPS = $PSVersionTable.PSVersion
if ($versaoPS.Major -ge 5) { Ok "PowerShell $($versaoPS.Major).$($versaoPS.Minor)" }
else { Erro "PowerShell $($versaoPS.Major) e antigo. Requer 5.1 ou superior." }

# .NET Framework 4.x (WinForms)
$netOk = $false
try {
    $versaoNet = (Get-ItemProperty 'HKLM:\SOFTWARE\Microsoft\NET Framework Setup\NDP\v4\Full' -ErrorAction Stop).Release
    $netOk = $true
    Ok ".NET Framework 4.x (release $versaoNet)"
} catch {
    Erro '.NET Framework 4.x nao encontrado. Ative em "Ativar ou desativar recursos do Windows".'
}

# VLC - o ActiveX e o que exibe o video
$clsidVlc = '{9BE31822-FDAD-461B-AD51-BE1D1C159921}'
$vlcInstalado = $false
$vlcCaminho = ''
foreach ($chave in @("HKLM:\SOFTWARE\Classes\CLSID\$clsidVlc\InprocServer32",
                     "HKCU:\SOFTWARE\Classes\CLSID\$clsidVlc\InprocServer32")) {
    if (Test-Path $chave) {
        $dll = (Get-ItemProperty $chave -ErrorAction SilentlyContinue).'(default)'
        if ($dll -and (Test-Path $dll)) { $vlcInstalado = $true; $vlcCaminho = $dll; break }
    }
}
if ($vlcInstalado) {
    $dirVlc = Split-Path $vlcCaminho -Parent
    $versaoVlc = ''
    if (Test-Path (Join-Path $dirVlc 'vlc.exe')) {
        $versaoVlc = (Get-Item (Join-Path $dirVlc 'vlc.exe')).VersionInfo.ProductVersion
    }
    Ok "VLC media player $versaoVlc"
    Item $dirVlc
} else {
    Erro 'VLC media player nao encontrado (ou sem o componente ActiveX).'
    Item 'Baixe em https://www.videolan.org/vlc/ e instale com "ActiveX" marcado.'
    Item 'Opcional: winget install VideoLAN.VLC'
}

# ffmpeg - so e necessario para gravar; visualizar funciona sem ele
$ffmpegCmd = Get-Command ffmpeg -ErrorAction SilentlyContinue
$ffprobeCmd = Get-Command ffprobe -ErrorAction SilentlyContinue
if ($ffmpegCmd) {
    Ok "ffmpeg: $($ffmpegCmd.Source)"
} else {
    Aviso 'ffmpeg nao encontrado no PATH - visualize funciona, mas GRAVAR nao.'
    Item 'Baixe de https://www.gyan.dev/ffmpeg/builds/ e copie a pasta bin para dentro do app.'
    Item 'Opcional: winget install Gyan.FFmpeg'
}
if ($ffprobeCmd) { Ok "ffprobe: $($ffprobeCmd.Source)" } else { Item 'ffprobe ausente (usado apenas pelo teste de cameras).' }

# rede local
$redeOk = $false
try {
    $adaptadores = Get-NetAdapter -ErrorAction Stop | Where-Object { $_.Status -eq 'Up' -and $_.MacAddress -ne $null }
    $redeOk = ($adaptadores | Measure-Object).Count -gt 0
} catch { }
if ($redeOk) { Ok 'adaptador de rede ativo' } else { Aviso 'nenhum adaptador de rede ativo detectado.' }

# ---------------------------------------------------------------------------
# 2. arquivos do projeto
# ---------------------------------------------------------------------------
Titulo 'Verificando arquivos do projeto'
$arquivosFaltando = @()
foreach ($a in $arquivosApp) {
    $orig = Join-Path $origem $a
    if (Test-Path $orig) { Ok "presente: $a" } else { Erro "faltando: $a"; $arquivosFaltando += $a }
}
if ($arquivosFaltando.Count -gt 0 -and -not $SomenteDiagnostico) {
    Escrever ''
    Erro 'Nao e possivel instalar: faltam arquivos do projeto.'
    Item "Execute o instalador a partir da pasta que contem: $($arquivosApp -join ', ')"
    exit 1
}

# ---------------------------------------------------------------------------
# 3. configuracao das cameras
#Titulo 'Configuracao das cameras'
$caminhoConfig = Join-Path $origem 'cameras.json'
$cameras = @()
if (Test-Path $caminhoConfig) {
    try {
        $bruto = Get-Content $caminhoConfig -Raw -Encoding UTF8 | ConvertFrom-Json
        # o PowerShell 5.1 pode devolver o array ja enumerado: re-agrupa sempre
        $cameras = @($bruto)
        Ok "$($cameras.Count) camera(s) no cameras.json"
        foreach ($c in $cameras) {
            $destino = if ($c.relay) { "$($c.relay) (via relay local)" } else { "$($c.url)" }
            Item ("{0}  ->  {1}" -f $c.nome, $destino)
        }
    } catch {
        Erro "cameras.json invalido: $($_.Exception.Message)"
    }
} else {
    Aviso 'cameras.json nao encontrado - sera criado um modelo vazio.'
}

# ---------------------------------------------------------------------------
# 4. instalacao
# ---------------------------------------------------------------------------
if ($SomenteDiagnostico) {
    Titulo 'Diagnostico concluido (nada foi instalado)'
} else {
    Titulo 'Instalando'

    if (-not (Test-Path $Pasta)) {
        New-Item -ItemType Directory -Force -Path $Pasta | Out-Null
    }
    Ok "pasta de instalacao: $Pasta"

    foreach ($a in $arquivosApp) {
        $orig = Join-Path $origem $a
        if (Test-Path $orig) {
            Copy-Item $orig (Join-Path $Pasta $a) -Force
        }
    }
    Ok 'arquivos copiados'

    # icone: usa o que veio no projeto; se faltar, gera
    $iconeDest = Join-Path $Pasta 'icone.ico'
    if (-not (Test-Path $iconeDest)) {
        Aviso 'icone.ico nao encontrado no projeto - usando o icone padrao do Windows.'
    } else {
        Ok 'icone.ico instalado'
    }

    # inicializador sem janela preta
    $ps1 = Join-Path $Pasta 'CameraViewer.ps1'
    $vbs = Join-Path $Pasta 'Iniciar.vbs'
    $conteudo = @"
Set sh = CreateObject("WScript.Shell")
sh.Run "powershell.exe -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File ""$ps1""", 0, False
"@
    Set-Content -Path $vbs -Value $conteudo -Encoding ASCII
    Ok 'Iniciar.vbs criado (inicia sem janela de console)'

    # atalhos
    $ws = New-Object -ComObject WScript.Shell
    function Novo-Atalho([string]$destinoPasta, [string]$nomeAtalho) {
        if (-not (Test-Path $destinoPasta)) { return $null }
        $caminho = Join-Path $destinoPasta "$nomeAtalho.lnk"
        if (Test-Path $caminho) { Remove-Item $caminho -Force }
        $lnk = $ws.CreateShortcut($caminho)
        $lnk.TargetPath = "$env:SystemRoot\System32\wscript.exe"
        $lnk.Arguments = "`"$vbs`""
        $lnk.WorkingDirectory = $Pasta
        if (Test-Path $iconeDest) { $lnk.IconLocation = "$iconeDest,0" }
        $lnk.Description = "$NomeApp - todas as cameras em uma janela"
        $lnk.Save()
        return $caminho
    }

    $desktop = [Environment]::GetFolderPath('Desktop')
    $atalho = Novo-Atalho $desktop $NomeApp
    if ($atalho) { Ok "atalho criado: $atalho" } else { Aviso 'nao foi possivel criar o atalho na Area de Trabalho.' }

    if (-not $SemInicializacao) {
        $inicio = [Environment]::GetFolderPath('Startup')
        $atalhoInicio = Novo-Atalho $inicio $NomeApp
        if ($atalhoInicio) { Ok "inicializacao automatica: $atalhoInicio" }
    } else {
        Item 'inicializacao automatica: nao criada (opcao -SemInicializacao)'
    }

    # permissao de firewall para o relÃ© RTSP (a camera e o PC precisam se falar)
    $regra = 'CentralCameras-Relay'
    try {
        $existe = Get-NetFirewallRule -DisplayName $regra -ErrorAction SilentlyContinue
        if (-not $existe) {
            New-NetFirewallRule -DisplayName $regra -Direction Inbound -Action Allow -Program "$env:SystemRoot\System32\WindowsPowerShell\v1.0\powershell.exe" -Profile Private,Domain -ErrorAction Stop | Out-Null
            Ok 'regra de firewall criada (permite o relÃ© RTSP)'
        } else {
            Ok 'regra de firewall ja existe'
        }
    } catch {
        Aviso 'nao foi possivel criar a regra de firewall (pode exigir administrador).'
        Item 'Se as cameras Yoosee nao conectarem, libere o PowerShell no Firewall como rede privada.'
    }
}

# ---------------------------------------------------------------------------
# 5. teste das cameras
# ---------------------------------------------------------------------------
if (-not $SemTeste -and $cameras.Count -gt 0) {
    Titulo 'Testando as cameras'
    if (-not $ffprobeCmd) {
        Aviso 'ffprobe ausente - pulando o teste automatico.'
    } else {
        foreach ($c in $cameras) {
            $alvo = if ($c.relay) { "$($c.relay)" } else { $c.url }
            Item "testando $($c.nome) ($alvo)..."
            $ip = $null
            if ($c.relay) { $ip = "$($c.relay)".Split(':')[0] }
            else {
                if ($c.url -match '@([^:]+):') { $ip = $Matches[1] } elseif ($c.url -match 'rtsp://([^:]+):') { $ip = $Matches[1] }
            }
            if (-not $ip) { Item 'sem IP identificavel - pulando.'; continue }

            $t0 = Get-Date
            $okRede = Test-NetConnection -ComputerName $ip -Port 554 -InformationLevel Quiet -WarningAction SilentlyContinue
            $dt = [Math]::Round(((Get-Date) - $t0).TotalSeconds, 1)
            if ($okRede) {
                Ok "$($c.nome): porta RTSP (554) acessivel em ${dt}s"
            } else {
                Erro "$($c.nome): nao respondeu na porta 554"
                Item 'Confirme: cameras ligadas, mesmo roteador, firewall liberado.'
            }
        }
    }
}

# ---------------------------------------------------------------------------
# 6. relatorio
# ---------------------------------------------------------------------------
Titulo 'Resumo'
if ($falhas.Count -eq 0) {
    Escrever 'Instalacao concluida sem erros.' $corOk
} else {
    Escrever "$($falhas.Count) problema(s) precisam de atencao:" $corErro
    foreach ($f in $falhas) { Escrever "  - $f" $corErro }
}
if ($avisos.Count -gt 0) {
    Escrever ''
    Escrever "$($avisos.Count) aviso(s):" $corAviso
    foreach ($a in $avisos) { Escrever "  - $a" $corAviso }
}

Escrever ''
Escrever ('Para abrir: clique no atalho "{0}" na Area de Trabalho' -f $NomeApp) $corInfo
Escrever 'Para mudar cameras/senhas: edite cameras.json' $corInfo
Escrever 'Para desinstalar: Instalar.bat -Desinstalar' $corInfo
Escrever ''

if ($falhas.Count -gt 0) { exit 1 }
exit 0

