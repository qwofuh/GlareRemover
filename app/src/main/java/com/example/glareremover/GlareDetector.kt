package com.example.glareremover

import android.graphics.PointF
import android.graphics.RectF
import android.util.Log
import org.opencv.core.*
import org.opencv.imgproc.Imgproc

/**
 * GlareDetector отвечает только за ОБНАРУЖЕНИЕ бликов.
 * Возвращает нормализованные (0..1) прямоугольники зон бликов
 * и битовую маску для каждой зоны.
 *
 * Алгоритм:
 *   1. Конвертация RGBA → LAB (L*a*b*)
 *      LAB лучше чем HSV для детекции бликов: канал L (lightness) линейно
 *      соответствует восприятию яркости, не зависит от цветовой температуры освещения.
 *   2. CLAHE (Contrast Limited Adaptive Histogram Equalization) на L-канале —
 *      адаптивно нормализует яркость, поэтому порог не нужно менять
 *      при разном освещении (это главное улучшение vs фиксированный порог 230).
 *   3. Threshold с адаптивным порогом (пользователь может подстраивать через UI).
 *   4. Морфология: OPEN убирает шум, CLOSE заполняет дыры.
 *   5. findContours + минимальный размер фильтр.
 */
class GlareDetector(
    // Порог яркости в пространстве CLAHE-L (0..255).
    // Рекомендуется 160–220. Меньше = агрессивнее, больше = осторожнее.
    var brightnessThreshold: Int = 200
) {
    companion object {
        private const val TAG = "GlareDetector"

        // Минимальная площадь контура блика в пикселях (отсекаем шум)
        private const val MIN_GLARE_AREA = 10.0
        private const val MAX_GLARE_FRACTION = 0.99
    }

    /**
     * Данные о найденных бликах в ROI.
     * @param mask       Бинарная маска (CV_8UC1, 255 = блик), размер == roi
     * @param contours   Контуры бликов в координатах roi
     * @param hasGlare   True если есть хотя бы один значимый блик
     */
    data class GlareResult(
        val mask: Mat,
        val contours: List<MatOfPoint>,
        val hasGlare: Boolean
    )

    /**
     * Обнаруживает блики в переданном ROI (RGBA Mat).
     * Caller отвечает за release() GlareResult.mask и каждого contour в contours.
     */
    fun detectInRoi(roi: Mat): GlareResult {
        val mask = Mat()
        val contours = mutableListOf<MatOfPoint>()

        if (roi.empty() || roi.rows() < 4 || roi.cols() < 4) {
            return GlareResult(mask, contours, false)
        }

        val lab     = Mat()
        val lChannel = Mat()
        val channels = mutableListOf<Mat>()

        try {
            // RGBA → LAB → L-канал
            val bgr = Mat()
            Imgproc.cvtColor(roi, bgr, Imgproc.COLOR_RGBA2BGR)
            Imgproc.cvtColor(bgr, lab, Imgproc.COLOR_BGR2Lab)
            bgr.release()

            Core.split(lab, channels)
            channels[0].copyTo(lChannel)

            // Находим порог как перцентиль яркости
            // Берём топ brightnessThreshold% самых ярких пикселей
            // brightnessThreshold теперь означает: "маскировать верхние N% по яркости"
            // Например 10 = маскировать 10% самых ярких пикселей
            val lFlat = lChannel.reshape(1, 1)
            val sorted = Mat()
            Core.sort(lFlat, sorted, Core.SORT_ASCENDING)
            val totalPixels = sorted.cols()
            val cutoffIndex = (totalPixels * (1.0 - brightnessThreshold / 100.0)).toInt()
                .coerceIn(0, totalPixels - 1)
            val thresholdValue = sorted.get(0, cutoffIndex)[0]
            sorted.release()
            lFlat.release()

            // Применяем порог
            Imgproc.threshold(
                lChannel, mask,
                thresholdValue,
                255.0,
                Imgproc.THRESH_BINARY
            )

            // Морфология
            val kernel = Imgproc.getStructuringElement(
                Imgproc.MORPH_ELLIPSE, Size(5.0, 5.0)
            )
            Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_OPEN, kernel)
            Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_CLOSE, kernel)
            kernel.release()

            // Расширяем маску
            val dilateKernel = Imgproc.getStructuringElement(
                Imgproc.MORPH_ELLIPSE, Size(20.0, 20.0)
            )
            Imgproc.dilate(mask, mask, dilateKernel)
            dilateKernel.release()

            // Контуры
            val rawContours = mutableListOf<MatOfPoint>()
            val hierarchy = Mat()
            Imgproc.findContours(
                mask.clone(),
                rawContours,
                hierarchy,
                Imgproc.RETR_EXTERNAL,
                Imgproc.CHAIN_APPROX_SIMPLE
            )
            hierarchy.release()

            val totalArea = (roi.rows() * roi.cols()).toDouble()
            for (c in rawContours) {
                contours.add(c)
            }

            return GlareResult(mask, contours, contours.isNotEmpty())

        } catch (e: Exception) {
            Log.e(TAG, "detectInRoi error: ${e.message}")
            mask.release()
            contours.forEach { it.release() }
            return GlareResult(Mat(), emptyList(), false)
        } finally {
            lab.release()
            lChannel.release()
            channels.forEach { it.release() }
        }
    }
}