package network.nervos.watchvisualiser

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.*
import network.nervos.watchvisualiser.data.AppPreferences

/**
 * Settings configuration screen for Pixel Watch 3.
 * Configures CKB RPC endpoints, haptic feedback, and screen sleep modes.
 */
class SettingsActivity : ComponentActivity() {

    private lateinit var preferences: AppPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferences = AppPreferences(this)

        setContent {
            var rpcUrl by remember { mutableStateOf(preferences.ckbRpcUrl) }
            var haptics by remember { mutableStateOf(preferences.hapticsEnabled) }
            var keepScreenOn by remember { mutableStateOf(preferences.keepScreenOn) }

            SettingsScreen(
                currentRpc = rpcUrl,
                hapticsEnabled = haptics,
                keepScreenOn = keepScreenOn,
                onToggleHaptics = {
                    haptics = !haptics
                    preferences.hapticsEnabled = haptics
                },
                onToggleKeepScreenOn = {
                    keepScreenOn = !keepScreenOn
                    preferences.keepScreenOn = keepScreenOn
                },
                onSelectRpcPreset = { newUrl ->
                    rpcUrl = newUrl
                    preferences.ckbRpcUrl = newUrl
                    Toast.makeText(this, "RPC Endpoint Updated", Toast.LENGTH_SHORT).show()
                },
                onBack = { finish() }
            )
        }
    }
}

@Composable
fun SettingsScreen(
    currentRpc: String,
    hapticsEnabled: Boolean,
    keepScreenOn: Boolean,
    onToggleHaptics: () -> Unit,
    onToggleKeepScreenOn: () -> Unit,
    onSelectRpcPreset: (String) -> Unit,
    onBack: () -> Unit
) {
    val listState = rememberScalingLazyListState()

    Scaffold(
        timeText = { TimeText() },
        vignette = { Vignette(vignettePosition = VignettePosition.TopAndBottom) },
        positionIndicator = { PositionIndicator(scalingLazyListState = listState) }
    ) {
        ScalingLazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF000000)),
            state = listState,
            contentPadding = PaddingValues(top = 26.dp, bottom = 28.dp, start = 12.dp, end = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            item {
                Text(
                    text = "SETTINGS",
                    color = Color(0xFF00F0FF),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            // Haptics Toggle
            item {
                ToggleChip(
                    modifier = Modifier.fillMaxWidth(),
                    checked = hapticsEnabled,
                    onCheckedChange = { onToggleHaptics() },
                    label = { Text("Watch Haptics", fontSize = 12.sp) },
                    toggleControl = {
                        Switch(
                            checked = hapticsEnabled,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color(0xFF00FFAA),
                                checkedTrackColor = Color(0xFF003822)
                            )
                        )
                    }
                )
            }

            // Keep Screen Awake Toggle
            item {
                Spacer(modifier = Modifier.height(4.dp))
                ToggleChip(
                    modifier = Modifier.fillMaxWidth(),
                    checked = keepScreenOn,
                    onCheckedChange = { onToggleKeepScreenOn() },
                    label = { Text("Keep Screen On", fontSize = 12.sp) },
                    toggleControl = {
                        Switch(
                            checked = keepScreenOn,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color(0xFF00FFAA),
                                checkedTrackColor = Color(0xFF003822)
                            )
                        )
                    }
                )
            }

            // RPC Section
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "RPC ENDPOINT",
                    color = Color(0xFF7FA5B5),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }

            // Preset: Mainnet Primary
            item {
                Spacer(modifier = Modifier.height(4.dp))
                Chip(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { onSelectRpcPreset("https://mainnet.ckb.dev/") },
                    colors = ChipDefaults.primaryChipColors(
                        backgroundColor = if (currentRpc == "https://mainnet.ckb.dev/") Color(0xFF00384D) else Color(0xFF132030)
                    ),
                    label = { Text("CKB Mainnet (Primary)", fontSize = 11.sp) }
                )
            }

            // Preset: Mainnet Fallback
            item {
                Spacer(modifier = Modifier.height(4.dp))
                Chip(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { onSelectRpcPreset("https://mainnet.ckbapp.dev/") },
                    colors = ChipDefaults.primaryChipColors(
                        backgroundColor = if (currentRpc == "https://mainnet.ckbapp.dev/") Color(0xFF00384D) else Color(0xFF132030)
                    ),
                    label = { Text("CKB Mainnet (App Dev)", fontSize = 11.sp) }
                )
            }

            // Back Button
            item {
                Spacer(modifier = Modifier.height(8.dp))
                CompactChip(
                    onClick = onBack,
                    label = { Text("DONE", fontSize = 10.sp, fontWeight = FontWeight.Bold) },
                    colors = ChipDefaults.secondaryChipColors()
                )
            }
        }
    }
}
