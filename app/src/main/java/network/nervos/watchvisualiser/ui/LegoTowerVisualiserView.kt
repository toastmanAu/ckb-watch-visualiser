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
import java.util.*
import kotlin.collections.ArrayDeque
import kotlin.math.*

/**
 * Native 60 FPS Wear OS Canvas Renderer for the "Nervos CKB LEGO Epoch Brick Tower".
 *
 * Features:
 * - Reconstructs the live CKB epoch (`epochIndex + 1` bricks out of `epochLength`)
 * - 3D turntable perspective with Digital Crown rotary orbit & depth-sorted isometric LEGO bricks + studs
 * - 4-Stage Hero Block Animation:
 *   1. Hero Entrance in foreground with "#BLOCK_NUMBER" physically printed on the brick face
 *   2. Presentation hold with floating settle
 *   3. Curved Bezier trajectory to its exact deterministic slot on top of the tower
 *   4. Physical snap, impact ring & haptic lock
 * - Epoch Completion celebration wave and historical skyline retirement on epoch rollover
 */
class LegoTowerVisualiserView(context: Context) : View(context) {

    private val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

    // Time & Turntable Camera Orbit
    private var timeSec = 0f
    private var lastFrameTime = System.nanoTime()
    private var cameraAngle = 0.62f
    private var targetAngle = 0.62f
    private var isDragging = false
    private var lastTouchX = 0f
    private var downX = 0f
    private var downY = 0f

    // Live CKB Epoch & Block State
    private var statusText = "CONNECTING"
    private var currentBlock: CkbBlockData? = null
    private var epochNumber = 15168L
    private var epochIndex = 0
    private var epochLength = 1800
    private var visibleBricks = 0
    private var showDetailOverlay = false

    // Initial Cascade Reconstruction State
    private var cascadeProgress = 1.0f
    private var isCascading = false

    // Epoch Completion Wave & Historical Skyline
    private var celebrationTimer = 0f
    private var celebrationEpochNum = 0L
    private var celebrationEpochLen = 0
    private var historicalTowerCount = 0

    // 4-Stage Hero Brick Queue & Animation State (~3.1s)
    private val blockQueue = ArrayDeque<HeroTask>()
    private val seenHashes = LinkedHashSet<String>()
    private var heroActive = false
    private var heroElapsed = 0f
    private val heroDuration = 3.1f
    private var activeHeroTask: HeroTask? = null
    private var lockSnapFired = false

    // Snap ring effect at target slot
    private var ringAlpha = 0f
    private var ringRadius = 0f
    private var ringScreenX = 0f
    private var ringScreenY = 0f

    // Reusable Paints & Paths
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    private val monoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textAlign = Paint.Align.CENTER
    }
    private val facePath = Path()

    private val palette = intArrayOf(
        Color.rgb(0, 224, 142),   // Nervos Emerald
        Color.rgb(0, 184, 230),   // Cerulean Cyan
        Color.rgb(59, 130, 246),  // Royal Blue
        Color.rgb(99, 102, 241),  // Indigo
        Color.rgb(245, 158, 11),  // Amber Gold
        Color.rgb(239, 68, 68),   // Brick Crimson
        Color.rgb(16, 185, 129),  // Mint Jade
        Color.rgb(234, 179, 8)    // Sunburst Yellow
    )

    private data class HeroTask(
        val block: CkbBlockData,
        val isEpochRollover: Boolean
    )

    private data class ProjectedBrick(
        val index: Int,
        val layer: Int,
        val sx: Float,
        val sy: Float,
        val depth: Float,
        val color: Int
    )

    private val drawList = ArrayList<ProjectedBrick>(256)

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
     * Reconstruct the current epoch tower on startup without playing thousands of hero animations.
     */
    fun onBaselineLoaded(blockData: CkbBlockData) {
        seenHashes.add(blockData.blockHash)
        currentBlock = blockData
        epochNumber = blockData.epochNumber
        epochLength = blockData.epochLength.toInt().coerceAtLeast(1)
        epochIndex = blockData.epochIndex.toInt().coerceIn(0, epochLength - 1)
        visibleBricks = epochIndex + 1
        isCascading = true
        cascadeProgress = 0.02f
        statusText = "CONNECTED • LIVE"
    }

    /**
     * Handle newly detected canonical block (or demo block).
     */
    fun onBlockArrived(blockData: CkbBlockData) {
        if (!blockData.isDemo) {
            if (seenHashes.contains(blockData.blockHash)) return
            seenHashes.add(blockData.blockHash)
            if (seenHashes.size > 300) {
                val first = seenHashes.iterator().next()
                seenHashes.remove(first)
            }
            statusText = "CONNECTED • LIVE"
        }

        val newEpochNum = blockData.epochNumber
        val isRollover = (currentBlock != null && newEpochNum > epochNumber)

        // If same epoch and multiple blocks jumped ahead while suspended, catch up intermediate bricks
        if (!isRollover && currentBlock != null) {
            val newIdx = blockData.epochIndex.toInt()
            if (newIdx > epochIndex + 1) {
                epochIndex = newIdx - 1
                visibleBricks = epochIndex + 1
            }
        }

        blockQueue.addLast(HeroTask(blockData, isRollover))
        processNextHeroTask()
    }

    fun onStatusUpdated(status: String) {
        statusText = when {
            status.startsWith("LIVE") -> "CONNECTED • LIVE"
            status.contains("ERROR") || status.contains("TIMEOUT") -> "OFFLINE • RETRYING"
            else -> "CONNECTING…"
        }
    }

    fun handleRotaryScroll(delta: Float) {
        targetAngle += delta * 0.22f
        triggerHaptic("tick")
    }

    private fun processNextHeroTask() {
        if (heroActive || blockQueue.isEmpty()) return
        val task = blockQueue.removeFirst()
        activeHeroTask = task
        heroActive = true
        heroElapsed = 0f
        lockSnapFired = false

        if (task.isEpochRollover) {
            // Retire previous completed tower to the distant skyline
            historicalTowerCount = (historicalTowerCount + 1).coerceAtMost(2)
            epochNumber = task.block.epochNumber
            epochLength = task.block.epochLength.toInt().coerceAtLeast(1)
            epochIndex = 0
            visibleBricks = 0
        }

        currentBlock = task.block
        triggerHaptic("click")
    }

    private fun updateScene(dt: Float) {
        cameraAngle += (targetAngle - cameraAngle) * 0.14f
        if (!isDragging) {
            targetAngle += dt * 0.28f // Gentle exhibition turntable orbit
        }

        if (isCascading) {
            cascadeProgress = (cascadeProgress + dt * 0.85f).coerceAtMost(1.0f)
            if (cascadeProgress >= 1.0f) {
                isCascading = false
            }
        }

        if (celebrationTimer > 0f) {
            celebrationTimer = (celebrationTimer - dt).coerceAtLeast(0f)
        }

        if (ringAlpha > 0f) {
            ringAlpha = (ringAlpha - dt * 1.6f).coerceAtLeast(0f)
            ringRadius += dt * 52f
        }

        if (!heroActive) return

        heroElapsed += dt
        val t = (heroElapsed / heroDuration).coerceIn(0f, 1f)

        if (t >= 0.90f && !lockSnapFired) {
            lockSnapFired = true
            triggerHaptic("heavy_click")
        }

        if (t >= 1.0f) {
            val task = activeHeroTask
            heroActive = false
            activeHeroTask = null
            if (task != null) {
                epochNumber = task.block.epochNumber
                epochLength = task.block.epochLength.toInt().coerceAtLeast(1)
                epochIndex = task.block.epochIndex.toInt().coerceIn(0, epochLength - 1)
                visibleBricks = epochIndex + 1

                // Trigger impact ring at the top of the tower
                val targetSlot = projectBrickSlot(epochIndex, epochLength, width / 2f, height / 2f + 68f)
                ringScreenX = targetSlot.sx
                ringScreenY = targetSlot.sy
                ringRadius = 8f
                ringAlpha = 1.0f

                // Check for Epoch Completion!
                if (epochIndex == epochLength - 1) {
                    celebrationEpochNum = epochNumber
                    celebrationEpochLen = epochLength
                    celebrationTimer = 4.5f
                    triggerHaptic("double_click")
                }
            }
            processNextHeroTask()
        }
    }

    @SuppressLint("DrawAllocation")
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f

        // 1. Deep Exhibition Hall Background & Glowing Horizon
        val bgGrad = RadialGradient(
            cx, cy - 15f, w * 0.65f,
            intArrayOf(Color.rgb(22, 36, 58), Color.rgb(10, 16, 28), Color.rgb(4, 7, 13)),
            floatArrayOf(0f, 0.68f, 1f),
            Shader.TileMode.CLAMP
        )
        paint.shader = bgGrad
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null

        // 2. Distant Historical Epoch Towers in Skyline (if any completed)
        drawHistoricalSkyline(canvas, cx, cy + 56f)

        // 3. Exhibition Pedestal Base Platform
        val baseCenterY = cy + 68f
        drawPedestalPlatform(canvas, cx, baseCenterY)

        // 4. Render Depth-Sorted 3D Isometric LEGO Brick Tower
        drawEpochBrickTower(canvas, cx, baseCenterY)

        // 5. Snap Lock Ring Effect
        if (ringAlpha > 0.02f) {
            strokePaint.color = Color.argb((ringAlpha * 235).toInt(), 0, 245, 155)
            strokePaint.strokeWidth = 2.4f
            canvas.drawOval(
                RectF(
                    ringScreenX - ringRadius,
                    ringScreenY - ringRadius * 0.55f,
                    ringScreenX + ringRadius,
                    ringScreenY + ringRadius * 0.55f
                ),
                strokePaint
            )
        }

        // 6. 4-Stage Hero Brick Animation (Foreground -> Bezier Flight -> Tower Slot)
        if (heroActive && activeHeroTask != null) {
            drawHeroBrickSequence(canvas, cx, cy, baseCenterY, activeHeroTask!!.block)
        }

        // 7. Epoch Completion Celebration Banner
        if (celebrationTimer > 0f) {
            drawCelebrationBanner(canvas, cx, 64f)
        }

        // 8. Top Header & Bottom Epoch Progress HUD
        drawTopHeader(canvas, cx)
        drawBottomHud(canvas, cx, h)

        // 9. Detail Inspector Overlay
        if (showDetailOverlay) {
            drawDetailOverlay(canvas, w, h, cx)
        }
    }

    private fun drawHistoricalSkyline(canvas: Canvas, cx: Float, horizonY: Float) {
        if (historicalTowerCount <= 0) return
        for (i in 0 until historicalTowerCount) {
            val side = if (i % 2 == 0) -1 else 1
            val hx = cx + side * (86f + i * 24f) - sin(cameraAngle) * 12f
            val hw = 24f
            val hh = 64f
            paint.color = Color.argb(95, 16, 48, 66)
            canvas.drawRect(hx - hw / 2f, horizonY - hh, hx + hw / 2f, horizonY, paint)
            strokePaint.color = Color.argb(120, 0, 245, 155)
            strokePaint.strokeWidth = 1f
            canvas.drawRect(hx - hw / 2f, horizonY - hh, hx + hw / 2f, horizonY, strokePaint)
        }
    }

    private fun drawPedestalPlatform(canvas: Canvas, cx: Float, baseY: Float) {
        val rx = 68f
        val ry = 22f

        // Pedestal shadow & lower cylinder body
        paint.color = Color.rgb(13, 21, 36)
        canvas.drawOval(RectF(cx - rx, baseY - ry + 8f, cx + rx, baseY + ry + 12f), paint)

        // Pedestal top deck
        paint.color = Color.rgb(21, 33, 54)
        canvas.drawOval(RectF(cx - rx, baseY - ry, cx + rx, baseY + ry), paint)

        // Glowing CKB Emerald Trim Ring
        strokePaint.color = Color.rgb(0, 245, 155)
        strokePaint.strokeWidth = 1.8f
        canvas.drawOval(RectF(cx - rx, baseY - ry, cx + rx, baseY + ry), strokePaint)
    }

    private fun getGridSize(len: Int): Int {
        return when {
            len <= 180 -> 3
            len <= 500 -> 4
            len <= 1100 -> 5
            else -> 6
        }
    }

    private fun projectBrickSlot(index: Int, len: Int, cx: Float, baseY: Float): ProjectedBrick {
        val grid = getGridSize(len)
        val perLayer = grid * grid
        val totalLayers = max(1, (len + perLayer - 1) / perLayer)

        val layer = index / perLayer
        val slot = index % perLayer
        val row = slot / grid
        val col = if (row % 2 == 0) (slot % grid) else (grid - 1 - (slot % grid))

        // Scale tower height so the full epoch fits gracefully inside the watch display
        val maxTowerHeightPx = 128f
        val layerStepY = (maxTowerHeightPx / max(16, totalLayers)).coerceIn(2.2f, 6.5f)
        val gridSpacing = (58f / grid).coerceIn(8.5f, 15f)

        val gx = (col - (grid - 1) / 2f) * gridSpacing
        val gz = (row - (grid - 1) / 2f) * gridSpacing

        val cosA = cos(cameraAngle)
        val sinA = sin(cameraAngle)
        val rx = gx * cosA - gz * sinA
        val rz = gx * sinA + gz * cosA

        val sx = cx + rx
        val sy = baseY - (layer * layerStepY * cascadeProgress) + rz * 0.36f

        val baseColor = palette[(layer / 3 + (if ((slot + layer) % 5 == 0) 1 else 0)) % palette.size]
        val finalColor = if (celebrationTimer > 0f) {
            val wavePos = (1f - (celebrationTimer / 4.5f)) * 1.4f
            val layerRatio = layer.toFloat() / max(1, totalLayers)
            if (abs(layerRatio - wavePos) < 0.18f) Color.rgb(255, 245, 175) else baseColor
        } else {
            baseColor
        }

        return ProjectedBrick(index, layer, sx, sy, rz, finalColor)
    }

    private fun drawEpochBrickTower(canvas: Canvas, cx: Float, baseY: Float) {
        val totalToConsider = visibleBricks.coerceIn(0, epochLength)
        if (totalToConsider == 0) return

        val grid = getGridSize(epochLength)
        val perLayer = grid * grid
        val topLayer = (totalToConsider - 1) / perLayer

        // For 60 FPS performance on Wear OS: render outer shell of lower completed layers
        // and all bricks of the top 3 active layers
        drawList.clear()
        val layerStride = if (topLayer > 28) 2 else 1

        for (i in 0 until totalToConsider) {
            val layer = i / perLayer
            val slot = i % perLayer
            val row = slot / grid
            val col = slot % grid
            val isOuterRing = (row == 0 || row == grid - 1 || col == 0 || col == grid - 1)
            val isNearTop = (layer >= topLayer - 2)

            if (isNearTop || (isOuterRing && (layer % layerStride == 0 || layer == 0))) {
                drawList.add(projectBrickSlot(i, epochLength, cx, baseY))
            }
        }

        // Depth sort back-to-front by layer (bottom-to-top) then by rotated Z depth
        drawList.sortWith(Comparator { a, b ->
            if (a.layer != b.layer) a.layer.compareTo(b.layer)
            else a.depth.compareTo(b.depth)
        })

        val brickW = (52f / grid).coerceIn(8f, 14f)
        val brickH = (brickW * 0.52f).coerceIn(4.2f, 7.5f)

        for (b in drawList) {
            drawIsoLegoBrick(canvas, b.sx, b.sy, brickW, brickH, b.color, drawStuds = (b.layer >= topLayer - 3))
        }
    }

    /**
     * Draw an individual 3D isometric LEGO-style plastic brick with top bevel & cylindrical studs.
     */
    private fun drawIsoLegoBrick(
        canvas: Canvas,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        color: Int,
        drawStuds: Boolean
    ) {
        val hw = w * 0.5f
        val qw = w * 0.26f

        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)

        val topColor = Color.rgb(min(255, (r * 1.18f).toInt()), min(255, (g * 1.18f).toInt()), min(255, (b * 1.18f).toInt()))
        val leftColor = color
        val rightColor = Color.rgb((r * 0.74f).toInt(), (g * 0.74f).toInt(), (b * 0.74f).toInt())

        // Top Diamond Face
        facePath.reset()
        facePath.moveTo(x, y - qw - h)
        facePath.lineTo(x + hw, y - h)
        facePath.lineTo(x, y + qw - h)
        facePath.lineTo(x - hw, y - h)
        facePath.close()
        paint.color = topColor
        canvas.drawPath(facePath, paint)

        // Left Side Face
        facePath.reset()
        facePath.moveTo(x - hw, y - h)
        facePath.lineTo(x, y + qw - h)
        facePath.lineTo(x, y + qw)
        facePath.lineTo(x - hw, y)
        facePath.close()
        paint.color = leftColor
        canvas.drawPath(facePath, paint)

        // Right Side Face
        facePath.reset()
        facePath.moveTo(x, y + qw - h)
        facePath.lineTo(x + hw, y - h)
        facePath.lineTo(x + hw, y)
        facePath.lineTo(x, y + qw)
        facePath.close()
        paint.color = rightColor
        canvas.drawPath(facePath, paint)

        // Cylindrical LEGO Studs on Top
        if (drawStuds) {
            val studR = w * 0.14f
            paint.color = topColor
            canvas.drawCircle(x - hw * 0.36f, y - h, studR, paint)
            canvas.drawCircle(x + hw * 0.36f, y - h, studR, paint)
            strokePaint.color = rightColor
            strokePaint.strokeWidth = 0.8f
            canvas.drawCircle(x - hw * 0.36f, y - h, studR, strokePaint)
            canvas.drawCircle(x + hw * 0.36f, y - h, studR, strokePaint)
        }
    }

    /**
     * Draw the 4-stage Hero Block animation with the printed "#BLOCK_NUMBER" on the brick face.
     */
    private fun drawHeroBrickSequence(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        baseY: Float,
        block: CkbBlockData
    ) {
        val t = (heroElapsed / heroDuration).coerceIn(0f, 1f)
        val targetIdx = block.epochIndex.toInt().coerceIn(0, max(0, block.epochLength.toInt() - 1))
        val targetSlot = projectBrickSlot(targetIdx, block.epochLength.toInt().coerceAtLeast(1), cx, baseY)

        val startX = cx
        val startY = cy - 12f
        val ctrlX = (startX + targetSlot.sx) * 0.5f + 34f
        val ctrlY = min(startY, targetSlot.sy) - 58f

        var curX = startX
        var curY = startY
        var scale = 1.0f

        if (t < 0.18f) {
            // Stage 1: Hero Entrance pop-in
            val p = t / 0.18f
            scale = 0.2f + 0.88f * sin(p * PI.toFloat() * 0.5f)
        } else if (t < 0.58f) {
            // Stage 2: Presentation hold in front of viewer
            val p = (t - 0.18f) / 0.40f
            curY = startY + sin(p * PI.toFloat() * 2f) * 4f
            scale = 1.05f
        } else if (t < 0.90f) {
            // Stage 3: Quadratic Bezier Arc into exact tower slot
            val p = (t - 0.58f) / 0.32f
            val u = p * p * (3f - 2f * p)
            val invU = 1f - u
            curX = invU * invU * startX + 2f * invU * u * ctrlX + u * u * targetSlot.sx
            curY = invU * invU * startY + 2f * invU * u * ctrlY + u * u * targetSlot.sy
            scale = 1.05f * (1f - u) + 0.22f * u
        } else {
            // Stage 4: Compression bounce at target slot
            val p = (t - 0.90f) / 0.10f
            curX = targetSlot.sx
            curY = targetSlot.sy - sin(p * PI.toFloat()) * 4f
            scale = 0.22f
        }

        canvas.save()
        canvas.translate(curX, curY)
        canvas.scale(scale, scale)

        // Outer Hero Glow
        val glowGrad = RadialGradient(
            0f, 0f, 74f,
            intArrayOf(Color.argb(120, 255, 204, 51), Color.TRANSPARENT),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        paint.shader = glowGrad
        canvas.drawCircle(0f, 0f, 74f, paint)
        paint.shader = null

        // 4 Top Cylindrical LEGO Studs on the Hero Brick
        val bw = 128f
        val bh = 54f
        val studW = 18f
        val studH = 9f
        for (i in 0 until 4) {
            val sx = -42f + i * 28f
            paint.color = Color.rgb(255, 224, 102)
            canvas.drawRoundRect(RectF(sx - studW / 2f, -bh / 2f - studH, sx + studW / 2f, -bh / 2f + 2f), 3f, 3f, paint)
            strokePaint.color = Color.rgb(180, 125, 12)
            strokePaint.strokeWidth = 1.2f
            canvas.drawRoundRect(RectF(sx - studW / 2f, -bh / 2f - studH, sx + studW / 2f, -bh / 2f + 2f), 3f, 3f, strokePaint)
        }

        // Top 3D Bevel Roof of Hero Brick
        facePath.reset()
        facePath.moveTo(-bw / 2f, -bh / 2f)
        facePath.lineTo(-bw / 2f + 10f, -bh / 2f - 7f)
        facePath.lineTo(bw / 2f - 10f, -bh / 2f - 7f)
        facePath.lineTo(bw / 2f, -bh / 2f)
        facePath.close()
        paint.color = Color.rgb(255, 232, 132)
        canvas.drawPath(facePath, paint)

        // Hero Brick Front Body
        val bodyRect = RectF(-bw / 2f, -bh / 2f, bw / 2f, bh / 2f)
        val brickGrad = LinearGradient(
            0f, -bh / 2f, 0f, bh / 2f,
            intArrayOf(Color.rgb(255, 215, 74), Color.rgb(230, 162, 20)),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        paint.shader = brickGrad
        canvas.drawRoundRect(bodyRect, 7f, 7f, paint)
        paint.shader = null

        strokePaint.color = Color.rgb(255, 242, 175)
        strokePaint.strokeWidth = 2f
        canvas.drawRoundRect(bodyRect, 7f, 7f, strokePaint)

        // Printed Block Number physically attached to the Hero Brick Front Face
        textPaint.color = Color.rgb(42, 24, 4)
        textPaint.textSize = 9.5f
        canvas.drawText(if (block.isDemo) "DEMO CKB BLOCK" else "NERVOS CKB BLOCK", 0f, -8f, textPaint)

        monoPaint.color = Color.rgb(15, 8, 1)
        monoPaint.textSize = 15.5f
        monoPaint.isFakeBoldText = true
        canvas.drawText("#${String.format("%,d", block.blockNumber)}", 0f, 14f, monoPaint)

        canvas.restore()
    }

    private fun drawCelebrationBanner(canvas: Canvas, cx: Float, topY: Float) {
        val rect = RectF(cx - 102f, topY, cx + 102f, topY + 38f)
        paint.color = Color.argb(230, 8, 38, 32)
        canvas.drawRoundRect(rect, 10f, 10f, paint)
        strokePaint.color = Color.rgb(0, 245, 155)
        strokePaint.strokeWidth = 1.8f
        canvas.drawRoundRect(rect, 10f, 10f, strokePaint)

        textPaint.color = Color.rgb(0, 245, 155)
        textPaint.textSize = 10f
        canvas.drawText("EPOCH $celebrationEpochNum COMPLETE", cx, topY + 16f, textPaint)

        monoPaint.color = Color.WHITE
        monoPaint.textSize = 9f
        canvas.drawText("$celebrationEpochLen BLOCKS CONSTRUCTED", cx, topY + 30f, monoPaint)
    }

    private fun drawTopHeader(canvas: Canvas, cx: Float) {
        textPaint.color = Color.rgb(240, 246, 252)
        textPaint.textSize = 10.5f
        canvas.drawText("CKB EPOCH TOWER", cx, 25f, textPaint)

        monoPaint.color = Color.rgb(0, 245, 155)
        monoPaint.textSize = 8.5f
        monoPaint.isFakeBoldText = false
        canvas.drawText("$statusText • EP #$epochNumber", cx, 37f, monoPaint)
    }

    private fun drawBottomHud(canvas: Canvas, cx: Float, h: Float) {
        val pw = 216f
        val ph = 48f
        val top = h - ph - 14f
        val rect = RectF(cx - pw / 2f, top, cx + pw / 2f, top + ph)

        paint.color = Color.argb(225, 13, 20, 33)
        canvas.drawRoundRect(rect, 14f, 14f, paint)
        strokePaint.color = if (heroActive) Color.rgb(255, 204, 51) else Color.argb(110, 0, 245, 155)
        strokePaint.strokeWidth = 1.4f
        canvas.drawRoundRect(rect, 14f, 14f, strokePaint)

        val b = currentBlock
        val blockLabel = if (b != null) {
            (if (b.isDemo) "[DEMO] " else "") + "#${String.format("%,d", b.blockNumber)}"
        } else {
            "SYNCING TIP…"
        }

        val pct = (visibleBricks.toFloat() / max(1, epochLength) * 100f).coerceIn(0f, 100f)

        monoPaint.color = Color.WHITE
        monoPaint.textSize = 10.5f
        monoPaint.isFakeBoldText = true
        canvas.drawText(
            "$blockLabel • $visibleBricks/$epochLength (${String.format("%.1f", pct)}%)",
            cx,
            top + 19f,
            monoPaint
        )

        // Compact Epoch Progress Bar
        val barLeft = cx - 82f
        val barRight = cx + 82f
        val barTop = top + 28f
        val barBottom = top + 35f
        paint.color = Color.argb(50, 255, 255, 255)
        canvas.drawRoundRect(RectF(barLeft, barTop, barRight, barBottom), 4f, 4f, paint)

        val fillRight = barLeft + (barRight - barLeft) * (pct / 100f)
        paint.color = Color.rgb(0, 245, 155)
        canvas.drawRoundRect(RectF(barLeft, barTop, fillRight, barBottom), 4f, 4f, paint)
    }

    private fun drawDetailOverlay(canvas: Canvas, w: Float, h: Float, cx: Float) {
        paint.color = Color.argb(238, 7, 11, 18)
        canvas.drawRect(0f, 0f, w, h, paint)

        val card = RectF(24f, 28f, w - 24f, h - 28f)
        strokePaint.color = Color.rgb(0, 245, 155)
        strokePaint.strokeWidth = 1.6f
        canvas.drawRoundRect(card, 18f, 18f, strokePaint)

        textPaint.color = Color.rgb(0, 245, 155)
        textPaint.textSize = 11f
        canvas.drawText("EPOCH #$epochNumber TOWER", cx, 54f, textPaint)

        val b = currentBlock
        val pct = String.format("%.2f%%", (visibleBricks.toFloat() / max(1, epochLength)) * 100f)
        val remaining = max(0, epochLength - visibleBricks)
        val shortHash = if (b != null && b.blockHash.length > 14) {
            "${b.blockHash.take(8)}…${b.blockHash.takeLast(6)}"
        } else "—"

        val lines = listOf(
            "BLOCK: #${b?.blockNumber ?: 0L}",
            "EPOCH INDEX: $epochIndex / $epochLength",
            "BRICKS BUILT: $visibleBricks ($pct)",
            "REMAINING: $remaining BLOCKS",
            "HASH: $shortHash",
            "TAP TOP FOR EPOCH ROLLOVER DEMO"
        )

        monoPaint.textSize = 9.5f
        monoPaint.isFakeBoldText = false
        monoPaint.color = Color.rgb(230, 238, 248)
        var yPos = 78f
        for (line in lines) {
            canvas.drawText(line, cx, yPos, monoPaint)
            yPos += 19f
        }

        textPaint.color = Color.rgb(139, 155, 180)
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
                    targetAngle += dx * 0.012f
                    lastTouchX = event.x
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!isDragging) {
                    if (showDetailOverlay) {
                        if (event.y < height * 0.4f) {
                            showDetailOverlay = false
                            triggerDemoEpochRollover()
                        } else {
                            showDetailOverlay = false
                            triggerHaptic("tick")
                        }
                    } else if (event.y > height - 70f) {
                        showDetailOverlay = true
                        triggerHaptic("click")
                    } else {
                        triggerDemoBrick()
                    }
                }
                isDragging = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    fun triggerDemoBrick() {
        val nextHeight = (currentBlock?.blockNumber ?: 20_820_500L) + 1L
        val nextIdx = (epochIndex + 1).coerceAtMost(epochLength - 1)
        val mockHash = "0x" + (1..64).map { "0123456789abcdef".random() }.joinToString("")
        val demo = CkbBlockData(
            blockNumber = nextHeight,
            blockHash = mockHash,
            txCount = 6,
            cells = emptyList(),
            timestampMs = System.currentTimeMillis(),
            epochNumber = epochNumber,
            epochIndex = nextIdx.toLong(),
            epochLength = epochLength.toLong(),
            isDemo = true
        )
        onBlockArrived(demo)
    }

    private fun triggerDemoEpochRollover() {
        val baseHeight = (currentBlock?.blockNumber ?: 20_820_500L) + 1L
        val finalBlock = CkbBlockData(
            blockNumber = baseHeight,
            blockHash = "0x" + (1..64).map { "0123456789abcdef".random() }.joinToString(""),
            txCount = 8,
            cells = emptyList(),
            timestampMs = System.currentTimeMillis(),
            epochNumber = epochNumber,
            epochIndex = (epochLength - 1).toLong(),
            epochLength = epochLength.toLong(),
            isDemo = true
        )
        val newEpochFirst = CkbBlockData(
            blockNumber = baseHeight + 1L,
            blockHash = "0x" + (1..64).map { "0123456789abcdef".random() }.joinToString(""),
            txCount = 5,
            cells = emptyList(),
            timestampMs = System.currentTimeMillis(),
            epochNumber = epochNumber + 1L,
            epochIndex = 0L,
            epochLength = 1680L,
            isDemo = true
        )
        onBlockArrived(finalBlock)
        onBlockArrived(newEpochFirst)
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
