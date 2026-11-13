package br.com.pedro.centralcameras.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Câmera cadastrada DENTRO do app, para o modo direto.
 *
 * No modo direto o celular fala RTSP com a câmera, sem passar pelo PC.
 * Só funciona quando o celular está na mesma rede da câmera.
 */
data class CameraLocal(
    val id: String,
    val nome: String,
    val host: String,
    val porta: Int = 554,
    val caminho: String = "/onvif1",
    val usuario: String = "",
    val senha: String = "",
    /**
     * Firmware que recusa Digest correto e só aceita um cabeçalho malformado
     * (caso das Yoosee). Quando verdadeiro, o app sobe um relay local antes
     * de abrir o vídeo.
     */
    val authQuebrado: Boolean = false,
    /** Força RTP sobre TCP. Em geral true; algumas Yoosee travam em TCP. */
    val tcp: Boolean = true,
) {
    /** URL RTSP com credenciais, usada quando não há relay. */
    fun urlDireta(): String {
        val cred = if (usuario.isNotEmpty() || senha.isNotEmpty()) {
            "${usuario}:${senha}@"
        } else ""
        val cam = caminho.let { if (it.startsWith("/")) it else "/$it" }
        return "rtsp://$cred$host:$porta$cam"
    }

    fun valida(): Boolean = host.isNotBlank() && porta > 0
}

/** Como o app vai buscar o vídeo. */
enum class ModoVisualizacao {
    /** RTSP direto na rede local. Não precisa de PC nem de internet. */
    Direto,

    /** HLS pelo gateway no PC. Precisa do gateway e de rede/internet. */
    Remoto,
}

/**
 * Guarda as câmeras e o modo escolhido no próprio aparelho.
 *
 * A senha fica no SharedPreferences privado do app (MODE_PRIVATE), protegido
 * pelo sandbox do Android. Não há sincronização com a nuvem.
 */
class CameraStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("central_cameras", Context.MODE_PRIVATE)

    fun carregarModo(): ModoVisualizacao {
        val v = prefs.getString(CHAVE_MODO, null) ?: return ModoVisualizacao.Direto
        return runCatching { ModoVisualizacao.valueOf(v) }.getOrDefault(ModoVisualizacao.Direto)
    }

    fun salvarModo(modo: ModoVisualizacao) {
        prefs.edit().putString(CHAVE_MODO, modo.name).apply()
    }

    fun carregar(): List<CameraLocal> {
        val bruto = prefs.getString(CHAVE_CAMERAS, null) ?: return padrao()
        return try {
            val arr = JSONArray(bruto)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                CameraLocal(
                    id = o.optString("id"),
                    nome = o.optString("nome", "Câmera"),
                    host = o.optString("host"),
                    porta = o.optInt("porta", 554),
                    caminho = o.optString("caminho", "/onvif1"),
                    usuario = o.optString("usuario"),
                    senha = o.optString("senha"),
                    authQuebrado = o.optBoolean("authQuebrado", false),
                    tcp = o.optBoolean("tcp", true),
                )
            }
        } catch (e: Exception) {
            padrao()
        }
    }

    fun salvar(cameras: List<CameraLocal>) {
        val arr = JSONArray()
        for (c in cameras) {
            arr.put(
                JSONObject().apply {
                    put("id", c.id)
                    put("nome", c.nome)
                    put("host", c.host)
                    put("porta", c.porta)
                    put("caminho", c.caminho)
                    put("usuario", c.usuario)
                    put("senha", c.senha)
                    put("authQuebrado", c.authQuebrado)
                    put("tcp", c.tcp)
                }
            )
        }
        prefs.edit().putString(CHAVE_CAMERAS, arr.toString()).apply()
    }

    /**
     * Câmeras do usuário, já com os endereços conhecidos.
     * A senha da iCSee não vem preenchida: é digitada na tela de configuração.
     */
    fun padrao(): List<CameraLocal> = listOf(
        CameraLocal(
            id = "icsee",
            nome = "iCSee",
            host = "192.168.0.11",
            porta = 554,
            caminho = "/onvif1",
            usuario = "",
            senha = "",
            authQuebrado = false,
            tcp = true,
        ),
        CameraLocal(
            id = "yoosee-cozinha",
            nome = "Yoosee Cozinha",
            host = "192.168.0.7",
            porta = 554,
            caminho = "/onvif1",
            authQuebrado = true,
            tcp = false,
        ),
        CameraLocal(
            id = "yoosee-sala",
            nome = "Yoosee Sala",
            host = "192.168.0.2",
            porta = 554,
            caminho = "/onvif1",
            authQuebrado = true,
            tcp = true,
        ),
    )

    private companion object {
        const val CHAVE_MODO = "modo_visualizacao"
        const val CHAVE_CAMERAS = "cameras_locais"
    }
}
