package com.jakharbek.monzaauto.setup

import android.Manifest
import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.PowerManager
import com.jakharbek.monzaauto.BuildConfig
import com.jakharbek.monzaauto.Diag
import com.jakharbek.monzaauto.Diag.AaStage
import com.jakharbek.monzaauto.Settings
import com.jakharbek.monzaauto.aa.HuCredentials
import java.net.BindException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.security.interfaces.RSAKey

/** Collects facts on the phone and runs them through [Rules]. Some probes block: call off the main thread. */
object Probes {
    const val AA_PACKAGE = "com.google.android.projection.gearhead"
    const val AA_SERVER_PORT = 5277

    fun aaVersion(ctx: Context): Pair<String?, Boolean> = try {
        val pi = ctx.packageManager.getPackageInfo(AA_PACKAGE, 0)
        (pi.versionName ?: "?") to (pi.applicationInfo?.enabled != false)
    } catch (_: PackageManager.NameNotFoundException) {
        null to false
    }

    fun micGranted(ctx: Context) = ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun notificationsGranted(ctx: Context): Boolean {
        val perm = Build.VERSION.SDK_INT < 33 ||
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        return perm && ctx.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
    }

    fun batteryUnrestricted(ctx: Context) = ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)

    fun backgroundRestricted(ctx: Context) = ctx.getSystemService(ActivityManager::class.java).isBackgroundRestricted

    fun certificateCheck(ctx: Context): Check {
        val c = HuCredentials.load(ctx)
            ?: return Rules.certificate(false, false, 0, 0, null, System.currentTimeMillis())
        val matches = try {
            (c.cert.publicKey as RSAKey).modulus == (c.key as RSAKey).modulus
        } catch (_: Exception) {
            false
        }
        val org = Regex("O=([^,]+)").find(c.cert.subjectX500Principal.name)?.groupValues?.get(1)
        return Rules.certificate(true, matches, c.cert.notBefore.time, c.cert.notAfter.time, org, System.currentTimeMillis())
    }

    /**
     * Finds out whether something listens on the head unit server port without connecting to it:
     * the server accepts only one connection in its life, so even a test connection would use it up.
     * Binding the same port fails only if the server is listening.
     */
    fun serverProbe(): ServerProbe {
        if (Diag.aaStage in setOf(AaStage.CONNECTING, AaStage.HANDSHAKE, AaStage.CONNECTED, AaStage.VIDEO)) return ServerProbe.IN_USE_BY_APP
        // The app keeps retrying on its own and the last try found nothing listening: do not hold the port meanwhile.
        if (Diag.aaRetryPending) return ServerProbe.NOT_RUNNING
        return try {
            ServerSocket().use {
                it.reuseAddress = false
                it.bind(InetSocketAddress("127.0.0.1", AA_SERVER_PORT))
            }
            ServerProbe.NOT_RUNNING
        } catch (_: BindException) {
            ServerProbe.RUNNING
        } catch (_: Exception) {
            ServerProbe.UNKNOWN
        }
    }

    fun videoCheck(): Check {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        fun find(encoder: Boolean) = list.filter { it.isEncoder == encoder && MediaFormat.MIMETYPE_VIDEO_AVC in it.supportedTypes.map(String::lowercase) }
            .filter {
                try {
                    it.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).videoCapabilities?.isSizeSupported(1280, 720) == true
                } catch (_: Exception) {
                    false
                }
            }
            .sortedByDescending { it.isHardwareAccelerated }
        val enc = find(true).firstOrNull()
        val dec = find(false).firstOrNull()
        return Rules.video(enc?.name, enc?.isHardwareAccelerated == true, dec?.name)
    }

    /**
     * Other installed apps that open when the head unit is plugged in: they declare the USB accessory event and
     * their accessory filter matches it (same rules Android applies: attributes a filter leaves out match anything).
     */
    fun otherAccessoryApps(ctx: Context): List<AccessoryApp> {
        val pm = ctx.packageManager
        val attached = ctx.getSystemService(UsbManager::class.java).accessoryList?.firstOrNull()
        val model = attached?.model ?: "CarLife"
        val handlers = try {
            pm.queryIntentActivities(Intent(UsbManager.ACTION_USB_ACCESSORY_ATTACHED), PackageManager.GET_META_DATA)
        } catch (_: Exception) {
            emptyList()
        }
        val out = ArrayList<AccessoryApp>()
        for (ri in handlers) {
            val ai = ri.activityInfo ?: continue
            if (ai.packageName == ctx.packageName) continue
            val res = ai.metaData?.getInt(UsbManager.ACTION_USB_ACCESSORY_ATTACHED, 0) ?: 0
            val matches = res == 0 || try {
                filterMatches(pm.getResourcesForApplication(ai.applicationInfo).getXml(res), model, attached?.manufacturer)
            } catch (_: Exception) {
                true // cannot read the filter: better to mention the app than to miss it
            }
            if (matches && out.none { it.packageName == ai.packageName }) {
                out += AccessoryApp(ai.packageName, ai.applicationInfo.loadLabel(pm).toString())
            }
        }
        return out
    }

    /** True if any usb-accessory entry of the filter accepts this model (and manufacturer, when known). */
    private fun filterMatches(xml: android.content.res.XmlResourceParser, model: String, manufacturer: String?): Boolean {
        xml.use { p ->
            var any = false
            while (p.next() != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                if (p.eventType != org.xmlpull.v1.XmlPullParser.START_TAG || p.name != "usb-accessory") continue
                any = true
                val m = p.getAttributeValue(null, "model")
                val mf = p.getAttributeValue(null, "manufacturer")
                if ((m == null || m == model) && (mf == null || manufacturer == null || mf == manufacturer)) return true
            }
            return !any // a filter without entries matches every accessory
        }
    }

    fun defaultAppCheck(ctx: Context): Check {
        val um = ctx.getSystemService(UsbManager::class.java)
        val acc = um.accessoryList?.firstOrNull()
        return Rules.defaultApp(otherAccessoryApps(ctx), acc != null, acc != null && um.hasPermission(acc))
    }

    fun usbCheck(ctx: Context): Check {
        val um = ctx.getSystemService(UsbManager::class.java)
        val acc = um.accessoryList?.firstOrNull()
        return Rules.usb(acc?.let { "${it.model} ${it.version}" }, acc != null && um.hasPermission(acc), Diag.snapshot())
    }

    /** Everything, most severe first. Blocks for a short while (codec list, port probe). */
    fun all(ctx: Context): List<Check> {
        val s = Diag.snapshot()
        val (aaVer, aaEnabled) = aaVersion(ctx)
        val cert = certificateCheck(ctx)
        val server = Rules.server(serverProbe(), s)
        val checks = listOf(
            Rules.androidAuto(aaVer, aaEnabled),
            cert,
            server,
            Rules.microphone(micGranted(ctx)),
            Rules.notifications(notificationsGranted(ctx), Build.VERSION.SDK_INT),
            Rules.battery(batteryUnrestricted(ctx), backgroundRestricted(ctx)),
            videoCheck(),
            usbCheck(ctx),
            defaultAppCheck(ctx),
            Rules.car(s),
            Rules.dedupe(Rules.androidAutoLink(s, cert.level == Level.OK || cert.level == Level.WARN), server),
            Rules.audio(Settings(ctx).audio.name, s.huFeatures?.get("AUDIO_TRANSMISSION_MODE")),
        )
        return Rules.sorted(checks)
    }

    fun header(ctx: Context): String {
        val (aaVer, _) = aaVersion(ctx)
        return "Monza Auto ${BuildConfig.VERSION_NAME} · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})" +
            " · Android Auto ${aaVer ?: "нет"}"
    }
}
