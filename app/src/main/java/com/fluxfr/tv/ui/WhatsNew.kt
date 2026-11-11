package com.fluxfr.tv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.fluxfr.tv.data.Changelog
import com.fluxfr.tv.ui.settings.SettingsButton
import kotlinx.coroutines.delay

/**
 * Fenêtre « Nouveautés » : au premier démarrage après une mise à jour ([Changelog.pending]), ou depuis
 * Réglages > Mise à jour. [entries] : la plus récente en premier.
 */
@Composable
fun WhatsNewDialog(entries: List<Changelog.Entry>, title: String, onClose: () -> Unit) {
    val ok = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(80); runCatching { ok.requestFocus() } }
    androidx.activity.compose.BackHandler(onBack = onClose)
    ModalPanel(620.dp) {
        Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Spacer(Modifier.height(14.dp))
        Column(
            Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            for ((i, e) in entries.withIndex()) {
                if (entries.size > 1 || i > 0) {
                    Text("Version ${e.version}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Palette.Accent)
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (item in e.items) {
                        Row {
                            Text("•", fontSize = 15.sp, color = Palette.Accent)
                            Spacer(Modifier.width(10.dp))
                            Text(item, fontSize = 15.sp, color = Color.White, lineHeight = 21.sp)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        SettingsButton("OK", onClose, Modifier.focusRequester(ok), primary = true)
        Spacer(Modifier.padding(bottom = 2.dp))
    }
}
