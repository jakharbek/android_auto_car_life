package com.jakharbek.monzaauto.ui

import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.ScrollView
import com.jakharbek.monzaauto.Diag
import com.jakharbek.monzaauto.Log
import com.jakharbek.monzaauto.Settings
import com.jakharbek.monzaauto.setup.Check
import com.jakharbek.monzaauto.setup.Level
import com.jakharbek.monzaauto.setup.Probes
import com.jakharbek.monzaauto.setup.Rules
import java.util.concurrent.Executors

/**
 * Step-by-step setup: each step explains what is needed, checks it on the phone right away
 * and offers a button that fixes it. Steps can be skipped; the last one sums everything up.
 */
class WizardActivity : BaseActivity() {
    companion object {
        private const val PREFS = "wizard"
        private const val KEY_DONE = "done"
        private const val KEY_STEP = "step"

        fun isDone(ctx: Context) = ctx.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_DONE, false)
    }

    private class Step(
        val title: String,
        val lines: List<String>,
        /** Checks shown under the text (run off the main thread). */
        val checks: ((Context) -> List<Check>)? = null,
        /** Re-check every couple of seconds while the step is open. */
        val live: Boolean = false,
        val extra: List<Pair<String, (WizardActivity) -> Unit>> = emptyList(),
    )

    private val steps = listOf(
        Step(
            "Добро пожаловать",
            listOf(
                "Monza Auto показывает Android Auto на штатной магнитоле Chevrolet Monza через CarLife.",
                "Понадобится: Android Auto на телефоне, USB-кабель для передачи данных и магнитола с CarLife.",
                "Мастер проверит телефон по шагам и подскажет, что включить. Любой шаг можно пропустить и вернуться позже — из главного экрана.",
            ),
        ),
        Step(
            "Android Auto",
            listOf("На телефоне должен быть установлен и включён Android Auto. Именно он рисует карты, музыку и помощника — приложение передаёт картинку в машину."),
            checks = { ctx -> listOf(Probes.aaVersion(ctx).let { (v, e) -> Rules.androidAuto(v, e) }) },
        ),
        Step(
            "Разрешения",
            listOf(
                "Микрофон — чтобы голосовой помощник Android Auto слышал вас (используется микрофон телефона).",
                "Уведомления — пока связь с машиной работает, в шторке висит уведомление; через него же её можно остановить.",
            ),
            checks = { ctx -> listOf(Rules.microphone(Probes.micGranted(ctx)), Rules.notifications(Probes.notificationsGranted(ctx), Build.VERSION.SDK_INT)) },
        ),
        Step(
            "Работа в фоне",
            listOf(
                "Пока вы едете, экран телефона гаснет, а приложение должно продолжать работать.",
                "Снимите для него ограничение батареи, иначе система может оборвать связь. На Xiaomi/Redmi дополнительно: Настройки → Приложения → Monza Auto → Контроль активности → «Нет ограничений».",
            ),
            checks = { ctx -> listOf(Rules.battery(Probes.batteryUnrestricted(ctx), Probes.backgroundRestricted(ctx))) },
        ),
        Step(
            "Сертификат",
            listOf("Android Auto подключается только к «магнитоле» с сертификатом. Обычно он уже встроен в приложение; если нет — загрузите файлы сертификата и ключа (.pem)."),
            checks = { ctx -> listOf(Probes.certificateCheck(ctx)) },
        ),
        Step(
            "Сервер головного устройства",
            listOf(
                "1. Откройте настройки Android Auto (кнопка ниже).",
                "2. Пролистайте вниз и 10 раз нажмите на «Версия» — появится сообщение о режиме разработчика (подтвердите).",
                "3. Нажмите меню ⋮ вверху справа → «Запустить сервер головного устройства».",
                "4. Вернитесь сюда и нажмите «Проверить».",
                "Сервер одноразовый: после каждого обрыва связи или перезагрузки телефона его нужно запускать заново.",
            ),
            checks = { _ -> listOf(Rules.server(Probes.serverProbe(), Diag.snapshot())) },
        ),
        Step(
            "Подключение к машине",
            listOf(
                "1. Заведите машину и дождитесь загрузки магнитолы.",
                "2. Подключите телефон кабелем к USB-разъёму машины.",
                "3. Если телефон спросит, чем открыть USB-устройство, — выберите Monza Auto и отметьте «Всегда».",
                "4. На магнитоле откройте CarLife. Через несколько секунд появится Android Auto.",
                "Состояние ниже обновляется само.",
            ),
            checks = { ctx -> listOf(Probes.usbCheck(ctx), Rules.car(Diag.snapshot()), Rules.androidAutoLink(Diag.snapshot(), true)) },
            live = true,
        ),
        Step(
            "Итог",
            listOf("Проверка всего сразу. Красное нужно исправить, жёлтое — желательно."),
            checks = { ctx -> Probes.all(ctx) },
            extra = listOf("Подробная диагностика" to { a -> a.startActivity(Intent(a, DiagnosticsActivity::class.java)) }),
        ),
    )

    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var index = 0
    private lateinit var content: LinearLayout
    private lateinit var checksBox: LinearLayout
    private var generation = 0

    private val liveTick = object : Runnable {
        override fun run() {
            if (steps[index].live) {
                runChecks()
                main.postDelayed(this, 2000)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        index = (savedInstanceState?.getInt(KEY_STEP) ?: 0).coerceIn(0, steps.lastIndex)
        content = ui.column(18)
        setContentView(ScrollView(this).apply { addView(content, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)) })
        show()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_STEP, index)
    }

    override fun onPause() {
        main.removeCallbacks(liveTick)
        super.onPause()
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    override fun refresh() {
        if (::checksBox.isInitialized) runChecks()
        main.removeCallbacks(liveTick)
        if (steps[index].live) main.postDelayed(liveTick, 2000)
    }

    private fun show() {
        val step = steps[index]
        content.removeAllViews()
        content.addView(progress())
        content.addView(ui.muted("Шаг ${index + 1} из ${steps.size}"))
        content.addView(ui.title(step.title))
        for (line in step.lines) content.addView(ui.body(line))
        checksBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(checksBox)
        if (step.checks != null) {
            for ((label, action) in step.extra) {
                content.addView(ui.gap(6))
                content.addView(ui.button(label, primary = false) { action(this) }, ui.fullWidth())
            }
            content.addView(ui.button("Проверить", primary = false) { runChecks() }, ui.fullWidth())
        }
        content.addView(ui.gap(18))
        val nav = ui.row()
        if (index > 0) {
            nav.addView(ui.button("Назад", primary = false) { go(index - 1) }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            nav.addView(View(this), LinearLayout.LayoutParams(ui.dp(10), 1))
        }
        val last = index == steps.lastIndex
        nav.addView(ui.button(if (last) "Завершить" else "Далее") { if (last) finishWizard() else go(index + 1) }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        content.addView(nav)
        refresh()
    }

    /** Segmented bar: done steps dark, the rest light. */
    private fun progress(): View = ui.row().apply {
        setPadding(0, 0, 0, ui.dp(10))
        for (i in steps.indices) {
            addView(View(this@WizardActivity).apply {
                background = GradientDrawable().apply {
                    cornerRadius = ui.dp(3).toFloat()
                    setColor(if (i <= index) Ui.PRIMARY else 0xFFD5DBE3.toInt())
                }
            }, LinearLayout.LayoutParams(0, ui.dp(6), 1f).apply { marginEnd = if (i < steps.lastIndex) ui.dp(4) else 0 })
        }
    }

    private fun go(i: Int) {
        index = i.coerceIn(0, steps.lastIndex)
        main.removeCallbacks(liveTick)
        show()
    }

    private fun runChecks() {
        val step = steps[index]
        val checks = step.checks ?: return
        val gen = ++generation
        if (checksBox.childCount == 0) checksBox.addView(ui.muted("Проверяю…"))
        val app = applicationContext
        worker.execute {
            val result = try {
                checks(app)
            } catch (e: Exception) {
                Log.e("WIZARD: check failed", e)
                emptyList()
            }
            main.post {
                if (gen != generation || isFinishing) return@post
                checksBox.removeAllViews()
                if (index == steps.lastIndex) {
                    val worst = result.minByOrNull { it.level.ordinal }?.level ?: Level.OK
                    checksBox.addView(ui.gap(8))
                    checksBox.addView(ui.card(ui.body(Rules.summary(result), 16f, Ui.color(worst))))
                }
                for (c in result) {
                    checksBox.addView(ui.gap(10))
                    checksBox.addView(ui.checkCard(c) { perform(it.fix) }, ui.fullWidth())
                }
                checksBox.addView(ui.gap(10))
            }
        }
    }

    private fun finishWizard() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_DONE, true).apply()
        Log.i("WIZARD: finished, audio=${Settings(this).audio}")
        finish()
    }
}
