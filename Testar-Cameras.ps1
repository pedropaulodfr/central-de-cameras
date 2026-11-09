<#
    Testar-Cameras.ps1
    Descobre qual URL RTSP funciona em cada camera, testando com ffprobe.

    Uso:
      powershell -ExecutionPolicy Bypass -File .\Testar-Cameras.ps1 -IP 192.168.0.7 -Usuario admin -Senha "SENHA_DA_CAMERA"
      powershell -ExecutionPolicy Bypass -File .\Testar-Cameras.ps1 -IP 192.168.0.5 -Usuario admin -Senha "SENHA_DA_CAMERA"

    A senha e a do DISPOSITIVO (a que voce definiu no app Yoosee/iCSee), nao a do Wi-Fi.
#>
param(
    [Parameter(Mandatory = $true)][string]$IP,
    [string]$Usuario = "admin",
    [Parameter(Mandatory = $true)][string]$Senha,
    [int]$TimeoutSegundos = 5
)

$ffprobe = (Get-Command ffprobe -ErrorAction SilentlyContinue).Source
if (-not $ffprobe) { Write-Host "ffprobe nao encontrado no PATH." -ForegroundColor Red; exit 1 }

# '&' e '?' fazem parte da URL da Xiongmai/iCSee; o PowerShell aceita normalmente.
$caminhos = @(
    '/onvif1'
    '/onvif2'
    '/live/ch0'
    '/live/ch1'
    '/stream1'
    '/stream2'
    '/11'
    '/12'
    '/h264'
    '/1'
    '/0'
    "/user=$Usuario&password=$Senha&channel=1&stream=0.sdp?real_stream"
    "/user=$Usuario&password=$Senha&channel=1&stream=1.sdp?real_stream"
    "/user=$Usuario`_password=$Senha`_channel=1_stream=0.sdp?real_stream"
    '/cam/realmonitor?channel=1&subtype=0'
    '/Streaming/Channels/101'
)

Write-Host "`n=== Testando $IP  (usuario: $Usuario) ===" -ForegroundColor Cyan
$achou = $false

foreach ($c in $caminhos) {
    $url = "rtsp://${Usuario}:$Senha@${IP}:554$c"
    $ffargs = @(
        '-hide_banner', '-v', 'error'
        '-rtsp_transport', 'tcp'
        '-timeout', ($TimeoutSegundos * 1000000)
        '-select_streams', 'v:0'
        '-show_entries', 'stream=codec_name,width,height'
        '-of', 'csv=p=0'
        '-i', $url
    )

    $saida = (& $ffprobe @ffargs 2>&1 | ForEach-Object { "$_" }) -join "`n"
    $saida = $saida -replace '(?m)^\s*ffprobe\.exe\s*:\s*', ''

    if ($saida -match '(?m)^\s*(h264|hevc|h265|mjpeg)') {
        $info = ($saida -split "`n" | Where-Object { $_ -match '^\s*(h264|hevc|h265|mjpeg)' } | Select-Object -First 1).Trim()
        Write-Host ("  OK   {0,-62} -> {1}" -f $c, $info) -ForegroundColor Green
        Write-Host ("       URL: {0}" -f $url) -ForegroundColor DarkGray
        $achou = $true
        continue
    }

    $motivo = ($saida -split "`n" | Where-Object { $_ -match 'Server returned|Unauthorized|Bad Request|Connection|timed out|Invalid' } | Select-Object -First 1)
    if (-not $motivo) { $motivo = ($saida -split "`n" | Where-Object { $_.Trim() -ne '' } | Select-Object -First 1) }
    $motivo = "$motivo".Trim()

    if ($motivo -match '400 Bad Request') { $motivo = '400 Bad Request (usuario/senha recusados)' }
    elseif ($motivo -match '401') { $motivo = '401 Unauthorized (usuario/senha recusados)' }

    Write-Host ("  --   {0,-62} -> {1}" -f $c, $motivo) -ForegroundColor DarkGray
}

if ($achou) {
    Write-Host "`nCamera OK. Use no programa a URL marcada como OK." -ForegroundColor Green
} else {
    Write-Host "`nNenhuma URL funcionou com essas credenciais." -ForegroundColor Yellow
    Write-Host "Confira no app da camera: senha do dispositivo e nome de login." -ForegroundColor Yellow
}
