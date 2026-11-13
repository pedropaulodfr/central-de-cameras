package br.com.pedro.centralcameras.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

/** Estado de reprodução, traduzido para o que a interface mostra. */
enum class EstadoVideo { Carregando, Reproduzindo, Erro }

/** De onde o vídeo vem. */
sealed interface Fonte {

    /** HLS servido pelo gateway (modo remoto). Precisa do PC. */
    data class Hls(val url: String, val cabecalhos: Map<String, String>) : Fonte

    /** RTSP direto na câmera (modo direto, aparelho na mesma rede). */
    data class RtspDireto(val url: String, val tcp: Boolean) : Fonte

    /**
     * RTSP através do relay local, para firmware que recusa autenticação
     * padrão. O relay é criado e destruído junto com o player.
     */
    data class RtspViaRelay(val host: String, val porta: Int, val tcp: Boolean) : Fonte
}

/**
 * Tile de vídeo.
 *
 * HLS (modo remoto) usa o Media3, que é eficiente e leve.
 * RTSP (modo direto) usa o libVLC, porque o RTSP do Media3 tem um
 * NullPointerException fatal com o SDP incompleto das Yoosee.
 */
@Composable
fun CameraVideo(
    fonte: Fonte,
    modifier: Modifier = Modifier,
    aoMudarEstado: (EstadoVideo) -> Unit = {},
    silencioso: Boolean = true,
) {
    when (fonte) {
        is Fonte.Hls -> HlsVideo(
            url = fonte.url,
            cabecalhos = fonte.cabecalhos,
            modifier = modifier,
            silencioso = silencioso,
            aoMudarEstado = aoMudarEstado,
        )

        is Fonte.RtspDireto -> VlcVideo(
            url = fonte.url,
            tcp = fonte.tcp,
            modifier = modifier,
            silencioso = silencioso,
            aoMudarEstado = aoMudarEstado,
        )

        is Fonte.RtspViaRelay -> {
            // O relay vive enquanto este tile existir.
            val relay = remember(fonte) {
                runCatching { RtspRelayLocal(fonte.host, fonte.porta).also { it.iniciar() } }
                    .getOrNull()
            }

            DisposableEffect(relay) {
                onDispose { relay?.parar() }
            }

            val porta = relay?.porta ?: 0
            if (porta > 0) {
                VlcVideo(
                    url = "rtsp://127.0.0.1:$porta/onvif1",
                    tcp = fonte.tcp,
                    modifier = modifier,
                    silencioso = silencioso,
                    aoMudarEstado = aoMudarEstado,
                )
            } else {
                SemSinal(modifier, aoMudarEstado)
            }
        }
    }
}

@Composable
private fun SemSinal(modifier: Modifier, aoMudarEstado: (EstadoVideo) -> Unit) {
    LaunchedEffect(Unit) { aoMudarEstado(EstadoVideo.Erro) }
    Box(modifier = modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        Text("Sem sinal", color = Color(0xFFF06464), fontSize = 13.sp)
    }
}

/**
 * Player HLS via Media3. Cada instância cria e libera o próprio ExoPlayer,
 * mantendo a memória controlada.
 */
@Composable
private fun HlsVideo(
    url: String,
    cabecalhos: Map<String, String>,
    modifier: Modifier,
    silencioso: Boolean,
    aoMudarEstado: (EstadoVideo) -> Unit,
) {
    val contexto = LocalContext.current
    var estado by remember(url) { mutableStateOf(EstadoVideo.Carregando) }

    val fabricaDados = remember(url, cabecalhos) {
        DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(cabecalhos)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(12_000)
            .setReadTimeoutMs(18_000)
            .setUserAgent(cabecalhos["User-Agent"] ?: "CentralCameras-Android/1.0")
    }

    val player = remember(url) {
        ExoPlayer.Builder(contexto)
            .setMediaSourceFactory(DefaultMediaSourceFactory(fabricaDados))
            .build()
            .apply {
                repeatMode = Player.REPEAT_MODE_OFF
                playWhenReady = true
                volume = if (silencioso) 0f else 1f
            }
    }

    DisposableEffect(player) {
        onDispose { player.release() }
    }

    LaunchedEffect(url) {
        estado = EstadoVideo.Carregando
        aoMudarEstado(estado)
        try {
            val fonte = HlsMediaSource.Factory(fabricaDados)
                .setAllowChunklessPreparation(true)
                .createMediaSource(MediaItem.fromUri(url))
            player.setMediaSource(fonte)
            player.prepare()
        } catch (e: Exception) {
            estado = EstadoVideo.Erro
            aoMudarEstado(estado)
        }
    }

    DisposableEffect(player) {
        val ouvinte = object : Player.Listener {
            override fun onPlaybackStateChanged(novo: Int) {
                when (novo) {
                    Player.STATE_READY -> {
                        estado = EstadoVideo.Reproduzindo
                        aoMudarEstado(estado)
                    }
                    Player.STATE_BUFFERING -> {
                        estado = EstadoVideo.Carregando
                        aoMudarEstado(estado)
                    }
                    Player.STATE_ENDED -> {
                        player.seekToDefaultPosition()
                        player.prepare()
                    }
                }
            }

            override fun onPlayerError(erro: PlaybackException) {
                estado = EstadoVideo.Erro
                aoMudarEstado(estado)
            }
        }
        player.addListener(ouvinte)
        onDispose { player.removeListener(ouvinte) }
    }

    Box(modifier = modifier.background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    this.player = player
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        if (estado == EstadoVideo.Carregando) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = Color(0xFF388BFD),
                strokeWidth = 2.dp,
            )
        }

        if (estado == EstadoVideo.Erro) {
            Text(
                text = "Sem sinal",
                color = Color(0xFFF06464),
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}
