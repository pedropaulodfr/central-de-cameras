# Log de Decisões

Registro cronológico das decisões técnicas, **por que** cada uma foi tomada e
o que foi descartado no caminho. Serve para não repetir experimentos que já
falharam.

Formato: cada entrada tem o contexto, a decisão, as alternativas descartadas e
o estado atual.

---

## Índice

- [D-01 · Linguagem e runtime](#d-01--linguagem-e-runtime)
- [D-02 · Player: VLC ActiveX](#d-02--player-vlc-activex)
- [D-03 · Relay RTSP em C#](#d-03--relay-rtsp-em-c)
- [D-04 · Bypass de autenticação das Yoosee](#d-04--bypass-de-autenticação-das-yoosee)
- [D-05 · Correção do header Transport](#d-05--correção-do-header-transport)
- [D-06 · TEARDOWN explícito](#d-06--teardown-explícito)
- [D-07 · Porta efêmera no relay](#d-07--porta-efêmera-no-relay)
- [D-08 · UDP vs TCP por câmera](#d-08--udp-vs-tcp-por-câmera)
- [D-09 · Gravação com ffmpeg e `-c copy`](#d-09--gravação-com-ffmpeg-e--c-copy)
- [D-10 · Watchdog de gravação com retry](#d-10--watchdog-de-gravação-com-retry)
- [D-11 · Esconder via Opacity, não Hide](#d-11--esconder-via-opacity-não-hide)
- [D-12 · Instância única com Mutex](#d-12--instância-única-com-mutex)
- [D-13 · AppUserModelID](#d-13--appusermodelid)
- [D-14 · Ícone `.ico` DIB-only](#d-14--ícone-ico-dib-only)
- [D-15 · Atalho apontando para `wscript.exe`](#d-15--atalho-apontando-para-wscriptexe)
- [D-16 · Estrutura do instalador](#d-16--estrutura-do-instalador)
- [D-17 · `cameras.json` como fonte única](#d-17--camerasjson-como-fonte-única)
- [D-18 · Remoção da feature de vigilância](#d-18--remoção-da-feature-de-vigilância)
- [D-19 · Paleta escura e](#d-19--paleta-escura-e)
- [D-20 · `Iniciar.vbs` com caminho relativo](#d-20--iniciarvbs-com-caminho-relativo)
- [D-21 · Migração para `D:\Documentos\projects`](#d-21--migração-para-ddocumentosprojects)

---

## D-01 · Linguagem e runtime

**Contexto.** O usuário queria um programa de PC que mostrasse duas câmeras em
uma janela. O PC de destino é Windows comum, sem toolchain.

**Decisão.** PowerShell 5.1 + WinForms, com C# compilado em runtime via
`Add-Type` para as partes que exigem sockets/byte-a-byte.

**Descartado.**
- **Electron / Tauri** — exige Node, empacotamento pesado, hundreds of MB
- **.NET Core / C# com publish** — exige SDK no PC de destino
- **Python + PyQt** — o ambiente nem tem Python (stub da Microsoft Store)

**Estado.** Funcional e distribuído como um conjunto de arquivos + `Instalar.bat`.

---

## D-02 · Player: VLC ActiveX

**Contexto.** Precisa-se de decodificação H.264 confiável, indicador de estado
do stream e captura de foto.

**Decisão.** VLC 3.x **ActiveX** embutido no WinForms via subclasse de
`AxHost` (`VlcHost`).

**Descartado.**
- **OpenCV (`cv2.VideoCapture`)** — sem suporte decente a RTSP/H.264 no
  Windows, sem estado de stream, sem snapshot nativo
- **`libvlc` via `libvlcSharp`** — exige DLLs nativas no caminho
- **ffplay/ffmpeg para exibir** — sem API de estado nem de tela cheia
- **Reproduzir via `Process` + janela separada** — inviável em tiles

**Estado.** Implementado. Dependência externa: VLC instalado **com ActiveX**.

---

## D-03 · Relay RTSP em C#

**Contexto.** O firmware Yoosee recusa o handshake RTSP padrão. É preciso
interceptar e reescrever o tráfego TCP entre o player e a câmera.

**Decisão.** Uma classe C# `RtspRelay` compilada em runtime com
`Add-Type -Path RtspRelay.cs`.

**Descartado.**
- **Port forwarding / proxy de porta do Windows (netsh)** — não reescreve
  headers, então não resolve nem o auth nem o `Transport`
- **Implementar o relay em PowerShell com `TcpClient`/`NetworkStream`** —
  viável masfragilizado: parsing de byte-a-byte, regex e o estado
  header/body/raw ficam muito difíceis de acertar
- **Usar `fMP4`/`hls` da câmera** — as Yoosee deste modelo não expõem

**Estado.** Implementado e validado (0/5 → 5/5 câmeras conectando).

---

## D-04 · Bypass de autenticação das Yoosee

**Contexto.** `ffprobe`/VLC com Digest correto → **`400 Bad Request`**.
A câmera aceita um cabeçalho `Authorization` **malformado**:

```
Authorization: Digest username="admin"
```

(sem `response=`, sem `uri`, sem `nonce`). É um defeito de firmware: a câmera
valida a *presença* do header, não o conteúdo.

**Decisão.** O relay **remove** o `Authorization` do cliente e **injeta** o
malformado.

**Alternativa testada.** Digest corretamente calculado com `nonce`/`response`
→ `400 Bad Request`. Confirmado que é um bypass, não um problema de credencial.

**Risco.** Qualquer pessoa na LAN consegue puxar o stream. Mitigação
documentada: senha forte do dispositivo, rede dedicada / VLAN, sem exposição
à internet.

**Estado.** Implementado. **Não tente "consertar" o relay para enviar Digest
correto** — já foi provado que a câmera recusa.

---

## D-05 · Correção do header Transport

**Contexto.** O cliente pede TCP interleaved; a câmera aceita, mas responde
`Transport: RTP/AVP;` (sem `/TCP`). O VLC/ffmpeg aborta com:

```
Nonmatching transport in server reply
```

**Decisão.** No `RewriteResponse`, quando `wantTcp` é verdadeiro, trocar
`Transport: RTP/AVP;` → `Transport: RTP/AVP/TCP;`.

**Estado.** Implementado. Só aplica se o cliente pediu TCP — com UDP o header
original é preservado.

---

## D-06 · TEARDOWN explícito

**Contexto.** Ao fechar o player, a Yoosee **mantém a sessão pendurada**. A
próxima tentativa de conectar falha por ~60 s. Era a causa da gravação
"intermitente" da câmera `.2`.

**Decisão.** O relay captura o `Session:` da resposta do `SETUP` e a URL base do
`DESCRIBE`, e envia `TEARDOWN` quando o cliente desconecta.

**Estado.** Implementado em `SendTeardown`. É a correção que estabilizou a
gravação da Yoosee Sala.

---

## D-07 · Porta efêmera no relay

**Contexto.** Duas instâncias do app (ou uma reinicialização rápida) causavam:

```
Normalmente só é permitido uma utilização de cada endereço de socket
```

**Decisão.** `New-Object RtspRelay($destino, $portaRemota, **0**)` — porta 0 =
o Windows escolhe uma livre. O relay expõe `Port` com a porta efetiva, e o
app monta a URL com ela.

Complementos:
- `IPAddress.Loopback` — relay inalcançável pela rede
- `SocketOptionName.ReuseAddress` — reabrir porta mesmo em `TIME_WAIT`

**Estado.** Implementado. O `cameras.json` tem portas fixas (`8611`, `8612`)
apenas como **documentação** — são sobrescritas em runtime.

---

## D-08 · UDP vs TCP por câmera

**Contexto.** A Yoosee `.7` **congela o stream TCP após ~2,3 s**. A `.2` grava
bem em TCP.

**Decisão.** Transporte **por câmera**, configurável:

| Campo | Significado |
|---|---|
| `tcp` | Transporte do **VLC** (ao vivo) |
| `recTcp` | Transporte do **ffmpeg** (gravação) |

Configuração atual: `.7` sem `recTcp` (gravação em UDP); `.2` com
`recTcp: true` (gravação em TCP).

**Estado.** Implementado. É uma peculiaridade do firmware — não tente
"padronizar" para TCP.

---

## D-09 · Gravação com ffmpeg e `-c copy`

**Decisão.** `ffmpeg -rtsp_transport {tcp|udp} -i <url> -c copy -y <arquivo.ts>`

- `-c copy` = remux, **sem recodificar** → CPU baixa, qualidade original
- `.ts` = container nativo de MPEG-TS, tolerante a interrupção (grava parcial
  ainda é reproduzível)
- `-nostdin` **obrigatório** — sem ele o ffmpeg herda o stdin do console e
  trava a UI inteira

**Descartado.** Gravar via VLC (API de gravação do ActiveX é pouco confiável
para RTSP longo), ou reprocessar com `-re` (gera delay).

**Estado.** Implementado. ffmpeg é **opcional** — o programa abre e mostra
sem ele, só não grava.

---

## D-10 · Watchdog de gravação com retry

**Contexto.** Gravações falhavam silenciosamente (processo saía, arquivo com
poucos KB) e ficavam travadas sem feedback.

**Decisão.** Timer de 2 s (`$script:wd`) que verifica cada gravação:

```powershell
$falhou = ($parado -and $tam -lt 150000) -or ($passou -ge 12 -and $tam -lt 80000)
```

Dois limiares:
- processo **saiu** + arquivo < 150 KB → falha
- processo **vivo há ≥ 12 s** + arquivo < 80 KB → travou

Retry: até **8 tentativas**, backoff exponencial limitado a 25 s
(`min(25, 2^tentativas)` = 4, 8, 16, 25, 25…). O delay existe porque a
Yoosee precisa de tempo para liberar a sessão anterior.

**Estado.** Implementado. O status mostra `tentando (N/8)`.

---

## D-11 · Esconder via Opacity, não Hide

**Contexto.** `Form.Hide()` **encerra o `ShowDialog()`** — o loop de mensagens
termina, o script segue para o cleanup e o app morre. Esconder = fechar.

**Decisão.**
```powershell
$form.Opacity = 0
$form.ShowInTaskbar = $false
```
`Opacity = 0` mantém o form vivo dentro do loop de mensagens.

A flag `$script:Saindo` distingue:
- fechar a janela → `FormClosing` cancela (`$e.Cancel = $true`) e esconde
- menu **Sair** → `$script:Saindo = $true` → o `FormClosing` deixa passar

**Estado.** Implementado. Balloon tip na primeira minimização.

---

## D-12 · Instância única com Mutex

**Decisão.**
```powershell
New-Object System.Threading.Mutex($true, 'Local\CentralDeCamerasApp', [ref]$criadoNovo)
```

Prefixo `Local\` (não `Global\`) → escopo da sessão do Windows, **sem**
exigir privilégios de administrador.

**Estado.** Implementado. Segunda instância mostra "O Central de Cameras ja
esta aberto." e sai.

---

## D-13 · AppUserModelID

**Contexto.** A janela aparecia na barra de tarefas com o **ícone do
PowerShell**.

**Decisão.**
```csharp
[DllImport("shell32.dll", CharSet = CharSet.Unicode)]
public static extern int SetCurrentProcessExplicitAppUserModelID(string appID);
```
Chamado com `'Pedro.CentralDeCameras'` **antes** de criar a janela.

**Estado.** Implementado. A janela passa a ter ícone e agrupamento próprios.

---

## D-14 · Ícone `.ico` DIB-only

**Contexto.** O atalho da área de trabalho mostrava o **ícone genérico de
página**. Causa: o `.ico` tinha entradas nos tamanhos 24/64/128 e uma entrada
PNG em 128 — o shell **rejeita o arquivo inteiro**.

**Decisão.** `.ico` com **apenas** entradas **DIB** nos tamanhos
**16, 32, 48, 256**. Nenhuma entrada PNG.

**Estado.** Implementado e validado com `SHGetFileInfo` — pixel de referência
**`-13726784`** (idêntico ao do `icone.ico`).

⚠️ Se você regenerar o ícone, **mantenha esses 4 tamanhos**. Adicionar 24/64/128
quebra o atalho de novo.

---

## D-15 · Atalho apontando para `wscript.exe`

**Contexto.** Atalho para `powershell.exe` abre uma **janela de console
preta** junto com o app.

**Decisão.**
```powershell
$lnk.TargetPath = "$env:SystemRoot\System32\wscript.exe"
$lnk.Arguments = "`"$vbs`""
```
O VBS executa o PowerShell com `-WindowStyle Hidden`.

**Estado.** Implementado. O `Instalador.ps1` **gera** o `Iniciar.vbs` na pasta
de destino — com o caminho absoluto correto daquela pasta.

---

## D-16 · Estrutura do instalador

**Decisão.** Dois arquivos:

| Arquivo | Papel |
|---|---|
| `Instalar.bat` | Eleva privilégios (`Start-Process -Verb RunAs`), escolhe PS 64-bit, repassa `%*` |
| `Instalador.ps1` | Todo o trabalho: diagnóstico → cópia → VBS → atalhos → firewall → teste → relatório |

**Por que não um `.exe`:** sem toolchain de build no ambiente, e o `.bat` já
resolve elevação de forma confiável.

**Firewall.** Regra `CentralCameras-Relay`, Inbound, Allow,
`Profile Private,Domain` — **nunca** pública. Falha de criação vira **AVISO**,
não FALHA (instalar sem admin ainda deve funcionar).

**Estado.** Implementado e validado (exit 0 na instalação real, no teste em
pasta temporária e no `-Desinstalar`).

---

## D-17 · `cameras.json` como fonte única

**Decisão.** Tudo que é configuração de câmera vive em **um** arquivo JSON:
nome, URL, transporte, relay, `recTcp`. O botão **Renomear** grava de volta
nesse arquivo (`ConvertTo-Json -Depth 6`).

**Estado.** Implementado. ⚠️ **A senha fica em texto puro** — documento isso
no README (feito) e nunca comite o arquivo em repositório público.

---

## D-18 · Remoção da feature de vigilância

**Contexto.** Implementamos gravação por **detecção de movimento com
pré-buffer**. O usuário reportou: *"Essa função de vigilância não funcionou
como esperado. Remova ela."*

**Decisão.** Remover **completamente**: botões `Vigilância`/`Eventos`,
funções, parâmetros, pastas `Buffer/` e `Eventos/`.

**Estado.** ✅ Concluído. **Zero referências** a "vigilância", "buffer",
"evento", "motion" no código. Não reintroduza sem pedido explícito — já foi
avaliado e rejeitado uma vez.

---

## D-19 · Paleta escura e

**Contexto.** Pedido de redesign: *"queria deixar o programa mais bonito"*.
Aplicado o skill `ui-ux-pro-max`.

**Decisão.** Tema escuro (câmeras em ambiente com pouca luz), contraste
mínimo 4,5:1, alvos de toque ≥ 44 px, texto ≥ 12 px, ritmo de espaçamento em
4/8 px, tooltips em todos os botões.

O vermelho de erro foi **clareado** de `#E5484D` para `#F06464` para atingir
~5,2:1 sobre `#0A0E13`.

O status usa **ponto + texto** (nunca só cor) — acessibilidade.

**Estado.** Implementado.

**Lacunas conhecidas:** sem tema claro; navegação por teclado/leitor de tela
não testada.

---

## D-20 · `Iniciar.vbs` com caminho relativo

**Contexto.** O `Iniciar.vbs` original tinha caminho absoluto fixo
(`D:\Documentos\deepseek-harness\default-workspace\...`). Ao mover o projeto,
o atalho quebrava **silenciosamente**.

**Decisão.** Resolver a partir da própria pasta do VBS:
```vbs
Set fso = CreateObject("Scripting.FileSystemObject")
base = fso.GetParentFolderName(WScript.ScriptFullName)
ps1  = base & "\CameraViewer.ps1"
```

**Estado.** Implementado na migração.

---

## D-21 · Migração para `D:\Documentos\projects`

**Contexto.** Pedido para mover o projeto para `D:\Documentos\projects` e
documentá-lo para que outra IA pudesse continuar.

**Decisão.**
- Nova pasta: **`D:\Documentos\projects\central-de-cameras`**
  (nome em kebab-case, consistente com o resto de `projects/`; evita colisão
  com a pasta existente `cameraSeguranca`, que é um projeto Python diferente)
- O `Iniciar.vbs` passou a ser **relativo** (D-20)
- Documentação em 4 arquivos:
  | Arquivo | Público-alvo |
  |---|---|
  | `README.md` | pessoa (instalar, usar, configurar) |
  | `ARQUITETURA.md` | desenvolvedor/IA (referência técnica) |
  | `DECISOES.md` | IA (o que já foi tentado, o que não repetir) |
  | `PARA-IA.md` | IA (handoff operacional) |
  | `LEIA-ME.txt` | usuário final (texto simples) |

**Estado.** ✅ Concluído e verificado.

---

## D-22 · Redesign completo com design system em `Ui.cs`

**Contexto.** O visual anterior usava variáveis de cor soltas, botões só de
texto, tooltips/menus/diálogos nativos do Windows (claros, destoando do tema) e
um `MessageBox` para o aviso de instância única.

**Decisão.** Criar `Ui.cs` (tokens + ícones vetoriais + componentes) e reescrever
a UI do `CameraViewer.ps1` sobre ele: barra superior com resumo vivo, grade de
`Cartao`, barra de status como única área de feedback, botões `BotaoUi` com
ícones vetoriais (sem emoji), menus/tooltips/diálogos/avisos no tema escuro,
validação em linha no Renomear, estados de erro tratados (VLC ausente,
`cameras.json` inválido, instância já aberta). Também: mudo por câmera, ampliar,
mover, tela cheia (F11) e teclas 1–9.

**Por que C# e não só PowerShell.** Controles desenhados (`OnPaint`) exigem uma
subclasse; em PowerShell puro seriam scriptblocks lentos e frágeis. O projeto já
compila `RtspRelay.cs` em runtime, então não há dependência nova.

**Consequências.**
- `Ui.cs` precisa estar ao lado do `CameraViewer.ps1` (o `Instalador.ps1` o copia).
- Arquivos `.ps1`/`.cs` com acentos precisam de **UTF-8 com BOM** (PS 5.1).
- O vídeo é um controle ActiveX: não aceita cantos arredondados nem sobreposição
  (o rodapé do tile fica abaixo do vídeo, não sobre ele).
- Tema claro continua não implementado (os tokens facilitam: trocar em `Tema`).

**Estado.** Implementado e validado por capturas de tela (grade, ampliada, mudo,
mover, janela mínima, tela cheia, Renomear com erro, aviso, menu, foco).

---

## D-23 · Virar a câmera (PTZ) com D-pad; "Mover" virou "Reordenar"

**Contexto.** O botão "Mover" só trocava a posição do vídeo na grade, mas o
pedido era virar a câmera de verdade. O nome causou o mal-entendido.

**Decisão.**
- `Ptz.cs`: cliente ONVIF (`ContinuousMove` + `Stop`, WS-Security digest) com
  fila em thread própria (a UI nunca trava) e `Timeout PT3S` no movimento: se o
  `Stop` se perder, a câmera para sozinha.
- `DPad` (em `Ui.cs`): cruz com quatro setores e centro "parar". **Segurar =
  mover, soltar = parar.** Setas do teclado também funcionam. Abre num painel
  flutuante pelo botão **Virar** do rodapé (o vídeo é ActiveX: nada se sobrepõe a ele).
- O botão antigo foi renomeado para **Reordenar** (ícone de troca).

**Descobertas (testadas com a câmera real).**
- A **iCSee** (`192.168.0.5`) fala ONVIF na porta **8899** (a 80 devolve 404) e
  tem PTZ. O pan é **invertido** (`x` positivo vira para a *esquerda*), o tilt é
  normal. Daí `ptzInverterX: true` no `cameras.json`.
- O motor é sensível: 0,6 s a 0,6 de velocidade gira muito. Velocidades do painel:
  lenta 0,15 / normal 0,3 / rápida 0,6.
- `GotoHomePosition` retorna ok mas não faz nada; não há presets. Não há como
  "voltar à posição inicial" por software.
- CORRIGIDO: as **Yoosee** (`.7` e `.2`) **expõem ONVIF na porta 5000** (ver D-24).
  5000, que recusa HTTP (protocolo proprietário). O botão Virar fica apagado
  nelas e explica o motivo ao clicar. Suportá-las exigiria engenharia reversa
  do protocolo da porta 5000: **não foi feito**.

**Estado.** Implementado e validado: o evento do D-pad fez a iCSee virar para a
esquerda e o seguinte devolveu o movimento.

---

## D-24 · D-pad também nas Yoosee (ONVIF na porta 5000)

**Correção do D-23.** A conclusão de que as Yoosee "não têm ONVIF" estava
errada: o teste usou `GET`, que o servidor delas recusa. A porta **5000** é o
serviço ONVIF (SOAP) e aceita `POST`. Confirmado em campo: ambas as Yoosee
completam a descoberta PTZ e movem.

**Particularidades das Yoosee (testadas):**
- **Sem autenticação:** aceitam qualquer usuário/senha; o cliente envia o
  token assim mesmo.
- **Servidor HTTP frágil:** derruba conexões ao acaso. `Ptz.cs` usa
  `KeepAlive = false`, `Expect100Continue = false` e **reenvia até 4 vezes**.
- **Andam em passos curtos** (um `ContinuousMove` gira pouco). Por isso a opção
  `ptzRepetir: true`: o D-pad reenvia o comando a cada 350 ms enquanto a direção
  está pressionada. Segurar ~1,5 s vira bastante: **use toques curtos** para ajuste fino.
- **Sentido padrão** (esquerda é esquerda, cima é cima): não precisam de `ptzInverter*`.
- Sem presets e sem posição inicial por software (igual à iCSee).

**Config no `cameras.json`:** `"ptz": "192.168.0.7:5000"` (e `.2`) +
`"ptzRepetir": true` nas duas Yoosee; `"ptzInverterX": true` só na iCSee.

**Estado.** Implementado. Validado pelo vídeo: Cozinha e Sala se moveram nos
dois sentidos. O tilt (cima/baixo) das Yoosee mexeu pouco nos testes: pode ser
limite mecânico do modelo; não foi esgotado.
