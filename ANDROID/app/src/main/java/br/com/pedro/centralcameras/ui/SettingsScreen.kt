package br.com.pedro.centralcameras.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import br.com.pedro.centralcameras.data.CameraLocal
import br.com.pedro.centralcameras.data.ConfigServidor
import br.com.pedro.centralcameras.data.ModoVisualizacao
import br.com.pedro.centralcameras.ui.theme.Azul
import br.com.pedro.centralcameras.ui.theme.Borda
import br.com.pedro.centralcameras.ui.theme.Botao
import br.com.pedro.centralcameras.ui.theme.Fraco
import br.com.pedro.centralcameras.ui.theme.Texto
import br.com.pedro.centralcameras.ui.theme.Topo
import br.com.pedro.centralcameras.ui.theme.Verde

/**
 * Configuração do app.
 *
 * Modo Direto  = o celular fala RTSP com a câmera. Não usa o PC.
 *                Só funciona na mesma rede das câmeras.
 * Modo Remoto  = fala HLS com o gateway rodando no PC. Funciona de qualquer
 *                lugar, mas depende do PC estar ligado.
 */
@Composable
fun TelaConfig(
    modo: ModoVisualizacao,
    cameras: List<CameraLocal>,
    servidor: ConfigServidor,
    aoVoltar: () -> Unit,
    aoMudarModo: (ModoVisualizacao) -> Unit,
    aoMudarCameras: (List<CameraLocal>) -> Unit,
    aoMudarServidor: (ConfigServidor) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        // Cabeçalho
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Topo)
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = aoVoltar, modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar", tint = Texto)
            }
            Spacer(Modifier.width(4.dp))
            Text(
                "Configurações",
                style = MaterialTheme.typography.headlineSmall,
                color = Texto,
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
        ) {
            Secao("Como ver as câmeras")

            OpcaoModo(
                titulo = "Direto (sem PC)",
                descricao = "O celular fala com as câmeras. Funciona só em casa, na mesma rede.",
                selecionado = modo == ModoVisualizacao.Direto,
                aoSelecionar = { aoMudarModo(ModoVisualizacao.Direto) },
            )
            Spacer(Modifier.height(10.dp))
            OpcaoModo(
                titulo = "Remoto (pelo PC)",
                descricao = "Usa o gateway do PC. Funciona em qualquer lugar, mas o PC precisa estar ligado.",
                selecionado = modo == ModoVisualizacao.Remoto,
                aoSelecionar = { aoMudarModo(ModoVisualizacao.Remoto) },
            )

            Spacer(Modifier.height(28.dp))

            if (modo == ModoVisualizacao.Direto) {
                Secao("Câmeras na rede local")
                Text(
                    "Informe o endereço de cada câmera. A senha só é necessária quando " +
                        "a câmera exige login (as Yoosee não exigem, marque \"autenticação " +
                        "quebrada\").",
                    style = MaterialTheme.typography.bodySmall,
                    color = Fraco,
                )
                Spacer(Modifier.height(14.dp))

                cameras.forEachIndexed { indice, cam ->
                    CardCameraConfig(
                        camera = cam,
                        aoMudar = { nova ->
                            aoMudarCameras(cameras.toMutableList().also { it[indice] = nova })
                        },
                    )
                    Spacer(Modifier.height(12.dp))
                }
            } else {
                Secao("Servidor (gateway no PC)")
                CampoTexto(
                    rotulo = "Endereço do servidor",
                    valor = servidor.baseUrl,
                    dica = "http://192.168.0.4:8080 ou https://…trycloudflare.com",
                    aoMudar = { aoMudarServidor(servidor.copy(baseUrl = it)) },
                    teclado = KeyboardType.Uri,
                )
                Spacer(Modifier.height(14.dp))
                CampoTexto(
                    rotulo = "Token de acesso",
                    valor = servidor.token,
                    dica = "mostrado pelo Iniciar-Gateway.bat",
                    aoMudar = { aoMudarServidor(servidor.copy(token = it)) },
                    teclado = KeyboardType.Password,
                    ehSenha = true,
                )
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun Secao(texto: String) {
    Text(
        text = texto,
        style = MaterialTheme.typography.titleMedium,
        color = Texto,
        modifier = Modifier.padding(bottom = 12.dp),
    )
}

@Composable
private fun OpcaoModo(
    titulo: String,
    descricao: String,
    selecionado: Boolean,
    aoSelecionar: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selecionado) Color(0xFF121C26) else Topo, RoundedCornerShape(12.dp))
            .border(
                1.dp,
                if (selecionado) Azul else Borda,
                RoundedCornerShape(12.dp),
            )
            .clickable(onClick = aoSelecionar)
            .padding(14.dp)
            .heightIn(min = 56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .background(
                    if (selecionado) Azul else Color.Transparent,
                    RoundedCornerShape(6.dp),
                )
                .border(1.dp, if (selecionado) Azul else Borda, RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (selecionado) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(15.dp))
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(titulo, style = MaterialTheme.typography.titleMedium, color = Texto)
            Spacer(Modifier.height(2.dp))
            Text(descricao, style = MaterialTheme.typography.bodySmall, color = Fraco)
        }
    }
}

@Composable
private fun CardCameraConfig(
    camera: CameraLocal,
    aoMudar: (CameraLocal) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Topo, RoundedCornerShape(12.dp))
            .border(1.dp, Borda, RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(camera.nome, style = MaterialTheme.typography.titleMedium, color = Texto)

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(modifier = Modifier.weight(2f)) {
                CampoTexto(
                    rotulo = "Endereço (IP)",
                    valor = camera.host,
                    dica = "192.168.0.5",
                    aoMudar = { aoMudar(camera.copy(host = it.trim())) },
                    teclado = KeyboardType.Uri,
                )
            }
            Box(modifier = Modifier.weight(1f)) {
                CampoTexto(
                    rotulo = "Porta",
                    valor = camera.porta.toString(),
                    dica = "554",
                    aoMudar = { txt ->
                        val p = txt.filter { it.isDigit() }.toIntOrNull() ?: 554
                        aoMudar(camera.copy(porta = p))
                    },
                    teclado = KeyboardType.Number,
                )
            }
        }

        if (!camera.authQuebrado) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(modifier = Modifier.weight(1f)) {
                    CampoTexto(
                        rotulo = "Usuário",
                        valor = camera.usuario,
                        dica = "usuario",
                        aoMudar = { aoMudar(camera.copy(usuario = it)) },
                    )
                }
                Box(modifier = Modifier.weight(1f)) {
                    CampoTexto(
                        rotulo = "Senha",
                        valor = camera.senha,
                        dica = "senha",
                        aoMudar = { aoMudar(camera.copy(senha = it)) },
                        teclado = KeyboardType.Password,
                        ehSenha = true,
                    )
                }
            }
        }

        // Alternador: firmware com autenticação quebrada (Yoosee)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { aoMudar(camera.copy(authQuebrado = !camera.authQuebrado)) }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Autenticação quebrada (Yoosee)",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Texto,
                )
                Text(
                    "O app corrige sozinho. Não precisa de senha.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Fraco,
                )
            }
            Switch(
                checked = camera.authQuebrado,
                onCheckedChange = { aoMudar(camera.copy(authQuebrado = it)) },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = Azul,
                    uncheckedThumbColor = Fraco,
                    uncheckedTrackColor = Botao,
                ),
            )
        }
    }
}

@Composable
private fun CampoTexto(
    rotulo: String,
    valor: String,
    dica: String,
    aoMudar: (String) -> Unit,
    teclado: KeyboardType = KeyboardType.Text,
    ehSenha: Boolean = false,
) {
    var visivel by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            rotulo,
            style = MaterialTheme.typography.labelMedium,
            color = Fraco,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        OutlinedTextField(
            value = valor,
            onValueChange = aoMudar,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text(dica, color = Fraco) },
            keyboardOptions = KeyboardOptions(keyboardType = teclado),
            // Senha nunca aparece na tela, a menos que o usuário peça.
            visualTransformation = if (ehSenha && !visivel) {
                PasswordVisualTransformation()
            } else VisualTransformation.None,
            trailingIcon = if (ehSenha) {
                {
                    IconButton(onClick = { visivel = !visivel }) {
                        Icon(
                            imageVector = if (visivel) Icons.Filled.VisibilityOff
                            else Icons.Filled.Visibility,
                            contentDescription = if (visivel) "Ocultar senha" else "Mostrar senha",
                            tint = Fraco,
                        )
                    }
                }
            } else null,
            shape = RoundedCornerShape(10.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Texto,
                unfocusedTextColor = Texto,
                focusedBorderColor = Azul,
                unfocusedBorderColor = Borda,
                focusedContainerColor = Botao,
                unfocusedContainerColor = Botao,
                cursorColor = Azul,
            ),
        )
    }
}
