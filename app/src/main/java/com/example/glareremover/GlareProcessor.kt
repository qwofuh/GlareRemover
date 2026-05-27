package com.example.glareremover

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import android.util.Log
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc

/**
 * GlareProcessor: координирует детекцию и устранение бликов.
 *
 * Изменения по сравнению с исходным кодом:
 *   • Детекция вынесена в GlareDetector (LAB + CLAHE вместо HSV + фиксированный порог)
 *   • Inpainting применяется ТОЛЬКО к пикселям маски — не к целой ROI
 *   • Возвращает и нормализованные RectF зон (для UI), и обработанный Mat
 *   • Правильный MediaPipe landmark mapping (478 точек)
 */
class GlareProcessor(private val detector: GlareDetector) {

    companion object {
        private const val TAG = "GlareProcessor"

        // ── MediaPipe Face Landmarker 478 точек ───────────────────────────────
        // Левый глаз (со стороны человека)
        private const val L_EYE_L = 33;  private const val L_EYE_R = 133
        private const val L_EYE_T = 159; private const val L_EYE_B = 145

        // Правый глаз
        private const val R_EYE_L = 263; private const val R_EYE_R = 362
        private const val R_EYE_T = 386; private const val R_EYE_B = 374

        // Нос (переносица и кончик)
        private const val NOSE_BRIDGE_T = 6
        private const val NOSE_BRIDGE_B = 4
        private const val NOSE_L = 129; private const val NOSE_R = 358

        // Лоб
        private const val FOREHEAD_TOP = 10
        private const val BROW_L = 70;  private const val BROW_R = 300

        // Щёки
        private const val CHEEK_L_T = 234; private const val CHEEK_L_B = 132
        private const val CHEEK_R_T = 454; private const val CHEEK_R_B = 361

        // Радиус inpainting (5–7 оптимально: больше = медленнее)
        private const val INPAINT_RADIUS = 5.0

        private const val MIN_LANDMARKS = 468 // FaceLandmarker даёт 468 или 478
    }

    /**
     * Результат обработки одного кадра.
     * @param processedBitmap  Bitmap с удалёнными бликами (или исходный, если лица нет)
     * @param glareRegions     Нормализованные (0..1) RectF зон бликов для UI
     * @param glareCount       Суммарное число найденных контуров бликов
     * @param processingMs     Время обработки в миллисекундах
     */
    data class ProcessResult(
        val processedBitmap: Bitmap,
        val glareRegions: List<RectF>,
        val glareCount: Int,
        val processingMs: Long
    )

    /**
     * Главный метод: принимает Bitmap (ARGB_8888) и landmarks от MediaPipe.
     * Возвращает ProcessResult.
     */
    fun process(bitmap: Bitmap, landmarks: List<PointF?>): ProcessResult {
        val t0 = System.currentTimeMillis()

        if (landmarks.size < MIN_LANDMARKS) {
            return ProcessResult(bitmap, emptyList(), 0, 0L)
        }

        val inputMat = Mat()
        Utils.bitmapToMat(bitmap, inputMat) // ARGB → RGBA (OpenCV порядок)

        val resultMat = inputMat.clone()
        val allRegions = mutableListOf<RectF>()
        var totalGlare = 0

        // Обрабатываем каждую зону лица
        val zones = buildZones(landmarks, inputMat.cols(), inputMat.rows())

        for (zone in zones) {
            if (zone.rect == null) continue

            val roi = resultMat.submat(zone.rect)
            val detected = detector.detectInRoi(roi)

            if (detected.hasGlare) {
                totalGlare += detected.contours.size
                inpaintRoi(roi, detected.mask)

                // Нормализуем координаты ROI к 0..1 относительно всего кадра
                val normRect = RectF(
                    zone.rect.x.toFloat() / inputMat.cols(),
                    zone.rect.y.toFloat() / inputMat.rows(),
                    (zone.rect.x + zone.rect.width).toFloat() / inputMat.cols(),
                    (zone.rect.y + zone.rect.height).toFloat() / inputMat.rows()
                )
                allRegions.add(normRect)
            }

            // Освобождаем ресурсы детекции
            detected.mask.release()
            detected.contours.forEach { it.release() }
            roi.release()
        }

        val resultBitmap = Bitmap.createBitmap(resultMat.cols(), resultMat.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(resultMat, resultBitmap)

        inputMat.release()
        resultMat.release()

        return ProcessResult(
            processedBitmap = resultBitmap,
            glareRegions = allRegions,
            glareCount = totalGlare,
            processingMs = System.currentTimeMillis() - t0
        )
    }

    // ── Внутренние вспомогательные структуры ─────────────────────────────────

    private data class FaceZone(val name: String, val rect: Rect?)

    /**
     * Строит список зон лица с Rect для submat.
     * Landmarks нормализованы 0..1 → умножаем на cols/rows.
     */
    private fun buildZones(lm: List<PointF?>, cols: Int, rows: Int): List<FaceZone> {
        return listOf(
            FaceZone("left_eye",  buildRect(lm, cols, rows, L_EYE_L, L_EYE_R, L_EYE_T, L_EYE_B,   0.8)),
            FaceZone("right_eye", buildRect(lm, cols, rows, R_EYE_L, R_EYE_R, R_EYE_T, R_EYE_B,   0.8)),
            FaceZone("nose",      buildRect(lm, cols, rows, NOSE_L, NOSE_R, NOSE_BRIDGE_T, NOSE_BRIDGE_B, 0.5)),
            FaceZone("forehead",  buildRect(lm, cols, rows, BROW_L, BROW_R, FOREHEAD_TOP, BROW_L, 0.4)),
            FaceZone("cheek_l",   buildRect(lm, cols, rows, CHEEK_L_T, L_EYE_L, CHEEK_L_T, CHEEK_L_B, 0.4)),
            FaceZone("cheek_r",   buildRect(lm, cols, rows, R_EYE_R, CHEEK_R_T, CHEEK_R_T, CHEEK_R_B, 0.4))
        )
    }

    private fun buildRect(
        lm: List<PointF?>, cols: Int, rows: Int,
        lIdx: Int, rIdx: Int, tIdx: Int, bIdx: Int,
        pad: Double = 0.2
    ): Rect? {
        val left   = lm.getOrNull(lIdx)   ?: return null
        val right  = lm.getOrNull(rIdx)   ?: return null
        val top    = lm.getOrNull(tIdx)   ?: return null
        val bottom = lm.getOrNull(bIdx)   ?: return null

        val x1r = (minOf(left.x, right.x) * cols).toInt()
        val x2r = (maxOf(left.x, right.x) * cols).toInt()
        val y1r = (minOf(top.y, bottom.y) * rows).toInt()
        val y2r = (maxOf(top.y, bottom.y) * rows).toInt()

        if (x2r - x1r < 4 || y2r - y1r < 4) return null

        val px = ((x2r - x1r) * pad).toInt()
        val py = ((y2r - y1r) * pad).toInt()

        val x1 = (x1r - px).coerceAtLeast(0)
        val x2 = (x2r + px).coerceAtMost(cols)
        val y1 = (y1r - py).coerceAtLeast(0)
        val y2 = (y2r + py).coerceAtMost(rows)

        return if (x2 > x1 && y2 > y1) Rect(x1, y1, x2 - x1, y2 - y1) else null
    }

    /**
     * Восстанавливает пиксели под маской.
     * roi — RGBA (CV_8UC4), inpaint требует 1 или 3 канала → конвертируем.
     */
    private fun inpaintRoi(roi: Mat, mask: Mat) {
        if (roi.empty() || mask.empty()) return
        val bgr  = Mat()
        val res  = Mat()
        val rgba = Mat()
        try {
            Imgproc.cvtColor(roi, bgr, Imgproc.COLOR_RGBA2BGR)
            org.opencv.photo.Photo.inpaint(bgr, mask, res, INPAINT_RADIUS, org.opencv.photo.Photo.INPAINT_TELEA)
            Imgproc.cvtColor(res, rgba, Imgproc.COLOR_BGR2RGBA)
            rgba.copyTo(roi)
        } catch (e: Exception) {
            Log.e(TAG, "inpaintRoi: ${e.message}")
        } finally {
            bgr.release(); res.release(); rgba.release()
        }
    }
}