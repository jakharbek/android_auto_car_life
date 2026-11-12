package com.jakharbek.monzaauto.ui

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import com.jakharbek.monzaauto.LinkService
import com.jakharbek.monzaauto.Log
import com.jakharbek.monzaauto.aa.HuCredentials
import com.jakharbek.monzaauto.setup.Fix
import com.jakharbek.monzaauto.setup.Probes

/** Shared behaviour of the app screens: performing fixes, permission and file-picker results. */
abstract class BaseActivity : Activity() {
    companion object {
        private const val REQ_PERMISSION = 10
        private const val REQ_CERT = 11
    }

    protected val ui by lazy { Ui(this) }

    /** Re-reads state and redraws. Called after every fix result and on resume. */
    protected abstract fun refresh()

    override fun onResume() {
        super.onResume()
        refresh()
    }

    fun perform(fix: Fix) {
        Log.i("UI: fix $fix")
        when (fix) {
            Fix.NONE -> Unit
            Fix.INSTALL_AA -> open(
                Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${Probes.AA_PACKAGE}")),
                Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=${Probes.AA_PACKAGE}")),
            )
            Fix.OPEN_AA_SETTINGS -> openAaSettings()
            Fix.ASK_NOTIFICATIONS -> if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                ask(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                open(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
            }
            Fix.ASK_MIC -> ask(Manifest.permission.RECORD_AUDIO)
            Fix.BATTERY -> open(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")),
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            )
            Fix.APP_SETTINGS -> appSettings()
            Fix.LOAD_CERT -> pickCertificate()
            Fix.CONNECT_AA -> LinkService.command(this, LinkService.ACTION_CONNECT_AA)
            Fix.ATTACH_CAR -> LinkService.attach(this, null)
        }
    }

    private fun open(vararg intents: Intent) {
        for (i in intents) {
            try {
                startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: Exception) {
            }
        }
        Toast.makeText(this, "Не удалось открыть настройки", Toast.LENGTH_LONG).show()
    }

    fun appSettings() = open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))

    fun openAaSettings() {
        val pm = packageManager
        val candidates = listOfNotNull(
            Intent().setComponent(ComponentName(Probes.AA_PACKAGE, "${Probes.AA_PACKAGE}.companion.settings.DefaultSettingsActivity")),
            pm.getLaunchIntentForPackage(Probes.AA_PACKAGE),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${Probes.AA_PACKAGE}")),
        )
        for (i in candidates) {
            try {
                startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: Exception) {
            }
        }
        Toast.makeText(this, "Android Auto не установлен", Toast.LENGTH_LONG).show()
    }

    /** Asks for a permission; if Android will not show the dialog any more, opens the app settings instead. */
    private fun ask(permission: String) {
        if (checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
            refresh()
            return
        }
        val prefs = getSharedPreferences("asked", MODE_PRIVATE)
        val askedBefore = prefs.getBoolean(permission, false)
        if (askedBefore && !shouldShowRequestPermissionRationale(permission)) {
            Toast.makeText(this, "Разрешение выключено навсегда — включите его в настройках приложения (Разрешения)", Toast.LENGTH_LONG).show()
            appSettings()
            return
        }
        prefs.edit().putBoolean(permission, true).apply()
        requestPermissions(arrayOf(permission), REQ_PERMISSION)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
    }

    private fun pickCertificate() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        try {
            startActivityForResult(i, REQ_CERT)
        } catch (_: Exception) {
            Toast.makeText(this, "Нет приложения для выбора файлов", Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Platform activity result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_CERT || resultCode != RESULT_OK || data == null) return
        val uris = ArrayList<Uri>()
        data.clipData?.let { c -> for (k in 0 until c.itemCount) uris += c.getItemAt(k).uri }
        data.data?.let { if (it !in uris) uris += it }
        val text = uris.joinToString("\n") { u ->
            try {
                contentResolver.openInputStream(u)?.use { it.bufferedReader().readText() } ?: ""
            } catch (_: Exception) {
                ""
            }
        }
        val ok = HuCredentials.import(this, text)
        Log.i("UI: certificate import from ${uris.size} file(s): ${if (ok) "ok" else "rejected"}")
        Toast.makeText(
            this,
            if (ok) "Сертификат сохранён" else "Не найдены сертификат (CERTIFICATE) и ключ (PRIVATE KEY). Выберите оба файла сразу или один файл, где есть оба.",
            Toast.LENGTH_LONG,
        ).show()
        refresh()
    }
}
