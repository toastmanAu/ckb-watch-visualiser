package network.nervos.watchvisualiser.ui

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import network.nervos.watchvisualiser.data.CkbBlockData
import network.nervos.watchvisualiser.data.CkbCellSpecimen
import kotlin.math.*

/**
 * High-Performance Native Wear OS (Pixel Watch 3) Canvas Renderer
 * for the CKB Cell Laboratory blockchain visualizer.
 *
 * Runs at 60 FPS natively with zero dependency on WebView, providing
 * fluid 3D procedural glass, glowing swirling Liquid of Knowledge,
 * deterministic UTXO cell organisms, and rotary crown camera orbit.
 */
class LabVisualiserView(context: Context) : View(context) {

    private var blockNumber: Long = 17_234_568L
    private var connectionStatus: String = "OBSERVATION ACTIVE"
    private var isSimulating: Boolean = false

    // Camera & Orbit state
    private var cameraOrbitAngle = 0f
    private var targetOrbitAngle = 0f
    private var lastTouchX = 0f
    private var isDragging = false

    // Timing
    private var timeSeconds = 0f
    private var lastFrameTime = System.nanoTime()

    // Choreography State
    private enum class AnimState { OBSERVING, MATERIALIZING, SWARMING, ABSORBING, SETTLED }
    private var animState = AnimState.OBSERVING
    private var stateStartTime = 0L

    // Active Cells and Liquid
    private val activeOrganisms = mutableListOf<OrganismRenderData>()
    private val liquidParticles = mutableListOf<LiquidParticle>()
    private var liquidGlowIntensity = 0.5f
    private var targetLiquidGlow = 0.5f

    // Specimen Inspection
    private var selectedSpecimen: CkbCellSpecimen? = null

    // Haptics
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        vm?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    // Paints & Shaders
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val beakerGlassPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.argb(160, 180, 230, 255)
    }
    private val beakerGraduationPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        color = Color.argb(110, 160, 220, 255)
    }
    private val liquidPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val liquidParticlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(200, 0, 255, 255)
    }
    private val organismPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val nucleusPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        color = Color.argb(220, 255, 230, 0)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textAlign = Paint.Align.CENTER
    }

    init {
        // Initialize 120 swirling suspended liquid particles
        for (i in 0 until 120) {
            liquidParticles.add(
                LiquidParticle(
                    normR = (0.1f + Math.random().toFloat() * 0.8f),
                    normAngle = (Math.random().toFloat() * PI.toFloat() * 2f),
                    normY = (0.15f + Math.random().toFloat() * 0.7f),
                    speed = (0.4f + Math.random().toFloat() * 0.8f),
                    size = (2f + Math.random().toFloat() * 2.5f)
                )
            )
        }

        // Start continuous 60fps frame loop
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1000
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                val now = System.nanoTime()
                val dt = (now - lastFrameTime) / 1_000_000_000f
                lastFrameTime = now
                timeSeconds += dt
                updateAnimation(timeSeconds, dt)
                invalidate()
            }
            start()
        }
    }

    fun onBlockArrived(blockData: CkbBlockData) {
        blockNumber = blockData.blockNumber
        connectionStatus = "NEW BLOCK #${blockData.blockNumber}"
        triggerHaptic("heavy_click")

        activeOrganisms.clear()
        val count = blockData.cells.size

        for (i in 0 until count) {
            val cell = blockData.cells[i]
            val angle = (i.toFloat() / count.toFloat()) * PI.toFloat() * 2f
            val r = 110f + (i % 3) * 25f
            val initialX = cos(angle) * r
            val initialY = -120f + (sin(i * 1.5f) * 35f)

            activeOrganisms.add(
                OrganismRenderData(
                    cell = cell,
                    x = initialX,
                    y = initialY,
                    initialX = initialX,
                    initialY = initialY,
                    color = deriveOrganismColor(cell.txHash + cell.outputIndex),
                    size = 10f + (min(1.8f, max(0.7f, (cell.dataBytes / 40f) + 1f)) * 5f),
                    pulseOffset = i * 0.4f
                )
            )
        }

        animState = AnimState.MATERIALIZING
        stateStartTime = System.currentTimeMillis()
        targetLiquidGlow = 1.4f
    }

    fun handleRotaryScroll(delta: Float) {
        targetOrbitAngle += delta * 0.25f
        triggerHaptic("tick")
    }

    private fun updateAnimation(time: Float, dt: Float) {
        // Smooth camera orbit
        cameraOrbitAngle += (targetOrbitAngle - cameraOrbitAngle) * 0.15f
        if (!isDragging) {
            targetOrbitAngle += 0.003f // gentle auto rotation
        }

        // Liquid glow interpolation
        liquidGlowIntensity += (targetLiquidGlow - liquidGlowIntensity) * 0.05f

        // Liquid suspended particles swirling motion
        for (p in liquidParticles) {
            p.normAngle += (0.015f * p.speed)
            p.normY += (sin(time * 2f + p.normAngle) * 0.0008f)
        }

        // Choreography State Machine
        val elapsed = (System.currentTimeMillis() - stateStartTime) / 1000f

        when (animState) {
            AnimState.OBSERVING -> {
                // Gentle floating
            }
            AnimState.MATERIALIZING -> {
                if (elapsed > 2.2f) {
                    animState = AnimState.SWARMING
                    stateStartTime = System.currentTimeMillis()
                    connectionStatus = "ABSORPTION ACTIVE"
                    targetLiquidGlow = 1.8f
                    triggerHaptic("double_click")
                }
            }
            AnimState.SWARMING -> {
                // Spiral toward beaker opening (center top: x=0, y=-40)
                for (org in activeOrganisms) {
                    val targetX = 0f
                    val targetY = -40f
                    val dx = targetX - org.x
                    val dy = targetY - org.y
                    val dist = hypot(dx, dy)

                    // Vortex tangential component
                    val tanX = -dy / (dist + 0.01f)
                    val tanY = dx / (dist + 0.01f)

                    org.x += (dx * 0.045f) + (tanX * 3.5f)
                    org.y += (dy * 0.045f) + (tanY * 1.5f)
                }

                if (elapsed > 3.8f) {
                    animState = AnimState.ABSORBING
                    stateStartTime = System.currentTimeMillis()
                    triggerHaptic("heavy_click")
                }
            }
            AnimState.ABSORBING -> {
                // Sink into liquid core (x=0, y=20) and shrink to zero
                val progress = min(1f, elapsed / 2.2f)
                for (org in activeOrganisms) {
                    org.x += (0f - org.x) * 0.08f
                    org.y += (20f - org.y) * 0.08f
                    org.alpha = max(0f, 1f - progress)
                }

                if (elapsed > 2.2f) {
                    animState = AnimState.SETTLED
                    stateStartTime = System.currentTimeMillis()
                    connectionStatus = "${activeOrganisms.size} CELLS ABSORBED"
                    targetLiquidGlow = 0.5f
                    activeOrganisms.clear()
                }
            }
            AnimState.SETTLED -> {
                if (elapsed > 3.0f) {
                    animState = AnimState.OBSERVING
                    connectionStatus = "AWAITING NEW CELLS"
                }
            }
        }
    }

    @SuppressLint("DrawAllocation")
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f

        // 1. Dark Clinical Laboratory Background with Radial Vignette
        val bgShader = RadialGradient(
            cx, cy, cx * 1.05f,
            intArrayOf(Color.rgb(10, 24, 38), Color.rgb(4, 9, 15), Color.rgb(0, 0, 0)),
            floatArrayOf(0f, 0.7f, 1f),
            Shader.TileMode.CLAMP
        )
        bgPaint.shader = bgShader
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        // Bench shadow & depth plane
        val benchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(90, 0, 20, 35)
        }
        canvas.drawOval(cx - 150f, cy + 90f, cx + 150f, cy + 130f, benchPaint)

        canvas.save()
        canvas.translate(cx, cy)

        // 2. Render Central Glass Beaker & Liquid of Knowledge
        drawBeakerAndLiquid(canvas)

        // 3. Render Microscopic Cell Organisms
        drawOrganisms(canvas)

        canvas.restore()

        // 4. Circular Watch HUD (Bezel-fitted for Pixel Watch 3)
        drawWatchHud(canvas, w, h)

        // 5. Specimen Card Modal Overlay (if cell tapped)
        if (selectedSpecimen != null) {
            drawSpecimenCard(canvas, w, h)
        }
    }

    private fun drawBeakerAndLiquid(canvas: Canvas) {
        val beakerWidth = 140f
        val beakerHeight = 160f
        val beakerTop = -60f
        val beakerBottom = beakerTop + beakerHeight
        val liquidTop = beakerBottom - (beakerHeight * 0.62f)

        // Perspective horizontal compression via camera orbit angle
        val cosOrbit = cos(cameraOrbitAngle)
        val xShift = sin(cameraOrbitAngle) * 8f

        // A. Liquid of Knowledge Fill & Menuiscus
        val liquidShader = LinearGradient(
            0f, liquidTop, 0f, beakerBottom,
            intArrayOf(
                Color.argb((180 * liquidGlowIntensity).toInt().coerceIn(100, 255), 0, 240, 255),
                Color.argb((220 * liquidGlowIntensity).toInt().coerceIn(120, 255), 0, 160, 210),
                Color.argb((240 * liquidGlowIntensity).toInt().coerceIn(140, 255), 2, 70, 110)
            ),
            null,
            Shader.TileMode.CLAMP
        )
        liquidPaint.shader = liquidShader

        val liquidPath = Path().apply {
            moveTo(-beakerWidth / 2f + 4f, liquidTop)
            // Curved surface meniscus
            quadTo(0f, liquidTop + 6f, beakerWidth / 2f - 4f, liquidTop)
            lineTo(beakerWidth / 2f - 6f, beakerBottom - 8f)
            // Beaker rounded bottom
            quadTo(0f, beakerBottom + 8f, -beakerWidth / 2f + 6f, beakerBottom - 8f)
            close()
        }
        canvas.drawPath(liquidPath, liquidPaint)

        // B. Swirling internal luminescent particles in liquid
        for (p in liquidParticles) {
            val rMax = (beakerWidth / 2f) - 14f
            val px = cos(p.normAngle) * (p.normR * rMax) + xShift
            val py = liquidTop + (p.normY * (beakerBottom - liquidTop - 12f))
            canvas.drawCircle(px, py, p.size, liquidParticlePaint)
        }

        // C. Transparent Glass Beaker Outline & Highlights
        val glassPath = Path().apply {
            // Left wall
            moveTo(-beakerWidth / 2f, beakerTop)
            lineTo(-beakerWidth / 2f, beakerBottom - 10f)
            // Bottom curved base
            quadTo(0f, beakerBottom + 12f, beakerWidth / 2f, beakerBottom - 10f)
            // Right wall
            lineTo(beakerWidth / 2f, beakerTop)
        }
        canvas.drawPath(glassPath, beakerGlassPaint)

        // Beaker Top Rim Ellipse
        canvas.drawOval(-beakerWidth / 2f, beakerTop - 8f, beakerWidth / 2f, beakerTop + 8f, beakerGlassPaint)

        // Glass Gloss Highlight (Vertical reflection streak)
        val glossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            color = Color.argb(120, 255, 255, 255)
        }
        canvas.drawLine(-beakerWidth / 2f + 8f, beakerTop + 14f, -beakerWidth / 2f + 8f, beakerBottom - 18f, glossPaint)

        // Measurement Graduations on Glass
        for (i in 1..4) {
            val gradY = beakerBottom - (beakerHeight / 5f) * i
            canvas.drawLine(beakerWidth / 2f - 24f, gradY, beakerWidth / 2f - 4f, gradY, beakerGraduationPaint)
        }
    }

    private fun drawOrganisms(canvas: Canvas) {
        for (org in activeOrganisms) {
            val alphaInt = (org.alpha * 255).toInt().coerceIn(0, 255)
            if (alphaInt <= 0) continue

            // Breathing / pulsation
            val pulse = 1f + sin(timeSeconds * 4f + org.pulseOffset) * 0.12f
            val curSize = org.size * pulse

            // Outer Translucent Organic Membrane
            organismPaint.color = Color.argb(alphaInt, Color.red(org.color), Color.green(org.color), Color.blue(org.color))
            canvas.drawCircle(org.x, org.y, curSize, organismPaint)

            // Inner Glowing Nucleus
            nucleusPaint.color = Color.argb(alphaInt, 255, 255, 255)
            canvas.drawCircle(org.x, org.y, curSize * 0.45f, nucleusPaint)

            // Distinctive Type Script Ring
            if (org.cell.hasTypeScript) {
                ringPaint.color = Color.argb(alphaInt, 255, 230, 0)
                canvas.drawCircle(org.x, org.y, curSize * 1.35f, ringPaint)
            }

            // Internal Data granules
            if (org.cell.dataBytes > 0) {
                val gPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.argb(alphaInt, 0, 255, 170)
                }
                canvas.drawCircle(org.x + curSize * 0.4f, org.y - curSize * 0.2f, 2f, gPaint)
                canvas.drawCircle(org.x - curSize * 0.35f, org.y + curSize * 0.3f, 2f, gPaint)
            }
        }
    }

    private fun drawWatchHud(canvas: Canvas, w: Float, h: Float) {
        val cx = w / 2f

        // Top arc info
        textPaint.color = Color.rgb(0, 240, 255)
        textPaint.textSize = 13f
        textPaint.isFakeBoldText = true
        canvas.drawText("CKB CELL LAB", cx, 36f, textPaint)

        textPaint.color = Color.rgb(220, 240, 255)
        textPaint.textSize = 14f
        canvas.drawText("#$blockNumber", cx, 54f, textPaint)

        // Bottom status pill
        val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(190, 8, 22, 36)
        }
        val pillStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.5f
            color = Color.argb(120, 0, 240, 255)
        }
        val pillRect = RectF(cx - 95f, h - 52f, cx + 95f, h - 22f)
        canvas.drawRoundRect(pillRect, 15f, 15f, pillPaint)
        canvas.drawRoundRect(pillRect, 15f, 15f, pillStroke)

        // Status dot
        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (animState != AnimState.OBSERVING) Color.rgb(0, 255, 170) else Color.rgb(0, 240, 255)
        }
        canvas.drawCircle(cx - 75f, h - 37f, 4f, dotPaint)

        textPaint.color = Color.rgb(200, 230, 245)
        textPaint.textSize = 10.5f
        textPaint.isFakeBoldText = false
        val hudMsg = if (activeOrganisms.isNotEmpty()) "${activeOrganisms.size} NEW CELLS" else connectionStatus
        canvas.drawText(hudMsg, cx + 5f, h - 33.5f, textPaint)
    }

    private fun drawSpecimenCard(canvas: Canvas, w: Float, h: Float) {
        val s = selectedSpecimen ?: return
        val cx = w / 2f
        val cy = h / 2f

        // Dim backdrop
        val dimPaint = Paint().apply { color = Color.argb(190, 0, 0, 0) }
        canvas.drawRect(0f, 0f, w, h, dimPaint)

        // Circular Card
        val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(8, 20, 32)
        }
        val cardStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            color = Color.rgb(0, 240, 255)
        }
        canvas.drawCircle(cx, cy, 180f, cardPaint)
        canvas.drawCircle(cx, cy, 180f, cardStroke)

        // Content
        textPaint.color = Color.rgb(0, 240, 255)
        textPaint.textSize = 12f
        textPaint.isFakeBoldText = true
        canvas.drawText("CELL SPECIMEN", cx, cy - 110f, textPaint)

        textPaint.color = Color.rgb(130, 170, 190)
        textPaint.textSize = 9f
        textPaint.isFakeBoldText = false
        canvas.drawText("TX HASH", cx, cy - 80f, textPaint)

        textPaint.color = Color.WHITE
        textPaint.textSize = 10f
        val shortTx = if (s.txHash.length > 18) s.txHash.take(8) + "..." + s.txHash.takeLast(6) else s.txHash
        canvas.drawText(shortTx, cx, cy - 65f, textPaint)

        textPaint.color = Color.rgb(130, 170, 190)
        textPaint.textSize = 9f
        canvas.drawText("OUTPUT INDEX", cx, cy - 38f, textPaint)

        textPaint.color = Color.WHITE
        textPaint.textSize = 12f
        textPaint.isFakeBoldText = true
        canvas.drawText("#${s.outputIndex}", cx, cy - 22f, textPaint)

        textPaint.color = Color.rgb(130, 170, 190)
        textPaint.textSize = 9f
        textPaint.isFakeBoldText = false
        canvas.drawText("CAPACITY", cx, cy + 6f, textPaint)

        textPaint.color = Color.rgb(0, 255, 170)
        textPaint.textSize = 13f
        textPaint.isFakeBoldText = true
        canvas.drawText(s.capacityCkb, cx, cy + 24f, textPaint)

        textPaint.color = Color.rgb(130, 170, 190)
        textPaint.textSize = 9f
        textPaint.isFakeBoldText = false
        canvas.drawText("TYPE: ${if (s.hasTypeScript) "ACTIVE" else "NONE"} • DATA: ${s.dataBytes}B", cx, cy + 50f, textPaint)

        textPaint.color = Color.rgb(0, 240, 255)
        textPaint.textSize = 10f
        textPaint.isFakeBoldText = true
        canvas.drawText("TAP TO CLOSE", cx, cy + 95f, textPaint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                isDragging = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastTouchX
                if (abs(dx) > 6f) {
                    isDragging = true
                    targetOrbitAngle += dx * 0.012f
                    lastTouchX = event.x
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!isDragging) {
                    // Tap action
                    if (selectedSpecimen != null) {
                        selectedSpecimen = null
                        triggerHaptic("click")
                    } else if (activeOrganisms.isNotEmpty()) {
                        // Select specimen
                        selectedSpecimen = activeOrganisms.first().cell
                        triggerHaptic("click")
                    } else {
                        // Double tap or empty tap simulation trigger
                        triggerSimulatedBlock()
                    }
                }
                isDragging = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    fun triggerSimulatedBlock() {
        val mockData = CkbBlockData(
            blockNumber = blockNumber + 1,
            blockHash = "0x" + (1..64).map { "0123456789abcdef".random() }.joinToString(""),
            txCount = 8,
            cells = (0..18).map { i ->
                CkbCellSpecimen(
                    txHash = "0x91abe720" + (1..32).map { "a" }.joinToString(""),
                    outputIndex = i,
                    outPoint = "0x91abe720:$i",
                    capacityHex = "0x2540be400",
                    capacityCkb = "${(i + 1) * 61} CKB",
                    hasTypeScript = (i % 3 == 0),
                    dataBytes = if (i % 2 == 0) 32 else 0
                )
            }
        )
        onBlockArrived(mockData)
    }

    private fun deriveOrganismColor(seed: String): Int {
        var hash = 0
        for (c in seed) {
            hash = (hash shl 5) - hash + c.code
        }
        val colors = intArrayOf(
            Color.rgb(0, 240, 255),
            Color.rgb(0, 255, 170),
            Color.rgb(255, 0, 187),
            Color.rgb(255, 184, 48),
            Color.rgb(155, 81, 224),
            Color.rgb(56, 239, 125)
        )
        val idx = abs(hash) % colors.size
        return colors[idx]
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
                vibrator.vibrate(30L)
            }
        } catch (e: Exception) {}
    }

    private data class OrganismRenderData(
        val cell: CkbCellSpecimen,
        var x: Float,
        var y: Float,
        val initialX: Float,
        val initialY: Float,
        val color: Int,
        val size: Float,
        val pulseOffset: Float,
        var alpha: Float = 1.0f
    )

    private data class LiquidParticle(
        var normR: Float,
        var normAngle: Float,
        var normY: Float,
        val speed: Float,
        val size: Float
    )
}
