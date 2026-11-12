package com.jakharbek.monzaauto.ui

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import com.jakharbek.monzaauto.Diag
import com.jakharbek.monzaauto.MainActivity
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Opens every screen with the real UI code, walks through the wizard and the diagnostics,
 * checks that nothing crashes and that the expected texts are there, and saves pictures of the screens
 * to app/build/screens/ for a visual check.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w400dp-h860dp-xhdpi")
class ScreensTest {
    private val out = File("build/screens").apply { mkdirs() }

    private fun settle() {
        repeat(20) {
            Thread.sleep(50)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    /** Draws the whole scrollable content, not just the visible part. */
    private fun shot(a: Activity, name: String) {
        val root = a.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        val content = (root as? ScrollView)?.getChildAt(0) ?: root
        val w = 400 * 2
        content.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        content.layout(0, 0, content.measuredWidth, content.measuredHeight)
        val bmp = Bitmap.createBitmap(w, content.measuredHeight.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        content.draw(c)
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun texts(v: View): List<String> = when (v) {
        is TextView -> listOf(v.text.toString())
        is ViewGroup -> (0 until v.childCount).flatMap { texts(v.getChildAt(it)) }
        else -> emptyList()
    }

    private fun allText(a: Activity) = texts(a.window.decorView).joinToString("\n")

    private fun click(a: Activity, label: String) {
        fun find(v: View): Button? = when {
            v is Button && v.text.toString() == label -> v
            v is ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { find(v.getChildAt(it)) }
            else -> null
        }
        val b = find(a.window.decorView) ?: error("no button '$label' in:\n${allText(a)}")
        b.performClick()
        settle()
    }

    @Test
    fun mainScreen() {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        settle()
        val t = allText(a)
        assertTrue(t.contains("Мастер настройки") && t.contains("Диагностика") && t.contains("Состояние"))
        shot(a, "1-main")
    }

    @Test
    fun wizardAllSteps() {
        val a = Robolectric.buildActivity(WizardActivity::class.java).setup().get()
        settle()
        val titles = listOf("Добро пожаловать", "Android Auto", "Разрешения", "Работа в фоне", "Сертификат",
            "Сервер головного устройства", "Подключение к машине", "Итог")
        for ((i, title) in titles.withIndex()) {
            settle()
            val t = allText(a)
            assertTrue("step ${i + 1}: $t", t.contains("Шаг ${i + 1} из 8") && t.contains(title))
            if (i > 0) assertTrue("step ${i + 1} shows checks: $t", !t.contains("Проверяю…"))
            shot(a, "2-wizard-${i + 1}")
            if (i < titles.lastIndex) click(a, "Далее")
        }
        click(a, "Назад")
        assertTrue(allText(a).contains("Шаг 7 из 8"))
    }

    @Test
    fun diagnosticsFindsAnotherAppForTheHeadUnit() {
        val ctx = org.robolectric.RuntimeEnvironment.getApplication()
        val ri = android.content.pm.ResolveInfo().apply {
            activityInfo = android.content.pm.ActivityInfo().apply {
                packageName = "com.example.carapp"
                name = "com.example.carapp.Main"
                applicationInfo = android.content.pm.ApplicationInfo().apply {
                    packageName = "com.example.carapp"
                    nonLocalizedLabel = "Другое приложение"
                }
            }
        }
        shadowOf(ctx.packageManager).addResolveInfoForIntent(
            android.content.Intent(android.hardware.usb.UsbManager.ACTION_USB_ACCESSORY_ATTACHED), ri,
        )
        val a = Robolectric.buildActivity(DiagnosticsActivity::class.java).setup().get()
        settle()
        val t = allText(a)
        assertTrue(t, t.contains("Приложение по умолчанию для магнитолы"))
        assertTrue(t, t.contains("«Другое приложение»"))
        assertTrue(t, t.contains("Настройки «Другое приложение» по умолчанию"))
        shot(a, "4-diagnostics-default-app")
    }

    @Test
    fun diagnosticsShowsProblemsWithFixes() {
        Diag.carStage = Diag.CarStage.CLOSED
        Diag.carClosedReason = "USB отключён"
        Diag.aaStage = Diag.AaStage.CLOSED
        Diag.aaRefused = true
        val a = Robolectric.buildActivity(DiagnosticsActivity::class.java).setup().get()
        settle()
        val t = allText(a)
        assertTrue(t, t.contains("Нужно исправить"))
        assertTrue(t, t.contains("Android Auto не установлен"))
        assertTrue(t, t.contains("Установить Android Auto"))
        assertTrue(t, t.contains("Связь прервалась: USB отключён"))
        shot(a, "3-diagnostics")
        Diag.carStage = Diag.CarStage.NONE
        Diag.aaStage = Diag.AaStage.NONE
        Diag.aaRefused = false
    }
}
