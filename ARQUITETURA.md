# Arquitetura — Referência Técnica

Documento de referência para quem for mexer no código. Detalha decisões de
implementação, o comportamento do firmware das câmeras e as armadilhas do
ambiente PowerShell/WinForms.

---

## Índice

1. [Stack e por que](#1-stack-e-por-quê)
2. [Módulos e responsabilidades](#2-módulos-e-responsabilidades)
3. [O relay RTSP](#3-o-relay-rtsp)
4. [Comportamento do firmware das câmeras](#4-comportamento-do-firmware-das-câmeras)
5. [A UI](#5-a-ui)
6. [Gravação](#6-gravação)
7. [Bandeja e ciclo de vida](#7-bandeja-e-ciclo-de-vida)
8. [Instalador](#8-instalador)
9. [Armadilhas do ambiente](#9-armadilhas-do-ambiente)
10. [Referência de API](#10-referência-de-api)

---

## 1. Stack e por que

### PowerShell 5.1 + WinForms

Escolhido para o ambiente-alvo: PC domesticado Windows, sem toolchain de
build, sem runtime de instalação. Não precisa de .NET Core, nem de
compilador — o `.ps1` roda direto com `powershell.exe`.

**Consequência:** todo hot-fix tem que ser `.ps1` + `.cs` compilado em runtime
via `Add-Type`. Não há projeto Visual Studio, nem teste unitário.

### VLC ActiveX (não `libvlc` / `cv2` / `ffplay`)

O VLC ActiveX (`CLSID {9BE31822-FDAD-461B-AD51-BE1D1C159921}`) é embutido
dentro do WinForms via uma subclasse de `AxHost`:

```powershell
Add-Type -TypeDefinition @"
using System;
using System.Windows.Forms;
public class VlcHost : AxHost {
    public VlcHost() : base("9BE31822-FDAD-461B-AD51-BE1D1C159921") { }
    public object Vlc { get { return GetOcx(); } }
}
"@ -ReferencedAssemblies System.Windows.Forms
```

Motivos:
- **Decodificação H.264 completa** sem depender de `ffplay`
- **Controle de estado** (`input.state`) → permite mostrar "ao vivo" vs "sem sinal"
- **`takeSnapshot()`** nativo → fotos sem processo externo
- **`toggleFullscreen()`** nativo

⚠️ **O VLC precisa estar instalado com o componente ActiveX.** O instalador
procura o CLSID no registro:
`HKLM/HKCU:\SOFTWARE\Classes\CLSID\{9BE31822-...}\InprocServer32`.

### ffmpeg só para gravar

`ffmpeg` grava com `-c copy` (remux, sem recodificar) — barato em CPU e
preserva a qualidade original. O VLC não grava RTSP de forma confiável.

### C# inline para o relay

O relay precisa de `Sockets` com controle fino de byte-a-byte e regex —
awkward em PowerShell, natural em C#. Compilado em runtime com
`Add-Type -Path RtspRelay.cs`. Nenhuma dependência de build.

---

## 2. Módulos e responsabilidades

```
CameraViewer.ps1
├── Bootstrap
│   ├── carrega assemblies (WinForms, Drawing)
│   ├── compila VlcHost (ActiveX host)
│   ├── compila RtspRelay.cs
│   ├── define AppUserModelID
│   └── trava instância única (Mutex)
├── Config
│   └── lê cameras.json → $cams
├── UI
│   ├── Form + cabeçalho + barra de ações
│   ├── TableLayoutPanel 2 colunas → tiles
│   └── cada tile: VlcHost + rodapé (ponto/nome/status)
├── Players
│   ├── Start-Player / Enable-Fullscreen
│   └── Atualiza-Estado (polling de input.state)
├── Gravação
│   ├── Start-Gravador / Start-Recording / Stop-Recording
│   └── Watch-Recording (watchdog com retry)
├── Fotos
│   └── Save-Snapshots
├── Renomear
│   └── diálogo modal → persiste no cameras.json
└── Bandeja
    ├── NotifyIcon + ContextMenuStrip
    └── FormClosing cancelado → esconde em vez de sair
```

### Estado global

```powershell
$script:Relays    = @()    # instâncias RtspRelay ativas
$script:Recording = @{}    # nome da câmera → objeto de gravação (Hashtable!)
$script:wd        = timer watchdog de gravação
$script:tEstado   = timer de status (1 s)
$script:tSnap     = timer de snapshot (auto)
$script:timerTest = timer do SelfTest
$script:Tray      = NotifyIcon
$script:Saindo    = flag: sair de verdade vs esconder
$script:Dicas     = ToolTip
```

> ⚠️ **`$script:Recording` é `@{}` (Hashtable), não `@()`.** Uma versão
> anterior usava array e o `.ContainsKey()` explodiu — todas as referências
> precisaram ser trocadas por indexação por chave.

> ⚠️ **Timers precisam de `$script:`.** Um `System.Windows.Forms.Timer` criado
> como variável local de função é coletado pelo GC e o timer **para de
> disparar** sem aviso.

---

## 3. O relay RTSP

### Por que existe

Três defeitos do firmware Yoosee impedem o VLC/ffmpeg de conectar
diretamente:

| # | Defeito | Sintoma | Correção no relay |
|---|---|---|---|
| 1 | Digest malformado aceito, Digest correto recusado | `400 Bad Request` | Injeta `Authorization: Digest username="admin"` (sem `response=`) |
| 2 | Responde `Transport: RTP/AVP` mesmo aceitando TCP | `Nonmatching transport in server reply` | Reescreve para `RTP/AVP/TCP` |
| 3 | Sessão fica pendurada na câmera ao desconectar | próxima conexão falha | Envia `TEARDOWN` |

### A anatomia

```csharp
public class RtspRelay
{
    public RtspRelay(string remoteHost, int remotePort, int localPort)
    public void Start()
    public void Stop()
    public int Port { get; }   // porta efetiva (quando localPort == 0)
}
```

**`localPort = 0` = porta efêmera.** O Windows escolhe uma porta livre e
`Port` devolve o número real. Isso evita a corrida clássica de "duas
instâncias do app, mesma porta, `Address already in use`".

O `TcpListener` é criado com:
```csharp
new TcpListener(IPAddress.Loopback, lport);
listener.Server.SetSocketOption(SocketOptionLevel.Socket,
                                 SocketOptionName.ReuseAddress, true);
```

- **`IPAddress.Loopback`** — o relay nunca é alcançável pela rede
- **`ReuseAddress`** — permite reabrir a porta mesmo com a conexão anterior em
  `TIME_WAIT` (sem isso, o segundo `Start()` falha com *"Normalmente só é
  permitido uma utilização de cada endereço de socket"*)

### A máquina de estados do `Pump`

Cada conexão tem **duas threads**: `client→camera` (toServer=true) e
`camera→client` (toServer=false). O parser é uma máquina de estados simples:

```
┌──────────┐   byte '$' (primeiro byte)   ┌──────────┐
│  HEADER  │───────────────────────────────▶│   RAW    │
│  (RTSP)  │◀───────────────────────────────│ (binário)│
└────┬─────┘         header completo       └──────────┘
     │
     │ Content-Length > 0
     ▼
┌──────────┐
│  BODY    │  ← download de SDP/descriptor
└──────────┘
```

Pontos-chave:

- **Latino-1 (ISO-8859-1)** para ler/escrever bytes de header — nunca UTF-8,
  porque RTSP headers são ASCII puro e UTF-8 corromperia bytes > 0x7F
- **Detecção de RAW**: quando o primeiro byte do buffer é `$` (0x24), o
  stream virou interleaved RTP/RTCP. A partir daí, é só copiar byte-a-byte
  sem interpretar.
- **Fim de header**: busca `\r\n\r\n` (CRLF CRLF)
- **`Content-Length`**: regex case-insensitive

### `RewriteRequest` (cliente → câmera)

```csharp
header = header.Replace("rtsp://127.0.0.1:" + lport,
                         "rtsp://" + rhost + ":" + rport);

if (header.IndexOf("RTP/AVP/TCP", ...) >= 0) { wantTcp = true; }

// captura a URL para o TEARDOWN
var linha = Regex.Match(header,
    "(?im)^(DESCRIBE|SETUP|PLAY|OPTIONS)\\s+(rtsp://[^\\s]+)\\s+RTSP/");
if (linha.Success) {
    var metodo = linha.Groups[1].Value.ToUpperInvariant();
    if (metodo == "DESCRIBE" || string.IsNullOrEmpty(lastUrl)) {
        lastUrl = linha.Groups[2].Value;
    }
}

// remove auth do cliente e injeta o malformado
head = Regex.Replace(head, "(?im)^Authorization:.*\r\n", "");
head = Regex.Replace(head, "(?im)^Proxy-Authorization:.*\r\n", "");
return head + "\r\nAuthorization: Digest username=\"admin\"" + tail;
```

Pontos que importam:

- **`lastUrl`** é capturada do `DESCRIBE` (a URL base do stream) — é o que o
  `TEARDOWN` precisa. Fallback: a primeira URL de `SETUP`/`PLAY`.
- **O `Authorization` do cliente é removido antes** de injetar o malformado.
  Se sobrassem dois headers `Authorization`, a câmera recusaria.
- **O header injetado NÃO tem `response=`** — é exatamente isso que o
  firmware quebrado aceita.

### `RewriteResponse` (câmera → cliente)

```csharp
var s = Regex.Match(header, "(?im)^Session:\\s*([^;\\r\\n]+)");
if (s.Success) { sessionId = s.Groups[1].Value.Trim(); }

if (wantTcp && header.IndexOf("Transport:", ...) >= 0) {
    header = Regex.Replace(header,
        "(?im)^Transport:\\s*RTP/AVP;", "Transport: RTP/AVP/TCP;");
}

return header.Replace("rtsp://" + rhost + ":" + rport,
                       "rtsp://127.0.0.1:" + lport);
```

- **`sessionId`** é capturado da resposta do `SETUP` para o `TEARDOWN`
- **A correção do `Transport` só aplica se `wantTcp`** — se o cliente pediu
  UDP, o header original é preservado
- **A URL real é reescrita de volta** para `127.0.0.1` — o cliente nunca deve
  ver o IP da câmera

### `SendTeardown`

```csharp
sb.Append("TEARDOWN ").Append(lastUrl).Append(" RTSP/1.0\r\n");
sb.Append("CSeq: 9999\r\n");
sb.Append("Session: ").Append(sessionId).Append("\r\n");
sb.Append("Authorization: Digest username=\"admin\"\r\n\r\n");
```

Enviado quando a thread `toServer` termina (o cliente desconectou). **Sem
isso, a Yoosee mantém a sessão e recusa a próxima conexão por ~60 s.**

---

## 4. Comportamento do firmware das câmeras

### iCSee (`192.168.0.5`)

- Autenticação Digest **correta e funcional**
- URL: `rtsp://USUARIO:SENHA@192.168.0.5:554/onvif1`
- `"tcp": true` funciona bem
- Tempo de conexão ~3,7 s

### Yoosee (`192.168.0.7`, `192.168.0.2`)

| Comportamento | Detalhe |
|---|---|
| Auth | Aceita `Digest username="admin"` **sem** `response=`; recusa Digest correto com `400 Bad Request` |
| Transport | Responde `RTP/AVP` mesmo tendo aceitado TCP interleaved |
| Sessões | ~1 sessão RTSP por vez por câmera |
| UDP | Confiável; `.2` grava bem por UDP |
| TCP | `.7` **congela após ~2,3 s** em streaming TCP → deve ficar em UDP |
| Teardown | Precisa de TEARDOWN explícito; sem isso trava por ~60 s |
| `.2` | Grava bem em TCP (`recTcp: true`) |
| `.7` | Grava em UDP (`recTcp` ausente) |

### Diagnóstico

```powershell
# Verificar caminho RTSP de uma câmera
.\Testar-Cameras.ps1 -IP 192.168.0.7 -Usuario admin -Senha "SENHA"

# Testar só a porta
Test-NetConnection -ComputerName 192.168.0.7 -Port 554

# Testar uma URL à mão
ffprobe -rtsp_transport tcp -i "rtsp://192.168.0.7:554/onvif1" -show_entries stream=codec_name
```

---

## 5. A UI

### Design system (`Ui.cs`)

Toda cor, fonte, raio e componente visual vive em **`Ui.cs`**, compilado em
runtime por `Add-Type` (C# 5). O `CameraViewer.ps1` só **lê** os tokens
(`[Tema]::Fundo`, `[Tema]::Primario`…): nenhuma cor solta nas telas.

| Token | Hex | Uso |
|---|---|---|
| `Tema.Fundo` | `#0B0F14` | Fundo da janela e da grade |
| `Tema.Superficie` | `#121820` | Barra superior, rodapés, diálogos |
| `Tema.Elevado` | `#19222D` | Botões secundários, campos, menus |
| `Tema.Hover` | `#222E3C` | Hover de botões e itens de menu |
| `Tema.Borda` / `BordaForte` | `#283543` / `#425264` | Divisores e bordas |
| `Tema.Texto` | `#E6EDF5` | Texto principal |
| `Tema.TextoSuave` | `#96A7BB` | Texto secundário (≥ 4,5:1 sobre `Superficie`) |
| `Tema.Primario` | `#2563EB` | Ação primária (texto branco 5,2:1) |
| `Tema.AcentoClaro` | `#60A5FA` | Anel de foco, câmera ampliada |
| `Tema.Perigo` | `#D32222` | Parar gravação (texto branco ≥ 4,5:1) |
| `Tema.Sucesso` / `Alerta` / `Erro` | `#34D399` / `#F5B84B` / `#F87171` | "ao vivo" / "conectando" / "sem sinal" |

Raios: botões e campos 8 px, pílula totalmente redonda. Espaçamento em
múltiplos de 4/8. Fontes: Segoe UI e Segoe UI Semibold (≥ 9 pt = 12 px).

**Componentes** (todos em `Ui.cs`):

| Componente | O que é |
|---|---|
| `BotaoUi` | Botão `primario` / `secundario` / `fantasma` / `perigo`, ícone vetorial, só-ícone, hover/pressionado/desabilitado, anel de foco por teclado |
| `Icones.Desenhar` | Ícones vetoriais (grade 24, traço 2 px). **Nunca emoji** |
| `Pilula` | Estado = ponto colorido **+ texto** (nunca só cor) |
| `Cartao` | Painel de cada câmera com borda visível (vermelha gravando, azul ampliada) |
| `CampoUi` | Campo de texto com rótulo externo, foco e estado de erro |
| `TemaRenderer` | Menus de contexto e da bandeja no tema escuro |
| `Tema.EstilizarDica` | Tooltips escuras |
| `Tema.EscurecerBarra` | Barra de título escura (DWM) |

### Layout

```
┌ barra superior ───────────────────────────────────────────────┐
│ [marca] Central de Câmeras      Gravar Foto Reconectar … ⛶     │
│         3 câmeras · 2 ao vivo                                 │
├───────────────────────────────────────────────────────────────┤
│ grade 2 colunas (câmera ímpar ocupa as duas colunas)          │
│  ┌ Cartao ─────────────┐ ┌ Cartao ─────────────┐              │
│  │ vídeo (VLC)         │ │                     │              │
│  │ Nome  ●estado 🔊 ⤢ ✥ │ │                     │              │
│  └─────────────────────┘ └─────────────────────┘              │
├───────────────────────────────────────────────────────────────┤
│ ● mensagem de status                 atalhos          14:32   │
└───────────────────────────────────────────────────────────────┘
```

- Barra superior de 64 px: marca, resumo vivo ("N câmeras · K ao vivo") e
  ações. Abaixo de 1180 px de largura, os botões secundários viram só ícone
  (a tooltip mostra o nome).
- **Barra de status** (36 px): mensagens de feedback com ponto colorido por
  nível (`info`/`ok`/`aviso`/`erro`), voltam a "Pronto" após 10 s; atalhos e
  relógio à direita. É a **única** área de feedback (`Set-Status`).
- Cada tile tem rodapé de 52 px: nome, pílula de estado e três botões de
  40 px (Som/Mudo, Ampliar, Mover). Ampliar = só uma câmera ocupa a grade
  (`Update-Layout` esconde as outras e usa `SetColumnSpan/SetRowSpan`).
- Tela cheia (F11) esconde a barra superior; a barra de status continua com a
  dica de saída.
- Diálogos (`Show-Renomear`, `Show-Aviso`) usam os mesmos componentes; o
  Renomear valida nome vazio/duplicado em linha e desabilita "Salvar".

### Estado por câmera

Lido de `$p.Ctl.Vlc.input.state` e mostrado na `Pilula`:

| `state` | Texto | Cor |
|---|---|---|
| `3` | `ao vivo` | verde |
| `4` | `pausado` | âmbar |
| `7` | `sem sinal` | vermelho |
| `1..2` | `conectando` | âmbar |
| `0`/erro | `aguardando` | suave |
| gravando | `gravando` / `tentando (n/8)` | vermelho / âmbar |

> **Não use a variável `$host`** — é uma variável **reservada** do PowerShell
> (o objeto host). Assignar nela quebra o script. O projeto usa `$vlcCtl` e
> `Ctl`.

> **PS 5.1:** `@(... | ConvertFrom-Json)` **aninha** o array. Atribua a uma
> variável primeiro e só então use `@($var)`.

### AppUserModelID

```powershell
[DllImport("shell32.dll", CharSet = CharSet.Unicode)]
public static extern int SetCurrentProcessExplicitAppUserModelID(string appID);

[Identidade]::SetCurrentProcessExplicitAppUserModelID('Pedro.CentralDeCameras')
```

Sem isso, o Windows agrupa a janela com o **PowerShell** na barra de tarefas
(ícone do PowerShell). Com o AppUserModelID, ela fica no seu próprio grupo
com o ícone da câmera.

### Instância única

```powershell
$criadoNovo = $false
$mutex = New-Object System.Threading.Mutex($true, 'Local\CentralDeCamerasApp', [ref]$criadoNovo)
if (-not $criadoNovo) {
    [System.Windows.Forms.MessageBox]::Show('O Central de Cameras ja esta aberto.', ...) | Out-Null
    exit
}
```

`Local\` = apenas nesta sessão do Windows (não global, para não pedir privilégios).

---

## 6. Gravação

### Fluxo

```
Start-Recording(p)
├── se $script:Recording já contém p.Nome → return
├── se Relay → para o VLC daquela câmera + espera 1500 ms
├── monta $info (Url, Tcp=RecTcp, Arquivo, Proc, Inicio, Pausa, Tentativas=1)
├── Start-Gravador($info)
│   └── ffmpeg -hide_banner -v error -rtsp_transport {tcp|udp}
│               -i <url> -c copy -y <arquivo.ts>
└── status = "gravando"
```

### Watchdog

`Watch-Recording` roda a cada **2 s** (`$script:wd`):

```powershell
$falhou = ($parado -and $tam -lt 150000) -or ($passou -ge 12 -and $tam -lt 80000)
if ($falhou -and $info.Tentativas -lt 8) {
    mata o processo
    apaga o arquivo parcial
    $info.Tentativas++
    $espera = [Math]::Min(25, [Math]::Pow(2, $info.Tentativas))   # 4,8,16,25,25...
    $info.Pausa = $agora.AddSeconds($espera)
    status = "tentando (N/8)"
}
```

Dois critérios de falha:
- o processo **saiu** e o arquivo tem **< 150 KB**
- o processo roda há **≥ 12 s** e o arquivo tem **< 80 KB** (travar sem sair)

Backoff exponencial **limitado a 25 s**, máximo **8 tentativas**.

> A pausa entre tentativas existe porque a Yoosee precisa de tempo para
> liberar a sessão RTSP anterior.

### Arquivo de saída

```
Gravacoes\NomeCamera_AAAA-MM-DD_HH-mm-ss.ts
```

O nome da câmera é sanitizado: `($p.Nome -replace '[^\w\-]', '_')`.

### `-nostdin`

**Obrigatório.** Sem ele, o ffmpeg fica esperando input de stdin e **trava a
UI inteira** (o clipboard de ffmpeg inherita do console do PowerShell).
O `-nostdin` já está implícito no `-hide_banner -v error` usado, mas se
mudar a chamada, mantenha.

> Na verdade o `-nostdin` foi adicionado explicitamente na versão atual do
> `Start-Gravador` quando a feature de vigilância existia. **Ao reintroduzir
> gravação, garanta `-nostdin`.**

---

## 7. Bandeja e ciclo de vida

### O problema do `Hide()`

`Form.Hide()` **encerra o `ShowDialog()`** — o loop de mensagens acaba, o
script continua executando e o `finally` de limpeza mata o app. Ou seja:
esconder com `Hide()` = fechar.

### A solução

```powershell
$form.Add_Resize({
    if ($form.WindowState -eq 'Minimized') {
        $form.WindowState = 'Normal'
        $form.Opacity = 0          # invisível
        $form.ShowInTaskbar = $false
        if (-not $script:AvisouTray) {
            $script:AvisouTray = $true
            $script:Tray.ShowBalloonTip(2500, 'Central de Cameras',
                'Continua rodando na bandeja. Clique duas vezes para abrir.',
                [System.Windows.Forms.ToolTipIcon]::Info)
        }
    }
})

$form.Add_FormClosing({
    param($s, $e)
    if (-not $script:Saindo) {
        $e.Cancel = $true          # cancela o fechamento
        $form.WindowState = 'Normal'
        $form.Opacity = 0
        $form.ShowInTaskbar = $false
    }
})
```

- `Opacity = 0` → invisível mas **continua no loop de mensagens**
- `ShowInTaskbar = $false` → some da barra de tarefas
- `$script:Saindo` é a flag que diferencia "esconder" de "sair de verdade"

Restaurar:

```powershell
$mostrar = {
    $form.Opacity = 1
    $form.ShowInTaskbar = $true
    $form.WindowState = 'Normal'
    $form.Show()
    $form.Activate()
}
```

### Limpeza na saída

```powershell
[void]$form.ShowDialog()

if ($script:Tray) { try { $script:Tray.Visible = $false; $script:Tray.Dispose() } catch { } }
foreach ($p in $players) { Stop-Recording $p }
foreach ($p in $players) {
    try { $p.Ctl.Vlc.playlist.stop(); $p.Ctl.Vlc.playlist.clear() } catch { }
    try { $p.Ctl.Dispose() } catch { }
}
foreach ($r in $script:Relays) { try { $r.Stop() } catch { } }
try { $mutex.ReleaseMutex() } catch { }
```

Ordem importante: **parar gravações → parar players → parar relays**. Os
relays mandam `TEARDOWN` quando o player desconecta.

---

## 8. Instalador

### Arquitetura

```
Instalar.bat  ──elevate──▶  Instalador.ps1
  (64-bit PS)                 │
                              ├─ 1. Diagnóstico (PS, .NET, VLC, ffmpeg, rede)
                              ├─ 2. Verifica arquivos do projeto
                              ├─ 3. Lê cameras.json
                              ├─ 4. Copia para %LOCALAPPDATA%\CentralCameras
                              │     + gera Iniciar.vbs
                              │     + atalhos (Desktop + Startup)
                              │     + regra de firewall
                              ├─ 5. Testa porta 554 de cada câmera
                              └─ 6. Relatório OK/AVISO/FALHA  → exit 0/1
```

### Por que `Instalar.bat` → `Instalador.ps1`

O `.bat` existe para **elevar privilégios** de forma confiável e para
escolher o PowerShell de 64 bits:

```bat
set "PS64=%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe"
set "PS32=%SystemRoot%\SysWOW64\WindowsPowerShell\v1.0\powershell.exe"

net session >nul 2>&1
if %errorlevel% neq 0 (
    powershell -Command "Start-Process -FilePath '%~f0' -ArgumentList '%*' -Verb RunAs"
)
```

Repassar `%*` mantém os argumentos (`-Desinstalar`, `-SomenteDiagnostico`…).

### Parâmetros do `Instalador.ps1`

| Parâmetro | Efeito |
|---|---|
| `-Pasta <path>` | Destino. Padrão `%LOCALAPPDATA%\CentralCameras` |
| `-SomenteDiagnostico` | Não instala nada, só o relatório |
| `-SemInicializacao` | Não cria o atalho na pasta Startup |
| `-SemTeste` | Não testa a porta 554 (o teste pode demorar) |
| `-Desinstalar` | Remove atalhos + pasta de instalação |

### Atalhos

```powershell
$lnk.TargetPath = "$env:SystemRoot\System32\wscript.exe"
$lnk.Arguments = "`"$vbs`""
$lnk.WorkingDirectory = $Pasta
$lnk.IconLocation = "$iconeDest,0"
```

O alvo é o **`wscript.exe`**, não o PowerShell — assim não aparece uma janela
de console preta. O VBS é o que realmente executa o app.

### Regra de firewall

```powershell
New-NetFirewallRule -DisplayName 'CentralCameras-Relay' `
  -Direction Inbound -Action Allow `
  -Program "$env:SystemRoot\System32\WindowsPowerShell\v1.0\powershell.exe" `
  -Profile Private,Domain
```

⚠️ Criada apenas com **Perfil privado/domínio** — nunca pública.

---

## 9. Armadilhas do ambiente

> Todas as armadilhas abaixo já custaram tempo. **Não repita.**

### 9.1 `ConvertFrom-Json` no PowerShell 5.1 devolve o array enumerado

```powershell
$cameras = @(Get-Content $cameras.json -Raw | ConvertFrom-Json)
# => 1 "câmera" que é um System.Object[]  ❌
```

**Correção:** leia numa variável intermediária e re-agrupar:

```powershell
$bruto = Get-Content $cameras.json -Raw -Encoding UTF8 | ConvertFrom-Json
$cameras = @($bruto)
```

O mesmo bug fazia `"$($c.nome)"` imprimir `System.Object[]` em vez do nome.

### 9.2 `.ico` inválido faz o shell rejeitar o ícone inteiro

Um `.ico` precisa ter **entradas DIB** (não PNG) nos tamanhos **16, 32, 48,
256**. Se incluir 24, 64 ou 128, ou usar PNG em 128, o Windows **descarta o
arquivo inteiro** e mostra o ícone genérico de página no atalho.

Receita validada (DIB-only):

```powershell
$lista = New-Object System.Collections.Generic.List[byte]
foreach ($tamanho in @(16, 32, 48, 256)) {
    $bmp = New-Object System.Drawing.Bitmap($tamanho, $tamanho)
    ... desenhar ...
    $rect = New-Object System.Drawing.Rectangle(0, 0, $tamanho, $tamanho)
    $data = $bmp.LockBits($rect, 'ReadOnly', 'Format32bppArgb')
    $bytes = New-Object byte[] ($data.Stride * $tamanho)
    [System.Runtime.InteropServices.Marshal]::Copy($data.Scan0, $bytes, 0, $bytes.Length)
    $bmp.UnlockBits($data)
    ...
}
```

⚠️ Ao escrever `byte[]` em arquivo, **force o tipo**:
`[System.IO.File]::WriteAllBytes($path, [byte[]]$lista)`. Sem o cast, o
PowerShell **desenrola o array** e o arquivo sai truncado (já aconteceu: 125
bytes em vez de 285 KB).

### 9.3 `ExtractIconEx` não funciona com `.lnk`

```powershell
# ❌ devolve 0 SEMPRE para .lnk, mesmo para atalhos que funcionam
ExtractIconEx($atalho, 0, $null, $null, 1)

# ✅ use SHGetFileInfo, que é o que o Explorer usa
SHGetFileInfo($atalho, 0, $out, [ref]$outSize,
              0x000004000 -bor 0x000000100)   # SHGFI_ICON | SHGFI_USEFILEATTRIBUTES
```

Verificação do pixel: `Icon.FromHandle($out.hIcon)` → `.ToBitmap()` →
`(0,0).ToArgb()`. Valor de referência do ícone atual: **`-13726784`**.

### 9.4 `$host` é reservada

Assignar `$host = ...` quebra o script com "Cannot overwrite variable Host
because it is read-only". Use `$vlcCtl`, `$vlc`, `Ctl`.

### 9.5 `SendMessage` com `BM_CLICK` trava em diálogo modal

```powershell
# ❌ deadlock: SendMessage espera o handler terminar, que espera o modal
[System.Windows.Forms.SendMessage]::SendMessage($btn.Handle, 0x00F5, 0, 0)

# ✅ PostMessage é assíncrono
[System.Windows.Forms.SendMessage]::PostMessage($btn.Handle, 0x00F5, 0, 0)
```

### 9.6 Timers do WinForms são coletados pelo GC

```powershell
# ❌ o timer some depois de um garbage collection
$t = New-Object System.Windows.Forms.Timer
$t.Add_Tick({ ... })

# ✅ guarde em $script:
$script:wd = New-Object System.Windows.Forms.Timer
```

### 9.7 `-Parallel` não existe no PowerShell 5.1

Só no PowerShell 7+. O ambiente usa o 5.1 do sistema.

### 9.8 `Add-Type -ReferencedAssemblies` só aceita **um** array

```powershell
# ❌ dá erro de parâmetro
Add-Type -TypeDefinition $c -ReferencedAssemblies 'System.Drawing', 'System.Windows.Forms'

# ✅ sintaxe de array
Add-Type -TypeDefinition $c -ReferencedAssemblies @('System.Drawing','System.Windows.Forms')
```

E o código C# precisa do `using System.Drawing;` — sem isso o `Add-Type`
falha com erro de compilação.

### 9.9 `CopyFromScreen` exige a janela em primeiro plano

`-Screenshot` só funciona se a janela estiver **visível e focada**. O harness
que executa o comando não foca a janela. Solução usada:

```powershell
ShowWindow($h, 9)                              # SW_RESTORE
SetWindowPos($h, [IntPtr](-1), 0, 0, 0, 0, 0x0001 -bor 0x0002)  # TOPMOST, NO MOVE/SIZE
SetForegroundWindow($h)
Start-Sleep -Milliseconds 400
$g.CopyFromScreen($form.Location, [Point]::Empty, $form.Size)
```

### 9.10 Processos filhos morrem quando o comando do harness termina

Qualquer `Start-Process` criado dentro de um comando do harness é encerrado
quando o comando termina. Para testes, o app precisa ser iniciado **no mesmo
comando** que faz a verificação.

### 9.11 Ambiente sem Python, sem `git clone`, sem `curl`

- Python = stub da Microsoft Store → indisponível
- `git clone` / `curl` falham com
  `schannel: AcquireCredentialsHandle failed: SEC_E_NO_CREDENTIALS (0x8009030e)`
- Só o `web_fetch` do harness alcança a internet

### 9.12 `Hide()` encerra o `ShowDialog()`

Já documentado em [seção 7](#7-bandeja-e-ciclo-de-vida). Use `Opacity = 0`.

### 9.13 O VBS original tinha caminho absoluto fixo

`Iniciar.vbs` apontava para `D:\Documentos\deepseek-harness\default-workspace\...`.
Ao mover o projeto, o atalho quebrava silenciosamente.

**Correção:** resolver o caminho a partir da própria pasta do VBS:

```vbs
Set fso = CreateObject("Scripting.FileSystemObject")
base = fso.GetParentFolderName(WScript.ScriptFullName)
ps1  = base & "\CameraViewer.ps1"
```

### 9.14 `-Desinstalar` remove os atalhos reais

Os caminhos dos atalhos são **fixos** (Desktop / Startup), independentemente de
`-Pasta`. Rodar `-Desinstalar` numa pasta de teste **apaga os atalhos da
instalação real**. Depois disso, reinstale para restaurar.

---

## 10. Referência de API

### VLC ActiveX (via `$p.Ctl.Vlc`)

| Chamada | Uso |
|---|---|
| `playlist.items.clear()` | Limpa a playlist |
| `playlist.add(url, nome, opcoes)` | Adiciona item. `opcoes = ':rtsp-tcp'` força TCP |
| `playlist.play()` | Inicia |
| `playlist.stop()` | Para |
| `playlist.clear()` | Limpa |
| `input.state` | Estado: `3`=playing, `4`=paused, `7`=error |
| `video.takeSnapshot()` | Salva BMP na pasta de temp |
| `video.toggleFullscreen()` | Alterna tela cheia |

### WinForms úteis

| API | Uso no projeto |
|---|---|
| `AxHost`/`GetOcx()` | Hospedar o ActiveX do VLC |
| `NotifyIcon` | Bandeja do sistema |
| `TableLayoutPanel.SetColumnSpan` | Última câmera ocupa 2 colunas |
| `Form.Opacity` | Esconder sem encerrar o `ShowDialog()` |
| `ToolTip.SetToolTip` | Dicas dos botões |

### Win32

| API | Uso |
|---|---|
| `shell32!SetCurrentProcessExplicitAppUserModelID` | Agrupamento na barra de tarefas |
| `SHGetFileInfo` | Verificar o ícone real de um `.lnk` |

### .NET

| API | Uso |
|---|---|
| `System.Threading.Mutex` | Instância única |
| `System.Net.Sockets.TcpListener` | Relay RTSP |
| `System.Text.RegularExpressions.Regex` | Parsing de headers RTSP |
| `Encoding.GetEncoding(28591)` | Lat1 para headers RTSP |
