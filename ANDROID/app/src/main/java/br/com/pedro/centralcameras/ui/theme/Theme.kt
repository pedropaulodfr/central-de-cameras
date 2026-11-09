package br.com.pedro.centralcameras.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Paleta: a mesma do app desktop, para o usuário reconhecer o produto.
val Fundo = Color(0xFF0A0E13)
val Topo = Color(0xFF0E131A)
val Barra = Color(0xFF0C1016)
val Botao = Color(0xFF1B242E)
val BotaoHover = Color(0xFF24303D)
val Borda = Color(0xFF202C3A)
val Texto = Color(0xFFE7EEF6)
val Fraco = Color(0xFF8595AC)
val Verde = Color(0xFF3FB950)
val Ambar = Color(0xFFE6AF3C)
val Vermelho = Color(0xFFF06464)
val Azul = Color(0xFF388BFD)

/**
 * Contraste sobre Fundo (#0A0E13):
 *   Texto    #E7EEF6 -> ~15,8:1  (AAA)
 *   Fraco    #8595AC -> ~ 6,4:1  (AA para texto normal)
 *   Verde    #3FB950 -> ~ 6,1:1
 *   Ambar    #E6AF3C -> ~ 9,7:1
 *   Vermelho #F06464 -> ~ 5,2:1
 */
private val EsquemaEscuro = darkColorScheme(
    primary = Azul,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF16304F),
    onPrimaryContainer = Color(0xFFCFE2FF),
    secondary = Azul,
    background = Fundo,
    onBackground = Texto,
    surface = Topo,
    onSurface = Texto,
    surfaceVariant = Botao,
    onSurfaceVariant = Fraco,
    surfaceContainer = Barra,
    surfaceContainerHigh = Botao,
    outline = Borda,
    error = Vermelho,
    onError = Color(0xFF1A0A0A),
    errorContainer = Color(0xFF3A1414),
    onErrorContainer = Color(0xFFFFD9D9),
)

// Tipografia: base 16sp, textos auxiliares 13-14sp (nunca abaixo de 12sp).
private val Tipografia = Typography(
    headlineMedium = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp),
    headlineSmall = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
    titleLarge = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Normal, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Normal, lineHeight = 21.sp),
    bodySmall = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Normal, lineHeight = 19.sp),
    labelLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun CentralCamerasTheme(
    @Suppress("UNUSED_PARAMETER") escuro: Boolean = isSystemInDarkTheme(),
    conteudo: @Composable () -> Unit,
) {
    // O app é sempre escuro: câmera em ambiente com pouca luz combina com
    // interface escura, e evita ofuscar quem abre o app de madrugada.
    MaterialTheme(
        colorScheme = EsquemaEscuro,
        typography = Tipografia,
        content = conteudo,
    )
}
