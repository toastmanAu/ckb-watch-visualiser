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
 * Native Wear OS 60 FPS Canvas Renderer for CKB Maple Bonsai Visualizer.
 * Renders an abundant Japanese maple bonsai with falling autumn leaves representing newly created Cells.
 */
class BonsaiVisualiserView(context: Context) : View(context) {

    private var blockNumber: Long = 17_234_568L
    private var statusMsg: String = "OBSERVING BONSAI"

    // Camera & Orbit
    private var cameraAngle = 0f
    private var targetAngle = 0f
    private var isDragging = false
    private var lastTouchX = 0f

    // Timing
    private var timeSec = 0f
    private var lastFrameTime = System.nanoTime()

    // Leaf Particles
    private val canopyAnchors = mutableListOf<PointF>()
    private val fallingLeaves = mutableListOf<FallingLeaf>()

    // Haptics
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        vm?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    // Paints
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val woodPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(28, 18, 12) }
    private val potPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(14, 40, 60) }
    private val trunkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.rgb(45, 30, 22)
    }
    private val mossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(46, 74, 29) }
    private val leafPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.SERIF
        textAlign = Paint.Align.CENTER
    }

    init {
        // Pre-calculate canopy leaf cluster anchors
        val baseCanopyY = -70f
        for (i in 0 until 40) {
            val a = (i.toFloat() / 40f) * PI.toFloat() * 2f
            val r = 25f + (i % 5) * 16f
            canopyAnchors.add(PointF(cos(a) * r, baseCanopyY + sin(i * 1.8f) * 28f))
        }

        // 60 FPS animation ticker
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1000
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                val now = System.nanoTime()
                val dt = (now - lastFrameTime) / 1_000_000_000f
                lastFrameTime = now
                timeSec += dt
                updateScene(timeSec, dt)
                invalidate()
            }
            start()
        }
    }

    fun onBlockArrived(blockData: CkbBlockData) {
        blockNumber = blockData.blockNumber
        statusMsg = "${blockData.cells.size} NEW CELLS"
        triggerHaptic("heavy_click")

        // Spawn falling leaves corresponding to the exact newly created Cell count
        val total = blockData.cells.size
        for (i in 0 until total) {
            val anchor = canopyAnchors[i % canopyAnchors.size]
            val cell = blockData.cells[i]
            fallingLeaves.add(
                FallingLeaf(
                    x = anchor.x + (Math.random().toFloat() - 0.5f) * 18f,
                    y = anchor.y + (Math.random().toFloat() - 0.5f) * 12f,
                    vx = (Math.random().toFloat() - 0.5f) * 1.5f,
                    vy = 1.2f + Math.random().toFloat() * 1.2f,
                    color = deriveLeafColor(cell.txHash + cell.outputIndex),
                    swayFreq = 2.5f + Math.random().toFloat() * 2f,
                    swayAmp = 1.2f + Math.random().toFloat() * 1.0f,
                    rot = Math.random().toFloat() * 360f,
                    vrot = (Math.random().toFloat() - 0.5f) * 4f,
                    size = 5.5f + (if (cell.hasTypeScript) 2.5f else 0f)
                )
            )
        }
    }

    fun handleRotaryScroll(delta: Float) {
        targetAngle += delta * 0.25f
        triggerHaptic("tick")
    }

    private fun updateScene(time: Float, dt: Float) {
        cameraAngle += (targetAngle - cameraAngle) * 0.15f
        if (!isDragging) {
            targetAngle += 0.0025f // gentle turntable rotation
        }

        // Update falling leaves
        for (i in fallingLeaves.indices.reversed()) {
            val l = fallingLeaves[i]
            l.age += dt
            l.y += l.vy
            l.x += l.vx + sin(time * l.swayFreq) * l.swayAmp
            l.rot += l.vrot

            // Land near pot / table level (y = 80)
            if (l.y > 80f || l.age > 6f) {
                l.alpha -= 0.03f
                if (l.alpha <= 0.02f) {
                    fallingLeaves.removeAt(i)
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

        // 1. Japanese Room Background: Tatami & Shoji Paper Backlight
        val bgShader = RadialGradient(
            cx, cy - 40f, cx * 1.1f,
            intArrayOf(Color.rgb(32, 26, 20), Color.rgb(16, 12, 10), Color.rgb(6, 6, 8)),
            floatArrayOf(0f, 0.65f, 1f),
            Shader.TileMode.CLAMP
        )
        bgPaint.shader = bgShader
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        // Shoji Screen Lattice lines in background
        val shojiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(45, 230, 200, 160)
            strokeWidth = 1.2f
        }
        for (x in -160..160 step 40) {
            canvas.drawLine(cx + x, 0f, cx + x, cy + 90f, shojiPaint)
        }
        for (y in 40..260 step 35) {
            canvas.drawLine(0f, y.toFloat(), w, y.toFloat(), shojiPaint)
        }

        canvas.save()
        canvas.translate(cx, cy + 20f)

        // 2. Ornate Bonsai Display Stand (Daiza)
        val standRect = RectF(-85f, 58f, 85f, 72f)
        canvas.drawRoundRect(standRect, 4f, 4f, woodPaint)
        // Stand feet
        canvas.drawRect(-75f, 72f, -60f, 82f, woodPaint)
        canvas.drawRect(60f, 72f, 75f, 82f, woodPaint)

        // 3. Ceramic Bonsai Pot (Oval Glazed Cobalt)
        val potOval = RectF(-68f, 32f, 68f, 62f)
        canvas.drawOval(potOval, potPaint)
        val lipOval = RectF(-70f, 30f, 70f, 40f)
        val lipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(20, 55, 80) }
        canvas.drawOval(lipOval, lipPaint)

        // Soil & Green Velvet Moss
        val soilOval = RectF(-60f, 32f, 60f, 38f)
        val soilPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(30, 22, 16) }
        canvas.drawOval(soilOval, soilPaint)
        canvas.drawCircle(-30f, 34f, 9f, mossPaint)
        canvas.drawCircle(25f, 33f, 10f, mossPaint)
        canvas.drawCircle(0f, 34f, 8f, mossPaint)

        // 4. Aged Trunk with Gnarled Curves
        val trunkShift = sin(cameraAngle) * 12f
        trunkPaint.strokeWidth = 18f
        val trunkPath = Path().apply {
            moveTo(0f, 34f)
            quadTo(18f + trunkShift, 0f, -12f + trunkShift, -40f)
            quadTo(8f, -70f, 0f, -95f) // Apex
        }
        canvas.drawPath(trunkPath, trunkPaint)

        // Main Boughs
        trunkPaint.strokeWidth = 9f
        canvas.drawLine(10f + trunkShift, 2f, 55f + trunkShift, -15f, trunkPaint)
        canvas.drawLine(-8f + trunkShift, -32f, -60f + trunkShift, -48f, trunkPaint)
        canvas.drawLine(4f + trunkShift, -62f, 42f + trunkShift, -78f, trunkPaint)

        // 5. Dense Canopy of Crimson & Amber Japanese Maple Foliage
        val mapleColors = intArrayOf(
            Color.rgb(211, 47, 47),  // Crimson
            Color.rgb(194, 24, 91),  // Deep Scarlet
            Color.rgb(230, 81, 0),   // Maple Orange
            Color.rgb(255, 143, 0),  // Golden Amber
            Color.rgb(183, 28, 28)   // Dark Wine
        )

        for (i in canopyAnchors.indices) {
            val pt = canopyAnchors[i]
            val px = pt.x + (trunkShift * 0.7f)
            val py = pt.y
            val col = mapleColors[i % mapleColors.size]
            leafPaint.color = col

            // Draw maple leaf cluster (overlapping organic shapes)
            canvas.drawCircle(px, py, 7f, leafPaint)
            canvas.drawCircle(px + 4f, py - 3f, 5.5f, leafPaint)
            canvas.drawCircle(px - 4f, py - 2f, 5.5f, leafPaint)
        }

        // 6. Falling Maple Leaves (representing newly created CKB Cells)
        for (l in fallingLeaves) {
            leafPaint.color = Color.argb(
                (l.alpha * 255).toInt().coerceIn(0, 255),
                Color.red(l.color),
                Color.green(l.color),
                Color.blue(l.color)
            )

            canvas.save()
            canvas.translate(l.x, l.y)
            canvas.rotate(l.rot)
            // Draw 5-lobed maple leaf silhouette
            val s = l.size
            val leafPath = Path().apply {
                moveTo(0f, -s)
                lineTo(s * 0.6f, -s * 0.3f)
                lineTo(s * 0.9f, 0f)
                lineTo(s * 0.5f, s * 0.5f)
                lineTo(0f, s * 0.8f)
                lineTo(-s * 0.5f, s * 0.5f)
                lineTo(-s * 0.9f, 0f)
                lineTo(-s * 0.6f, -s * 0.3f)
                close()
            }
            canvas.drawPath(leafPath, leafPaint)
            canvas.restore()
        }

        canvas.restore()

        // 7. Watch Bezel HUD
        drawWatchHud(canvas, w, h)
    }

    private fun drawWatchHud(canvas: Canvas, w: Float, h: Float) {
        val cx = w / 2f

        // Top arc title
        textPaint.color = Color.rgb(229, 169, 60)
        textPaint.textSize = 12f
        textPaint.isFakeBoldText = true
        canvas.drawText("CKB MAPLE BONSAI", cx, 34f, textPaint)

        textPaint.color = Color.rgb(240, 230, 215)
        textPaint.textSize = 13.5f
        canvas.drawText("#$blockNumber", cx, 52f, textPaint)

        // Bottom status pill
        val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(180, 16, 20, 28) }
        val pillStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.2f
            color = Color.argb(120, 229, 169, 60)
        }
        val pillRect = RectF(cx - 90f, h - 50f, cx + 90f, h - 22f)
        canvas.drawRoundRect(pillRect, 14f, 14f, pillPaint)
        canvas.drawRoundRect(pillRect, 14f, 14f, pillStroke)

        // Status dot
        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(229, 169, 60) }
        canvas.drawCircle(cx - 72f, h - 36f, 3.5f, dotPaint)

        textPaint.color = Color.rgb(220, 215, 205)
        textPaint.textSize = 10f
        textPaint.isFakeBoldText = false
        val displayMsg = if (fallingLeaves.isNotEmpty()) "${fallingLeaves.size} FALLING LEAVES" else statusMsg
        canvas.drawText(displayMsg, cx + 6f, h - 32.5f, textPaint)
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
                    targetAngle += dx * 0.012f
                    lastTouchX = event.x
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!isDragging) {
                    // Tap triggers a test block leaf fall
                    triggerSimulatedLeafShower()
                }
                isDragging = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    fun triggerSimulatedLeafShower() {
        val count = 18 + (Math.random() * 25).toInt()
        val mockData = CkbBlockData(
            blockNumber = blockNumber + 1,
            blockHash = "0x" + (1..64).map { "0123456789abcdef".random() }.joinToString(""),
            txCount = 6,
            cells = (0 until count).map { i ->
                CkbCellSpecimen(
                    txHash = "0x" + (1..64).map { "a" }.joinToString(""),
                    outputIndex = i,
                    outPoint = "0x...:$i",
                    capacityHex = "0x1",
                    capacityCkb = "100 CKB",
                    hasTypeScript = (i % 3 == 0),
                    dataBytes = 0
                )
            }
        )
        onBlockArrived(mockData)
    }

    private fun deriveLeafColor(seed: String): Int {
        var hash = 0
        for (c in seed) hash = (hash shl 5) - hash + c.code
        val colors = intArrayOf(
            Color.rgb(211, 47, 47),  // Crimson
            Color.rgb(230, 81, 0),   // Orange
            Color.rgb(255, 143, 0),  // Amber
            Color.rgb(194, 24, 91),  // Scarlet
            Color.rgb(183, 28, 28)   // Deep Burgundy
        )
        return colors[abs(hash) % colors.size]
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
        } catch (e: Exception) {}
    }

    private data class FallingLeaf(
        var x: Float,
        var y: Float,
        val vx: Float,
        val vy: Float,
        val color: Int,
        val swayFreq: Float,
        val swayAmp: Float,
        var rot: Float,
        val vrot: Float,
        val size: Float,
        var alpha: Float = 1.0f,
        var age: Float = 0f
    )
}
