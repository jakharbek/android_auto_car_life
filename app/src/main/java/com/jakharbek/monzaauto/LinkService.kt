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
 * All state changes run on the main thread; link callbacks are posted here and ignored when they come
 * from a link that is no longer the current one.
 */
class LinkService : Service(), CarLifeEvents, AaEvents {
    companion object {
        const val ACTION_ATTACH = "com.jakharbek.monzaauto.ATTACH"
        const val ACTION_STOP = "com.jakharbek.monzaauto.STOP"
        const val ACTION_CONNECT_AA = "com.jakharbek.monzaauto.CONNECT_AA"
        /** Sent by a screen in front when the microphone permission was just granted. */
        const val ACTION_PERMISSIONS_CHANGED = "com.jakharbek.monzaauto.PERMISSIONS_CHANGED"
        const val EXTRA_ACCESSORY = "accessory"

        private const val NOTIFICATION_ID = 1
        private const val CHANNEL = "link"

        /** Android Auto is connected only after the car told us where it wants sound, or after this delay. */
        private const val AA_WAIT_FOR_CAR_MS = 4000L

        /** How long Android Auto stays connected after the cable is gone, waiting for the car to come back. */
        private const val CAR_GRACE_MS = 20_000L

        private const val AA_RETRY_MS = 3000L

        @Volatile var isRunning = false
            private set

        fun attach(context: Context, accessory: UsbAccessory?) {
            val i = Intent(context, LinkService::class.java).setAction(ACTION_ATTACH)
            if (accessory != null) i.putExtra(EXTRA_ACCESSORY, accessory)
            context.startForegroundService(i)
        }

        /** Commands for a running service; [ACTION_CONNECT_AA] also starts it. */
        fun command(context: Context, action: String) {
            if (action != ACTION_CONNECT_AA && !isRunning) return
            context.startForegroundService(Intent(context, LinkService::class.java).setAction(action))
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var settings: Settings
    private var wakeLock: PowerManager.WakeLock? = null

    /** One USB connection: the protocol link and the file descriptor it reads and writes. */
    private class CarConn(val link: CarLifeLink, val pfd: ParcelFileDescriptor)

    @Volatile private var car: CarConn? = null
    @Volatile private var aa: AapLink? = null
    @Volatile private var video: Transcoder? = null
    private var mic: PhoneMic? = null
    private var stopped = false

    /** The foreground service currently includes the microphone type (needed to record with the screen off). */
    private var foregroundMic = false

    /** Head unit feature AUDIO_TRANSMISSION_MODE: 1 = the car plays phone sound over Bluetooth. */
    private var carWantsBluetoothAudio: Boolean? = null
    private var aaConnectScheduled = false
    private var aaGaveUp = false

    private val carLink get() = car?.link

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
        isRunning = true
        Status.running = true
        Status.changed()
        Log.i("SVC: started, app ${BuildConfig.VERSION_NAME}, android ${Build.VERSION.RELEASE}, ${Build.MANUFACTURER} ${Build.MODEL}")
    }

    private fun micAllowed() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** (Re)enters the foreground; adds the microphone type when it is allowed. */
    private fun goForeground() {
        val n = notification()
        val base = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        if (micAllowed() && Build.VERSION.SDK_INT >= 30) {
            try {
                startForeground(NOTIFICATION_ID, n, base or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                foregroundMic = true
                return
            } catch (e: Exception) {
                Log.w("SVC: foreground with microphone refused, continuing without it", e)
            }
        }
        startForeground(NOTIFICATION_ID, n, base)
        foregroundMic = Build.VERSION.SDK_INT < 30 && micAllowed()
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
        if (!stopped) getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (stopped) {
            // Started again while stopping: become a normal running service again.
            Log.i("SVC: restarted while stopping")
            stopped = false
            isRunning = true
            Status.running = true
        }
        // Every start through startForegroundService must be answered with startForeground.
        goForeground()
        when (intent?.action) {
            ACTION_STOP -> stopAll()
            ACTION_CONNECT_AA -> {
                aaGaveUp = false
                if (carLink == null) attach(null) else connectAa()
            }
            ACTION_PERMISSIONS_CHANGED -> Log.i("SVC: permissions changed, microphone in foreground: $foregroundMic")
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
        if (carLink?.isClosed == false) {
            Log.i("SVC: already connected to the car")
            return
        }
        // A closed connection whose close callback has not run yet: release its descriptor now,
        // otherwise the accessory cannot be opened again.
        car?.let {
            car = null
            try { it.pfd.close() } catch (_: Exception) {}
        }
        val um = getSystemService(UsbManager::class.java)
        val acc = given ?: um.accessoryList?.firstOrNull()
        if (acc == null) {
            Status.usb = "магнитола не найдена: подключите кабель и включите CarLife на магнитоле"
            updateUi()
            if (aa == null) stopAll(keepStatus = true)
            return
        }
        Log.i("SVC: accessory ${acc.manufacturer} / ${acc.model} / ${acc.version}")
        Diag.accessory = "${acc.model} ${acc.version}"
        Diag.usbPermissionMissing = !um.hasPermission(acc)
        if (!um.hasPermission(acc)) {
            // Ask now; when the user allows, the system starts this service again with the accessory.
            Log.w("SVC: no permission for the accessory, asking")
            val again = Intent(this, LinkService::class.java).setAction(ACTION_ATTACH).putExtra(EXTRA_ACCESSORY, acc)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            try {
                um.requestPermission(acc, PendingIntent.getForegroundService(this, 2, again, flags))
                Status.usb = "разрешите доступ к USB в окне на экране телефона"
            } catch (e: Exception) {
                Log.e("SVC: USB permission request failed", e)
                Status.usb = "нет разрешения USB: отключите и снова подключите кабель"
            }
            updateUi()
            if (aa == null) stopAll(keepStatus = true)
            return
        }
        val fd = try {
            um.openAccessory(acc)
        } catch (e: Exception) {
            Log.e("SVC: openAccessory failed", e)
            null
        }
        if (fd == null) {
            Status.usb = "не удалось открыть USB: отключите и снова подключите кабель"
            updateUi()
            if (aa == null) stopAll(keepStatus = true)
            return
        }
        main.removeCallbacks(stopIfNoCar)
        if (aa == null) aaGaveUp = false
        Status.usb = "подключено (${acc.model})"
        val link = CarLifeLink(FileInputStream(fd.fileDescriptor), FileOutputStream(fd.fileDescriptor), phoneInfo(), this)
        car = CarConn(link, fd)
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

    /** Closes the descriptor a moment later, after the link had its chance to send its goodbye. */
    private fun closeLater(pfd: ParcelFileDescriptor) {
        Thread({
            try { Thread.sleep(500) } catch (_: InterruptedException) {}
            try { pfd.close() } catch (_: Exception) {}
        }, "usb-close").apply { isDaemon = true }.start()
    }

    override fun onCarState(state: String) = main.post {
        if (stopped) return@post
        Status.car = state
        updateUi()
    }.let {}

    override fun onFeatures(features: Map<String, Int>) = main.post {
        if (stopped) return@post
        Log.i("SVC: car features $features")
        carWantsBluetoothAudio = features["AUDIO_TRANSMISSION_MODE"] == 1
        if (aa == null) connectAa()
    }.let {}

    /** Called on the link thread right before it opens the gate for frames: ask for the key frame at once. */
    override fun onVideoWanted() {
        video?.wantKeyFrame()
    }

    override fun onVideoPaused() = Unit

    override fun onFrameRate(fps: Int) {
        lastCarFps = fps
        video?.setMaxFps(fps)
    }

    @Volatile private var lastCarFps = 30

    override fun onTouch(t: Touch) {
        aa?.sendTouch(t.action, t.index, t.fingers.map { Triple(it.id, it.x, it.y) })
    }

    override fun onKey(androidKeyCode: Int) {
        aa?.sendKey(androidKeyCode)
    }

    override fun onCarClosed(link: CarLifeLink, reason: String) = main.post {
        val conn = car
        if (conn == null || conn.link !== link) return@post // an older connection: its descriptor is already handled
        car = null
        closeLater(conn.pfd)
        if (stopped) return@post
        Log.i("SVC: car link closed: $reason")
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
        if (stopped || aa != null || aaGaveUp) return
        Diag.aaRetryPending = false
        if (carLink == null) return
        val creds = HuCredentials.load(this)
        if (creds == null) {
            Status.aa = "нет сертификата головного устройства (загрузите в приложении)"
            updateUi()
            Log.e("SVC: head unit certificate is missing")
            return
        }
        // Declare the microphone only if the service entered the foreground with it while a screen was in front
        // (adding the type from the background would succeed but record silence on Android 11-13).
        val config = HeadUnitConfig(dpi = settings.dpi, usbAudio = usbAudio(), mic = micAllowed() && foregroundMic)
        val link = AapLink(creds, config, this)
        aa = link
        link.start()
    }

    override fun onAaState(state: String) = main.post {
        if (stopped) return@post
        Status.aa = state
        updateUi()
    }.let {}

    override fun onVideoConfig(data: ByteArray) {
        val t = synchronized(this) {
            video ?: Transcoder(
                CarLifeLink.VIDEO_W, CarLifeLink.VIDEO_H, settings.bitrateKbps * 1000, 30,
                output = { frame, key -> carLink?.sendVideo(frame, key) },
                needSourceKeyFrame = { aa?.requestKeyFrame() },
            ).also {
                it.start()
                it.setMaxFps(lastCarFps)
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
        val c = carLink ?: return
        when (channel) {
            Ch.MEDIA -> c.sendMedia(pcm)
            Ch.SPEECH -> c.sendSpeech(pcm)
        }
    }

    override fun onAudioStopped(channel: Int) {
        val c = carLink ?: return
        when (channel) {
            Ch.MEDIA -> c.stopMedia()
            Ch.SPEECH -> c.stopSpeech()
        }
    }

    override fun onMicRequest(open: Boolean) = main.post {
        if (stopped) return@post
        if (open && micAllowed()) {
            val m = mic ?: PhoneMic { pcm, ts -> aa?.sendMic(pcm, ts) }.also { mic = it }
            m.start()
        } else {
            mic?.stop()
        }
    }.let {}

    override fun onAaClosed(link: AapLink, reason: String, refused: Boolean) = main.post {
        if (aa !== link) return@post // an older session
        aa = null
        mic?.stop()
        releaseVideo()
        if (stopped) return@post
        Log.i("SVC: Android Auto closed: $reason")
        if (refused && carLink != null) {
            Status.aa = "Включите «сервер головного устройства» в Android Auto"
            Diag.aaRetryPending = true
            main.postDelayed({ connectAa() }, AA_RETRY_MS)
        } else {
            aaGaveUp = true
            Status.aa = "отключён: $reason. Перезапустите сервер ГУ в Android Auto и нажмите «Подключить снова»"
        }
        updateUi()
    }.let {}

    private fun releaseVideo() {
        val v = synchronized(this) {
            val v = video
            video = null
            v
        }
        v?.release()
    }

    // ---------- stop ----------

    /** [keepStatus]: leave the last message on screen (it explains why nothing happened). */
    private fun stopAll(keepStatus: Boolean = false) {
        if (stopped) return
        stopped = true
        Log.i("SVC: stopping")
        main.removeCallbacksAndMessages(null)
        val a = aa
        aa = null
        if (a != null) Thread({ a.close() }, "aa-close").start()
        val c = car
        car = null
        if (c != null) {
            c.link.close("остановлено")
            closeLater(c.pfd)
        }
        mic?.stop()
        releaseVideo()
        isRunning = false
        Status.running = false
        if (!keepStatus) {
            Status.usb = "не подключено"
            Status.car = "—"
            Status.aa = "—"
        }
        Status.changed()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (!stopped) stopAll()
        wakeLock?.let { if (it.isHeld) it.release() }
        isRunning = false
        Status.running = false
        Status.changed()
        super.onDestroy()
    }
}
