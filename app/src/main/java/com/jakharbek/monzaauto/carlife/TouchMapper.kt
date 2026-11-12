package com.jakharbek.monzaauto.carlife

/** One finger as sent to the phone side: stable id and coordinates in video pixels. */
data class Finger(val id: Int, val x: Int, val y: Int)

/** Touch event in Android MotionEvent terms: action 0 down, 1 up, 2 move, 5 pointer down, 6 pointer up. */
data class Touch(val action: Int, val index: Int, val fingers: List<Finger>)

/**
 * Converts head unit touches into full pointer lists with stable pointer ids.
 *
 * The head unit reports at most two fingers by slot: slot 0 in x/y, slot 1 in pointerx/pointery, and puts the
 * slot of a pointer down/up into `action >> 8`. When the slot-0 finger lifts, the remaining finger moves to slot 0.
 * Pointer ids stay with the finger for its whole life, as Android Auto expects.
 */
class TouchMapper(private val maxX: Int, private val maxY: Int) {
    /** Fingers currently down, by slot (index 0 = slot 0). */
    private val slots = ArrayList<Finger>(2)

    private fun cx(v: Int) = v.coerceIn(0, maxX)
    private fun cy(v: Int) = v.coerceIn(0, maxY)

    private fun active() = slots.sortedBy { it.id }

    private fun indexOf(id: Int) = active().indexOfFirst { it.id == id }

    private fun freeId() = (0..1).first { id -> slots.none { it.id == id } }

    fun reset() = slots.clear()

    /** TOUCH_ACTION: MotionEvent-like action with the optional second finger. */
    fun action(action: Int, x: Int, y: Int, px: Int, py: Int): List<Touch> {
        val code = action and 0xFF
        val slot = (action shr 8).coerceIn(0, 1)
        val hasSecond = px != 0 || py != 0
        val out = ArrayList<Touch>()

        fun update() {
            if (slots.isNotEmpty()) slots[0] = slots[0].copy(x = cx(x), y = cy(y))
            if (slots.size > 1 && hasSecond) slots[1] = slots[1].copy(x = cx(px), y = cy(py))
        }

        when (code) {
            0 -> {
                slots.clear()
                slots += Finger(0, cx(x), cy(y))
                out += Touch(0, 0, active())
            }
            2 -> {
                if (slots.isEmpty()) {
                    // first event we see is a move: treat it as the start of the gesture
                    slots += Finger(0, cx(x), cy(y))
                    out += Touch(0, 0, active())
                } else {
                    update()
                    out += Touch(2, 0, active())
                }
            }
            1 -> {
                if (slots.isEmpty()) return out
                update()
                out += Touch(1, 0, active())
                slots.clear()
            }
            5 -> {
                if (slots.isEmpty()) {
                    slots += Finger(0, cx(x), cy(y))
                    out += Touch(0, 0, active())
                }
                if (slots.size >= 2) return out
                update()
                val f = if (hasSecond) Finger(freeId(), cx(px), cy(py)) else Finger(freeId(), cx(x), cy(y))
                slots += f
                out += Touch(5, indexOf(f.id), active())
            }
            6 -> {
                if (slots.isEmpty()) return out
                update()
                val lifting = slots[if (slot < slots.size) slot else slots.lastIndex]
                if (slots.size == 1) {
                    out += Touch(1, 0, active())
                    slots.clear()
                    return out
                }
                out += Touch(6, indexOf(lifting.id), active())
                slots.remove(lifting) // the other finger is now in slot 0
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
