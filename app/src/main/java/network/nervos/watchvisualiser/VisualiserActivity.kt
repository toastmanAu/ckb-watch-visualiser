package network.nervos.watchvisualiser

import android.content.Context
import android.content.Intent
import android.graphics.*
import android.os.Bundle
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import network.nervos.watchvisualiser.data.AppPreferences
import network.nervos.watchvisualiser.data.CkbRpcClient
import network.nervos.watchvisualiser.ui.BonsaiVisualiserView
import network.nervos.watchvisualiser.ui.CuckooVisualiserView
import network.nervos.watchvisualiser.ui.LabVisualiserView
import network.nervos.watchvisualiser.ui.LegoTowerVisualiserView
import network.nervos.watchvisualiser.ui.PirateShipVisualiserView
import network.nervos.watchvisualiser.ui.SharkRaftVisualiserView
import kotlin.math.abs
import kotlin.math.max

/**
 * Native Wear OS Activity housing the visualizer selected by the user.
 * Seamlessly routes to LabVisualiserView, BonsaiVisualiserView, CuckooVisualiserView,
 * LegoTowerVisualiserView, SharkRaftVisualiserView, or PirateShipVisualiserView with:
 * - Full Pixel Watch 3 Digital Crown support
 * - Always-accessible floating `‹` Back-to-Menu pill button at 9 o'clock
 * - Standard Wear OS left-edge swipe-to-dismiss gesture back to MainActivity
 */
class VisualiserActivity : ComponentActivity() {

    private var labView: LabVisualiserView? = null
    private var bonsaiView: BonsaiVisualiserView? = null
    private var cuckooView: CuckooVisualiserView? = null
    private var bricksView: LegoTowerVisualiserView? = null
    private var sharksView: SharkRaftVisualiserView? = null
    private var pirateView: PirateShipVisualiserView? = null
    private lateinit var preferences: AppPreferences
    private lateinit var rpcClient: CkbRpcClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        preferences = AppPreferences(this)

        if (preferences.keepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        val visId = intent.getStringExtra(EXTRA_VISUALISER_ID) ?: "ckb_cell_lab"

        val activeVisView: View = when (visId) {
            "ckb_bonsai" -> BonsaiVisualiserView(this).also { bonsaiView = it }
            "ckb_cuckoo" -> CuckooVisualiserView(this).also { cuckooView = it }
            "ckb_bricks" -> LegoTowerVisualiserView(this).also { bricksView = it }
            "ckb_sharks" -> SharkRaftVisualiserView(this).also { sharksView = it }
            "ckb_pirate" -> PirateShipVisualiserView(this).also { pirateView = it }
            else -> LabVisualiserView(this).also { labView = it }
        }

        // Wrap visualizer in a navigation container with left-edge swipe-to-back + floating Back button
        val container = VisualiserNavigationContainer(
            context = this,
            contentView = activeVisView,
            onNavigateBack = { navigateBackToMenu() }
        )
        setContentView(container)

        // Ensure system Back always returns to the visualizer selection menu
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                navigateBackToMenu()
            }
        })

        // Connect to live CKB Blockchain via native RPC client
        rpcClient = CkbRpcClient(preferences.ckbRpcUrl, lifecycleScope).apply {
            onBaselineLoaded = { baseline ->
                cuckooView?.onBaselineLoaded(baseline)
                bricksView?.onBaselineLoaded(baseline)
                sharksView?.onBaselineLoaded(baseline)
                pirateView?.onBaselineLoaded(baseline)
            }
            onBlockDetected = { blockData ->
                labView?.onBlockArrived(blockData)
                bonsaiView?.onBlockArrived(blockData)
                cuckooView?.onBlockArrived(blockData)
                bricksView?.onBlockArrived(blockData)
                sharksView?.onBlockArrived(blockData)
                pirateView?.onBlockArrived(blockData)
            }
            onStatusChanged = { status ->
                cuckooView?.onStatusUpdated(status)
                bricksView?.onStatusUpdated(status)
                sharksView?.onStatusUpdated(status)
                pirateView?.onStatusUpdated(status)
            }
            start()
        }
    }

    /**
     * Navigates cleanly back to the MainActivity visualizer selection menu,
     * even if VisualiserActivity was launched directly as task root.
     */
    private fun navigateBackToMenu() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(intent)
        finish()
    }

    /**
     * Intercept Pixel Watch 3 physical digital crown / rotary encoder
     * to orbit the camera smoothly around the 3D scene.
     */
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_SCROLL &&
            event.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER)
        ) {
            val delta = -event.getAxisValue(MotionEvent.AXIS_SCROLL) * preferences.rotarySensitivity
            labView?.handleRotaryScroll(delta)
            bonsaiView?.handleRotaryScroll(delta)
            cuckooView?.handleRotaryScroll(delta)
            bricksView?.handleRotaryScroll(delta)
            sharksView?.handleRotaryScroll(delta)
            pirateView?.handleRotaryScroll(delta)
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    override fun onDestroy() {
        rpcClient.stop()
        super.onDestroy()
    }

    /**
     * Wrapper container that provides:
     * 1. Left-edge swipe-to-dismiss gesture back to MainActivity
     * 2. Floating 9-o'clock `‹` Back-to-Menu button overlay
     */
    private class VisualiserNavigationContainer(
        context: Context,
        private val contentView: View,
        private val onNavigateBack: () -> Unit
    ) : FrameLayout(context) {

        private var downX = 0f
        private var downY = 0f
        private var swipingFromLeftEdge = false

        init {
            setBackgroundColor(Color.BLACK)
            addView(
                contentView,
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            )

            val density = resources.displayMetrics.density
            val btnWidth = (42 * density).toInt()
            val btnHeight = (52 * density).toInt()
            val leftMargin = (6 * density).toInt()

            val backBtn = MenuBackButtonView(context) {
                performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                onNavigateBack()
            }
            addView(
                backBtn,
                LayoutParams(btnWidth, btnHeight, Gravity.START or Gravity.CENTER_VERTICAL).apply {
                    marginStart = leftMargin
                }
            )
        }

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            val w = width.toFloat().coerceAtLeast(1f)
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.x
                    downY = ev.y
                    swipingFromLeftEdge = false
                    contentView.translationX = 0f
                    contentView.alpha = 1f
                }
                MotionEvent.ACTION_MOVE -> {
                    // If gesture started near the left watch bezel (within 15% of width) and swipes right
                    if (downX <= w * 0.15f) {
                        val dx = ev.x - downX
                        val dy = ev.y - downY
                        if (dx > w * 0.08f && dx > abs(dy) * 1.2f) {
                            swipingFromLeftEdge = true
                            return true
                        }
                    }
                }
            }
            return super.onInterceptTouchEvent(ev)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (!swipingFromLeftEdge) {
                return super.onTouchEvent(event)
            }
            val w = width.toFloat().coerceAtLeast(1f)
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    val dx = max(0f, event.x - downX)
                    contentView.translationX = dx * 0.85f
                    contentView.alpha = (1f - (dx / w) * 0.6f).coerceIn(0.3f, 1f)
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    val dx = event.x - downX
                    if (dx > w * 0.22f) {
                        performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                        onNavigateBack()
                    } else {
                        contentView.animate().translationX(0f).alpha(1f).setDuration(150).start()
                    }
                    swipingFromLeftEdge = false
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    contentView.animate().translationX(0f).alpha(1f).setDuration(150).start()
                    swipingFromLeftEdge = false
                    return true
                }
            }
            return true
        }
    }

    /**
     * Sleek, low-profile floating Back button at the 9 o'clock bezel edge
     * so the user can return to the visualizer selection menu with a single tap.
     */
    private class MenuBackButtonView(
        context: Context,
        private val onClickAction: () -> Unit
    ) : View(context) {

        private var isPressedState = false

        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.6f
            color = Color.argb(125, 56, 217, 255)
        }
        private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.6f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = Color.rgb(56, 217, 255)
        }
        private val chevronPath = Path()
        private val pillRect = RectF()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return

            val pad = 3f
            pillRect.set(pad, pad, w - pad, h - pad)
            val radius = (w - pad * 2f) * 0.5f

            bgPaint.color = if (isPressedState) {
                Color.argb(235, 16, 48, 78)
            } else {
                Color.argb(185, 6, 18, 32)
            }
            canvas.drawRoundRect(pillRect, radius, radius, bgPaint)
            canvas.drawRoundRect(pillRect, radius, radius, borderPaint)

            // Draw crisp left-pointing `<` chevron inside the pill
            val cx = w * 0.52f
            val cy = h * 0.5f
            val cw = w * 0.14f
            val ch = h * 0.15f
            chevronPath.reset()
            chevronPath.moveTo(cx + cw * 0.5f, cy - ch)
            chevronPath.lineTo(cx - cw * 0.6f, cy)
            chevronPath.lineTo(cx + cw * 0.5f, cy + ch)
            canvas.drawPath(chevronPath, iconPaint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    isPressedState = true
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    val wasPressed = isPressedState
                    isPressedState = false
                    invalidate()
                    if (wasPressed &&
                        event.x in 0f..width.toFloat() &&
                        event.y in 0f..height.toFloat()
                    ) {
                        onClickAction()
                    }
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    isPressedState = false
                    invalidate()
                    return true
                }
            }
            return super.onTouchEvent(event)
        }
    }

    companion object {
        const val EXTRA_VISUALISER_ID = "extra_visualiser_id"
        const val EXTRA_ASSET_PATH = "extra_asset_path"
        const val EXTRA_VISUALISER_TITLE = "extra_asset_title"
    }
}
