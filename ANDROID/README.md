# Central de Câmeras — App Android

Aplicativo que mostra as mesmas câmeras do programa de PC. Tem **dois modos**:

| Modo | Funciona | Precisa de quê |
|---|---|---|
| **Direto** (padrão) | Em casa, na mesma Wi-Fi das câmeras | **Nada** — nem PC, nem internet |
| **Remoto** | Em qualquer lugar (4G, outra rede) | Gateway rodando no PC (ou num guardião 24h) |

Troque de modo em **Configurações** (engrenagem), sem reinstalar.

---

## Índice

- [Como funciona](#como-funciona)
- [Modo direto — sem PC](#modo-direto--sem-pc)
- [Modo remoto — de qualquer lugar](#modo-remoto--de-qualquer-lugar)
- [Requisitos](#requisitos)
- [Instalar o app no celular](#instalar-o-app-no-celular)
- [Usar o app](#usar-o-app)
- [Solução de problemas](#solução-de-problemas)
- [Compilar do zero](#compilar-do-zero)
- [Estrutura de arquivos](#estrutura-de-arquivos)
- [Segurança](#segurança)
- [Limitações conhecidas](#limitações-conhecidas)

---

## Como funciona

### Modo direto (sem PC)

O celular fala **RTSP** direto com as câmeras, na rede local.

```
┌────────────────┐   RTSP 554    ┌──────────────┐
│  CELULAR       │──────────────▶│  Câmera      │
│  • relay local │               └──────────────┘
│  • libVLC      │
└────────────────┘
```

As câmeras Yoosee têm firmware quebrado (recusam Digest correto), então o app
sobe um **relay local em Kotlin** (porta em `127.0.0.1`) que injeta o
cabeçalho aceito e corrige o `Transport`. O player aponta para o relay.

O player do modo direto é o **libVLC** — o mesmo motor do app de PC. O RTSP do
Media3 foi descartado porque tem um `NullPointerException` **fatal** quando o
SDP do firmware é incompleto (as Yoosee anunciam H265 sem `sprop-vps`), o que
derrubava o app inteiro.

### Modo remoto (de qualquer lugar)

O celular fala **HTTP/HLS** com um gateway que roda no PC.

```
┌────────────────┐  HTTPS   ┌──────────────────────┐  RTSP  ┌──────────┐
│  CELULAR       │◀────────▶│  PC (gateway Node)   │───────▶│ Câmeras  │
│  • HLS/Media3  │          │  • API com token     │        └──────────┘
└────────────────┘          │  • RTSP → HLS        │
                            │  • relay das Yoosee  │
                            └──────────────────────┘
```

O vídeo é convertido para **H.264 720p** na GPU (AMD AMF), ~1,2 Mbps por
câmera — leve para 4G e compatível com qualquer celular.

### Por que dois modos

| Alternativa | Problema |
|---|---|
| Só RTSP direto | Fora de casa é fisicamente impossível (as câmeras não têm porta aberta na internet) |
| Só gateway | Obriga o PC a ficar ligado mesmo quando você está em casa |
| **Os dois** | Em casa você não depende de nada; fora de casa usa o gateway |

---

## Modo direto — sem PC

**Não precisa de servidor, nem de internet.** Basta o celular estar na mesma
rede Wi-Fi das câmeras.

1. Abra o app (ele já inicia no modo direto)
2. Toque na engrenagem
3. Confira o **IP de cada câmera** (mudam quando o roteador troca)
4. Para a iCSee, informe **usuário e senha**
5. Para as Yoosee, deixe **"Autenticação quebrada (Yoosee)"** ligado

**Custo:** o APK fica maior (~60 MB por arquitetura) porque embute o libVLC.
Vale a pena: é o que faz funcionar sem depender de nada.

---

## Modo remoto — de qualquer lugar

## Requisitos

### No PC (gateway)

- **Windows** com o programa de PC já instalado (as câmeras configuradas)
- **Node.js 18+** — `winget install OpenJS.NodeJS.LTS`
- **ffmpeg** no PATH — `winget install Gyan.FFmpeg`
- O PC precisa ficar **ligado** enquanto você usa o app

### No celular

- **Android 8.0** ou superior
- Acesso à internet (Wi-Fi ou dados móveis)

---

## Preparar o PC (gateway)

1. Abra a pasta `ANDROID\gateway`
2. Dê dois cliques em **`Iniciar-Gateway.bat`**
3. O programa mostra:

```
   Token de acesso:

       d835703814b39ca064dc084e6b1a8fc9b718fd70db8c5d22

   Iniciando gateway na porta 8080...
   [rede] http://192.168.0.3:8080
```

Guarde o **token** e o **endereço de rede**. O token fica salvo em `token.txt`
e não muda nas próximas vezes.

> Se o Windows perguntar sobre o Firewall, **permita** o acesso em redes
> privadas. Sem isso o celular não alcança o PC.

---

## Instalar o app no celular

### Opção A — pelo cabo USB (mais rápido)

1. Ligue o celular no PC com o cabo e ative a **Depuração USB**
2. Rode:

```powershell
adb install -r D:\Documentos\projects\central-de-cameras\ANDROID\dist\CentralCameras.apk
```

### Opção B — pelo próprio celular

1. Copie `dist\CentralCameras.apk` para o celular (cabo, Google Drive, etc.)
2. Abra o arquivo no celular e permita "instalar de fontes desconhecidas"

### Primeira configuração no app

1. Abra **Central de Câmeras**
2. **Endereço do servidor:** o que o gateway mostrou (`http://192.168.0.3:8080`)
3. **Token de acesso:** o token mostrado pelo gateway
4. Toque em **Conectar**

As três câmeras aparecem na grade, ao vivo.

---

## Acesso de fora da rede

Para ver as câmeras no 4G, num Wi-Fi de outro lugar ou viajando, é preciso
expor o gateway na internet. O roteador não abre porta (e muitas operadoras
usam CGNAT, o que impede isso), então usamos um **túnel Cloudflare**.

### Como abrir

1. Deixe o `Iniciar-Gateway.bat` rodando
2. Em outra janela, dê dois cliques em **`Iniciar-Tunel.bat`**
3. Aguarde e copie o endereço que aparece:

```
https://alguma-coisa-aleatoria.trycloudflare.com
```

4. No app, toque no ícone de **engrenagem** e troque o endereço por essa URL
   (o token continua o mesmo)

Pronto: agora funciona de qualquer lugar.

### Importante sobre o túnel

- **O endereço muda** cada vez que o túnel é aberto (túnel "rápido", sem conta)
- **Não é preciso abrir porta** no roteador nem ter IP fixo
- O tráfego sai por HTTPS com certificado válido
- Para um **endereço fixo**, crie um túnel nomeado com um domínio próprio
  (gratuito no Cloudflare) — veja abaixo

### Endereço fixo (opcional)

Se você tem um domínio no Cloudflare:

```powershell
cloudflared tunnel login
cloudflared tunnel create cameras
cloudflared tunnel route dns cameras cameras.seudominio.com
cloudflared tunnel run --url http://127.0.0.1:8080 cameras
```

Depois use `https://cameras.seudominio.com` no app, e ele nunca muda.

---

## Usar o app

| Ação | Como |
|---|---|
| Ver todas as câmeras | Tela inicial — grade com todas |
| Tela cheia | Toque na câmera |
| Mostrar/esconder controles | Toque na tela na tela cheia |
| Voltar | Botão Voltar do Android ou seta no topo |
| Atualizar lista | Ícone de recarregar no topo |
| Trocar servidor/token | Ícone de engrenagem |
| Girar para paisagem | Vídeo ocupa a tela inteira |

A grade atualiza o estado sozinha a cada 10 segundos. Cada câmera mostra um
ponto colorido **e** um texto ("ao vivo", "conectando", "sem sinal") — a cor
não é o único indicador.

---

## Solução de problemas

### "Não foi possível conectar" no app

1. O `Iniciar-Gateway.bat` está rodando no PC?
2. O endereço está correto? (inclua `http://` ou `https://`)
3. Celular e PC estão na **mesma rede**? (para o acesso local)
4. Firewall do Windows liberou o Node.js em rede privada?

Teste no navegador do celular: abra `http://IP_DO_PC:8080/api/ping`.
Deve responder `{"ok":true,...}`.

### "Token recusado pelo servidor"

O token do app não é o mesmo do `token.txt`. Copie de novo, com atenção.

### Vídeo fica "conectando" e não abre

No PC, verifique a janela do gateway. Se aparecer erro de ffmpeg:

- A câmera está ligada e na rede?
- `ffmpeg` está no PATH? (`ffmpeg -version` no PowerShell)
- Alguma câmera Yoosee aceita só **uma** sessão RTSP: se o app de PC estiver
  aberto com a mesma câmera, feche-o

### Túnel abre mas o app não conecta

- O endereço copiado está completo, com `https://`?
- O gateway estava rodando **antes** de abrir o túnel?
- Túneis rápidos expiram: reabra o `Iniciar-Tunel.bat` e atualize o endereço

### A imagem trava ou fica lenta

O gateway converte para 720p. Em conexões muito ruins, reduza no
`cameras.json` do gateway:

```json
{ "modo": "amf", ... }   // padrão: 720p H.264 na GPU
```

Também é possível usar `"modo": "copy"` (sem converter, mais leve no PC mas
pesado na rede) ou `"modo": "x264"` (converte na CPU, para PCs sem GPU AMD).

---

## Compilar do zero

```powershell
cd D:\Documentos\projects\central-de-cameras\ANDROID
$env:JAVA_HOME = "D:\Arquivos de Programas\Java\jdk-17"
gradle assembleDebug
```

Ou dê dois cliques em **`Compilar-APK.bat`**, que gera
`dist\CentralCameras.apk`.

### Detalhes do projeto

| Item | Valor |
|---|---|
| Linguagem | Kotlin + Jetpack Compose |
| AGP | 9.5.1 (Kotlin embutido) |
| compileSdk / targetSdk | 37 / 36 |
| minSdk | 26 (Android 8.0) |
| Player | Media3 ExoPlayer 1.11.1 (HLS) |
| HTTP | OkHttp 5.5.1 |
| Pacote | `br.com.pedro.centralcameras` |

> **Atenção ao mexer:** o AGP 9 traz Kotlin embutido. **Não** adicione o
> plugin `org.jetbrains.kotlin.android` — ele quebra o build. O plugin
> `org.jetbrains.kotlin.plugin.compose` continua sendo necessário.

---

## Estrutura de arquivos

```
ANDROID/
├── gateway/                     ← servidor que roda no PC
│   ├── server.js                ← API + HLS + relay + vigia
│   ├── cameras.json             ← quais câmeras servir
│   ├── RtspRelayCli.cs          ← CLI do relay (usa o RtspRelay.cs do projeto)
│   ├── RtspRelayCli.exe         ← relay compilado
│   ├── cloudflared.exe          ← túnel HTTPS
│   ├── index.html               ← painel de teste no navegador
│   ├── Iniciar-Gateway.bat
│   ├── Iniciar-Tunel.bat
│   └── token.txt                ← gerado na primeira execução
│
├── app/src/main/                ← código do app
│   ├── java/br/com/pedro/centralcameras/
│   │   ├── MainActivity.kt      ← navegação entre telas
│   │   ├── data/                ← API, modelos, preferências
│   │   ├── player/              ← ExoPlayer com token
│   │   └── ui/                  ← telas e tema
│   └── res/                     ← ícone, cores, manifesto
│
├── dist/CentralCameras.apk      ← APK pronto para instalar
├── Compilar-APK.bat
└── ARQUITETURA-ANDROID.md       ← decisões técnicas detalhadas
```

---

## Segurança

- Toda requisição exige o **token** (`Authorization: Bearer ...`)
- Sem token, o gateway recusa: não há stream público
- O túnel Cloudflare entrega HTTPS com certificado válido
- As credenciais das câmeras **nunca** chegam ao celular — só o gateway as vê

⚠️ **Pontos de atenção:**

- O token fica salvo no `token.txt` em texto puro. Não compartilhe a pasta.
- O bypass de autenticação das câmeras Yoosee continua sendo uma falha do
  firmware. O gateway é a barreira: **quem tiver o token, vê as câmeras.**
- Se o token vazar, apague `token.txt` e reinicie o gateway para gerar outro.

---

## Limitações conhecidas

- O **PC precisa estar ligado** — é ele que fala com as câmeras
- Latência de **2 a 4 segundos** (inerente ao HLS)
- O túnel rápido muda de endereço a cada execução
- Sem áudio (as câmeras usam G.711, que o HLS não carrega bem)
- Sem gravação pelo app — a gravação continua sendo feita no PC
- Vídeo em 720p (conversão para caber em 4G)
