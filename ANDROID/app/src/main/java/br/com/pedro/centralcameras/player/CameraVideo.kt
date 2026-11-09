package br.com.pedro.centralcameras.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

/** Estado de reprodução, traduzido para o que a interface mostra. */
enum class EstadoVideo { Carregando, Reproduzindo, Erro }

/**
 * Tile de vídeo HLS.
 *
 * Cada instância cria o próprio ExoPlayer e o libera ao sair da composição —
 * isso é o que mantém a memória controlada num aparelho de 2 GB.
 */
@Composable
fun CameraVideo(
    url: String,
    cabecalhos: Map<String, String>,
    modifier: Modifier = Modifier,
    aoMudarEstado: (EstadoVideo) -> Unit = {},
    silencioso: Boolean = true,
) {
    val contexto = LocalContext.current
    var estado by remember(url) { mutableStateOf(EstadoVideo.Carregando) }

    // Fábrica de dados com o token aplicado a TODA requisição:
    // a playlist .m3u8 e cada segmento .ts passam por aqui.
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

    // Libera o player quando o tile sai da tela.
    DisposableEffect(player) {
        onDispose {
            player.release()
        }
    }

    // (Re)carrega quando a URL muda.
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
                        // Stream ao vivo terminou: tenta recomeçar.
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
            androidx.compose.material3.CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center).run { this },
                color = Color(0xFF388BFD),
                strokeWidth = 2.dp,
            )
        }

        if (estado == EstadoVideo.Erro) {
            androidx.compose.material3.Text(
                text = "Sem sinal",
                color = Color(0xFFF06464),
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}
