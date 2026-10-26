package network.nervos.watchvisualiser.ui

import android.content.Context
import android.graphics.*
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import network.nervos.watchvisualiser.data.AppPreferences
import network.nervos.watchvisualiser.data.CkbBlockData
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.*
import kotlin.random.Random

/**
 * Native 60 FPS Wear OS Visualizer: CKB Halving & Live Epoch Water Vessel (`ckb_halving`).
 *
 * Visualizes:
 * - Current CKB variable-length epoch as a 3D transparent borosilicate glass vessel filling
 *   from a stationary bottom upward (`(epochIndex + 1) / epochLength`).
 * - Primary Issuance Halving (`HALVING_INTERVAL = 8760` epochs, `nextHalving = ((epoch / 8760) + 1) * 8760`).
 * - Live Expected Halving Date/Time & Countdown using historical epoch pace sampling (or 4h baseline fallback).
 * - Condensation droplet, concentric meniscus ripples, and rising bubble bursts on every new canonical block.
 */
class HalvingVesselVisualiserView(context: Context) : View(context) {

    companion object {
        private const val HALVING_INTERVAL = 8760L
        private const val DEFAULT_EPOCH_MS = 4L * 60L * 60L * 1000L // 4 hours (14,400,000 ms)
    }

    // --- Chain & Epoch State ---
    private var hasHydrated = false
    private var statusText: String = "CONNECTING"
    private var blockNumber: Long = 0L
    private var blockHash: String = "—"
    private var blockTimestampMs: Long = System.currentTimeMillis()

    private var epochNumber: Long = 0L
    private var epochIndex: Long = 0L
    private var epochLength: Long = 1800L
    private var completedBlocks: Long = 0L
    private var remainingBlocks: Long = 1800L

    // Separate actual vs visual epoch progress (Section 26)
    private var actualEpochProgress: Float = 0f
    private var visualEpochProgress: Float = 0f
    private var overflowHoldTimer: Float = 0f
    private var overflowGlowAlpha: Float = 0f

    // --- Halving & ETA State ---
    private var eraNumber: Long = 2L
    private var nextHalvingEpoch: Long = 17520L
    private var epochsUntilHalving: Double = 2341.0
    private var halvingCycleProgress: Float = 0.73f
    @Volatile
    private var estimatedEpochMs: Double = DEFAULT_EPOCH_MS.toDouble()
    @Volatile
    private var paceLabel: String = "4h 00m target"
    @Volatile
    private var lastSampledEpoch: Long = -1L
    @Volatile
    private var samplingInFlight: Boolean = false
    private var estimatedHalvingTimestampMs: Long = 0L

    private val seenHashes = LinkedHashSet<String>()

    // --- 3D Vessel Geometry & Camera ---
    private var camYaw: Float = 0.25f
    private var camPitch: Float = 0.14f
    private var camDist: Float = 6.2f
    private var autoOrbit: Boolean = true

    private val tankRadius = 1.05f
    private val waterRadius = 0.98f
    private val tankBottomY = -1.32f
    private val tankTopY = 1.18f
    private val tankHeight = tankTopY - tankBottomY

    // --- Water Animation, Droplet, Ripple & Pooled Bubbles ---
    private var startTimeNs: Long = System.nanoTime()
    private var lastFrameSec: Float = 0f
    private var rippleAge: Float = 10f
    private var rippleStrength: Float = 0f

    private var dropletActive: Boolean = false
    private var dropletY: Float = 0f
    private var dropletVy: Float = 0f

    private data class Bubble(
        var relX: Float,
        var relZ: Float,
        var relY: Float,
        var speed: Float,
        var phase: Float,
        var radius: Float
    )

    private data class SplashDrop(
        var active: Boolean = false,
        var x: Float = 0f,
        var y: Float = 0f,
        var z: Float = 0f,
        var vx: Float = 0f,
        var vy: Float = 0f,
        var vz: Float = 0f,
        var age: Float = 0f,
        var maxAge: Float = 0.5f
    )

    private val bubbles = Array(34) {
        val r = sqrt(Random.nextFloat()) * (waterRadius * 0.84f)
        val a = Random.nextFloat() * 6.283185f
        Bubble(
            relX = cos(a) * r,
            relZ = sin(a) * r,
            relY = Random.nextFloat(),
            speed = 0.14f + Random.nextFloat() * 0.22f,
            phase = Random.nextFloat() * 6.283185f,
            radius = 1.8f + Random.nextFloat() * 2.4f
        )
    }

    private val splashPool = Array(14) { SplashDrop() }

    // --- Touch & Inspector State ---
    private var showInspector: Boolean = false
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var touchDownX = 0f
    private var touchDownY = 0f
    private var isDragging = false

    // --- Paints ---
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val hudBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(198, 5, 16, 32)
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
    private val path = Path()
    private val localDateFormat = SimpleDateFormat("MMM d, yyyy HH:mm", Locale.US)
    private val utcDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm 'UTC'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    // --- CKB Blockchain Callbacks ---

    fun onBaselineLoaded(block: CkbBlockData) {
        applyBlockState(block, isInitial = true)
    }

    fun onBlockArrived(block: CkbBlockData) {
        if (seenHashes.contains(block.blockHash)) return
        applyBlockState(block, isInitial = false)
    }

    fun onStatusUpdated(status: String) {
        statusText = status
        postInvalidateOnAnimation()
    }

    private fun applyBlockState(block: CkbBlockData, isInitial: Boolean) {
        seenHashes.add(block.blockHash)
        if (seenHashes.size > 250) {
            val first = seenHashes.iterator().next()
            seenHashes.remove(first)
        }

        val prevEpoch = epochNumber
        val wasHydrated = hasHydrated

        blockNumber = block.blockNumber
        blockHash = block.blockHash
        blockTimestampMs = if (block.timestampMs > 0L) block.timestampMs else System.currentTimeMillis()

        epochNumber = max(0L, block.epochNumber)
        epochIndex = max(0L, block.epochIndex)
        epochLength = max(1L, block.epochLength)
        completedBlocks = (epochIndex + 1L).coerceAtMost(epochLength)
        remainingBlocks = (epochLength - completedBlocks).coerceAtLeast(0L)

        actualEpochProgress = (completedBlocks.toFloat() / epochLength.toFloat()).coerceIn(0f, 1f)

        if (!wasHydrated || isInitial) {
            hasHydrated = true
            visualEpochProgress = actualEpochProgress
        } else if (epochNumber != prevEpoch) {
            // Epoch boundary rollover: briefly hold at 100% with golden overflow glow
            visualEpochProgress = 1.0f
            overflowHoldTimer = 1.35f
            overflowGlowAlpha = 1.0f
            triggerSplashAtSurface(tankTopY)
            performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        } else {
            // New canonical block droplet
            triggerFallingDroplet()
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }

        recalculateHalvingAndEta()

        if (lastSampledEpoch != epochNumber && !samplingInFlight && epochNumber > 4L) {
            sampleHistoricalEpochPaceAsync(epochNumber)
        }

        postInvalidateOnAnimation()
    }

    private fun recalculateHalvingAndEta() {
        val eraIdx = epochNumber / HALVING_INTERVAL
        eraNumber = eraIdx + 1L
        nextHalvingEpoch = (eraIdx + 1L) * HALVING_INTERVAL

        val frac = completedBlocks.toDouble() / epochLength.toDouble()
        val wholeRemaining = (nextHalvingEpoch - epochNumber - 1L).coerceAtLeast(0L).toDouble()
        epochsUntilHalving = wholeRemaining + (1.0 - frac)

        val intoCycle = (epochNumber % HALVING_INTERVAL).toDouble() + frac
        halvingCycleProgress = (intoCycle / HALVING_INTERVAL.toDouble()).toFloat().coerceIn(0f, 1f)

        val baseTs = if (blockTimestampMs > 0L) blockTimestampMs else System.currentTimeMillis()
        estimatedHalvingTimestampMs = baseTs + (epochsUntilHalving * estimatedEpochMs).roundToLong()
    }

    /**
     * Queries recent completed epoch start-block timestamps on a background thread
     * (`get_epoch_by_number` + `get_header_by_number`) to compute a trimmed-mean epoch duration.
     * Gracefully falls back to the 4-hour target if historical sampling fails.
     */
    private fun sampleHistoricalEpochPaceAsync(currentEp: Long) {
        samplingInFlight = true
        lastSampledEpoch = currentEp
        val rpcUrl = AppPreferences(context).ckbRpcUrl
        Thread {
            try {
                val sampleCount = 24
                val stride = 3
                val points = ArrayList<Pair<Long, Long>>()
                for (offset in 0..sampleCount step stride) {
                    val targetEp = currentEp - offset
                    if (targetEp < 0L) break
                    val epHex = "0x" + targetEp.toString(16)
                    val epObj = rpcCall(rpcUrl, "get_epoch_by_number", JSONArray().put(epHex)) ?: continue
                    val startNumHex = epObj.optString("start_number", "")
                    if (startNumHex.isEmpty()) continue
                    val hdrObj = rpcCall(rpcUrl, "get_header_by_number", JSONArray().put(startNumHex))
                        ?: rpcCall(rpcUrl, "get_block_by_number", JSONArray().put(startNumHex))?.optJSONObject("header")
                        ?: continue
                    val tsHex = hdrObj.optString("timestamp", "")
                    if (tsHex.isNotEmpty()) {
                        val tsMs = parseHexLong(tsHex)
                        if (tsMs > 0L) points.add(targetEp to tsMs)
                    }
                }
                points.sortBy { it.first }
                val durations = ArrayList<Double>()
                for (i in 1 until points.size) {
                    val dEp = points[i].first - points[i - 1].first
                    val dTs = points[i].second - points[i - 1].second
                    if (dEp > 0 && dTs > 0) {
                        val avg = dTs.toDouble() / dEp.toDouble()
                        if (avg in 3_600_000.0..43_200_000.0) {
                            durations.add(avg)
                        }
                    }
                }
                if (durations.size >= 3) {
                    durations.sort()
                    val trim = (durations.size * 0.15).toInt().coerceAtLeast(1)
                    val trimmed = if (durations.size > trim * 2) {
                        durations.subList(trim, durations.size - trim)
                    } else durations
                    val meanMs = trimmed.average()
                    val totalMin = (meanMs / 60000.0).roundToInt()
                    val hrs = totalMin / 60
                    val mins = totalMin % 60
                    estimatedEpochMs = meanMs
                    paceLabel = String.format(Locale.US, "%dh %02dm avg (24 ep)", hrs, mins)
                    recalculateHalvingAndEta()
                    postInvalidateOnAnimation()
                }
            } catch (_: Exception) {
                estimatedEpochMs = DEFAULT_EPOCH_MS.toDouble()
                paceLabel = "4h 00m target"
            } finally {
                samplingInFlight = false
            }
        }.start()
    }

    private fun rpcCall(rpcUrl: String, method: String, params: JSONArray): JSONObject? {
        val req = JSONObject().apply {
            put("id", System.currentTimeMillis())
            put("jsonrpc", "2.0")
            put("method", method)
            put("params", params)
        }
        val conn = (URL(rpcUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            connectTimeout = 5000
            readTimeout = 5000
            doOutput = true
        }
        OutputStreamWriter(conn.outputStream).use { it.write(req.toString()) }
        if (conn.responseCode == 200) {
            val txt = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
            return JSONObject(txt).optJSONObject("result")
        }
        return null
    }

    private fun parseHexLong(hex: String): Long {
        val clean = if (hex.startsWith("0x", ignoreCase = true)) hex.substring(2) else hex
        return if (clean.isEmpty()) 0L else BigInteger(clean, 16).toLong()
    }

    private fun triggerFallingDroplet() {
        dropletActive = true
        dropletY = tankTopY + 0.05f
        dropletVy = -1.85f
    }

    private fun triggerSplashAtSurface(surfaceY: Float) {
        rippleAge = 0f
        rippleStrength = 1.0f
        for (i in splashPool.indices) {
            val sp = splashPool[i]
            val ang = (i.toFloat() / splashPool.size) * 6.283185f + (Random.nextFloat() - 0.5f) * 0.25f
            val spd = 0.35f + Random.nextFloat() * 0.55f
            sp.active = true
            sp.age = 0f
            sp.maxAge = 0.42f + Random.nextFloat() * 0.22f
            sp.x = 0f
            sp.y = surfaceY + 0.02f
            sp.z = 0f
            sp.vx = cos(ang) * spd
            sp.vz = sin(ang) * spd
            sp.vy = 1.05f + Random.nextFloat() * 0.75f
        }
    }

    // --- Digital Crown & Touch Controls ---

    fun handleRotaryScroll(delta: Float) {
        camYaw += delta * 0.08f
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
                    camYaw -= dx * 0.01f
                    camPitch = (camPitch + dy * 0.007f).coerceIn(-0.18f, 0.52f)
                }
                lastTouchX = event.x
                lastTouchY = event.y
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!isDragging) {
                    if (showInspector) {
                        showInspector = false
                    } else if (event.y > h * 0.76f) {
                        showInspector = true
                    } else {
                        // Tap on vessel triggers a visible block droplet + ripple demonstration
                        autoOrbit = true
                        triggerFallingDroplet()
                        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    }
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    // --- 3D Projection Helper ---

    private var camPosX = 0f
    private var camPosY = 0f
    private var camPosZ = 0f
    private var rightX = 1f; private var rightY = 0f; private var rightZ = 0f
    private var upX = 0f; private var upY = 1f; private var upZ = 0f
    private var fwdX = 0f; private var fwdY = 0f; private var fwdZ = -1f
    private var focalLen = 360f
    private var centerX = 225f
    private var centerY = 232f
    private val pt1 = FloatArray(3)
    private val pt2 = FloatArray(3)

    private fun updateCamera(w: Float, h: Float, dt: Float) {
        centerX = w * 0.5f
        centerY = h * 0.52f
        focalLen = min(w, h) * 0.92f

        if (autoOrbit) {
            camYaw += dt * 0.22f
        }

        camPosX = sin(camYaw) * cos(camPitch) * camDist
        camPosY = sin(camPitch) * camDist
        camPosZ = cos(camYaw) * cos(camPitch) * camDist

        var fx = -camPosX
        var fy = -0.05f - camPosY
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

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val nowSec = (System.nanoTime() - startTimeNs) * 1e-9f
        val dt = if (lastFrameSec == 0f) 0.016f else (nowSec - lastFrameSec).coerceIn(0.001f, 0.05f)
        lastFrameSec = nowSec

        updatePhysics(dt)
        updateCamera(w, h, dt)

        // 1. Deep Hydro-Chronometer Backdrop
        drawBackdrop(canvas, w, h)

        // 2. 3D Transparent Borosilicate Glass Vessel & Stationary-Bottom Rising Water
        drawGlassWaterVessel(canvas, nowSec)

        // 3. Upper Halving ETA & Countdown + Lower Epoch & Cycle HUD
        drawWatchHud(canvas, w, h)

        // 4. Full Halving & Epoch Inspector Overlay
        if (showInspector) {
            drawInspectorOverlay(canvas, w, h)
        }

        postInvalidateOnAnimation()
    }

    private fun updatePhysics(dt: Float) {
        rippleAge += dt
        rippleStrength = (rippleStrength - dt * 0.38f).coerceAtLeast(0f)
        overflowGlowAlpha = (overflowGlowAlpha - dt * 0.55f).coerceAtLeast(0f)

        if (overflowHoldTimer > 0f) {
            overflowHoldTimer -= dt
        } else {
            val target = if (hasHydrated) actualEpochProgress else 0.02f
            visualEpochProgress += (target - visualEpochProgress) * (1f - exp(-dt * 4.5f))
        }

        val clamped = visualEpochProgress.coerceIn(0.01f, 1.0f)
        val surfaceY = tankBottomY + tankHeight * clamped

        if (dropletActive) {
            dropletVy -= 7.8f * dt
            dropletY += dropletVy * dt
            if (dropletY <= surfaceY) {
                dropletActive = false
                triggerSplashAtSurface(surfaceY)
            }
        }

        for (sp in splashPool) {
            if (!sp.active) continue
            sp.age += dt
            if (sp.age >= sp.maxAge) {
                sp.active = false
                continue
            }
            sp.vy -= 5.8f * dt
            sp.x += sp.vx * dt
            sp.y += sp.vy * dt
            sp.z += sp.vz * dt
            if (sp.y < surfaceY) {
                sp.active = false
            }
        }

        for (b in bubbles) {
            b.relY += (b.speed / max(0.32f, clamped)) * dt
            if (b.relY >= 0.98f) b.relY = 0.02f
        }
    }

    private fun drawBackdrop(canvas: Canvas, w: Float, h: Float) {
        bgPaint.shader = RadialGradient(
            w * 0.5f, h * 0.52f, max(w, h) * 0.65f,
            intArrayOf(
                Color.rgb(10, 36, 66),
                Color.rgb(4, 14, 28),
                Color.rgb(1, 5, 12)
            ),
            floatArrayOf(0f, 0.65f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, bgPaint)
    }

    private fun drawGlassWaterVessel(canvas: Canvas, t: Float) {
        val clamped = visualEpochProgress.coerceIn(0.01f, 1.0f)
        val waterHeight = tankHeight * clamped
        val surfaceY = tankBottomY + waterHeight

        // 1. Back Struts & Metallic Pedestal Base
        draw3DRing(canvas, tankBottomY - 0.08f, tankRadius * 1.14f, Color.rgb(32, 62, 92), 4.5f)
        draw3DRing(canvas, tankBottomY, tankRadius * 1.05f, Color.rgb(255, 209, 102), 2.4f)

        // 2. Stationary-Bottom Water Volume Body
        val leftBot = project(-waterRadius, tankBottomY, 0f, pt1)
        val rightTop = project(waterRadius, surfaceY, 0f, pt2)
        if (leftBot && rightTop) {
            val leftX = min(pt1[0], pt2[0])
            val rightX = max(pt1[0], pt2[0])
            val topY = min(pt1[1], pt2[1])
            val botY = max(pt1[1], pt2[1])

            fillPaint.shader = LinearGradient(
                0f, topY, 0f, botY,
                intArrayOf(
                    Color.argb(205, 36, 224, 250),
                    Color.argb(185, 10, 138, 198),
                    Color.argb(215, 5, 68, 120)
                ),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP
            )
            val ovalRy = (rightX - leftX) * 0.14f
            path.reset()
            path.moveTo(leftX, topY)
            path.lineTo(leftX, botY)
            path.quadTo((leftX + rightX) * 0.5f, botY + ovalRy, rightX, botY)
            path.lineTo(rightX, topY)
            path.close()
            canvas.drawPath(path, fillPaint)
            fillPaint.shader = null
        }

        // 3. Rising Micro-Bubbles Inside Water Volume
        for (b in bubbles) {
            val by = tankBottomY + b.relY * waterHeight
            val wobble = sin(t * 2.5f + b.phase) * 0.03f
            if (project(b.relX + wobble, by, b.relZ, pt1)) {
                fillPaint.color = Color.argb(155, 185, 248, 255)
                canvas.drawCircle(pt1[0], pt1[1], b.radius, fillPaint)
            }
        }

        // 4. Animated Top Water Meniscus Wave Surface & Concentric Block Ripples
        drawAnimatedWaterMeniscus(canvas, surfaceY, t)

        // 5. Falling New-Block Condensation Droplet & Splash Droplets
        if (dropletActive && project(0f, dropletY, 0f, pt1)) {
            fillPaint.color = Color.rgb(112, 248, 255)
            canvas.drawOval(pt1[0] - 4.5f, pt1[1] - 6.8f, pt1[0] + 4.5f, pt1[1] + 6.8f, fillPaint)
        }
        for (sp in splashPool) {
            if (!sp.active) continue
            if (project(sp.x, sp.y, sp.z, pt1)) {
                val alpha = ((1f - sp.age / sp.maxAge) * 230).toInt().coerceIn(0, 230)
                fillPaint.color = Color.argb(alpha, 165, 252, 255)
                canvas.drawCircle(pt1[0], pt1[1], 2.6f, fillPaint)
            }
        }

        // 6. Graduation Tick Rings (25%, 50%, 75%, 100%)
        val gradLevels = floatArrayOf(0.25f, 0.50f, 0.75f, 1.0f)
        for (pct in gradLevels) {
            val gy = tankBottomY + tankHeight * pct
            val col = if (pct == 1.0f) Color.argb(190, 255, 209, 102) else Color.argb(95, 112, 228, 255)
            draw3DRing(canvas, gy, tankRadius, col, if (pct == 0.5f || pct == 1.0f) 1.8f else 1.1f)
        }

        // 7. Outer Borosilicate Glass Cylinder Rim & Specular Highlights
        if (project(-tankRadius, tankTopY, 0f, pt1) && project(-tankRadius, tankBottomY, 0f, pt2)) {
            strokePaint.color = Color.argb(145, 130, 232, 255)
            strokePaint.strokeWidth = 2.0f
            canvas.drawLine(pt1[0], pt1[1], pt2[0], pt2[1], strokePaint)
            // Specular glass highlight streak
            strokePaint.color = Color.argb(85, 225, 250, 255)
            strokePaint.strokeWidth = 3.5f
            canvas.drawLine(pt1[0] + 10f, pt1[1] + 6f, pt2[0] + 10f, pt2[1] - 6f, strokePaint)
        }
        if (project(tankRadius, tankTopY, 0f, pt1) && project(tankRadius, tankBottomY, 0f, pt2)) {
            strokePaint.color = Color.argb(145, 130, 232, 255)
            strokePaint.strokeWidth = 2.0f
            canvas.drawLine(pt1[0], pt1[1], pt2[0], pt2[1], strokePaint)
        }

        // Top Metallic Crown & Condenser Nozzle
        draw3DRing(canvas, tankTopY + 0.06f, tankRadius * 1.08f, Color.rgb(32, 62, 92), 4.0f)
        if (project(0f, tankTopY + 0.04f, 0f, pt1)) {
            fillPaint.color = Color.rgb(255, 209, 102)
            canvas.drawCircle(pt1[0], pt1[1], 5.5f, fillPaint)
        }
    }

    private fun drawAnimatedWaterMeniscus(canvas: Canvas, surfaceY: Float, t: Float) {
        val segs = 36
        path.reset()
        for (i in 0..segs) {
            val a = (i.toFloat() / segs) * 6.283185f
            val wx = cos(a) * waterRadius
            val wz = sin(a) * waterRadius
            val wave = sin(a * 3f + t * 2.6f) * 0.022f + cos(a * 2f - t * 2.1f) * 0.016f
            val rip = sin(10f - rippleAge * 9f) * exp(-rippleAge * 1.5f) * rippleStrength * 0.035f
            if (project(wx, surfaceY + wave + rip, wz, pt1)) {
                if (i == 0) path.moveTo(pt1[0], pt1[1]) else path.lineTo(pt1[0], pt1[1])
            }
        }
        path.close()
        fillPaint.color = if (overflowGlowAlpha > 0.1f) {
            Color.argb(215, 255, 225, 120)
        } else {
            Color.argb(200, 68, 238, 255)
        }
        canvas.drawPath(path, fillPaint)

        strokePaint.color = Color.argb(235, 175, 252, 255)
        strokePaint.strokeWidth = 2.0f
        canvas.drawPath(path, strokePaint)

        // Concentric block impact ripple rings on the meniscus
        if (rippleStrength > 0.04f) {
            for (rIdx in 1..2) {
                val frac = ((rippleAge * 0.85f + rIdx * 0.32f) % 1.0f)
                val ringRad = waterRadius * frac
                val alpha = ((1f - frac) * rippleStrength * 220).toInt().coerceIn(0, 220)
                draw3DRing(canvas, surfaceY + 0.01f, ringRad, Color.argb(alpha, 210, 255, 255), 1.6f)
            }
        }
    }

    private fun draw3DRing(canvas: Canvas, y: Float, radius: Float, color: Int, strokeW: Float) {
        val segs = 32
        var prevValid = false
        var px = 0f
        var py = 0f
        strokePaint.color = color
        strokePaint.strokeWidth = strokeW
        for (i in 0..segs) {
            val a = (i.toFloat() / segs) * 6.283185f
            val wx = cos(a) * radius
            val wz = sin(a) * radius
            if (project(wx, y, wz, pt1)) {
                if (prevValid) {
                    canvas.drawLine(px, py, pt1[0], pt1[1], strokePaint)
                }
                px = pt1[0]
                py = pt1[1]
                prevValid = true
            } else {
                prevValid = false
            }
        }
    }

    private fun formatCountdown(targetMs: Long): String {
        if (!hasHydrated || targetMs <= 0L) return "AWAITING CHAIN…"
        val diffSec = max(0L, (targetMs - System.currentTimeMillis()) / 1000L)
        val totalDays = diffSec / 86400L
        val years = totalDays / 365L
        val days = totalDays % 365L
        val hrs = (diffSec % 86400L) / 3600L
        val mins = (diffSec % 3600L) / 60L
        val secs = diffSec % 60L
        return if (years > 0L) {
            String.format(Locale.US, "%dY %dD %02d:%02d:%02d", years, days, hrs, mins, secs)
        } else {
            String.format(Locale.US, "%dD %02d:%02d:%02d", totalDays, hrs, mins, secs)
        }
    }

    private fun drawWatchHud(canvas: Canvas, w: Float, h: Float) {
        // 1. Top Pill: Expected Next Primary Issuance Halving + Live Countdown
        val pillW = w * 0.78f
        val pillH = h * 0.21f
        val pillLeft = (w - pillW) * 0.5f
        val pillTop = h * 0.055f
        val pillRect = RectF(pillLeft, pillTop, pillLeft + pillW, pillTop + pillH)

        canvas.drawRoundRect(pillRect, 24f, 24f, hudBgPaint)
        canvas.drawRoundRect(pillRect, 24f, 24f, hudBorderPaint)

        // Status dot
        fillPaint.color = if (statusText.startsWith("LIVE")) Color.rgb(0, 245, 160) else Color.rgb(255, 209, 102)
        canvas.drawCircle(pillLeft + 20f, pillTop + pillH * 0.26f, 4.5f, fillPaint)

        monoPaint.textSize = w * 0.029f
        monoPaint.color = Color.rgb(255, 209, 102)
        val targetStr = if (hasHydrated) "HALVING EPOCH #$nextHalvingEpoch (EST)" else "CKB HALVING (EST)"
        canvas.drawText(targetStr, w * 0.52f, pillTop + pillH * 0.31f, monoPaint)

        titlePaint.textSize = w * 0.043f
        titlePaint.color = Color.rgb(56, 228, 255)
        canvas.drawText(formatCountdown(estimatedHalvingTimestampMs), w * 0.5f, pillTop + pillH * 0.64f, titlePaint)

        monoPaint.textSize = w * 0.027f
        monoPaint.color = Color.rgb(0, 245, 160)
        val etaDateStr = if (hasHydrated && estimatedHalvingTimestampMs > 0L) {
            "EST: ${localDateFormat.format(Date(estimatedHalvingTimestampMs))}"
        } else {
            statusText
        }
        canvas.drawText(etaDateStr, w * 0.5f, pillTop + pillH * 0.88f, monoPaint)

        // 2. Center-Bottom Vessel Callout: Current Epoch Water Level
        val pctStr = String.format(Locale.US, "%.2f%%", actualEpochProgress * 100f)
        monoPaint.textSize = w * 0.032f
        monoPaint.color = Color.rgb(238, 247, 255)
        val epLine = if (hasHydrated) {
            "EPOCH #$epochNumber · $completedBlocks/$epochLength ($pctStr)"
        } else {
            "HYDRATING EPOCH STATE…"
        }
        canvas.drawText(epLine, w * 0.5f, h * 0.77f, monoPaint)

        // 3. Bottom Pill: Halving Cycle Progress & Inspector Trigger
        val botW = w * 0.64f
        val botH = h * 0.105f
        val botLeft = (w - botW) * 0.5f
        val botTop = h * 0.81f
        val botRect = RectF(botLeft, botTop, botLeft + botW, botTop + botH)

        canvas.drawRoundRect(botRect, botH * 0.5f, botH * 0.5f, hudBgPaint)
        canvas.drawRoundRect(botRect, botH * 0.5f, botH * 0.5f, hudBorderPaint)

        monoPaint.textSize = w * 0.028f
        monoPaint.color = Color.rgb(255, 209, 102)
        val cyclePctStr = String.format(Locale.US, "%.1f%%", halvingCycleProgress * 100f)
        canvas.drawText("ERA $eraNumber · CYCLE $cyclePctStr · INSPECT", w * 0.5f, botTop + botH * 0.63f, monoPaint)
    }

    private fun drawInspectorOverlay(canvas: Canvas, w: Float, h: Float) {
        fillPaint.color = Color.argb(238, 3, 10, 22)
        canvas.drawRect(0f, 0f, w, h, fillPaint)

        titlePaint.textSize = w * 0.046f
        titlePaint.color = Color.rgb(255, 209, 102)
        canvas.drawText("CKB HALVING & EPOCH", w * 0.5f, h * 0.17f, titlePaint)

        monoPaint.textSize = w * 0.028f
        monoPaint.color = Color.rgb(0, 245, 160)
        canvas.drawText("8,760-EPOCH PRIMARY ISSUANCE", w * 0.5f, h * 0.24f, monoPaint)

        val rows = listOf(
            "EPOCH" to "#$epochNumber ($completedBlocks/$epochLength)",
            "EPOCH FILL" to String.format(Locale.US, "%.2f%% (%d left)", actualEpochProgress * 100f, remainingBlocks),
            "ERA / TARGET" to "Era $eraNumber → #$nextHalvingEpoch",
            "CYCLE / LEFT" to String.format(Locale.US, "%.2f%% (%.1f ep)", halvingCycleProgress * 100f, epochsUntilHalving),
            "EPOCH PACE" to paceLabel,
            "UTC ESTIMATE" to if (estimatedHalvingTimestampMs > 0L) utcDateFormat.format(Date(estimatedHalvingTimestampMs)) else "—",
            "CHAIN TIP" to "#$blockNumber"
        )

        var y = h * 0.34f
        monoPaint.textSize = w * 0.029f
        for ((label, value) in rows) {
            monoPaint.color = Color.rgb(138, 176, 204)
            canvas.drawText("$label: $value", w * 0.5f, y, monoPaint)
            y += h * 0.078f
        }

        monoPaint.textSize = w * 0.027f
        monoPaint.color = Color.rgb(56, 228, 255)
        canvas.drawText("TAP ANYWHERE TO CLOSE", w * 0.5f, h * 0.90f, monoPaint)
    }
}
