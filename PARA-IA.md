# Guia para IAs — continuando o Central de Câmeras

> **Leia este arquivo inteiro antes de tocar em qualquer coisa.**
> Ele existe para que uma IA sem histórico de conversa consiga assumir o
> projeto e não repetir os experimentos que já falharam.

---

## 1. O que é este projeto

Um programa Windows que mostra **três câmeras de vigilância em uma única
janela**: duas **Yoosee** (192.168.0.7 e 192.168.0.2) e uma **iCSee**
(192.168.0.5).

O ponto central do projeto **não** é a UI — é o **relay RTSP** que contorna
um defeito de firmware das Yoosee. Qualquer mudança que toque o relay ou o
comportamento do RTSP pode quebrar as câmeras silenciosamente.

### Stack

- **PowerShell 5.1** + **WinForms** (a UI inteira)
- **VLC ActiveX** embarcado (decodifica e exibe o vídeo)
- **C# compilado em runtime** via `Add-Type` (o relay RTSP)
- **ffmpeg** (opcional — só para gravar)

Não há projeto de build, gerenciador de pacotes, teste unitário nem CI.
"Compilar" = o PowerShell compila o `.cs` na hora. "Testar" = rodar o app e
olhar.

---

## 2. Ordem de leitura

1. **Este arquivo** — contexto, armadilhas, o que fazer
2. **[ARQUITETURA.md](ARQUITETURA.md)** — como cada peça funciona
3. **[DECISOES.md](DECISOES.md)** — o que já foi decidido e por quê
4. `CameraViewer.ps1` (673 linhas) e `RtspRelay.cs` (234 linhas)
5. **[README.md](README.md)** — para o usuário final

---

## 3. Mapa do repositório

```
central-de-cameras/
├── CameraViewer.ps1      ← A APLICAÇÃO (UI, players, gravação, bandeja)
├── Ui.cs                 ← DESIGN SYSTEM (tokens, ícones, componentes). Compilado em runtime
├── Ptz.cs                ← CONTROLE PTZ via ONVIF (D-pad). Compilado em runtime
├── RtspRelay.cs          ← O RELAY RTSP (o coração do projeto)
├── cameras.json          ← Configuração das câmeras
├── icone.ico             ← Ícone (DIB 16/32/48/256 — não mudar os tamanhos)
├── Instalar.bat          ← Eleva privilégios → Instalador.ps1
├── Instalador.ps1        ← Diagnóstico → instalação → teste → relatório
├── Instalar.ps1          ← ⚠️ LEGADO. Gerador de ícone/atalhos antigo.
│                           Superseded pelo Instalador.ps1. Não use.
├── LEIA-ME.txt           ← Manual do usuário final (texto simples)
├── Testar-Cameras.ps1    ← Descobre URLs RTSP com ffprobe
├── Iniciar.vbs           ← Inicia sem console (caminho RELATIVO à própria pasta)
├── Iniciar-Cameras.cmd   ← Atalho alternativo
├── README.md             ← Documentação principal
├── ARQUITETURA.md        ← Referência técnica do app de PC
├── DECISOES.md           ← Log de decisões
├── PARA-IA.md            ← Este arquivo
│
└── ANDROID/              ← APP ANDROID (ver seção 12)
    ├── gateway/          ← servidor que roda no PC (Node.js + ffmpeg + relay)
    ├── app/src/main/     ← código Kotlin/Compose
    ├── dist/             ← APK pronto
    ├── README.md         ← documentação do app
    └── ARQUITETURA-ANDROID.md ← decisões técnicas do app
```

⚠️ **`Instalar.ps1` é legado.** Foi o gerador de ícone/atalhos da primeira
versão. O instalador de verdade é **`Instalador.ps1`** (com "or"). Mantenha os
dois ou apague o legado com cuidado — mas **não confunda os dois**.

---

## 4. Contexto do hardware

### As câmeras

| Câmera | IP | Modelo | Auth RTSP | Transporte |
|---|---|---|---|---|
| iCSee | `192.168.0.5` | Xiongmai/iCSee | Digest **correto** ✅ | TCP ok |
| Yoosee Cozinha | `192.168.0.7` | Yoosee | **bypass** (firmware quebrado) | UDP obrigatório |
| Yoosee Sala | `192.168.0.2` | Yoosee | **bypass** (firmware quebrado) | TCP ok |

### Credenciais

- iCSee: usuário e senha definidos no `cameras.json` local (não versionado)
- Yoosee: conta do app Yoosee — **não** é a senha RTSP do dispositivo
- O relay usa `username="admin"` (o firmware aceita sem validar)

> **Nunca** coloque credenciais em repositório público.

### Comportamentos que parecem bug mas NÃO são

1. **A Yoosee aceita ~1 sessão RTSP por vez.** Gravar pausa o ao vivo
   daquela câmera. É o firmware.
2. **A Yoosee mantém a sessão por ~60 s** se o TEARDOWN não for enviado. É
   por isso que o relay existe.
3. **A Yoosee `.7` congela o TCP após ~2,3 s.** Por isso ela fica em UDP.
4. **A Yoosee recusa Digest correto com `400 Bad Request`.** É um bypass de
   autenticação, não um problema de senha.

---

## 5. O que já foi tentado e NÃO funciona

### ❌ Digest correto na Yoosee

```
rtsp://admin:senha@192.168.0.7:554/onvif1 → 400 Bad Request
```

O firmware **rejeita** o `response=` calculado. Só aceita o header malformado
`Authorization: Digest username="admin"`.

**Não tente "arrumar" a autenticação.** Já foi provado.

### ❌ Port forwarding do Windows para a câmera

Não reescreve headers. Não resolve nem auth nem `Transport`.

### ❌ `Form.Hide()` para esconder na bandeja

**Encerra o `ShowDialog()`.** Esconder = fechar. Use `Opacity = 0`.

### ❌ Timer do WinForms em variável local

É coletado pelo GC e para de disparar. Use `$script:`.

### ❌ `$script:Recording = @()`

Precisa ser **`@{}`** (Hashtable) para `.ContainsKey()`.

### ❌ Usar a variável `$host`

É reservada do PowerShell. Use `$vlcCtl` / `Ctl`.

### ❌ `ExtractIconEx` para verificar o ícone de um `.lnk`

Retorna "sem ícone" **sempre**, até para atalhos comprovadamente bons
(VS Code, CapCut). É limitação da API. Use `SHGetFileInfo`.

### ❌ `SendMessage` + `BM_CLICK` em diálogo modal

Deadlock. Use `PostMessage`.

### ❌ `-Parallel` no PowerShell

Só existe no PowerShell 7+. O ambiente usa o 5.1.

### ❌ Câmera ímpar ocupando meia linha

A última câmera usa `SetColumnSpan($tile, 2)` para não deixar buraco.

---

## 6. Antes de mudar qualquer coisa

### Checklist de mudanças na UI

- [ ] Alvos de **44 px** de altura
- [ ] Texto **≥ 12 px**
- [ ] Espaçamento múltiplo de **4/8 px**
- [ ] Contraste **≥ 4,5:1** para texto normal
- [ ] Status sempre com **ponto + texto** (nunca só cor)
- [ ] Todo botão novo tem `ToolTip` e `AccessibleName`
- [ ] Cores, fontes e componentes só vêm de `Ui.cs` (nunca hex solto, nunca emoji como ícone)
- [ ] `.ps1`/`.cs` com acentos: salvar como UTF-8 **com BOM**
- [ ] Não quebrar a paleta (use os tokens de `[Tema]`)

### Checklist de mudanças no relay

- [ ] Manter **Latino-1** para headers (nunca UTF-8)
- [ ] Manter a detecção de modo **RAW** (byte `$` = 0x24)
- [ ] Não remover o `TEARDOWN` no disconnect
- [ ] Não enviar `Authorization` do cliente em paralelo ao malformado
- [ ] Manter `ReuseAddress` + `IPAddress.Loopback` + porta 0

### Checklist de mudanças na gravação

- [ ] `-nostdin` **presente** (senão a UI trava)
- [ ] `-c copy` mantido
- [ ] Considerar que a câmera só aceita 1 sessão

### Armadilhas já pagas

| Sintoma | Causa | Correção |
|---|---|---|
| Texto aparece como `InicializaÇõÇœo` / `Câmeras` vira `CÃ¢meras` | O `.ps1` foi salvo **sem BOM UTF-8** e o PowerShell 5.1 lê como ANSI | Regravar com BOM: `[System.IO.File]::WriteAllBytes($p, ([byte[]](239,187,191)) + $bytes)` |
| `-InicioAutomatico:$false` → *"Cannot convert value System.String to Nullable[Boolean]"* | Com `-File`, o PowerShell **não avalia** o `$false`: chega a string literal `$false` | Aceitar `[string]` e converter (`ligar`/`desligar`, `true`/`false`) |
| Item de menu marcável não mostra o check | `ShowImageMargin = $false` também esconde a margem de check | Definir `ShowCheckMargin = $true` **e** refletir o estado no texto do item |

> Ferramentas de edição automática **removem o BOM** com facilidade. Depois de
> editar qualquer `.ps1` com acentos, confira os três primeiros bytes antes de
> testar (`239,187,191`).

---

## 7. Como testar

### Ambiente

As câmeras estão na rede local. Verifique antes:

```powershell
Test-NetConnection -ComputerName 192.168.0.7 -Port 554
Test-NetConnection -ComputerName 192.168.0.2 -Port 554
Test-NetConnection -ComputerName 192.168.0.5 -Port 554
```

Se falhar: PC fora da rede, câmera desligada, ou *AP isolation* ligado no
roteador.

### Rodar o app

```powershell
cd D:\Documentos\projects\central-de-cameras
powershell -NoProfile -ExecutionPolicy Bypass -File .\CameraViewer.ps1
```

### Teste automático (fecha sozinho)

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\CameraViewer.ps1 -SelfTestSeconds 15
```

### Captura de tela

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\CameraViewer.ps1 `
  -SelfTestSeconds 15 -Screenshot .\saida.png
```

> ⚠️ **O `-Screenshot` só funciona com a janela focada.** O harness executa o
> comando sem focar a janela → a captura sai errada (ou vazia).
>
> Workaround usado com sucesso:
> ```powershell
> ShowWindow($h, 9)                                        # SW_RESTORE
> SetWindowPos($h, [IntPtr](-1), 0,0,0,0, 0x0001 -bor 0x0002)  # TOPMOST
> SetForegroundWindow($h)
> Start-Sleep -Milliseconds 400
> $g.CopyFromScreen($form.Location, [Point]::Empty, $form.Size)
> ```

### Testar o instalador

```powershell
# Diagnóstico puro (não instala nada)
.\Instalar.bat -SomenteDiagnostico -SemTeste

# Instalação de teste numa pasta temporária
.\Instalar.bat -Pasta "$env:TEMP\TesteCentralCameras"

# Remover
.\Instalar.bat -Pasta "$env:TEMP\TesteCentralCameras" -Desinstalar
```

> ⚠️ **`-Desinstalar` apaga os atalhos REAIS** (Desktop e Startup), porque
> esses caminhos são fixos e não dependem de `-Pasta`. Depois de testar,
> **reinstale** para restaurar.

### Verificar o ícone de um atalho

```powershell
# SHGetFileInfo — o que o Explorer usa
# flags: 0x000004000 (SHGFI_ICON) | 0x000000100 (SHGFI_USEFILEATTRIBUTES)
# pixel de referência do ícone atual: -13726784
```

### Testar o relay isoladamente

```powershell
# Descobrir o caminho RTSP de uma câmera
.\Testar-Cameras.ps1 -IP 192.168.0.7 -Usuario admin -Senha "SENHA_DO_DISPOSITIVO"
```

---

## 8. Limitações do ambiente de trabalho

Este ambiente é **incomodo** e é importante saber dos limites antes de
perder tempo:

| Limitação | Workaround |
|---|---|
| Sem Python (stub da Store) | Usar PowerShell + .NET |
| `git clone` e `curl` falham (`SEC_E_NO_CREDENTIALS`) | Só o `web_fetch` do harness alcança a internet |
| Sem `-Parallel` (PS 5.1) | `Start-Job` ou sequencial |
| Processos filhos morrem quando o comando do harness termina | Iniciar app e verificar **no mesmo comando** |
| `Add-Type` sem toolchain | Compilar C# inline |
| Ícones de arquivo não são lidos por `ExtractIconEx` | Usar `SHGetFileInfo` |

---

## 9. Tarefas comuns — como abordar

### "A câmera não abre"

1. `Test-NetConnection` na porta 554
2. Se a porta responde mas o vídeo não → o relay falhou
3. Verificar o `Write-Warning "Relay $destino falhou: ..."` no console
4. Abrir o app **fora** do harness (duplo clique) para ver o console
5. Verificar se a câmera está ocupada por outra sessão (≈60 s)

### "O ícone do atalho virou genérico"

Regenerou o `.ico` com tamanhos errados? O shell rejeita o arquivo inteiro se
houver entradas em 24/64/128 ou PNG em 128. Refazer DIB-only 16/32/48/256.

### "A gravação parou de funcionar"

1. `ffmpeg` está no PATH? (`Get-Command ffmpeg`)
2. A câmera está ocupada por outra sessão?
3. O `.ts` parcial tem tamanho razoável? (< 80 KB após 12 s = travou)
4. O watchdog deveria estar tentando de novo — verificar o status
   `tentando (N/8)`. Se não aparece, `$script:wd` não está rodando
   (timer coletado pelo GC?).

### "A janela some ao minimizar"

`Hide()` foi usado? Deve ser `Opacity = 0` + `ShowInTaskbar = $false`.

### "Quero adicionar uma câmera nova"

1. `Testar-Cameras.ps1 -IP <novo-ip> -Usuario admin -Senha "<senha>"`
2. Se for iCSee/Xiongmai → URL direta com Digest, `"tcp": true`
3. Se for Yoosee → `"url": "rtsp://127.0.0.1:8613/onvif1"`,
   `"relay": "<ip>:554"`, `"tcp": false`
4. A porta no `url` é **decorativa** — o relay usa porta efêmera e sobrescreve

### "Quero mudar o visual"

Use o skill **`ui-ux-pro-max`** (já instalado em
`C:\Users\pedro\.agents\skills\ui-ux-pro-max\`). Respeite o checklist de UI da
seção 6.

> ⚠️ O skill foi instalado em versão **reduzida** (só `SKILL.md` e
> `references/pro-rules.md`) — a base de dados pesquisável precisa de Python,
> que não existe neste ambiente.

---

## 10. O que ainda NÃO foi feito

- [ ] Tema claro
- [ ] Teste de navegação por teclado / leitor de tela
- [ ] Medição de uso de CPU em rodadas longas
- [ ] Instalador que **instale** VLC/ffmpeg automaticamente (os downloads
      estão bloqueados neste ambiente — hoje o instalador só *reporta* e dá
      dicas de `winget`)
- [ ] `cameras.json` com credenciais cifradas
- [ ] Testes automatizados de verdade (unitários)
- [ ] Registro em arquivo (log) das falhas de conexão

---

## 11. Resumo em 10 linhas

1. App Windows em **PowerShell + WinForms**, vídeo via **VLC ActiveX**.
2. O núcleo é o **`RtspRelay.cs`**: contorna 3 defeitos do firmware Yoosee.
3. Auth: as Yoosee só aceitam `Digest username="admin"` malformado.
4. Transport: o relay reescreve `RTP/AVP` → `RTP/AVP/TCP` quando preciso.
5. Sessão: o relay manda `TEARDOWN` ao disconnect — senão a câmera trava 60 s.
6. Relay ouve em porta **efêmera** em `127.0.0.1` (nunca conflita).
7. Gravação: `ffmpeg -c copy` + watchdog de retry (8 tentativas, backoff 25 s).
8. Bandeja: esconder com `Opacity = 0` (**nunca** `Hide()`).
9. Instalar: `Instalar.bat` (eleva) → `Instalador.ps1` (faz tudo).
10. Leia `ARQUITETURA.md` + `DECISOES.md` antes de mexer.

---

## 12. App Android (pasta `ANDROID/`)

### Arquitetura em uma frase

O celular **não fala RTSP**. Um **gateway Node.js** roda no PC, converte as
câmeras para **HLS** e entrega por HTTP com token; o app Android só consome
HLS via ExoPlayer.

```
Celular ──HTTPS──▶ Cloudflare Tunnel ──▶ PC (gateway) ──RTSP──▶ Câmeras
```

### Componentes

| Peça | Tecnologia | Onde |
|---|---|---|
| Gateway | Node.js + ffmpeg + `RtspRelayCli.exe` | `ANDROID/gateway/` |
| Túnel | `cloudflared` (quick tunnel) | `ANDROID/gateway/cloudflared.exe` |
| App | Kotlin + Compose + Media3 ExoPlayer | `ANDROID/app/` |

### Detalhes que importam

- **H.264 720p na GPU (AMF)**: o gateway transcodifica com `h264_amf` para
  caber em 4G (~1,2 Mbps/câmera) e funcionar em qualquer celular. Modos
  alternativos por câmera: `"modo": "copy"` (sem converter) ou `"x264"`.
- **Áudio removido** (`-an`): as câmeras usam G.711/PCMA, que o HLS não
  carrega bem e o ExoPlayer não decodifica.
- **Token obrigatório**: o gateway não sobe sem `--token`. Tudo exige
  `Authorization: Bearer`.
- **Vigia a cada 20 s**: reinicia ffmpeg morto ou com playlist parada.
- **Mesmo relay**: `RtspRelayCli.cs` só é um `Main` em volta do
  `RtspRelay.cs` do projeto — o código do relay **não** foi duplicado.

### Armadilhas específicas do Android

| Armadilha | Detalhe |
|---|---|
| **AGP 9 tem Kotlin embutido** | **Não** adicione `org.jetbrains.kotlin.android` — o build quebra. O `org.jetbrains.kotlin.plugin.compose` continua obrigatório. |
| `compileSdk` precisa ser 37 | O AndroidX atual exige compilar contra a API 37 (o SDK tem a pasta `android-37.0`). |
| `-rw_timeout` não existe no FFmpeg 6 | Use `-timeout`; o `-rw_timeout` só existe do FFmpeg 7 em diante. |
| `calculateBottomPadding` | É membro de `PaddingValues`, **não** precisa de import. |
| Barra de navegação clara no Android 8.1 | `enableEdgeToEdge()` deixa a barra translúcida; pintar `navigationBarColor` explicitamente resolve. |
| Status da API ≠ status do vídeo | A API é consultada a cada 10 s; o tile deve mostrar o estado do **player**, não o da API. |
| `bash` não existe | Todo o ambiente é PowerShell + `.bat`. |

### Testar

```powershell
# gateway
cd ANDROID\gateway; .\Iniciar-Gateway.bat

# app (celular por USB)
adb install -r ANDROID\dist\CentralCameras.apk

# capturar a tela do aparelho (NAO use '>' no PowerShell: corrompe o PNG)
adb shell screencap -p /sdcard/s.png
adb pull /sdcard/s.png C:\temp\s.png
```

### Credenciais de teste usadas

- Gateway: porta `8080`, token em `ANDROID/gateway/token.txt`
- Rede: PC `192.168.0.3`, celular de teste Galaxy J4 Core `192.168.0.6`

### O que ainda falta no Android

- [ ] Endereço fixo do túnel (túnel nomeado com domínio próprio)
- [ ] Áudio
- [ ] Notificações de detecção de movimento
- [ ] Ícone/identidade visual própria por câmera
- [ ] Ver o app funcionando por 4G de verdade (o aparelho de teste não tinha
      internet no momento dos testes; o túnel foi validado a partir do PC)
