package br.com.pedro.centralcameras.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import br.com.pedro.centralcameras.player.CameraItem
import br.com.pedro.centralcameras.player.CameraVideo
import br.com.pedro.centralcameras.player.EstadoVideo
import br.com.pedro.centralcameras.ui.theme.Ambar
import br.com.pedro.centralcameras.ui.theme.Azul
import br.com.pedro.centralcameras.ui.theme.Borda
import br.com.pedro.centralcameras.ui.theme.Fraco
import br.com.pedro.centralcameras.ui.theme.Texto
import br.com.pedro.centralcameras.ui.theme.Topo
import br.com.pedro.centralcameras.ui.theme.Verde
import br.com.pedro.centralcameras.ui.theme.Vermelho

/** Modo de visualização, para o cabeçalho mostrar de onde vem o vídeo. */
enum class RotuloConexao { Direto, Remoto }

@Composable
fun TelaGrade(
    cameras: List<CameraItem>,
    rotulo: String,
    modo: RotuloConexao,
    versao: Int,
    aoAbrirCamera: (CameraItem) -> Unit,
    aoAtualizar: () -> Unit,
    aoAbrirConfig: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Cabecalho(
            quantidade = cameras.size,
            subtitulo = rotulo,
            modo = modo,
            aoAtualizar = aoAtualizar,
            aoAbrirConfig = aoAbrirConfig,
        )

        if (cameras.isEmpty()) {
            Vazio(modo)
            return@Column
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 320.dp),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 16.dp,
                bottom = 16.dp +
                    WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
            ),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            // A versão entra na chave para que "Atualizar" recrie os players.
            items(cameras, key = { "${it.id}-$versao" }) { cam ->
                CardCamera(camera = cam, aoAbrir = { aoAbrirCamera(cam) })
            }
        }
    }
}

@Composable
private fun Cabecalho(
    quantidade: Int,
    subtitulo: String,
    modo: RotuloConexao,
    aoAtualizar: () -> Unit,
    aoAbrirConfig: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Topo)
            .statusBarsPadding()
            .padding(start = 20.dp, end = 8.dp, top = 14.dp, bottom = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Central de Câmeras",
                    style = MaterialTheme.typography.headlineSmall,
                    color = Texto,
                )
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Etiqueta do modo: deixa claro se depende do PC ou não.
                    Box(
                        modifier = Modifier
                            .background(
                                if (modo == RotuloConexao.Direto) Color(0xFF12301C)
                                else Color(0xFF10243D),
                                RoundedCornerShape(6.dp),
                            )
                            .padding(horizontal = 7.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = if (modo == RotuloConexao.Direto) "direto" else "remoto",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (modo == RotuloConexao.Direto) Verde else Azul,
                        )
                    }
                    Spacer(Modifier.size(8.dp))
                    Text(
                        text = if (quantidade == 1) "1 câmera · $subtitulo"
                        else "$quantidade câmeras · $subtitulo",
                        style = MaterialTheme.typography.bodySmall,
                        color = Fraco,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            IconButton(onClick = aoAtualizar, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Filled.Refresh, contentDescription = "Atualizar", tint = Fraco)
            }
            IconButton(onClick = aoAbrirConfig, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Filled.Settings, contentDescription = "Configurações", tint = Fraco)
            }
        }
    }
}

@Composable
private fun CardCamera(
    camera: CameraItem,
    aoAbrir: () -> Unit,
) {
    var estadoVideo by remember(camera.id) { mutableStateOf(EstadoVideo.Carregando) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF06090C))
            .border(1.dp, Borda, RoundedCornerShape(16.dp))
            .clickable(onClick = aoAbrir),
    ) {
        CameraVideo(
            fonte = camera.fonte,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f),
            aoMudarEstado = { estadoVideo = it },
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .background(Topo)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(9.dp)
                    .background(corDoEstado(estadoVideo), CircleShape),
            )
            Spacer(Modifier.size(10.dp))
            Text(
                text = camera.nome,
                style = MaterialTheme.typography.titleMedium,
                color = Texto,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.size(8.dp))
            Text(
                text = rotuloDoEstado(estadoVideo),
                style = MaterialTheme.typography.bodySmall,
                color = corDoEstado(estadoVideo),
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun Vazio(modo: RotuloConexao) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "Nenhuma câmera",
                style = MaterialTheme.typography.titleMedium,
                color = Texto,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                if (modo == RotuloConexao.Direto)
                    "Toque na engrenagem para cadastrar as câmeras."
                else
                    "Toque na engrenagem e informe o endereço do gateway.",
                style = MaterialTheme.typography.bodySmall,
                color = Fraco,
            )
        }
    }
}

private fun corDoEstado(e: EstadoVideo): Color = when (e) {
    EstadoVideo.Reproduzindo -> Verde
    EstadoVideo.Carregando -> Ambar
    EstadoVideo.Erro -> Vermelho
}

private fun rotuloDoEstado(e: EstadoVideo): String = when (e) {
    EstadoVideo.Reproduzindo -> "ao vivo"
    EstadoVideo.Carregando -> "conectando"
    EstadoVideo.Erro -> "sem sinal"
}
