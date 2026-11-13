package br.com.pedro.centralcameras.player

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

/**
 * Uma instância de LibVLC por processo.
 *
 * Criar uma por câmera alocaria vários conjuntos de decodificadores e
 * estouraria a memória num aparelho de 2 GB.
 */
object VlcProvider {
    @Volatile private var instancia: LibVLC? = null

    fun obter(contexto: Context): LibVLC {
        instancia?.let { return it }
        return synchronized(this) {
            instancia ?: LibVLC(
                contexto.applicationContext,
                arrayListOf(
                    "--no-video-title-show",
                    "--network-caching=800",
                    "--rtsp-frame-buffer-size=500000",
                    "--no-stats",
                    "--no-snapshot-preview",
                )
            ).also { instancia = it }
        }
    }
}

/**
 * Player RTSP via libVLC.
 *
 * É o mesmo motor do app de PC e aceita o firmware problemático das Yoosee
 * (autenticação malformada e SDP com H265 sem sprop-vps) sem derrubar o app.
 */
@Composable
fun VlcVideo(
    url: String,
    tcp: Boolean,
    modifier: Modifier = Modifier,
    silencioso: Boolean = true,
    aoMudarEstado: (EstadoVideo) -> Unit = {},
) {
    val contexto = LocalContext.current
    var estado by remember(url) { mutableStateOf(EstadoVideo.Carregando) }

    val layout = remember(url) { VLCVideoLayout(contexto) }
    val libVlc = remember { VlcProvider.obter(contexto) }

    DisposableEffect(url) {
        val player = MediaPlayer(libVlc)
        player.attachViews(layout, null, false, false)
        player.volume = if (silencioso) 0 else 100

        player.setEventListener { evento ->
            when (evento.type) {
                MediaPlayer.Event.Playing -> {
                    estado = EstadoVideo.Reproduzindo
                    aoMudarEstado(estado)
                }
                MediaPlayer.Event.Buffering -> {
                    if (estado != EstadoVideo.Reproduzindo) {
                        estado = EstadoVideo.Carregando
                        aoMudarEstado(estado)
                    }
                }
                MediaPlayer.Event.EncounteredError -> {
                    estado = EstadoVideo.Erro
                    aoMudarEstado(estado)
                }
                MediaPlayer.Event.EndReached -> {
                    estado = EstadoVideo.Erro
                    aoMudarEstado(estado)
                }
            }
        }

        try {
            val media = Media(libVlc, Uri.parse(url))
            media.setHWDecoderEnabled(true, false)
            if (tcp) media.addOption(":rtsp-tcp")
            // A própria câmera costuma levar alguns segundos para responder.
            media.addOption(":network-caching=800")
            player.media = media
            media.release()
            player.play()
        } catch (e: Exception) {
            estado = EstadoVideo.Erro
            aoMudarEstado(estado)
        }

        onDispose {
            try { player.stop() } catch (_: Exception) { }
            try { player.detachViews() } catch (_: Exception) { }
            try { player.release() } catch (_: Exception) { }
        }
    }

    Box(modifier = modifier.background(Color.Black)) {
        AndroidView(
            factory = { layout },
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
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}
