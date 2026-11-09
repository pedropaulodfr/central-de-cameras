package br.com.pedro.centralcameras.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Cliente HTTP do gateway.
 *
 * O celular nunca fala RTSP: ele pede a lista de câmeras e consome HLS
 * servido pelo PC. Toda requisição leva o token no cabeçalho Authorization.
 */
class Api(private val config: ConfigServidor) {

    private val cliente: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /** Cabeçalhos que o player também precisa mandar (playlist e segmentos). */
    fun cabecalhos(): Map<String, String> = mapOf(
        "Authorization" to "Bearer ${config.token}",
        "User-Agent" to "CentralCameras-Android/1.0",
    )

    /** URL absoluta de uma câmera a partir do caminho relativo do gateway. */
    fun urlAbsoluta(caminho: String): String {
        val base = config.baseUrl.trimEnd('/')
        return if (caminho.startsWith("http")) caminho else "$base$caminho"
    }

    suspend fun listarCameras(): List<Camera> = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("${config.baseUrl}/api/cameras")
            .apply { cabecalhos().forEach { (k, v) -> header(k, v) } }
            .get()
            .build()

        try {
            cliente.newCall(req).execute().use { resp ->
                val corpo = resp.body?.string().orEmpty()

                if (resp.code == 401) throw ErroApi("Token recusado pelo servidor.")
                if (!resp.isSuccessful) throw ErroApi("O servidor respondeu ${resp.code}.")

                val raiz = JSONObject(corpo)
                val arr = raiz.optJSONArray("cameras") ?: throw ErroApi("Resposta sem lista de câmeras.")

                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    Camera(
                        id = o.optString("id"),
                        nome = o.optString("nome", "Câmera"),
                        url = o.optString("url"),
                        status = o.optString("status", "desconhecido"),
                        erro = o.optString("erro").takeIf { it.isNotBlank() && it != "null" },
                    )
                }
            }
        } catch (e: ErroApi) {
            throw e
        } catch (e: IOException) {
            throw ErroApi(traduzirErroDeRede(e))
        } catch (e: Exception) {
            throw ErroApi("Falha ao ler a resposta do servidor: ${e.message}")
        }
    }

    private fun traduzirErroDeRede(e: IOException): String {
        val m = e.message.orEmpty()
        return when {
            m.contains("Failed to connect", true) ||
                m.contains("Connection refused", true) ->
                "Não foi possível conectar. Confira o endereço e se o gateway está rodando no PC."

            m.contains("timeout", true) || m.contains("timed out", true) ->
                "O servidor demorou demais para responder. Verifique a conexão."

            m.contains("Unable to resolve host", true) ->
                "Endereço não encontrado. Confira se digitou corretamente."

            m.contains("SSL", true) || m.contains("certificate", true) ->
                "Problema no certificado HTTPS do servidor."

            else -> "Erro de rede: ${e.message}"
        }
    }
}

class ErroApi(mensagem: String) : Exception(mensagem)
