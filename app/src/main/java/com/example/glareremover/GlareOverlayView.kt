package com.example.glareremover

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

/**
 * Оверлей, который рисует поверх PreviewView:
 *   1. Полупрозрачные жёлтые контуры зон бликов (в режиме маски)
 *   2. Скорректированный bitmap кадра (в режиме удаления бликов)
 *
 * Важно: GlareOverlayView перекрывает PreviewView — если overlayBitmap == null,
 * просто ничего не рисуем (PreviewView виден насквозь).
 */
class GlareOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // Режимы отображения
    enum class DisplayMode {
        PROCESSED_FRAME,   // показываем обработанный bitmap (удалены блики)
        GLARE_MASK_ONLY,   // показываем только маску бликов поверх превью
        OVERLAY_ON_PREVIEW // показываем маску поверх живого превью (PreviewView под нами)
    }

    var displayMode: DisplayMode = DisplayMode.OVERLAY_ON_PREVIEW
        set(value) { field = value; postInvalidate() }

    // Текущий обработанный кадр
    private var overlayBitmap: Bitmap? = null

    // Список прямоугольников бликов для отрисовки контуров
    private var glareRegions: List<RectF> = emptyList()

    // Матрица для масштабирования — переиспользуем
    private val drawMatrix = Matrix()

    // Paint для обработанного кадра
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    // Paint для контура зоны блика (жёлтая обводка)
    private val glareStrokePaint = Paint().apply {
        color = Color.argb(220, 255, 220, 0)
        style = Paint.Style.STROKE
        strokeWidth = 3f
        isAntiAlias = true
    }

    // Paint для заливки зоны блика (полупрозрачный жёлтый)
    private val glareFillPaint = Paint().apply {
        color = Color.argb(70, 255, 220, 0)
        style = Paint.Style.FILL
    }

    // Paint для текста "GLARE" внутри зоны
    private val glareTextPaint = Paint().apply {
        color = Color.argb(200, 255, 200, 0)
        textSize = 18f
        isAntiAlias = true
        typeface = Typeface.DEFAULT_BOLD
    }

    /**
     * Обновить обработанный кадр (вызывается из фонового потока).
     */
    fun updateFrame(bitmap: Bitmap?, regions: List<RectF> = emptyList()) {
        overlayBitmap = bitmap
        glareRegions = regions
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val bitmap = overlayBitmap

        when (displayMode) {
            DisplayMode.PROCESSED_FRAME -> {
                // Рисуем обработанный bitmap на весь View
                if (bitmap != null) {
                    val src = RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
                    val dst = RectF(0f, 0f, width.toFloat(), height.toFloat())
                    drawMatrix.setRectToRect(src, dst, Matrix.ScaleToFit.FILL)
                    canvas.drawBitmap(bitmap, drawMatrix, bitmapPaint)
                }
            }

            DisplayMode.GLARE_MASK_ONLY, DisplayMode.OVERLAY_ON_PREVIEW -> {
                // Рисуем только контуры/заливки зон бликов поверх живого PreviewView
                // (сам PreviewView находится под нами в Z-order)
                drawGlareRegions(canvas)
            }
        }
    }

    /**
     * Рисует прямоугольные зоны бликов с заливкой и меткой.
     * Координаты в glareRegions нормализованы (0..1) — масштабируем к размеру View.
     */
    private fun drawGlareRegions(canvas: Canvas) {
        if (glareRegions.isEmpty()) return

        for (region in glareRegions) {
            val left   = region.left   * width
            val top    = region.top    * height
            val right  = region.right  * width
            val bottom = region.bottom * height

            val rect = RectF(left, top, right, bottom)

            // Заливка
            canvas.drawRoundRect(rect, 8f, 8f, glareFillPaint)
            // Контур
            canvas.drawRoundRect(rect, 8f, 8f, glareStrokePaint)

            // Метка "БЛИК" если зона достаточно большая
            val regionWidth = right - left
            if (regionWidth > 60f) {
                canvas.drawText("БЛИК", left + 4f, top + 18f, glareTextPaint)
            }
        }
    }
}