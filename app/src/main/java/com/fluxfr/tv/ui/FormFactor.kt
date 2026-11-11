package com.fluxfr.tv.ui

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext

/** Type d'appareil : la même appli tourne sur TV (télécommande) et sur téléphone / tablette (tactile). */
object FormFactor {
    /** TV : mode TV d'Android, ou appareil sans écran tactile. */
    fun isTv(ctx: Context): Boolean {
        val ui = ctx.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        if (ui?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) return true
        return !ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
    }
}

/** true sur TV (navigation à la télécommande), false sur téléphone / tablette. */
@Composable
fun rememberIsTv(): Boolean {
    val ctx = LocalContext.current
    return remember { FormFactor.isTv(ctx) }
}

/** Largeur d'écran < 600 dp (téléphone en portrait) : mise en page compacte. */
@Composable
fun rememberIsCompact(): Boolean = LocalConfiguration.current.screenWidthDp < 600
