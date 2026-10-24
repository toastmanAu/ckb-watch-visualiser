package network.nervos.watchvisualiser.ui

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import network.nervos.watchvisualiser.data.CkbBlockData
import java.text.SimpleDateFormat
import java.util.*
import kotlin.collections.ArrayDeque
import kotlin.math.*

/**
 * Native 60 FPS Wear OS Canvas Renderer for the "Nervos CKB Mechanical Cuckoo Clock".
 *
 * Features:
 * - Handcrafted Black Forest Chalet Cuckoo Clock with 3D perspective parallax (Digital Crown rotary support)
 * - Real-time local device analogue clock (continuous hour, minute, and sweeping second hands)
 * - Continuously swinging brass pendulum and dual cast-iron pine-cone weights
 * - Internal rotating brass escapement gears visible through the skeleton dial window
 * - Hinged wooden cuckoo doors, warm interior lantern glow, and animated emerging Cuckoo Bird
 * - FIFO Block Event Queue with hash deduplication so rapid blocks never get lost
 * - Illuminated Brass Block Plaque with decoded RFC-0027 Epoch & tap-to-inspect header drawer
 */
class CuckooVisualiserView(context: Context) : View(context) {

    private val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

    // Animation & 3D perspective camera state
    private var timeSec = 0f
    private var lastFrameTime = System.nanoTime()
    private var cameraAngle = 0f
    private var targetAngle = 0f
    private var isDragging = false
    private var lastTouchX = 0f
    private var downX = 0f
    private var downY = 0f

    // Connection & Block State
    private var statusText = "CONNECTING"
    private var currentBlock: CkbBlockData? = null
    private var showDetailOverlay = false

    // FIFO Block Event Queue & Deduplication
    private val blockQueue = ArrayDeque<CkbBlockData>()
    private val seenHashes = LinkedHashSet<String>()

    // Cuckoo Sequence State (~7.2s choreography)
    private var seqActive = false
    private var seqTime = 0f
    private val seqDuration = 7.2f
    private var pendingBlock: CkbBlockData? = null
    private var midpointRevealed = false
    private var call1Fired = false
    private var call2Fired = false
    private var doorOpenFired = false
    private var doorCloseFired = false
    private var plaqueGlow = 0f

    // Gears rotation angle
    private var gearAngle = 0f

    // Reusable Paints
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.SERIF
        textAlign = Paint.Align.CENTER
    }
    private val monoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textAlign = Paint.Align.CENTER
    }

    private val romans = arrayOf("XII", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X", "XI")
    private val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    init {
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1000
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                val now = System.nanoTime()
                val dt = ((now - lastFrameTime) / 1_000_000_000f).coerceIn(0f, 0.1f)
                lastFrameTime = now
                timeSec += dt
                updateScene(dt)
                invalidate()
            }
            start()
        }
    }

    /**
     * Set initial baseline tip quietly on load without triggering the cuckoo bird.
     */
    fun onBaselineLoaded(blockData: CkbBlockData) {
        seenHashes.add(blockData.blockHash)
        if (currentBlock == null) {
            currentBlock = blockData
        }
        statusText = "LIVE"
    }

    /**
     * Enqueue a newly arrived canonical block into the FIFO queue.
     */
    fun onBlockArrived(blockData: CkbBlockData) {
        if (!blockData.isDemo) {
            if (seenHashes.contains(blockData.blockHash)) return
            seenHashes.add(blockData.blockHash)
            if (seenHashes.size > 300) {
                val first = seenHashes.iterator().next()
                seenHashes.remove(first)
            }
            statusText = "LIVE"
        }

        blockQueue.addLast(blockData)
        processNextInQueue()
    }

    fun onStatusUpdated(status: String) {
        statusText = when {
            status.startsWith("LIVE") -> "LIVE"
            status.contains("ERROR") || status.contains("TIMEOUT") -> "OFFLINE"
            else -> "CONNECTING"
        }
    }

    fun handleRotaryScroll(delta: Float) {
        targetAngle = (targetAngle + delta * 0.18f).coerceIn(-0.65f, 0.65f)
        triggerHaptic("tick")
    }

    private fun processNextInQueue() {
        if (seqActive || blockQueue.isEmpty()) return
        seqActive = true
        seqTime = 0f
        pendingBlock = blockQueue.removeFirst()
        midpointRevealed = false
        call1Fired = false
        call2Fired = false
        doorOpenFired = false
        doorCloseFired = false
        triggerHaptic("click")
    }

    private fun updateScene(dt: Float) {
        // Smoothly interpolate 3D parallax angle and gently ease toward center when idle
        cameraAngle += (targetAngle - cameraAngle) * 0.14f
        if (!isDragging) {
            targetAngle *= 0.992f
        }

        val gearSpeed = if (seqActive) 2.6f else 0.45f
        gearAngle += dt * gearSpeed

        if (plaqueGlow > 0f) {
            plaqueGlow = (plaqueGlow - dt * 0.35f).coerceAtLeast(0f)
        }

        if (!seqActive) return

        seqTime += dt
        val t = seqTime

        if (t >= 0.5f && !doorOpenFired) {
            doorOpenFired = true
            triggerHaptic("click")
        }

        if (t >= 2.05f && !call1Fired) {
            call1Fired = true
            triggerHaptic("double_click")
        }

        if (t >= 2.85f && !call2Fired) {
            call2Fired = true
            triggerHaptic("double_click")
        }

        if (t >= 2.3f && !midpointRevealed) {
            midpointRevealed = true
            pendingBlock?.let {
                currentBlock = it
                plaqueGlow = 1.0f
            }
        }

        if (t >= 6.85f && !doorCloseFired) {
            doorCloseFired = true
            triggerHaptic("tick")
        }

        if (t >= seqDuration) {
            seqActive = false
            seqTime = 0f
            pendingBlock = null
            processNextInQueue()
        }
    }

    @SuppressLint("DrawAllocation")
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f

        // 1. Warm Dark Study Timber Wall Background
        val bgGrad = RadialGradient(
            cx, cy - 20f, w * 0.65f,
            intArrayOf(Color.rgb(42, 26, 17), Color.rgb(18, 11, 7), Color.rgb(6, 4, 3)),
            floatArrayOf(0f, 0.68f, 1f),
            Shader.TileMode.CLAMP
        )
        paint.shader = bgGrad
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null

        // Subtle vertical timber wall wainscoting lines
        strokePaint.color = Color.argb(28, 212, 168, 73)
        strokePaint.strokeWidth = 1f
        val step = w / 7f
        for (i in 1..6) {
            val lx = i * step - cameraAngle * 14f
            canvas.drawLine(lx, 0f, lx, h, strokePaint)
        }

        // Parallax horizontal offset from Digital Crown / touch orbit
        val px = sin(cameraAngle) * 26f
        val vibX = if (seqActive && seqTime < 0.5f) sin(seqTime * 75f) * 2.2f else 0f
        val clockCx = cx + px + vibX
        val clockCy = cy - 18f

        // 2. Draw Hanging Chains, Pine-Cone Weights & Swinging Brass Pendulum (behind lower apron)
        drawPendulumAndWeights(canvas, clockCx, clockCy)

        // 3. Draw Handcrafted Black Forest Chalet Wooden Clock Case
        drawChaletCase(canvas, clockCx, clockCy, px)

        // 4. Draw Upper Cuckoo Alcove, Hinged Doors & Emerging Cuckoo Bird
        drawCuckooAlcoveAndBird(canvas, clockCx, clockCy - 66f)

        // 5. Draw Analogue Local-Time Clock Face & Internal Brass Gears
        drawAnalogueClockFace(canvas, clockCx, clockCy + 4f)

        // 6. Top Header Title & Connection Status Lamp
        drawTopHeader(canvas, cx)

        // 7. Bottom Illuminated Brass Block Plaque
        drawBrassPlaque(canvas, cx, h)

        // 8. Full Block Header & Decoded Epoch Detail Drawer (if tapped)
        if (showDetailOverlay) {
            drawDetailOverlay(canvas, w, h, cx, cy)
        }
    }

    private fun drawPendulumAndWeights(canvas: Canvas, cx: Float, cy: Float) {
        val anchorY = cy + 62f

        // Hanging Brass Chains & Cast-Iron Pine-Cone Weights
        val strikeOffset = if (seqActive) sin((seqTime / seqDuration) * PI.toFloat()) * 6f else 0f
        val weights = arrayOf(
             floatArrayOf(cx - 46f, anchorY + 46f + strikeOffset),
            floatArrayOf(cx + 46f, anchorY + 58f - strikeOffset * 0.6f)
        )

        strokePaint.color = Color.rgb(165, 128, 55)
        strokePaint.strokeWidth = 2.2f
        for (wt in weights) {
            val wx = wt[0]
            val wy = wt[1]
            canvas.drawLine(wx, anchorY, wx, wy, strokePaint)

            // Pine-cone weight body
            paint.color = Color.rgb(66, 58, 50)
            val conePath = Path().apply {
                moveTo(wx - 8f, wy)
                lineTo(wx + 8f, wy)
                lineTo(wx + 5f, wy + 22f)
                lineTo(wx, wy + 28f)
                lineTo(wx - 5f, wy + 22f)
                close()
            }
            canvas.drawPath(conePath, paint)
            strokePaint.color = Color.rgb(212, 168, 73)
            strokePaint.strokeWidth = 1f
            canvas.drawPath(conePath, strokePaint)
        }

        // Swinging Brass Pendulum
        val swingAmp = if (seqActive) 0.28f else 0.22f
        val pendAngle = sin(timeSec * 3.15f) * swingAmp
        val rodLen = 68f
        val bobX = cx + sin(pendAngle) * rodLen
        val bobY = anchorY + cos(pendAngle) * rodLen

        strokePaint.color = Color.rgb(198, 152, 64)
        strokePaint.strokeWidth = 3f
        canvas.drawLine(cx, anchorY, bobX, bobY, strokePaint)

        // Brass Pendulum Bob with CKB Hex Center
        val bobShader = RadialGradient(
            bobX - 4f, bobY - 4f, 16f,
            intArrayOf(Color.rgb(255, 232, 158), Color.rgb(204, 155, 58), Color.rgb(120, 86, 24)),
            floatArrayOf(0f, 0.65f, 1f),
            Shader.TileMode.CLAMP
        )
        paint.shader = bobShader
        canvas.drawCircle(bobX, bobY, 14f, paint)
        paint.shader = null

        strokePaint.color = Color.rgb(92, 62, 18)
        strokePaint.strokeWidth = 1.5f
        canvas.drawCircle(bobX, bobY, 14f, strokePaint)
    }

    private fun drawChaletCase(canvas: Canvas, cx: Float, cy: Float, parallaxX: Float) {
        // 3D Side depth extrusion for parallax effect
        val sideShift = -parallaxX * 0.38f
        paint.color = Color.rgb(34, 18, 10)
        val sideRect = RectF(cx - 76f + sideShift, cy - 62f, cx + 76f + sideShift, cy + 64f)
        canvas.drawRoundRect(sideRect, 8f, 8f, paint)

        // Main Chalet Timber Body + Gable Peak
        val bodyPath = Path().apply {
            moveTo(cx, cy - 118f)          // Roof apex
            lineTo(cx + 80f, cy - 56f)     // Right eave
            lineTo(cx + 74f, cy + 64f)     // Bottom right
            lineTo(cx - 74f, cy + 64f)     // Bottom left
            lineTo(cx - 80f, cy - 56f)     // Left eave
            close()
        }
        val woodGrad = LinearGradient(
            cx - 75f, cy - 110f, cx + 75f, cy + 64f,
            intArrayOf(Color.rgb(78, 44, 25), Color.rgb(54, 30, 16), Color.rgb(38, 20, 11)),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        paint.shader = woodGrad
        canvas.drawPath(bodyPath, paint)
        paint.shader = null

        // Carved wood border outline
        strokePaint.color = Color.rgb(142, 96, 46)
        strokePaint.strokeWidth = 2f
        canvas.drawPath(bodyPath, strokePaint)

        // Fluted Corner Pilasters (Left & Right Columns)
        paint.color = Color.rgb(92, 52, 29)
        canvas.drawRoundRect(RectF(cx - 72f, cy - 52f, cx - 58f, cy + 58f), 5f, 5f, paint)
        canvas.drawRoundRect(RectF(cx + 58f, cy - 52f, cx + 72f, cy + 58f), 5f, 5f, paint)

        // Brass Column Capitals & Bases
        paint.color = Color.rgb(212, 168, 73)
        canvas.drawRect(cx - 74f, cy - 54f, cx - 56f, cy - 48f, paint)
        canvas.drawRect(cx + 56f, cy - 54f, cx + 74f, cy - 48f, paint)
        canvas.drawRect(cx - 74f, cy + 54f, cx - 56f, cy + 60f, paint)
        canvas.drawRect(cx + 56f, cy + 54f, cx + 74f, cy + 60f, paint)

        // Steep Overhanging Shingled Roof Eaves
        val roofPath = Path().apply {
            moveTo(cx - 94f, cy - 48f)
            lineTo(cx, cy - 124f)
            lineTo(cx + 94f, cy - 48f)
            lineTo(cx + 84f, cy - 40f)
            lineTo(cx, cy - 108f)
            lineTo(cx - 84f, cy - 40f)
            close()
        }
        paint.color = Color.rgb(36, 19, 10)
        canvas.drawPath(roofPath, paint)
        strokePaint.color = Color.rgb(212, 168, 73)
        strokePaint.strokeWidth = 2.2f
        canvas.drawPath(roofPath, strokePaint)

        // Brass Spire Finial at Roof Peak
        paint.color = Color.rgb(232, 190, 94)
        canvas.drawCircle(cx, cy - 126f, 5f, paint)
        val spirePath = Path().apply {
            moveTo(cx - 3f, cy - 126f)
            lineTo(cx + 3f, cy - 126f)
            lineTo(cx, cy - 140f)
            close()
        }
        canvas.drawPath(spirePath, paint)

        // Tiered Moulded Base Cornice
        paint.color = Color.rgb(46, 25, 14)
        canvas.drawRoundRect(RectF(cx - 84f, cy + 58f, cx + 84f, cy + 70f), 5f, 5f, paint)
        strokePaint.color = Color.rgb(185, 142, 58)
        strokePaint.strokeWidth = 1.5f
        canvas.drawRoundRect(RectF(cx - 84f, cy + 58f, cx + 84f, cy + 70f), 5f, 5f, strokePaint)

        // Embedded CKB Status Lamp Jewel on Lower Cornice
        val lampColor = when (statusText) {
            "LIVE" -> Color.rgb(0, 245, 155)
            "OFFLINE" -> Color.rgb(231, 76, 60)
            else -> Color.rgb(245, 166, 35)
        }
        paint.color = lampColor
        canvas.drawCircle(cx, cy + 64f, 4.2f, paint)
    }

    private fun drawCuckooAlcoveAndBird(canvas: Canvas, cx: Float, alcoveY: Float) {
        val w = 36f
        val h = 38f
        val rect = RectF(cx - w / 2f, alcoveY - h / 2f, cx + w / 2f, alcoveY + h / 2f)

        // Calculate Door Open Progress [0..1]
        val doorProgress = if (!seqActive) {
            0f
        } else {
            when {
                seqTime < 0.5f -> 0f
                seqTime < 1.15f -> smoothStep(0.5f, 1.15f, seqTime)
                seqTime < 6.4f -> 1f
                seqTime < 7.05f -> 1f - smoothStep(6.4f, 7.05f, seqTime)
                else -> 0f
            }
        }

        // Dark Alcove Interior & Warm Amber Lantern Glow when open
        paint.color = Color.rgb(16, 9, 5)
        canvas.drawRoundRect(rect, 4f, 4f, paint)

        if (doorProgress > 0.02f) {
            val glowGrad = RadialGradient(
                cx, alcoveY, 26f,
                intArrayOf(
                    Color.argb((doorProgress * 220).toInt(), 255, 196, 85),
                    Color.argb((doorProgress * 90).toInt(), 215, 120, 28),
                    Color.TRANSPARENT
                ),
                floatArrayOf(0f, 0.65f, 1f),
                Shader.TileMode.CLAMP
            )
            paint.shader = glowGrad
            canvas.drawRoundRect(rect, 4f, 4f, paint)
            paint.shader = null
        }

        // Alcove Frame & Balcony Sill
        strokePaint.color = Color.rgb(198, 152, 64)
        strokePaint.strokeWidth = 2f
        canvas.drawRoundRect(rect, 4f, 4f, strokePaint)
        paint.color = Color.rgb(92, 52, 29)
        canvas.drawRect(cx - 23f, alcoveY + h / 2f, cx + 23f, alcoveY + h / 2f + 4.5f, paint)

        // Left & Right Hinged Wooden Shutters
        val halfW = w / 2f
        val closedWidth = halfW * (1f - doorProgress * 0.82f)
        paint.color = Color.rgb(58, 32, 18)

        // Left shutter swings outward to the left
        val leftShutter = if (doorProgress < 0.5f) {
            RectF(cx - halfW, alcoveY - h / 2f, cx - halfW + closedWidth, alcoveY + h / 2f)
        } else {
            val openExt = (doorProgress - 0.5f) * 2f * 14f
            RectF(cx - halfW - openExt, alcoveY - h / 2f, cx - halfW, alcoveY + h / 2f)
        }
        canvas.drawRect(leftShutter, paint)
        canvas.drawRect(leftShutter, strokePaint)

        // Right shutter swings outward to the right
        val rightShutter = if (doorProgress < 0.5f) {
            RectF(cx + halfW - closedWidth, alcoveY - h / 2f, cx + halfW, alcoveY + h / 2f)
        } else {
            val openExt = (doorProgress - 0.5f) * 2f * 14f
            RectF(cx + halfW, alcoveY - h / 2f, cx + halfW + openExt, alcoveY + h / 2f)
        }
        canvas.drawRect(rightShutter, paint)
        canvas.drawRect(rightShutter, strokePaint)

        // Emerging Cuckoo Bird Choreography
        if (seqActive && seqTime in 1.1f..6.45f) {
            val extend = when {
                seqTime < 1.95f -> smoothStep(1.15f, 1.95f, seqTime)
                seqTime < 5.5f -> 1f
                else -> 1f - smoothStep(5.5f, 6.4f, seqTime)
            }

            val callIntensity = when {
                seqTime in 2.05f..2.65f -> sin(((seqTime - 2.05f) / 0.6f) * PI.toFloat())
                seqTime in 2.85f..3.45f -> sin(((seqTime - 2.85f) / 0.6f) * PI.toFloat())
                else -> 0f
            }

            drawCuckooBird(canvas, cx, alcoveY + 4f, extend, callIntensity)
        }
    }

    private fun drawCuckooBird(
        canvas: Canvas,
        cx: Float,
        baseY: Float,
        extend: Float,
        callIntensity: Float
    ) {
        canvas.save()
        val scale = 0.55f + extend * 0.62f
        val birdY = baseY + extend * 5f + callIntensity * 3f
        canvas.translate(cx, birdY)
        canvas.scale(scale, scale)

        // Wings (flutter outward on cuckoo call)
        val wingAngle = 18f + callIntensity * 38f
        paint.color = Color.rgb(44, 64, 84)

        canvas.save()
        canvas.rotate(-wingAngle, -9f, -2f)
        canvas.drawOval(RectF(-19f, -8f, -6f, 8f), paint)
        canvas.restore()

        canvas.save()
        canvas.rotate(wingAngle, 9f, -2f)
        canvas.drawOval(RectF(6f, -8f, 19f, 8f), paint)
        canvas.restore()

        // Bird Body & Cream Breast Plumage
        paint.color = Color.rgb(62, 85, 108)
        canvas.drawOval(RectF(-10f, -10f, 10f, 10f), paint)
        paint.color = Color.rgb(224, 210, 188)
        canvas.drawOval(RectF(-7f, -4f, 7f, 9f), paint)

        // Head
        paint.color = Color.rgb(54, 76, 98)
        canvas.drawCircle(0f, -13f, 8.5f, paint)

        // Eyes
        paint.color = Color.rgb(245, 215, 110)
        canvas.drawCircle(-4f, -14.5f, 2.2f, paint)
        canvas.drawCircle(4f, -14.5f, 2.2f, paint)
        paint.color = Color.BLACK
        canvas.drawCircle(-4f, -14.5f, 1.1f, paint)
        canvas.drawCircle(4f, -14.5f, 1.1f, paint)

        // Articulated Upper & Lower Golden Beak
        paint.color = Color.rgb(242, 168, 48)
        val upperBeak = Path().apply {
            moveTo(-3.5f, -13f)
            lineTo(3.5f, -13f)
            lineTo(0f, -20f - callIntensity * 2f)
            close()
        }
        canvas.drawPath(upperBeak, paint)

        val lowerBeak = Path().apply {
            moveTo(-3f, -11.5f)
            lineTo(3f, -11.5f)
            lineTo(0f, -6.5f + callIntensity * 4.5f)
            close()
        }
        canvas.drawPath(lowerBeak, paint)

        // Acoustic Sound Rings & "CUCK-OO!" Callout during call
        if (callIntensity > 0.2f) {
            strokePaint.color = Color.argb((callIntensity * 220).toInt(), 255, 224, 130)
            strokePaint.strokeWidth = 1.6f
            val r = 16f + (1f - callIntensity) * 14f
            canvas.drawCircle(0f, -14f, r, strokePaint)
        }

        canvas.restore()
    }

    private fun drawAnalogueClockFace(canvas: Canvas, cx: Float, cy: Float) {
        val radius = 44f

        // Stepped Outer Brass Bezel
        val bezelGrad = LinearGradient(
            cx - radius, cy - radius, cx + radius, cy + radius,
            intArrayOf(Color.rgb(248, 216, 128), Color.rgb(198, 152, 64), Color.rgb(122, 88, 28)),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        paint.shader = bezelGrad
        canvas.drawCircle(cx, cy, radius + 5f, paint)
        paint.shader = null

        // Cream Enamel Dial Face
        paint.color = Color.rgb(244, 235, 217)
        canvas.drawCircle(cx, cy, radius, paint)

        // Inner Skeleton Window showing Rotating Brass Gears
        val skelRadius = 18f
        paint.color = Color.rgb(32, 18, 10)
        canvas.drawCircle(cx, cy, skelRadius, paint)

        drawGear(canvas, cx - 5f, cy - 3f, 9f, 8, gearAngle)
        drawGear(canvas, cx + 6f, cy + 4f, 7.5f, 7, -gearAngle * 1.35f)

        strokePaint.color = Color.rgb(184, 138, 52)
        strokePaint.strokeWidth = 1.5f
        canvas.drawCircle(cx, cy, skelRadius, strokePaint)

        // Roman Numerals & Minute Ticks
        textPaint.color = Color.rgb(38, 20, 9)
        textPaint.textSize = 7.5f
        textPaint.isFakeBoldText = true

        for (i in 0 until 12) {
            val ang = (i / 12f) * PI.toFloat() * 2f
            val tx = cx + sin(ang) * (radius - 9.5f)
            val ty = cy - cos(ang) * (radius - 9.5f) + 2.6f
            canvas.drawText(romans[i], tx, ty, textPaint)
        }

        // Real-Time Local Device Clock Hands (continuous sweep)
        val cal = Calendar.getInstance()
        val ms = cal.get(Calendar.MILLISECOND)
        val sec = cal.get(Calendar.SECOND) + ms / 1000f
        val min = cal.get(Calendar.MINUTE) + sec / 60f
        val hr = (cal.get(Calendar.HOUR) % 12) + min / 60f

        val hrAng = (hr / 12f) * PI.toFloat() * 2f
        val minAng = (min / 60f) * PI.toFloat() * 2f
        val secAng = (sec / 60f) * PI.toFloat() * 2f

        // Hour Hand
        strokePaint.color = Color.rgb(28, 15, 8)
        strokePaint.strokeWidth = 3.2f
        strokePaint.strokeCap = Paint.Cap.ROUND
        canvas.drawLine(cx, cy, cx + sin(hrAng) * 23f, cy - cos(hrAng) * 23f, strokePaint)

        // Minute Hand
        strokePaint.strokeWidth = 2.2f
        canvas.drawLine(cx, cy, cx + sin(minAng) * 33f, cy - cos(minAng) * 33f, strokePaint)

        // Crimson-Gold Sweeping Second Hand
        strokePaint.color = Color.rgb(212, 52, 38)
        strokePaint.strokeWidth = 1.3f
        canvas.drawLine(
            cx - sin(secAng) * 7f,
            cy + cos(secAng) * 7f,
            cx + sin(secAng) * 37f,
            cy - cos(secAng) * 37f,
            strokePaint
        )

        // Center Brass Arbor Cap
        paint.color = Color.rgb(228, 184, 82)
        canvas.drawCircle(cx, cy, 3.5f, paint)
    }

    private fun drawGear(canvas: Canvas, gx: Float, gy: Float, r: Float, teeth: Int, angleRad: Float) {
        canvas.save()
        canvas.translate(gx, gy)
        canvas.rotate(Math.toDegrees(angleRad.toDouble()).toFloat())

        strokePaint.color = Color.rgb(212, 168, 73)
        strokePaint.strokeWidth = 2f
        canvas.drawCircle(0f, 0f, r * 0.72f, strokePaint)

        for (i in 0 until teeth) {
            val a = (i.toFloat() / teeth) * PI.toFloat() * 2f
            val x1 = cos(a) * (r * 0.65f)
            val y1 = sin(a) * (r * 0.65f)
            val x2 = cos(a) * r
            val y2 = sin(a) * r
            canvas.drawLine(x1, y1, x2, y2, strokePaint)
        }
        canvas.restore()
    }

    private fun drawTopHeader(canvas: Canvas, cx: Float) {
        textPaint.color = Color.rgb(236, 212, 150)
        textPaint.textSize = 10.5f
        textPaint.isFakeBoldText = true
        canvas.drawText("CKB CUCKOO CLOCK", cx, 26f, textPaint)

        val subText = if (blockQueue.isNotEmpty()) {
            "$statusText • QUEUED: ${blockQueue.size}"
        } else if (seqActive) {
            "CUCKOO! NEW BLOCK"
        } else {
            statusText
        }
        monoPaint.color = if (seqActive) Color.rgb(0, 245, 155) else Color.rgb(185, 162, 118)
        monoPaint.textSize = 8.5f
        canvas.drawText(subText, cx, 38f, monoPaint)
    }

    private fun drawBrassPlaque(canvas: Canvas, cx: Float, h: Float) {
        val pw = 216f
        val ph = 46f
        val top = h - ph - 14f
        val rect = RectF(cx - pw / 2f, top, cx + pw / 2f, top + ph)

        // Brass Plaque Fill
        paint.color = Color.rgb(34, 21, 13)
        canvas.drawRoundRect(rect, 12f, 12f, paint)

        // Illumination highlight when cuckoo reveals a block
        val borderColor = if (plaqueGlow > 0.05f) {
            Color.rgb(255, 230, 142)
        } else {
            Color.rgb(198, 152, 64)
        }
        strokePaint.color = borderColor
        strokePaint.strokeWidth = if (plaqueGlow > 0.05f) 2.2f else 1.3f
        canvas.drawRoundRect(rect, 12f, 12f, strokePaint)

        val b = currentBlock
        if (b == null) {
            monoPaint.color = Color.rgb(236, 212, 150)
            monoPaint.textSize = 11f
            canvas.drawText("SYNCING CANONICAL TIP…", cx, top + 27f, monoPaint)
            return
        }

        // Line 1: Block Number + TX count (including Cellbase)
        monoPaint.color = Color.rgb(245, 216, 130)
        monoPaint.textSize = 11.5f
        monoPaint.isFakeBoldText = true
        val demoPrefix = if (b.isDemo) "[DEMO] " else ""
        canvas.drawText(
            "$demoPrefix#${String.format("%,d", b.blockNumber)} • ${b.txCount} TXs",
            cx,
            top + 18f,
            monoPaint
        )

        // Line 2: Decoded Epoch & Shortened Hash
        monoPaint.color = Color.rgb(212, 198, 172)
        monoPaint.textSize = 9f
        monoPaint.isFakeBoldText = false
        val shortHash = if (b.blockHash.length > 12) {
            "${b.blockHash.take(6)}…${b.blockHash.takeLast(4)}"
        } else b.blockHash
        val epochText = if (b.epochLength > 0) {
            "Ep ${b.epochNumber} (${b.epochIndex}/${b.epochLength}) • $shortHash"
        } else {
            "Hash $shortHash • Tap for Details"
        }
        canvas.drawText(epochText, cx, top + 34f, monoPaint)
    }

    private fun drawDetailOverlay(canvas: Canvas, w: Float, h: Float, cx: Float, cy: Float) {
        paint.color = Color.argb(236, 14, 9, 6)
        canvas.drawRect(0f, 0f, w, h, paint)

        val card = RectF(24f, 28f, w - 24f, h - 28f)
        strokePaint.color = Color.rgb(212, 168, 73)
        strokePaint.strokeWidth = 1.8f
        canvas.drawRoundRect(card, 18f, 18f, strokePaint)

        val b = currentBlock ?: return

        textPaint.color = Color.rgb(243, 210, 122)
        textPaint.textSize = 11.5f
        textPaint.isFakeBoldText = true
        canvas.drawText(
            if (b.isDemo) "DEMO BLOCK #${b.blockNumber}" else "BLOCK #${b.blockNumber}",
            cx,
            54f,
            textPaint
        )

        val lines = listOf(
            "HASH: ${b.blockHash.take(10)}…${b.blockHash.takeLast(8)}",
            "TRANSACTIONS: ${b.txCount} (incl. Cellbase)",
            "NEW CELLS: ${b.cells.size} Outputs",
            "EPOCH: ${b.epochNumber} • ${b.epochIndex} / ${b.epochLength}",
            "TIME: ${timeFormatter.format(Date(b.timestampMs))}",
            "TARGET: ${b.compactTarget} • VER: ${b.version}"
        )

        monoPaint.textSize = 9.5f
        monoPaint.color = Color.rgb(235, 224, 202)
        var yPos = 78f
        for (line in lines) {
            canvas.drawText(line, cx, yPos, monoPaint)
            yPos += 19f
        }

        textPaint.color = Color.rgb(175, 148, 102)
        textPaint.textSize = 9f
        canvas.drawText("TAP ANYWHERE TO CLOSE", cx, h - 42f, textPaint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                lastTouchX = event.x
                isDragging = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastTouchX
                if (abs(event.x - downX) > 8f) {
                    isDragging = true
                    targetAngle = (targetAngle + dx * 0.008f).coerceIn(-0.65f, 0.65f)
                    lastTouchX = event.x
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!isDragging) {
                    if (showDetailOverlay) {
                        showDetailOverlay = false
                        triggerHaptic("tick")
                    } else if (event.y > height - 72f && currentBlock != null) {
                        // Tap on Brass Plaque opens full block header & epoch inspector
                        showDetailOverlay = true
                        triggerHaptic("click")
                    } else {
                        // Tap on Clock triggers a Demo Cuckoo Sequence
                        triggerDemoCuckoo()
                    }
                }
                isDragging = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    fun triggerDemoCuckoo() {
        val nextHeight = (currentBlock?.blockNumber ?: 18_540_210L) + 1L
        val mockHash = "0x" + (1..64).map { "0123456789abcdef".random() }.joinToString("")
        val demoBlock = CkbBlockData(
            blockNumber = nextHeight,
            blockHash = mockHash,
            txCount = 4 + (Math.random() * 14).toInt(),
            cells = emptyList(),
            timestampMs = System.currentTimeMillis(),
            epochNumber = currentBlock?.epochNumber ?: 14321L,
            epochIndex = ((currentBlock?.epochIndex ?: 621L) + 1L) % 1800L,
            epochLength = currentBlock?.epochLength ?: 1800L,
            isDemo = true
        )
        onBlockArrived(demoBlock)
    }

    private fun smoothStep(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun triggerHaptic(type: String) {
        if (vibrator == null || !vibrator.hasVibrator()) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val effect = when (type) {
                    "heavy_click" -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)
                    "double_click" -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK)
                    "tick" -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
                    else -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
                }
                vibrator.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(25L)
            }
        } catch (_: Exception) {}
    }
}
