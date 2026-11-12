package com.jakharbek.monzaauto.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.jakharbek.monzaauto.setup.Check
import com.jakharbek.monzaauto.setup.Level

/** Small view toolkit for the code-built screens: one look everywhere. */
class Ui(private val ctx: Context) {
    companion object {
        val PRIMARY = Color.parseColor("#1F3A5F")
        val TEXT = Color.parseColor("#1B1F24")
        val MUTED = Color.parseColor("#5B6470")
        val CARD = Color.parseColor("#F3F5F8")
        val OK = Color.parseColor("#1E8E3E")
        val WARN = Color.parseColor("#B26A00")
        val FAIL = Color.parseColor("#C5221F")
        val INFO = Color.parseColor("#3367D6")

        fun color(l: Level) = when (l) {
            Level.OK -> OK; Level.WARN -> WARN; Level.FAIL -> FAIL; Level.INFO -> INFO
        }

        fun mark(l: Level) = when (l) {
            Level.OK -> "✓"; Level.WARN -> "!"; Level.FAIL -> "✕"; Level.INFO -> "i"
        }

        fun word(l: Level) = when (l) {
            Level.OK -> "Готово"; Level.WARN -> "Внимание"; Level.FAIL -> "Нужно исправить"; Level.INFO -> "Информация"
        }
    }

    fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics).toInt()

    fun column(padding: Int = 16) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(padding), dp(padding), dp(padding), dp(padding))
    }

    fun row() = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    fun title(s: String) = TextView(ctx).apply {
        text = s
        textSize = 24f
        setTextColor(TEXT)
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, 0, 0, dp(6))
    }

    fun heading(s: String) = TextView(ctx).apply {
        text = s
        textSize = 17f
        setTextColor(TEXT)
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(14), 0, dp(6))
    }

    fun body(s: CharSequence, size: Float = 15f, color: Int = TEXT) = TextView(ctx).apply {
        text = s
        textSize = size
        setTextColor(color)
        setLineSpacing(0f, 1.15f)
        setPadding(0, dp(3), 0, dp(3))
    }

    fun muted(s: CharSequence) = body(s, 13f, MUTED)

    private fun rounded(color: Int, radius: Int = 12) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    fun card(vararg children: View) = column(14).apply {
        background = rounded(CARD)
        for (c in children) addView(c)
    }

    fun button(s: String, primary: Boolean = true, onClick: () -> Unit) = Button(ctx).apply {
        text = s
        isAllCaps = false
        textSize = 15f
        backgroundTintList = ColorStateList.valueOf(if (primary) PRIMARY else Color.parseColor("#E1E6ED"))
        setTextColor(if (primary) Color.WHITE else PRIMARY)
        setOnClickListener { onClick() }
    }

    /** Round coloured mark with a symbol. */
    fun badge(l: Level) = TextView(ctx).apply {
        text = mark(l)
        textSize = 15f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        setTypeface(typeface, Typeface.BOLD)
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color(l))
        }
        layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginEnd = dp(12) }
    }

    /** One check as a card: badge, title, finding, what to do, optional fix button. */
    fun checkCard(c: Check, onFix: ((Check) -> Unit)?): View {
        val head = row().apply {
            addView(badge(c.level))
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                addView(body(c.title, 16f).apply { setTypeface(typeface, Typeface.BOLD) })
                addView(body(word(c.level), 12f, color(c.level)))
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
        val card = card(head, body(c.detail))
        c.todo?.let { card.addView(body("Что сделать: $it", 14f, MUTED)) }
        if (onFix != null && c.fix.label.isNotEmpty() && c.level != Level.OK) {
            card.addView(button(c.fix.label) { onFix(c) })
        }
        return card
    }

    /**
     * Keeps content out from under the status bar, navigation bar and display cutout
     * (apps targeting Android 15+ are always drawn edge to edge).
     */
    fun insetSafe(root: View): View {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            root.setOnApplyWindowInsetsListener { v, insets ->
                val b = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
                v.setPadding(b.left, b.top, b.right, b.bottom)
                insets
            }
            (root as? ViewGroup)?.clipToPadding = false
        }
        return root
    }

    fun gap(h: Int = 10) = View(ctx).apply { layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(h)) }

    fun fullWidth() = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
}
