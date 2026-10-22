package network.nervos.watchvisualiser.bridge

import android.app.Activity
import android.content.Context
import android.os.Build
import android.os.CombinedVibration
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.webkit.JavascriptInterface
import network.nervos.watchvisualiser.data.AppPreferences

/**
 * JavaScript Interface bridge connecting Three.js visualizers with native Wear OS (Pixel Watch 3) capabilities.
 */
class WatchBridge(
    private val activity: Activity,
    private val preferences: AppPreferences
) {
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vibratorManager = activity.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        vibratorManager?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        activity.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    @JavascriptInterface
    fun getRpcUrl(): String {
        return preferences.ckbRpcUrl
    }

    @JavascriptInterface
    fun isWatchRound(): Boolean {
        return activity.resources.configuration.isScreenRound
    }

    @JavascriptInterface
    fun getWatchMetrics(): String {
        val displayMetrics = activity.resources.displayMetrics
        return "{\"width\":${displayMetrics.widthPixels},\"height\":${displayMetrics.heightPixels},\"density\":${displayMetrics.density}}"
    }

    @JavascriptInterface
    fun vibrate(type: String) {
        if (!preferences.hapticsEnabled || vibrator == null || !vibrator.hasVibrator()) return

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val effect = when (type.lowercase()) {
                    "heavy_click" -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)
                    "double_click" -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK)
                    "tick" -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
                    else -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
                }
                vibrator.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                val millis = when (type.lowercase()) {
                    "heavy_click" -> 50L
                    "tick" -> 15L
                    else -> 25L
                }
                @Suppress("DEPRECATION")
                vibrator.vibrate(millis)
            }
        } catch (e: Exception) {
            // Ignore vibration errors if not permitted
        }
    }

    @JavascriptInterface
    fun closeVisualiser() {
        activity.runOnUiThread {
            activity.finish()
        }
    }
}
