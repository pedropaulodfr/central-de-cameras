package br.com.pedro.centralcameras.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Aviso flutuante de erro. Fica sobre a grade e pode ser dispensado.
 * Aparece quando o gateway fica inacessível depois de já estar conectado.
 */
@Composable
fun BannerErro(
    mensagem: String,
    aoFechar: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF3A1414), RoundedCornerShape(12.dp))
            .border(1.dp, Color(0xFF6B2B2B), RoundedCornerShape(12.dp))
            .padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)
            .heightIn(min = 52.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Warning,
            contentDescription = null,
            tint = Color(0xFFFFD9D9),
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.size(10.dp))
        Text(
            text = mensagem,
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFFFFD9D9),
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = aoFechar, modifier = Modifier.size(48.dp)) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "Fechar aviso",
                tint = Color(0xFFFFD9D9),
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
