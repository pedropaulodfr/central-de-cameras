# Arquitetura do App Android — Decisões

> Documento de trabalho. Define como o app Android acessa as câmeras de
> qualquer lugar, dado que as câmeras só existem na LAN e têm RTSP quebrado.

---

## O problema

O app desktop (`CameraViewer.ps1`) roda **na mesma rede** das câmeras e pode
usar o `RtspRelay.cs` local. O celular precisa acessar **de qualquer lugar**,
inclusive fora da rede Wi-Fi.

Restrições reais:

1. **Câmeras**: RTSP na porta 554, LAN `192.168.0.0/24`, sem porta pública
2. **Firmware Yoosee**: só aceita `Authorization: Digest username="admin"`
   malformado (bypass). Digest correto → `400 Bad Request`
3. **Firmware Yoosee**: responde `Transport: RTP/AVP` mesmo aceitando TCP
4. **1 sessão RTSP** por câmera Yoosee
5. **Roteador**: IP WAN `187.19.170.8` com portas externas **fechadas**
   (80/443/22 testados → CGNAT ou firewall). Não dá para abrir porta
6. **Celular**: Galaxy J4 Core, Android **8.1 (API 27)**, **armeabi-v7a** (32-bit)

---

## Decisão 1 — O celular nunca fala RTSP direto

O app **não** implementa RTSP. Ele fala **HTTP** com um servidor que roda no
PC (o "gateway").

```
┌──────────────────────────┐        ┌───────────────────────────────┐
│  CELULAR (Android)       │        │  PC (sempre ligado, na LAN)  │
│                          │        │                               │
│  App: HTTP + HLS         │◀──────▶│  Servidor "Central Remota"   │
│  ExoPlayer (Media3)      │  HTTPS │   • API JSON (lista câmeras)  │
│                          │        │   • HLS (transmux p/ HLS)     │
│  NÃO usa RTSP            │        │   • Relay RTSP (o mesmo .cs)  │
└──────────────────────────┘        └───────────────┬───────────────┘
                                                   │ RTSP 554
                                          ┌────────▼────────┐
                                          │  Câmeras LAN    │
                                          └─────────────────┘
```

**Por quê:**

| Alternativa | Veredito |
|---|---|
| App fala RTSP direto | ❌ Impossível fora da LAN. Dentro da LAN ainda assim quebraria por causa do auth do firmware |
| App fala RTSP direto **via VPN** | ⚠️ Funciona, mas exige VPN configurada no celular + expõe o bypass de auth a qualquer um na VPN. E o relay TCP ainda é necessário |
| **App fala HLS/HTTP com gateway no PC** | ✅ Escolhido |

**Consequência:** o PC precisa estar **ligado** para o celular ver as câmeras.
Isso é aceitável — o PC é onde as câmeras estão.

---

## Decisão 2 — HLS (não RTSP, não WebRTC)

O gateway converte RTSP → HLS (HLS = HTTP Live Streaming: segmentos `.ts` +
playlist `.m3u8`).

**Por que HLS:**

- **ExoPlayer/Media3 é nativo em HLS** — decoder H.264/H.265 optimized, sem
  biblioteca nativa extra
- **Sobre HTTP** — funciona atrás de qualquer proxy/CDN/túnel
- **Reconexão automática** — o player só precisa reler o `.m3u8`
- **latência ~2-4 s** — aceitável para vigilância
- **Escalável** — o gateway serve N câmeras para N celulares

**Por que NÃO:**

| Alternativa | Veredito |
|---|---|
| RTSP direto no app | ❌ Auth quebrado + sem biblioteca Android decente (libVLC é pesado, ~40 MB) |
| WebRTC | ⚠️ Latência mínima, mas precisa de servidor STUN/TURN + signaling + HTTPS obrigatorio  — complexidade alta e **não funciona em API 27 sem libraries modernos** |
| **MJPEG via HTTP** | ⚠️ Funciona, mas 8x mais bandwidth que H.264. 3 câmeras a 1080p = inviável em 4G |

**Implementação do HLS:** `ffmpeg` (já é dependência do projeto desktop) faz
`ffmpeg -i rtsp://... -c copy -f hls -hls_time 2 -hls_list_size 6 ...`

O `-c copy` remuxa sem recodificar → CPU baixo, qualidade original.

---

## Decisão 3 — Como o celular alcança o PC fora da rede

O WAN `187.19.170.8` **não** expõe portas (CGNAT). Opções:

| Opção | Como funciona | Veredito |
|---|---|---|
| **Port forwarding no roteador** | Precisa de IP público real | ❌ CGNAT — não funciona |
| **Cloudflare Tunnel** | `cloudflared` faz o tráfego sair pelo servidor da Cloudflare. HTTPS público, **sem abrir porta**, **sem CGNAT** | ✅ **Escolhido** |
| **ngrok** | Mesmo princípio, free tier com limite | ⚠️ Alternativa; URL muda a cada sessão |
| **Tailscale / WireGuard** | VPN mesh, IP privado | ✅ Boa opção, mas exige instalar app Tailscale no celular |
| **SSH reverse tunnel** | `ssh -R` para um VPS | ⚠️ Precisa de um VPS |

**Escolhido: Cloudflare Tunnel** (`cloudflared`).

Motivos:
- Zero configuração de rede no roteador
- HTTPS válido (certificado Let's Encrypt automático) → o Android aceita sem
  exceção de certificado
- **Não exige root nem VPN no celular** — só o app
- Grátis para uso pessoal
- Suporta acesso por nome (ex.: `cameras.seudominio.com`)

**Fallback documentado:** se o usuário não quiser Cloudflare, pode usar
Tailscale (WireGuard) — o app funciona igual, só muda a URL de conexão.

---

## Decisão 4 — Stack do app

### Android 8.1 / API 27 / armeabi-v7a restringe bastante

| Biblioteca | minSdk | Usar? |
|---|---|---|
| **Media3 / ExoPlayer 1.11.1** | 21 | ✅ Funciona em API 27 |
| **Compose Material3 1.4.0** | 21 | ✅ Funciona em API 27 |
| **Kotlin 2.4.21** | — | ✅ (Kotlin não impõe minSdk) |
| **Coil** (imagens) | 21 | ✅ (cacheado? precisa verificar) |
| **libVLC Android** | 21 | ❌ ~40 MB, e não resolve o auth |
| **Retrofit** | 21 | ⚠️ OkHttp já está cacheado; usar só OkHttp |

### Compose vs Views

**Compose**, apesar do dispositivo ser antigo:
- Compose Material3 1.4.0 está **cacheado**
- Compose Compose 1.4+ suporta API 21+
- Kotlin 2.4.21 + compose compiler 2.4.21 (emparelhados no cache)
- Código muito mais curto e legível para manutenção por IA

⚠️ **Risco:** `minSdk` do Compose 1.4.x = 21, mas há um problema conhecido
de `desugaring` em API < 24 com `java.time` etc. Vou:
- `minSdk = 24` (cobre 99% dos aparelhos e evita desugaring pesado)
  — mas o Galaxy J4 é API 27, então OK
- Habilitar `coreLibraryDesugaring` se necessário

### Estrutura do app

```
app/
├── ui/
│   ├── theme/          ← paleta, tipografia, shapes (UI/UX Pro Max)
│   ├── CameraGrid.kt   ← grade de tiles
│   ├── CameraTile.kt   ← tile individual + estado
│   ├── CameraDetail.kt ← tela cheia
│   └── ConnectionScreen.kt ← configurar servidor
├── data/
│   ├── Api.kt          ← OkHttp, endpoints
│   ├── Models.kt       ← Camera, ServerConfig
│   └── Settings.kt     ← SharedPreferences
├── player/
│   └── HlsPlayer.kt    ← Media3 ExoPlayer wrapper
└── MainActivity.kt
```

---

## Decisão 5 — Segurança

O gateway expõe as câmeras na internet. Medidas:

1. **Token de acesso** — header `Authorization: Bearer <token>`; o gateway
   valida antes de servir qualquer stream
2. **HTTPS obrigatório** via Cloudflare Tunnel (TLS termina na borda)
3. **Nonce/refresh** — o token pode ser trocado pelo app
4. **Sem expor as credenciais** — o celular nunca vê a senha da câmera;
   ele fala só com o gateway
5. **Rate limit** no gateway

⚠️ **O bypass de auth do firmware Yoosee continua sendo um risco** — mas
agora o gateway **é** a barreira: sem o token, ninguém puxa stream.

---

## Decisão 6 — O gateway no PC

**Decidido: Node.js + ffmpeg + o relay em C# reaproveitado.**

| Alternativa | Veredito |
|---|---|
| PowerShell + `HttpListener` | ⚠️ Sem instalação nova, mas manipular streaming, concorrência e processos filhos em PowerShell fica frágil |
| `node-media-server` (biblioteca pronta) | ❌ Faz RTMP, não RTSP; não resolvia o problema do relay |
| **Node.js + ffmpeg orquestrado à mão** | ✅ Escolhido |

O Node é leve, lida bem com `child_process` (um `ffmpeg` por câmera) e deixa
o código de orquestração legível. Custo: uma dependência nova no PC
(`winget install OpenJS.NodeJS.LTS`).

**O relay não foi reimplementado.** `ANDROID/gateway/RtspRelayCli.cs` é só um
`Main` em volta da classe `RtspRelay` que já existia. Compilado com o
`csc.exe` do .NET Framework (já vem no Windows):

```powershell
csc.exe /target:exe /out:RtspRelayCli.exe RtspRelayCli.cs ..\..\RtspRelay.cs
```

O executável imprime a **porta local efêmera** no stdout e fica rodando; o
Node lê essa porta e monta a URL para o ffmpeg. Assim o contorno do firmware
Yoosee é **idêntico** nos dois programas — um único lugar para corrigir.

### Conversão de vídeo: por que transcodificar

As câmeras entregam **HEVC 1080p**. Testado:

| Modo | CPU | Rede | Compatibilidade |
|---|---|---|---|
| `copy` | ~0% | 2-4 Mbps/câmera | Só aparelhos com HEVC |
| `x264` | ~1 núcleo/câmera | 1,2 Mbps | Universal |
| **`amf`** (GPU AMD) | ~0% | 1,2 Mbps | Universal |

`amf` usa o codificador H.264 da GPU (`h264_amf`) — praticamente de graça em
CPU e entrega 720p H.264, que qualquer celular decodifica. É o padrão.
`copy` e `x264` ficam disponíveis por câmera no `cameras.json`.

**Áudio foi removido** (`-an`): as câmeras usam PCMA/G.711, que o HLS muxa
como stream privado e o ExoPlayer não decodifica. Sem áudio o stream fica
mais leve e estável.

---

## Decisão 7 — Formato final do fluxo

```
Celular                                PC (gateway)
  │                                      │
  │ GET /api/cameras  (Bearer token)     │
  │─────────────────────────────────────▶│  valida token
  │◀─────────────────────────────────────│  JSON: [{id, nome, url, status}]
  │                                      │
  │ GET /hls/{id}/index.m3u8             │
  │─────────────────────────────────────▶│  valida token
  │◀─────────────────────────────────────│  playlist (janela de 6 segmentos)
  │                                      │
  │ GET /hls/{id}/segN.ts                │
  │─────────────────────────────────────▶│  valida token
  │◀─────────────────────────────────────│  segmento MPEG-TS (~2 s)
  │                                      │
  │                    (em background)   │
  │   ffmpeg -i rtsp://127.0.0.1:<relay> -an -c:v h264_amf ... -f hls
  │                                      │
  │                    relay RTSP → câmera (o mesmo RtspRelay.cs do PC)
```

Cada câmera tem sua **própria** playlist — não há master playlist. O app
abre a playlist da câmera direto no ExoPlayer, o que simplifica o erro por
câmera (uma pode falhar sem afetar as outras).

O token vai no cabeçalho `Authorization` de **todas** as requisições,
inclusive a playlist e cada segmento — por isso o app configura
`DefaultHttpDataSource.Factory().setDefaultRequestProperties(...)`, que se
aplica a toda a cadeia do HLS.

---

## O que foi implementado

- [x] `RtspRelayCli.exe` compilado e testado com as três câmeras
- [x] Gateway Node.js: API, HLS, token, vigia de 20 s
- [x] Transcodificação H.264 720p na GPU (AMF), com fallback `copy`/`x264`
- [x] `cloudflared` baixado e túnel público validado por HTTPS
- [x] App Compose: grade, tela cheia, conexão, tema escuro
- [x] Player Media3 ExoPlayer com token em playlist e segmentos
- [x] Configuração persistida em SharedPreferences
- [x] Ícone adaptativo (API 26+)
- [x] APK compilado (21,8 MB) e instalado no Galaxy J4 Core
- [x] Scripts `Iniciar-Gateway.bat`, `Iniciar-Tunel.bat`, `Compilar-APK.bat`

### Pendente

- [ ] Endereço fixo de túnel (hoje o quick tunnel muda a cada execução)
- [ ] Teste real por 4G (o aparelho de teste não tinha internet)
- [ ] Áudio
- [ ] Gravação pelo app
