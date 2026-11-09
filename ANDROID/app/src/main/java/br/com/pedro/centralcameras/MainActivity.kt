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
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import br.com.pedro.centralcameras.data.Api
import br.com.pedro.centralcameras.data.Camera
import br.com.pedro.centralcameras.data.ConfigServidor
import br.com.pedro.centralcameras.data.ErroApi
import br.com.pedro.centralcameras.data.Settings
import br.com.pedro.centralcameras.ui.TelaCameraCheia
import br.com.pedro.centralcameras.ui.TelaConexao
import br.com.pedro.centralcameras.ui.TelaGrade
import br.com.pedro.centralcameras.ui.theme.CentralCamerasTheme
import br.com.pedro.centralcameras.ui.theme.Fundo
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // O Android 8.1 deixa a barra de navegação translúcida quando se usa
        // edge-to-edge, o que gera uma faixa clara feia sobre o conteúdo.
        // Pintamos as duas barras com a cor de fundo do app.
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

private enum class Tela { Conexao, Grade, Detalhe }

@Composable
private fun Aplicativo() {
    val contexto = LocalContext.current
    val settings = remember { Settings(contexto) }
    val escopo = rememberCoroutineScope()

    var config by remember { mutableStateOf(settings.carregar()) }
    var tela by remember { mutableStateOf(if (config.preenchida) Tela.Grade else Tela.Conexao) }
    var cameras by remember { mutableStateOf<List<Camera>>(emptyList()) }
    var conectando by remember { mutableStateOf(false) }
    var erro by remember { mutableStateOf<String?>(null) }
    var cameraAberta by remember { mutableStateOf<Camera?>(null) }
    var controlesVisiveis by remember { mutableStateOf(true) }
    var atualizacao by remember { mutableStateOf(0) }

    fun aplicarConfig(nova: ConfigServidor) {
        config = nova
        settings.salvar(nova)
    }

    fun buscarCameras(exibirErro: Boolean) {
        if (!config.preenchida) return
        escopo.launch {
            try {
                val api = Api(config)
                cameras = api.listarCameras()
                erro = null
            } catch (e: ErroApi) {
                if (exibirErro) erro = e.message
            } catch (e: Exception) {
                if (exibirErro) erro = e.message
            }
        }
    }

    // Busca inicial e recarregamento periódico do estado das câmeras.
    LaunchedEffect(config, atualizacao, tela) {
        if (!config.preenchida) return@LaunchedEffect
        if (tela == Tela.Detalhe) return@LaunchedEffect

        conectando = tela != Tela.Grade
        buscarCameras(exibirErro = true)
        conectando = false

        while (true) {
            delay(10_000)
            buscarCameras(exibirErro = false)
        }
    }

    // Controles da tela cheia somem sozinhos depois de 4 segundos.
    LaunchedEffect(tela, controlesVisiveis) {
        if (tela == Tela.Detalhe && controlesVisiveis) {
            delay(4_000)
            controlesVisiveis = false
        }
    }

    // Imersivo apenas na tela cheia.
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

    // Voltar: detalhe -> grade; configuração -> grade (ou fecha se nunca conectou).
    BackHandler(enabled = tela != Tela.Grade) {
        when (tela) {
            Tela.Detalhe -> {
                tela = Tela.Grade
                cameraAberta = null
                controlesVisiveis = true
            }
            Tela.Conexao -> {
                if (config.preenchida) {
                    tela = Tela.Grade
                    erro = null
                } else {
                    (contexto as? ComponentActivity)?.finish()
                }
            }
            Tela.Grade -> Unit
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Fundo)) {
        Crossfade(targetState = tela, animationSpec = tween(220), label = "telas") { atual ->

            when (atual) {

                Tela.Conexao -> TelaConexao(
                    configInicial = config,
                    conectando = conectando,
                    erro = erro,
                    aoConectar = { nova ->
                        aplicarConfig(nova)
                        conectando = true
                        erro = null
                        escopo.launch {
                            try {
                                cameras = Api(nova).listarCameras()
                                tela = Tela.Grade
                            } catch (e: ErroApi) {
                                erro = e.message
                            } catch (e: Exception) {
                                erro = e.message ?: "Falha ao conectar."
                            } finally {
                                conectando = false
                            }
                        }
                    },
                    aoCancelar = if (config.preenchida) {
                        { tela = Tela.Grade; erro = null }
                    } else null,
                )

                Tela.Grade -> {
                    val api = remember(config) { Api(config) }
                    TelaGrade(
                        cameras = cameras,
                        urlDe = { cam -> api.urlAbsoluta(cam.url) },
                        cabecalhos = api.cabecalhos(),
                        servidor = config.baseUrl
                            .removePrefix("https://")
                            .removePrefix("http://")
                            .substringBefore('/'),
                        aoAbrirCamera = { cam ->
                            cameraAberta = cam
                            controlesVisiveis = true
                            tela = Tela.Detalhe
                        },
                        aoAtualizar = { atualizacao++ },
                        aoAbrirConfig = {
                            erro = null
                            tela = Tela.Conexao
                        },
                    )
                }

                Tela.Detalhe -> {
                    val cam = cameraAberta
                    val api = remember(config) { Api(config) }
                    if (cam != null) {
                        TelaCameraCheia(
                            camera = cam,
                            url = api.urlAbsoluta(cam.url),
                            cabecalhos = api.cabecalhos(),
                            aoVoltar = {
                                tela = Tela.Grade
                                cameraAberta = null
                                controlesVisiveis = true
                            },
                            controlesVisiveis = controlesVisiveis,
                            aoAlternarControles = { controlesVisiveis = !controlesVisiveis },
                        )
                    } else {
                        LaunchedEffect(Unit) { tela = Tela.Grade }
                    }
                }
            }
        }

        // Erro persistente na grade (ex.: servidor caiu).
        if (tela == Tela.Grade && erro != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
            ) {
                br.com.pedro.centralcameras.ui.BannerErro(
                    mensagem = erro!!,
                    aoFechar = { erro = null },
                    modifier = Modifier.align(androidx.compose.ui.Alignment.BottomCenter),
                )
            }
        }
    }
}
