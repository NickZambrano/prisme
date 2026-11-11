package com.fluxfr.tv

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import kotlinx.coroutines.flow.MutableSharedFlow
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Événements de l'appli vers l'interface. [goHomeTop] : l'utilisateur « ouvre » l'appli (lanceur Android TV /
 * Google TV, ou retour au premier plan après plus de [BACKGROUND_RESET_MS] en arrière-plan) → Accueil, en haut,
 * fiche / saga fermées, focus sur le bouton de l'en-tête. Jamais au retour du lecteur (navigation interne).
 */
object AppEvents {
    const val BACKGROUND_RESET_MS = 5L * 60 * 1000

    val goHomeTop = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Demande émise alors que l'interface ne l'écoutait pas encore (traitée à l'abonnement). */
    private val pending = AtomicBoolean(false)

    fun requestHomeTop() {
        pending.set(true)
        goHomeTop.tryEmit(Unit)
    }

    /** true si une demande attendait (et la marque comme traitée). */
    fun consumePending(): Boolean = pending.getAndSet(false)

    fun isLauncherIntent(intent: Intent?): Boolean =
        intent != null && intent.action == Intent.ACTION_MAIN &&
            (intent.hasCategory(Intent.CATEGORY_LEANBACK_LAUNCHER) || intent.hasCategory(Intent.CATEGORY_LAUNCHER))

    // ---- Premier plan / arrière-plan de l'appli entière (toutes activités confondues, lecteur compris).
    private var installed = false
    /** Activités démarrées (identités) : une activité démarrée avant [install] est ignorée à son arrêt. */
    private val started = HashSet<Int>()
    private var backgroundAt = 0L

    /**
     * Suit les activités démarrées : quand l'appli revient au premier plan sur [MainActivity] après plus de
     * 5 min en arrière-plan, retour en haut de l'accueil. Passer de l'accueil au lecteur (et revenir) ne
     * met jamais l'appli en arrière-plan : aucun effet.
     */
    fun install(app: Application) {
        if (installed) return
        installed = true
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                val wasBackground = started.isEmpty()
                started += System.identityHashCode(activity)
                if (wasBackground && backgroundAt > 0 && activity is MainActivity &&
                    System.currentTimeMillis() - backgroundAt > BACKGROUND_RESET_MS
                ) requestHomeTop()
                if (wasBackground) backgroundAt = 0L
            }

            override fun onActivityStopped(activity: Activity) {
                if (!started.remove(System.identityHashCode(activity))) return
                if (started.isEmpty() && !activity.isChangingConfigurations) backgroundAt = System.currentTimeMillis()
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }
}
