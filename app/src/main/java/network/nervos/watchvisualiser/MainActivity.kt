package network.nervos.watchvisualiser

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.*
import network.nervos.watchvisualiser.data.VisualiserRegistry
import network.nervos.watchvisualiser.model.VisualiserInfo

/**
 * Main launcher activity for Pixel Watch 3 offering a menu-driven selection
 * of Nervos Network blockchain visualizers with rotary crown navigation.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            WearApp(
                visualisers = VisualiserRegistry.getAll(),
                onSelectVisualiser = { info ->
                    if (info.isAvailable) {
                        val intent = Intent(this, VisualiserActivity::class.java).apply {
                            putExtra(VisualiserActivity.EXTRA_VISUALISER_ID, info.id)
                            putExtra(VisualiserActivity.EXTRA_ASSET_PATH, info.assetPath)
                            putExtra(VisualiserActivity.EXTRA_VISUALISER_TITLE, info.title)
                        }
                        startActivity(intent)
                    } else {
                        Toast.makeText(this, "${info.title} is coming soon!", Toast.LENGTH_SHORT).show()
                    }
                },
                onOpenSettings = {
                    startActivity(Intent(this, SettingsActivity::class.java))
                }
            )
        }
    }
}

@Composable
fun WearApp(
    visualisers: List<VisualiserInfo>,
    onSelectVisualiser: (VisualiserInfo) -> Unit,
    onOpenSettings: () -> Unit
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
            contentPadding = PaddingValues(top = 28.dp, bottom = 32.dp, start = 14.dp, end = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header
            item {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(bottom = 10.dp)
                ) {
                    Text(
                        text = "NERVOS",
                        color = Color(0xFF00F0FF),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 2.sp
                    )
                    Text(
                        text = "BLOCK VISUALISERS",
                        color = Color(0xFFE2F4F8),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.ExtraBold,
                        textAlign = TextAlign.Center
                    )
                }
            }

            // Visualiser Items
            items(visualisers) { item ->
                VisualiserCard(
                    item = item,
                    onClick = { onSelectVisualiser(item) }
                )
                Spacer(modifier = Modifier.height(6.dp))
            }

            // Settings Button
            item {
                Spacer(modifier = Modifier.height(4.dp))
                Chip(
                    modifier = Modifier.fillMaxWidth(0.9f),
                    onClick = onOpenSettings,
                    colors = ChipDefaults.secondaryChipColors(
                        backgroundColor = Color(0xFF0D1B2A),
                        contentColor = Color(0xFF7FA5B5)
                    ),
                    icon = {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_settings),
                            contentDescription = "Settings",
                            modifier = Modifier.size(18.dp),
                            tint = Color(0xFF00F0FF)
                        )
                    },
                    label = {
                        Text(
                            text = "Node Settings",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                )
            }
        }
    }
}

@Composable
fun VisualiserCard(
    item: VisualiserInfo,
    onClick: () -> Unit
) {
    AppCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        appName = {
            Text(
                text = item.badge,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = if (item.isAvailable) Color(0xFF00FFAA) else Color(0xFFFFB830)
            )
        },
        time = {},
        title = {
            Text(
                text = item.title,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        appImage = {
            Icon(
                painter = painterResource(id = item.iconRes),
                contentDescription = item.title,
                modifier = Modifier.size(20.dp),
                tint = if (item.isAvailable) Color(0xFF00F0FF) else Color(0xFF7FA5B5)
            )
        }
    ) {
        Text(
            text = item.subtitle,
            fontSize = 10.sp,
            color = Color(0xFF94B5C6),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}
