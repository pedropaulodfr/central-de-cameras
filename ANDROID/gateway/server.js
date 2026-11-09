/**
 * Central de Cameras - Gateway remoto
 * ====================================
 *
 * Servidor HTTP que roda no PC (dentro da rede das cameras) e serve as
 * cameras para o celular Android, de qualquer lugar do mundo.
 *
 * O celular NUNCA fala RTSP direto. Ele fala HTTP com este servidor:
 *
 *   GET /api/cameras              -> lista das cameras (JSON)
 *   GET /api/status               -> status do gateway e das cameras (JSON)
 *   GET /hls/<id>/index.m3u8      -> playlist HLS de uma camera
 *   GET /hls/<id>/segN.ts         -> segmento MPEG-TS
 *
 * Por tras de cada camera HLS roda um ffmpeg que le da camera via RTSP e
 * remuxa para HLS (-c copy, sem recodificar).
 *
 * As cameras Yoosee tem um firmware quebrado: recusam Digest correto e
 * aceitam apenas um cabecalho de autenticacao malformado. Por isso o
 * gateway sobe um RtspRelayCli.exe (o MESMO codigo do app desktop) para
 * cada camera Yoosee, e o ffmpeg consome o relay local.
 *
 * Uso:
 *   node server.js [--port 8080] [--config cameras.json] [--token SEU_TOKEN]
 */

'use strict';

const http = require('http');
const fs = require('fs');
const path = require('path');
const { spawn } = require('child_process');
const crypto = require('crypto');
const os = require('os');

// ---------------------------------------------------------------------------
// configuracao
// ---------------------------------------------------------------------------

const RAIZ = __dirname;
const PASTA_HLS = path.join(RAIZ, 'hls');

function arg(nome, padrao) {
  const i = process.argv.indexOf('--' + nome);
  if (i >= 0 && process.argv[i + 1]) return process.argv[i + 1];
  return padrao;
}
function flag(nome) {
  return process.argv.includes('--' + nome);
}

const PORTA = parseInt(arg('port', '8080'), 10);
const ARQUIVO_CONFIG = path.resolve(arg('config', path.join(RAIZ, 'cameras.json')));
const TOKEN = arg('token', '') || process.env.CENTRAL_TOKEN || '';
const CAMINHO_RELAY = path.join(RAIZ, 'RtspRelayCli.exe');
const CAMINHO_FFMPEG = arg('ffmpeg', 'ffmpeg');

if (!TOKEN) {
  console.error('');
  console.error('  ERRO: nenhum token de acesso definido.');
  console.error('  Use:  node server.js --token MEU_TOKEN');
  console.error('  ou defina a variavel de ambiente CENTRAL_TOKEN.');
  console.error('');
  process.exit(1);
}

fs.mkdirSync(PASTA_HLS, { recursive: true });

// ---------------------------------------------------------------------------
// cameras
// ---------------------------------------------------------------------------

let cameras = [];

function carregarCameras() {
  try {
    const bruto = JSON.parse(fs.readFileSync(ARQUIVO_CONFIG, 'utf8'));
    cameras = Array.isArray(bruto) ? bruto : [bruto];
    console.log(`[config] ${cameras.length} camera(s) carregada(s) de ${ARQUIVO_CONFIG}`);
  } catch (e) {
    console.error(`[config] ERRO ao ler ${ARQUIVO_CONFIG}: ${e.message}`);
    cameras = [];
  }
}
carregarCameras();

// ---------------------------------------------------------------------------
// relay RTSP (para cameras cujo firmware recusa autenticacao correta)
// ---------------------------------------------------------------------------

/**
 * Sobe um relay local para a camera e devolve a porta local.
 * Reusa o RtspRelayCli.exe do projeto desktop - o codigo do relay e o mesmo
 * nos dois apps.
 */
function subirRelay(host, portaRemota) {
  return new Promise((resolve, reject) => {
    if (!fs.existsSync(CAMINHO_RELAY)) {
      return reject(new Error(`RtspRelayCli.exe nao encontrado em ${CAMINHO_RELAY}`));
    }
    const proc = spawn(CAMINHO_RELAY, [host, String(portaRemota), '0'], {
      windowsHide: true,
    });

    let saida = '';
    let resolvido = false;

    proc.stdout.on('data', (d) => {
      saida += d.toString();
      // A primeira linha e a porta local efetiva.
      const linhas = saida.split(/\r?\n/).filter((l) => l.trim().length > 0);
      if (!resolvido && linhas.length > 0) {
        const porta = parseInt(linhas[0].trim(), 10);
        if (porta > 0) {
          resolvido = true;
          resolve({ proc, porta });
        }
      }
    });

    proc.stderr.on('data', (d) => {
      console.error(`[relay ${host}] ${d.toString().trim()}`);
    });

    proc.on('error', (e) => {
      if (!resolvido) reject(e);
    });

    proc.on('exit', (code) => {
      if (!resolvido) reject(new Error(`relay para ${host} saiu com codigo ${code}`));
    });

    setTimeout(() => {
      if (!resolvido) reject(new Error(`timeout ao subir relay para ${host}`));
    }, 10000);
  });
}

// ---------------------------------------------------------------------------
// processos ffmpeg (um por camera)
// ---------------------------------------------------------------------------

const streams = new Map(); // id -> { proc, url, inicio, status }

async function iniciarStream(cam) {
  if (streams.has(cam.id)) return streams.get(cam.id);

  const pasta = path.join(PASTA_HLS, cam.id);
  fs.mkdirSync(pasta, { recursive: true });

  let url = cam.url;
  let relay = null;

  // Camera com relay: precisa do relay local para o ffmpeg conseguir
  // conectar (o firmware recusa autenticacao RTSP padrao).
  if (cam.relay) {
    const [host, porta] = String(cam.relay).split(':');
    try {
      relay = await subirRelay(host, porta ? parseInt(porta, 10) : 554);
      url = `rtsp://127.0.0.1:${relay.porta}/onvif1`;
      console.log(`[relay ${cam.id}] ${host} -> porta local ${relay.porta}`);
    } catch (e) {
      console.error(`[relay ${cam.id}] FALHA: ${e.message}`);
      return null;
    }
  }

  const transporte = cam.tcp === false ? 'udp' : 'tcp';

  // Modo de video:
  //   'amf'  -> transcodifica para H.264 720p na GPU AMD (universal, ~1,2 Mbps)
  //   'x264' -> transcodifica para H.264 720p na CPU (fallback sem GPU)
  //   'copy' -> remuxa sem recodificar (zero CPU, mas mantem HEVC/1080p)
  const modo = cam.modo || 'amf';

  const entrada = [
    '-hide_banner',
    '-loglevel', 'error',
    '-rtsp_transport', transporte,
    // -timeout e a opcao de espera do RTSP no FFmpeg 6 (rw_timeout e do FFmpeg 7+).
    '-timeout', '15000000',
    '-i', url,
    // Somente video: o audio das cameras e PCMA/G.711, que o HLS nao
    // carrega bem e o ExoPlayer nao decodifica.
    '-an',
  ];

  let video;
  if (modo === 'copy') {
    video = ['-c:v', 'copy'];
  } else if (modo === 'x264') {
    video = [
      '-vf', 'scale=-2:720',
      '-c:v', 'libx264', '-preset', 'ultrafast', '-tune', 'zerolatency',
      '-g', '50', '-b:v', '1200k', '-maxrate', '1400k', '-bufsize', '2400k',
    ];
  } else {
    // AMF: codificador H.264 da GPU AMD. Barato em CPU e universal no celular.
    video = [
      '-vf', 'scale=-2:720',
      '-c:v', 'h264_amf', '-quality', 'speed', '-usage', 'lowlatency',
      '-b:v', '1200k', '-maxrate', '1400k', '-bufsize', '2400k',
    ];
  }

  const args = entrada.concat(video, [
    '-f', 'hls',
    '-hls_time', '2',
    '-hls_list_size', '6',
    '-hls_flags', 'delete_segments+append_list+omit_endlist',
    '-hls_segment_type', 'mpegts',
    '-hls_segment_filename', path.join(pasta, 'seg%d.ts'),
    path.join(pasta, 'index.m3u8'),
  ]);

  const proc = spawn(CAMINHO_FFMPEG, args, { windowsHide: true });
  let stderr = '';

  proc.stderr.on('data', (d) => {
    stderr += d.toString();
    if (stderr.length > 8000) stderr = stderr.slice(-4000);
    const st = streams.get(cam.id);
    if (st) st.saida = stderr.slice(-1000);
  });

  proc.on('exit', (code) => {
    console.error(`[ffmpeg ${cam.id}] saiu com codigo ${code}`);
    const st = streams.get(cam.id);
    if (st) {
      st.status = 'erro';
      st.saida = stderr.slice(-500);
      if (st.relay) { try { st.relay.proc.kill(); } catch (e) { } }
    }
  });

  const st = { proc, relay, url, urlOriginal: cam.url, stderr: '', status: 'iniciando', saida: '', inicio: new Date().toISOString() };
  streams.set(cam.id, st);
  console.log(`[ffmpeg ${cam.id}] iniciado (${transporte}, modo ${modo}) ${url}`);
  return st;
}

function pararStream(id) {
  const st = streams.get(id);
  if (!st) return;
  try { st.proc.kill(); } catch (e) { }
  if (st.relay) { try { st.relay.proc.kill(); } catch (e) { } }
  streams.delete(id);
}

/**
 * Status real de uma camera, deduzido do que existe em disco.
 * O ffmpeg pode estar rodando mas ainda sem ter escrito o primeiro
 * segmento; ou pode ter morrido. Nao confiamos numa flag estatica.
 */
function statusReal(id, st) {
  if (!st) return 'parado';
  const pasta = path.join(PASTA_HLS, id);
  const playlist = path.join(pasta, 'index.m3u8');
  try {
    const info = fs.statSync(playlist);
    const idade = (Date.now() - info.mtimeMs) / 1000;
    // A playlist e reescrita a cada segmento (2 s). Se ficou velha, travou.
    if (idade < 15) return 'ao vivo';
    if (idade < 60) return 'instavel';
    return 'parado';
  } catch (e) {
    return st.proc.exitCode === null ? 'iniciando' : 'erro';
  }
}

// ---------------------------------------------------------------------------
// autenticacao
// ---------------------------------------------------------------------------

function tokenValido(req) {
  const header = req.headers['authorization'] || '';
  let enviado = '';
  if (header.toLowerCase().startsWith('bearer ')) {
    enviado = header.slice(7).trim();
  } else {
    enviado = (req.headers['x-access-token'] || '').trim();
  }
  if (!enviado) return false;

  // Comparacao de tempo constante.
  const a = crypto.createHash('sha256').update(enviado).digest();
  const b = crypto.createHash('sha256').update(TOKEN).digest();
  return crypto.timingSafeEqual(a, b);
}

// ---------------------------------------------------------------------------
// servidor HTTP
// ---------------------------------------------------------------------------

const TIPOS = {
  '.m3u8': 'application/vnd.apple.mpegurl',
  '.ts': 'video/mp2t',
  '.json': 'application/json; charset=utf-8',
  '.html': 'text/html; charset=utf-8',
  '.js': 'application/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
};

function responderJson(res, codigo, obj) {
  const corpo = JSON.stringify(obj, null, 2);
  res.writeHead(codigo, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(corpo),
    'Cache-Control': 'no-store',
  });
  res.end(corpo);
}

function servirArquivo(res, caminho) {
  fs.readFile(caminho, (err, dados) => {
    if (err) {
      res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
      res.end('nao encontrado');
      return;
    }
    const ext = path.extname(caminho).toLowerCase();
    const headers = {
      'Content-Type': TIPOS[ext] || 'application/octet-stream',
      'Content-Length': dados.length,
      'Cache-Control': 'no-cache',
      'Access-Control-Allow-Origin': '*',
    };
    if (ext === '.m3u8') headers['Cache-Control'] = 'no-cache, no-store, must-revalidate';
    res.writeHead(200, headers);
    res.end(dados);
  });
}

const servidor = http.createServer(async (req, res) => {
  const url = new URL(req.url, `http://${req.headers.host || 'localhost'}`);
  const rota = url.pathname;

  // --- rotas publicas -------------------------------------------------------

  if (rota === '/api/ping') {
    return responderJson(res, 200, { ok: true, versao: '1.0', Cameras: cameras.length });
  }

  if (rota === '/api/cameras' || rota === '/api/status') {
    if (!tokenValido(req)) {
      return responderJson(res, 401, { erro: 'token invalido ou ausente' });
    }

    const lista = cameras.map((c) => {
      const st = streams.get(c.id);
      return {
        id: c.id,
        nome: c.nome,
        url: `/hls/${encodeURIComponent(c.id)}/index.m3u8`,
        temHls: !!st,
        status: statusReal(c.id, st),
        inicio: st ? st.inicio : null,
        // Ultima mensagem de erro do ffmpeg, para diagnostico.
        erro: st && st.saida ? st.saida.trim().split('\n').slice(-1)[0] : null,
      };
    });

    return responderJson(res, 200, {
      cameras: lista,
      servidor: {
        hostname: os.hostname(),
        versao: '1.0',
        cameraCount: cameras.length,
        streamsAtivos: streams.size,
      },
    });
  }

  // --- HLS ------------------------------------------------------------------

  if (rota.startsWith('/hls/')) {
    if (!tokenValido(req)) {
      res.writeHead(401, { 'Content-Type': 'text/plain; charset=utf-8' });
      return res.end('token invalido');
    }

    const partes = rota.split('/').filter(Boolean); // ['hls', id, arquivo]
    if (partes.length < 3) {
      res.writeHead(400, { 'Content-Type': 'text/plain; charset=utf-8' });
      return res.end('rota invalida');
    }

    const id = decodeURIComponent(partes[1]);
    const arquivo = partes.slice(2).join('/');

    // Protecao contra path traversal.
    if (arquivo.includes('..') || arquivo.includes('/') || arquivo.includes('\\')) {
      res.writeHead(400, { 'Content-Type': 'text/plain; charset=utf-8' });
      return res.end('nome invalido');
    }

    const cam = cameras.find((c) => c.id === id);
    if (!cam) {
      res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
      return res.end('camera nao encontrada');
    }

    // Garante que o stream exista (sobe se preciso).
    if (!streams.has(id)) {
      const st = await iniciarStream(cam);
      if (!st) {
        res.writeHead(503, { 'Content-Type': 'text/plain; charset=utf-8' });
        return res.end('nao foi possivel iniciar o stream');
      }
    }

    const caminho = path.join(PASTA_HLS, id, arquivo);
    if (!caminho.startsWith(PASTA_HLS)) {
      res.writeHead(400, { 'Content-Type': 'text/plain; charset=utf-8' });
      return res.end('caminho invalido');
    }
    return servirArquivo(res, caminho);
  }

  // --- painel simples -------------------------------------------------------

  if (rota === '/' || rota === '/index.html') {
    return servirArquivo(res, path.join(RAIZ, 'index.html'));
  }

  res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
  res.end('nao encontrado');
});

// ---------------------------------------------------------------------------
// inicializacao
// ---------------------------------------------------------------------------

async function principal() {
  console.log('');
  console.log('  ==============================================');
  console.log('   CENTRAL DE CAMERAS - Gateway remoto');
  console.log('  ==============================================');
  console.log(`  Porta local : ${PORTA}`);
  console.log(`  Cameras     : ${cameras.length}`);
  console.log(`  Token       : ${TOKEN.slice(0, 6)}...${TOKEN.slice(-4)}`);
  console.log(`  HLS em      : ${PASTA_HLS}`);
  console.log('');

  // Pre-sobe os streams para a primeira carga do app ser rapida.
  for (const cam of cameras) {
    try {
      await iniciarStream(cam);
    } catch (e) {
      console.error(`[init ${cam.id}] FALHA: ${e.message}`);
    }
  }

  servidor.listen(PORTA, '0.0.0.0', () => {
    console.log(`  [ok] escutando em http://0.0.0.0:${PORTA}`);
    console.log('');

    const nets = os.networkInterfaces();
    for (const nome of Object.keys(nets)) {
      for (const net of nets[nome]) {
        if (net.family === 'IPv4' && !net.internal) {
          console.log(`  [rede] http://${net.address}:${PORTA}`);
        }
      }
    }
    console.log('');
  });
}

// Encerramento limpo: matar ffmpeg e os relays.
function encerrar() {
  console.log('\n  Encerrando...');
  for (const id of Array.from(streams.keys())) pararStream(id);
  servidor.close(() => process.exit(0));
  setTimeout(() => process.exit(0), 2000);
}
process.on('SIGINT', encerrar);
process.on('SIGTERM', encerrar);
process.on('exit', () => {
  for (const id of Array.from(streams.keys())) {
    const st = streams.get(id);
    try { st.proc.kill(); } catch (e) { }
    if (st.relay) { try { st.relay.proc.kill(); } catch (e) { } }
  }
});

principal();

// ---------------------------------------------------------------------------
// vigia: reinicia ffmpeg morto ou travado
// ---------------------------------------------------------------------------

setInterval(() => {
  for (const cam of cameras) {
    const st = streams.get(cam.id);
    if (!st) continue;

    const morreu = st.proc.exitCode !== null;
    const travou = statusReal(cam.id, st) === 'parado';

    if (morreu || travou) {
      console.error(`[vigia ${cam.id}] stream ${morreu ? 'morreu' : 'travou'} - reiniciando`);
      pararStream(cam.id);
      // Espera a camera liberar a sessao RTSP (as Yoosee aceitam ~1 por vez).
      setTimeout(() => {
        iniciarStream(cam).catch((e) => console.error(`[vigia ${cam.id}] ${e.message}`));
      }, 5000);
    }
  }
}, 20000);
