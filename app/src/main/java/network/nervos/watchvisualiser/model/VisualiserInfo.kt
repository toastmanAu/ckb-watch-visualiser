package network.nervos.watchvisualiser.model

import androidx.annotation.DrawableRes

/**
 * Model representing a Nervos Network visualizer hosted on Wear OS.
 */
data class VisualiserInfo(
    val id: String,
    val title: String,
    val subtitle: String,
    val badge: String,
    @DrawableRes val iconRes: Int,
    val assetPath: String,
    val isAvailable: Boolean = true
)
