package network.nervos.watchvisualiser.data

import android.content.Context
import android.content.SharedPreferences

class AppPreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("nervos_watch_prefs", Context.MODE_PRIVATE)

    var ckbRpcUrl: String
        get() = prefs.getString(KEY_RPC_URL, DEFAULT_RPC_URL) ?: DEFAULT_RPC_URL
        set(value) = prefs.edit().putString(KEY_RPC_URL, value).apply()

    var hapticsEnabled: Boolean
        get() = prefs.getBoolean(KEY_HAPTICS, true)
        set(value) = prefs.edit().putBoolean(KEY_HAPTICS, value).apply()

    var keepScreenOn: Boolean
        get() = prefs.getBoolean(KEY_KEEP_SCREEN_ON, true)
        set(value) = prefs.edit().putBoolean(KEY_KEEP_SCREEN_ON, value).apply()

    var rotarySensitivity: Float
        get() = prefs.getFloat(KEY_ROTARY_SENSITIVITY, 1.0f)
        set(value) = prefs.edit().putFloat(KEY_ROTARY_SENSITIVITY, value).apply()

    companion object {
        const val DEFAULT_RPC_URL = "https://mainnet.ckb.dev/"
        private const val KEY_RPC_URL = "ckb_rpc_url"
        private const val KEY_HAPTICS = "haptics_enabled"
        private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        private const val KEY_ROTARY_SENSITIVITY = "rotary_sensitivity"
    }
}
