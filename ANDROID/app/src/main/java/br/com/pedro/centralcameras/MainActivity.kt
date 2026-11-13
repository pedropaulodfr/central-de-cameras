package br.com.pedro.centralcameras

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import br.com.pedro.centralcameras.data.Api
import br.com.pedro.centralcameras.data.CameraLocal
import br.com.pedro.centralcameras.data.CameraStore
import br.com.pedro.centralcameras.data.ErroApi
import br.com.pedro.centralcameras.data.ModoVisualizacao
import br.com.pedro.centralcameras.data.Settings
import br.com.pedro.centralcameras.player.CameraItem
import br.com.pedro.centralcameras.player.Fonte
import br.com.pedro.centralcameras.ui.BannerErro
import br.com.pedro.centralcameras.ui.RotuloConexao
import br.com.pedro.centralcameras.ui.TelaCameraCheia
import br.com.pedro.centralcameras.ui.TelaConfig
import br.com.pedro.centralcameras.ui.TelaGrade
import br.com.pedro.centralcameras.ui.theme.CentralCamerasTheme
import br.com.pedro.centralcameras.ui.theme.Fundo
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        @Suppress("DEPRECATION")
        run {
            window.statusBarColor = COR_FUNDO
            window.navigationBarColor = COR_FUNDO
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }

        setContent {
            CentralCamerasTheme {
                Aplicativo()
            }
        }
    }

    private companion object {
        const val COR_FUNDO = 0xFF0A0E13.toInt()
    }
}

private enum class Tela { Grade, Detalhe, Config }

@Composable
private fun Aplicativo() {
    val contexto = LocalContext.current
    val settings = remember { Settings(contexto) }
    val store = remember { CameraStore(contexto) }
    val escopo = rememberCoroutineScope()

    var modo by remember { mutableStateOf(store.carregarModo()) }
    var camerasLocais by remember { mutableStateOf(store.carregar()) }
    var servidor by remember { mutableStateOf(settings.carregar()) }

    var tela by remember { mutableStateOf(Tela.Grade) }
    var aberta by remember { mutableStateOf<CameraItem?>(null) }
    var controles by remember { mutableStateOf(true) }
    var versao by remember { mutableStateOf(0) }

    var remotas by remember { mutableStateOf<List<br.com.pedro.centralcameras.data.Camera>>(emptyList()) }
    var erro by remember { mutableStateOf<String?>(null) }

    // Fonte de vídeo de uma câmera cadastrada no aparelho.
    fun fonteLocal(c: CameraLocal): Fonte =
        if (c.authQuebrado) Fonte.RtspViaRelay(c.host, c.porta, c.tcp)
        else Fonte.RtspDireto(c.urlDireta(), c.tcp)

    fun buscarRemotas(mostrarErro: Boolean) {
        if (!servidor.preenchida) {
            if (mostrarErro) erro = "Configure o endereço e o token do servidor."
            return
        }
        escopo.launch {
            try {
                remotas = Api(servidor).listarCameras()
                erro = null
            } catch (e: ErroApi) {
                if (mostrarErro) erro = e.message
            } catch (e: Exception) {
                if (mostrarErro) erro = e.message
            }
        }
    }

    // No modo remoto o estado vem do gateway; recarrega de tempo em tempo.
    LaunchedEffect(modo, servidor, versao) {
        if (modo != ModoVisualizacao.Remoto) return@LaunchedEffect
        if (tela == Tela.Detalhe) return@LaunchedEffect
        buscarRemotas(mostrarErro = true)
        while (true) {
            delay(10_000)
            buscarRemotas(mostrarErro = false)
        }
    }

    LaunchedEffect(tela, controles) {
        if (tela == Tela.Detalhe && controles) {
            delay(4_000)
            controles = false
        }
    }

    val view = LocalView.current
    LaunchedEffect(tela) {
        val window = (view.context as? ComponentActivity)?.window ?: return@LaunchedEffect
        val controlador = WindowCompat.getInsetsController(window, view)
        if (tela == Tela.Detalhe) {
            controlador.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controlador.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controlador.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    BackHandler(enabled = tela != Tela.Grade) {
        when (tela) {
            Tela.Detalhe -> {
                tela = Tela.Grade
                aberta = null
                controles = true
            }
            Tela.Config -> tela = Tela.Grade
            Tela.Grade -> Unit
        }
    }

    // Lista que a interface desenha, conforme o modo escolhido.
    val itens: List<CameraItem> = when (modo) {
        ModoVisualizacao.Direto ->
            camerasLocais.map { c ->
                CameraItem(id = c.id, nome = c.nome, fonte = fonteLocal(c))
            }

        ModoVisualizacao.Remoto -> {
            val api = Api(servidor)
            remotas.map { c ->
                CameraItem(
                    id = c.id,
                    nome = c.nome,
                    fonte = Fonte.Hls(api.urlAbsoluta(c.url), api.cabecalhos()),
                )
            }
        }
    }

    val rotulo = when (modo) {
        ModoVisualizacao.Direto -> "rede local, sem PC"
        ModoVisualizacao.Remoto ->
            servidor.baseUrl.removePrefix("https://").removePrefix("http://").substringBefore('/')
    }

    Box(modifier = Modifier.fillMaxSize().background(Fundo)) {
        Crossfade(targetState = tela, animationSpec = tween(200), label = "telas") { atual ->
            when (atual) {

                Tela.Grade -> TelaGrade(
                    cameras = itens,
                    rotulo = rotulo,
                    modo = if (modo == ModoVisualizacao.Direto) RotuloConexao.Direto
                    else RotuloConexao.Remoto,
                    versao = versao,
                    aoAbrirCamera = { cam ->
                        aberta = cam
                        controles = true
                        tela = Tela.Detalhe
                    },
                    aoAtualizar = {
                        versao++
                        if (modo == ModoVisualizacao.Remoto) buscarRemotas(mostrarErro = true)
                    },
                    aoAbrirConfig = {
                        erro = null
                        tela = Tela.Config
                    },
                )

                Tela.Detalhe -> {
                    val cam = aberta
                    if (cam != null) {
                        TelaCameraCheia(
                            camera = cam,
                            aoVoltar = {
                                tela = Tela.Grade
                                aberta = null
                                controles = true
                            },
                            controlesVisiveis = controles,
                            aoAlternarControles = { controles = !controles },
                        )
                    } else {
                        LaunchedEffect(Unit) { tela = Tela.Grade }
                    }
                }

                Tela.Config -> TelaConfig(
                    modo = modo,
                    cameras = camerasLocais,
                    servidor = servidor,
                    aoVoltar = { tela = Tela.Grade },
                    aoMudarModo = {
                        modo = it
                        store.salvarModo(it)
                        versao++
                    },
                    aoMudarCameras = {
                        camerasLocais = it
                        store.salvar(it)
                        versao++
                    },
                    aoMudarServidor = {
                        servidor = it
                        settings.salvar(it)
                        versao++
                    },
                )
            }
        }

        if (tela == Tela.Grade && erro != null) {
            Box(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                BannerErro(
                    mensagem = erro!!,
                    aoFechar = { erro = null },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }
}
