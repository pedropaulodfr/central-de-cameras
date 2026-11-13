package br.com.pedro.centralcameras.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import br.com.pedro.centralcameras.player.CameraItem
import br.com.pedro.centralcameras.player.CameraVideo
import br.com.pedro.centralcameras.ui.theme.Fraco
import br.com.pedro.centralcameras.ui.theme.Texto

/**
 * Uma câmera em tela cheia. Toque em qualquer lugar para mostrar ou esconder
 * os controles.
 */
@Composable
fun TelaCameraCheia(
    camera: CameraItem,
    aoVoltar: () -> Unit,
    controlesVisiveis: Boolean,
    aoAlternarControles: () -> Unit,
) {
    val interacao = remember { MutableInteractionSource() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(
                interactionSource = interacao,
                indication = null,
                onClick = aoAlternarControles,
            ),
    ) {
        CameraVideo(
            fonte = camera.fonte,
            modifier = Modifier.fillMaxSize(),
            silencioso = false,
        )

        if (controlesVisiveis) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xCC0E131A))
                    .padding(horizontal = 8.dp, vertical = 6.dp)
                    .align(Alignment.TopCenter),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = aoVoltar, modifier = Modifier.size(48.dp)) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Voltar",
                        tint = Texto,
                    )
                }
                Spacer(Modifier.size(4.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = camera.nome,
                        style = MaterialTheme.typography.titleMedium,
                        color = Texto,
                    )
                    Text(
                        text = "ao vivo",
                        style = MaterialTheme.typography.bodySmall,
                        color = Fraco,
                    )
                }
            }
        }
    }
}
