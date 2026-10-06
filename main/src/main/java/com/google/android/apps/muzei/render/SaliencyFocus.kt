/*
 * Copyright 2014 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.apps.muzei.render

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Cheap, dependency-free horizontal "point of interest" detection used to
 * center wallpapers on their subject rather than on the geometric center.
 *
 * Each pixel is scored by a mix of
 *  - color contrast against the image's mean color (frequency-tuned saliency), and
 *  - local edge strength (detail attracts the eye, flat sky/walls do not).
 *
 * Scores are collapsed into a per-column profile. A crop window of the visible
 * width is then slid across the profile to find the position that captures the
 * most saliency, with a small center prior so ambiguous images stay centered.
 *
 * Intended to run on a tiny (~64px) thumbnail.
 */
class SaliencyFocus private constructor(private val columns: FloatArray) {

    companion object {
        private const val EDGE_WEIGHT = 0.5f
        private const val CENTER_PRIOR = 0.15f

        /** Returns null if the bitmap is unusable. */
        fun analyze(bitmap: Bitmap?): SaliencyFocus? {
            if (bitmap == null || bitmap.width < 3 || bitmap.height < 3) {
                return null
            }
            val w = bitmap.width
            val h = bitmap.height
            val pixels = IntArray(w * h)
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

            val luma = FloatArray(w * h)
            var meanR = 0f
            var meanG = 0f
            var meanB = 0f
            for (i in pixels.indices) {
                val p = pixels[i]
                val r = Color.red(p)
                val g = Color.green(p)
                val b = Color.blue(p)
                meanR += r
                meanG += g
                meanB += b
                luma[i] = 0.299f * r + 0.587f * g + 0.114f * b
            }
            meanR /= pixels.size
            meanG /= pixels.size
            meanB /= pixels.size

            val columns = FloatArray(w)
            for (y in 1 until h - 1) {
                for (x in 1 until w - 1) {
                    val i = y * w + x
                    val p = pixels[i]
                    val dr = Color.red(p) - meanR
                    val dg = Color.green(p) - meanG
                    val db = Color.blue(p) - meanB
                    val contrast = sqrt(dr * dr + dg * dg + db * db)
                    val edge = abs(luma[i + 1] - luma[i - 1]) + abs(luma[i + w] - luma[i - w])
                    columns[x] += contrast + EDGE_WEIGHT * edge
                }
            }
            // Edge columns were skipped by the gradient pass; mirror neighbors
            columns[0] = columns[1]
            columns[w - 1] = columns[w - 2]
            smooth(columns)
            return SaliencyFocus(columns)
        }

        private fun smooth(values: FloatArray) {
            val copy = values.copyOf()
            for (i in values.indices) {
                val a = copy[max(0, i - 1)]
                val c = copy[min(values.size - 1, i + 1)]
                values[i] = (a + 2 * copy[i] + c) / 4f
            }
        }

        private fun min(a: Int, b: Int) = if (a < b) a else b
    }

    /**
     * @param visibleFraction the fraction of the image width that is visible (0..1]
     * @return the horizontal center of the best window in 0..1 image coordinates
     * (0.5 means keep centered)
     */
    fun focusX(visibleFraction: Float): Float {
        val n = columns.size
        val window = (visibleFraction * n).toInt()
        if (visibleFraction >= 1f || window < 1 || window >= n) {
            return 0.5f
        }
        val total = columns.sum()
        if (total <= 0f) {
            return 0.5f
        }
        // Prefix sums for O(1) window sums
        val prefix = FloatArray(n + 1)
        for (i in 0 until n) {
            prefix[i + 1] = prefix[i] + columns[i]
        }
        var bestStart = (n - window) / 2
        var bestScore = Float.NEGATIVE_INFINITY
        val maxStart = n - window
        for (start in 0..maxStart) {
            val captured = (prefix[start + window] - prefix[start]) / total
            val centerOffset = abs((start + window / 2f) / n - 0.5f) * 2f
            val score = captured - CENTER_PRIOR * centerOffset
            if (score > bestScore) {
                bestScore = score
                bestStart = start
            }
        }
        return (bestStart + window / 2f) / n
    }
}
