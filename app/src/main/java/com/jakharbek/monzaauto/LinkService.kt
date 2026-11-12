package com.jakharbek.monzaauto

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import com.jakharbek.monzaauto.aa.AaEvents
import com.jakharbek.monzaauto.aa.AapLink
import com.jakharbek.monzaauto.aa.Ch
import com.jakharbek.monzaauto.aa.HeadUnitConfig
import com.jakharbek.monzaauto.aa.HuCredentials
import com.jakharbek.monzaauto.carlife.CarLifeEvents
import com.jakharbek.monzaauto.carlife.CarLifeLink
import com.jakharbek.monzaauto.carlife.PhoneInfo
import com.jakharbek.monzaauto.carlife.Touch
import com.jakharbek.monzaauto.media.PhoneMic
import com.jakharbek.monzaauto.media.Transcoder
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Keeps the bridge running: the head unit on USB on one side, Android Auto on the other.
 * All state changes run on the main thread; link callbacks are posted here.
 */
class LinkService : Service(), CarLifeEvents, AaEvents {
    companion object {
        const val ACTION_ATTACH = "com.jakharbek.monzaauto.ATTACH"
        const val ACTION_STOP = "com.jakharbek.monzaauto.STOP"
        const val ACTION_CONNECT_AA = "com.jakharbek.monzaauto.CONNECT_AA"
        const val EXTRA_ACCESSORY = "accessory"

        private const val NOTIFICATION_ID = 1
        private const val CHANNEL = "link"

        /** Android Auto is connected only after the car told us where it wants sound, or after this delay. */
        private const val AA_WAIT_FOR_CAR_MS = 4000L

        /** How long Android Auto stays connected after the cable is gone, waiting for the car to come back. */
        private const val CAR_GRACE_MS = 20_000L

        private const val AA_RETRY_MS = 3000L

        fun attach(context: Context, accessory: UsbAccessory?) {
            val i = Intent(context, LinkService::class.java).setAction(ACTION_ATTACH)
            if (accessory != null) i.putExtra(EXTRA_ACCESSORY, accessory)
            context.startForegroundService(i)
        }

        fun command(context: Context, action: String) {
            context.startService(Intent(context, LinkService::class.java).setAction(action))
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var settings: Settings
    private var wakeLock: PowerManager.WakeLock? = null

    private var pfd: ParcelFileDescriptor? = null
    @Volatile private var car: CarLifeLink? = null
    @Volatile private var aa: AapLink? = null
    @Volatile private var video: Transcoder? = null
    private var mic: PhoneMic? = null
    private var stopped = false

    /** Head unit feature AUDIO_TRANSMISSION_MODE: 1 = the car plays phone sound over Bluetooth. */
    private var carWantsBluetoothAudio: Boolean? = null
    private var aaConnectScheduled = false
    private var aaGaveUp = false

    private val stopIfNoCar = Runnable {
        if (car == null && !stopped) {
            Log.i("SVC: car did not come back, stopping")
            stopAll()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.dir = getExternalFilesDir("logs")
        settings = Settings(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW))
        goForeground()
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "monzaauto:link").apply { acquire(12 * 3600_000L) }
        Status.running = true
        Status.changed()
        Log.i("SVC: started, app ${BuildConfig.VERSION_NAME}, android ${Build.VERSION.RELEASE}, ${Build.MANUFACTURER} ${Build.MODEL}")
    }

    private fun micAllowed() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun goForeground() {
        val n = notification()
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        if (micAllowed() && Build.VERSION.SDK_INT >= 30) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        try {
            startForeground(NOTIFICATION_ID, n, type)
        } catch (e: Exception) {
            Log.w("SVC: foreground with microphone refused, continuing without it", e)
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        }
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, LinkService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Магнитола: ${Status.car} · AA: ${Status.aa}")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, getString(R.string.stop), stop).build())
            .build()
    }

    private fun updateUi() {
        Status.changed()
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (stopped) {
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_STOP -> stopAll()
            ACTION_CONNECT_AA -> {
                aaGaveUp = false
                connectAa()
            }
            else -> {
                @Suppress("DEPRECATION")
                val acc = intent?.getParcelableExtra<UsbAccessory>(EXTRA_ACCESSORY)
                attach(acc)
            }
        }
        return START_NOT_STICKY
    }

    // ---------- head unit ----------

    private fun attach(given: UsbAccessory?) {
        if (car?.isClosed == false) {
            Log.i("SVC: already connected to the car")
            return
        }
        val um = getSystemService(UsbManager::class.java)
        val acc = given ?: um.accessoryList?.firstOrNull()
        if (acc == null) {
            Status.usb = "магнитола не найдена"
            updateUi()
            if (aa == null) stopAll()
            return
        }
        Log.i("SVC: accessory ${acc.manufacturer} / ${acc.model} / ${acc.version}")
        Diag.accessory = "${acc.model} ${acc.version}"
        Diag.usbPermissionMissing = !um.hasPermission(acc)
        if (!um.hasPermission(acc)) {
            Status.usb = "нет разрешения USB (переподключите кабель)"
            updateUi()
            Log.w("SVC: no permission for the accessory")
            if (aa == null) stopAll()
            return
        }
        val fd = um.openAccessory(acc)
        if (fd == null) {
            Status.usb = "не удалось открыть USB"
            updateUi()
            if (aa == null) stopAll()
            return
        }
        main.removeCallbacks(stopIfNoCar)
        if (aa == null) aaGaveUp = false
        pfd = fd
        Status.usb = "подключено (${acc.model})"
        val link = CarLifeLink(FileInputStream(fd.fileDescriptor), FileOutputStream(fd.fileDescriptor), phoneInfo(), this)
        car = link
        link.start()
        updateUi()
        if (aa == null && !aaConnectScheduled) {
            aaConnectScheduled = true
            main.postDelayed({ connectAa() }, AA_WAIT_FOR_CAR_MS)
        }
    }

    private fun phoneInfo() = PhoneInfo(
        board = Build.BOARD, bootloader = Build.BOOTLOADER, brand = Build.BRAND,
        cpuAbi = Build.SUPPORTED_ABIS.getOrElse(0) { "" }, cpuAbi2 = Build.SUPPORTED_ABIS.getOrElse(1) { "" },
        device = Build.DEVICE, display = Build.DISPLAY, fingerprint = Build.FINGERPRINT, hardware = Build.HARDWARE,
        host = Build.HOST, id = Build.ID, manufacturer = Build.MANUFACTURER, model = Build.MODEL, product = Build.PRODUCT,
        codename = Build.VERSION.CODENAME, incremental = Build.VERSION.INCREMENTAL, release = Build.VERSION.RELEASE,
        sdkInt = Build.VERSION.SDK_INT,
    )

    override fun onCarState(state: String) = main.post {
        Status.car = state
        updateUi()
    }.let {}

    override fun onFeatures(features: Map<String, Int>) = main.post {
        Log.i("SVC: car features $features")
        carWantsBluetoothAudio = features["AUDIO_TRANSMISSION_MODE"] == 1
        if (aa == null) connectAa()
    }.let {}

    override fun onVideoWanted() = main.post { video?.wantKeyFrame() }.let {}

    override fun onVideoPaused() = Unit

    override fun onTouch(t: Touch) {
        aa?.sendTouch(t.action, t.index, t.fingers.map { Triple(it.id, it.x, it.y) })
    }

    override fun onKey(androidKeyCode: Int) {
        aa?.sendKey(androidKeyCode)
    }

    override fun onCarClosed(reason: String) = main.post {
        if (stopped) return@post
        Log.i("SVC: car link closed: $reason")
        car = null
        try { pfd?.close() } catch (_: Exception) {}
        pfd = null
        Status.usb = "отключено"
        Status.car = reason
        updateUi()
        main.removeCallbacks(stopIfNoCar)
        main.postDelayed(stopIfNoCar, CAR_GRACE_MS)
    }.let {}

    // ---------- Android Auto ----------

    private fun usbAudio(): Boolean = when (settings.audio) {
        Settings.Audio.USB -> true
        Settings.Audio.PHONE -> false
        Settings.Audio.AUTO -> carWantsBluetoothAudio != true
    }

    private fun connectAa() {
        aaConnectScheduled = false
        if (aa != null || aaGaveUp) return
        if (car == null) return
        val creds = HuCredentials.load(this)
        if (creds == null) {
            Status.aa = "нет сертификата головного устройства (загрузите в приложении)"
            updateUi()
            Log.e("SVC: head unit certificate is missing")
            return
        }
        val config = HeadUnitConfig(dpi = settings.dpi, usbAudio = usbAudio(), mic = micAllowed())
        val link = AapLink(creds, config, this)
        aa = link
        link.start()
    }

    override fun onAaState(state: String) = main.post {
        Status.aa = state
        updateUi()
    }.let {}

    override fun onVideoConfig(data: ByteArray) {
        val t = synchronized(this) {
            video ?: Transcoder(CarLifeLink.VIDEO_W, CarLifeLink.VIDEO_H, settings.bitrateKbps * 1000, 30) { frame, key ->
                car?.sendVideo(frame, key)
            }.also {
                it.start()
                video = it
            }
        }
        Log.i("SVC: video parameters ${Log.hex(data, 24)}")
        t.feedConfig(data)
        t.wantKeyFrame()
    }

    override fun onVideoFrame(data: ByteArray, ptsUs: Long, done: () -> Unit) {
        val t = video
        if (t == null) done() else t.feedFrame(data, ptsUs, done)
    }

    override fun onVideoStopped() = Unit

    override fun onAudio(channel: Int, pcm: ByteArray) {
        val c = car ?: return
        when (channel) {
            Ch.MEDIA -> c.sendMedia(pcm)
            Ch.SPEECH -> c.sendSpeech(pcm)
        }
    }

    override fun onAudioStopped(channel: Int) {
        val c = car ?: return
        when (channel) {
            Ch.MEDIA -> c.stopMedia()
            Ch.SPEECH -> c.stopSpeech()
        }
    }

    override fun onMicRequest(open: Boolean) = main.post {
        if (open && micAllowed()) {
            val m = mic ?: PhoneMic { pcm, ts -> aa?.sendMic(pcm, ts) }.also { mic = it }
            m.start()
        } else {
            mic?.stop()
        }
    }.let {}

    override fun onAaClosed(reason: String, refused: Boolean) = main.post {
        if (stopped) return@post
        Log.i("SVC: Android Auto closed: $reason")
        aa = null
        mic?.stop()
        synchronized(this) {
            video?.release()
            video = null
        }
        if (refused && car != null) {
            Status.aa = "Включите «сервер головного устройства» в Android Auto"
            main.postDelayed({ connectAa() }, AA_RETRY_MS)
        } else {
            aaGaveUp = true
            Status.aa = "отключён: $reason. Перезапустите сервер ГУ в Android Auto и нажмите «Подключить снова»"
        }
        updateUi()
    }.let {}

    // ---------- stop ----------

    private fun stopAll() {
        if (stopped) return
        stopped = true
        Log.i("SVC: stopping")
        main.removeCallbacksAndMessages(null)
        val a = aa
        aa = null
        if (a != null) Thread { a.close() }.start()
        car?.close("остановлено")
        car = null
        mic?.stop()
        synchronized(this) {
            video?.release()
            video = null
        }
        try { pfd?.close() } catch (_: Exception) {}
        pfd = null
        Status.running = false
        Status.usb = "не подключено"
        Status.car = "—"
        Status.aa = "—"
        Status.changed()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        Status.running = false
        Status.changed()
        super.onDestroy()
    }
}
