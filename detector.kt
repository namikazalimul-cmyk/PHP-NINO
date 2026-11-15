package com.phpnano.panel

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Détecte les pseudos (texte lumineux) et en déduit la position de la tête.
 * Tout est réglable via le slider "SENSIBILITÉ" et "HEADSHOT".
 */
class Detector {

    data class Target(
        val cx: Float,        // centre X du pseudo (coordonnées capture)
        val headY: Float,     // Y estimé de la tête
        val tagW: Float,      // largeur du pseudo (sert à dimensionner le carré)
        val moving: Boolean   // joueur en mouvement -> pseudo rouge
    )

    private var buf = IntArray(0)
    private var lastTargets: List<Target> = emptyList()

    fun detect(
        bmp: Bitmap,
        w: Int,
        h: Int,
        sens: Int,
        headOffset: Float
    ): List<Target> {
        val need = w * h
        if (buf.size < need) buf = IntArray(need)
        bmp.getPixels(buf, 0, w, 0, 0, w, h)

        val x0 = (w * 0.05f).toInt()
        val x1 = (w * 0.95f).toInt()
        val y0 = (h * 0.06f).toInt()
        val y1 = (h * 0.86f).toInt()
        val step = if (sens >= 8) 1 else 2

        // Seuils pilotés par la sensibilité (1..10)
        val lumMin = 200 - sens * 4          // 196 -> 160
        val satMax = 55 + sens * 6           // tolère les pseudos jaunâtres

        val cols = IntArray(w)
        val minY = IntArray(w) { Int.MAX_VALUE }
        val maxY = IntArray(w) { -1 }

        var y = y0
        while (y < y1) {
            val rowBase = y * w
            var x = x0
            while (x < x1) {
                val c = buf[rowBase + x]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                val mx = max(r, max(g, b))
                val mn = min(r, min(g, b))
                val lum = (r * 299 + g * 587 + b * 114) / 1000
                if (lum >= lumMin && (mx - mn) <= satMax) {
                    cols[x]++
                    if (y < minY[x]) minY[x] = y
                    if (y > maxY[x]) maxY[x] = y
                }
                x += step
            }
            y += step
        }

        // Regroupement en colonnes -> blocs = pseudos
        val needCol = if (step == 1) 4 else 3
        val runs = ArrayList<IntArray>()
        var x = x0
        var start = -1
        var yyMin = Int.MAX_VALUE
        var yyMax = -1
        var gap = 0
        while (x < x1) {
            if (cols[x] >= needCol) {
                if (start < 0) {
                    start = x
                    yyMin = Int.MAX_VALUE
                    yyMax = -1
                }
                gap = 0
                if (minY[x] < yyMin) yyMin = minY[x]
                if (maxY[x] > yyMax) yyMax = maxY[x]
            } else if (start >= 0) {
                gap++
                if (gap > 6) {
                    runs.add(intArrayOf(start, x - gap, yyMin, yyMax))
                    start = -1
                    gap = 0
                }
            }
            x++
        }
        if (start >= 0) runs.add(intArrayOf(start, x1 - 1, yyMin, yyMax))

        val raw = ArrayList<Target>()
        for (r in runs) {
            val rw = (r[1] - r[0] + 1).toFloat()
            if (rw < 12f) continue                 // trop petit
            if (rw > w * 0.45f) continue           // HUD / décor
            if (r[2] <= y0 + 2) continue            // collé en haut = HUD
            val cx = (r[0] + r[1]) / 2f
            val headY = r[3] + headOffset           // bas du pseudo -> tête
            raw.add(Target(cx, headY, rw, false))
        }

        // Détection de mouvement par rapport à la frame précédente
        val out = if (lastTargets.isEmpty()) raw else raw.map { t ->
            val p = lastTargets.minByOrNull { abs(it.cx - t.cx) }
            val moving = p != null &&
                (abs(p.cx - t.cx) > 3f || abs(p.tagW - t.tagW) > 6f)
            t.copy(moving = moving)
        }

        lastTargets = raw
        return out
    }
}