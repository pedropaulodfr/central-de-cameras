# Guardião 24h (TV Box) — acesso de fora de casa

Para ver as câmeras **fora de casa sem o PC ligado**, algum dispositivo na sua
rede precisa ficar ligado e manter um túnel aberto. Este documento descreve
como usar um **TV Box Android** nesse papel.

> **Por que é necessário:** as câmeras ficam atrás do seu roteador, que não tem
> porta aberta na internet (testado — o provedor usa CGNAT). Sem um dispositivo
> dentro da rede fazendo a ponte, o celular não tem como alcançá-las de fora.
> Isso não é limitação do app: é como a rede funciona.

---

## O que já está pronto no projeto

O gateway foi preparado para rodar em Linux/Android:

| Peça | Situação |
|---|---|
| `server.js` | Multiplataforma (roda em Node no Windows, Linux e Android) |
| `relay.js` | Relay RTSP em JavaScript — **novo**, substitui o `.exe` fora do Windows |
| `RtspRelayCli.exe` | Usado automaticamente **apenas no Windows** (caminho já testado) |
| `cloudflared` | Existe versão Linux/ARM |
| `ffmpeg` | Disponível no Termux (pacote) |

O `server.js` escolhe sozinho: no Windows usa o `.exe`; em Linux/Android usa o
`relay.js`. Você não precisa configurar nada.

---

## Opção A — TV Box com Android + Termux (recomendado)

TV Box Android comum (2 GB de RAM ou mais) serve.

### 1. Instale o Termux

Baixe o **F-Droid** e, por ele, o **Termux** (a versão da Play Store é
descontinuada e quebra pacotes).

### 2. Dentro do Termux

```bash
pkg update && pkg upgrade -y
pkg install -y nodejs ffmpeg wget

# permite que o Termux continue rodando com a tela desligada
termux-wake-lock
```

### 3. Copie o gateway para o TV Box

Copie a pasta `ANDROID/gateway` (sem o `.exe` e sem o `cloudflared.exe`) para o
armazenamento do TV Box, por exemplo em `~/gateway`.

### 4. Inicie o gateway

```bash
cd ~/gateway
node server.js --port 8080 --token SEU_TOKEN_AQUI
```

Deve aparecer a lista das câmeras e a porta. Deixe rodando.

> Para iniciar junto com o Termux, adicione o comando acima ao
> `~/.bashrc` (ou use o widget do Termux).

### 5. Abra o túnel

```bash
pkg install -y wget
wget https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-arm64
chmod +x cloudflared-linux-arm64
./cloudflared-linux-arm64 tunnel --url http://127.0.0.1:8080
```

Copie o endereço `https://....trycloudflare.com` e use no app (modo **Remoto**).

> Em TV Box com CPU ARM de 32 bits, troque `arm64` por `arm` no link.

### 6. Impedir que o Android mate o Termux

- Desative a otimização de bateria para o Termux
- Mantenha o `termux-wake-lock` ativo
- Se o aparelho reiniciar, o Termux precisa ser aberto de novo (a menos que
  você use um inicializador automático)

---

## Opção B — TV Box com Linux (Armbian)

Se o TV Box aceita Armbian, o processo é o mesmo do Linux normal:

```bash
sudo apt install nodejs ffmpeg
node server.js --port 8080 --token SEU_TOKEN
./cloudflared tunnel --url http://127.0.0.1:8080
```

Aqui é fácil deixar como serviço de verdade:

```ini
# /etc/systemd/system/central-gateway.service
[Unit]
Description=Central de Cameras - Gateway
After=network-online.target

[Service]
ExecStart=/usr/bin/node /home/pi/gateway/server.js --port 8080 --token SEU_TOKEN
Restart=always
RestartSec=5
WorkingDirectory=/home/pi/gateway

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl enable --now central-gateway
```

---

## Qual modo usar no app

| Você está… | Modo | Precisa de quê |
|---|---|---|
| Em casa, na Wi-Fi | **Direto** | Nada. Nem PC, nem TV Box |
| Fora de casa | **Remoto** | PC ligado **ou** TV Box guardião |

O modo é trocado em **Configurações** (engrenagem), sem reinstalar.

---

## Checklist do TV Box

- [ ] TV Box ligado na mesma rede das câmeras
- [ ] IP fixo ou reserva DHCP para o TV Box (evita trocar de endereço)
- [ ] Termux com `termux-wake-lock` ativo
- [ ] Bateria/otimização desativada para o Termux
- [ ] Token do gateway anotado
- [ ] Túnel aberto e endereço copiado
- [ ] App no modo **Remoto** com esse endereço e token

---

## Limitações

- Um TV Box modesto converte vídeo mais devagar que um PC; se travar, use
  `"modo": "copy"` no `cameras.json` do gateway (sem transcodificar)
- O endereço do túnel rápido muda a cada reinício (veja
  [README.md](README.md#endereço-fixo-opcional) para fixar)
- Sem TV Box e sem PC ligado, **fora de casa não funciona** — só o modo
  direto, em casa
