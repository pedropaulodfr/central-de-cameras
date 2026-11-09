<#
    CameraViewer.ps1 - Central de Câmeras

    - Janela única com todas as câmeras (VLC embutido)
    - Relay local para as câmeras cujo firmware recusa autenticação RTSP (Yoosee)
    - Gravar / Foto / Renomear / Mudo / Ampliar / Mover / Tela cheia / Bandeja

    Design system: Ui.cs (tokens, ícones vetoriais e componentes).
    Uso normal: atalho "Central de Câmeras" ou Iniciar-Cameras.cmd
#>
param(
    [string]$Config = "",
    [int]$SelfTestSeconds = 0,
    [string]$Screenshot = "",
    [switch]$AutoRecord,
    [switch]$AutoSnapshot
)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
if (-not $Config) { $Config = Join-Path $root 'cameras.json' }

$tempDir = Join-Path $root 'Temp'
$gravDir = Join-Path $root 'Gravacoes'
$fotoDir = Join-Path $root 'Fotos'
foreach ($d in @($tempDir, $gravDir, $fotoDir)) { New-Item -ItemType Directory -Force -Path $d | Out-Null }
$env:TEMP = $tempDir
$env:TMP = $tempDir

$ffmpeg = (Get-Command ffmpeg -ErrorAction SilentlyContinue).Source

Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing

# ---- dependencias nativas -------------------------------------------------
try {
    Add-Type -TypeDefinition @"
using System;
using System.Windows.Forms;
public class VlcHost : AxHost {
    public VlcHost() : base("9BE31822-FDAD-461B-AD51-BE1D1C159921") { }
    public object Vlc { get { return GetOcx(); } }
}
"@ -ReferencedAssemblies System.Windows.Forms
} catch { }

$relaySource = Join-Path $root 'RtspRelay.cs'
if (Test-Path $relaySource) { try { Add-Type -Path $relaySource } catch { } }

# design system (tokens + componentes)
try {
    Add-Type -Path (Join-Path $root 'Ui.cs') -ReferencedAssemblies System.Windows.Forms, System.Drawing
} catch {
    [System.Windows.Forms.MessageBox]::Show("Não foi possível carregar a interface (Ui.cs).`n`n$($_.Exception.Message)", 'Central de Câmeras') | Out-Null
    exit 1
}

# controle de movimento (PTZ via ONVIF); sem ele o app funciona, so sem o D-pad
$ptzSource = Join-Path $root 'Ptz.cs'
if (Test-Path $ptzSource) { try { Add-Type -Path $ptzSource } catch { Write-Warning "Ptz.cs: $($_.Exception.Message)" } }

try {
    Add-Type -TypeDefinition @"
using System;
using System.Runtime.InteropServices;
public class Identidade {
    [DllImport("shell32.dll", CharSet = CharSet.Unicode)]
    public static extern int SetCurrentProcessExplicitAppUserModelID(string appID);
}
"@
    [Identidade]::SetCurrentProcessExplicitAppUserModelID('Pedro.CentralDeCameras') | Out-Null
} catch { }

[System.Windows.Forms.Application]::EnableVisualStyles()

# ---- tokens (fonte unica: Ui.cs) -----------------------------------------
$corFundo      = [Tema]::Fundo
$corSuperficie = [Tema]::Superficie
$corElevado    = [Tema]::Elevado
$corBorda      = [Tema]::Borda
$corTexto      = [Tema]::Texto
$corFraco      = [Tema]::TextoSuave
$corVerde      = [Tema]::Sucesso
$corAmbar      = [Tema]::Alerta
$corVermelho   = [Tema]::Erro
$corGravando   = [Tema]::Perigo
$corAzul       = [Tema]::Primario
$corAzulClaro  = [Tema]::AcentoClaro

$arquivoIcone = Join-Path $root 'icone.ico'

$script:Dicas = New-Object System.Windows.Forms.ToolTip
$script:Dicas.InitialDelay = 400
[Tema]::EstilizarDica($script:Dicas)

# ---- componentes reutilizaveis -------------------------------------------
function New-Rotulo([string]$texto, [int]$x, [int]$y, [int]$w, [int]$h, $cor, $fonte) {
    $l = New-Object System.Windows.Forms.Label
    $l.Text = $texto
    $l.AutoSize = $false
    $l.UseMnemonic = $false
    $l.Left = $x; $l.Top = $y; $l.Width = $w; $l.Height = $h
    $l.ForeColor = $cor
    $l.BackColor = [System.Drawing.Color]::Transparent
    $l.Font = $fonte
    return $l
}

function New-BotaoUi([string]$texto, [string]$icone, [string]$variante = 'secundario', [string]$nomeAcessivel = '') {
    $b = New-Object BotaoUi
    $b.Text = $texto
    $b.Icone = $icone
    $b.Variante = $variante
    $b.Margin = New-Object System.Windows.Forms.Padding(4, 0, 4, 0)
    $b.AccessibleName = if ($nomeAcessivel) { $nomeAcessivel } else { $texto }
    $b.AutoSize = $true
    return $b
}

function New-Dialogo([string]$titulo, [int]$largura, [int]$altura) {
    $d = New-Object System.Windows.Forms.Form
    $d.Text = $titulo
    $d.ClientSize = New-Object System.Drawing.Size($largura, $altura)
    $d.StartPosition = 'CenterParent'
    $d.FormBorderStyle = 'FixedDialog'
    $d.MaximizeBox = $false
    $d.MinimizeBox = $false
    $d.ShowInTaskbar = $false
    $d.KeyPreview = $true
    $d.BackColor = $corSuperficie
    $d.ForeColor = $corTexto
    $d.Font = [Tema]::Fonte(10, $false)
    if (Test-Path $arquivoIcone) { try { $d.Icon = New-Object System.Drawing.Icon($arquivoIcone) } catch { } }
    [Tema]::EscurecerBarra($d)
    return $d
}

# Aviso modal no tema do app (substitui MessageBox nativo)
function Show-Aviso([string]$titulo, [string]$texto, [string]$nivel = 'info', $dono = $null) {
    $d = New-Dialogo 'Central de Câmeras' 480 200
    if (-not $dono) { $d.StartPosition = 'CenterScreen' }
    $cor = switch ($nivel) { 'erro' { $corVermelho } 'aviso' { $corAmbar } 'ok' { $corVerde } default { $corAzulClaro } }

    $faixa = New-Object System.Windows.Forms.Panel
    $faixa.Dock = 'Left'; $faixa.Width = 4; $faixa.BackColor = $cor

    $t = New-Rotulo $titulo 28 24 428 28 $corTexto ([Tema]::Fonte(13, $true))
    $m = New-Rotulo $texto 28 60 428 70 $corFraco ([Tema]::Fonte(10, $false))

    $ok = New-Object BotaoUi
    $ok.Text = 'OK'; $ok.Variante = 'primario'; $ok.AccessibleName = 'OK'
    $ok.Left = 480 - 28 - 104; $ok.Top = 200 - 24 - 44; $ok.Width = 104
    $ok.DialogResult = 'OK'

    $d.Controls.AddRange(@($faixa, $t, $m, $ok))
    $d.AcceptButton = $ok
    $d.CancelButton = $ok
    if ($dono) { [void]$d.ShowDialog($dono) } else { [void]$d.ShowDialog() }
    $d.Dispose()
}

# ---- instancia unica ------------------------------------------------------
$criadoNovo = $false
$mutex = New-Object System.Threading.Mutex($true, 'Local\CentralDeCamerasApp', [ref]$criadoNovo)
if (-not $criadoNovo) {
    Show-Aviso 'O programa já está aberto' 'A Central de Câmeras já está em execução. Procure o ícone na bandeja do sistema (perto do relógio) e dê dois cliques para abrir a janela.' 'info'
    exit
}

# ---- pre-requisitos -------------------------------------------------------
$vlcOk = (Test-Path 'Registry::HKEY_CLASSES_ROOT\CLSID\{9BE31822-FDAD-461B-AD51-BE1D1C159921}') -and ($null -ne ('VlcHost' -as [type]))
if (-not $vlcOk) {
    Show-Aviso 'VLC não encontrado' 'A Central de Câmeras usa o VLC (versão 64 bits) para exibir o vídeo. Instale o VLC media player e abra o programa novamente.' 'erro'
    try { $mutex.ReleaseMutex() } catch { }
    exit 1
}

try {
    # atribuir primeiro: no PS 5.1 @(... | ConvertFrom-Json) aninharia o array
    $cams = Get-Content $Config -Raw | ConvertFrom-Json
    $cams = @($cams)
    if ($cams.Count -eq 0) { throw 'O arquivo não tem nenhuma câmera.' }
} catch {
    Show-Aviso 'Configuração inválida' "Não foi possível ler as câmeras em:`n$Config`n`n$($_.Exception.Message)" 'erro'
    try { $mutex.ReleaseMutex() } catch { }
    exit 1
}

$script:Relays = @()
$script:Recording = @{}

# ---- janela ---------------------------------------------------------------
$form = New-Object System.Windows.Forms.Form
$form.Text = 'Central de Câmeras'
$area = [System.Windows.Forms.Screen]::PrimaryScreen.WorkingArea
$form.Width = [Math]::Min(1320, ($area.Width - 30))
$form.Height = [Math]::Min(860, ($area.Height - 30))
$form.MinimumSize = New-Object System.Drawing.Size(900, 560)
$form.BackColor = $corFundo
$form.ForeColor = $corTexto
$form.StartPosition = 'CenterScreen'
$form.KeyPreview = $true
$form.Font = [Tema]::Fonte(10, $false)
[Tema]::EscurecerBarra($form)

if (Test-Path $arquivoIcone) {
    try { $form.Icon = New-Object System.Drawing.Icon($arquivoIcone) } catch { }
}

# ---- barra superior: marca + resumo | acoes -------------------------------
$topo = New-Object System.Windows.Forms.Panel
$topo.Dock = 'Top'
$topo.Height = 64
$topo.BackColor = $corSuperficie

# divisor de 1 px abaixo da barra (controle proprio: filhos nao o cobrem)
$divisorTopo = New-Object System.Windows.Forms.Panel
$divisorTopo.Dock = 'Top'
$divisorTopo.Height = 1
$divisorTopo.BackColor = $corBorda

$marca = New-Object System.Windows.Forms.Panel
$marca.Left = 20; $marca.Top = 14; $marca.Width = 36; $marca.Height = 36
$marca.BackColor = $corSuperficie
$marca.Add_Paint({
    param($s, $e)
    $g = $e.Graphics
    $g.SmoothingMode = 'AntiAlias'
    $caminho = [Tema]::Retangulo((New-Object System.Drawing.RectangleF(0, 0, $s.Width, $s.Height)), 9)
    $pincel = New-Object System.Drawing.SolidBrush($corAzul)
    $g.FillPath($pincel, $caminho)
    $pincel.Dispose(); $caminho.Dispose()
    [Icones]::Desenhar($g, 'camera', (New-Object System.Drawing.RectangleF(7, 7, 22, 22)), [System.Drawing.Color]::White)
})

$lblTitulo = New-Rotulo 'Central de Câmeras' 68 11 320 26 $corTexto ([Tema]::Fonte(13, $true))
$lblSub = New-Rotulo '' 69 37 320 18 $corFraco ([Tema]::Fonte(9, $false))

$acoes = New-Object System.Windows.Forms.FlowLayoutPanel
$acoes.Dock = 'Right'
$acoes.AutoSize = $true
$acoes.AutoSizeMode = 'GrowAndShrink'
$acoes.WrapContents = $false
$acoes.FlowDirection = 'LeftToRight'
$acoes.Padding = New-Object System.Windows.Forms.Padding(0, 10, 16, 10)
$acoes.BackColor = $corSuperficie

$btnRec        = New-BotaoUi 'Gravar' 'gravar' 'primario' 'Gravar todas as câmeras'
$btnFoto       = New-BotaoUi 'Foto' 'foto' 'secundario' 'Tirar foto de todas as câmeras'
$btnAtualizar  = New-BotaoUi 'Reconectar' 'reconectar' 'secundario' 'Reconectar todas as câmeras'
$btnRenomear   = New-BotaoUi 'Renomear' 'renomear' 'secundario' 'Renomear câmeras'
$btnPasta      = New-BotaoUi 'Gravações' 'pasta' 'secundario' 'Abrir pasta de gravações'
$btnTelaCheia  = New-BotaoUi '' 'telacheia' 'secundario' 'Tela cheia'
$btnTelaCheia.SoIcone = $true
$btnTelaCheia.Margin = New-Object System.Windows.Forms.Padding(12, 0, 4, 0)

$acoes.Controls.AddRange(@($btnRec, $btnFoto, $btnAtualizar, $btnRenomear, $btnPasta, $btnTelaCheia))
$topo.Controls.AddRange(@($marca, $lblTitulo, $lblSub, $acoes))

$script:Dicas.SetToolTip($btnRec, 'Grava todas as câmeras em arquivos .ts (clique de novo para parar)')
$script:Dicas.SetToolTip($btnFoto, 'Salva uma foto de cada câmera na pasta Fotos')
$script:Dicas.SetToolTip($btnAtualizar, 'Reconecta todas as câmeras (F5)')
$script:Dicas.SetToolTip($btnRenomear, 'Altera o nome exibido de cada câmera')
$script:Dicas.SetToolTip($btnPasta, 'Abre a pasta de gravações')
$script:Dicas.SetToolTip($btnTelaCheia, 'Programa em tela cheia (F11). Esc para sair')

# ---- barra de status ------------------------------------------------------
$rodapeApp = New-Object System.Windows.Forms.Panel
$rodapeApp.Dock = 'Bottom'
$rodapeApp.Height = 36
$rodapeApp.BackColor = $corSuperficie
$rodapeApp.Add_Paint({
    param($s, $e)
    $pen = New-Object System.Drawing.Pen($corBorda, 1)
    $e.Graphics.DrawLine($pen, 0, 0, $s.Width, 0)
    $pen.Dispose()
})

$lblMsgPonto = New-Rotulo ([string][char]0x25CF) 0 0 32 36 $corFraco ([Tema]::Fonte(9, $false))
$lblMsgPonto.Dock = 'Left'; $lblMsgPonto.Width = 34; $lblMsgPonto.TextAlign = 'MiddleRight'
$lblMsg = New-Rotulo 'Pronto' 0 0 100 36 $corFraco ([Tema]::Fonte(9, $false))
$lblMsg.Dock = 'Fill'; $lblMsg.TextAlign = 'MiddleLeft'; $lblMsg.AutoEllipsis = $true
$lblMsg.Padding = New-Object System.Windows.Forms.Padding(6, 0, 0, 0)
$lblAtalhos = New-Rotulo 'F5 Reconectar     F11 Tela cheia     1-9 Ampliar     Esc Voltar' 0 0 440 36 $corFraco ([Tema]::Fonte(9, $false))
$lblAtalhos.Dock = 'Right'; $lblAtalhos.TextAlign = 'MiddleRight'
$lblRelogio = New-Rotulo '' 0 0 84 36 $corFraco ([Tema]::Fonte(9.5, $true))
$lblRelogio.Dock = 'Right'; $lblRelogio.TextAlign = 'MiddleRight'
$lblRelogio.Padding = New-Object System.Windows.Forms.Padding(0, 0, 18, 0)

# ordem: o ultimo adicionado encosta primeiro na borda; o Fill vai para a frente
$rodapeApp.Controls.AddRange(@($lblMsg, $lblMsgPonto, $lblAtalhos, $lblRelogio))
$lblMsg.BringToFront()

$script:StatusAte = [datetime]::MinValue

function Get-MsgPadrao {
    if ($script:Recording.Count -gt 0) { return @("Gravando em $gravDir", $corGravando) }
    return @('Pronto', $corFraco)
}

function Set-Status([string]$texto, [string]$nivel = 'info') {
    $cor = switch ($nivel) { 'ok' { $corVerde } 'aviso' { $corAmbar } 'erro' { $corVermelho } default { $corAzulClaro } }
    $lblMsgPonto.ForeColor = $cor
    $lblMsg.ForeColor = $corTexto
    $lblMsg.Text = $texto
    $script:StatusAte = (Get-Date).AddSeconds(10)
}

function Reset-Status {
    $m = Get-MsgPadrao
    $lblMsg.Text = $m[0]
    $lblMsg.ForeColor = $corFraco
    $lblMsgPonto.ForeColor = $m[1]
    $script:StatusAte = [datetime]::MinValue
}

# ordem: o topo e adicionado por ultimo para ser posicionado primeiro
$form.Controls.Add($rodapeApp)
$form.Controls.Add($divisorTopo)
$form.Controls.Add($topo)

# ---- grade ----------------------------------------------------------------
$grid = New-Object System.Windows.Forms.TableLayoutPanel
$grid.Dock = 'Fill'
$grid.Padding = New-Object System.Windows.Forms.Padding(16, 12, 16, 16)
$grid.ColumnCount = 2
$grid.RowCount = [Math]::Max(1, [Math]::Ceiling($cams.Count / 2))
$grid.BackColor = $corFundo
for ($i = 0; $i -lt $grid.ColumnCount; $i++) {
    $grid.ColumnStyles.Add((New-Object System.Windows.Forms.ColumnStyle([System.Windows.Forms.SizeType]::Percent, 50))) | Out-Null
}
for ($i = 0; $i -lt $grid.RowCount; $i++) {
    $grid.RowStyles.Add((New-Object System.Windows.Forms.RowStyle([System.Windows.Forms.SizeType]::Percent, (100 / $grid.RowCount)))) | Out-Null
}
$form.Controls.Add($grid)
$grid.BringToFront()

$players = New-Object System.Collections.ArrayList
$script:Focada = $null     # camera ampliada (ou $null)
$script:Cheia = $false     # programa em tela cheia

$indice = 0
foreach ($cam in $cams) {
    $urlFinal = $cam.url
    $ehRelay = [bool]$cam.relay
    $portaRelay = 0

    if ($ehRelay) {
        $partes = "$($cam.relay)".Split(':')
        $destino = $partes[0]
        $portaRemota = if ($partes.Count -gt 1) { [int]$partes[1] } else { 554 }
        try {
            # porta 0 = automatica: nunca conflita com outra abertura do app
            $r = New-Object RtspRelay($destino, $portaRemota, 0)
            $r.Start()
            $script:Relays += $r
            $portaRelay = $r.Port
            $urlFinal = "rtsp://127.0.0.1:$portaRelay/onvif1"
        } catch {
            $ehRelay = $false
            Write-Warning "Relay $destino falhou: $($_.Exception.Message)"
        }
    }

    # PTZ: "ptz": "ip:porta" no cameras.json, ou (cameras sem relay) o proprio IP da URL na porta 8899.
    # Usuario/senha vem da URL RTSP; ptzUsuario/ptzSenha sobrescrevem.
    $ptz = $null
    if ('OnvifPtz' -as [type]) {
        try {
            $hostPtz = $null; $portaPtz = 8899; $usrPtz = ''; $senhaPtz = ''
            $mUrl = [regex]::Match("$($cam.url)", '^rtsp://(?:([^:@/]*)(?::([^@/]*))?@)?([^:/]+)')
            if ($cam.ptz) {
                $pp = "$($cam.ptz)".Split(':')
                $hostPtz = $pp[0]
                if ($pp.Count -gt 1) { $portaPtz = [int]$pp[1] }
            } elseif (-not $cam.relay -and $mUrl.Success) {
                $hostPtz = $mUrl.Groups[3].Value
            }
            if ($mUrl.Success) { $usrPtz = $mUrl.Groups[1].Value; $senhaPtz = $mUrl.Groups[2].Value }
            if ($cam.ptzUsuario) { $usrPtz = "$($cam.ptzUsuario)" }
            if ($cam.ptzSenha) { $senhaPtz = "$($cam.ptzSenha)" }
            if ($hostPtz) { $ptz = New-Object OnvifPtz($hostPtz, $portaPtz, $usrPtz, $senhaPtz) }
        } catch { $ptz = $null }
    }

    $tile = New-Object Cartao
    $tile.Dock = 'Fill'
    $tile.Margin = New-Object System.Windows.Forms.Padding(8)

    $vlcCtl = New-Object VlcHost
    $vlcCtl.Dock = 'Fill'
    $vlcCtl.BackColor = [System.Drawing.Color]::Black
    $tile.Controls.Add($vlcCtl)

    # rodape do tile: nome | estado | acoes
    $rodape = New-Object System.Windows.Forms.Panel
    $rodape.Dock = 'Bottom'
    $rodape.Height = 52
    $rodape.BackColor = $corSuperficie

    $nome = New-Rotulo "$($cam.nome)" 0 0 100 52 $corTexto ([Tema]::Fonte(10.5, $true))
    $nome.Dock = 'Fill'
    $nome.AutoEllipsis = $true
    $nome.TextAlign = 'MiddleLeft'
    $nome.Padding = New-Object System.Windows.Forms.Padding(14, 0, 0, 0)

    $estado = New-Object Pilula
    $estado.Dock = 'Right'
    $estado.Width = 124
    $estado.Text = 'aguardando'
    $estado.AccessibleName = "Estado da câmera $($cam.nome)"

    $painelBtn = New-Object System.Windows.Forms.Panel
    $painelBtn.Dock = 'Right'
    $painelBtn.Width = 184
    $painelBtn.BackColor = $corSuperficie

    $btnMudo = New-Object BotaoUi
    $btnVirar = New-Object BotaoUi
    $btnAmpliar = New-Object BotaoUi
    $btnMover = New-Object BotaoUi
    $x = 0
    foreach ($b in @($btnMudo, $btnVirar, $btnAmpliar, $btnMover)) {
        $b.Variante = 'fantasma'
        $b.SoIcone = $true
        $b.AutoSize = $false
        $b.Width = 40; $b.Height = 40
        $b.Left = $x; $b.Top = 6
        $x += 44
        $painelBtn.Controls.Add($b)
    }
    $btnMudo.Icone = 'som'
    $btnVirar.Icone = 'virar'
    $btnAmpliar.Icone = 'ampliar'
    $btnMover.Icone = 'reordenar'
    if (-not $ptz) { $btnVirar.CorIcone = [Tema]::TextoOff }

    # ordem de adicao: o ultimo adicionado encosta primeiro na borda
    $rodape.Controls.AddRange(@($nome, $estado, $painelBtn))
    $nome.BringToFront()
    $tile.Controls.Add($rodape)
    $rodape.BringToFront()

    $grid.Controls.Add($tile)

    $obj = [pscustomobject]@{
        Id           = $indice
        Nome         = $cam.nome
        Url          = $urlFinal
        Tcp          = [bool]$cam.tcp
        Relay        = $ehRelay
        RecTcp       = [bool]$cam.recTcp
        Mudo         = [bool]$cam.mudo
        MuteAplicado = $null
        Ctl          = $vlcCtl
        Tile         = $tile
        Label        = $nome
        Estado       = $estado
        Rodape       = $rodape
        BtnMudo      = $btnMudo
        BtnVirar     = $btnVirar
        BtnAmpliar   = $btnAmpliar
        BtnMover     = $btnMover
        Ptz          = $ptz
        PtzInvX      = [bool]$cam.ptzInverterX
        PtzInvY      = [bool]$cam.ptzInverterY
        PtzRepetir   = [bool]$cam.ptzRepetir
    }
    [void]$players.Add($obj)

    # Tag leva o player para o handler (variavel de loop nao serve em scriptblock)
    foreach ($c in @($btnMudo, $btnVirar, $btnAmpliar, $btnMover, $nome, $tile)) { $c.Tag = $obj }
    $indice++
}

# ---- funcoes --------------------------------------------------------------
function Update-Borda($p) {
    if ($script:Recording.ContainsKey($p.Nome)) { $p.Tile.CorBorda = $corGravando; $p.Tile.Espessura = 2 }
    elseif ($script:Focada -eq $p) { $p.Tile.CorBorda = $corAzulClaro; $p.Tile.Espessura = 2 }
    else { $p.Tile.CorBorda = $corBorda; $p.Tile.Espessura = 1 }
}

function Set-Estado($p, [string]$texto, $cor) {
    $p.Estado.Text = $texto
    $p.Estado.CorPonto = $cor
    Update-Borda $p
}

function Start-Player($p) {
    try {
        $v = $p.Ctl.Vlc
        $opcoes = if ($p.Tcp) { ':rtsp-tcp' } else { $null }
        $v.playlist.items.clear() | Out-Null
        $v.playlist.add($p.Url, $p.Nome, $opcoes) | Out-Null
        $v.playlist.play() | Out-Null
        $p.MuteAplicado = $null
        Set-Mudo $p $p.Mudo $false
        Set-Estado $p 'conectando' $corAmbar
    } catch {
        Set-Estado $p 'erro ao abrir' $corVermelho
    }
}

# ---- audio ----------------------------------------------------------------
function Show-BotaoMudo($p) {
    if ($p.Mudo) {
        $p.BtnMudo.Icone = 'mudo'
        $p.BtnMudo.CorIcone = $corVermelho
        $p.BtnMudo.AccessibleName = "Ativar o som da câmera $($p.Nome) (está sem som)"
        $script:Dicas.SetToolTip($p.BtnMudo, 'Sem som. Clique para ativar o áudio')
    } else {
        $p.BtnMudo.Icone = 'som'
        $p.BtnMudo.CorIcone = [System.Drawing.Color]::Empty
        $p.BtnMudo.AccessibleName = "Silenciar a câmera $($p.Nome)"
        $script:Dicas.SetToolTip($p.BtnMudo, 'Silenciar o áudio desta câmera')
    }
}

function Set-Mudo($p, [bool]$mudo, [bool]$salvar = $true) {
    $p.Mudo = $mudo
    Show-BotaoMudo $p
    try {
        $p.Ctl.Vlc.audio.mute = $mudo
        $p.MuteAplicado = $mudo
    } catch { }
    if ($salvar) {
        if (Save-Config) {
            if ($mudo) { Set-Status "Câmera '$($p.Nome)' silenciada." 'info' }
            else { Set-Status "Som da câmera '$($p.Nome)' ativado." 'info' }
        }
    }
}

# ---- configuracao ---------------------------------------------------------
# Grava nome, ordem e mudo. Id = posicao do item em cameras.json (atualizado ao salvar).
function Save-Config {
    try {
        $json = Get-Content $Config -Raw | ConvertFrom-Json
        $json = @($json)
        $novo = @()
        foreach ($p in $players) {
            $o = $json[$p.Id]
            $o.nome = $p.Nome
            $o | Add-Member -NotePropertyName mudo -NotePropertyValue ([bool]$p.Mudo) -Force
            $novo += $o
        }
        ConvertTo-Json -InputObject $novo -Depth 6 | Set-Content $Config -Encoding UTF8
        $k = 0
        foreach ($p in $players) { $p.Id = $k; $k++ }
        return $true
    } catch {
        Set-Status "Não foi possível salvar o cameras.json: $($_.Exception.Message)" 'erro'
        return $false
    }
}

# ---- posicao / ampliar ----------------------------------------------------
function Update-Layout {
    $n = $players.Count
    $grid.SuspendLayout()
    foreach ($p in $players) {
        $grid.SetColumnSpan($p.Tile, 1)
        $grid.SetRowSpan($p.Tile, 1)
    }
    if ($script:Focada) {
        foreach ($p in $players) { $p.Tile.Visible = ($p -eq $script:Focada) }
        $f = $script:Focada.Tile
        $grid.SetCellPosition($f, (New-Object System.Windows.Forms.TableLayoutPanelCellPosition(0, 0)))
        $grid.SetColumnSpan($f, 2)
        $grid.SetRowSpan($f, $grid.RowCount)
    } else {
        for ($i = 0; $i -lt $n; $i++) {
            $t = $players[$i].Tile
            $t.Visible = $true
            $pos = New-Object System.Windows.Forms.TableLayoutPanelCellPosition(($i % 2), [int][Math]::Floor($i / 2))
            $grid.SetCellPosition($t, $pos)
        }
        if ($n % 2 -eq 1) { $grid.SetColumnSpan($players[$n - 1].Tile, 2) }
    }
    foreach ($p in $players) {
        if ($p -eq $script:Focada) {
            $p.BtnAmpliar.Icone = 'reduzir'
            $p.BtnAmpliar.AccessibleName = "Voltar à grade (câmera $($p.Nome))"
            $script:Dicas.SetToolTip($p.BtnAmpliar, 'Voltar para todas as câmeras (Esc)')
        } else {
            $p.BtnAmpliar.Icone = 'ampliar'
            $p.BtnAmpliar.AccessibleName = "Ampliar a câmera $($p.Nome)"
            $script:Dicas.SetToolTip($p.BtnAmpliar, 'Ampliar só esta câmera (ou dê duplo clique)')
        }
        $script:Dicas.SetToolTip($p.BtnMover, 'Reordenar: mudar a posição desta câmera na grade')
        $p.BtnMover.AccessibleName = "Reordenar a câmera $($p.Nome) na grade"
        if ($p.Ptz) { $script:Dicas.SetToolTip($p.BtnVirar, 'Virar a câmera (controle direcional)') }
        else { $script:Dicas.SetToolTip($p.BtnVirar, 'Esta câmera não permite controle de direção pelo programa') }
        $p.BtnVirar.AccessibleName = "Virar a câmera $($p.Nome)"
        $p.BtnMover.Enabled = ($null -eq $script:Focada)
        Update-Borda $p
    }
    $grid.ResumeLayout($true)
}

function Toggle-Foco($p) {
    if ($script:Focada -eq $p) { $script:Focada = $null } else { $script:Focada = $p }
    Update-Layout
    if ($script:Focada) { Set-Status "Câmera '$($p.Nome)' ampliada. Esc para voltar." 'info' }
}

function Move-Camera($p, [int]$destino) {
    $i = $players.IndexOf($p)
    if ($destino -lt 0 -or $destino -ge $players.Count -or $destino -eq $i) { return }
    $outro = $players[$destino]
    $players[$destino] = $p
    $players[$i] = $outro
    Update-Layout
    if (Save-Config) { Set-Status "Câmera '$($p.Nome)' movida. Posição salva." 'ok' }
}

# ---- virar a camera (D-pad / PTZ) ----------------------------------------
$script:PtzForm = $null
$script:PtzTimer = $null
$script:PtzAtual = $null
$script:PtzP = $null
$script:PtzVel = 0.3

function Close-Ptz {
    if ($script:PtzTimer) { $script:PtzTimer.Stop() }
    if ($script:PtzRepTimer) { $script:PtzRepTimer.Stop() }
    $script:PtzUltimo = $null
    if ($script:PtzAtual) { try { $script:PtzAtual.Parar() } catch { } }
    if ($script:PtzForm) {
        $f = $script:PtzForm
        $script:PtzForm = $null
        try { $f.Close() } catch { }
    }
}

function Show-Ptz($p) {
    Close-Ptz
    if (-not $p.Ptz) {
        Set-Status "A câmera '$($p.Nome)' não permite controle de direção pelo programa (sem ONVIF)." 'aviso'
        return
    }
    $script:PtzAtual = $p.Ptz
    $script:PtzP = $p
    $p.Ptz.Erro = ''
    $p.Ptz.ConectarAsync()

    $f = New-Object System.Windows.Forms.Form
    $f.FormBorderStyle = 'None'
    $f.ShowInTaskbar = $false
    $f.StartPosition = 'Manual'
    $f.BackColor = $corSuperficie
    $f.KeyPreview = $true
    $f.ClientSize = New-Object System.Drawing.Size(248, 372)
    $f.Font = [Tema]::Fonte(10, $false)
    $f.Add_Paint({
        param($s, $e)
        $pen = New-Object System.Drawing.Pen([Tema]::BordaForte, 1)
        $e.Graphics.DrawRectangle($pen, 0, 0, ($s.Width - 1), ($s.Height - 1))
        $pen.Dispose()
    })

    $titulo = New-Rotulo 'Virar câmera' 20 16 208 24 $corTexto ([Tema]::Fonte(11.5, $true))
    $sub = New-Rotulo $p.Nome 20 40 208 20 $corFraco ([Tema]::Fonte(9, $false))
    $sub.AutoEllipsis = $true

    $dpad = New-Object DPad
    $dpad.Left = 36; $dpad.Top = 72
    $dpad.Enabled = $false
    $dpad.AccessibleName = "Controle direcional da câmera $($p.Nome)"
    $script:Dicas.SetToolTip($dpad, 'Segure uma direção para virar. O quadrado central para.')

    $lblVel = New-Rotulo 'Velocidade' 20 262 208 18 $corFraco ([Tema]::Fonte(9, $true))
    $botoesVel = @()
    $x = 20
    foreach ($def in @(@('Lenta', 0.15), @('Normal', 0.3), @('Rápida', 0.6))) {
        $b = New-Object BotaoUi
        $b.Text = $def[0]; $b.Tag = [double]$def[1]
        $b.Left = $x; $b.Top = 284; $b.Width = 64; $b.Height = 36
        $b.Font = [Tema]::Fonte(9, $true)
        $b.AccessibleName = "Velocidade $($def[0])"
        $b.Variante = if ([Math]::Abs($def[1] - $script:PtzVel) -lt 0.001) { 'primario' } else { 'secundario' }
        $b.Add_Click({
            param($s, $e)
            $script:PtzVel = [double]$s.Tag
            foreach ($o in $script:PtzBotoesVel) { $o.Variante = if ($o -eq $s) { 'primario' } else { 'secundario' } }
        })
        $botoesVel += $b
        $x += 72
    }
    $script:PtzBotoesVel = $botoesVel

    $lblMsg2 = New-Rotulo 'Conectando à câmera…' 20 330 208 36 $corFraco ([Tema]::Fonte(9, $false))

    $dpad.Add_Pressionado({
        param($d)
        $o = $script:PtzAtual; $q = $script:PtzP
        if (-not $o) { return }
        if ($d -eq 'centro') { $o.Parar(); return }
        $v = [double]$script:PtzVel
        $vx = 0.0; $vy = 0.0
        switch ($d) { 'esq' { $vx = -$v } 'dir' { $vx = $v } 'cima' { $vy = $v } 'baixo' { $vy = -$v } }
        if ($q.PtzInvX) { $vx = -$vx }
        if ($q.PtzInvY) { $vy = -$vy }
        $o.Mover([single]$vx, [single]$vy)
        # cameras que andam em passos (Yoosee): repete enquanto a direcao esta pressionada
        if ($q.PtzRepetir) { $script:PtzUltimo = @($vx, $vy); $script:PtzRepTimer.Start() }
    })
    $dpad.Add_Solto({
        $script:PtzUltimo = $null
        if ($script:PtzRepTimer) { $script:PtzRepTimer.Stop() }
        if ($script:PtzAtual) { $script:PtzAtual.Parar() }
    })

    $script:PtzUltimo = $null
    $script:PtzRepTimer = New-Object System.Windows.Forms.Timer
    $script:PtzRepTimer.Interval = 350
    $script:PtzRepTimer.Add_Tick({
        if ($script:PtzUltimo -and $script:PtzAtual) { $script:PtzAtual.Mover([single]$script:PtzUltimo[0], [single]$script:PtzUltimo[1]) }
    })

    $f.Controls.AddRange(@($titulo, $sub, $dpad, $lblVel, $lblMsg2))
    $f.Controls.AddRange($botoesVel)

    # estado da conexao -> habilita o D-pad / mostra o erro
    $script:PtzDpad = $dpad
    $script:PtzLbl = $lblMsg2
    $script:PtzTimer = New-Object System.Windows.Forms.Timer
    $script:PtzTimer.Interval = 300
    $script:PtzTimer.Add_Tick({
        $o = $script:PtzAtual
        if (-not $o -or -not $script:PtzDpad) { return }
        switch ($o.Estado) {
            'pronto' {
                $script:PtzDpad.Enabled = $true
                if ($o.Erro) { $script:PtzLbl.ForeColor = $corVermelho; $script:PtzLbl.Text = "Falha no último comando: $($o.Erro)" }
                else { $script:PtzLbl.ForeColor = $corFraco; $script:PtzLbl.Text = 'Segure uma direção para virar. As setas do teclado também funcionam.' }
            }
            'indisponivel' {
                $script:PtzDpad.Enabled = $false
                $script:PtzLbl.ForeColor = $corVermelho
                $script:PtzLbl.Text = "Não foi possível controlar a câmera: $($o.Erro)"
            }
            default { $script:PtzDpad.Enabled = $false; $script:PtzLbl.ForeColor = $corFraco; $script:PtzLbl.Text = 'Conectando à câmera…' }
        }
    })
    $script:PtzTimer.Start()

    $f.Add_Deactivate({ Close-Ptz })
    $f.Add_KeyDown({ param($s, $e) if ($e.KeyCode -eq 'Escape') { Close-Ptz } })

    # acima do botao Virar (o rodape do tile fica na base da tela), alinhado a direita
    $pt = $p.BtnVirar.PointToScreen((New-Object System.Drawing.Point(0, 0)))
    $wa = [System.Windows.Forms.Screen]::FromControl($form).WorkingArea
    $px = [Math]::Max($wa.Left, [Math]::Min(($pt.X + $p.BtnVirar.Width - $f.Width), ($wa.Right - $f.Width)))
    $py = $pt.Y - $f.Height - 8
    if ($py -lt $wa.Top) { $py = [Math]::Min(($pt.Y + $p.BtnVirar.Height + 8), ($wa.Bottom - $f.Height)) }
    $f.Location = New-Object System.Drawing.Point($px, $py)

    $script:PtzForm = $f
    $f.Show($form)
    $f.Activate()
    $dpad.Focus()
}

# ---- tela cheia do programa ----------------------------------------------
function Revive-Players {
    # recriar a janela (mudar a borda) pode derrubar o ActiveX do VLC
    if ($script:tRevive) { $script:tRevive.Stop() }
    $script:tRevive = New-Object System.Windows.Forms.Timer
    $script:tRevive.Interval = 1500
    $script:tRevive.Add_Tick({
        $script:tRevive.Stop()
        foreach ($p in $players) {
            if ($script:Recording.ContainsKey($p.Nome)) { continue }
            $st = -1
            try { $st = [int]$p.Ctl.Vlc.input.state } catch { $st = -1 }
            if ($st -notin 1, 2, 3, 4) { Start-Player $p }
        }
    })
    $script:tRevive.Start()
}

function Toggle-TelaCheia {
    if (-not $script:Cheia) {
        $script:EstadoAnt = $form.WindowState
        $script:BoundsAnt = $form.Bounds
        $form.WindowState = 'Normal'
        $form.FormBorderStyle = 'None'
        $form.Bounds = [System.Windows.Forms.Screen]::FromControl($form).Bounds
        $topo.Visible = $false
        $divisorTopo.Visible = $false
        $script:Cheia = $true
        $btnTelaCheia.Icone = 'sairtelacheia'
        $btnTelaCheia.AccessibleName = 'Sair da tela cheia'
        Set-Status 'Tela cheia. Pressione Esc ou F11 para sair.' 'info'
    } else {
        $form.FormBorderStyle = 'Sizable'
        $form.Bounds = $script:BoundsAnt
        $form.WindowState = $script:EstadoAnt
        $topo.Visible = $true
        $divisorTopo.Visible = $true
        $script:Cheia = $false
        $btnTelaCheia.Icone = 'telacheia'
        $btnTelaCheia.AccessibleName = 'Tela cheia'
        Reset-Status
    }
    Revive-Players
}

# em janelas estreitas, botoes secundarios viram so icone (a dica mostra o nome)
function Update-Compacto {
    $so = ($form.ClientSize.Width -lt 1180)
    foreach ($b in @($btnFoto, $btnAtualizar, $btnRenomear, $btnPasta)) { $b.SoIcone = $so }
    $lblAtalhos.Visible = ($form.ClientSize.Width -ge 1000)
}

function Update-Resumo {
    $n = $players.Count
    $vivas = @($players | Where-Object { $_.Estado.Text -in 'ao vivo', 'gravando' }).Count
    $txt = if ($n -eq 1) { '1 câmera' } else { "$n câmeras" }
    $txt += "  ·  $vivas ao vivo"
    if ($script:Recording.Count -gt 0) { $txt += '  ·  gravando' }
    $lblSub.Text = $txt
    $lblRelogio.Text = (Get-Date).ToString('HH:mm')
}

# ---- gravacao -------------------------------------------------------------
function Start-Gravador($info) {
    $transporte = if ($info.Tcp) { 'tcp' } else { 'udp' }
    $argumentos = @(
        '-hide_banner', '-v', 'error',
        '-rtsp_transport', $transporte,
        '-i', $info.Url,
        '-c', 'copy', '-y', $info.Arquivo
    )
    $info.Proc = Start-Process -FilePath $ffmpeg -ArgumentList $argumentos -WindowStyle Hidden -PassThru
    $info.Inicio = Get-Date
    $info.Pausa = $null
}

function Start-Recording($p) {
    if (-not $ffmpeg) { Set-Status 'ffmpeg não encontrado. Instale o ffmpeg para gravar.' 'erro'; return }
    if ($script:Recording.ContainsKey($p.Nome)) { return }

    $seguro = ($p.Nome -replace '[^\w\-]', '_')
    $arquivo = Join-Path $gravDir ("{0}_{1:yyyy-MM-dd_HH-mm-ss}.ts" -f $seguro, (Get-Date))

    # firmware das Yoosee aceita uma sessao por vez: pausa a imagem antes
    if ($p.Relay) {
        try { $p.Ctl.Vlc.playlist.stop() } catch { }
        Start-Sleep -Milliseconds 1500
    }

    $info = [pscustomobject]@{
        Url        = $p.Url
        Tcp        = $p.RecTcp
        Arquivo    = $arquivo
        Proc       = $null
        Inicio     = $null
        Pausa      = $null
        Tentativas = 1
    }
    Start-Gravador $info
    $script:Recording[$p.Nome] = $info

    Set-Estado $p 'gravando' $corVermelho
}

function Watch-Recording {
    if (-not $script:Recording) { return }
    $agora = Get-Date
    foreach ($nome in @($script:Recording.Keys)) {
        $info = $script:Recording[$nome]
        $p = $players | Where-Object { $_.Nome -eq $nome } | Select-Object -First 1
        if (-not $p) { continue }

        if ($info.Pausa) {
            if ($agora -ge $info.Pausa) {
                Start-Gravador $info
                Set-Estado $p 'gravando' $corVermelho
            }
            continue
        }

        if (-not $info.Proc) { continue }
        $passou = ($agora - $info.Inicio).TotalSeconds
        $parado = $info.Proc.HasExited
        $tam = 0
        if (Test-Path $info.Arquivo) { $tam = (Get-Item $info.Arquivo).Length }

        $falhou = ($parado -and $tam -lt 150000) -or ($passou -ge 12 -and $tam -lt 80000)
        if ($falhou -and $info.Tentativas -lt 8) {
            try { if (-not $info.Proc.HasExited) { $info.Proc.Kill() } } catch { }
            Remove-Item $info.Arquivo -Force -ErrorAction SilentlyContinue
            $info.Proc = $null
            $info.Tentativas++
            $espera = [Math]::Min(25, [Math]::Pow(2, $info.Tentativas))
            $info.Pausa = $agora.AddSeconds($espera)
            Set-Estado $p "tentando ($($info.Tentativas)/8)" $corAmbar
        }
    }
}

function Stop-Recording($p) {
    if (-not $script:Recording.ContainsKey($p.Nome)) { return }
    $info = $script:Recording[$p.Nome]
    try { if ($info.Proc -and -not $info.Proc.HasExited) { $info.Proc.Kill() } } catch { }
    $script:Recording.Remove($p.Nome)

    if ($p.Relay) {
        Start-Sleep -Milliseconds 1200
        Start-Player $p
    }
    Set-Estado $p 'ao vivo' $corFraco
}

function Save-Snapshots {
    $antes = @(Get-ChildItem $tempDir -Filter '*.bmp' -ErrorAction SilentlyContinue | Select-Object -ExpandProperty FullName)
    foreach ($p in $players) { try { $p.Ctl.Vlc.video.takeSnapshot() | Out-Null } catch { } }
    Start-Sleep -Milliseconds 1200

    $novos = @(Get-ChildItem $tempDir -Filter '*.bmp' -ErrorAction SilentlyContinue | Where-Object { $antes -notcontains $_.FullName })
    $i = 0
    foreach ($f in ($novos | Sort-Object LastWriteTime)) {
        try {
            $img = [System.Drawing.Image]::FromFile($f.FullName)
            $destino = Join-Path $fotoDir ("foto_{0:yyyy-MM-dd_HH-mm-ss}_{1}.jpg" -f (Get-Date), $i)
            $img.Save($destino, [System.Drawing.Imaging.ImageFormat]::Jpeg)
            $img.Dispose()
            Remove-Item $f.FullName -Force -ErrorAction SilentlyContinue
            $i++
        } catch { }
    }
    return @($novos).Count
}

function Atualiza-Estado {
    foreach ($p in $players) {
        if ($script:Recording.ContainsKey($p.Nome)) { continue }
        $st = -1
        try { $st = [int]$p.Ctl.Vlc.input.state } catch { $st = -1 }

        switch ($st) {
            3 {
                Set-Estado $p 'ao vivo' $corVerde
                # o VLC so aceita mudo depois que o audio existe: reaplica ate pegar
                if ($p.MuteAplicado -ne $p.Mudo) { Set-Mudo $p $p.Mudo $false }
            }
            4 { Set-Estado $p 'pausado' $corAmbar }
            7 { Set-Estado $p 'sem sinal' $corVermelho }
            default {
                if ($st -ge 1) { Set-Estado $p 'conectando' $corAmbar }
                else { Set-Estado $p 'aguardando' $corFraco }
            }
        }
    }
}

# ---- dialogo: renomear ----------------------------------------------------
function Test-NomesDialogo {
    $vistos = @{}
    $todosOk = $true
    foreach ($c in $script:DlgCampos) {
        $t = $c.Caixa.Text.Trim()
        $msg = ''
        if ($t -eq '') { $msg = 'Informe um nome para a câmera.' }
        elseif ($vistos.ContainsKey($t.ToLower())) { $msg = 'Já existe outra câmera com esse nome.' }
        else { $vistos[$t.ToLower()] = $true }
        $c.Erro = ($msg -ne '')
        $c.Tag.Text = $msg
        if ($msg -ne '') { $todosOk = $false }
    }
    $script:DlgOk.Enabled = $todosOk
}

function Show-Renomear {
    $n = $players.Count
    $linha = 90
    $areaH = [Math]::Min($n * $linha, 360)
    $btnY = 96 + $areaH + 16
    $d = New-Dialogo 'Renomear câmeras' 480 ($btnY + 44 + 24)

    $t = New-Rotulo 'Renomear câmeras' 24 22 432 28 $corTexto ([Tema]::Fonte(13, $true))
    $s = New-Rotulo 'O nome aparece no rodapé de cada câmera e nos arquivos gravados.' 24 52 432 36 $corFraco ([Tema]::Fonte(9.5, $false))

    $area = New-Object System.Windows.Forms.Panel
    $area.Left = 24; $area.Top = 96; $area.Width = 440; $area.Height = $areaH
    $area.AutoScroll = $true
    $area.BackColor = $corSuperficie

    $script:DlgCampos = New-Object System.Collections.ArrayList
    $larg = if (($n * $linha) -gt 360) { 400 } else { 432 }
    for ($i = 0; $i -lt $n; $i++) {
        $y = $i * $linha
        $lb = New-Rotulo "Câmera $($i + 1)" 0 $y 300 20 $corFraco ([Tema]::Fonte(9, $true))
        $campo = New-Object CampoUi
        $campo.Left = 0; $campo.Top = ($y + 22); $campo.Width = $larg
        $campo.Caixa.Text = $players[$i].Nome
        $campo.Caixa.MaxLength = 40
        $campo.Caixa.AccessibleName = "Nome da câmera $($i + 1)"
        $err = New-Rotulo '' 2 ($y + 68) $larg 18 $corVermelho ([Tema]::Fonte(9, $false))
        $campo.Tag = $err
        $campo.Caixa.Add_TextChanged({ Test-NomesDialogo })
        $area.Controls.AddRange(@($lb, $campo, $err))
        [void]$script:DlgCampos.Add($campo)
    }

    $ok = New-Object BotaoUi
    $ok.Text = 'Salvar'; $ok.Variante = 'primario'; $ok.AccessibleName = 'Salvar nomes'
    $ok.Left = 480 - 24 - 112; $ok.Top = $btnY; $ok.Width = 112
    $ok.DialogResult = 'OK'
    $cancelar = New-Object BotaoUi
    $cancelar.Text = 'Cancelar'; $cancelar.Variante = 'secundario'; $cancelar.AccessibleName = 'Cancelar'
    $cancelar.Left = $ok.Left - 8 - 112; $cancelar.Top = $btnY; $cancelar.Width = 112
    $cancelar.DialogResult = 'Cancel'
    $script:DlgOk = $ok

    $d.Controls.AddRange(@($t, $s, $area, $cancelar, $ok))
    $d.AcceptButton = $ok
    $d.CancelButton = $cancelar
    $d.Add_Shown({ $script:DlgCampos[0].Caixa.Focus(); $script:DlgCampos[0].Caixa.SelectAll() })

    $mudou = $false
    if ($d.ShowDialog($form) -eq 'OK') {
        for ($i = 0; $i -lt $n; $i++) {
            $novoNome = $script:DlgCampos[$i].Caixa.Text.Trim()
            if ($novoNome -eq '' -or $novoNome -eq $players[$i].Nome) { continue }
            $players[$i].Nome = $novoNome
            $players[$i].Label.Text = $novoNome
            $mudou = $true
        }
        if ($mudou) {
            if (Save-Config) { Set-Status 'Nomes salvos.' 'ok' }
            foreach ($p in $players) { Show-BotaoMudo $p }
            Update-Layout
        }
    }
    $d.Dispose()
}

# ---- acoes ----------------------------------------------------------------
$btnRec.Add_Click({
    if ($script:Recording.Count -gt 0) {
        foreach ($p in $players) { Stop-Recording $p }
        $btnRec.Text = 'Gravar'
        $btnRec.Icone = 'gravar'
        $btnRec.Variante = 'primario'
        $btnRec.AccessibleName = 'Gravar todas as câmeras'
        Set-Status 'Gravação encerrada. Os arquivos estão na pasta Gravações.' 'ok'
    } else {
        foreach ($p in $players) { Start-Recording $p }
        if ($script:Recording.Count -gt 0) {
            $btnRec.Text = 'Parar'
            $btnRec.Icone = 'parar'
            $btnRec.Variante = 'perigo'
            $btnRec.AccessibleName = 'Parar a gravação'
            Set-Status "Gravando em $gravDir" 'erro'
        }
    }
})

$btnFoto.Add_Click({
    Set-Status 'Salvando fotos…' 'info'
    $rodapeApp.Refresh()
    $n = Save-Snapshots
    if ($n -gt 0) { Set-Status "$n foto(s) salva(s) em $fotoDir" 'ok' }
    else { Set-Status 'Nenhuma foto foi salva. Aguarde as câmeras ficarem ao vivo.' 'aviso' }
})

$btnPasta.Add_Click({ Start-Process explorer.exe $gravDir })

$btnAtualizar.Add_Click({
    foreach ($p in $players) { Start-Player $p }
    Set-Status 'Reconectando as câmeras…' 'info'
})

$btnRenomear.Add_Click({ Show-Renomear })
$btnTelaCheia.Add_Click({ Toggle-TelaCheia })

# ---- menu "Mover" (o player vem pelo Tag do botao) ------------------------
$menuMover = New-Object System.Windows.Forms.ContextMenuStrip
$menuMover.Renderer = New-Object TemaRenderer
$menuMover.ShowImageMargin = $false
$menuMover.Font = [Tema]::Fonte(10, $false)
$menuMover.BackColor = $corElevado
$miCima = $menuMover.Items.Add('Trocar com a câmera de cima')
$miBaixo = $menuMover.Items.Add('Trocar com a câmera de baixo')
$miEsq = $menuMover.Items.Add('Trocar com a câmera da esquerda')
$miDir = $menuMover.Items.Add('Trocar com a câmera da direita')
foreach ($mi in @($miCima, $miBaixo, $miEsq, $miDir)) { $mi.Padding = New-Object System.Windows.Forms.Padding(6, 8, 6, 8) }
$menuMover.Add_Opening({
    $p = $menuMover.Tag
    $i = $players.IndexOf($p); $n = $players.Count
    $miCima.Enabled = ($i - 2 -ge 0)
    $miBaixo.Enabled = ($i + 2 -lt $n)
    $miEsq.Enabled = ($i -gt 0)
    $miDir.Enabled = ($i -lt ($n - 1))
})
$miCima.Add_Click({ $p = $menuMover.Tag; Move-Camera $p ($players.IndexOf($p) - 2) })
$miBaixo.Add_Click({ $p = $menuMover.Tag; Move-Camera $p ($players.IndexOf($p) + 2) })
$miEsq.Add_Click({ $p = $menuMover.Tag; Move-Camera $p ($players.IndexOf($p) - 1) })
$miDir.Add_Click({ $p = $menuMover.Tag; Move-Camera $p ($players.IndexOf($p) + 1) })

foreach ($p in $players) {
    Show-BotaoMudo $p
    $p.BtnMudo.Add_Click({ param($s, $e) $q = $s.Tag; Set-Mudo $q (-not $q.Mudo) })
    $p.BtnAmpliar.Add_Click({ param($s, $e) Toggle-Foco $s.Tag })
    $p.BtnVirar.Add_Click({ param($s, $e) Show-Ptz $s.Tag })
    $p.BtnMover.Add_Click({
        param($s, $e)
        $menuMover.Tag = $s.Tag
        $menuMover.Show($s, (New-Object System.Drawing.Point(0, $s.Height)))
    })
    $p.Label.Add_DoubleClick({ param($s, $e) Toggle-Foco $s.Tag })
    $p.Tile.Add_DoubleClick({ param($s, $e) Toggle-Foco $s.Tag })
}

Update-Layout
Update-Compacto
Update-Resumo
Reset-Status
$form.Add_Resize({ Update-Compacto })

# ---- ciclo de vida --------------------------------------------------------
$form.Add_Shown({
    foreach ($p in $players) { Start-Player $p }

    $script:wd = New-Object System.Windows.Forms.Timer
    $script:wd.Interval = 2000
    $script:wd.Add_Tick({ Watch-Recording })
    $script:wd.Start()

    $script:tEstado = New-Object System.Windows.Forms.Timer
    $script:tEstado.Interval = 1000
    $script:tEstado.Add_Tick({
        Atualiza-Estado
        Update-Resumo
        if ($script:StatusAte -ne [datetime]::MinValue -and (Get-Date) -gt $script:StatusAte) { Reset-Status }
    })
    $script:tEstado.Start()

    if ($AutoRecord) { foreach ($p in $players) { Start-Recording $p } }
    if ($AutoSnapshot) {
        $script:tSnap = New-Object System.Windows.Forms.Timer
        $script:tSnap.Interval = 9000
        $script:tSnap.Add_Tick({
            $script:tSnap.Stop()
            $n = Save-Snapshots
            Set-Status "$n foto(s) salva(s) em $fotoDir" 'ok'
        })
        $script:tSnap.Start()
    }
    if ($SelfTestSeconds -gt 0) {
        $script:timerTest = New-Object System.Windows.Forms.Timer
        $script:timerTest.Interval = $SelfTestSeconds * 1000
        $script:timerTest.Add_Tick({
            if ($Screenshot) {
                try {
                    $bmp = New-Object System.Drawing.Bitmap($form.Width, $form.Height)
                    $g = [System.Drawing.Graphics]::FromImage($bmp)
                    $g.CopyFromScreen($form.Location, [System.Drawing.Point]::Empty, $form.Size)
                    $bmp.Save($Screenshot, [System.Drawing.Imaging.ImageFormat]::Png)
                    $g.Dispose(); $bmp.Dispose()
                } catch { }
            }
            $script:Saindo = $true
            $form.Close()
        })
        $script:timerTest.Start()
    }
})

$form.Add_KeyDown({
    param($s, $e)
    $k = $e.KeyCode.ToString()
    if ($k -eq 'F5') { foreach ($p in $players) { Start-Player $p }; Set-Status 'Reconectando as câmeras…' 'info' }
    elseif ($k -eq 'F11') { Toggle-TelaCheia }
    elseif ($k -eq 'Escape') {
        if ($script:Focada) { Toggle-Foco $script:Focada }
        elseif ($script:Cheia) { Toggle-TelaCheia }
    }
    elseif ($k -match '^D([1-9])$') {
        $idx = [int]$Matches[1] - 1
        if ($idx -lt $players.Count) { Toggle-Foco $players[$idx] }
    }
})

# ---- bandeja --------------------------------------------------------------
$script:Saindo = $false
$script:Tray = $null

if (Test-Path $arquivoIcone) {
    $script:Tray = New-Object System.Windows.Forms.NotifyIcon
    $script:Tray.Icon = New-Object System.Drawing.Icon($arquivoIcone)
    $script:Tray.Text = 'Central de Câmeras'

    $menu = New-Object System.Windows.Forms.ContextMenuStrip
    $menu.Renderer = New-Object TemaRenderer
    $menu.ShowImageMargin = $false
    $menu.Font = [Tema]::Fonte(10, $false)
    $menu.BackColor = $corElevado
    $miAbrir = $menu.Items.Add('Abrir janela')
    $miGravar = $menu.Items.Add('Gravar / Parar')
    $menu.Items.Add('-') | Out-Null
    $miSair = $menu.Items.Add('Sair')
    foreach ($mi in @($miAbrir, $miGravar, $miSair)) { $mi.Padding = New-Object System.Windows.Forms.Padding(6, 8, 6, 8) }
    $script:Tray.ContextMenuStrip = $menu
    $script:Tray.Visible = $true

    $mostrar = {
        $form.Opacity = 1
        $form.ShowInTaskbar = $true
        $form.WindowState = 'Normal'
        $form.Show()
        $form.Activate()
    }

    $miAbrir.Add_Click($mostrar)
    $script:Tray.Add_DoubleClick($mostrar)
    $miGravar.Add_Click({ $btnRec.PerformClick() })
    $miSair.Add_Click({ $script:Saindo = $true; $form.Close() })

    $form.Add_Resize({
        if ($form.WindowState -eq 'Minimized') {
            $form.WindowState = 'Normal'
            $form.Opacity = 0
            $form.ShowInTaskbar = $false
            if (-not $script:AvisouTray) {
                $script:AvisouTray = $true
                $script:Tray.ShowBalloonTip(2500, 'Central de Câmeras', 'Continua rodando na bandeja. Clique duas vezes para abrir.', [System.Windows.Forms.ToolTipIcon]::Info)
            }
        }
    })

    $form.Add_FormClosing({
        param($s, $e)
        if (-not $script:Saindo) {
            $e.Cancel = $true
            $form.WindowState = 'Normal'
            $form.Opacity = 0
            $form.ShowInTaskbar = $false
        }
    })
}

[void]$form.ShowDialog()

Close-Ptz
if ($script:Tray) { try { $script:Tray.Visible = $false; $script:Tray.Dispose() } catch { } }
foreach ($p in $players) { Stop-Recording $p }
foreach ($p in $players) {
    try { $p.Ctl.Vlc.playlist.stop(); $p.Ctl.Vlc.playlist.clear() } catch { }
    try { $p.Ctl.Dispose() } catch { }
}
foreach ($r in $script:Relays) { try { $r.Stop() } catch { } }
try { $mutex.ReleaseMutex() } catch { }
