package network.nervos.watchvisualiser.ui

import android.content.Context
import android.graphics.*
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import network.nervos.watchvisualiser.data.CkbBlockData
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque
import kotlin.math.*
import kotlin.random.Random

/**
 * Native 60fps 3D Pirate Ship Broadside Visualizer for Wear OS (Google Pixel Watch 3).
 *
 * Directly loads and renders `assets/visualisers/ckb-pirate/BoatAnimated.glb` (40,651 vertices +
 * embedded 2MB JPEG texture) riding on a layered Gerstner ocean simulation:
 *   - 1 new Nervos CKB block = 1 cannon volley (FIFO queued)
 *   - 1 transaction in that block (INCLUDING cellbase) = 1 cannon shot
 *   - Exact cannonball collision against displaced wave height `getWaveHeight(x, z, t)`
 *   - Layered water impacts: vertical splash column, radial droplets, foam ring & wave perturbation
 */
class PirateShipVisualiserView(context: Context) : View(context) {

    // --- Parsed BoatAnimated.glb Vertex Data ---
    private class BoatVertex(
        val px: Float, val py: Float, val pz: Float,
        val nx: Float, val ny: Float, val nz: Float,
        val baseColor: Int
    )

    @Volatile
    private var boatVertices: Array<BoatVertex> = emptyArray()
    @Volatile
    private var glbLoaded = false

    // Pre-allocated projection buffers for zero-GC 60fps rendering
    private var projX = FloatArray(0)
    private var projY = FloatArray(0)
    private var projZ = FloatArray(0)
    private var projColor = IntArray(0)
    private var sortOrder = IntArray(0)

    // --- Boat Floating & Recoil State ---
    private val shipScale = 6.8f
    private val boatWaterlineOffset = -0.82f
    private val halfLength = 2.85f
    private val halfBeam = 1.25f

    private var currentHeave = 0f
    private var currentPitch = 0f
    private var currentRoll = 0f
    private var recoilRoll = 0f
    private var recoilRollVel = 0f
    private var recoilSwayZ = 0f
    private var recoilSwayVel = 0f
    private var oceanScrollX = 0f
    private val sailingSpeed = 2.1f

    // --- FIFO Block Volley Queue & Rolling Broadside ---
    private class BlockVolley(
        val blockNumber: Long,
        val blockHash: String,
        val totalShots: Int,
        var firedShots: Int = 0,
        val cadenceSec: Float,
        val emitterSequence: IntArray
    )

    private val volleyQueue = ArrayDeque<BlockVolley>()
    private var activeVolley: BlockVolley? = null
    private var shotTimer = 0f
    private var patternCursor = 0
    private val seenBlockHashes = LinkedHashSet<String>()

    // --- Pooled Water & Cannon Effects ---
    private class WakePatch(
        var active: Boolean = false,
        var x: Float = 0f,
        var z: Float = 0f,
        var vx: Float = 0f,
        var vz: Float = 0f,
        var age: Float = 0f,
        var maxAge: Float = 4.5f,
        var startScale: Float = 0.6f,
        var endScale: Float = 2.4f
    )

    private class SprayDrop(
        var active: Boolean = false,
        var x: Float = 0f,
        var y: Float = 0f,
        var z: Float = 0f,
        var vx: Float = 0f,
        var vy: Float = 0f,
        var vz: Float = 0f,
        var age: Float = 0f,
        var maxAge: Float = 0.9f,
        var isImpactDroplet: Boolean = false
    )

    private class SmokePuff(
        var active: Boolean = false,
        var x: Float = 0f,
        var y: Float = 0f,
        var z: Float = 0f,
        var vx: Float = 0f,
        var vy: Float = 0f,
        var vz: Float = 0f,
        var age: Float = 0f,
        var maxAge: Float = 2.4f,
        var startRadius: Float = 0.25f,
        var endRadius: Float = 1.35f,
        var isMuzzleFlash: Boolean = false
    )

    private class Cannonball(
        var active: Boolean = false,
        var x: Float = 0f,
        var y: Float = 0f,
        var z: Float = 0f,
        var vx: Float = 0f,
        var vy: Float = 0f,
        var vz: Float = 0f
    )

    private class WaterImpact(
        var active: Boolean = false,
        var x: Float = 0f,
        var z: Float = 0f,
        var startTime: Float = -999f,
        var age: Float = 0f,
        var maxAge: Float = 2.6f,
        var plumeHeight: Float = 1.8f
    )

    private val wakePool = Array(56) { WakePatch() }
    private var wakeCursor = 0
    private var wakeSpawnTimer = 0f

    private val sprayPool = Array(140) { SprayDrop() }
    private var sprayCursor = 0
    private var bowSprayAccum = 0f

    private val smokePool = Array(72) { SmokePuff() }
    private var smokeCursor = 0

    private val ballPool = Array(36) { Cannonball() }
    private var ballCursor = 0

    private val impactPool = Array(18) { WaterImpact() }
    private var impactCursor = 0

    private var muzzleFlashLightAlpha = 0f
    private var muzzleFlashX = 0f
    private var muzzleFlashZ = 0f

    // --- Live CKB HUD & Inspector State ---
    private var statusText = "CONNECTING"
    private var blockNumber = 0L
    private var blockHash = "—"
    private var txCount = 0
    private var cellCount = 0
    private var epochNumber = 0L
    private var epochIndex = 0L
    private var epochLength = 1800L
    private var volleyBannerAlpha = 0f
    private var volleyBannerTitle = ""
    private var volleyBannerSub = ""
    private var showInspector = false

    // --- Camera & Touch Controls ---
    private var camYaw = -0.32f
    private var camPitch = 0.44f
    private var camDist = 14.5f
    private var autoOrbit = true
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var touchDownX = 0f
    private var touchDownY = 0f
    private var isDragging = false
    private val startTimeNs = System.nanoTime()
    private var lastFrameSec = 0f

    // --- Reusable Paints ---
    private val skyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val oceanPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val waveLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val surfletPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val hudBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(190, 5, 16, 30)
        style = Paint.Style.FILL
    }
    private val hudBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(95, 255, 207, 92)
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 207, 92)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }
    private val monoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(56, 217, 255)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.MONOSPACE
    }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        loadBoatAnimatedGlbAsync()
    }

    /**
     * Parses `assets/visualisers/ckb-pirate/BoatAnimated.glb` directly on a background thread:
     * - Extracts `Texture_1px` embedded JPEG (`bufferView 6`: offset 7524 + 2852408, length 2011016)
     * - Samples 3D mesh vertices (`POSITION`, `NORMAL`, `TEXCOORD_0`) with exact UV color lookup
     */
    private fun loadBoatAnimatedGlbAsync() {
        Thread {
            try {
                val bytes = context.assets.open("visualisers/ckb-pirate/BoatAnimated.glb").use { it.readBytes() }
                val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

                val chunk0Len = buf.getInt(12)
                val binOffset = 20 + chunk0Len + 8 // 7524

                // Decode embedded JPEG texture (`bufferView 6`: offset 2852408, len 2011016)
                val jpegOffset = binOffset + 2852408
                val jpegLen = 2011016
                val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
                val bitmap = BitmapFactory.decodeByteArray(bytes, jpegOffset, jpegLen, opts)
                val bmpW = bitmap?.width ?: 1
                val bmpH = bitmap?.height ?: 1

                val totalVerts = 40651
                val stride = 5 // ~8,130 solid textured surflets
                val list = ArrayList<BoatVertex>(totalVerts / stride + 1)

                val posBase = binOffset + 0
                val normBase = binOffset + 487812
                val uvBase = binOffset + 975624

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

                    val tx = ((u * (bmpW - 1)).toInt()).coerceIn(0, bmpW - 1)
                    val ty = ((v * (bmpH - 1)).toInt()).coerceIn(0, bmpH - 1)
                    val col = bitmap?.getPixel(tx, ty) ?: Color.rgb(165, 115, 70)

                    list.add(BoatVertex(px, py, pz, nx, ny, nz, col))
                }
                bitmap?.recycle()

                val arr = list.toTypedArray()
                projX = FloatArray(arr.size)
                projY = FloatArray(arr.size)
                projZ = FloatArray(arr.size)
                projColor = IntArray(arr.size)
                sortOrder = IntArray(arr.size) { it }

                boatVertices = arr
                glbLoaded = true
                postInvalidateOnAnimation()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }

    // --- CKB Blockchain Callbacks ---

    fun onBaselineLoaded(block: CkbBlockData) {
        blockNumber = block.blockNumber
        blockHash = block.blockHash
        txCount = max(1, block.txCount)
        cellCount = block.cells.size
        epochNumber = block.epochNumber
        epochIndex = block.epochIndex
        epochLength = max(1L, block.epochLength)
        seenBlockHashes.add(block.blockHash)
        // Establish baseline tip without firing a volley
        postInvalidateOnAnimation()
    }

    fun onBlockArrived(block: CkbBlockData) {
        if (seenBlockHashes.contains(block.blockHash)) return
        seenBlockHashes.add(block.blockHash)
        if (seenBlockHashes.size > 200) {
            val first = seenBlockHashes.iterator().next()
            seenBlockHashes.remove(first)
        }

        cellCount = block.cells.size
        epochNumber = block.epochNumber
        epochIndex = block.epochIndex
        epochLength = max(1L, block.epochLength)

        enqueueBlockVolley(block.blockNumber, block.blockHash, max(1, block.txCount))
        postInvalidateOnAnimation()
    }

    fun onStatusUpdated(status: String) {
        statusText = status
        postInvalidateOnAnimation()
    }

    private fun enqueueBlockVolley(num: Long, hash: String, shots: Int) {
        val total = max(1, shots)
        val cadence = when {
            total <= 10 -> 0.25f
            total <= 30 -> 0.18f
            total <= 75 -> 0.115f
            else -> 0.065f
        }
        val emitterCount = 8
        val seq = IntArray(emitterCount) { it }
        when (patternCursor % 4) {
            1 -> seq.reverse()
            2 -> {
                val alt = IntArray(emitterCount)
                var l = 0; var r = emitterCount - 1; var k = 0
                while (l <= r) {
                    alt[k++] = l++
                    if (l <= r) alt[k++] = r--
                }
                for (i in 0 until emitterCount) seq[i] = alt[i]
            }
            3 -> seq.shuffle()
        }
        patternCursor++

        volleyQueue.addLast(
            BlockVolley(
                blockNumber = num,
                blockHash = hash,
                totalShots = total,
                firedShots = 0,
                cadenceSec = cadence,
                emitterSequence = seq
            )
        )
        if (activeVolley == null) {
            startNextVolley()
        }
    }

    private fun startNextVolley() {
        val next = volleyQueue.pollFirst()
        if (next == null) {
            activeVolley = null
            return
        }
        activeVolley = next
        blockNumber = next.blockNumber
        blockHash = next.blockHash
        txCount = next.totalShots
        shotTimer = 0.05f

        volleyBannerTitle = "NEW BLOCK #${next.blockNumber}"
        val sWord = if (next.totalShots == 1) "SHOT" else "SHOTS"
        volleyBannerSub = "${next.totalShots} TX · ${next.totalShots} $sWord"
        volleyBannerAlpha = 1.0f
        performHapticFeedback(HapticFeedbackConstants.CONFIRM)
    }

    // --- Digital Crown & Touch Controls ---

    fun handleRotaryScroll(delta: Float) {
        camYaw = (camYaw + delta * 0.07f).coerceIn(-1.25f, 1.25f)
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
                    camYaw = (camYaw - dx * 0.01f).coerceIn(-1.25f, 1.25f)
                    camPitch = (camPitch + dy * 0.007f).coerceIn(0.24f, 0.98f)
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
                        // Tap on ship/ocean -> trigger test cannon broadside volley!
                        autoOrbit = true
                        val testShots = intArrayOf(1, 8, 14, 20)[Random.nextInt(4)]
                        val nextNum = if (blockNumber > 0) blockNumber + 1 else 20834600L
                        enqueueBlockVolley(nextNum, "0xtest_${System.currentTimeMillis()}", testShots)
                    }
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    // --- Layered Gerstner Wave Function + Impact Perturbation ---

    fun getWaveHeight(x: Float, z: Float, t: Float): Float {
        val sx = x + oceanScrollX
        var dy = 0f
        // Primary swells
        dy += 0.36f * sin((sx * 0.88f + z * 0.47f) * 0.22f + t * 1.35f)
        dy += 0.24f * sin((sx * 0.95f - z * 0.31f) * 0.29f + t * 1.65f)
        // Secondary rolling waves
        dy += 0.14f * sin((sx * 0.52f + z * 0.85f) * 0.52f + t * 2.15f)
        dy += 0.09f * cos((-sx * 0.38f + z * 0.92f) * 0.68f + t * 2.45f)
        // Surface chop
        dy += 0.045f * sin((sx * 0.76f + z * 0.65f) * 1.35f + t * 3.30f)

        // Bounded local cannonball impact wave perturbations
        for (imp in impactPool) {
            if (!imp.active) continue
            val age = t - imp.startTime
            if (age in 0.001f..3.2f) {
                val dist = hypot(x - imp.x, z - imp.z)
                val ringR = age * 3.4f
                val dR = dist - ringR
                val band = exp(-dR * dR * 1.5f)
                val decay = exp(-age * 0.95f) * 0.24f
                dy += sin(dR * 5.2f) * band * decay
            }
        }
        return dy
    }

    // --- 3D Camera Matrix & Projection ---

    private var camPosX = 0f
    private var camPosY = 0f
    private var camPosZ = 0f
    private var rightX = 1f; private var rightY = 0f; private var rightZ = 0f
    private var upX = 0f; private var upY = 1f; private var upZ = 0f
    private var fwdX = 0f; private var fwdY = 0f; private var fwdZ = -1f
    private var focalLen = 380f
    private var centerX = 225f
    private var centerY = 232f

    private fun updateCamera(w: Float, h: Float, t: Float) {
        centerX = w * 0.5f
        centerY = h * 0.50f
        focalLen = min(w, h) * 1.04f

        if (autoOrbit) {
            val targetYaw = -0.28f + sin(t * 0.22f) * 0.18f
            camYaw += (targetYaw - camYaw) * 0.03f
        }

        // Frame both the pirate ship (at z = 0) and the foreground cannonball impact zone (z > 0)
        val lookAtX = -0.2f
        val lookAtY = 0.55f
        val lookAtZ = 1.65f

        camPosX = lookAtX + sin(camYaw) * cos(camPitch) * camDist
        camPosY = lookAtY + sin(camPitch) * camDist
        camPosZ = lookAtZ + cos(camYaw) * cos(camPitch) * camDist

        var fx = lookAtX - camPosX
        var fy = lookAtY - camPosY
        var fz = lookAtZ - camPosZ
        val fLen = max(0.0001f, sqrt(fx * fx + fy * fy + fz * fz))
        fx /= fLen; fy /= fLen; fz /= fLen
        fwdX = fx; fwdY = fy; fwdZ = fz

        var rx = -fz
        var ry = 0f
        var rz = fx
        val rLen = max(0.0001f, sqrt(rx * rx + rz * rz))
        rx /= rLen; rz /= rLen
        rightX = rx; rightY = ry; rightZ = rz

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

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val nowSec = (System.nanoTime() - startTimeNs) * 1e-9f
        val dt = if (lastFrameSec == 0f) 0.016f else (nowSec - lastFrameSec).coerceIn(0.001f, 0.06f)
        lastFrameSec = nowSec

        oceanScrollX += sailingSpeed * dt
        updateCamera(w, h, nowSec)

        // 1. Update Boat Wave Heave/Pitch/Roll, Volley Scheduler & Particle Pools
        val bowImmersion = updateBoatPhysics(dt, nowSec)
        updateVolleyScheduler(dt)
        updateEffects(dt, nowSec, bowImmersion)

        // 2. Draw Golden-Hour Maritime Sky & Deep Ocean Backdrop
        drawMaritimeBackdrop(canvas, w, h)

        // 3. Draw 3D Animated Ocean Wave Grid, V-Wake & Impact Ripples
        drawOceanAndWake(canvas, nowSec)

        // 4. Draw Supplied `BoatAnimated.glb` Pirate Ship Riding the Waves
        drawPirateShipGlb(canvas)

        // 5. Draw Foreground Cannon Smoke, Ballistic Cannonballs & Layered Water Splashes
        drawProjectilesAndSplashes(canvas, nowSec)

        // 6. Draw Round Watch HUD & Block Volley Banner
        drawWatchHud(canvas, w, h, dt)

        // 7. Draw Block Inspector Overlay if active
        if (showInspector) {
            drawInspectorOverlay(canvas, w, h)
        }

        postInvalidateOnAnimation()
    }

    private fun updateBoatPhysics(dt: Float, t: Float): Float {
        // In BoatAnimated.glb: +X is the BOW (red figurehead) and -X is the STERN
        val hCenter = getWaveHeight(0f, 0f, t)
        val hBow = getWaveHeight(halfLength, 0f, t)
        val hStern = getWaveHeight(-halfLength, 0f, t)
        val hPort = getWaveHeight(0f, -halfBeam, t)
        val hStarboard = getWaveHeight(0f, halfBeam, t)

        val targetHeave = hCenter * 0.5f + (hBow + hStern) * 0.25f
        val targetPitch = atan2(hBow - hStern, halfLength * 2f) * 0.78f
        val targetRoll = atan2(hPort - hStarboard, halfBeam * 2f) * 0.52f

        val smooth = 1f - exp(-dt * 5.5f)
        currentHeave += (targetHeave - currentHeave) * smooth
        currentPitch += (targetPitch - currentPitch) * smooth
        currentRoll += (targetRoll - currentRoll) * smooth

        // Damped cannon recoil spring
        recoilRollVel += (-recoilRoll * 18f - recoilRollVel * 5.2f) * dt
        recoilRoll += recoilRollVel * dt
        recoilSwayVel += (-recoilSwayZ * 14f - recoilSwayVel * 4.8f) * dt
        recoilSwayZ += recoilSwayVel * dt

        val bowWorldY = currentHeave + sin(currentPitch) * halfLength
        return hBow - bowWorldY
    }

    private fun updateVolleyScheduler(dt: Float) {
        val v = activeVolley ?: return
        shotTimer -= dt
        if (shotTimer <= 0f && v.firedShots < v.totalShots) {
            val emitterIdx = v.emitterSequence[v.firedShots % v.emitterSequence.size]
            fireBroadsideShot(emitterIdx)
            v.firedShots++
            shotTimer = if (v.firedShots >= v.totalShots) 1.05f else v.cadenceSec
        } else if (shotTimer <= 0f && v.firedShots >= v.totalShots) {
            startNextVolley()
        }
    }

    private fun fireBroadsideShot(emitterIdx: Int) {
        // Compute world position of broadside cannon emitter along starboard (+Z) hull
        val frac = emitterIdx / 7f
        val localX = -halfLength * 0.68f + frac * (halfLength * 1.36f)
        val localY = 0.65f
        val localZ = halfBeam * 0.85f

        val cr = cos(currentRoll + recoilRoll)
        val sr = sin(currentRoll + recoilRoll)
        val cp = cos(currentPitch)
        val sp = sin(currentPitch)

        val x1 = localX * cp - localY * sp
        val y1 = localX * sp + localY * cp
        val y2 = y1 * cr - localZ * sr
        val z2 = y1 * sr + localZ * cr

        val wx = x1
        val wy = currentHeave + y2
        val wz = recoilSwayZ + z2

        // 1. Trigger muzzle flash light on water & hull
        muzzleFlashX = wx
        muzzleFlashZ = wz + 0.5f
        muzzleFlashLightAlpha = 1.0f

        // 2. Spawn instantaneous muzzle flash + drifting cannon smoke puffs
        val flash = smokePool[smokeCursor]
        smokeCursor = (smokeCursor + 1) % smokePool.size
        flash.active = true
        flash.x = wx; flash.y = wy; flash.z = wz + 0.18f
        flash.vx = (Random.nextFloat() - 0.5f) * 0.8f
        flash.vy = 0.4f
        flash.vz = 2.6f
        flash.age = 0f
        flash.maxAge = 0.14f
        flash.startRadius = 0.32f
        flash.endRadius = 0.78f
        flash.isMuzzleFlash = true

        for (i in 0 until 4) {
            val puff = smokePool[smokeCursor]
            smokeCursor = (smokeCursor + 1) % smokePool.size
            puff.active = true
            puff.x = wx + (Random.nextFloat() - 0.5f) * 0.25f
            puff.y = wy + (Random.nextFloat() - 0.5f) * 0.15f
            puff.z = wz + 0.2f
            puff.vx = -0.45f - Random.nextFloat() * 0.55f
            puff.vy = 0.35f + Random.nextFloat() * 0.45f
            puff.vz = 1.4f + Random.nextFloat() * 1.5f
            puff.age = 0f
            puff.maxAge = 2.0f + Random.nextFloat() * 1.1f
            puff.startRadius = 0.22f
            puff.endRadius = 1.25f + Random.nextFloat() * 0.55f
            puff.isMuzzleFlash = false
        }

        // 3. Spawn world-space ballistic cannonball
        val ball = ballPool[ballCursor]
        ballCursor = (ballCursor + 1) % ballPool.size
        ball.active = true
        ball.x = wx
        ball.y = wy
        ball.z = wz + 0.2f
        val outSpeed = 4.6f + Random.nextFloat() * 2.8f
        ball.vx = (Random.nextFloat() - 0.5f) * 1.4f
        ball.vy = 3.2f + Random.nextFloat() * 1.8f
        ball.vz = outSpeed

        // 4. Add subtle boat recoil impulse
        recoilRollVel -= 0.055f
        recoilSwayVel -= 0.14f
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    private fun triggerWaterImpact(x: Float, waterY: Float, z: Float, t: Float) {
        val imp = impactPool[impactCursor]
        impactCursor = (impactCursor + 1) % impactPool.size
        imp.active = true
        imp.x = x
        imp.z = z
        imp.startTime = t
        imp.age = 0f
        imp.maxAge = 2.5f
        imp.plumeHeight = 1.5f + Random.nextFloat() * 0.7f

        // Spawn radial splash droplets
        for (i in 0 until 14) {
            val drop = sprayPool[sprayCursor]
            sprayCursor = (sprayCursor + 1) % sprayPool.size
            drop.active = true
            drop.x = x + (Random.nextFloat() - 0.5f) * 0.25f
            drop.y = waterY + 0.08f
            drop.z = z + (Random.nextFloat() - 0.5f) * 0.25f
            val a = Random.nextFloat() * 6.28318f
            val rSpd = 0.8f + Random.nextFloat() * 2.4f
            drop.vx = cos(a) * rSpd
            drop.vy = 3.2f + Random.nextFloat() * 3.4f
            drop.vz = sin(a) * rSpd
            drop.age = 0f
            drop.maxAge = 0.75f + Random.nextFloat() * 0.45f
            drop.isImpactDroplet = true
        }
    }

    private fun updateEffects(dt: Float, t: Float, bowImmersion: Float) {
        if (muzzleFlashLightAlpha > 0f) {
            muzzleFlashLightAlpha = (muzzleFlashLightAlpha - dt * 9.5f).coerceAtLeast(0f)
        }

        // 1. Spawn V-shaped wake behind ship (+X is bow, -X is stern)
        wakeSpawnTimer += dt
        if (wakeSpawnTimer >= 0.08f) {
            wakeSpawnTimer = 0f
            // Central stern churn
            spawnWake(-halfLength * 0.92f, 0f, -sailingSpeed * 1.05f, (Random.nextFloat() - 0.5f) * 0.2f, 0.65f, 2.5f)
            // Port & Starboard V-wake arms
            for (side in intArrayOf(-1, 1)) {
                spawnWake(
                    halfLength * 0.55f,
                    side * halfBeam * 0.82f,
                    -sailingSpeed * 0.96f,
                    side * (0.48f + Random.nextFloat() * 0.18f),
                    0.45f,
                    2.1f
                )
            }
        }

        for (w in wakePool) {
            if (!w.active) continue
            w.age += dt
            if (w.age >= w.maxAge) {
                w.active = false
                continue
            }
            w.x += w.vx * dt
            w.z += w.vz * dt
        }

        // 2. Spawn bow spray at +X (under the red figurehead) scaled by bow wave immersion
        val sprayRate = 22f * (0.5f + bowImmersion * 2.2f).coerceIn(0.25f, 2.4f)
        bowSprayAccum += sprayRate * dt
        val bowX = halfLength * 0.95f
        val bowY = getWaveHeight(bowX, 0f, t)
        while (bowSprayAccum >= 1f) {
            bowSprayAccum -= 1f
            val drop = sprayPool[sprayCursor]
            sprayCursor = (sprayCursor + 1) % sprayPool.size
            drop.active = true
            val side = if (Random.nextBoolean()) 1 else -1
            drop.x = bowX + (Random.nextFloat() - 0.5f) * 0.45f
            drop.y = bowY + 0.12f
            drop.z = side * (0.2f + Random.nextFloat() * 0.35f)
            drop.vx = -sailingSpeed * 0.45f
            drop.vy = 1.8f + Random.nextFloat() * 1.6f
            drop.vz = side * (0.9f + Random.nextFloat() * 1.2f)
            drop.age = 0f
            drop.maxAge = 0.65f + Random.nextFloat() * 0.35f
            drop.isImpactDroplet = false
        }

        for (d in sprayPool) {
            if (!d.active) continue
            d.age += dt
            if (d.age >= d.maxAge) {
                d.active = false
                continue
            }
            d.vy -= 9.5f * dt
            d.x += d.vx * dt
            d.y += d.vy * dt
            d.z += d.vz * dt
            if (d.vy < 0f && d.y <= getWaveHeight(d.x, d.z, t)) {
                d.active = false
            }
        }

        // 3. Update drifting world-space cannon smoke
        for (s in smokePool) {
            if (!s.active) continue
            s.age += dt
            if (s.age >= s.maxAge) {
                s.active = false
                continue
            }
            val damp = 0.35f.pow(dt)
            s.vx = s.vx * damp - 0.4f * dt
            s.vz = s.vz * damp + 0.6f * dt
            s.x += s.vx * dt
            s.y += s.vy * dt
            s.z += s.vz * dt
        }

        // 4. Update cannonballs & check exact displaced ocean wave collision
        for (b in ballPool) {
            if (!b.active) continue
            b.vy -= 9.2f * dt
            b.x += b.vx * dt
            b.y += b.vy * dt
            b.z += b.vz * dt

            val waterY = getWaveHeight(b.x, b.z, t)
            if (b.y <= waterY) {
                b.active = false
                triggerWaterImpact(b.x, waterY, b.z, t)
            }
        }

        // 5. Update water impacts
        for (imp in impactPool) {
            if (!imp.active) continue
            imp.age += dt
            if (imp.age >= imp.maxAge) {
                imp.active = false
            }
        }
    }

    private fun spawnWake(x: Float, z: Float, vx: Float, vz: Float, s0: Float, s1: Float) {
        val w = wakePool[wakeCursor]
        wakeCursor = (wakeCursor + 1) % wakePool.size
        w.active = true
        w.x = x
        w.z = z
        w.vx = vx
        w.vz = vz
        w.age = 0f
        w.maxAge = 4.2f
        w.startScale = s0
        w.endScale = s1
    }

    private fun drawMaritimeBackdrop(canvas: Canvas, w: Float, h: Float) {
        val horizonY = h * 0.25f
        skyPaint.shader = LinearGradient(
            0f, 0f, 0f, horizonY,
            intArrayOf(
                Color.rgb(6, 18, 36),
                Color.rgb(18, 48, 82),
                Color.rgb(52, 92, 128)
            ),
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, horizonY + 4f, skyPaint)

        oceanPaint.shader = LinearGradient(
            0f, horizonY, 0f, h,
            intArrayOf(
                Color.rgb(16, 78, 114),
                Color.rgb(9, 52, 84),
                Color.rgb(5, 28, 52),
                Color.rgb(3, 14, 28)
            ),
            floatArrayOf(0f, 0.28f, 0.68f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, horizonY, w, h, oceanPaint)
    }

    private fun drawOceanAndWake(canvas: Canvas, t: Float) {
        // 1. Animated 3D longitudinal & lateral wave lines
        waveLinePaint.strokeWidth = 1.3f
        for (zRow in -6..10 step 2) {
            val z = zRow * 0.95f
            var prevValid = false
            var px = 0f
            var py = 0f
            for (xStep in -10..12) {
                val x = xStep * 0.9f
                val y = getWaveHeight(x, z, t)
                if (projectPoint(x, y, z, tmpPt1)) {
                    if (prevValid) {
                        val crest = ((y + 0.4f) * 0.85f).coerceIn(0f, 1f)
                        val alpha = (35 + crest * 80).toInt().coerceIn(20, 125)
                        waveLinePaint.color = Color.argb(
                            alpha,
                            (45 + crest * 135).toInt(),
                            (165 + crest * 75).toInt(),
                            245
                        )
                        canvas.drawLine(px, py, tmpPt1[0], tmpPt1[1], waveLinePaint)
                    }
                    px = tmpPt1[0]
                    py = tmpPt1[1]
                    prevValid = true
                } else {
                    prevValid = false
                }
            }
        }

        // 2. Muzzle flash reflection glow on the water surface near the broadside
        if (muzzleFlashLightAlpha > 0.02f) {
            val wy = getWaveHeight(muzzleFlashX, muzzleFlashZ, t)
            if (projectPoint(muzzleFlashX, wy, muzzleFlashZ, tmpPt1)) {
                val r = (focalLen / tmpPt1[2]) * 1.4f
                fillPaint.color = Color.argb((muzzleFlashLightAlpha * 110).toInt(), 255, 175, 60)
                canvas.drawCircle(tmpPt1[0], tmpPt1[1], r, fillPaint)
            }
        }

        // 3. Persistent V-Shaped Foam Wake Patches
        for (w in wakePool) {
            if (!w.active) continue
            val p = w.age / w.maxAge
            val wy = getWaveHeight(w.x, w.z, t) + 0.03f
            if (projectPoint(w.x, wy, w.z, tmpPt1)) {
                val sc = w.startScale + (w.endScale - w.startScale) * p
                val rad = ((focalLen / tmpPt1[2]) * sc * 0.35f).coerceIn(2f, 26f)
                val alpha = ((1f - p).pow(1.4f) * 125).toInt().coerceIn(0, 140)
                fillPaint.color = Color.argb(alpha, 220, 246, 255)
                canvas.drawOval(
                    tmpPt1[0] - rad * 1.35f,
                    tmpPt1[1] - rad * 0.55f,
                    tmpPt1[0] + rad * 1.35f,
                    tmpPt1[1] + rad * 0.55f,
                    fillPaint
                )
            }
        }
    }

    /**
     * Renders the supplied `BoatAnimated.glb` mesh transformed by `BoatFloatRoot`
     * (wave heave, bow-to-stern pitch, port-to-starboard roll + cannon recoil).
     */
    private fun drawPirateShipGlb(canvas: Canvas) {
        val verts = boatVertices
        if (!glbLoaded || verts.isEmpty()) return

        val cr = cos(currentRoll + recoilRoll)
        val sr = sin(currentRoll + recoilRoll)
        val cp = cos(currentPitch)
        val sp = sin(currentPitch)

        var validCount = 0
        for (i in verts.indices) {
            val v = verts[i]
            val lx = v.px * shipScale
            val ly = v.py * shipScale + boatWaterlineOffset
            val lz = v.pz * shipScale

            // Pitch around Z (since ship axis is along X: +X = bow, -X = stern)
            val x1 = lx * cp - ly * sp
            val y1 = lx * sp + ly * cp
            // Roll around X
            val y2 = y1 * cr - lz * sr
            val z2 = y1 * sr + lz * cr

            val wx = x1
            val wy = currentHeave + y2
            val wz = recoilSwayZ + z2

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

            val flashBoost = muzzleFlashLightAlpha * 0.22f
            val light = (0.76f + 0.28f * (v.ny * 0.65f + v.nz * 0.45f - v.nx * 0.35f) + flashBoost).coerceIn(0.58f, 1.25f)
            val c = v.baseColor
            val rCol = ((Color.red(c) * light).toInt()).coerceIn(0, 255)
            val gCol = ((Color.green(c) * light).toInt()).coerceIn(0, 255)
            val bCol = ((Color.blue(c) * light).toInt()).coerceIn(0, 255)
            projColor[validCount] = Color.rgb(rCol, gCol, bCol)
            sortOrder[validCount] = validCount
            validCount++
        }

        // Fast shell sort back-to-front by projZ descending
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

        for (i in 0 until validCount) {
            val idx = sortOrder[i]
            val radius = (focalLen / projZ[idx]) * 0.092f
            surfletPaint.color = projColor[idx]
            canvas.drawCircle(projX[idx], projY[idx], radius.coerceIn(1.8f, 4.4f), surfletPaint)
        }
    }

    private fun drawProjectilesAndSplashes(canvas: Canvas, t: Float) {
        // 1. Expanding Foam Rings, Ripples & Vertical Splash Plumes at Impact Points
        for (imp in impactPool) {
            if (!imp.active) continue
            val p = imp.age / imp.maxAge
            val wy = getWaveHeight(imp.x, imp.z, t)
            if (!projectPoint(imp.x, wy, imp.z, tmpPt1)) continue
            val scale = focalLen / tmpPt1[2]

            // Broader outer cyan ripple ring
            val outerR = (0.6f + p * 3.8f) * scale * 0.45f
            strokePaint.strokeWidth = 1.6f
            strokePaint.color = Color.argb(((1f - p) * 140).toInt().coerceIn(0, 140), 110, 225, 255)
            canvas.drawOval(
                tmpPt1[0] - outerR,
                tmpPt1[1] - outerR * 0.42f,
                tmpPt1[0] + outerR,
                tmpPt1[1] + outerR * 0.42f,
                strokePaint
            )

            // Inner white foam ring
            if (imp.age < 1.5f) {
                val fp = imp.age / 1.5f
                val innerR = (0.35f + fp * 2.1f) * scale * 0.42f
                strokePaint.strokeWidth = 2.6f
                strokePaint.color = Color.argb(((1f - fp) * 225).toInt().coerceIn(0, 225), 245, 252, 255)
                canvas.drawOval(
                    tmpPt1[0] - innerR,
                    tmpPt1[1] - innerR * 0.42f,
                    tmpPt1[0] + innerR,
                    tmpPt1[1] + innerR * 0.42f,
                    strokePaint
                )
            }

            // Vertical whitewater splash column during first 0.85s
            if (imp.age < 0.85f) {
                val sp = imp.age / 0.85f
                val colH = sin(sp * Math.PI.toFloat()) * imp.plumeHeight
                if (projectPoint(imp.x, wy + colH, imp.z, tmpPt2)) {
                    val baseW = (0.28f + sp * 0.35f) * scale * 0.45f
                    fillPaint.color = Color.argb(((1f - sp) * 235).toInt().coerceIn(0, 235), 242, 252, 255)
                    canvas.drawLine(tmpPt1[0], tmpPt1[1], tmpPt2[0], tmpPt2[1], strokePaint.apply {
                        strokeWidth = max(3f, baseW)
                    })
                }
            }
        }

        // 2. Bow Spray & Radial Impact Droplets
        for (d in sprayPool) {
            if (!d.active) continue
            if (projectPoint(d.x, d.y, d.z, tmpPt1)) {
                val p = d.age / d.maxAge
                val alpha = ((1f - p) * 220).toInt().coerceIn(0, 220)
                fillPaint.color = Color.argb(alpha, 240, 252, 255)
                val rad = if (d.isImpactDroplet) 2.2f else 1.7f
                canvas.drawCircle(tmpPt1[0], tmpPt1[1], rad, fillPaint)
            }
        }

        // 3. Ballistic Cannonballs
        for (b in ballPool) {
            if (!b.active) continue
            if (projectPoint(b.x, b.y, b.z, tmpPt1)) {
                val r = ((focalLen / tmpPt1[2]) * 0.13f).coerceIn(2.4f, 6.5f)
                fillPaint.color = Color.rgb(28, 32, 38)
                canvas.drawCircle(tmpPt1[0], tmpPt1[1], r, fillPaint)
                fillPaint.color = Color.rgb(160, 175, 190)
                canvas.drawCircle(tmpPt1[0] - r * 0.3f, tmpPt1[1] - r * 0.3f, r * 0.32f, fillPaint)
            }
        }

        // 4. Drifting World-Space Cannon Smoke & Muzzle Flashes
        for (s in smokePool) {
            if (!s.active) continue
            if (projectPoint(s.x, s.y, s.z, tmpPt1)) {
                val p = s.age / s.maxAge
                val rad = ((s.startRadius + (s.endRadius - s.startRadius) * p) * (focalLen / tmpPt1[2]) * 0.32f).coerceIn(2.5f, 24f)
                if (s.isMuzzleFlash) {
                    fillPaint.color = Color.argb(((1f - p) * 245).toInt().coerceIn(0, 245), 255, 210, 95)
                } else {
                    val alpha = ((1f - p) * 115).toInt().coerceIn(0, 115)
                    fillPaint.color = Color.argb(alpha, 215, 224, 235)
                }
                canvas.drawCircle(tmpPt1[0], tmpPt1[1], rad, fillPaint)
            }
        }
    }

    private fun drawWatchHud(canvas: Canvas, w: Float, h: Float, dt: Float) {
        // Top HUD Pill (Curved-safe inside circular Pixel Watch 3 bezel)
        val topRect = RectF(w * 0.21f, h * 0.065f, w * 0.79f, h * 0.245f)
        canvas.drawRoundRect(topRect, 28f, 28f, hudBgPaint)
        canvas.drawRoundRect(topRect, 28f, 28f, hudBorderPaint)

        val isLive = statusText.startsWith("LIVE") || statusText.startsWith("NEW")
        fillPaint.color = if (isLive) Color.rgb(0, 245, 155) else Color.rgb(255, 184, 77)
        canvas.drawCircle(w * 0.28f, h * 0.11f, 4.2f, fillPaint)

        monoPaint.textSize = w * 0.026f
        monoPaint.color = Color.rgb(185, 222, 245)
        val topStatus = if (blockNumber > 0) "BLOCK #$blockNumber · $txCount TX" else statusText
        canvas.drawText(topStatus, w * 0.52f, h * 0.119f, monoPaint)

        // Primary Volley Firing Counter
        titlePaint.textSize = w * 0.042f
        val v = activeVolley
        if (v != null) {
            titlePaint.color = Color.rgb(255, 207, 92)
            canvas.drawText("FIRING ${v.firedShots} / ${v.totalShots}", w * 0.5f, h * 0.176f, titlePaint)
        } else {
            titlePaint.color = Color.rgb(56, 217, 255)
            val idleText = if (txCount > 0) "$txCount SHOTS · CELLBASE INCL." else "WAITING FOR BLOCK"
            canvas.drawText(idleText, w * 0.5f, h * 0.176f, titlePaint)
        }

        monoPaint.textSize = w * 0.024f
        monoPaint.color = Color.rgb(0, 245, 155)
        val qSize = volleyQueue.size
        val subLine = if (qSize > 0) "+$qSize VOLLEY QUEUED · 1 TX = 1 SHOT" else "CELLBASE INCLUDED · 1 TX = 1 SHOT"
        canvas.drawText(subLine, w * 0.5f, h * 0.221f, monoPaint)

        // New-Block Volley Banner Toast
        if (volleyBannerAlpha > 0.01f) {
            volleyBannerAlpha = (volleyBannerAlpha - dt * 0.34f).coerceAtLeast(0f)
            val bRect = RectF(w * 0.20f, h * 0.268f, w * 0.80f, h * 0.365f)
            fillPaint.color = Color.argb((volleyBannerAlpha * 215).toInt(), 8, 24, 44)
            canvas.drawRoundRect(bRect, 16f, 16f, fillPaint)
            strokePaint.strokeWidth = 1.4f
            strokePaint.color = Color.argb((volleyBannerAlpha * 220).toInt(), 255, 207, 92)
            canvas.drawRoundRect(bRect, 16f, 16f, strokePaint)

            titlePaint.textSize = w * 0.031f
            titlePaint.color = Color.argb((volleyBannerAlpha * 255).toInt(), 255, 207, 92)
            canvas.drawText(volleyBannerTitle, w * 0.5f, h * 0.312f, titlePaint)

            monoPaint.textSize = w * 0.026f
            monoPaint.color = Color.argb((volleyBannerAlpha * 255).toInt(), 0, 245, 155)
            canvas.drawText(volleyBannerSub, w * 0.5f, h * 0.348f, monoPaint)
        }

        // Bottom Interactive Pill
        val botRect = RectF(w * 0.22f, h * 0.81f, w * 0.78f, h * 0.915f)
        canvas.drawRoundRect(botRect, 20f, 20f, hudBgPaint)
        canvas.drawRoundRect(botRect, 20f, 20f, hudBorderPaint)

        monoPaint.textSize = w * 0.026f
        monoPaint.color = Color.rgb(255, 207, 92)
        canvas.drawText("TAP SHIP: VOLLEY · INSPECT", w * 0.5f, h * 0.873f, monoPaint)
    }

    private fun drawInspectorOverlay(canvas: Canvas, w: Float, h: Float) {
        fillPaint.color = Color.argb(235, 3, 12, 24)
        canvas.drawRect(0f, 0f, w, h, fillPaint)

        titlePaint.textSize = w * 0.044f
        titlePaint.color = Color.rgb(255, 207, 92)
        canvas.drawText("CKB BROADSIDE", w * 0.5f, h * 0.20f, titlePaint)

        monoPaint.textSize = w * 0.028f
        monoPaint.color = Color.rgb(0, 245, 155)
        canvas.drawText("1 BLOCK = 1 VOLLEY · 1 TX = 1 SHOT", w * 0.5f, h * 0.27f, monoPaint)

        val lines = arrayOf(
            "BLOCK   #$blockNumber",
            "SHOTS   $txCount (= $txCount TXs)",
            "QUEUED  ${volleyQueue.size} VOLLEYS",
            "EPOCH   #$epochNumber ($epochIndex/$epochLength)",
            "MODEL   BoatAnimated.glb",
            "HASH    ${if (blockHash.length > 16) blockHash.take(10) + "…" + blockHash.takeLast(6) else blockHash}"
        )

        monoPaint.textSize = w * 0.030f
        monoPaint.color = Color.WHITE
        var yPos = h * 0.38f
        for (line in lines) {
            canvas.drawText(line, w * 0.5f, yPos, monoPaint)
            yPos += h * 0.075f
        }

        monoPaint.textSize = w * 0.027f
        monoPaint.color = Color.rgb(160, 210, 240)
        canvas.drawText("TAP ANYWHERE TO CLOSE", w * 0.5f, h * 0.86f, monoPaint)
    }
}
