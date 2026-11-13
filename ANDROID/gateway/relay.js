'use strict';

/**
 * Relay RTSP em Node.js.
 *
 * Mesma lógica do RtspRelay.cs (app de PC) e do RtspRelayLocal.kt (app
 * Android), agora em JavaScript para o gateway rodar em qualquer sistema:
 * Windows, Linux, ou um TV Box Android com Termux.
 *
 * Contorna três defeitos do firmware das câmeras Yoosee:
 *  1. recusa Digest correto (400) e só aceita `Authorization: Digest username="admin"`
 *  2. responde `Transport: RTP/AVP` mesmo tendo aceitado TCP intercalado
 *  3. mantém a sessão pendurada se não receber TEARDOWN
 */

const net = require('net');

class RtspRelay {
  constructor(hostRemoto, portaRemota) {
    this.hostRemoto = hostRemoto;
    this.portaRemota = portaRemota;
    this.servidor = null;
    this.rodando = false;
    this.porta = 0;
    this.sessionId = null;
    this.ultimaUrl = null;
    this.querTcp = false;
    this.conexoes = new Set();
  }

  /** Sobe o relay em 127.0.0.1 e resolve com a porta escolhida. */
  iniciar() {
    return new Promise((resolve, reject) => {
      this.servidor = net.createServer((cliente) => this.atender(cliente));
      this.servidor.on('error', reject);
      this.servidor.listen(0, '127.0.0.1', () => {
        this.porta = this.servidor.address().port;
        this.rodando = true;
        resolve(this.porta);
      });
    });
  }

  parar() {
    this.rodando = false;
    if (this.servidor) {
      try { this.servidor.close(); } catch (e) { }
      this.servidor = null;
    }
    for (const c of this.conexoes) {
      try { c.destroy(); } catch (e) { }
    }
    this.conexoes.clear();
    this.sessionId = null;
    this.ultimaUrl = null;
    this.querTcp = false;
  }

  atender(cliente) {
    const camera = net.connect(this.portaRemota, this.hostRemoto);
    this.conexoes.add(cliente);
    this.conexoes.add(camera);

    // Cada conexão começa do zero.
    this.sessionId = null;
    this.ultimaUrl = null;
    this.querTcp = false;

    let pronto = false;
    const fechar = () => {
      try { cliente.destroy(); } catch (e) { }
      try { camera.destroy(); } catch (e) { }
      this.conexoes.delete(cliente);
      this.conexoes.delete(camera);
    };

    camera.on('error', fechar);
    cliente.on('error', fechar);
    camera.on('close', fechar);
    cliente.on('close', fechar);

    camera.on('connect', () => {
      pronto = true;
      // A resposta da câmera passa pelo reescritor antes de chegar ao cliente.
      // A requisição do cliente já está sendo tratada pelo pipeline abaixo —
      // não usar cliente.pipe(camera) aqui, ou os bytes iriam duplicados.
      this.pipeline(camera, cliente, false);
    });

    // Requisições do cliente passam pelo reescritor.
    this.pipeline(cliente, camera, true);
  }

  /**
   * Lê de `entrada` e escreve em `saida`, interpretando headers RTSP até
   * encontrar o primeiro byte '$' — daí em diante o stream é mídia pura e
   * vai direto.
   */
  pipeline(entrada, saida, paraServidor) {
    let acc = Buffer.alloc(0);
    let modoRaw = false;
    let corpo = 0;

    entrada.on('data', (pedaco) => {
      if (modoRaw) {
        if (!saida.destroyed) saida.write(pedaco);
        return;
      }

      acc = Buffer.concat([acc, pedaco]);

      while (true) {
        if (corpo > 0) {
          const take = Math.min(corpo, acc.length);
          if (take <= 0) break;
          if (!saida.destroyed) saida.write(acc.subarray(0, take));
          acc = acc.subarray(take);
          corpo -= take;
          if (corpo > 0) break;
        }

        if (acc.length > 0 && acc[0] === 0x24) { // '$'
          modoRaw = true;
          if (!saida.destroyed) saida.write(acc);
          acc = Buffer.alloc(0);
          break;
        }

        const fim = this.fimDoHeader(acc);
        if (fim <= 0) break;

        let header = acc.subarray(0, fim).toString('latin1');
        acc = acc.subarray(fim);

        header = paraServidor
          ? this.reescreverRequisicao(header)
          : this.reescreverResposta(header);

        corpo = this.tamanhoDoConteudo(header);
        if (!saida.destroyed) saida.write(Buffer.from(header, 'latin1'));
      }
    });

    entrada.on('end', () => {
      if (paraServidor) this.enviarTeardown(saida);
      try { saida.end(); } catch (e) { }
    });
  }

  reescreverRequisicao(header) {
    let h = header.replace(
      `rtsp://127.0.0.1:${this.porta}`,
      `rtsp://${this.hostRemoto}:${this.portaRemota}`
    );

    if (/RTP\/AVP\/TCP/i.test(h)) this.querTcp = true;

    const linha = h.match(/^(DESCRIBE|SETUP|PLAY|OPTIONS)\s+(rtsp:\/\/\S+)\s+RTSP\//im);
    if (linha) {
      const metodo = linha[1].toUpperCase();
      if (metodo === 'DESCRIBE' || !this.ultimaUrl) this.ultimaUrl = linha[2];
    }

    const idx = h.indexOf('\r\n\r\n');
    if (idx < 0) return h;

    let cabeca = h.slice(0, idx);
    const cauda = h.slice(idx);

    cabeca = cabeca.replace(/^Authorization:.*\r\n/gim, '');
    cabeca = cabeca.replace(/^Proxy-Authorization:.*\r\n/gim, '');

    return `${cabeca}\r\nAuthorization: Digest username="admin"${cauda}`;
  }

  reescreverResposta(header) {
    let h = header;

    const sess = h.match(/^Session:\s*([^;\r\n]+)/im);
    if (sess) this.sessionId = sess[1].trim();

    // Firmware anuncia H265 mas manda parâmetros de H.264 (mesmo tamanho,
    // então o Content-Length do SDP continua válido).
    if (/H265\/90000/i.test(h) && !/sprop-vps/i.test(h)) {
      h = h.replace(/H265\/90000/gi, 'H264/90000');
    }

    if (this.querTcp && /Transport:/i.test(h)) {
      h = h.replace(/^Transport:\s*RTP\/AVP;/gim, 'Transport: RTP/AVP/TCP;');
    }

    return h.replace(
      `rtsp://${this.hostRemoto}:${this.portaRemota}`,
      `rtsp://127.0.0.1:${this.porta}`
    );
  }

  enviarTeardown(saida) {
    try {
      if (!this.sessionId || !this.ultimaUrl) return;
      if (saida.destroyed) return;
      const texto =
        `TEARDOWN ${this.ultimaUrl} RTSP/1.0\r\n` +
        `CSeq: 9999\r\n` +
        `Session: ${this.sessionId}\r\n` +
        `Authorization: Digest username="admin"\r\n\r\n`;
      saida.write(Buffer.from(texto, 'latin1'));
    } catch (e) { }
  }

  tamanhoDoConteudo(header) {
    const m = header.match(/^Content-Length:\s*(\d+)/im);
    return m ? parseInt(m[1], 10) : 0;
  }

  fimDoHeader(buf) {
    for (let i = 3; i < buf.length; i++) {
      if (buf[i - 3] === 13 && buf[i - 2] === 10 && buf[i - 1] === 13 && buf[i] === 10) {
        return i + 1;
      }
    }
    return -1;
  }
}

module.exports = { RtspRelay };
