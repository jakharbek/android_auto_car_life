package com.jakharbek.monzaauto

import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.jakharbek.monzaauto.setup.Fix
import com.jakharbek.monzaauto.setup.Probes
import com.jakharbek.monzaauto.ui.BaseActivity
import com.jakharbek.monzaauto.ui.DiagnosticsActivity
import com.jakharbek.monzaauto.ui.Ui
import com.jakharbek.monzaauto.ui.WizardActivity

class MainActivity : BaseActivity() {
    private lateinit var settings: Settings
    private lateinit var statusView: TextView
    private lateinit var certView: TextView
    private lateinit var logView: TextView
    private val renderNow = Runnable { render() }

    /** Coalesces frequent log/status updates into one redraw. */
    private val onChange: () -> Unit = {
        statusView.removeCallbacks(renderNow)
        statusView.postDelayed(renderNow, 250)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.dir = getExternalFilesDir("logs")
        settings = Settings(this)
        setContentView(ui.insetSafe(buildUi()))
        if (savedInstanceState == null && !WizardActivity.isDone(this)) {
            startActivity(Intent(this, WizardActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        Status.listen(onChange)
        Log.listen(onChange)
    }

    override fun onPause() {
        Status.unlisten(onChange)
        Log.unlisten(onChange)
        super.onPause()
    }

    override fun refresh() {
        renderCert()
        render()
    }

    // ---------- UI ----------

    private fun buildUi(): View {
        val col = ui.column(16)
        col.addView(ui.title(getString(R.string.app_name)))
        col.addView(ui.muted("Android Auto на штатной магнитоле через CarLife · версия ${BuildConfig.VERSION_NAME}"))
        col.addView(ui.gap(8))

        statusView = ui.body("", 15f)
        col.addView(ui.card(ui.body("Состояние", 16f).apply { setTypeface(typeface, Typeface.BOLD) }, statusView))
        col.addView(ui.gap(8))

        val top = ui.row()
        top.addView(ui.button("Мастер настройки") { startActivity(Intent(this, WizardActivity::class.java)) }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        top.addView(View(this), LinearLayout.LayoutParams(ui.dp(10), 1))
        top.addView(ui.button("Диагностика") { startActivity(Intent(this, DiagnosticsActivity::class.java)) }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        col.addView(top)

        col.addView(ui.heading("Управление"))
        col.addView(ui.button("Открыть настройки Android Auto", primary = false) { openAaSettings() }, ui.fullWidth())
        col.addView(ui.button("Подключить Android Auto снова", primary = false) { perform(Fix.CONNECT_AA) }, ui.fullWidth())
        col.addView(ui.button("Подключиться к магнитоле", primary = false) { perform(Fix.ATTACH_CAR) }, ui.fullWidth())
        col.addView(ui.button("Остановить", primary = false) { LinkService.command(this, LinkService.ACTION_STOP) }, ui.fullWidth())

        col.addView(ui.heading("Звук"))
        val group = RadioGroup(this)
        val options = listOf(
            Settings.Audio.AUTO to "Авто (как скажет магнитола)",
            Settings.Audio.USB to "Через USB в магнитолу",
            Settings.Audio.PHONE to "С телефона (Bluetooth машины)",
        )
        for ((value, label) in options) {
            group.addView(RadioButton(this).apply {
                id = View.generateViewId()
                text = label
                textSize = 15f
                isChecked = settings.audio == value
                setOnCheckedChangeListener { _, checked -> if (checked) settings.audio = value }
            })
        }
        col.addView(group)

        col.addView(ui.heading("Изображение"))
        col.addView(ui.muted("Плотность экрана Android Auto (DPI, ${Settings.DPI_MIN}–${Settings.DPI_MAX}): больше — крупнее кнопки и шрифт."))
        col.addView(numberField(settings.dpi) { settings.dpi = it })
        col.addView(ui.muted("Битрейт видео, кбит/с (1000–12000): больше — чётче, но тяжелее для кабеля и магнитолы."))
        col.addView(numberField(settings.bitrateKbps) { settings.bitrateKbps = it })
        col.addView(ui.muted("Изменения применяются при следующем подключении Android Auto."))

        col.addView(ui.heading("Сертификат головного устройства"))
        certView = ui.body("", 14f)
        col.addView(certView)
        col.addView(ui.button("Загрузить сертификат и ключ (.pem)", primary = false) { perform(Fix.LOAD_CERT) }, ui.fullWidth())

        col.addView(ui.heading("Журнал"))
        col.addView(ui.button("Поделиться журналом", primary = false) { shareLog() }, ui.fullWidth())
        logView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 10f
            setTextColor(Ui.MUTED)
            setTextIsSelectable(true)
        }
        col.addView(logView)

        return ScrollView(this).apply { addView(col, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)) }
    }

    private fun numberField(value: Int, save: (Int) -> Unit): View {
        val row = ui.row()
        val field = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(value.toString())
        }
        row.addView(field, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        row.addView(ui.button("Сохранить", primary = false) {
            val v = field.text.toString().toIntOrNull()
            if (v == null) {
                Toast.makeText(this, "Введите число", Toast.LENGTH_SHORT).show()
            } else {
                save(v)
                Toast.makeText(this, "Сохранено", Toast.LENGTH_SHORT).show()
            }
        })
        return row
    }

    private fun render() {
        statusView.text = "Сервис: ${if (Status.running) "работает" else "остановлен"}\n" +
            "USB: ${Status.usb}\nМагнитола: ${Status.car}\nAndroid Auto: ${Status.aa}"
        logView.text = Log.lines().takeLast(80).joinToString("\n")
    }

    private fun renderCert() {
        val c = Probes.certificateCheck(this)
        certView.text = c.detail + (c.todo?.let { "\n$it" } ?: "")
        certView.setTextColor(Ui.color(c.level))
    }

    private fun shareLog() {
        val file = Log.files().lastOrNull()
        if (file == null) {
            Toast.makeText(this, "Журнал пуст", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = Uri.parse("content://${packageName}.logs/${file.name}")
        val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, "${getString(R.string.app_name)}: ${file.name}")
            .putExtra(Intent.EXTRA_TEXT, "Журнал Monza Auto")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(i, "Журнал"))
    }
}
