package network.nervos.watchvisualiser.ui

import android.content.Context
import android.graphics.*
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import network.nervos.watchvisualiser.data.CkbBlockData
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import kotlin.math.*
import kotlin.random.Random

/**
 * Native 60 FPS Wear OS Visualizer: CKB Illuminated Ferris Wheel (`ckb_ferris`).
 *
 * Metaphor:
 * - Ferris wheel rotating = Nervos Network progressing
 * - New CKB block = Ferris wheel arriving at the station:
 *   1. Transitions decorative bulb lighting to a new curated nighttime palette + radial light pulse
 *   2. Smoothly decelerates the wheel (`DECELERATING`)
 *   3. Brings the wheel to a short station stop (`STOPPED`) with upright settling gondolas
 *   4. Displays a compact non-obscuring new-block notification (total tx count including cellbase)
 *   5. Smoothly accelerates back to normal operating speed (`ACCELERATING` -> `RUNNING`)
 */
class FerrisWheelVisualiserView(context: Context) : View(context) {

    private enum class WheelState {
        RUNNING,
        DECELERATING,
        STOPPED,
        ACCELERATING
    }

    private data class Palette(
        val name: String,
        val primary: Int,
        val secondary: Int
    )

    private val palettes = arrayOf(
        Palette("CYAN + ELECTRIC BLUE", Color.rgb(0, 242, 254), Color.rgb(0, 102, 255)),
        Palette("VIOLET + MAGENTA",     Color.rgb(183, 33, 255), Color.rgb(255, 8, 68)),
        Palette("AMBER + GOLD",         Color.rgb(255, 159, 67), Color.rgb(255, 209, 102)),
        Palette("EMERALD + AQUA",       Color.rgb(0, 245, 160),  Color.rgb(0, 217, 245)),
        Palette("PINK + PURPLE",        Color.rgb(255, 94, 156), Color.rgb(140, 66, 255)),
        Palette("RED + WARM GOLD",      Color.rgb(255, 56, 92),  Color.rgb(255, 190, 61)),
        Palette("BLUE + COOL WHITE",    Color.rgb(56, 182, 255), Color.rgb(232, 247, 255)),
        Palette("TEAL + VIOLET",        Color.rgb(0, 225, 217),  Color.rgb(155, 93, 229))
    )

    // --- Chain & Deduplication State ---
    private var hasHydratedBaseline = false
    private var statusText = "CONNECTING"
    private var latestBlockNumber = 0L
    private var latestBlockHash = "—"
    private var latestTxCount = 1 // Includes cellbase transaction
    private var latestTimestampMs = System.currentTimeMillis()
    private var latestEpochNumber = 0L
    private var latestEpochIndex = 0L
    private var latestEpochLength = 1800L

    private val seenBlockHashes = LinkedHashSet<String>()
    private val blockEventQueue = ArrayDeque<CkbBlockData>()

    // --- Ferris Wheel State Machine ---
    private val normalAngularVelocity = 0.28f // rad/s
    private val decelDurationSec = 1.60f
    private val stopHoldDurationSec = 2.35f
    private val accelDurationSec = 1.75f

    private var wheelState = WheelState.RUNNING
    private var wheelRotation = 0f
    private var currentAngularVelocity = normalAngularVelocity
    private var prevAngularVelocity = normalAngularVelocity
    private var phaseElapsed = 0f
    private var decelStartVelocity = normalAngularVelocity
    private var activeBlockEvent: CkbBlockData? = null

    // --- Lighting Palette & Block Wave Pulse ---
    private var currentPaletteIdx = 0
    private var fromPrimary = palettes[0].primary
    private var fromSecondary = palettes[0].secondary
    private var toPrimary = palettes[0].primary
    private var toSecondary = palettes[0].secondary
    private var activePrimary = palettes[0].primary
    private var activeSecondary = palettes[0].secondary
    private var paletteTransition = 1.0f
    private var pulseAge = 99f
    private var pulseActive = false
    private var toastAlpha = 0f

    // --- 3D Geometry Constants ---
    private val gondolaCount = 16
    private val spokeCount = 16
    private val wheelRadius = 1.62f
    private val innerRimRadius = 1.06f
    private val hubRadius = 0.25f
    private val wheelDepth = 0.38f
    private val wheelCenterY = 0.12f
    private val groundY = -1.85f

    private val gondolaSwingAngle = FloatArray(gondolaCount)
    private val gondolaSwingVel = FloatArray(gondolaCount)

    // Starfield
    private val starX = FloatArray(55) { (Random.nextFloat() - 0.5f) * 8.5f }
    private val starY = FloatArray(55) { -0.4f + Random.nextFloat() * 3.2f }
    private val starZ = FloatArray(55) { -2.5f - Random.nextFloat() * 3.0f }
    private val starPhase = FloatArray(55) { Random.nextFloat() * 6.283f }

    // --- Camera & Input ---
    private var camYaw = 0.22f
    private var camPitch = 0.06f
    private var camDist = 5.45f
    private var autoOrbit = true
    private var showInspector = false
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var touchDownX = 0f
    private var touchDownY = 0f
    private var isDragging = false

    private var startTimeNs = System.nanoTime()
    private var lastFrameSec = 0f

    // --- Paints ---
    private val skyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val hudBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(196, 6, 15, 30)
        style = Paint.Style.FILL
    }
    private val hudBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(95, 56, 228, 255)
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 209, 102)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }
    private val monoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(56, 228, 255)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.MONOSPACE
    }
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    // --- CKB Blockchain Callbacks ---

    fun onBaselineLoaded(block: CkbBlockData) {
        rememberHash(block.blockHash)
        updateChainMetadata(block)
        hasHydratedBaseline = true
        // Critical Rule: Initial hydrated block establishes baseline state without triggering stop animation
        postInvalidateOnAnimation()
    }

    fun onBlockArrived(block: CkbBlockData) {
        if (!rememberHash(block.blockHash)) return
        updateChainMetadata(block)
        if (!hasHydratedBaseline) {
            hasHydratedBaseline = true
            postInvalidateOnAnimation()
            return
        }
        enqueueBlockEvent(block)
        postInvalidateOnAnimation()
    }

    fun onStatusUpdated(status: String) {
        statusText = status
        postInvalidateOnAnimation()
    }

    private fun rememberHash(hash: String): Boolean {
        if (seenBlockHashes.contains(hash)) return false
        seenBlockHashes.add(hash)
        if (seenBlockHashes.size > 300) {
            val oldest = seenBlockHashes.iterator().next()
            seenBlockHashes.remove(oldest)
        }
        return true
    }

    private fun updateChainMetadata(block: CkbBlockData) {
        latestBlockNumber = block.blockNumber
        latestBlockHash = block.blockHash
        latestTxCount = max(1, block.txCount) // Includes cellbase transaction
        latestTimestampMs = if (block.timestampMs > 0L) block.timestampMs else System.currentTimeMillis()
        latestEpochNumber = block.epochNumber
        latestEpochIndex = block.epochIndex
        latestEpochLength = max(1L, block.epochLength)
    }

    private fun enqueueBlockEvent(block: CkbBlockData) {
        if (blockEventQueue.size >= 8) {
            blockEventQueue.pollFirst()
        }
        blockEventQueue.addLast(block)
        if (wheelState == WheelState.RUNNING && activeBlockEvent == null) {
            startNextBlockSequence()
        }
    }

    private fun startNextBlockSequence() {
        val next = blockEventQueue.pollFirst()
        if (next == null) {
            activeBlockEvent = null
            return
        }
        activeBlockEvent = next
        latestBlockNumber = next.blockNumber
        latestBlockHash = next.blockHash
        latestTxCount = max(1, next.txCount)
        latestTimestampMs = if (next.timestampMs > 0L) next.timestampMs else System.currentTimeMillis()

        // 1. Select new curated palette & start radial/clockwise light wave pulse
        var nextIdx = Random.nextInt(palettes.size)
        if (nextIdx == currentPaletteIdx) {
            nextIdx = (nextIdx + 1) % palettes.size
        }
        currentPaletteIdx = nextIdx
        fromPrimary = activePrimary
        fromSecondary = activeSecondary
        toPrimary = palettes[nextIdx].primary
        toSecondary = palettes[nextIdx].secondary
        paletteTransition = 0f
        pulseAge = 0f
        pulseActive = true

        // 2. Begin smooth deceleration to station stop
        wheelState = WheelState.DECELERATING
        phaseElapsed = 0f
        decelStartVelocity = currentAngularVelocity
        performHapticFeedback(HapticFeedbackConstants.CONFIRM)
    }

    // --- Digital Crown & Touch Controls ---

    fun handleRotaryScroll(delta: Float) {
        camYaw = (camYaw + delta * 0.07f).coerceIn(-0.68f, 0.68f)
        autoOrbit = false
        postInvalidateOnAnimation()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val h = height.toFloat()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y
                touchDownX = event.x
                touchDownY = event.y
                isDragging = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastTouchX
                val dy = event.y - lastTouchY
                if (hypot(event.x - touchDownX, event.y - touchDownY) > 10f) {
                    isDragging = true
                    autoOrbit = false
                    camYaw = (camYaw - dx * 0.008f).coerceIn(-0.68f, 0.68f)
                    camPitch = (camPitch + dy * 0.006f).coerceIn(-0.18f, 0.30f)
                }
                lastTouchX = event.x
                lastTouchY = event.y
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!isDragging) {
                    if (showInspector) {
                        showInspector = false
                    } else if (event.y > h * 0.78f) {
                        showInspector = true
                    } else {
                        // Tap on Ferris wheel triggers a station arrival demonstration
                        autoOrbit = true
                        val nextHeight = if (latestBlockNumber > 0L) latestBlockNumber + 1L else 20837500L
                        val sampleTx = intArrayOf(1, 9, 17, 26)[Random.nextInt(4)]
                        enqueueBlockEvent(
                            CkbBlockData(
                                blockNumber = nextHeight,
                                blockHash = "0xstation_${System.currentTimeMillis()}",
                                txCount = sampleTx,
                                cells = emptyList(),
                                timestampMs = System.currentTimeMillis(),
                                epochNumber = latestEpochNumber,
                                epochIndex = latestEpochIndex,
                                epochLength = latestEpochLength
                            )
                        )
                    }
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    // --- 3D Camera & Perspective Projection ---

    private var camPosX = 0f
    private var camPosY = 0f
    private var camPosZ = 0f
    private var rightX = 1f; private var rightY = 0f; private var rightZ = 0f
    private var upX = 0f; private var upY = 1f; private var upZ = 0f
    private var fwdX = 0f; private var fwdY = 0f; private var fwdZ = -1f
    private var focalLen = 360f
    private var centerX = 225f
    private var centerY = 230f
    private val pt1 = FloatArray(3)
    private val pt2 = FloatArray(3)

    private fun updateCamera(w: Float, h: Float, t: Float) {
        centerX = w * 0.5f
        centerY = h * 0.51f
        focalLen = min(w, h) * 0.88f

        if (autoOrbit) {
            val targetYaw = sin(t * 0.24f) * 0.24f
            camYaw += (targetYaw - camYaw) * 0.03f
        }

        camPosX = sin(camYaw) * cos(camPitch) * camDist
        camPosY = (wheelCenterY - 0.1f) + sin(camPitch) * camDist
        camPosZ = cos(camYaw) * cos(camPitch) * camDist

        var fx = -camPosX
        var fy = (wheelCenterY - 0.12f) - camPosY
        var fz = -camPosZ
        val fLen = max(0.0001f, sqrt(fx * fx + fy * fy + fz * fz))
        fx /= fLen; fy /= fLen; fz /= fLen
        fwdX = fx; fwdY = fy; fwdZ = fz

        var rx = -fz
        val ry = 0f
        var rz = fx
        val rLen = max(0.0001f, sqrt(rx * rx + rz * rz))
        rx /= rLen; rz /= rLen
        rightX = rx; rightY = ry; rightZ = rz

        upX = ry * fz - rz * fy
        upY = rz * fx - rx * fz
        upZ = rx * fy - ry * fx
    }

    private fun project(wx: Float, wy: Float, wz: Float, out: FloatArray): Boolean {
        val dx = wx - camPosX
        val dy = wy - camPosY
        val dz = wz - camPosZ
        val cz = dx * fwdX + dy * fwdY + dz * fwdZ
        if (cz <= 0.4f) return false
        val cx = dx * rightX + dy * rightY + dz * rightZ
        val cy = dx * upX + dy * upY + dz * upZ
        val sc = focalLen / cz
        out[0] = centerX + cx * sc
        out[1] = centerY - cy * sc
        out[2] = cz
        return true
    }

    private fun lerpColor(c1: Int, c2: Int, t: Float): Int {
        val f = t.coerceIn(0f, 1f)
        val r = (Color.red(c1) + (Color.red(c2) - Color.red(c1)) * f).toInt().coerceIn(0, 255)
        val g = (Color.green(c1) + (Color.green(c2) - Color.green(c1)) * f).toInt().coerceIn(0, 255)
        val b = (Color.blue(c1) + (Color.blue(c2) - Color.blue(c1)) * f).toInt().coerceIn(0, 255)
        return Color.rgb(r, g, b)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val nowSec = (System.nanoTime() - startTimeNs) * 1e-9f
        val dt = if (lastFrameSec == 0f) 0.016f else (nowSec - lastFrameSec).coerceIn(0.001f, 0.05f)
        lastFrameSec = nowSec

        updateStateMachineAndPhysics(dt)
        updateCamera(w, h, nowSec)

        // 1. Nighttime Amusement-Park Sky, Stars & Illuminated Plaza
        drawNightEnvironment(canvas, w, h, nowSec)

        // 2. Stationary A-Frame Support Structure & Boarding Station Platform
        drawSupportAndStation(canvas)

        // 3. Rotating Illuminated Ferris Wheel, Bulbs & Upright Gondolas
        drawFerrisWheelAssembly(canvas, nowSec)

        // 4. Compact Persistent HUD & Non-Obscuring Block Arrival Toast
        drawWatchHud(canvas, w, h)

        // 5. Inspector Overlay
        if (showInspector) {
            drawInspectorOverlay(canvas, w, h)
        }

        postInvalidateOnAnimation()
    }

    private fun updateStateMachineAndPhysics(dt: Float) {
        phaseElapsed += dt

        when (wheelState) {
            WheelState.DECELERATING -> {
                val t = (phaseElapsed / decelDurationSec).coerceIn(0f, 1f)
                val easeOut = 1f - (1f - t).pow(3)
                currentAngularVelocity = decelStartVelocity * (1f - easeOut)
                toastAlpha = (toastAlpha + dt * 3.2f).coerceAtMost(1f)
                if (t >= 1f) {
                    currentAngularVelocity = 0f
                    wheelState = WheelState.STOPPED
                    phaseElapsed = 0f
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                }
            }
            WheelState.STOPPED -> {
                currentAngularVelocity = 0f
                toastAlpha = 1f
                if (phaseElapsed >= stopHoldDurationSec) {
                    wheelState = WheelState.ACCELERATING
                    phaseElapsed = 0f
                }
            }
            WheelState.ACCELERATING -> {
                val t = (phaseElapsed / accelDurationSec).coerceIn(0f, 1f)
                val easeInOut = t * t * (3f - 2f * t)
                currentAngularVelocity = normalAngularVelocity * easeInOut
                toastAlpha = (toastAlpha - dt * 2.4f).coerceAtLeast(0f)
                if (t >= 1f) {
                    currentAngularVelocity = normalAngularVelocity
                    wheelState = WheelState.RUNNING
                    phaseElapsed = 0f
                    activeBlockEvent = null
                    toastAlpha = 0f
                    if (blockEventQueue.isNotEmpty()) {
                        startNextBlockSequence()
                    }
                }
            }
            WheelState.RUNNING -> {
                currentAngularVelocity = normalAngularVelocity
                toastAlpha = (toastAlpha - dt * 2.5f).coerceAtLeast(0f)
            }
        }

        val angularAccel = (currentAngularVelocity - prevAngularVelocity) / max(0.001f, dt)
        prevAngularVelocity = currentAngularVelocity
        wheelRotation += currentAngularVelocity * dt

        // Update Gondola damped gravity swing
        for (i in 0 until gondolaCount) {
            val spring = -12f * gondolaSwingAngle[i] - 3.2f * gondolaSwingVel[i] - angularAccel * 0.14f
            gondolaSwingVel[i] += spring * dt
            gondolaSwingAngle[i] = (gondolaSwingAngle[i] + gondolaSwingVel[i] * dt).coerceIn(-0.20f, 0.20f)
        }

        // Update palette interpolation & radial block light pulse
        if (paletteTransition < 1f) {
            paletteTransition = (paletteTransition + dt * 0.78f).coerceAtMost(1f)
        }
        val smoothT = paletteTransition * paletteTransition * (3f - 2f * paletteTransition)
        activePrimary = lerpColor(fromPrimary, toPrimary, smoothT)
        activeSecondary = lerpColor(fromSecondary, toSecondary, smoothT)

        if (pulseActive) {
            pulseAge += dt
            if (pulseAge > 4.0f) {
                pulseActive = false
            }
        }
    }

    private fun drawNightEnvironment(canvas: Canvas, w: Float, h: Float, t: Float) {
        skyPaint.shader = LinearGradient(
            0f, 0f, 0f, h,
            intArrayOf(
                Color.rgb(2, 6, 15),
                Color.rgb(7, 19, 40),
                Color.rgb(12, 28, 54),
                Color.rgb(4, 10, 20)
            ),
            floatArrayOf(0f, 0.55f, 0.82f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, skyPaint)

        // Twinkling night stars
        for (i in starX.indices) {
            if (project(starX[i], starY[i], starZ[i], pt1)) {
                val tw = 0.5f + 0.5f * sin(t * 2.1f + starPhase[i])
                fillPaint.color = Color.argb((80 + tw * 155).toInt(), 215, 238, 255)
                canvas.drawCircle(pt1[0], pt1[1], 1.1f + tw * 0.9f, fillPaint)
            }
        }

        // Illuminated fairground plaza ring on the ground
        val segs = 32
        var prev = false
        var px = 0f
        var py = 0f
        strokePaint.strokeWidth = 1.6f
        strokePaint.color = Color.argb(85, Color.red(activePrimary), Color.green(activePrimary), Color.blue(activePrimary))
        for (i in 0..segs) {
            val a = (i.toFloat() / segs) * 6.283185f
            if (project(cos(a) * 2.2f, groundY, sin(a) * 2.2f, pt1)) {
                if (prev) canvas.drawLine(px, py, pt1[0], pt1[1], strokePaint)
                px = pt1[0]; py = pt1[1]; prev = true
            } else {
                prev = false
            }
        }
    }

    private fun drawSupportAndStation(canvas: Canvas) {
        val frontZ = wheelDepth * 0.85f
        val backZ = -wheelDepth * 0.85f
        val footSpreadX = 1.12f

        // Stationary A-frame legs (back and front)
        for (zPos in floatArrayOf(backZ, frontZ)) {
            strokePaint.color = Color.rgb(42, 68, 98)
            strokePaint.strokeWidth = 3.2f
            draw3DLine(canvas, -footSpreadX, groundY, zPos * 1.25f, 0f, wheelCenterY, zPos)
            draw3DLine(canvas, footSpreadX, groundY, zPos * 1.25f, 0f, wheelCenterY, zPos)

            // Horizontal A-frame tie bars
            strokePaint.color = Color.rgb(68, 100, 136)
            strokePaint.strokeWidth = 1.8f
            for (frac in floatArrayOf(0.35f, 0.65f)) {
                val lx = -footSpreadX * (1f - frac)
                val rx = footSpreadX * (1f - frac)
                val ly = groundY + (wheelCenterY - groundY) * frac
                val lz = zPos * (1.25f - 0.25f * frac)
                draw3DLine(canvas, lx, ly, lz, rx, ly, lz)
            }
        }

        // Stationary Boarding Station Platform & Warm Lamps at Bottom of Wheel
        strokePaint.color = Color.rgb(185, 135, 75)
        strokePaint.strokeWidth = 4.0f
        draw3DLine(canvas, -0.95f, groundY + 0.08f, 0.35f, 0.95f, groundY + 0.08f, 0.35f)

        for (lampX in floatArrayOf(-0.82f, 0.82f)) {
            if (project(lampX, groundY + 0.28f, 0.42f, pt1)) {
                fillPaint.color = Color.argb(95, 255, 195, 90)
                canvas.drawCircle(pt1[0], pt1[1], 7.5f, fillPaint)
                fillPaint.color = Color.rgb(255, 220, 130)
                canvas.drawCircle(pt1[0], pt1[1], 3.0f, fillPaint)
            }
        }
    }

    private fun drawFerrisWheelAssembly(canvas: Canvas, t: Float) {
        val frontZ = wheelDepth * 0.5f
        val backZ = -wheelDepth * 0.5f

        // 1. Structural Spokes & Transverse Rungs
        for (i in 0 until spokeCount) {
            val a = wheelRotation + (i.toFloat() / spokeCount) * 6.283185f
            val cosA = cos(a)
            val sinA = sin(a)
            val ox = cosA * wheelRadius
            val oy = wheelCenterY + sinA * wheelRadius
            val ix = cosA * hubRadius
            val iy = wheelCenterY + sinA * hubRadius

            strokePaint.color = Color.argb(145, 92, 134, 178)
            strokePaint.strokeWidth = 1.5f
            draw3DLine(canvas, ix, iy, backZ, ox, oy, backZ)
            draw3DLine(canvas, ix, iy, frontZ, ox, oy, frontZ)
            draw3DLine(canvas, ox, oy, backZ, ox, oy, frontZ)

            // Decorative Spoke Chaser Bulbs
            for (b in 1..4) {
                val rFrac = b / 5f
                val r = hubRadius + rFrac * (wheelRadius - hubRadius)
                val bx = cosA * r
                val by = wheelCenterY + sinA * r
                if (project(bx, by, frontZ, pt1)) {
                    val col = computeBulbColor(rFrac, i.toFloat() / spokeCount, t, isOuterRim = false)
                    fillPaint.color = col
                    canvas.drawCircle(pt1[0], pt1[1], 2.1f, fillPaint)
                }
            }
        }

        // 2. Inner & Outer Structural Rims + Glowing Rim Bulbs
        val rimSegs = 48
        for (zSide in floatArrayOf(backZ, frontZ)) {
            var prevOuter = false
            var pox = 0f; var poy = 0f
            var prevInner = false
            var pix = 0f; var piy = 0f

            for (i in 0..rimSegs) {
                val a = wheelRotation + (i.toFloat() / rimSegs) * 6.283185f
                val cosA = cos(a)
                val sinA = sin(a)

                val ox = cosA * wheelRadius
                val oy = wheelCenterY + sinA * wheelRadius
                if (project(ox, oy, zSide, pt1)) {
                    if (prevOuter) {
                        strokePaint.color = Color.rgb(110, 155, 200)
                        strokePaint.strokeWidth = if (zSide > 0f) 2.4f else 1.6f
                        canvas.drawLine(pox, poy, pt1[0], pt1[1], strokePaint)
                    }
                    pox = pt1[0]; poy = pt1[1]; prevOuter = true

                    if (i < rimSegs) {
                        val bulbCol = computeBulbColor(1.0f, i.toFloat() / rimSegs, t, isOuterRim = true)
                        // Subtle outer glow halo on front rim bulbs
                        if (zSide > 0f) {
                            fillPaint.color = Color.argb(65, Color.red(bulbCol), Color.green(bulbCol), Color.blue(bulbCol))
                            canvas.drawCircle(pt1[0], pt1[1], 5.0f, fillPaint)
                        }
                        fillPaint.color = bulbCol
                        canvas.drawCircle(pt1[0], pt1[1], if (zSide > 0f) 2.5f else 1.8f, fillPaint)
                    }
                } else {
                    prevOuter = false
                }

                val ix = cosA * innerRimRadius
                val iy = wheelCenterY + sinA * innerRimRadius
                if (project(ix, iy, zSide, pt2)) {
                    if (prevInner) {
                        strokePaint.color = Color.argb(170, 68, 106, 148)
                        strokePaint.strokeWidth = 1.4f
                        canvas.drawLine(pix, piy, pt2[0], pt2[1], strokePaint)
                    }
                    pix = pt2[0]; piy = pt2[1]; prevInner = true
                } else {
                    prevInner = false
                }
            }
        }

        // 3. Central Axle & Neon Hub Drum
        if (project(0f, wheelCenterY, frontZ * 1.1f, pt1)) {
            val rHub = (focalLen / pt1[2]) * hubRadius
            fillPaint.color = Color.argb(90, Color.red(activePrimary), Color.green(activePrimary), Color.blue(activePrimary))
            canvas.drawCircle(pt1[0], pt1[1], rHub * 1.45f, fillPaint)
            fillPaint.color = Color.rgb(24, 42, 68)
            canvas.drawCircle(pt1[0], pt1[1], rHub, fillPaint)
            strokePaint.color = activePrimary
            strokePaint.strokeWidth = 2.6f
            canvas.drawCircle(pt1[0], pt1[1], rHub * 0.82f, strokePaint)
        }

        // 4. 16 Upright Passenger Gondolas with Damped Pendulum Settling
        for (i in 0 until gondolaCount) {
            val a = wheelRotation + (i.toFloat() / gondolaCount) * 6.283185f
            val pinX = cos(a) * wheelRadius
            val pinY = wheelCenterY + sin(a) * wheelRadius
            val swing = gondolaSwingAngle[i]

            // Gondola hangs upright beneath its rim mounting pin, deflected only by damped swing
            val cabCenterX = pinX + sin(swing) * 0.22f
            val cabCenterY = pinY - cos(swing) * 0.22f

            if (project(pinX, pinY, 0f, pt1) && project(cabCenterX, cabCenterY, 0f, pt2)) {
                val scale = focalLen / pt2[2]
                val cabW = (0.11f * scale).coerceIn(4.5f, 14f)
                val cabH = (0.13f * scale).coerceIn(5.5f, 16f)

                // Hanger arm from pin to cabin roof
                strokePaint.color = Color.rgb(150, 185, 220)
                strokePaint.strokeWidth = 1.6f
                canvas.drawLine(pt1[0], pt1[1], pt2[0], pt2[1] - cabH * 0.45f, strokePaint)

                // Cabin body
                val rect = RectF(
                    pt2[0] - cabW * 0.5f,
                    pt2[1] - cabH * 0.45f,
                    pt2[0] + cabW * 0.5f,
                    pt2[1] + cabH * 0.55f
                )
                fillPaint.color = Color.rgb(20, 38, 66)
                canvas.drawRoundRect(rect, 4f, 4f, fillPaint)

                // Illuminated warm cabin window band
                fillPaint.color = Color.rgb(255, 224, 140)
                canvas.drawRect(
                    pt2[0] - cabW * 0.38f,
                    pt2[1] - cabH * 0.16f,
                    pt2[0] + cabW * 0.38f,
                    pt2[1] + cabH * 0.14f,
                    fillPaint
                )

                // Palette-colored gondola roof canopy
                fillPaint.color = activePrimary
                canvas.drawOval(
                    pt2[0] - cabW * 0.62f,
                    pt2[1] - cabH * 0.60f,
                    pt2[0] + cabW * 0.62f,
                    pt2[1] - cabH * 0.28f,
                    fillPaint
                )
            }
        }
    }

    private fun computeBulbColor(radiusNorm: Float, angleNorm: Float, t: Float, isOuterRim: Boolean): Int {
        val wave = 0.5f + 0.5f * sin(angleNorm * 6.283185f * 4f - t * 2.2f + radiusNorm * 4.2f)
        var col = lerpColor(activePrimary, activeSecondary, wave)

        if (pulseActive) {
            val radialFront = pulseAge * 0.78f
            val radialDist = abs(radiusNorm - radialFront)
            val radialBoost = (1f - radialDist * 3.6f).coerceAtLeast(0f) * exp(-pulseAge * 0.55f)

            val cwFront = (pulseAge * 0.48f) % 1.0f
            val cwDist = min(abs(angleNorm - cwFront), 1f - abs(angleNorm - cwFront))
            val cwBoost = if (isOuterRim) (1f - cwDist * 5.2f).coerceAtLeast(0f) * exp(-pulseAge * 0.45f) else 0f

            val boost = (radialBoost + cwBoost).coerceAtMost(0.85f)
            if (boost > 0.02f) {
                col = lerpColor(col, Color.WHITE, boost)
            }
        }
        return col
    }

    private fun draw3DLine(canvas: Canvas, x1: Float, y1: Float, z1: Float, x2: Float, y2: Float, z2: Float) {
        if (project(x1, y1, z1, pt1) && project(x2, y2, z2, pt2)) {
            canvas.drawLine(pt1[0], pt1[1], pt2[0], pt2[1], strokePaint)
        }
    }

    private fun drawWatchHud(canvas: Canvas, w: Float, h: Float) {
        // 1. Compact Top Status Pill (NERVOS NETWORK · LIVE · TIP #...)
        val topW = w * 0.66f
        val topH = h * 0.135f
        val topLeft = (w - topW) * 0.5f
        val topY = h * 0.045f
        val topRect = RectF(topLeft, topY, topLeft + topW, topY + topH)

        canvas.drawRoundRect(topRect, 20f, 20f, hudBgPaint)
        canvas.drawRoundRect(topRect, 20f, 20f, hudBorderPaint)

        fillPaint.color = if (statusText.startsWith("LIVE")) Color.rgb(0, 245, 160) else Color.rgb(255, 209, 102)
        canvas.drawCircle(topLeft + 18f, topY + topH * 0.36f, 4.2f, fillPaint)

        monoPaint.textSize = w * 0.027f
        monoPaint.color = Color.rgb(238, 247, 255)
        val tipStr = if (latestBlockNumber > 0L) "LIVE · TIP #$latestBlockNumber" else statusText
        canvas.drawText(tipStr, w * 0.52f, topY + topH * 0.44f, monoPaint)

        monoPaint.textSize = w * 0.024f
        monoPaint.color = activePrimary
        canvas.drawText(
            "${wheelState.name} · ${palettes[currentPaletteIdx].name}",
            w * 0.5f,
            topY + topH * 0.80f,
            monoPaint
        )

        // 2. Non-Obscuring Lower Block Arrival Notification Toast (During DECELERATING / STOPPED)
        if (toastAlpha > 0.02f) {
            val aInt = (toastAlpha * 230).toInt().coerceIn(0, 230)
            val cardW = w * 0.70f
            val cardH = h * 0.145f
            val cardLeft = (w - cardW) * 0.5f
            val slideY = (1f - toastAlpha) * 12f
            val cardTop = h * 0.645f + slideY
            val cardRect = RectF(cardLeft, cardTop, cardLeft + cardW, cardTop + cardH)

            fillPaint.color = Color.argb(aInt, 7, 18, 36)
            canvas.drawRoundRect(cardRect, 18f, 18f, fillPaint)

            strokePaint.color = Color.argb(aInt, Color.red(activePrimary), Color.green(activePrimary), Color.blue(activePrimary))
            strokePaint.strokeWidth = 1.8f
            canvas.drawRoundRect(cardRect, 18f, 18f, strokePaint)

            titlePaint.textSize = w * 0.027f
            titlePaint.color = Color.argb(aInt, 255, 209, 102)
            val qExtra = if (blockEventQueue.isNotEmpty()) " (+${blockEventQueue.size})" else ""
            canvas.drawText("NEW NERVOS BLOCK$qExtra", w * 0.5f, cardTop + cardH * 0.34f, titlePaint)

            monoPaint.textSize = w * 0.032f
            monoPaint.color = Color.argb(aInt, 242, 248, 255)
            val txLabel = if (latestTxCount == 1) "1 transaction" else "$latestTxCount transactions"
            canvas.drawText("#$latestBlockNumber · $txLabel", w * 0.5f, cardTop + cardH * 0.66f, monoPaint)

            monoPaint.textSize = w * 0.024f
            monoPaint.color = Color.argb(aInt, 56, 228, 255)
            canvas.drawText(timeFormat.format(Date(latestTimestampMs)), w * 0.5f, cardTop + cardH * 0.90f, monoPaint)
        }

        // 3. Bottom Pill (Tap Wheel: Station Stop / Tap Here: Inspect)
        val botW = w * 0.64f
        val botH = h * 0.095f
        val botLeft = (w - botW) * 0.5f
        val botTop = h * 0.82f
        val botRect = RectF(botLeft, botTop, botLeft + botW, botTop + botH)

        canvas.drawRoundRect(botRect, botH * 0.5f, botH * 0.5f, hudBgPaint)
        canvas.drawRoundRect(botRect, botH * 0.5f, botH * 0.5f, hudBorderPaint)

        monoPaint.textSize = w * 0.026f
        monoPaint.color = Color.rgb(255, 209, 102)
        canvas.drawText("TAP WHEEL: STOP · INSPECT", w * 0.5f, botTop + botH * 0.64f, monoPaint)
    }

    private fun drawInspectorOverlay(canvas: Canvas, w: Float, h: Float) {
        fillPaint.color = Color.argb(238, 3, 9, 20)
        canvas.drawRect(0f, 0f, w, h, fillPaint)

        titlePaint.textSize = w * 0.045f
        titlePaint.color = Color.rgb(255, 209, 102)
        canvas.drawText("CKB FERRIS WHEEL", w * 0.5f, h * 0.17f, titlePaint)

        monoPaint.textSize = w * 0.027f
        monoPaint.color = activePrimary
        canvas.drawText(palettes[currentPaletteIdx].name, w * 0.5f, h * 0.24f, monoPaint)

        val shortHash = if (latestBlockHash.length > 18) {
            latestBlockHash.take(10) + "…" + latestBlockHash.takeLast(6)
        } else latestBlockHash

        val rows = listOf(
            "RIDE STATE" to wheelState.name,
            "CHAIN TIP" to "#$latestBlockNumber",
            "TX COUNT" to "$latestTxCount (incl. cellbase)",
            "BLOCK TIME" to timeFormat.format(Date(latestTimestampMs)),
            "EPOCH" to "#$latestEpochNumber ($latestEpochIndex/$latestEpochLength)",
            "QUEUED" to "${blockEventQueue.size} blocks",
            "BLOCK HASH" to shortHash
        )

        var y = h * 0.34f
        monoPaint.textSize = w * 0.029f
        for ((label, value) in rows) {
            monoPaint.color = Color.rgb(143, 178, 210)
            canvas.drawText("$label: $value", w * 0.5f, y, monoPaint)
            y += h * 0.078f
        }

        monoPaint.textSize = w * 0.027f
        monoPaint.color = Color.rgb(56, 228, 255)
        canvas.drawText("TAP ANYWHERE TO CLOSE", w * 0.5f, h * 0.90f, monoPaint)
    }
}
