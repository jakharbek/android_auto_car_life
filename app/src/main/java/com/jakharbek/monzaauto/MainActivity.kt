package com.jakharbek.monzaauto

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings as SystemSettings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.jakharbek.monzaauto.aa.HuCredentials

class MainActivity : Activity() {
    companion object {
        private const val REQ_PERMISSIONS = 1
        private const val REQ_CERT = 2
        private const val AA_PACKAGE = "com.google.android.projection.gearhead"
    }

    private lateinit var settings: Settings
    private lateinit var statusView: TextView
    private lateinit var certView: TextView
    private lateinit var logView: TextView
    private val renderNow = Runnable { render() }

    /** Coalesces frequent log/status updates into one redraw. */
    private val refresh: () -> Unit = {
        statusView.removeCallbacks(renderNow)
        statusView.postDelayed(renderNow, 250)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.dir = getExternalFilesDir("logs")
        settings = Settings(this)
        setContentView(buildUi())
        askPermissions()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == UsbManager.ACTION_USB_ACCESSORY_ATTACHED) {
            @Suppress("DEPRECATION")
            val acc = intent.getParcelableExtra<UsbAccessory>(UsbManager.EXTRA_ACCESSORY)
            Log.i("UI: accessory attached")
            LinkService.attach(this, acc)
        }
    }

    override fun onResume() {
        super.onResume()
        Status.listen(refresh)
        Log.listen(refresh)
        renderCert()
        render()
    }

    override fun onPause() {
        Status.unlisten(refresh)
        Log.unlisten(refresh)
        super.onPause()
    }

    private fun askPermissions() {
        val want = ArrayList<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) want += Manifest.permission.RECORD_AUDIO
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            want += Manifest.permission.POST_NOTIFICATIONS
        }
        if (want.isNotEmpty()) requestPermissions(want.toTypedArray(), REQ_PERMISSIONS)
    }

    // ---------- UI ----------

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun text(s: String, size: Float = 15f, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = size
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun button(s: String, onClick: () -> Unit) = Button(this).apply {
        text = s
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun section(title: String) = text(title, 18f, true).apply { setPadding(0, dp(18), 0, dp(6)) }

    private fun buildUi(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }
        col.addView(text(getString(R.string.app_name), 24f, true))
        col.addView(text("Android Auto на штатной магнитоле через CarLife", 14f))

        statusView = text("", 15f)
        col.addView(section("Состояние"))
        col.addView(statusView)
        col.addView(button("Открыть настройки Android Auto") { openAaSettings() })
        col.addView(button("Подключить Android Auto снова") { LinkService.command(this, LinkService.ACTION_CONNECT_AA) })
        col.addView(button("Подключиться к магнитоле") { LinkService.attach(this, null) })
        col.addView(button("Остановить") { LinkService.command(this, LinkService.ACTION_STOP) })

        col.addView(section("Как запустить"))
        col.addView(text(
            "1. Android Auto → Настройки → 10 раз нажмите «Версия» (режим разработчика).\n" +
                "2. Меню ⋮ → «Запустить сервер головного устройства». После каждого обрыва его нужно включить снова.\n" +
                "3. Подключите телефон кабелем к USB машины и включите CarLife на магнитоле.\n" +
                "4. Разрешите приложению доступ к USB-устройству, когда телефон спросит.", 14f,
        ))

        col.addView(section("Звук"))
        val group = RadioGroup(this)
        val options = listOf(
            Settings.Audio.AUTO to "Авто (как скажет магнитола)",
            Settings.Audio.USB to "Через USB в магнитолу",
            Settings.Audio.PHONE to "С телефона (Bluetooth)",
        )
        for ((value, label) in options) {
            group.addView(RadioButton(this).apply {
                id = View.generateViewId()
                text = label
                isChecked = settings.audio == value
                setOnCheckedChangeListener { _, checked -> if (checked) settings.audio = value }
            })
        }
        col.addView(group)

        col.addView(section("Изображение"))
        col.addView(text("Плотность экрана Android Auto (DPI, ${Settings.DPI_MIN}–${Settings.DPI_MAX}). Больше — крупнее кнопки.", 14f))
        col.addView(numberField(settings.dpi) { settings.dpi = it })
        col.addView(text("Битрейт видео, кбит/с (1000–12000)", 14f))
        col.addView(numberField(settings.bitrateKbps) { settings.bitrateKbps = it })
        col.addView(text("Изменения применяются при следующем подключении Android Auto.", 13f))

        col.addView(section("Сертификат головного устройства"))
        certView = text("", 14f)
        col.addView(certView)
        col.addView(button("Загрузить сертификат и ключ (.pem)") { pickCertificate() })

        col.addView(section("Журнал"))
        col.addView(button("Поделиться журналом") { shareLog() })
        logView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 10f
            setTextIsSelectable(true)
        }
        col.addView(logView)

        return ScrollView(this).apply { addView(col, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)) }
    }

    private fun numberField(value: Int, save: (Int) -> Unit): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val field = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(value.toString())
        }
        row.addView(field, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        row.addView(button("Сохранить") {
            field.text.toString().toIntOrNull()?.let {
                save(it)
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
        certView.text = if (HuCredentials.load(this) != null) "Сертификат есть." else "Сертификата нет — Android Auto не подключится. Загрузите .pem с сертификатом и закрытым ключом."
    }

    // ---------- actions ----------

    private fun openAaSettings() {
        val candidates = listOf(
            Intent().setComponent(ComponentName(AA_PACKAGE, "$AA_PACKAGE.companion.settings.DefaultSettingsActivity")),
            packageManager.getLaunchIntentForPackage(AA_PACKAGE),
            Intent(SystemSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$AA_PACKAGE")),
        )
        for (i in candidates) {
            if (i == null) continue
            try {
                startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: Exception) {
            }
        }
        Toast.makeText(this, "Android Auto не установлен", Toast.LENGTH_LONG).show()
    }

    private fun pickCertificate() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        startActivityForResult(i, REQ_CERT)
    }

    @Deprecated("Activity result API of the platform")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_CERT || resultCode != RESULT_OK || data == null) return
        val uris = ArrayList<Uri>()
        data.clipData?.let { c -> for (k in 0 until c.itemCount) uris += c.getItemAt(k).uri }
        data.data?.let { if (it !in uris) uris += it }
        val text = uris.joinToString("\n") { u ->
            try {
                contentResolver.openInputStream(u)?.bufferedReader()?.readText() ?: ""
            } catch (_: Exception) {
                ""
            }
        }
        val ok = HuCredentials.import(this, text)
        Toast.makeText(
            this,
            if (ok) "Сертификат сохранён" else "Нужны сертификат (CERTIFICATE) и закрытый ключ (PRIVATE KEY) в формате PEM — выберите оба файла сразу",
            Toast.LENGTH_LONG,
        ).show()
        renderCert()
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
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(i, "Журнал"))
    }
}
