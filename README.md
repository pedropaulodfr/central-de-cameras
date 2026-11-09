# Central de Câmeras

Aplicação Windows (PowerShell + WinForms + VLC) que mostra **todas as câmeras de
vigilância em uma única janela**, com gravação, fotos, renomeação e bandeja do
sistema.

Feito para o caso real do dono: duas câmeras **Yoosee** (firmware com defeito de
autenticação RTSP) e uma **iCSee** (autenticação Digest correta) que só podiam
ser vistas em aplicativos separados, um por vez.

> 📱 **Também existe um app Android** para ver as mesmas câmeras de qualquer
> lugar, inclusive fora da rede. Veja **[ANDROID/README.md](ANDROID/README.md)**.

---

## Índice

- [O que o programa faz](#o-que-o-programa-faz)
- [Requisitos](#requisitos)
- [Instalação](#instalação)
- [Uso](#uso)
- [Arquivos do projeto](#arquivos-do-projeto)
- [Configuração das câmeras](#configuração-das-câmeras)
- [Arquitetura](#arquitetura)
- [Problemas conhecidos](#problemas-conhecidos)
- [App Android](#app-android)
- [Documentação para IA](#documentação-para-ia)

---

## O que o programa faz

| Recurso | Como funciona |
|---|---|
| **Visualização simultânea** | Grade 2 colunas, uma janela, VLC ActiveX embutido em cada tile |
| **Relay RTSP local** | Ponte em C# que contorna o defeito de autenticação das Yoosee |
| **Gravação** | `ffmpeg` grava todas as câmeras em `.ts` na pasta `Gravacoes` |
| **Fotos** | `VLC takeSnapshot` → JPEG na pasta `Fotos` |
| **Renomear** | Diálogo que persiste o nome no `cameras.json` |
| **Reconectar** | Botão ou tecla `F5` reinicia todos os players |
| **Tela cheia** | Botão ou tecla `F11` coloca o programa em tela cheia (`Esc` sai) |
| **Som / Mudo** (por câmera) | Liga/desliga o áudio de cada câmera; salvo em `mudo` no `cameras.json` |
| **Ampliar / Voltar** (por câmera) | Mostra só uma câmera ocupando toda a área (duplo clique também; `Esc` volta) |
| **Virar** (por câmera) | Abre o D-pad (cruz direcional) para virar a câmera fisicamente (PTZ via ONVIF). Segure a direção; o quadrado central para. Velocidade lenta/normal/rápida |
| **Reordenar** (por câmera) | Menu para trocar a posição da câmera na grade; a ordem é salva no `cameras.json` |
| **Bandeja do sistema** | Minimizar esconde a janela e continua rodando; menu "Abrir / Gravar / Sair" |
| **Status por câmera** | Bolinha colorida **e** texto ("ao vivo", "conectando", "sem sinal") |
| **Auto-instalador** | `Instalar.bat` diagnostica, instala, cria atalhos e testa as câmeras |

---

## Requisitos

### Obrigatório

- **Windows 10/11** (ou Windows 7+ com .NET 4.x)
- **PowerShell 5.1** ou superior (já vem no Windows 10/11)
- **.NET Framework 4.x**
- **VLC media player 3.x** — o componente **ActiveX** precisa estar marcado
  na instalação. É ele que exibe o vídeo.
- Rede local com as câmeras (mesmo Wi-Fi / roteador)

### Recomendado

- **ffmpeg** no `PATH` — sem ele o programa **abre e mostra** as câmeras, mas
  **não grava**. (`winget install Gyan.FFmpeg`)
- **ffprobe** no `PATH` — usado apenas pelo instalador e pelo script de teste.

### Sobre o firewall

O relay abre uma porta TCP local (`127.0.0.1`) e fala com a câmera na porta
554. O instalador tenta criar a regra `CentralCameras-Relay` automaticamente.
Se falhar, libere o PowerShell como rede privada.

---

## Instalação

### No PC de origem

```powershell
cd D:\Documentos\projects\central-de-cameras
.\Instalar.bat
```

O `.bat` solicita elevação de administrador e executa `Instalador.ps1`.
O programa é instalado em `%LOCALAPPDATA%\CentralCameras`.

### Em outro computador

1. Copie **a pasta inteira** `central-de-cameras` para o outro PC
2. Instale o VLC (marcando **ActiveX**): `winget install VideoLAN.VLC`
3. Instale o ffmpeg: `winget install Gyan.FFmpeg`
4. Dois cliques em `Instalar.bat`
5. Abra o atalho **Central de Câmeras** na Área de Trabalho

### Comandos do instalador

```powershell
# Só diagnostica, não instala nada
.\Instalar.bat -SomenteDiagnostico

# Instala em outra pasta
.\Instalar.bat -Pasta "D:\Apps\CentralCameras"

# Não cria o atalho de inicialização automática
.\Instalar.bat -SemInicializacao

# Não testa o acesso às câmeras (teste pode demorar)
.\Instalar.bat -SemTeste

# Remove atalhos e arquivos instalados
.\Instalar.bat -Desinstalar
```

> **Atenção:** `-Desinstalar` remove a pasta de instalação **e** as gravações e
> fotos que estavam dentro dela. Copie antes se precisar guardar.

---

## Uso

### Janela

- **Gravar** — começa/para a gravação de **todas** as câmeras de uma vez
  (o botão vira "Parar"). Arquivos `.ts` em `Gravacoes`.
- **Foto** — salva um JPEG de cada câmera em `Fotos`.
- **Gravacoes** — abre a pasta de gravações no Explorer.
- **Renomear** — muda o nome exibido de cada câmera; salva no `cameras.json`.
- **Reconectar** — reinicia todos os players (mesmo que `F5`).
- **Duplo clique** no nome ou no tile da câmera → tela cheia.

### Bandeja do sistema

O ícone na bandeja (próximo ao relógio) abre o menu:

- **Abrir janela** — restaura a janela
- **Gravar / Parar** — mesma ação do botão
- **Sair** — encerra de verdade

Minimizar a janela **não encerra o programa**: ele continua gravando e
mantendo as câmeras ao vivo. Para sair de fato, use **Sair** no menu da
bandeja.

### Atalhos de teclado

| Tecla | Ação |
|---|---|
| `F5` | Reconectar todas as câmeras |
| `F11` | Tela cheia do programa |
| `Esc` | Sai da câmera ampliada ou da tela cheia |

---

## Arquivos do projeto

| Arquivo | Papel |
|---|---|
| `CameraViewer.ps1` | **A aplicação.** UI, players VLC, gravação, fotos, renomeação, bandeja |
| `RtspRelay.cs` | **Relay RTSP em C#** — contorna o defeito de auth das Yoosee |
| `cameras.json` | Lista de câmeras (nome, URL, transporte, relay) |
| `icone.ico` | Ícone do app (DIB 16/32/48/256) |
| `Instalar.bat` | Wrapper batch: eleva privilégios e chama o `Instalador.ps1` |
| `Instalador.ps1` | Instalador real: diagnóstico, cópia, atalhos, firewall, teste |
| `LEIA-ME.txt` | Manual em texto simples para o usuário final |
| `Testar-Cameras.ps1` | Descobre qual URL RTSP funciona em cada câmera (ffprobe) |
| `Iniciar.vbs` | Inicia sem janela de console (caminho relativo à própria pasta) |
| `Iniciar-Cameras.cmd` | Atalho alternativo para rodar direto |

Pastas criadas em tempo de execução (ao lado do script):
`Temp/`, `Gravacoes/`, `Fotos/`.

---

## Configuração das câmeras

Arquivo `cameras.json` (em `%LOCALAPPDATA%\CentralCameras` após instalar):

```json
[
    {
        "nome":  "iCSee",
        "url":  "rtsp://USUARIO:SENHA@192.168.0.5:554/onvif1",
        "tcp":  true
    },
    {
        "nome":  "Yoosee Cozinha",
        "url":  "rtsp://127.0.0.1:8611/onvif1",
        "relay": "192.168.0.7:554"
    },
    {
        "nome":  "Yoosee Sala",
        "url":  "rtsp://127.0.0.1:8612/onvif1",
        "relay": "192.168.0.2:554",
        "recTcp": true
    }
]
```

### Campos

| Campo | Significado |
|---|---|
| `nome` | Nome exibido no tile. Editável pelo botão **Renomear**. |
| `url` | URL RTSP que o VLC abre. |
| `tcp` | `true` = força `rtsp-tcp` para o VLC. `false`/ausente = UDP. |
| `relay` | `host:porta` real da câmera. Quando presente, o programa cria o relay e **sobrescreve** a `url` com a porta efêmera real. |
| `recTcp` | `true` = grava via TCP. Ausente/`false` = grava via UDP. |
| `ptz` | `"ip:porta"` do serviço ONVIF para virar a câmera. Sem relay, o padrão é o IP da URL na porta 8899. |
| `ptzUsuario` / `ptzSenha` | Credenciais ONVIF, se diferirem das da URL RTSP. |
| `ptzInverterX` / `ptzInverterY` | `true` inverte esquerda/direita (ou cima/baixo) no D-pad. A iCSee `.5` precisa de `ptzInverterX`. |
| `ptzRepetir` | `true` reenvia o comando enquanto a direção está pressionada. Necessário nas Yoosee, que andam em passos curtos. |

### Câmeras iCSee / Xiongmai

Autenticação Digest **correta**. Coloque usuário e senha na URL
(`rtsp://usuario:senha@ip:554/onvif1`). Use `"tcp": true`.

### Câmeras Yoosee

O firmware **rejeita** Digest bem calculado com `400 Bad Request`, mas aceita
um cabeçalho malformado `Authorization: Digest username="admin"` (sem
`response=`). É um defeito de firmware — um bypass de autenticação.

Por isso a URL aponta para `127.0.0.1` e o campo `relay` aponta para o IP
real: o relay em C# injeta o cabeçalho malformado e corrige o `Transport`.

> ⚠️ **Risco de segurança:** o bypass funciona para *qualquer* pessoa na rede
> local. Troque a senha do dispositivo Wi-Fi da câmera, use VLAN ou rede
> dedicada, e não exponha a câmera à internet.

### Descobrir a URL certa de uma câmera nova

```powershell
.\Testar-Cameras.ps1 -IP 192.168.0.9 -Usuario admin -Senha "SENHA_DO_DISPOSITIVO"
```

Testa 15 caminhos RTSP comuns (`/onvif1`, `/live/ch0`, `/Streaming/Channels/101`,
`/cam/realmonitor?channel=1&subtype=0`, …) e diz qual funciona.

> A senha é a **do dispositivo** (definida no app da câmera), não a do Wi-Fi.

---

## Arquitetura

```
┌──────────────────────────────────────────────────────────┐
│  CameraViewer.ps1  (PowerShell + WinForms)                │
│  ┌────────┐ ┌────────┐ ┌────────┐                         │
│  │ VLC #1 │ │ VLC #2 │ │ VLC #3 │   ← ActiveX embutido  │
│  └───┬────┘ └───┬────┘ └───┬────┘                         │
│      │          │          │                               │
│      │     127.0.0.1:ephem (relay)                        │
│      │          │          │                               │
│  ┌───▼──────────▼───┐      │                               │
│  │  RtspRelay.cs    │      │                               │
│  │  • injeta Auth   │      │                               │
│  │  • corrige Transp│      │                               │
│  │  • manda TEARDOWN│      │                               │
│  └───────┬──────────┘      │                               │
└──────────┼─────────────────┼───────────────────────────────┘
           │  RTSP 554       │
    ┌──────┴──────┐   ┌──────┴──────┐
    │  iCSee .5   │   │ Yoosee .7/.2│
    │  (Digest OK)│   │  (bypass)   │
    └─────────────┘   └─────────────┘
```

**Fluxo de uma câmera Yoosee:**

1. `CameraViewer.ps1` lê `cameras.json`
2. Detecta o campo `relay` → cria `RtspRelay(destino, 554, 0)` (porta 0 =
   efêmera, nunca conflita)
3. O relay sobe em `127.0.0.1:<porta_efemera>`
4. O VLC abre `rtsp://127.0.0.1:<porta_efemera>/onvif1`
5. O relay reescreve a URL para o IP real, remove o `Authorization` do cliente
   e injeta `Authorization: Digest username="admin"`
6. O firmware responde `200 OK` — o stream funciona
7. Na resposta do `SETUP`, o relay troca `Transport: RTP/AVP;` por
   `Transport: RTP/AVP/TCP;` quando o cliente pediu TCP (senão o ffmpeg/VLC
   rejeita com *"Nonmatching transport in server reply"*)
8. Ao desconectar, o relay manda `TEARDOWN` para a câmera liberar a sessão

Detalhes completos em **[ARQUITETURA.md](ARQUITETURA.md)**.

---

## Problemas conhecidos

| Problema | Causa | Solução / Estado |
|---|---|---|
| Câmera não abre | Firewall, AP isolation ou IP errado | Mesmo roteador; desligue *AP isolation*; rode `Testar-Cameras.ps1` |
| "sem sinal" na Yoosee | Sessão RTSP anterior ainda pendurada na câmera | Feche pelo menu **Sair** da bandeja; espere ~60 s |
| Gravar falha | ffmpeg ausente | `winget install Gyan.FFmpeg` |
| Vídeo pausa ao gravar | Firmware Yoosee aceita ~1 sessão RTSP por vez | Comportamento esperado; o app pausa o ao vivo daquela câmera enquanto grava |
| Ícone do atalho genérico | `.ico` com tamanhos inválidos | O `icone.ico` atual é DIB-only 16/32/48/256 — não altere os tamanhos |
| `cameras.json` com senha em texto puro | Necessidade de auto-login do VLC | Troque a senha do dispositivo ou use VPN |
| Ao vivo congela na Yoosee `.7` | Estouro de TCP | Permanece em UDP (`recTcp` ausente) por decisão |

---

## App Android

Além do programa de PC, o projeto tem um **app Android** que mostra as mesmas
câmeras **de qualquer lugar**, inclusive fora da rede (4G, outro Wi-Fi).

### Como funciona em duas linhas

O celular não fala RTSP. Um **gateway** roda no PC, converte as câmeras para
HLS e entrega por HTTP com token. O acesso externo usa **Cloudflare Tunnel**,
que dispensa abrir porta no roteador.

```
Celular ──HTTPS──▶ Cloudflare ──▶ PC (gateway) ──RTSP──▶ Câmeras
```

### Início rápido

```powershell
# 1. no PC
cd ANDROID\gateway
.\Iniciar-Gateway.bat          # mostra o token

# 2. opcional: acesso de fora da rede
.\Iniciar-Tunel.bat            # mostra a URL pública

# 3. no celular
adb install -r ANDROID\dist\CentralCameras.apk
```

No app: informe o **endereço** (o que o gateway mostrou) e o **token**.

### O que o app tem

- Grade com todas as câmeras, ao vivo
- Tela cheia por câmera (com controles que somem sozinhos)
- Estado por câmera: ponto colorido **e** texto ("ao vivo", "conectando", "sem sinal")
- Tema escuro consistente com o app de PC
- Atualização automática do estado a cada 10 segundos

📖 **Documentação completa:** **[ANDROID/README.md](ANDROID/README.md)**
(decisões técnicas: [ARQUITETURA-ANDROID.md](ANDROID/ARQUITETURA-ANDROID.md))

---

## Documentação para IA

Se você é uma IA continuando este projeto, leia nesta ordem:

1. **[PARA-IA.md](PARA-IA.md)** — handoff, o que já foi tentado, o que NÃO fazer
2. **[ARQUITETURA.md](ARQUITETURA.md)** — referência técnica detalhada
3. **[DECISOES.md](DECISOES.md)** — log de decisões e armadilhas
4. `CameraViewer.ps1` e `RtspRelay.cs` — o código

### Ambiente de desenvolvimento

```powershell
# Rodar direto do projeto
powershell -NoProfile -ExecutionPolicy Bypass -File .\CameraViewer.ps1

# Teste automático: abre, espera N segundos e fecha
powershell -NoProfile -ExecutionPolicy Bypass -File .\CameraViewer.ps1 -SelfTestSeconds 12

# Com captura de tela (verificar visualmente)
powershell -NoProfile -ExecutionPolicy Bypass -File .\CameraViewer.ps1 -SelfTestSeconds 12 -Screenshot .\saida.png
```

> ⚠️ O `-Screenshot` **só funciona se a janela estiver visível e em primeiro
> plano**. O harness que executa o comando não foca a janela automaticamente;
> force com `ShowWindow`/`SetForegroundWindow` antes do `CopyFromScreen`.

### Parâmetros de `CameraViewer.ps1`

| Parâmetro | Tipo | Efeito |
|---|---|---|
| `-Config` | string | Caminho de outro `cameras.json` |
| `-SelfTestSeconds` | int | Fecha sozinho após N segundos |
| `-Screenshot` | string | Salva PNG da tela ao fechar |
| `-AutoRecord` | switch | Começa a gravar ao abrir |
| `-AutoSnapshot` | switch | Tira fotos a cada 9 s |

---

## Licença

Uso interno. Sem licença formal definida.
