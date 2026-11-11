package com.fluxfr.tv.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text

const val PIN_LENGTH = 4

private val DIGIT_KEYS = mapOf(
    Key.Zero to '0', Key.One to '1', Key.Two to '2', Key.Three to '3', Key.Four to '4',
    Key.Five to '5', Key.Six to '6', Key.Seven to '7', Key.Eight to '8', Key.Nine to '9',
    Key.NumPad0 to '0', Key.NumPad1 to '1', Key.NumPad2 to '2', Key.NumPad3 to '3', Key.NumPad4 to '4',
    Key.NumPad5 to '5', Key.NumPad6 to '6', Key.NumPad7 to '7', Key.NumPad8 to '8', Key.NumPad9 to '9',
)

/**
 * Saisie d'un code à 4 chiffres : pavé 3×4 focalisable (0-9, ⌫, OK) et touches chiffres de la
 * télécommande. Le code est validé au 4e chiffre (ou par OK).
 *
 * @param errorTick incrémenté par l'appelant quand le code est refusé : secoue le pavé et l'efface.
 * @param message texte sous le titre ; affiché en rouge si [isError].
 */
@Composable
fun PinPad(
    title: String,
    onSubmit: (String) -> Unit,
    modifier: Modifier = Modifier,
    message: String? = null,
    isError: Boolean = false,
    errorTick: Int = 0,
    focusRequester: FocusRequester = remember { FocusRequester() },
    requestFocusOnStart: Boolean = true,
) {
    var digits by remember { mutableStateOf("") }
    val shake = remember { Animatable(0f) }

    LaunchedEffect(errorTick) {
        if (errorTick > 0) {
            digits = ""
            for (x in listOf(-14f, 14f, -10f, 10f, -5f, 5f, 0f)) shake.animateTo(x, tween(45))
        }
    }
    if (requestFocusOnStart) {
        LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
    }

    fun submit() {
        if (digits.length == PIN_LENGTH) {
            val pin = digits
            digits = ""
            onSubmit(pin)
        }
    }
    fun type(c: Char) {
        if (digits.length < PIN_LENGTH) {
            digits += c
            if (digits.length == PIN_LENGTH) submit()
        }
    }
    fun erase() { digits = digits.dropLast(1) }

    Column(
        modifier
            .onPreviewKeyEvent { e ->
                val d = DIGIT_KEYS[e.key]
                when {
                    d != null -> { if (e.type == KeyEventType.KeyDown) type(d); true }
                    e.key == Key.Backspace || e.key == Key.Delete -> {
                        if (e.type == KeyEventType.KeyDown) erase(); true
                    }
                    else -> false
                }
            }
            .focusGroup(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Color.White, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(
            message ?: " ", fontSize = 13.sp, textAlign = TextAlign.Center,
            color = if (isError) Palette.Error else Palette.Dim,
            fontWeight = if (isError) FontWeight.SemiBold else FontWeight.Normal,
        )
        Spacer(Modifier.height(14.dp))
        // Points du code
        Row(
            Modifier.graphicsLayer { translationX = shake.value * density },
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            repeat(PIN_LENGTH) { i ->
                val filled = i < digits.length
                Box(
                    Modifier.size(16.dp)
                        .background(
                            if (filled) (if (isError) Palette.Error else Color.White) else Color.Transparent,
                            CircleShape,
                        )
                        .border(2.dp, if (isError) Palette.Error else Color.White.copy(alpha = 0.6f), CircleShape)
                )
            }
        }
        Spacer(Modifier.height(18.dp))
        // Pavé numérique
        val rows = listOf(
            listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("⌫", "0", "OK"),
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            rows.forEachIndexed { r, row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEachIndexed { c, label ->
                        PadKey(
                            label,
                            onClick = {
                                when (label) {
                                    "⌫" -> erase()
                                    "OK" -> submit()
                                    else -> type(label[0])
                                }
                            },
                            accent = label == "OK" && digits.length == PIN_LENGTH,
                            modifier = if (r == 0 && c == 0) Modifier.focusRequester(focusRequester) else Modifier,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PadKey(label: String, onClick: () -> Unit, accent: Boolean, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    FocusSurface(
        onClick = onClick,
        modifier = modifier.size(width = 64.dp, height = 46.dp).onFocusChanged { focused = it.isFocused },
        shape = RoundedCornerShape(12.dp),
        focusedScale = 1.08f,
        containerColor = if (accent) Palette.Accent else Color.White.copy(alpha = 0.08f),
        focusedContainerColor = Color.White,
        focusedContentColor = Color.Black,
        glow = false,
    ) {
        Text(
            label, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
            color = if (focused) Color.Black else Color.White,
            modifier = Modifier.align(Alignment.Center),
        )
    }
}
