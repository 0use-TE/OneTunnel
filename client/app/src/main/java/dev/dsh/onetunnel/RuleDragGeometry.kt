// SPDX-License-Identifier: GPL-3.0-or-later
package dev.dsh.onetunnel

import kotlin.math.abs

internal data class RuleDragSlot(val index: Int, val top: Int, val height: Int)

/** Viewport coordinates: scrolling moves the slots, not the held row. */
internal object RuleDragGeometry {
    fun closestIndex(center: Float, slots: List<RuleDragSlot>): Int? =
        if (!center.isFinite()) null else slots.minByOrNull { abs(center - (it.top + it.height / 2f)) }?.index

    fun scrollVelocity(pointer: Float, start: Int, end: Int, edge: Float, maxSpeed: Float): Float {
        if (!pointer.isFinite() || end <= start || edge <= 0 || maxSpeed <= 0) return 0f
        val band = minOf(edge, (end - start) / 2f)
        return when {
            pointer < start + band -> -maxSpeed * ((start + band - pointer) / band).coerceIn(0f, 1f)
            pointer > end - band -> maxSpeed * ((pointer - end + band) / band).coerceIn(0f, 1f)
            else -> 0f
        }
    }
}
