package network.nervos.watchvisualiser.ui

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import network.nervos.watchvisualiser.data.CkbBlockData
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*
import kotlin.random.Random

/**
 * Native 60fps 3D Open-Ocean Shark Raft Visualizer for Wear OS (Google Pixel Watch 3).
 *
 * Visualizes live Nervos Network CKB transactions in each newly mined block as sharks
 * circling a buoyant wooden log raft in the ocean:
 *   number of sharks = block.txCount (including the cellbase transaction)
 *
 * Directly parses and animates `assets/visualisers/ckb-sharks/ToastmanAnim.glb` (including its
 * embedded `Texture_1px` JPEG texture, 2-bone skinning weights, and 664-frame `Action` animation
 * clip) so the supplied Toastman character dances continuously on the wooden raft on the watch!
 */
class SharkRaftVisualiserView(context: Context) : View(context) {

    // --- Parsed ToastmanAnim.glb Data ---
    private class GlbVertex(
        val px: Float, val py: Float, val pz: Float,
        val nx: Float, val ny: Float, val nz: Float,
        val w0: Float, val w1: Float, // w0 = BoneBottom (Node 1), w1 = BoneTop (Node 0)
        val baseColor: Int
    )

    private class GlbAnimationData(
        val vertices: Array<GlbVertex>,
        val frameCount: Int,
        val durationSec: Float,
        val bbTx: FloatArray, val bbTy: FloatArray, val bbTz: FloatArray,
        val bbQx: FloatArray, val bbQy: FloatArray, val bbQz: FloatArray, val bbQw: FloatArray,
        val bbSx: FloatArray, val bbSy: FloatArray, val bbSz: FloatArray,
        val btTx: FloatArray, val btTy: FloatArray, val btTz: FloatArray,
        val btQx: FloatArray, val btQy: FloatArray, val btQz: FloatArray, val btQw: FloatArray,
        val btSx: FloatArray, val btSy: FloatArray, val btSz: FloatArray
    )

    @Volatile
    private var glbData: GlbAnimationData? = null
    @Volatile
    private var glbLoaded = false

    // Pre-allocated projected surflet buffer for zero-GC 60fps rendering
    private var projX = FloatArray(0)
    private var projY = FloatArray(0)
    private var projZ = FloatArray(0)
    private var projColor = IntArray(0)
    private var sortOrder = IntArray(0)

    // --- 3D Circling Shark State (1 Shark = 1 CKB Transaction incl. Cellbase) ---
    private class SharkAgent(
        val index: Int,
        val totalInBlock: Int,
        var angle: Float,
        val baseRadius: Float,
        val angularSpeed: Float,
        val swimFreq: Float,
        val swimPhase: Float,
        val scale: Float,
        val eccentricity: Float,
        val ellipseAngle: Float,
        var depthOffset: Float = -2.2f, // Emerges from below surface to 0f
        var alpha: Float = 0f,
        var retiring: Boolean = false
    )

    private val activeSharks = mutableListOf<SharkAgent>()
    private val retiringSharks = mutableListOf<SharkAgent>()

    // Wave rings triggered when a new block arrives
    private class WavePulse(var radius: Float, var alpha: Float)
    private val wavePulses = mutableListOf<WavePulse>()

    // --- Live CKB Block & Network State ---
    private var statusText = "CONNECTING"
    private var blockNumber = 0L
    private var blockHash = "—"
    private var txCount = 0
    private var cellCount = 0
    private var epochNumber = 0L
    private var epochIndex = 0L
    private var epochLength = 1800L
    private var lastBlockTimeMs = 0L
    private var showInspector = false
    private var blockBannerAlpha = 0f
    private var blockBannerText = ""

    // --- Camera & Interaction State ---
    private var camYaw = 0.25f
    private var camPitch = 0.56f
    private var camDist = 18.5f
    private var autoOrbit = true
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var touchDownX = 0f
    private var touchDownY = 0f
    private var isDragging = false
    private val startTimeNs = System.nanoTime()
    private var lastFrameSec = 0f

    // --- Reusable Paints & Paths ---
    private val skyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val oceanPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val waveLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val polyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val surfletPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val hudBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(185, 4, 15, 28)
        style = Paint.Style.FILL
    }
    private val hudBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(90, 56, 217, 255)
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }
    private val monoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(56, 217, 255)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.MONOSPACE
    }
    private val polyPath = Path()

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        loadToastmanGlbAsync()
    }

    /**
     * Parses `assets/visualisers/ckb-sharks/ToastmanAnim.glb` directly on a background thread:
     * - Extracts `Texture_1px` embedded JPEG (offset 7520 + 2157808, length 704656)
     * - Samples 3D mesh vertices (`POSITION`, `NORMAL`, `TEXCOORD_0`, `JOINTS_0`, `WEIGHTS_0`)
     * - Extracts all 664 keyframes of the `Action` animation clip (`BoneBottom` & `BoneTop`)
     */
    private fun loadToastmanGlbAsync() {
        Thread {
            try {
                val bytes = context.assets.open("visualisers/ckb-sharks/ToastmanAnim.glb").use { it.readBytes() }
                val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

                // GLB header: magic (4), version (4), length (4), chunk0Len (4), chunk0Type (4)
                val chunk0Len = buf.getInt(12)
                val binOffset = 20 + chunk0Len + 8 // 7520

                // 1. Decode embedded JPEG texture (`bufferView 6`: offset 2157808, len 704656)
                val jpegOffset = binOffset + 2157808
                val jpegLen = 704656
                val bitmap = BitmapFactory.decodeByteArray(bytes, jpegOffset, jpegLen)
                val bmpW = bitmap?.width ?: 1
                val bmpH = bitmap?.height ?: 1

                // 2. Sample vertices with stride = 10 (28,292 / 10 = 2,829 solid textured surflets)
                val totalVerts = 28292
                val stride = 10
                val sampledCount = totalVerts / stride
                val vertices = ArrayList<GlbVertex>(sampledCount)

                val posBase = binOffset + 0
                val normBase = binOffset + 339504
                val uvBase = binOffset + 679008
                val jointsBase = binOffset + 905344
                val weightsBase = binOffset + 1131680

                for (idx in 0 until totalVerts step stride) {
                    val pOff = posBase + idx * 12
                    val px = buf.getFloat(pOff)
                    val py = buf.getFloat(pOff + 4)
                    val pz = buf.getFloat(pOff + 8)

                    val nOff = normBase + idx * 12
                    val nx = buf.getFloat(nOff)
                    val ny = buf.getFloat(nOff + 4)
                    val nz = buf.getFloat(nOff + 8)

                    val uOff = uvBase + idx * 8
                    val u = buf.getFloat(uOff).coerceIn(0f, 1f)
                    val v = buf.getFloat(uOff + 4).coerceIn(0f, 1f)

                    val jOff = jointsBase + idx * 8
                    val j0 = buf.getShort(jOff).toInt() and 0xFFFF
                    val j1 = buf.getShort(jOff + 2).toInt() and 0xFFFF

                    val wOff = weightsBase + idx * 16
                    val wa = buf.getFloat(wOff)
                    val wb = buf.getFloat(wOff + 4)

                    // Joint 0 in skin is Node 1 (BoneBottom), Joint 1 is Node 0 (BoneTop)
                    var weightBottom = 0f
                    var weightTop = 0f
                    if (j0 == 0) weightBottom += wa else if (j0 == 1) weightTop += wa
                    if (j1 == 0) weightBottom += wb else if (j1 == 1) weightTop += wb
                    val wSum = weightBottom + weightTop
                    if (wSum > 0.0001f) {
                        weightBottom /= wSum
                        weightTop /= wSum
                    } else {
                        weightBottom = 1f
                    }

                    val tx = ((u * (bmpW - 1)).toInt()).coerceIn(0, bmpW - 1)
                    val ty = ((v * (bmpH - 1)).toInt()).coerceIn(0, bmpH - 1)
                    val color = bitmap?.getPixel(tx, ty) ?: Color.rgb(235, 175, 100)

                    vertices.add(
                        GlbVertex(
                            px = px, py = py, pz = pz,
                            nx = nx, ny = ny, nz = nz,
                            w0 = weightBottom, w1 = weightTop,
                            baseColor = color
                        )
                    )
                }
                bitmap?.recycle()

                // 3. Parse all 664 keyframes of `Action` animation clip
                val frames = 664
                val bbTx = FloatArray(frames); val bbTy = FloatArray(frames); val bbTz = FloatArray(frames)
                val bbQx = FloatArray(frames); val bbQy = FloatArray(frames); val bbQz = FloatArray(frames); val bbQw = FloatArray(frames)
                val bbSx = FloatArray(frames); val bbSy = FloatArray(frames); val bbSz = FloatArray(frames)

                val btTx = FloatArray(frames); val btTy = FloatArray(frames); val btTz = FloatArray(frames)
                val btQx = FloatArray(frames); val btQy = FloatArray(frames); val btQz = FloatArray(frames); val btQw = FloatArray(frames)
                val btSx = FloatArray(frames); val btSy = FloatArray(frames); val btSz = FloatArray(frames)

                val bbTOff = binOffset + 2865248
                val bbROff = binOffset + 2873216
                val bbSOff = binOffset + 2883840
                val btTOff = binOffset + 2891808
                val btROff = binOffset + 2899776
                val btSOff = binOffset + 2910400

                for (f in 0 until frames) {
                    val v3 = f * 12
                    val v4 = f * 16
                    bbTx[f] = buf.getFloat(bbTOff + v3)
                    bbTy[f] = buf.getFloat(bbTOff + v3 + 4)
                    bbTz[f] = buf.getFloat(bbTOff + v3 + 8)

                    bbQx[f] = buf.getFloat(bbROff + v4)
                    bbQy[f] = buf.getFloat(bbROff + v4 + 4)
                    bbQz[f] = buf.getFloat(bbROff + v4 + 8)
                    bbQw[f] = buf.getFloat(bbROff + v4 + 12)

                    bbSx[f] = buf.getFloat(bbSOff + v3)
                    bbSy[f] = buf.getFloat(bbSOff + v3 + 4)
                    bbSz[f] = buf.getFloat(bbSOff + v3 + 8)

                    btTx[f] = buf.getFloat(btTOff + v3)
                    btTy[f] = buf.getFloat(btTOff + v3 + 4)
                    btTz[f] = buf.getFloat(btTOff + v3 + 8)

                    btQx[f] = buf.getFloat(btROff + v4)
                    btQy[f] = buf.getFloat(btROff + v4 + 4)
                    btQz[f] = buf.getFloat(btROff + v4 + 8)
                    btQw[f] = buf.getFloat(btROff + v4 + 12)

                    btSx[f] = buf.getFloat(btSOff + v3)
                    btSy[f] = buf.getFloat(btSOff + v3 + 4)
                    btSz[f] = buf.getFloat(btSOff + v3 + 8)
                }

                val vertArray = vertices.toTypedArray()
                projX = FloatArray(vertArray.size)
                projY = FloatArray(vertArray.size)
                projZ = FloatArray(vertArray.size)
                projColor = IntArray(vertArray.size)
                sortOrder = IntArray(vertArray.size) { it }

                glbData = GlbAnimationData(
                    vertices = vertArray,
                    frameCount = frames,
                    durationSec = 11.0667f,
                    bbTx = bbTx, bbTy = bbTy, bbTz = bbTz,
                    bbQx = bbQx, bbQy = bbQy, bbQz = bbQz, bbQw = bbQw,
                    bbSx = bbSx, bbSy = bbSy, bbSz = bbSz,
                    btTx = btTx, btTy = btTy, btTz = btTz,
                    btQx = btQx, btQy = btQy, btQz = btQz, btQw = btQw,
                    btSx = btSx, btSy = btSy, btSz = btSz
                )
                glbLoaded = true
                postInvalidateOnAnimation()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }

    // --- CKB Blockchain Event Callbacks ---

    fun onBaselineLoaded(block: CkbBlockData) {
        applyBlockData(block, isNewBlockEvent = false)
    }

    fun onBlockArrived(block: CkbBlockData) {
        applyBlockData(block, isNewBlockEvent = true)
    }

    fun onStatusUpdated(status: String) {
        statusText = status
        postInvalidateOnAnimation()
    }

    private fun applyBlockData(block: CkbBlockData, isNewBlockEvent: Boolean) {
        blockNumber = block.blockNumber
        blockHash = block.blockHash
        // Strict CKB Transaction Invariant: 1 Shark = 1 Transaction (including cellbase)
        txCount = max(1, block.txCount)
        cellCount = block.cells.size
        epochNumber = block.epochNumber
        epochIndex = block.epochIndex
        epochLength = max(1L, block.epochLength)
        lastBlockTimeMs = System.currentTimeMillis()

        spawnSharksForBlock(txCount)

        if (isNewBlockEvent) {
            wavePulses.add(WavePulse(radius = 2.0f, alpha = 1.0f))
            blockBannerText = "BLOCK #$blockNumber · $txCount SHARK${if (txCount == 1) "" else "S"}"
            blockBannerAlpha = 1.0f
        }
        postInvalidateOnAnimation()
    }

    private fun spawnSharksForBlock(count: Int) {
        // Gracefully retire previous block's sharks below the ocean surface
        for (s in activeSharks) {
            s.retiring = true
            retiringSharks.add(s)
        }
        activeSharks.clear()

        val total = max(1, count)
        for (i in 0 until total) {
            val ringIdx = i / 8
            val posInRing = i % 8
            val countInRing = min(8, total - ringIdx * 8)
            val baseAngle = (posInRing.toFloat() / max(1, countInRing)) * (Math.PI.toFloat() * 2f) +
                ringIdx * 0.45f + Random.nextFloat() * 0.22f
            val baseRadius = 3.9f + ringIdx * 1.45f + Random.nextFloat() * 0.55f
            val speed = (0.48f + Random.nextFloat() * 0.22f) / sqrt(baseRadius / 4.0f)

            activeSharks.add(
                SharkAgent(
                    index = i,
                    totalInBlock = total,
                    angle = baseAngle,
                    baseRadius = baseRadius,
                    angularSpeed = speed,
                    swimFreq = 6.2f + Random.nextFloat() * 2.0f,
                    swimPhase = Random.nextFloat() * 6.28f,
                    scale = 0.88f + Random.nextFloat() * 0.26f,
                    eccentricity = 0.85f + Random.nextFloat() * 0.25f,
                    ellipseAngle = Random.nextFloat() * 3.14f,
                    depthOffset = -1.8f - Random.nextFloat() * 0.8f,
                    alpha = 0f,
                    retiring = false
                )
            )
        }
    }

    /**
     * Digital Crown / Rotary Scroll handler for orbiting around the raft & shark arena.
     */
    fun handleRotaryScroll(delta: Float) {
        camYaw += delta * 0.08f
        autoOrbit = false
        postInvalidateOnAnimation()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val w = width.toFloat()
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
                    camYaw -= dx * 0.011f
                    camPitch = (camPitch + dy * 0.007f).coerceIn(0.28f, 1.05f)
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
                        // Bottom pill tapped -> toggle Inspector overlay
                        showInspector = true
                    } else {
                        // Tap on raft/ocean -> re-enable auto-orbit & trigger shark swarm surge
                        autoOrbit = true
                        val surgeCount = 7 + Random.nextInt(8)
                        txCount = surgeCount
                        spawnSharksForBlock(surgeCount)
                        wavePulses.add(WavePulse(radius = 2.0f, alpha = 1.0f))
                        blockBannerText = "SURGE TEST · $surgeCount SHARKS ($surgeCount TXs)"
                        blockBannerAlpha = 1.0f
                    }
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    // --- Ocean Wave Math (Gerstner Height & Slope) ---

    private fun oceanHeight(x: Float, z: Float, t: Float): Float {
        var y = 0f
        y += sin(x * 0.42f + z * 0.15f + t * 1.65f) * 0.16f
        y += sin(-x * 0.22f + z * 0.48f + t * 1.35f) * 0.12f
        y += cos(x * 0.55f - z * 0.52f + t * 2.15f) * 0.06f
        return y
    }

    // --- 3D Camera Projection ---

    private var camPosX = 0f
    private var camPosY = 0f
    private var camPosZ = 0f
    private var rightX = 1f; private var rightY = 0f; private var rightZ = 0f
    private var upX = 0f; private var upY = 1f; private var upZ = 0f
    private var fwdX = 0f; private var fwdY = 0f; private var fwdZ = -1f
    private var focalLen = 380f
    private var centerX = 225f
    private var centerY = 232f

    private fun updateCamera(w: Float, h: Float) {
        centerX = w * 0.5f
        centerY = h * 0.52f
        focalLen = min(w, h) * 1.02f

        val targetY = 0.65f
        camPosX = sin(camYaw) * cos(camPitch) * camDist
        camPosY = targetY + sin(camPitch) * camDist
        camPosZ = cos(camYaw) * cos(camPitch) * camDist

        // Forward vector from camera to lookAt(0, targetY, 0)
        var fx = -camPosX
        var fy = targetY - camPosY
        var fz = -camPosZ
        val fLen = max(0.0001f, sqrt(fx * fx + fy * fy + fz * fz))
        fx /= fLen; fy /= fLen; fz /= fLen
        fwdX = fx; fwdY = fy; fwdZ = fz

        // Right = normalize(cross(fwd, worldUp(0,1,0)))
        var rx = -fz
        var ry = 0f
        var rz = fx
        val rLen = max(0.0001f, sqrt(rx * rx + rz * rz))
        rx /= rLen; rz /= rLen
        rightX = rx; rightY = ry; rightZ = rz

        // Up = cross(right, fwd)
        upX = ry * fz - rz * fy
        upY = rz * fx - rx * fz
        upZ = rx * fy - ry * fx
    }

    private fun projectPoint(wx: Float, wy: Float, wz: Float, out: FloatArray): Boolean {
        val dx = wx - camPosX
        val dy = wy - camPosY
        val dz = wz - camPosZ
        val cz = dx * fwdX + dy * fwdY + dz * fwdZ
        if (cz <= 0.5f) return false
        val cx = dx * rightX + dy * rightY + dz * rightZ
        val cy = dx * upX + dy * upY + dz * upZ
        val scale = focalLen / cz
        out[0] = centerX + cx * scale
        out[1] = centerY - cy * scale
        out[2] = cz
        return true
    }

    private val tmpPt1 = FloatArray(3)
    private val tmpPt2 = FloatArray(3)
    private val tmpPt3 = FloatArray(3)
    private val tmpPt4 = FloatArray(3)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val nowSec = (System.nanoTime() - startTimeNs) * 1e-9f
        val dt = if (lastFrameSec == 0f) 0.016f else (nowSec - lastFrameSec).coerceIn(0.001f, 0.08f)
        lastFrameSec = nowSec

        if (autoOrbit) {
            camYaw += dt * 0.14f
        }

        // Adapt camera distance to keep all active sharks comfortably framed
        val outerRing = max(0, (txCount - 1) / 8)
        val targetCamDist = (17.2f + outerRing * 2.2f).coerceIn(17.0f, 25.5f)
        camDist += (targetCamDist - camDist) * min(1f, dt * 3f)

        updateCamera(w, h)

        // 1. Draw Ocean Sky & Deep Water Background
        drawOceanBackdrop(canvas, w, h, nowSec)

        // 2. Draw Animated 3D Ocean Wave Rings & Block Surge Pulses
        drawOceanWaveGrid(canvas, nowSec, dt)

        // 3. Update & Draw Retiring + Active 3D Sharks Behind Raft (cz > camDist)
        updateSharks(dt)
        drawSharksPass(canvas, nowSec, behindRaft = true)

        // 4. Draw Buoyant 3D Wooden Log Raft + Animated ToastmanAnim.glb Character
        drawRaftAndToastman(canvas, nowSec)

        // 5. Draw Foreground 3D Sharks (cz <= camDist)
        drawSharksPass(canvas, nowSec, behindRaft = false)

        // 6. Draw Round Watch HUD & Block Banner
        drawWatchHud(canvas, w, h, dt)

        // 7. Draw Full-Screen Block Inspector Overlay if active
        if (showInspector) {
            drawInspectorOverlay(canvas, w, h)
        }

        postInvalidateOnAnimation()
    }

    private fun drawOceanBackdrop(canvas: Canvas, w: Float, h: Float, t: Float) {
        val horizonY = h * 0.22f
        // Twilight ocean sky above horizon
        skyPaint.shader = LinearGradient(
            0f, 0f, 0f, horizonY,
            intArrayOf(
                Color.rgb(5, 16, 34),
                Color.rgb(12, 42, 76),
                Color.rgb(24, 78, 122)
            ),
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, horizonY + 4f, skyPaint)

        // Deep glowing ocean gradient below horizon
        oceanPaint.shader = LinearGradient(
            0f, horizonY, 0f, h,
            intArrayOf(
                Color.rgb(14, 92, 138),
                Color.rgb(7, 58, 96),
                Color.rgb(4, 30, 56),
                Color.rgb(2, 14, 28)
            ),
            floatArrayOf(0f, 0.25f, 0.68f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, horizonY, w, h, oceanPaint)

        // Subtle golden-cyan sun reflection shimmer near top horizon
        val sunShimmer = 0.7f + 0.3f * sin(t * 1.8f)
        waveLinePaint.color = Color.argb((70 * sunShimmer).toInt(), 120, 230, 255)
        waveLinePaint.strokeWidth = 1.5f
        canvas.drawLine(w * 0.25f, horizonY, w * 0.75f, horizonY, waveLinePaint)
    }

    private fun drawOceanWaveGrid(canvas: Canvas, t: Float, dt: Float) {
        // Concentric animated 3D ocean swell rings around the raft
        val radii = floatArrayOf(2.8f, 4.2f, 5.7f, 7.3f, 9.0f, 11.0f)
        val segs = 36
        for (rIdx in radii.indices) {
            val r = radii[rIdx]
            waveLinePaint.strokeWidth = if (rIdx < 2) 1.6f else 1.2f
            for (s in 0 until segs) {
                val a0 = (s.toFloat() / segs) * (Math.PI.toFloat() * 2f)
                val a1 = ((s + 1).toFloat() / segs) * (Math.PI.toFloat() * 2f)
                val x0 = cos(a0) * r
                val z0 = sin(a0) * r
                val y0 = oceanHeight(x0, z0, t)
                val x1 = cos(a1) * r
                val z1 = sin(a1) * r
                val y1 = oceanHeight(x1, z1, t)

                if (projectPoint(x0, y0, z0, tmpPt1) && projectPoint(x1, y1, z1, tmpPt2)) {
                    val crest = ((y0 + y1) * 2.5f + 0.5f).coerceIn(0f, 1f)
                    val alpha = ((1f - rIdx * 0.13f) * (40 + crest * 85)).toInt().coerceIn(15, 140)
                    waveLinePaint.color = Color.argb(
                        alpha,
                        (40 + crest * 140).toInt(),
                        (180 + crest * 65).toInt(),
                        255
                    )
                    canvas.drawLine(tmpPt1[0], tmpPt1[1], tmpPt2[0], tmpPt2[1], waveLinePaint)
                }
            }
        }

        // Expanding CKB block arrival wave pulses
        val iter = wavePulses.iterator()
        while (iter.hasNext()) {
            val pulse = iter.next()
            pulse.radius += dt * 4.5f
            pulse.alpha -= dt * 0.42f
            if (pulse.alpha <= 0.02f) {
                iter.remove()
                continue
            }
            waveLinePaint.strokeWidth = 2.4f
            waveLinePaint.color = Color.argb((pulse.alpha * 190).toInt(), 0, 245, 155)
            val pulseSegs = 40
            for (s in 0 until pulseSegs) {
                val a0 = (s.toFloat() / pulseSegs) * 6.28318f
                val a1 = ((s + 1).toFloat() / pulseSegs) * 6.28318f
                val x0 = cos(a0) * pulse.radius
                val z0 = sin(a0) * pulse.radius
                val x1 = cos(a1) * pulse.radius
                val z1 = sin(a1) * pulse.radius
                if (projectPoint(x0, oceanHeight(x0, z0, t) + 0.05f, z0, tmpPt1) &&
                    projectPoint(x1, oceanHeight(x1, z1, t) + 0.05f, z1, tmpPt2)
                ) {
                    canvas.drawLine(tmpPt1[0], tmpPt1[1], tmpPt2[0], tmpPt2[1], waveLinePaint)
                }
            }
        }
    }

    private fun updateSharks(dt: Float) {
        for (s in activeSharks) {
            s.angle += s.angularSpeed * dt
            s.depthOffset += (0f - s.depthOffset) * min(1f, dt * 2.4f)
            s.alpha = (s.alpha + dt * 1.8f).coerceAtMost(1f)
        }
        val retIter = retiringSharks.iterator()
        while (retIter.hasNext()) {
            val s = retIter.next()
            s.angle += s.angularSpeed * 1.25f * dt
            s.depthOffset -= dt * 1.6f
            s.alpha -= dt * 0.85f
            if (s.alpha <= 0.02f || s.depthOffset < -3.0f) {
                retIter.remove()
            }
        }
    }

    private fun drawSharksPass(canvas: Canvas, t: Float, behindRaft: Boolean) {
        for (s in retiringSharks) {
            drawSingleShark(canvas, s, t, behindRaft)
        }
        for (s in activeSharks) {
            drawSingleShark(canvas, s, t, behindRaft)
        }
    }

    private fun drawSingleShark(canvas: Canvas, s: SharkAgent, t: Float, behindRaft: Boolean) {
        // Compute shark position and heading tangent along its elliptical orbit
        val rWave = s.baseRadius + sin(t * 1.1f + s.swimPhase) * 0.25f
        val lx = cos(s.angle) * rWave
        val lz = sin(s.angle) * rWave * s.eccentricity
        val ca = cos(s.ellipseAngle)
        val sa = sin(s.ellipseAngle)
        val wx = lx * ca - lz * sa
        val wz = lx * sa + lz * ca
        val wy = oceanHeight(wx, wz, t) - 0.06f + s.depthOffset

        if (!projectPoint(wx, wy, wz, tmpPt1)) return
        val isBehind = tmpPt1[2] > camDist
        if (isBehind != behindRaft) return

        // Tangent velocity direction for shark orientation
        val dtStep = 0.04f
        val nextAngle = s.angle + s.angularSpeed * dtStep
        val nlx = cos(nextAngle) * rWave
        val nlz = sin(nextAngle) * rWave * s.eccentricity
        val nwx = nlx * ca - nlz * sa
        val nwz = nlx * sa + nlz * ca
        var dirX = nwx - wx
        var dirZ = nwz - wz
        val dLen = max(0.0001f, hypot(dirX, dirZ))
        dirX /= dLen; dirZ /= dLen
        // Right perpendicular vector in XZ plane
        val perpX = -dirZ
        val perpZ = dirX

        val sc = s.scale * 0.95f
        val wag = sin(t * s.swimFreq + s.swimPhase) * 0.22f * sc
        val alphaInt = (s.alpha * 255).toInt().coerceIn(0, 255)

        // 1. White V-Wake Foam Trail on Water Surface (when near surface)
        if (s.depthOffset > -0.45f) {
            val wakeAlpha = ((1f + s.depthOffset * 2f) * s.alpha * 115).toInt().coerceIn(0, 130)
            waveLinePaint.color = Color.argb(wakeAlpha, 195, 245, 255)
            waveLinePaint.strokeWidth = 1.6f
            val finBaseX = wx - dirX * 0.1f * sc
            val finBaseZ = wz - dirZ * 0.1f * sc
            val wakeL1X = wx - (dirX * 1.35f - perpX * 0.48f) * sc
            val wakeL1Z = wz - (dirZ * 1.35f - perpZ * 0.48f) * sc
            val wakeL2X = wx - (dirX * 1.35f + perpX * 0.48f) * sc
            val wakeL2Z = wz - (dirZ * 1.35f + perpZ * 0.48f) * sc
            if (projectPoint(finBaseX, wy + 0.08f, finBaseZ, tmpPt1) &&
                projectPoint(wakeL1X, wy + 0.04f, wakeL1Z, tmpPt2) &&
                projectPoint(wakeL2X, wy + 0.04f, wakeL2Z, tmpPt3)
            ) {
                canvas.drawLine(tmpPt1[0], tmpPt1[1], tmpPt2[0], tmpPt2[1], waveLinePaint)
                canvas.drawLine(tmpPt1[0], tmpPt1[1], tmpPt3[0], tmpPt3[1], waveLinePaint)
            }
        }

        // 2. Submerged Torpedo Body (Nose -> Left Flank -> Wagging Tail -> Right Flank)
        val noseX = wx + dirX * 0.85f * sc
        val noseZ = wz + dirZ * 0.85f * sc
        val leftX = wx + perpX * 0.26f * sc
        val leftZ = wz + perpZ * 0.26f * sc
        val rightXPt = wx - perpX * 0.26f * sc
        val rightZPt = wz - perpZ * 0.26f * sc
        val tailX = wx - dirX * 0.98f * sc + perpX * wag
        val tailZ = wz - dirZ * 0.98f * sc + perpZ * wag

        if (projectPoint(noseX, wy, noseZ, tmpPt1) &&
            projectPoint(leftX, wy, leftZ, tmpPt2) &&
            projectPoint(tailX, wy + 0.04f, tailZ, tmpPt3) &&
            projectPoint(rightXPt, wy, rightZPt, tmpPt4)
        ) {
            polyPath.reset()
            polyPath.moveTo(tmpPt1[0], tmpPt1[1])
            polyPath.lineTo(tmpPt2[0], tmpPt2[1])
            polyPath.lineTo(tmpPt3[0], tmpPt3[1])
            polyPath.lineTo(tmpPt4[0], tmpPt4[1])
            polyPath.close()
            polyPaint.color = Color.argb(alphaInt, 42, 74, 102)
            canvas.drawPath(polyPath, polyPaint)

            // Pectoral fins
            val pecLX = wx + (dirX * 0.12f + perpX * 0.62f) * sc
            val pecLZ = wz + (dirZ * 0.12f + perpZ * 0.62f) * sc
            val pecRX = wx + (dirX * 0.12f - perpX * 0.62f) * sc
            val pecRZ = wz + (dirZ * 0.12f - perpZ * 0.62f) * sc
            if (projectPoint(pecLX, wy - 0.05f, pecLZ, tmpPt1) &&
                projectPoint(pecRX, wy - 0.05f, pecRZ, tmpPt3)
            ) {
                waveLinePaint.strokeWidth = 2.4f
                waveLinePaint.color = Color.argb(alphaInt, 36, 64, 90)
                canvas.drawLine(tmpPt2[0], tmpPt2[1], tmpPt1[0], tmpPt1[1], waveLinePaint)
                canvas.drawLine(tmpPt4[0], tmpPt4[1], tmpPt3[0], tmpPt3[1], waveLinePaint)
            }
        }

        // 3. Tall Curved Dorsal Fin Breaking the Ocean Surface
        val dFrontX = wx + dirX * 0.25f * sc
        val dFrontZ = wz + dirZ * 0.25f * sc
        val dTipX = wx - dirX * 0.14f * sc
        val dTipZ = wz - dirZ * 0.14f * sc
        val dBackX = wx - dirX * 0.28f * sc
        val dBackZ = wz - dirZ * 0.28f * sc
        if (projectPoint(dFrontX, wy + 0.05f, dFrontZ, tmpPt1) &&
            projectPoint(dTipX, wy + 0.62f * sc, dTipZ, tmpPt2) &&
            projectPoint(dBackX, wy + 0.05f, dBackZ, tmpPt3)
        ) {
            polyPath.reset()
            polyPath.moveTo(tmpPt1[0], tmpPt1[1])
            polyPath.lineTo(tmpPt2[0], tmpPt2[1])
            polyPath.lineTo(tmpPt3[0], tmpPt3[1])
            polyPath.close()
            polyPaint.color = Color.argb(alphaInt, 28, 52, 76)
            canvas.drawPath(polyPath, polyPaint)

            // Rim highlight on dorsal fin leading edge
            strokePaint.strokeWidth = 1.3f
            strokePaint.color = Color.argb((s.alpha * 190).toInt(), 90, 215, 255)
            canvas.drawLine(tmpPt1[0], tmpPt1[1], tmpPt2[0], tmpPt2[1], strokePaint)
        }

        // 4. Crescent Caudal Tail Fin
        val tailTipX = tailX - dirX * 0.34f * sc + perpX * wag * 1.4f
        val tailTipZ = tailZ - dirZ * 0.34f * sc + perpZ * wag * 1.4f
        if (projectPoint(tailX, wy + 0.02f, tailZ, tmpPt1) &&
            projectPoint(tailTipX, wy + 0.44f * sc, tailTipZ, tmpPt2) &&
            projectPoint(tailTipX, wy - 0.16f * sc, tailTipZ, tmpPt3)
        ) {
            polyPath.reset()
            polyPath.moveTo(tmpPt1[0], tmpPt1[1])
            polyPath.lineTo(tmpPt2[0], tmpPt2[1])
            polyPath.lineTo(tmpPt3[0], tmpPt3[1])
            polyPath.close()
            polyPaint.color = Color.argb(alphaInt, 34, 62, 88)
            canvas.drawPath(polyPath, polyPaint)
        }
    }

    /**
     * Renders the 3D buoyant wooden log raft and the animated `ToastmanAnim.glb` character riding on top.
     */
    private fun drawRaftAndToastman(canvas: Canvas, t: Float) {
        val raftY = oceanHeight(0f, 0f, t) - 0.02f
        val pitch = (oceanHeight(0f, 0.6f, t) - oceanHeight(0f, -0.6f, t)) * 0.32f
        val roll = (oceanHeight(-0.6f, 0f, t) - oceanHeight(0.6f, 0f, t)) * 0.32f

        fun raftPoint(lx: Float, ly: Float, lz: Float, out: FloatArray): Boolean {
            // Apply raft roll (around Z) and pitch (around X)
            val y1 = ly * cos(roll) - lx * sin(roll)
            val x1 = ly * sin(roll) + lx * cos(roll)
            val y2 = y1 * cos(pitch) - lz * sin(pitch)
            val z2 = y1 * sin(pitch) + lz * cos(pitch)
            return projectPoint(x1, raftY + y2, z2, out)
        }

        // 1. Draw 7 Buoyant Cedar Logs along X-axis
        val halfLen = 1.65f
        for (i in -3..3) {
            val zCenter = i * 0.46f
            val z0 = zCenter - 0.19f
            val z1 = zCenter + 0.19f
            if (raftPoint(-halfLen, 0.08f, z0, tmpPt1) &&
                raftPoint(halfLen, 0.08f, z0, tmpPt2) &&
                raftPoint(halfLen, 0.08f, z1, tmpPt3) &&
                raftPoint(-halfLen, 0.08f, z1, tmpPt4)
            ) {
                polyPath.reset()
                polyPath.moveTo(tmpPt1[0], tmpPt1[1])
                polyPath.lineTo(tmpPt2[0], tmpPt2[1])
                polyPath.lineTo(tmpPt3[0], tmpPt3[1])
                polyPath.lineTo(tmpPt4[0], tmpPt4[1])
                polyPath.close()

                val shade = if (i % 2 == 0) 0 else 14
                polyPaint.color = Color.rgb(138 - shade, 88 - shade / 2, 48)
                canvas.drawPath(polyPath, polyPaint)

                strokePaint.strokeWidth = 1f
                strokePaint.color = Color.rgb(86, 52, 26)
                canvas.drawPath(polyPath, strokePaint)
            }
        }

        // 2. Draw Hemp Rope Lashings & Cross-Planks on Deck
        for (xRope in floatArrayOf(-1.25f, 1.25f)) {
            if (raftPoint(xRope, 0.14f, -1.45f, tmpPt1) &&
                raftPoint(xRope, 0.14f, 1.45f, tmpPt2)
            ) {
                strokePaint.strokeWidth = 3.2f
                strokePaint.color = Color.rgb(198, 164, 108)
                canvas.drawLine(tmpPt1[0], tmpPt1[1], tmpPt2[0], tmpPt2[1], strokePaint)
            }
        }

        // Central platform deck planks
        if (raftPoint(-1.05f, 0.16f, -1.05f, tmpPt1) &&
            raftPoint(1.05f, 0.16f, -1.05f, tmpPt2) &&
            raftPoint(1.05f, 0.16f, 1.05f, tmpPt3) &&
            raftPoint(-1.05f, 0.16f, 1.05f, tmpPt4)
        ) {
            polyPath.reset()
            polyPath.moveTo(tmpPt1[0], tmpPt1[1])
            polyPath.lineTo(tmpPt2[0], tmpPt2[1])
            polyPath.lineTo(tmpPt3[0], tmpPt3[1])
            polyPath.lineTo(tmpPt4[0], tmpPt4[1])
            polyPath.close()
            polyPaint.color = Color.rgb(162, 108, 62)
            canvas.drawPath(polyPath, polyPaint)
            strokePaint.strokeWidth = 1.2f
            strokePaint.color = Color.rgb(105, 66, 34)
            canvas.drawPath(polyPath, strokePaint)
        }

        // 3. Warm Lantern Post on Raft Corner
        if (raftPoint(1.1f, 0.16f, -1.0f, tmpPt1) &&
            raftPoint(1.15f, 1.65f, -1.0f, tmpPt2)
        ) {
            strokePaint.strokeWidth = 2.6f
            strokePaint.color = Color.rgb(120, 78, 42)
            canvas.drawLine(tmpPt1[0], tmpPt1[1], tmpPt2[0], tmpPt2[1], strokePaint)

            // Glowing warm lantern orb
            polyPaint.color = Color.argb(95, 255, 210, 95)
            canvas.drawCircle(tmpPt2[0], tmpPt2[1], 8.5f, polyPaint)
            polyPaint.color = Color.rgb(255, 236, 150)
            canvas.drawCircle(tmpPt2[0], tmpPt2[1], 4.2f, polyPaint)
        }

        // 4. Render Animated `ToastmanAnim.glb` Character on the Raft Deck
        val data = glbData
        if (glbLoaded && data != null) {
            drawAnimatedToastmanGlb(canvas, data, t, raftY, pitch, roll)
        }
    }

    /**
     * Evaluates the 664-keyframe `Action` skeletal animation clip from `ToastmanAnim.glb`
     * (`BoneBottom` + `BoneTop`), skins the sampled 3D vertices, depth-sorts them, and
     * renders Toastman with his actual GLB texture colors on the wooden raft!
     */
    private fun drawAnimatedToastmanGlb(
        canvas: Canvas,
        data: GlbAnimationData,
        t: Float,
        raftY: Float,
        raftPitch: Float,
        raftRoll: Float
    ) {
        val animT = (t % data.durationSec) / data.durationSec
        val fIdx = ((animT * (data.frameCount - 1)).toInt()).coerceIn(0, data.frameCount - 1)

        // BoneBottom keyframe (keep horizontal root motion centered on raft deck via * 0.20f)
        val bbx = data.bbTx[fIdx] * 0.20f
        val bby = data.bbTy[fIdx]
        val bbz = data.bbTz[fIdx] * 0.20f
        val bbqx = data.bbQx[fIdx]
        val bbqy = data.bbQy[fIdx]
        val bbqz = data.bbQz[fIdx]
        val bbqw = data.bbQw[fIdx]
        val bbsx = data.bbSx[fIdx]
        val bbsy = data.bbSy[fIdx]
        val bbsz = data.bbSz[fIdx]

        // BoneTop keyframe (child of BoneBottom; bind position is y = 0.9955f)
        val bty = data.btTy[fIdx]
        val btsx = data.btSx[fIdx]
        val btsy = data.btSy[fIdx]
        val btsz = data.btSz[fIdx]

        val charScale = 2.35f
        val deckY = 0.18f

        val verts = data.vertices
        var validCount = 0

        val cr = cos(raftRoll); val sr = sin(raftRoll)
        val cp = cos(raftPitch); val sp = sin(raftPitch)

        for (i in verts.indices) {
            val v = verts[i]

            // Apply 2-bone skinning (BoneBottom + BoneTop squash/stretch/hop + BoneBottom rotation)
            val sx = v.px * (v.w0 * bbsx + v.w1 * bbsx * btsx)
            val sy = v.py * (v.w0 * bbsy + v.w1 * bbsy * btsy) + v.w1 * (bty - 0.9955f) * 0.45f
            val sz = v.pz * (v.w0 * bbsz + v.w1 * bbsz * btsz)

            // Rotate by BoneBottom quaternion (bbqx, bbqy, bbqz, bbqw)
            val tx = 2f * (bbqy * sz - bbqz * sy)
            val ty = 2f * (bbqz * sx - bbqx * sz)
            val tz = 2f * (bbqx * sy - bbqy * sx)
            val rx = sx + bbqw * tx + (bbqy * tz - bbqz * ty)
            val ry = sy + bbqw * ty + (bbqz * tx - bbqx * tz)
            val rz = sz + bbqw * tz + (bbqx * ty - bbqy * tx)

            // Orient model (-PI/2 around Y so wide Z axis faces front) and scale to raft
            val mx = (rz + bbz) * charScale
            val my = deckY + max(0f, (ry + bby)) * charScale
            val mz = -(rx + bbx) * charScale

            // Apply raft buoyancy roll & pitch
            val y1 = my * cr - mx * sr
            val x1 = my * sr + mx * cr
            val y2 = y1 * cp - mz * sp
            val z2 = y1 * sp + mz * cp

            val wx = x1
            val wy = raftY + y2
            val wz = z2

            // Project to screen
            val dx = wx - camPosX
            val dy = wy - camPosY
            val dz = wz - camPosZ
            val cz = dx * fwdX + dy * fwdY + dz * fwdZ
            if (cz <= 0.5f) continue

            val cx = dx * rightX + dy * rightY + dz * rightZ
            val cy = dx * upX + dy * upY + dz * upZ
            val pScale = focalLen / cz

            projX[validCount] = centerX + cx * pScale
            projY[validCount] = centerY - cy * pScale
            projZ[validCount] = cz

            // Directional sun lighting on vertex normal
            val light = (0.78f + 0.26f * (v.ny * 0.6f + v.nz * 0.5f - v.nx * 0.4f)).coerceIn(0.62f, 1.15f)
            val c = v.baseColor
            val rCol = ((Color.red(c) * light).toInt()).coerceIn(0, 255)
            val gCol = ((Color.green(c) * light).toInt()).coerceIn(0, 255)
            val bCol = ((Color.blue(c) * light).toInt()).coerceIn(0, 255)
            projColor[validCount] = Color.rgb(rCol, gCol, bCol)
            sortOrder[validCount] = validCount
            validCount++
        }

        // Painter's depth sort (back-to-front by projZ descending) via fast shell sort
        var gap = validCount / 2
        while (gap > 0) {
            for (i in gap until validCount) {
                val temp = sortOrder[i]
                val tempZ = projZ[temp]
                var j = i
                while (j >= gap && projZ[sortOrder[j - gap]] < tempZ) {
                    sortOrder[j] = sortOrder[j - gap]
                    j -= gap
                }
                sortOrder[j] = temp
            }
            gap /= 2
        }

        // Draw solid textured surflets of ToastmanAnim.glb
        for (i in 0 until validCount) {
            val idx = sortOrder[i]
            val radius = (focalLen / projZ[idx]) * 0.048f
            surfletPaint.color = projColor[idx]
            canvas.drawCircle(projX[idx], projY[idx], radius.coerceIn(1.5f, 3.6f), surfletPaint)
        }
    }

    private fun drawWatchHud(canvas: Canvas, w: Float, h: Float, dt: Float) {
        // Top HUD Pill (Curved-safe placement inside circular Pixel Watch 3 display)
        val topRect = RectF(w * 0.21f, h * 0.068f, w * 0.79f, h * 0.245f)
        canvas.drawRoundRect(topRect, 28f, 28f, hudBgPaint)
        canvas.drawRoundRect(topRect, 28f, 28f, hudBorderPaint)

        // Status indicator dot + status text
        val isLive = statusText.startsWith("LIVE") || statusText.startsWith("NEW")
        polyPaint.color = if (isLive) Color.rgb(0, 245, 155) else Color.rgb(255, 184, 77)
        canvas.drawCircle(w * 0.29f, h * 0.112f, 4.2f, polyPaint)

        monoPaint.textSize = w * 0.027f
        monoPaint.color = Color.rgb(175, 220, 245)
        canvas.drawText(statusText, w * 0.52f, h * 0.121f, monoPaint)

        // Primary Shark = Transaction Counter
        textPaint.textSize = w * 0.044f
        textPaint.color = Color.rgb(56, 217, 255)
        val sharkLabel = if (txCount > 0) "$txCount SHARK${if (txCount == 1) "" else "S"} ($txCount TXs)" else "SYNCING TIP..."
        canvas.drawText(sharkLabel, w * 0.5f, h * 0.178f, textPaint)

        monoPaint.textSize = w * 0.025f
        monoPaint.color = Color.rgb(0, 245, 155)
        val subLabel = if (blockNumber > 0) "INCL. CELLBASE · $cellCount CELLS" else "1 SHARK = 1 CKB TX"
        canvas.drawText(subLabel, w * 0.5f, h * 0.222f, monoPaint)

        // New-block arrival banner toast
        if (blockBannerAlpha > 0.01f) {
            blockBannerAlpha = (blockBannerAlpha - dt * 0.28f).coerceAtLeast(0f)
            val bannerRect = RectF(w * 0.18f, h * 0.27f, w * 0.82f, h * 0.34f)
            polyPaint.color = Color.argb((blockBannerAlpha * 210).toInt(), 0, 185, 120)
            canvas.drawRoundRect(bannerRect, 14f, 14f, polyPaint)
            textPaint.textSize = w * 0.031f
            textPaint.color = Color.argb((blockBannerAlpha * 255).toInt(), 255, 255, 255)
            canvas.drawText(blockBannerText, w * 0.5f, h * 0.316f, textPaint)
        }

        // Bottom Interactive Pill
        val botRect = RectF(w * 0.22f, h * 0.81f, w * 0.78f, h * 0.915f)
        canvas.drawRoundRect(botRect, 20f, 20f, hudBgPaint)
        canvas.drawRoundRect(botRect, 20f, 20f, hudBorderPaint)

        monoPaint.textSize = w * 0.027f
        monoPaint.color = Color.rgb(56, 217, 255)
        canvas.drawText("CROWN: ORBIT · TAP: INSPECT", w * 0.5f, h * 0.873f, monoPaint)
    }

    private fun drawInspectorOverlay(canvas: Canvas, w: Float, h: Float) {
        polyPaint.color = Color.argb(232, 3, 12, 24)
        canvas.drawRect(0f, 0f, w, h, polyPaint)

        textPaint.textSize = w * 0.044f
        textPaint.color = Color.rgb(56, 217, 255)
        canvas.drawText("CKB SHARK RAFT", w * 0.5f, h * 0.20f, textPaint)

        monoPaint.textSize = w * 0.030f
        monoPaint.color = Color.rgb(0, 245, 155)
        canvas.drawText("1 SHARK = 1 TX (INCL. CELLBASE)", w * 0.5f, h * 0.27f, monoPaint)

        val lines = arrayOf(
            "BLOCK   #$blockNumber",
            "SHARKS  $txCount (= $txCount TXs)",
            "CELLS   $cellCount OUTPUTS",
            "EPOCH   #$epochNumber ($epochIndex/$epochLength)",
            "MODEL   ToastmanAnim.glb (Action)",
            "HASH    ${if (blockHash.length > 16) blockHash.take(10) + "…" + blockHash.takeLast(6) else blockHash}"
        )

        monoPaint.textSize = w * 0.031f
        monoPaint.color = Color.WHITE
        var yPos = h * 0.38f
        for (line in lines) {
            canvas.drawText(line, w * 0.5f, yPos, monoPaint)
            yPos += h * 0.075f
        }

        monoPaint.textSize = w * 0.028f
        monoPaint.color = Color.rgb(160, 210, 240)
        canvas.drawText("TAP ANYWHERE TO CLOSE", w * 0.5f, h * 0.86f, monoPaint)
    }
}
