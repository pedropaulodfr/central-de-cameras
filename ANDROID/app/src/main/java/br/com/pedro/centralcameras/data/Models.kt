package br.com.pedro.centralcameras.data

/** Uma câmera, como o gateway a descreve. */
data class Camera(
    val id: String,
    val nome: String,
    /** Caminho relativo da playlist HLS, ex.: "/hls/icsee/index.m3u8". */
    val url: String,
    val status: String = "desconhecido",
    val erro: String? = null,
) {
    val aoVivo: Boolean get() = status == "ao vivo"
    val comProblema: Boolean get() = status == "erro" || status == "parado"
}

/** Estado da tela, para a UI reagir a cada situação sem ambiguidade. */
sealed interface EstadoConexao {
    data object Ocioso : EstadoConexao
    data object Conectando : EstadoConexao
    data class Conectado(val cameras: List<Camera>) : EstadoConexao
    data class Falha(val mensagem: String) : EstadoConexao
}

/** Configuração que o usuário digita e que fica salva no aparelho. */
data class ConfigServidor(
    val baseUrl: String = "",
    val token: String = "",
) {
    val preenchida: Boolean get() = baseUrl.isNotBlank() && token.isNotBlank()

    /** Normaliza: remove barra final e adiciona esquema se o usuário omitiu. */
    fun normalizada(): ConfigServidor {
        var u = baseUrl.trim()
        if (u.isEmpty()) return this
        if (!u.startsWith("http://", true) && !u.startsWith("https://", true)) {
            u = "http://$u"
        }
        u = u.trimEnd('/')
        return copy(baseUrl = u, token = token.trim())
    }
}
