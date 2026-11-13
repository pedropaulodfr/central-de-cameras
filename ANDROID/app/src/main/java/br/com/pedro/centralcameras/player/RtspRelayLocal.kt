package br.com.pedro.centralcameras.player

import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections

/**
 * Relay RTSP que roda DENTRO do celular.
 *
 * É a versão Kotlin do `RtspRelay.cs` do app de PC, com o mesmo objetivo:
 * contornar três defeitos do firmware das câmeras Yoosee.
 *
 *  1. O firmware recusa o Digest correto (responde 400) e só aceita um
 *     cabeçalho malformado `Authorization: Digest username="admin"`.
 *  2. Responde `Transport: RTP/AVP` mesmo tendo aceitado TCP intercalado,
 *     o que faz o player abortar com "Nonmatching transport in server reply".
 *  3. Mantém a sessão pendurada depois que o player desconecta, deixando a
 *     câmera ocupada por cerca de 60 segundos.
 *
 * O relay escuta em 127.0.0.1 numa porta livre e conversa com a câmera de
 * verdade. O player aponta para o relay e nunca vê as credenciais.
 */
class RtspRelayLocal(
    private val hostRemoto: String,
    private val portaRemota: Int,
) {

    private var servidor: ServerSocket? = null
    private var threadAceite: Thread? = null

    @Volatile private var rodando = false
    @Volatile private var sessionId: String? = null
    @Volatile private var ultimaUrl: String? = null
    @Volatile private var querTcp = false

    /** Porta local efetivamente aberta. */
    @Volatile
    var porta: Int = 0
        private set

    private val conexoes = Collections.synchronizedList(mutableListOf<Socket>())

    /** Sobe o relay e devolve a porta local. Lança exceção se não conseguir. */
    fun iniciar(): Int {
        if (rodando) return porta

        val s = ServerSocket()
        s.reuseAddress = true
        s.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
        servidor = s
        porta = s.localPort
        rodando = true

        threadAceite = Thread({ lacoAceite(s) }, "rtsp-relay-aceite").apply {
            isDaemon = true
            start()
        }
        return porta
    }

    fun parar() {
        rodando = false
        try { servidor?.close() } catch (_: Exception) { }
        servidor = null
        synchronized(conexoes) {
            for (c in conexoes) { try { c.close() } catch (_: Exception) { } }
            conexoes.clear()
        }
        sessionId = null
        ultimaUrl = null
        querTcp = false
    }

    // -----------------------------------------------------------------------

    private fun lacoAceite(s: ServerSocket) {
        while (rodando) {
            try {
                val cliente = s.accept()
                conexoes.add(cliente)
                Thread({ atender(cliente) }, "rtsp-relay-conn").apply {
                    isDaemon = true
                    start()
                }
            } catch (_: Exception) {
                if (!rodando) return
            }
        }
    }

    private fun atender(cliente: Socket) {
        val camera = Socket()
        try {
            camera.connect(InetSocketAddress(hostRemoto, portaRemota), 6000)
        } catch (_: Exception) {
            try { cliente.close() } catch (_: Exception) { }
            return
        }

        conexoes.add(camera)

        // Cada conexão começa do zero: aceitar de novo limpa o estado.
        sessionId = null
        ultimaUrl = null
        querTcp = false

        try { cliente.tcpNoDelay = true } catch (_: Exception) { }
        try { camera.tcpNoDelay = true } catch (_: Exception) { }

        val subida = Thread({
            bombear(cliente.getInputStream(), camera.getOutputStream(), true)
        }, "rtsp-relay-subida").apply { isDaemon = true; start() }

        val descida = Thread({
            bombear(camera.getInputStream(), cliente.getOutputStream(), false)
        }, "rtsp-relay-descida").apply { isDaemon = true; start() }

        try {
            subida.join()
            descida.join()
        } catch (_: InterruptedException) { }

        try { cliente.close() } catch (_: Exception) { }
        try { camera.close() } catch (_: Exception) { }
    }

    private fun bombear(entrada: InputStream, saida: OutputStream, paraServidor: Boolean) {
        val bruto = ByteArray(65536)
        val acc = Acumulador()
        var modoRaw = false
        var corpo = 0

        try {
            while (true) {
                val n = entrada.read(bruto)
                if (n <= 0) break
                acc.adicionar(bruto, n)

                if (modoRaw) {
                    if (acc.tamanho > 0) {
                        saida.write(acc.consumir(acc.tamanho))
                        saida.flush()
                    }
                    continue
                }

                while (true) {
                    if (corpo > 0) {
                        val take = minOf(corpo, acc.tamanho)
                        if (take <= 0) break
                        saida.write(acc.consumir(take))
                        saida.flush()
                        corpo -= take
                        if (corpo > 0) break
                    }

                    // Stream intercalado de mídia: a partir daqui é só repassar.
                    if (acc.tamanho > 0 && acc.byte(0) == '$'.code) {
                        modoRaw = true
                        break
                    }

                    val fim = acc.fimDoHeader()
                    if (fim <= 0) break

                    val headerBytes = acc.consumir(fim)
                    var header = String(headerBytes, Charsets.ISO_8859_1)
                    header = if (paraServidor) reescreverRequisicao(header)
                    else reescreverResposta(header)

                    corpo = tamanhoDoConteudo(header)
                    saida.write(header.toByteArray(Charsets.ISO_8859_1))
                    saida.flush()
                }
            }
        } catch (_: Exception) { }

        // Avisa a câmera que a sessão acabou; sem isso ela fica ocupada.
        if (paraServidor) enviarTeardown(saida)

        try { entrada.close() } catch (_: Exception) { }
        try { saida.close() } catch (_: Exception) { }
    }

    private fun reescreverRequisicao(header: String): String {
        var h = header.replace("rtsp://127.0.0.1:$porta", "rtsp://$hostRemoto:$portaRemota")

        if (h.contains("RTP/AVP/TCP", ignoreCase = true)) querTcp = true

        val linha = Regex("(?im)^(DESCRIBE|SETUP|PLAY|OPTIONS)\\s+(rtsp://\\S+)\\s+RTSP/").find(h)
        if (linha != null) {
            val metodo = linha.groupValues[1].uppercase()
            if (metodo == "DESCRIBE" || ultimaUrl.isNullOrEmpty()) {
                ultimaUrl = linha.groupValues[2]
            }
        }

        val idx = h.indexOf("\r\n\r\n")
        if (idx < 0) return h

        var cabeca = h.substring(0, idx)
        val cauda = h.substring(idx)

        // Remove a autenticação do player e injeta a que o firmware aceita.
        cabeca = cabeca.replace(Regex("(?im)^Authorization:.*\r\n"), "")
        cabeca = cabeca.replace(Regex("(?im)^Proxy-Authorization:.*\r\n"), "")

        return "$cabeca\r\nAuthorization: Digest username=\"admin\"$cauda"
    }

    private fun reescreverResposta(header: String): String {
        var h = header

        Regex("(?im)^Session:\\s*([^;\\r\\n]+)").find(h)?.let {
            sessionId = it.groupValues[1].trim()
        }

        // Alguns firmwares anunciam H265 no SDP mas enviam os parâmetros no
        // formato H.264 (sprop-parameter-sets, sem sprop-vps/sps/pps). O
        // Media3 exige sprop-vps para H.265 e aborta com
        // "missing sprop-vps parameter". Como a troca H265 -> H264 tem o
        // MESMO tamanho em bytes, o Content-Length do SDP continua correto.
        if (h.contains("H265/90000", ignoreCase = true) &&
            !h.contains("sprop-vps", ignoreCase = true)
        ) {
            h = h.replace("H265/90000", "H264/90000")
        }

        // O firmware diz RTP/AVP mesmo quando aceitou TCP intercalado.
        if (querTcp && h.contains("Transport:", ignoreCase = true)) {
            h = h.replace(
                Regex("(?im)^Transport:\\s*RTP/AVP;"),
                "Transport: RTP/AVP/TCP;"
            )
        }

        return h.replace(
            "rtsp://$hostRemoto:$portaRemota",
            "rtsp://127.0.0.1:$porta"
        )
    }

    private fun enviarTeardown(saida: OutputStream) {
        try {
            val sessao = sessionId
            val url = ultimaUrl
            if (sessao.isNullOrEmpty() || url.isNullOrEmpty()) return
            val texto = buildString {
                append("TEARDOWN ").append(url).append(" RTSP/1.0\r\n")
                append("CSeq: 9999\r\n")
                append("Session: ").append(sessao).append("\r\n")
                append("Authorization: Digest username=\"admin\"\r\n\r\n")
            }
            saida.write(texto.toByteArray(Charsets.ISO_8859_1))
            saida.flush()
        } catch (_: Exception) { }
    }

    private fun tamanhoDoConteudo(header: String): Int {
        val m = Regex("(?im)^Content-Length:\\s*(\\d+)").find(header) ?: return 0
        return m.groupValues[1].toIntOrNull() ?: 0
    }

    /**
     * Buffer de bytes com consumo pela frente.
     * Evita o custo de remover do início de um ArrayList a cada header.
     */
    private class Acumulador(capacidadeInicial: Int = 16384) {
        private var buf = ByteArray(capacidadeInicial)
        private var inicio = 0
        private var fim = 0

        val tamanho: Int get() = fim - inicio

        fun byte(i: Int): Int = buf[inicio + i].toInt() and 0xFF

        fun adicionar(origem: ByteArray, n: Int) {
            garantir(n)
            System.arraycopy(origem, 0, buf, fim, n)
            fim += n
        }

        fun consumir(n: Int): ByteArray {
            val r = ByteArray(n)
            System.arraycopy(buf, inicio, r, 0, n)
            inicio += n
            if (inicio == fim) { inicio = 0; fim = 0 }
            return r
        }

        /** Posição logo após o CRLFCRLF, ou -1 se o header ainda não chegou. */
        fun fimDoHeader(): Int {
            var i = 3
            while (i < tamanho) {
                if (byte(i - 3) == 13 && byte(i - 2) == 10 && byte(i - 1) == 13 && byte(i) == 10) {
                    return i + 1
                }
                i++
            }
            return -1
        }

        private fun garantir(n: Int) {
            if (fim + n <= buf.size) return
            // Compacta o que já foi consumido.
            val usado = tamanho
            if (usado + n <= buf.size) {
                System.arraycopy(buf, inicio, buf, 0, usado)
                inicio = 0
                fim = usado
                return
            }
            // Cresce (dobrando) até caber.
            var nova = buf.size
            while (nova < usado + n) nova *= 2
            val novo = ByteArray(nova)
            System.arraycopy(buf, inicio, novo, 0, usado)
            buf = novo
            inicio = 0
            fim = usado
        }
    }
}
