package com.fluxfr.tv

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import com.fluxfr.tv.ui.LocalIsTv
import androidx.lifecycle.lifecycleScope
import com.fluxfr.tv.data.CatalogRepository
import com.fluxfr.tv.data.Profiles
import com.fluxfr.tv.player.PlayerSession
import com.fluxfr.tv.tvhome.CatalogAccess
import com.fluxfr.tv.tvhome.WatchNextSync
import com.fluxfr.tv.ui.FluxApp
import com.fluxfr.tv.ui.FluxTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class MainActivity : ComponentActivity() {
    private var playJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Profiles.load(this)
        // Ouverture de l'appli = haut de l'accueil (lanceur, retour après plus de 5 min en arrière-plan).
        AppEvents.install(application)
        // Intent d'origine traité une seule fois (pas après une recréation de l'activité).
        if (savedInstanceState == null) handleIntent(intent)
        // Appli relancée après la fin du processus (état restauré) depuis le lanceur : on repart du haut de l'accueil.
        else if (AppEvents.isLauncherIntent(intent)) AppEvents.requestHomeTop()
        // Ménage unique des fichiers et réglages d'anciennes versions (hors thread principal).
        com.fluxfr.tv.data.LegacyCleanup.runOnce(this)
        // Version publique : mise à jour automatique depuis Internet (Google Drive), installée hors lecture.
        com.fluxfr.tv.data.RemoteUpdate.start(this)
        // Même APK sur TV (télécommande) et sur téléphone / tablette (tactile) : type d'appareil calculé une fois.
        val isTv = com.fluxfr.tv.ui.FormFactor.isTv(this)
        if (!isTv) {
            // Téléphone : affichage bord à bord (imposé par Android 15), barres système sombres et transparentes ;
            // le clavier redimensionne la fenêtre (marges d'encart « safeDrawing » appliquées à la racine).
            enableEdgeToEdge(
                statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
                navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            )
            @Suppress("DEPRECATION")
            window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        setContent {
            CompositionLocalProvider(LocalIsTv provides isTv) {
                FluxTheme {
                    if (isTv) FluxApp()
                    // Barres système à la racine ; le clavier seulement sous le contenu (rail et fond jusqu'en bas).
                    else com.fluxfr.tv.ui.TouchRoot { FluxApp() }
                }
            }
        }
        // Rangée « Continuer à regarder » de Google TV : remise à jour au démarrage.
        WatchNextSync.requestSync(this, delayMs = 3_000)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Relance depuis le lanceur Android TV / Google TV (activité singleTask déjà ouverte) : haut de l'accueil.
        if (AppEvents.isLauncherIntent(intent)) AppEvents.requestHomeTop()
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        when (val parsed = DeepLinks.parse(intent)) {
            is DeepLinks.Parsed.Ui -> DeepLinks.pending.value = parsed.target
            is DeepLinks.Parsed.Playback -> play(parsed.play)
            null -> Unit
        }
    }

    /** Reprise directe dans le lecteur (programme Watch Next, prisme://play?key=…). */
    private fun play(link: DeepLinks.Play) {
        val target = link.profile
        val targetProfile = target?.let { Profiles.get(it) }
        if (targetProfile != null && target != Profiles.current.value) {
            // Un profil Enfants ne se quitte pas par un lien pour un profil adulte quand un code parental existe.
            val active = Profiles.active.value
            if (Profiles.needsParentalPin(active, targetProfile, Profiles.list.value)) return
            Profiles.set(this, target)
        }
        playJob?.cancel()
        playJob = lifecycleScope.launch {
            // Catalogue en mémoire, sinon le cache disque ; à défaut, attend le chargement par l'interface.
            val catalog = CatalogAccess.peek()
                ?: withContext(Dispatchers.IO) { CatalogAccess.get(this@MainActivity) }
                ?: withTimeoutOrNull(60_000) {
                    (CatalogRepository.state.first { it is CatalogRepository.State.Ready } as CatalogRepository.State.Ready).catalog
                }
            // Profil Enfants : seuls les programmes du catalogue jeunesse peuvent être lus par un lien.
            if (Profiles.active.value.kids &&
                !withContext(Dispatchers.IO) { CatalogAccess.kidsAllows(this@MainActivity, link.key, catalog) }
            ) {
                Toast.makeText(this@MainActivity, "Ce programme n'est pas disponible dans ce profil.", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val resolved = withContext(Dispatchers.IO) { CatalogAccess.resolvePlay(this@MainActivity, link.key, catalog) }
            if (resolved == null) {
                Toast.makeText(this@MainActivity, "Ce programme n'est plus disponible.", Toast.LENGTH_SHORT).show()
                return@launch
            }
            PlayerSession.start(this@MainActivity, resolved.first, resolved.second)
        }
    }
}
