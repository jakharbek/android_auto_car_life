package com.jakharbek.monzaauto.carlife

/** One finger as sent to the phone side: stable id and coordinates in video pixels. */
data class Finger(val id: Int, val x: Int, val y: Int)

/** Touch event in Android MotionEvent terms: action 0 down, 1 up, 2 move, 5 pointer down, 6 pointer up. */
data class Touch(val action: Int, val index: Int, val fingers: List<Finger>)

/**
 * Converts head unit touches into full pointer lists.
 *
 * The head unit reports at most two fingers: the first in x/y, the second in pointerx/pointery,
 * and encodes the finger index of pointer down/up in `action >> 8`.
 * Gesture-style messages (separate down/move/up/click types) are converted to the same form.
 */
class TouchMapper(private val maxX: Int, private val maxY: Int) {
    private var first: Finger? = null
    private var second: Finger? = null

    /** After the first finger lifts while the second stays, the head unit reports the second one in x/y. */
    private var swapped = false

    private fun cx(v: Int) = v.coerceIn(0, maxX)
    private fun cy(v: Int) = v.coerceIn(0, maxY)

    private fun active() = listOfNotNull(first, second).sortedBy { it.id }

    fun reset() {
        first = null
        second = null
        swapped = false
    }

    /** TOUCH_ACTION: raw MotionEvent-like action with optional second finger. */
    fun action(action: Int, x: Int, y: Int, px: Int, py: Int): List<Touch> {
        val code = action and 0xFF
        val idx = action shr 8
        val hasSecond = px != 0 || py != 0
        val out = ArrayList<Touch>()
        when (code) {
            0 -> {
                reset()
                first = Finger(0, cx(x), cy(y))
                out += Touch(0, 0, active())
            }
            2 -> {
                if (first == null && second == null) {
                    first = Finger(0, cx(x), cy(y))
                    out += Touch(0, 0, active())
                    return out
                }
                if (swapped) {
                    second = second?.copy(x = cx(x), y = cy(y))
                } else {
                    first = first?.copy(x = cx(x), y = cy(y))
                    if (hasSecond) second = second?.copy(x = cx(px), y = cy(py))
                }
                out += Touch(2, 0, active())
            }
            1 -> {
                if (first == null && second == null) return out
                if (swapped) second = second?.copy(x = cx(x), y = cy(y)) else first = first?.copy(x = cx(x), y = cy(y))
                val list = active()
                out += Touch(1, 0, list)
                reset()
            }
            5 -> {
                if (first == null) {
                    first = Finger(0, cx(x), cy(y))
                    out += Touch(0, 0, active())
                }
                second = if (hasSecond) Finger(1, cx(px), cy(py)) else Finger(1, cx(x), cy(y))
                val list = active()
                out += Touch(5, list.indexOfFirst { it.id == 1 }, list)
            }
            6 -> {
                val liftId = if (idx == 1 || swapped) 1 else 0
                val list = active()
                val i = list.indexOfFirst { it.id == liftId }
                if (i < 0) return out
                if (list.size == 1) {
                    out += Touch(1, 0, list)
                    reset()
                    return out
                }
                out += Touch(6, i, list)
                if (liftId == 1) {
                    second = null
                    swapped = false
                } else {
                    first = null
                    swapped = true
                }
            }
        }
        return out
    }

    /** Gesture-style messages: 1 down, 2 move, 3 up, 4 single click. */
    fun gesture(kind: Int, x: Int, y: Int): List<Touch> = when (kind) {
        1 -> action(0, x, y, 0, 0)
        2 -> action(2, x, y, 0, 0)
        3 -> action(1, x, y, 0, 0)
        4 -> action(0, x, y, 0, 0) + action(1, x, y, 0, 0)
        else -> emptyList()
    }
}
