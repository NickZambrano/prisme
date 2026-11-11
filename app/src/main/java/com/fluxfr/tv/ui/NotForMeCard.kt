package com.fluxfr.tv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text

/**
 * Fin d'une rangée « Parce que tu as regardé « X » » : X n'est plus utilisé pour les recommandations du profil
 * (regardé par quelqu'un d'autre, import Netflix d'un autre profil…). La rangée disparaît.
 */
@Composable
fun NotForMeCard(seedTitle: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val tv = LocalIsTv.current
    Column(Modifier.revealOnFocus().width(if (tv) 132.dp else 110.dp)) {
        FocusSurface(
            onClick,
            Modifier.fillMaxWidth().aspectRatio(2f / 3f).onFocusChanged { focused = it.isFocused },
            focusedScale = 1.08f,
            containerColor = Color.White.copy(alpha = 0.05f),
        ) {
            Column(
                Modifier.align(Alignment.Center).padding(horizontal = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("✕", fontSize = 24.sp, color = if (focused) Color.White else Palette.Dim)
                Text(
                    "Pas pour moi", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                    color = if (focused) Color.White else Palette.Dim,
                )
                Text(
                    "Je n'ai pas regardé « $seedTitle »", fontSize = 11.sp, textAlign = TextAlign.Center,
                    color = Palette.Faint, maxLines = 4,
                )
            }
        }
    }
}
