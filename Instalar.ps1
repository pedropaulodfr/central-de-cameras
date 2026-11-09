<#
    ATENCAO - ARQUIVO LEGADO (nao use)

    Este script era o gerador de icone/atalhos da PRIMEIRA versao do projeto.
    Ele foi SUBSTITUIDO pelo instalador completo:

        Instalador.ps1   <- instalador de verdade (diagnostico, copia,
                            atalhos, firewall, teste de cameras, relatorio)
        Instalar.bat     <- wrapper que eleva privilegios e chama o de cima

    O instalador novo ja gera o icone e os atalhos, e o icone.ico ja vem
    pronto no projeto. Este arquivo foi mantido apenas por historico.

    O que ainda pode ser util aqui: as funcoes de geracao do .ico
    (New-Desenho / Get-Dib) - ver README.md e ARQUITETURA.md secao 9.2
    sobre o formato DIB-only 16/32/48/256 que o shell do Windows exige.

    Se voce vai reinstalar, use:
        powershell -ExecutionPolicy Bypass -File .\Instalar.bat
#>
<#
    Instalar.ps1 - gera o icone multi-tamanho, o inicializador sem console e os atalhos
    (Area de Trabalho + Inicializar com o Windows).
#>
param([switch]$SemInicializacao)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$icone = Join-Path $root 'icone.ico'
$pastaIcone = Join-Path $env:LOCALAPPDATA 'CentralCameras'
New-Item -ItemType Directory -Force -Path $pastaIcone | Out-Null
$iconeExe = Join-Path $pastaIcone 'icone.ico'
$vbs = Join-Path $root 'Iniciar.vbs'
$ps1 = Join-Path $root 'CameraViewer.ps1'

Add-Type -AssemblyName System.Drawing

function New-Desenho([int]$size) {
    $bmp = New-Object System.Drawing.Bitmap($size, $size, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = 'AntiAlias'
    $g.Clear([System.Drawing.Color]::Transparent)
    $k = $size / 256.0

    $fundo = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(255, 24, 32, 40))
    $g.FillEllipse($fundo, (6 * $k), (6 * $k), ((256 - 12) * $k), ((256 - 12) * $k))

    $branco = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::White)
    $g.FillRectangle($branco, (52 * $k), (96 * $k), (152 * $k), (96 * $k))
    $pontos = @(
        (New-Object System.Drawing.PointF((96 * $k), (96 * $k))),
        (New-Object System.Drawing.PointF((116 * $k), (66 * $k))),
        (New-Object System.Drawing.PointF((160 * $k), (66 * $k))),
        (New-Object System.Drawing.PointF((176 * $k), (96 * $k)))
    )
    $g.FillPolygon($branco, $pontos)

    $azul = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(255, 46, 139, 192))
    $g.FillEllipse($azul, (96 * $k), (108 * $k), (72 * $k), (72 * $k))
    $g.FillEllipse($branco, (118 * $k), (130 * $k), (28 * $k), (28 * $k))

    $g.Dispose()
    return $bmp
}

function Get-Dib([System.Drawing.Bitmap]$bmp) {
    $w = $bmp.Width
    $h = $bmp.Height
    $ms = New-Object System.IO.MemoryStream
    $bw = New-Object System.IO.BinaryWriter($ms)
    $bw.Write([UInt32]40)
    $bw.Write([Int32]$w)
    $bw.Write([Int32]($h * 2))
    $bw.Write([UInt16]1)
    $bw.Write([UInt16]32)
    $bw.Write([UInt32]0)
    $bw.Write([UInt32]($w * $h * 4))
    $bw.Write([Int32]0); $bw.Write([Int32]0)
    $bw.Write([UInt32]0); $bw.Write([UInt32]0)
    for ($y = $h - 1; $y -ge 0; $y--) {
        for ($x = 0; $x -lt $w; $x++) {
            $c = $bmp.GetPixel($x, $y)
            $bw.Write([Byte]$c.B); $bw.Write([Byte]$c.G); $bw.Write([Byte]$c.R); $bw.Write([Byte]$c.A)
        }
    }
    $linhaMascara = [int][Math]::Ceiling($w / 32.0) * 4
    $zeros = New-Object byte[] ($linhaMascara * $h)
    $bw.Write($zeros)
    $bw.Flush()
    return $ms.ToArray()
}

function Get-Png([System.Drawing.Bitmap]$bmp) {
    $ms = New-Object System.IO.MemoryStream
    $bmp.Save($ms, [System.Drawing.Imaging.ImageFormat]::Png)
    return $ms.ToArray()
}

# 16/24/32/48/64 em DIB (compativel com tudo) e 128/256 em PNG
$tamanhos = @(
    @{ T = 16; Png = $false }, @{ T = 32; Png = $false },
    @{ T = 48; Png = $false }, @{ T = 256; Png = $false }
)

$entradas = @()
foreach ($t in $tamanhos) {
    $bmp = New-Desenho $t.T
    [byte[]]$dados = if ($t.Png) { Get-Png $bmp } else { Get-Dib $bmp }
    $bmp.Dispose()
    $entradas += [pscustomobject]@{ Tamanho = $t.T; Dados = $dados }
}

$lista = New-Object System.Collections.Generic.List[byte]
$lista.AddRange([byte[]]@(0, 0, 1, 0))
$lista.Add([byte]$entradas.Count)
$lista.Add([byte]0)

$offset = 6 + (16 * $entradas.Count)
foreach ($e in $entradas) {
    $dim = if ($e.Tamanho -ge 256) { 0 } else { $e.Tamanho }
    $lista.Add([byte]$dim); $lista.Add([byte]$dim)
    $lista.Add([byte]0); $lista.Add([byte]0)
    $lista.AddRange([byte[]][BitConverter]::GetBytes([UInt16]1))
    $lista.AddRange([byte[]][BitConverter]::GetBytes([UInt16]32))
    $lista.AddRange([byte[]][BitConverter]::GetBytes([UInt32]$e.Dados.Length))
    $lista.AddRange([byte[]][BitConverter]::GetBytes([UInt32]$offset))
    $offset += $e.Dados.Length
}
foreach ($e in $entradas) { $lista.AddRange([byte[]]$e.Dados) }

[System.IO.File]::WriteAllBytes($icone, $lista.ToArray())
Copy-Item $icone $iconeExe -Force
"icone gerado: $($lista.Count) bytes"
# ---------------- inicializador sem janela preta ----------------
$conteudo = @"
Set sh = CreateObject("WScript.Shell")
sh.Run "powershell.exe -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File ""$ps1""", 0, False
"@
Set-Content -Path $vbs -Value $conteudo -Encoding ASCII

# ---------------- atalhos ----------------
$ws = New-Object -ComObject WScript.Shell

function Novo-Atalho([string]$pasta, [string]$nome) {
    $caminho = Join-Path $pasta "$nome.lnk"
    if (Test-Path $caminho) { Remove-Item $caminho -Force }
    $lnk = $ws.CreateShortcut($caminho)
    $lnk.TargetPath = "$env:SystemRoot\System32\wscript.exe"
    $lnk.Arguments = "`"$vbs`""
    $lnk.WorkingDirectory = $root
    $lnk.IconLocation = "$iconeExe,0"
    $lnk.Description = 'Central de Cameras - todas as cameras em uma janela'
    $lnk.Save()
    return $caminho
}

$desktop = [Environment]::GetFolderPath('Desktop')
$atalhoDesktop = Novo-Atalho $desktop 'Central de Cameras'
Write-Output "atalho: $atalhoDesktop"

if (-not $SemInicializacao) {
    $inicio = [Environment]::GetFolderPath('Startup')
    $atalhoInicio = Novo-Atalho $inicio 'Central de Cameras'
    Write-Output "inicializacao: $atalhoInicio"
}

Write-Output "icone: $iconeExe  ($([math]::Round((Get-Item $iconeExe).Length / 1KB, 1)) KB, $($entradas.Count) tamanhos)"




