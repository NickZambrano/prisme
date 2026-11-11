package com.fluxfr.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActionScope
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.runtime.produceState
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.platform.LocalDensity
import com.fluxfr.tv.data.Channel
import com.fluxfr.tv.data.DisplayTitle
import com.fluxfr.tv.data.VodMeta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.fluxfr.tv.data.Catalog
import com.fluxfr.tv.data.Favorites
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import com.fluxfr.tv.data.Movie
import com.fluxfr.tv.data.Series
import com.fluxfr.tv.data.searchKey
import com.fluxfr.tv.player.PlayerSession
import com.fluxfr.tv.player.Playables
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun SearchScreen(
    catalog: Catalog,
    onOpenSeries: (Series) -> Unit,
    onOpenMovie: (Movie) -> Unit,
    /** Recherche lancée depuis l'extérieur (Assistant, recherche globale de la TV). */
    initialQuery: String? = null,
    /** Appelé une fois [initialQuery] appliquée : l'appelant l'oublie (consommation unique). */
    onSeedConsumed: () -> Unit = {},
) {
    val ctx = LocalContext.current
    val isTv = LocalIsTv.current
    var query by rememberSaveable { mutableStateOf("") }
    var debounced by remember { mutableStateOf(query) }
    val isCompactWidth = rememberIsCompact()
    val autoFocusOnEntry = remember { !isTv && initialQuery.isNullOrBlank() && query.isEmpty() }
    LaunchedEffect(initialQuery) {
        if (!initialQuery.isNullOrBlank()) {
            query = initialQuery; debounced = initialQuery
            onSeedConsumed()
        }
    }
    LaunchedEffect(query) { delay(250); debounced = query }

    val words = remember(debounced) {
        searchKey(debounced).split(' ').filter { it.isNotBlank() }
    }
    val active = words.sumOf { it.length } >= 2

    // Classement hors du thread principal (titres nettoyés par DisplayTitle) : les titres qui COMMENCENT par la
    // recherche d'abord, puis ceux dont un mot commence ainsi, puis ceux qui la contiennent ; le plus récent d'abord.
    val results by produceState(SearchResults.EMPTY, words, catalog, active) {
        value = if (!active) SearchResults.EMPTY else withContext(Dispatchers.Default) { rankResults(words, catalog) }
    }
    val channels = results.channels
    val movies = results.movies
    val series = results.series
    val total = channels.size + movies.size + series.size

    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    // Première vignette du premier résultat : cible de ▼ depuis le champ.
    val firstResult = remember { FocusRequester() }
    fun focusFirstResult() {
        if (runCatching { firstResult.requestFocus() }.getOrDefault(false) == true) return
        scope.launch {
            repeat(8) {
                withFrameNanos { }
                if (runCatching { firstResult.requestFocus() }.getOrDefault(false) == true) return@launch
            }
        }
    }
    // « Rechercher » / « OK » sur le clavier : on le ferme et on descend sur le premier résultat.
    val submit: KeyboardActionScope.() -> Unit = {
        debounced = query
        keyboard?.hide()
        // Téléphone : on ferme juste le clavier (pas de déplacement du focus).
        if (isTv) scope.launch {
            // Laisse les résultats se composer (recherche non encore « debouncée ») avant de descendre.
            // Attendre le relâchement de la touche de validation : sinon elle « clique » sur le 1er résultat.
            delay(450)
            focusFirstResult()
        }
    }

    // Recherche vocale (micro de la télécommande) : la reconnaissance Google renvoie le texte dicté.
    val voiceLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val text = res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (res.resultCode == Activity.RESULT_OK && !text.isNullOrBlank()) {
            query = text
            debounced = text
            if (isTv) scope.launch {
                // Le focus revient sur le bouton micro au retour de l'activité : on descend sur le premier résultat.
                delay(350)
                focusFirstResult()
            }
        }
    }
    // Repli : fenêtre vocale de Google (si la reconnaissance directe est indisponible ou le micro refusé).
    fun startVoiceIntent() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fr-FR")
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Que veux-tu regarder ?")
        try {
            voiceLauncher.launch(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(ctx, "Recherche vocale indisponible", Toast.LENGTH_SHORT).show()
        } catch (_: SecurityException) {
            Toast.makeText(ctx, "Recherche vocale indisponible", Toast.LENGTH_SHORT).show()
        }
    }
    // Reconnaissance directe (service vocal de la TV) : le texte s'écrit dans le champ pendant qu'on parle.
    var listening by remember { mutableStateOf(false) }
    val recognizer = remember {
        if (android.speech.SpeechRecognizer.isRecognitionAvailable(ctx))
            runCatching { android.speech.SpeechRecognizer.createSpeechRecognizer(ctx) }.getOrNull() else null
    }
    androidx.compose.runtime.DisposableEffect(recognizer) { onDispose { runCatching { recognizer?.destroy() } } }
    fun listenDirect(): Boolean {
        val r = recognizer ?: return false
        r.setRecognitionListener(object : android.speech.RecognitionListener {
            private fun first(b: android.os.Bundle?) =
                b?.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            override fun onReadyForSpeech(params: android.os.Bundle?) { listening = true }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
            override fun onPartialResults(partialResults: android.os.Bundle?) {
                first(partialResults)?.takeIf { it.isNotBlank() }?.let { query = it }
            }
            override fun onResults(results: android.os.Bundle?) {
                listening = false
                val text = first(results)
                if (!text.isNullOrBlank()) {
                    query = text
                    debounced = text
                    if (isTv) scope.launch { delay(350); focusFirstResult() }
                }
            }
            override fun onError(error: Int) {
                listening = false
                val msg = when (error) {
                    android.speech.SpeechRecognizer.ERROR_NO_MATCH, android.speech.SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Je n'ai pas compris, réessaie"
                    android.speech.SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Autorise le micro pour Prisme"
                    android.speech.SpeechRecognizer.ERROR_NETWORK, android.speech.SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Reconnaissance vocale : pas de réseau"
                    else -> null
                }
                msg?.let { Toast.makeText(ctx, it, Toast.LENGTH_SHORT).show() }
            }
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fr-FR")
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
        return runCatching { r.startListening(intent); listening = true; true }.getOrDefault(false)
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted || !listenDirect()) startVoiceIntent()
    }
    fun startVoice() {
        if (listening) { runCatching { recognizer?.stopListening() }; listening = false; return }
        if (recognizer == null) { startVoiceIntent(); return }
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) { if (!listenDirect()) startVoiceIntent() }
        else micPermission.launch(android.Manifest.permission.RECORD_AUDIO)
    }
    LaunchedEffect(Unit) { Favorites.load(ctx) }
    val favs by Favorites.items.collectAsState()

    Box(
        Modifier.fillMaxSize().onPreviewKeyEvent { e ->
            val k = e.nativeKeyEvent.keyCode
            if (k == KeyEvent.KEYCODE_SEARCH || k == KeyEvent.KEYCODE_VOICE_ASSIST) {
                if (e.type == KeyEventType.KeyUp) startVoice()
                true
            } else false
        }
    ) {
        // Halo d'ambiance très discret en haut à droite
        Box(
            Modifier.fillMaxSize().background(
                Brush.radialGradient(
                    listOf(Palette.Accent2.copy(alpha = 0.10f), Color.Transparent),
                    center = Offset(1400f, -200f),
                    radius = 1100f,
                )
            )
        )
        // Le clavier à l'écran (TV) occupe le bas de l'écran : la zone des résultats se réduit pour rester
        // visible au-dessus de lui, et l'en-tête se compacte pendant la saisie.
        val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
        val compact = isTv && imeVisible
        // Tactile : Retour ferme d'abord le clavier (et rend le focus), le suivant quitte la recherche.
        val focusManager = LocalFocusManager.current
        BackHandler(enabled = !isTv && imeVisible) {
            keyboard?.hide()
            focusManager.clearFocus()
        }
        Column(Modifier.fillMaxSize().imePadding()) {
            Column(
                Modifier.padding(start = screenPadStart(), top = if (!isTv) 12.dp else if (compact) 10.dp else 32.dp, end = screenPadEnd())
            ) {
                if (!compact) {
                    Text("Recherche", fontSize = if (isTv) 26.sp else 22.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(if (isTv) 14.dp else 10.dp))
                }
                Row(
                    Modifier.onPreviewKeyEvent { e ->
                        // ▼ depuis le champ (ou le micro) : premier résultat, quelle que soit la position du focus.
                        if (isTv && total > 0 && e.type == KeyEventType.KeyDown && e.key == Key.DirectionDown) {
                            keyboard?.hide()
                            focusFirstResult()
                            true
                        } else false
                    },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TvTextField(
                        query, { query = it }, "",
                        // Téléphone : champ sur toute la largeur, clavier ouvert d'emblée.
                        modifier = if (isTv) Modifier.width(560.dp) else Modifier.weight(1f).semantics(mergeDescendants = true) {
                            contentDescription = "Rechercher un film, une série ou une chaîne"
                        },
                        imeAction = ImeAction.Search,
                        placeholder = "Films, séries, chaînes…",
                        leadingIcon = FluxIcons.Search,
                        keyboardActions = KeyboardActions(onSearch = submit, onDone = submit),
                        // Seulement si rien n'est déjà affiché : revenir sur l'onglet ne rouvre pas le clavier.
                        autoFocus = autoFocusOnEntry,
                    )
                    Spacer(Modifier.width(if (isTv) 14.dp else 8.dp))
                    if (isTv) {
                        ActionButton(if (listening) "Je t'écoute…" else "Recherche vocale", { startVoice() }, icon = MicIcon)
                    } else {
                        // Bouton micro rond (48 dp), accentué pendant l'écoute.
                        FocusSurface(
                            { startVoice() }, Modifier.size(48.dp), shape = CircleShape,
                            containerColor = if (listening) Palette.Accent else Color.White.copy(alpha = 0.10f),
                        ) {
                            FluxIcon(MicIcon, Modifier.align(Alignment.Center), size = 22.dp)
                        }
                    }
                }
                if (!isTv && listening) {
                    Spacer(Modifier.height(6.dp))
                    Text("Je t'écoute…", fontSize = 12.sp, color = Palette.Accent)
                }
                if (!compact) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        when {
                            active && total > 0 -> "$total résultat${if (total > 1) "s" else ""} pour « $debounced »"
                            else -> "Tape un titre de film, de série ou un nom de chaîne"
                        },
                        fontSize = 12.sp, color = Palette.Faint,
                    )
                }
            }
            val loading = active && results.words != words
            when {
                !active -> SearchEmptyState(
                    FluxIcons.Search, "Que veux-tu regarder ?",
                    "Commence à taper : les résultats s'affichent au fur et à mesure.",
                )
                total == 0 && loading -> SearchEmptyState(FluxIcons.Search, "Recherche en cours…", "")
                total == 0 -> SearchEmptyState(
                    FluxIcons.Info, "Aucun résultat pour « $debounced »",
                    "Vérifie l'orthographe ou essaie un autre mot-clé.",
                )
                // Tablette (tactile, largeur >= 600 dp) : une grille par type, avec titres de section.
                !isTv && !isCompactWidth -> {
                    val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
                    ScrollToTopEffect { gridState.animateScrollToItem(0) }
                    androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                        columns = androidx.compose.foundation.lazy.grid.GridCells.Adaptive(140.dp),
                        state = gridState,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        contentPadding = PaddingValues(
                            start = screenPadStart(), end = screenPadEnd(), top = 16.dp, bottom = 40.dp,
                        ),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        fun header(id: String, title: String, n: Int) = item(
                            key = "h-$id", span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) },
                        ) {
                            Text(
                                "$title · $n", fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                        if (movies.isNotEmpty()) {
                            header("movies", "Films", movies.size)
                            items(movies.size, key = { "m-${movies[it].id}" }) { i ->
                                val m = movies[i]
                                GridPosterCard(
                                    m.name, m.poster, m.quality, onClick = { onOpenMovie(m) },
                                    onLongClick = { PlayerSession.start(ctx, listOf(Playables.movie(m)), 0) },
                                )
                            }
                        }
                        if (series.isNotEmpty()) {
                            header("series", "Séries", series.size)
                            items(series.size, key = { "s-${series[it].id}" }) { i ->
                                val s = series[i]
                                GridPosterCard(s.name, s.poster, s.quality, onClick = { onOpenSeries(s) })
                            }
                        }
                        if (channels.isNotEmpty()) {
                            header("channels", "Chaînes", channels.size)
                            items(channels.size, key = { "c-${channels[it].id}" }) { i ->
                                val ch = channels[i]
                                ChannelTile(
                                    ch, favorite = Favorites.liveKey(ch.id) in favs,
                                    onClick = { PlayerSession.start(ctx, Playables.channels(channels), i) },
                                    onLongClick = { toggleChannelFavorite(ctx, ch.id) },
                                    onFocus = {},
                                )
                            }
                        }
                    }
                }
                else -> LazyColumn(
                    Modifier.fillMaxWidth().weight(1f),
                    contentPadding = PaddingValues(top = if (compact) 6.dp else 22.dp, bottom = 40.dp),
                    verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 20.dp),
                ) {
                    shelf("Films", movies, key = { it.id }) { m, i ->
                        PosterCard(
                            displayTitle(m.name), m.poster, m.quality,
                            modifier = if (i == 0) Modifier.focusRequester(firstResult) else Modifier,
                            onClick = { onOpenMovie(m) },
                            onLongClick = { PlayerSession.start(ctx, listOf(Playables.movie(m)), 0) },
                            subtitle = (VodMeta.get(VodMeta.movieKey(m.id))?.year ?: DisplayTitle.year(m.name))?.toString(),
                        )
                    }
                    shelf("Séries", series, key = { it.id }) { s, i ->
                        PosterCard(
                            displayTitle(s.name), s.poster, s.quality,
                            modifier = if (i == 0 && movies.isEmpty()) Modifier.focusRequester(firstResult) else Modifier,
                            onClick = { onOpenSeries(s) },
                            subtitle = (VodMeta.get(VodMeta.seriesKey(s.id))?.year ?: DisplayTitle.year(s.name))?.toString(),
                        )
                    }
                    shelf("Chaînes", channels, key = { it.id }) { ch, i ->
                        ChannelCard(
                            ch.name, ch.logo, ch.quality,
                            modifier = if (i == 0 && movies.isEmpty() && series.isEmpty()) Modifier.focusRequester(firstResult) else Modifier,
                            onClick = { PlayerSession.start(ctx, Playables.channels(channels), i) },
                            channelId = ch.id,
                            onLongClick = { toggleChannelFavorite(ctx, ch.id) },
                            favorite = Favorites.liveKey(ch.id) in favs,
                        )
                    }
                }
            }
        }
    }
}

/** Résultats classés pour les mots [words] (un résultat d'une autre recherche n'est jamais présenté comme le courant). */
private class SearchResults(
    val words: List<String>,
    val channels: List<Channel>,
    val movies: List<Movie>,
    val series: List<Series>,
) {
    companion object { val EMPTY = SearchResults(emptyList(), emptyList(), emptyList(), emptyList()) }
}

private val NON_ALNUM = Regex("[^a-z0-9]+")

/**
 * Titres qui COMMENCENT par la recherche (après nettoyage par [DisplayTitle], sans accents ni casse), puis ceux dont
 * un mot commence ainsi, puis ceux qui la contiennent seulement ; à égalité, le plus récemment ajouté d'abord.
 */
private fun <T> rank(
    items: List<T>,
    words: List<String>,
    rawKey: (T) -> String,
    cleanName: (T) -> String,
    added: (T) -> Long,
    limit: Int,
): List<T> {
    val q = words.joinToString(" ")
    class Hit(val item: T, val tier: Int, val added: Long)
    val hits = ArrayList<Hit>()
    for (it in items) {
        val key = rawKey(it)
        if (!words.all { w -> key.contains(w) }) continue
        val title = searchKey(cleanName(it))
        val tier = when {
            title.startsWith(q) -> 0
            title.split(NON_ALNUM).let { tw -> words.all { w -> tw.any { t -> t.startsWith(w) } } } -> 1
            else -> 2
        }
        hits += Hit(it, tier, added(it))
    }
    hits.sortWith(compareBy<Hit> { it.tier }.thenByDescending { it.added })
    return hits.take(limit).map { it.item }
}

private fun rankResults(words: List<String>, catalog: Catalog): SearchResults = SearchResults(
    words,
    channels = rank(catalog.channels, words, { it.key }, { it.name }, { 0L }, 60),
    movies = rank(catalog.movies, words, { it.key }, { DisplayTitle.of(it.name) }, { it.added }, 80),
    series = rank(catalog.series, words, { it.key }, { DisplayTitle.of(it.name) }, { it.added }, 80),
)

@Composable
private fun SearchEmptyState(icon: ImageVector, title: String, message: String) {
    Box(Modifier.fillMaxSize().padding(bottom = 40.dp, end = screenPadEnd()), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(84.dp).background(
                    Brush.linearGradient(listOf(Palette.Accent.copy(alpha = 0.28f), Palette.Accent2.copy(alpha = 0.22f))),
                    CircleShape,
                ),
                contentAlignment = Alignment.Center,
            ) { FluxIcon(icon, size = 38.dp) }
            Spacer(Modifier.height(18.dp))
            Text(title, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            Spacer(Modifier.height(6.dp))
            Row {
                Text(message, fontSize = 13.sp, color = Palette.Dim, textAlign = TextAlign.Center)
            }
        }
    }
}
