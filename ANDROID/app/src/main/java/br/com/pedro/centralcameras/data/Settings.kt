package br.com.pedro.centralcameras.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Persistência simples da configuração do servidor.
 * O token fica no SharedPreferences privado do app (MODE_PRIVATE), que no
 * Android é protegido pelo sandbox do próprio aplicativo.
 */
class Settings(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("central_cameras", Context.MODE_PRIVATE)

    fun carregar(): ConfigServidor = ConfigServidor(
        baseUrl = prefs.getString(CHAVE_URL, "") ?: "",
        token = prefs.getString(CHAVE_TOKEN, "") ?: "",
    )

    fun salvar(config: ConfigServidor) {
        prefs.edit()
            .putString(CHAVE_URL, config.baseUrl)
            .putString(CHAVE_TOKEN, config.token)
            .apply()
    }

    fun limpar() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val CHAVE_URL = "base_url"
        const val CHAVE_TOKEN = "token"
    }
}
