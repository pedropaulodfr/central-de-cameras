package br.com.pedro.centralcameras.player

/**
 * O que a grade e a tela cheia precisam saber de uma câmera,
 * independente de ela vir do modo direto (RTSP) ou remoto (HLS).
 */
data class CameraItem(
    val id: String,
    val nome: String,
    val fonte: Fonte,
)
