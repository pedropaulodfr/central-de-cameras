package br.com.pedro.centralcameras.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import br.com.pedro.centralcameras.data.ConfigServidor
import br.com.pedro.centralcameras.ui.theme.Azul
import br.com.pedro.centralcameras.ui.theme.Borda
import br.com.pedro.centralcameras.ui.theme.Botao
import br.com.pedro.centralcameras.ui.theme.Fraco
import br.com.pedro.centralcameras.ui.theme.Texto
import br.com.pedro.centralcameras.ui.theme.Topo
import br.com.pedro.centralcameras.ui.theme.Vermelho

/**
 * Tela de configuração: endereço do gateway e token de acesso.
 * Aparece na primeira execução e quando o usuário toca em "Servidor".
 */
@Composable
fun TelaConexao(
    configInicial: ConfigServidor,
    conectando: Boolean,
    erro: String?,
    aoConectar: (ConfigServidor) -> Unit,
    aoCancelar: (() -> Unit)? = null,
) {
    var endereco by rememberSaveable { mutableStateOf(configInicial.baseUrl) }
    var token by rememberSaveable { mutableStateOf(configInicial.token) }
    var tokenVisivel by remember { mutableStateOf(false) }

    val podeConectar = endereco.isNotBlank() && token.isNotBlank() && !conectando

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 24.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(24.dp))

            // Marca simples: quadrado arredondado com o glifo de câmera
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .background(Botao, RoundedCornerShape(20.dp))
                    .border(1.dp, Borda, RoundedCornerShape(20.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text("●", color = Azul, style = MaterialTheme.typography.headlineMedium)
            }

            Spacer(Modifier.height(20.dp))

            Text(
                text = "Central de Câmeras",
                style = MaterialTheme.typography.headlineMedium,
                color = Texto,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Acesse suas câmeras de qualquer lugar",
                style = MaterialTheme.typography.bodyMedium,
                color = Fraco,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(32.dp))

            // Campo: endereço do servidor
            Rotulo("Endereço do servidor")
            OutlinedTextField(
                value = endereco,
                onValueChange = { endereco = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("https://cameras.exemplo.com", color = Fraco) },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Next,
                ),
                shape = RoundedCornerShape(12.dp),
                colors = coresCampo(),
            )
            Ajuda("Endereço mostrado pelo gateway no PC.")

            Spacer(Modifier.height(20.dp))

            // Campo: token
            Rotulo("Token de acesso")
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("cole o token aqui", color = Fraco) },
                visualTransformation = if (tokenVisivel) VisualTransformation.None
                else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
                trailingIcon = {
                    IconButton(onClick = { tokenVisivel = !tokenVisivel }) {
                        Icon(
                            imageVector = if (tokenVisivel) Icons.Filled.VisibilityOff
                            else Icons.Filled.Visibility,
                            contentDescription = if (tokenVisivel) "Ocultar token" else "Mostrar token",
                            tint = Fraco,
                        )
                    }
                },
                shape = RoundedCornerShape(12.dp),
                colors = coresCampo(),
            )
            Ajuda("Definido com --token ao iniciar o servidor.")

            if (!erro.isNullOrBlank()) {
                Spacer(Modifier.height(20.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF3A1414), RoundedCornerShape(10.dp))
                        .border(1.dp, Color(0xFF6B2B2B), RoundedCornerShape(10.dp))
                        .padding(14.dp),
                ) {
                    Text(
                        text = erro,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFFFD9D9),
                    )
                }
            }

            Spacer(Modifier.height(28.dp))

            Button(
                onClick = {
                    aoConectar(
                        ConfigServidor(baseUrl = endereco, token = token).normalizada()
                    )
                },
                enabled = podeConectar,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Azul,
                    contentColor = Color.White,
                    disabledContainerColor = Botao,
                    disabledContentColor = Fraco,
                ),
            ) {
                if (conectando) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = Color.White,
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.size(12.dp))
                    Text("Conectando…", style = MaterialTheme.typography.labelLarge)
                } else {
                    Text("Conectar", style = MaterialTheme.typography.labelLarge)
                }
            }

            if (aoCancelar != null) {
                Spacer(Modifier.height(8.dp))
                androidx.compose.material3.TextButton(onClick = aoCancelar) {
                    Text("Cancelar", color = Fraco, style = MaterialTheme.typography.labelLarge)
                }
            }

            Spacer(Modifier.height(32.dp))

            // Passo a passo resumido
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Topo, RoundedCornerShape(12.dp))
                    .border(1.dp, Borda, RoundedCornerShape(12.dp))
                    .padding(16.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Como conectar",
                        style = MaterialTheme.typography.titleMedium,
                        color = Texto,
                    )
                    Passo("1", "No PC, inicie o gateway em ANDROID/gateway.")
                    Passo("2", "O gateway mostra o endereço e o token.")
                    Passo("3", "Digite os dois aqui e toque em Conectar.")
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(
                text = "O PC precisa estar ligado e conectado à mesma rede das câmeras.",
                style = MaterialTheme.typography.bodySmall,
                color = Fraco,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Rotulo(texto: String) {
    Text(
        text = texto,
        style = MaterialTheme.typography.labelMedium,
        color = Fraco,
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
    )
}

@Composable
private fun Ajuda(texto: String) {
    Text(
        text = texto,
        style = MaterialTheme.typography.bodySmall,
        color = Fraco,
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
    )
}

@Composable
private fun Passo(numero: String, texto: String) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .background(Botao, RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(numero, style = MaterialTheme.typography.labelSmall, color = Azul)
        }
        Spacer(Modifier.size(10.dp))
        Text(
            text = texto,
            style = MaterialTheme.typography.bodySmall,
            color = Fraco,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun coresCampo() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Texto,
    unfocusedTextColor = Texto,
    focusedBorderColor = Azul,
    unfocusedBorderColor = Borda,
    focusedContainerColor = Botao,
    unfocusedContainerColor = Botao,
    cursorColor = Azul,
    errorBorderColor = Vermelho,
)
