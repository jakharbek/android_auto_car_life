package com.jakharbek.monzaauto.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.ScrollView
import com.jakharbek.monzaauto.Log
import com.jakharbek.monzaauto.setup.Check
import com.jakharbek.monzaauto.setup.Level
import com.jakharbek.monzaauto.setup.Probes
import com.jakharbek.monzaauto.setup.Rules
import java.util.concurrent.Executors

/** Full check of the phone, the head unit link and Android Auto, with what to do for each problem. */
class DiagnosticsActivity : BaseActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var box: LinearLayout
    private var last: List<Check> = emptyList()
    private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = ui.column(18)
        col.addView(ui.title("Диагностика"))
        col.addView(ui.muted("Проверяет телефон, Android Auto, сертификат, подключение к магнитоле и говорит, что исправить."))
        col.addView(ui.gap(8))
        val buttons = ui.row()
        buttons.addView(ui.button("Проверить снова") { run() }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        buttons.addView(android.view.View(this), LinearLayout.LayoutParams(ui.dp(10), 1))
        buttons.addView(ui.button("Отправить отчёт", primary = false) { share() }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        col.addView(buttons)
        box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(box)
        setContentView(ScrollView(this).apply { addView(col, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)) })
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    override fun refresh() = run()

    private fun run() {
        if (running) return
        running = true
        box.removeAllViews()
        box.addView(ui.gap(12))
        box.addView(ui.body("Проверяю…"))
        val app = applicationContext
        worker.execute {
            val checks = try {
                Probes.all(app)
            } catch (e: Exception) {
                Log.e("DIAG: failed", e)
                emptyList()
            }
            val header = Probes.header(app)
            main.post {
                running = false
                if (isFinishing) return@post
                last = checks
                render(header, checks)
                Log.i("DIAG: " + Rules.summary(checks) + "; " + checks.joinToString("; ") { "${it.id}=${it.level}" })
            }
        }
    }

    private fun render(header: String, checks: List<Check>) {
        box.removeAllViews()
        box.addView(ui.gap(12))
        val worst = checks.minByOrNull { it.level.ordinal }?.level ?: Level.OK
        box.addView(ui.card(
            ui.body(Rules.summary(checks), 17f, Ui.color(worst)),
            ui.muted(header),
        ))
        var lastLevel: Level? = null
        for (c in checks) {
            if (c.level != lastLevel) {
                lastLevel = c.level
                box.addView(ui.heading(
                    when (c.level) {
                        Level.FAIL -> "Нужно исправить"
                        Level.WARN -> "Желательно исправить"
                        Level.OK -> "В порядке"
                        Level.INFO -> "Сведения"
                    },
                ))
            }
            box.addView(ui.checkCard(c) { perform(it.fix) }, ui.fullWidth())
            box.addView(ui.gap(10))
        }
    }

    private fun share() {
        if (last.isEmpty()) return
        val text = Rules.report(last, Probes.header(this))
        val i = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "Monza Auto: диагностика")
            .putExtra(Intent.EXTRA_TEXT, text)
        startActivity(Intent.createChooser(i, "Отчёт диагностики"))
    }
}
