package com.sdk.glassessdksample.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import com.sdk.glassessdksample.R
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * Center glasses circle surrounded by an orbiting-dots swirl, matching the
 * animated "IMI" constellation used on the Mark I BLE gate screen (DotsOrbitView).
 */
class RadarScanView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private data class Blip(
        val device: SmartWatch,
        val x: Float,
        val y: Float,
        val radius: Float,
        val distanceLabel: String,
        val labelYOffset: Float
    )

    // Each dot orbits around the center. tone: 0 = orange, 1 = light orange, 2 = warm accent
    private data class Dot(
        val angleDeg: Float,
        val radiusFraction: Float,
        val sizeDp: Float,
        val baseAlpha: Float,
        val tone: Int,
        var phase: Float = 0f,
        var speed: Float = 0.5f
    )

    private val colorTextPrimary = ContextCompat.getColor(context, R.color.text_primary)
    private val colorAccentSilver = ContextCompat.getColor(context, R.color.accent_silver)
    private val colorAccentGlow = ContextCompat.getColor(context, R.color.accent_glow)

    // Same orange theme as DotsOrbitView, so both animations look identical
    private val colorOrange = Color.parseColor("#FF7F2E")
    private val colorOrangeLight = Color.parseColor("#FFB07A")
    private val colorAccent = Color.parseColor("#FFD9B8")

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val centerFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#3D2C1E")
    }

    private val blipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = colorAccentSilver
    }

    private val blipStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.2f)
        color = colorAccentGlow
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorTextPrimary
        textSize = sp(13f)
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    private val subLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorAccentSilver
        textSize = sp(12f)
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    private val centerTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorTextPrimary
        textSize = sp(16f)
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    private val centerImagePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
    private val deviceImagePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }

    private val centerClipPath = Path()
    private val blipClipPath = Path()

    private val blips = mutableListOf<Blip>()

    private val dots = listOf(
        // Primary orange dots — prominent
        Dot(15f,  0.55f, 7f,  0.90f, 0),
        Dot(55f,  0.80f, 9f,  0.95f, 0),
        Dot(90f,  0.65f, 6f,  0.80f, 0),
        Dot(130f, 0.88f, 10f, 1.00f, 0),
        Dot(165f, 0.58f, 7f,  0.85f, 0),
        Dot(200f, 0.75f, 9f,  0.90f, 0),
        Dot(240f, 0.62f, 6f,  0.80f, 0),
        Dot(275f, 0.85f, 8f,  0.95f, 0),
        Dot(310f, 0.55f, 7f,  0.85f, 0),
        Dot(340f, 0.78f, 9f,  0.90f, 0),
        Dot(5f,   0.92f, 8f,  0.92f, 0),
        Dot(70f,  0.50f, 6f,  0.82f, 0),
        Dot(150f, 0.82f, 9f,  0.95f, 0),
        Dot(225f, 0.90f, 8f,  0.90f, 0),
        Dot(300f, 0.70f, 7f,  0.85f, 0),
        // Softer / smaller light-orange
        Dot(35f,  0.70f, 5f,  0.70f, 1),
        Dot(110f, 0.72f, 5f,  0.65f, 1),
        Dot(185f, 0.68f, 4f,  0.60f, 1),
        Dot(255f, 0.73f, 5f,  0.70f, 1),
        Dot(320f, 0.67f, 4f,  0.65f, 1),
        Dot(50f,  0.95f, 5f,  0.68f, 1),
        Dot(125f, 0.60f, 5f,  0.66f, 1),
        Dot(170f, 0.92f, 4f,  0.62f, 1),
        Dot(280f, 0.60f, 5f,  0.70f, 1),
        Dot(345f, 0.62f, 4f,  0.64f, 1),
        // Warm accent dots — small
        Dot(25f,  0.42f, 4f,  0.50f, 2),
        Dot(75f,  0.38f, 3f,  0.45f, 2),
        Dot(145f, 0.45f, 4f,  0.55f, 2),
        Dot(215f, 0.40f, 3f,  0.45f, 2),
        Dot(290f, 0.43f, 4f,  0.50f, 2),
        Dot(355f, 0.39f, 3f,  0.40f, 2),
        Dot(60f,  0.48f, 3f,  0.42f, 2),
        Dot(120f, 0.35f, 3f,  0.48f, 2),
        Dot(190f, 0.50f, 4f,  0.52f, 2),
        Dot(250f, 0.36f, 3f,  0.44f, 2),
        Dot(330f, 0.47f, 4f,  0.50f, 2),
    ).also { list ->
        list.forEachIndexed { i, dot ->
            dot.phase = (i * 0.6f) % (2f * PI.toFloat())
            dot.speed = 1.15f - dot.radiusFraction
        }
    }

    private var orbitAngle = 0f
    private var twinkleAngle = 0f
    private var pulsePhase = 0f
    private var isScanning = false

    private val centerGlassesBitmap: Bitmap? = loadCenterGlassesBitmap()
    private val deviceNodeBitmap: Bitmap? = loadDeviceNodeBitmap()

    var onDeviceClick: ((SmartWatch) -> Unit)? = null

    private val orbitAnimator = ValueAnimator.ofFloat(0f, 2f * PI.toFloat()).apply {
        duration = 10000L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            orbitAngle = it.animatedValue as Float
            invalidate()
        }
    }

    private val twinkleAnimator = ValueAnimator.ofFloat(0f, 2f * PI.toFloat()).apply {
        duration = 3000L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            twinkleAngle = it.animatedValue as Float
            invalidate()
        }
    }

    private val pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1200L
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.REVERSE
        interpolator = LinearInterpolator()
        addUpdateListener {
            pulsePhase = it.animatedValue as Float
            invalidate()
        }
    }

    fun setScanning(scanning: Boolean) {
        if (isScanning == scanning) return
        isScanning = scanning
        if (scanning) {
            if (!pulseAnimator.isStarted) pulseAnimator.start()
        } else {
            pulseAnimator.cancel()
            pulsePhase = 0f
            invalidate()
        }
    }

    fun updateDevices(devices: List<SmartWatch>) {
        blips.clear()
        if (width == 0 || height == 0) {
            post { updateDevices(devices) }
            return
        }

        val cx = width / 2f
        val cy = height / 2f
        val radarRadius = min(width, height) * 0.42f
        val innerSafeRadius = radarRadius * 0.28f

        devices.take(10).forEachIndexed { index, device ->
            val strength = ((device.rssi + 95f) / 60f).coerceIn(0f, 1f)
            val radiusFactor = 1f - (strength * 0.72f)
            val ringRadius = innerSafeRadius + (radarRadius - innerSafeRadius) * radiusFactor
            val angle = stableAngle(device.deviceAddress)

            val x = cx + cos(angle) * ringRadius
            val y = cy + sin(angle) * ringRadius
            val dotRadius = dp(8f) + (strength * dp(4f))
            val labelOffset = when (index % 4) {
                0 -> -dp(4f)
                1 -> dp(5f)
                2 -> -dp(9f)
                else -> dp(9f)
            }

            blips.add(
                Blip(
                    device = device,
                    x = x,
                    y = y,
                    radius = dotRadius,
                    distanceLabel = "${estimateDistanceMeters(device.rssi)} m",
                    labelYOffset = labelOffset
                )
            )
        }
        invalidate()
    }

    init {
        orbitAnimator.start()
        twinkleAnimator.start()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        orbitAnimator.cancel()
        twinkleAnimator.cancel()
        pulseAnimator.cancel()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!orbitAnimator.isRunning) orbitAnimator.start()
        if (!twinkleAnimator.isRunning) twinkleAnimator.start()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val cx = width / 2f
        val cy = height / 2f
        val radarRadius = min(width, height) * 0.42f

        drawOrbitingDots(canvas, cx, cy, radarRadius)
        drawCenter(canvas, cx, cy, radarRadius * 0.23f)
        drawBlips(canvas)
    }

    private fun drawOrbitingDots(canvas: Canvas, cx: Float, cy: Float, maxR: Float) {
        dots.forEach { dot ->
            val angleRad = Math.toRadians(dot.angleDeg.toDouble()) + orbitAngle * dot.speed
            val breathe = 1f + 0.06f * sin((orbitAngle * 2f + dot.phase).toDouble()).toFloat()
            val r = maxR * dot.radiusFraction * breathe
            val x = cx + (r * cos(angleRad)).toFloat()
            val y = cy + (r * sin(angleRad)).toFloat()

            val tw = 0.75f + 0.25f * sin((twinkleAngle + dot.phase).toDouble()).toFloat()
            val alpha = (dot.baseAlpha * tw * 255).toInt().coerceIn(0, 255)

            dotPaint.alpha = alpha
            dotPaint.color = when (dot.tone) {
                0 -> colorOrange
                1 -> colorOrangeLight
                else -> colorAccent
            }

            val radius = dp(dot.sizeDp) / 2f
            canvas.drawCircle(x, y, radius, dotPaint)
        }
    }

    private fun drawCenter(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        canvas.drawCircle(cx, cy, radius, centerFillPaint)

        val image = centerGlassesBitmap
        if (image != null) {
            val inset = dp(3f)
            val imageRadius = (radius - inset).coerceAtLeast(dp(10f))
            val dest = RectF(cx - imageRadius, cy - imageRadius, cx + imageRadius, cy + imageRadius)
            val saveCount = canvas.save()
            centerClipPath.reset()
            centerClipPath.addCircle(cx, cy, imageRadius, Path.Direction.CW)
            canvas.clipPath(centerClipPath)
            canvas.drawBitmap(image, null, dest, centerImagePaint)
            canvas.restoreToCount(saveCount)
        } else {
            val baseline = cy - (centerTextPaint.ascent() + centerTextPaint.descent()) / 2f
            canvas.drawText("IMI", cx, baseline, centerTextPaint)
        }
    }

    private fun drawBlips(canvas: Canvas) {
        blips.forEach { blip ->
            val pulse = 1f + 0.15f * pulsePhase
            val dynamicRadius = blip.radius * pulse

            val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = withAlpha(colorAccentGlow, 0.28f)
            }
            canvas.drawCircle(blip.x, blip.y, dynamicRadius + dp(5f), halo)

            val nodeImage = deviceNodeBitmap
            if (nodeImage != null) {
                val dest = RectF(
                    blip.x - dynamicRadius,
                    blip.y - dynamicRadius,
                    blip.x + dynamicRadius,
                    blip.y + dynamicRadius
                )
                val saveCount = canvas.save()
                blipClipPath.reset()
                blipClipPath.addCircle(blip.x, blip.y, dynamicRadius, Path.Direction.CW)
                canvas.clipPath(blipClipPath)
                canvas.drawBitmap(nodeImage, null, dest, deviceImagePaint)
                canvas.restoreToCount(saveCount)
            } else {
                canvas.drawCircle(blip.x, blip.y, dynamicRadius, blipPaint)
            }

            canvas.drawCircle(blip.x, blip.y, dynamicRadius, blipStrokePaint)

            canvas.drawText(
                blip.distanceLabel,
                blip.x,
                blip.y - dynamicRadius - dp(8f) + blip.labelYOffset,
                subLabelPaint
            )

            val displayName = if (blip.device.deviceName.length > 10) {
                blip.device.deviceName.take(10) + "..."
            } else {
                blip.device.deviceName
            }
            canvas.drawText(
                displayName,
                blip.x,
                blip.y + dynamicRadius + dp(18f) + blip.labelYOffset,
                labelPaint
            )
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_DOWN) return super.onTouchEvent(event)

        val tapped = blips.firstOrNull { blip ->
            val distance = hypot(event.x - blip.x, event.y - blip.y)
            distance <= blip.radius + dp(10f)
        }

        if (tapped != null) {
            performClick()
            onDeviceClick?.invoke(tapped.device)
            return true
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        return super.performClick()
    }

    private fun stableAngle(address: String): Float {
        val hash = address.hashCode().toLong() and 0xFFFFFFFFL
        val normalized = (hash % 3600L).toFloat() / 10f
        return (normalized / 180f * PI).toFloat()
    }

    private fun loadCenterGlassesBitmap(): Bitmap? {
        val candidates = listOf(
            "imi glasses image/mart1.png",
            "imi glasses image/mart1_2.png"
        )
        candidates.forEach { assetPath ->
            try {
                context.assets.open(assetPath).use { stream ->
                    val decoded = BitmapFactory.decodeStream(stream)
                    if (decoded != null) return decoded
                }
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun loadDeviceNodeBitmap(): Bitmap? {
        val candidates = listOf(
            "imi glasses image/mart1_2.png",
            "imi glasses image/mart1.png"
        )
        candidates.forEach { assetPath ->
            try {
                context.assets.open(assetPath).use { stream ->
                    val decoded = BitmapFactory.decodeStream(stream)
                    if (decoded != null) return decoded
                }
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun withAlpha(color: Int, alpha: Float): Int {
        val a = (alpha.coerceIn(0f, 1f) * 255).toInt()
        return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))
    }

    private fun estimateDistanceMeters(rssi: Int): String {
        val txPower = -59.0
        val ratio = rssi.toDouble() / txPower
        val distance = if (ratio < 1.0) {
            Math.pow(ratio, 10.0)
        } else {
            0.89976 * Math.pow(ratio, 7.7095) + 0.111
        }
        return String.format("%.1f", distance.coerceIn(0.6, 30.0))
    }

    private fun dp(value: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value,
        resources.displayMetrics
    )

    private fun sp(value: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP,
        value,
        resources.displayMetrics
    )
}
