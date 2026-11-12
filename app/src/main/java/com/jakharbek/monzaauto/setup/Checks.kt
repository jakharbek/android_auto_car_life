package com.jakharbek.monzaauto.setup

import com.jakharbek.monzaauto.Diag
import com.jakharbek.monzaauto.Diag.AaStage
import com.jakharbek.monzaauto.Diag.CarStage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** How a check came out. Order = severity for sorting. */
enum class Level { FAIL, WARN, OK, INFO }

/** One-tap remedies the screens know how to perform. */
enum class Fix(val label: String) {
    NONE(""),
    INSTALL_AA("Установить Android Auto"),
    OPEN_AA_SETTINGS("Открыть настройки Android Auto"),
    ASK_NOTIFICATIONS("Разрешить уведомления"),
    ASK_MIC("Разрешить микрофон"),
    BATTERY("Снять ограничение батареи"),
    APP_SETTINGS("Открыть настройки приложения"),
    LOAD_CERT("Загрузить сертификат"),
    CONNECT_AA("Подключить Android Auto"),
    ATTACH_CAR("Подключиться к магнитоле"),
    OPEN_APP_DEFAULTS("Открыть «Открывать по умолчанию»"),
}

data class Check(
    val id: String,
    val title: String,
    val level: Level,
    /** What was found. */
    val detail: String,
    /** What the user should do, if anything. */
    val todo: String? = null,
    val fix: Fix = Fix.NONE,
    /** Parameter of the fix (a package name for [Fix.OPEN_APP_DEFAULTS]). */
    val fixArg: String? = null,
    /** Button text when the generic [Fix.label] is not specific enough. */
    val fixLabel: String? = null,
)

/** Another app that can open when the head unit is plugged in. */
data class AccessoryApp(val packageName: String, val label: String)

/** Result of looking for Android Auto's head unit server without touching it. */
enum class ServerProbe { RUNNING, NOT_RUNNING, IN_USE_BY_APP, UNKNOWN }

/**
 * Turns raw facts into checks. No Android calls here: everything comes in as plain values,
 * so the rules can be tested on the JVM. [Probes] gathers the facts on the phone.
 */
object Rules {
    const val AA_SERVER_STEPS =
        "Android Auto → Настройки → 10 раз нажмите «Версия» (включится режим разработчика) → меню ⋮ вверху → " +
            "«Вкл. сервер головного устр-ва» (в английской версии «Start head unit server»)."

    private val dateFmt = SimpleDateFormat("dd.MM.yyyy", Locale.US)

    fun androidAuto(versionName: String?, enabled: Boolean): Check = when {
        versionName == null -> Check(
            "aa", "Android Auto", Level.FAIL, "Android Auto не установлен.",
            "Установите Android Auto из Google Play.", Fix.INSTALL_AA,
        )
        !enabled -> Check(
            "aa", "Android Auto", Level.FAIL, "Android Auto $versionName отключён в системе.",
            "Включите Android Auto в настройках телефона (Приложения → Android Auto → Включить).", Fix.INSTALL_AA,
        )
        else -> Check("aa", "Android Auto", Level.OK, "Установлен, версия $versionName.")
    }

    fun notifications(granted: Boolean, sdk: Int): Check = when {
        granted -> Check("notif", "Уведомления", Level.OK, "Разрешены.")
        else -> Check(
            "notif", "Уведомления", Level.WARN,
            "Уведомления выключены" + (if (sdk >= 33) " (разрешение не дано)." else "."),
            "Разрешите уведомления: по ним видно, что связь с машиной работает, и через них её можно остановить.",
            Fix.ASK_NOTIFICATIONS,
        )
    }

    fun microphone(granted: Boolean): Check = if (granted) {
        Check("mic", "Микрофон", Level.OK, "Разрешён: голосовой помощник Android Auto будет слышать вас через микрофон телефона.")
    } else {
        Check(
            "mic", "Микрофон", Level.WARN, "Нет доступа к микрофону.",
            "Разрешите микрофон, иначе голосовые команды в Android Auto работать не будут.", Fix.ASK_MIC,
        )
    }

    fun battery(ignoringOptimizations: Boolean, backgroundRestricted: Boolean): Check = when {
        backgroundRestricted -> Check(
            "battery", "Работа в фоне", Level.FAIL, "Система запретила приложению работать в фоне.",
            "Откройте настройки приложения → Батарея → «Без ограничений».", Fix.APP_SETTINGS,
        )
        !ignoringOptimizations -> Check(
            "battery", "Работа в фоне", Level.WARN, "Включена оптимизация батареи: система может оборвать связь, когда экран телефона погаснет.",
            "Снимите ограничение батареи для приложения.", Fix.BATTERY,
        )
        else -> Check("battery", "Работа в фоне", Level.OK, "Ограничений батареи нет.")
    }

    /**
     * [present]: a certificate and key were found; [keyMatches]: the key belongs to the certificate;
     * validity dates in milliseconds.
     */
    fun certificate(present: Boolean, keyMatches: Boolean, notBefore: Long, notAfter: Long, subject: String?, now: Long): Check {
        val title = "Сертификат головного устройства"
        if (!present) {
            return Check(
                "cert", title, Level.FAIL, "Сертификата нет — Android Auto не примет подключение.",
                "Загрузите файлы сертификата и закрытого ключа (.pem) или соберите APK с секретами HU_CERT_PEM и HU_KEY_PEM.",
                Fix.LOAD_CERT,
            )
        }
        if (!keyMatches) {
            return Check(
                "cert", title, Level.FAIL, "Ключ не подходит к сертификату.",
                "Загрузите сертификат и ключ из одного набора.", Fix.LOAD_CERT,
            )
        }
        val until = dateFmt.format(Date(notAfter))
        return when {
            now > notAfter -> Check("cert", title, Level.FAIL, "Срок действия истёк $until.", "Загрузите действующий сертификат.", Fix.LOAD_CERT)
            now < notBefore -> Check(
                "cert", title, Level.FAIL, "Сертификат ещё не начал действовать: проверьте дату и время на телефоне.",
                "Включите автоматическую дату и время в настройках телефона.",
            )
            notAfter - now < 30L * 24 * 3600 * 1000 -> Check("cert", title, Level.WARN, "Истекает $until.", "Скоро понадобится новый сертификат.", Fix.LOAD_CERT)
            else -> Check("cert", title, Level.OK, "Есть, действует до $until." + (subject?.let { " ($it)" } ?: ""))
        }
    }

    fun server(probe: ServerProbe, snapshot: Diag.Snapshot): Check {
        val title = "Сервер головного устройства"
        return when (probe) {
            ServerProbe.IN_USE_BY_APP -> Check("server", title, Level.OK, "Приложение сейчас подключено к Android Auto.")
            // The server serves one connection in its life: after any finished session it still listens but never answers.
            ServerProbe.RUNNING -> if (snapshot.aaStage == AaStage.CLOSED && !snapshot.aaRefused) {
                Check(
                    "server", title, Level.WARN,
                    if (snapshot.aaTimedOut) {
                        "Сервер запущен, но в прошлый раз не ответил: он одноразовый и после обрыва перестаёт отвечать."
                    } else {
                        "Сервер запущен, но уже использован прошлым сеансом: он одноразовый и второй раз не ответит."
                    } + " Если вы его уже перезапустили — просто нажмите «Подключить Android Auto».",
                    "В Android Auto: меню ⋮ → «Остановить сервер головного устройства» (Stop head unit server), затем снова «Вкл. сервер головного устр-ва», и нажмите «Подключить Android Auto».",
                    Fix.OPEN_AA_SETTINGS,
                )
            } else {
                Check("server", title, Level.OK, "Запущен и ждёт подключения.")
            }
            ServerProbe.NOT_RUNNING -> Check(
                "server", title, Level.FAIL, "Не запущен — без него Android Auto не передаёт картинку.",
                AA_SERVER_STEPS + " После каждого обрыва связи его нужно запускать заново.", Fix.OPEN_AA_SETTINGS,
            )
            ServerProbe.UNKNOWN -> Check(
                "server", title, Level.INFO, "Не удалось проверить.",
                "Убедитесь, что сервер запущен: $AA_SERVER_STEPS", Fix.OPEN_AA_SETTINGS,
            )
        }
    }

    fun video(encoder: String?, encoderHw: Boolean, decoder: String?): Check = when {
        encoder == null || decoder == null -> Check(
            "codec", "Видео", Level.FAIL,
            "Телефон не умеет ${if (encoder == null) "кодировать" else "декодировать"} H.264 1280×720.",
            "Этот телефон не подходит для вывода картинки на магнитолу.",
        )
        !encoderHw -> Check(
            "codec", "Видео", Level.WARN, "Кодировщик программный ($encoder): картинка может тормозить.",
            "Уменьшите битрейт в настройках (например, до 2000 кбит/с).",
        )
        else -> Check("codec", "Видео", Level.OK, "Аппаратный кодировщик $encoder, декодер $decoder.")
    }

    fun usb(accessory: String?, hasPermission: Boolean, s: Diag.Snapshot): Check {
        val title = "USB-подключение"
        return when {
            accessory == null -> Check(
                "usb", title, Level.INFO, "Магнитола сейчас не подключена.",
                "Подключите телефон кабелем к USB-разъёму машины (лучше короткий качественный кабель) и включите CarLife на магнитоле.",
            )
            !hasPermission -> Check(
                "usb", title, Level.FAIL, "Магнитола подключена ($accessory), но нет разрешения на USB.",
                "Отключите и снова подключите кабель и в появившемся окне выберите Monza Auto (можно отметить «по умолчанию»).",
            )
            s.carStage == CarStage.NONE || s.carStage == CarStage.CLOSED -> Check(
                "usb", title, Level.WARN, "Магнитола подключена ($accessory), но приложение с ней не связано.",
                "Нажмите «Подключиться к магнитоле».", Fix.ATTACH_CAR,
            )
            else -> Check("usb", title, Level.OK, "Подключена: $accessory.")
        }
    }

    fun car(s: Diag.Snapshot): Check {
        val title = "Магнитола (CarLife)"
        val who = listOfNotNull(s.huModel, s.huProtocol?.let { "протокол $it" }, s.huScreen?.let { "экран $it" }).joinToString(", ")
        val about = if (who.isEmpty()) "" else " ($who)"
        return when {
            s.authUnsupported -> Check(
                "car", title, Level.FAIL, "Магнитола требует авторизацию, которую приложение пока не поддерживает$about.",
                "Пришлите журнал разработчику (кнопка «Поделиться журналом»).",
            )
            s.carStage == CarStage.NONE -> Check(
                "car", title, Level.INFO, "Связи с магнитолой ещё не было.",
                "Подключите кабель и включите CarLife на магнитоле.",
            )
            s.carStage == CarStage.USB_OPEN -> Check(
                "car", title, Level.WARN, "USB открыт, но магнитола молчит.",
                "Откройте CarLife на экране магнитолы. Если не помогает — переподключите кабель.",
            )
            s.carStage == CarStage.TALKING -> Check(
                "car", title, Level.WARN, "Магнитола на связи$about, но ещё не запросила картинку.",
                "Подождите несколько секунд и откройте CarLife на магнитоле.",
            )
            s.carStage == CarStage.VIDEO_SETUP -> Check("car", title, Level.OK, "Готовится показ картинки$about.")
            s.carStage == CarStage.STREAMING -> Check("car", title, Level.OK, "Показывает экран$about, отправлено кадров: ${s.carFramesSent}.")
            s.carStage == CarStage.PAUSED -> Check(
                "car", title, Level.INFO, "Магнитола поставила картинку на паузу$about (открыт другой экран магнитолы).",
                "Вернитесь в CarLife на магнитоле.",
            )
            else -> Check(
                "car", title, Level.WARN, "Связь прервалась: ${s.carClosedReason ?: "причина неизвестна"}.",
                "Проверьте кабель и разъём; переподключите телефон.", Fix.ATTACH_CAR,
            )
        }
    }

    fun androidAutoLink(s: Diag.Snapshot, certOk: Boolean): Check {
        val title = "Связь с Android Auto"
        return when (s.aaStage) {
            AaStage.NONE -> Check(
                "aalink", title, Level.INFO, "Ещё не подключались.",
                "Приложение подключится само, когда магнитола будет на связи.",
            )
            AaStage.CONNECTING, AaStage.HANDSHAKE -> Check("aalink", title, Level.INFO, "Подключаюсь…")
            AaStage.CONNECTED -> Check(
                "aalink", title, Level.OK,
                "Подключено (протокол ${s.aaProtocol ?: "?"}), ждём картинку.",
            )
            AaStage.VIDEO -> Check(
                "aalink", title, Level.OK,
                "Картинка идёт, получено кадров: ${s.aaFramesReceived}." +
                    " Звук: ${if (s.aaUsbAudio == true) "через USB в магнитолу" else "с телефона"}." +
                    " Микрофон: ${if (s.aaMic == true) "телефона" else "нет"}.",
            )
            AaStage.CLOSED -> when {
                s.aaRefused -> Check(
                    "aalink", title, Level.FAIL, "Android Auto не принимает подключение: сервер головного устройства не запущен.",
                    AA_SERVER_STEPS, Fix.OPEN_AA_SETTINGS,
                )
                s.aaTlsFailed -> Check(
                    "aalink", title, Level.FAIL, "Android Auto отклонил защищённое подключение" +
                        (if (certOk) " (возможно, сертификат больше не принимается)." else " — проблема с сертификатом."),
                    "Попробуйте другой набор сертификата и ключа.", Fix.LOAD_CERT,
                )
                s.aaTimedOut -> Check(
                    "aalink", title, Level.FAIL, "Android Auto не ответил: сервер головного устройства «завис» после прошлого сеанса.",
                    "В Android Auto: ⋮ → «Остановить сервер головного устройства» (Stop head unit server), затем «Вкл. сервер головного устр-ва», и нажмите «Подключить Android Auto».",
                    Fix.OPEN_AA_SETTINGS,
                )
                else -> Check(
                    "aalink", title, Level.WARN, "Сеанс закончился: ${s.aaClosedReason ?: "причина неизвестна"}.",
                    "Перезапустите сервер головного устройства в Android Auto и нажмите «Подключить Android Auto».",
                    Fix.CONNECT_AA,
                )
            }
        }
    }

    const val DEFAULT_APP_STEPS =
        "Как сделать Monza Auto приложением по умолчанию для магнитолы:\n" +
            "1) если при подключении кабеля появляется окно «Открыть с помощью» — выберите Monza Auto и нажмите «Всегда»;\n" +
            "2) если открывается другое приложение — в настройках телефона: Приложения → это приложение → " +
            "«Открывать по умолчанию» (или «Запуск по умолчанию») → «Сбросить настройки по умолчанию» / «Удалить настройки по умолчанию», " +
            "затем отключите и снова подключите кабель и выберите Monza Auto → «Всегда»;\n" +
            "3) если окно не появляется и ничего не открывается — откройте Monza Auto и нажмите «Подключиться к магнитоле»."

    /**
     * Which app opens when the head unit is plugged in. [others]: other installed apps that react to it;
     * [attached]: the head unit is plugged in now; [havePermission]: Monza Auto got it.
     */
    fun defaultApp(others: List<AccessoryApp>, attached: Boolean, havePermission: Boolean): Check {
        val title = "Приложение по умолчанию для магнитолы"
        if (others.isEmpty()) {
            return Check(
                "default", title, Level.OK,
                "Кроме Monza Auto, подключение магнитолы не открывает ни одно приложение на телефоне.",
                DEFAULT_APP_STEPS,
            )
        }
        val names = others.joinToString(", ") { "«${it.label}»" }
        val first = others.first()
        val stolen = attached && !havePermission
        return Check(
            "default", title, if (stolen) Level.FAIL else Level.WARN,
            (if (stolen) "Магнитола подключена, но открылась не Monza Auto. " else "") +
                "Подключение магнитолы могут открывать и другие приложения: $names. " +
                "Если одно из них выбрано «по умолчанию», Monza Auto не получит подключение.",
            "Сбросьте «Открывать по умолчанию» у " + (if (others.size > 1) "этих приложений" else "этого приложения") +
                " (кнопка ниже открывает нужную страницу для ${"«" + first.label + "»"}).\n" + DEFAULT_APP_STEPS,
            Fix.OPEN_APP_DEFAULTS, first.packageName, "Настройки «${first.label}» по умолчанию",
        )
    }

    /** [mode]: the app setting; [carAudioMode]: AUDIO_TRANSMISSION_MODE from the head unit, if it told us. */
    fun audio(mode: String, carAudioMode: Int?): Check {
        val car = when (carAudioMode) {
            null -> "магнитола не сообщила, как хочет получать звук"
            1 -> "магнитола принимает звук телефона по Bluetooth"
            else -> "магнитола принимает звук по USB"
        }
        return when (mode) {
            "PHONE" -> Check(
                "audio", "Звук", Level.INFO, "Звук Android Auto играет телефон; $car.",
                "Подключите телефон к машине по Bluetooth (как аудиоустройство), чтобы звук шёл в динамики машины.",
            )
            "USB" -> if (carAudioMode == 1) {
                Check(
                    "audio", "Звук", Level.WARN, "Выбран звук через USB, но $car.",
                    "Если звука нет — выберите в настройках «Авто» или «С телефона».",
                )
            } else {
                Check("audio", "Звук", Level.OK, "Звук через USB в магнитолу; $car.")
            }
            else -> Check(
                "audio", "Звук", Level.OK,
                "Авто: " + (if (carAudioMode == 1) "звук с телефона по Bluetooth" else "звук через USB в магнитолу") + "; $car.",
                if (carAudioMode == 1) "Телефон должен быть подключён к машине по Bluetooth." else null,
            )
        }
    }

    /**
     * The link check repeats the server check when the last connection was refused because the server is not
     * running: show the problem once, as the server check, and keep the link check as a pointer to it.
     */
    fun dedupe(link: Check, server: Check): Check =
        if (link.level == Level.FAIL && link.fix == Fix.OPEN_AA_SETTINGS && server.level == Level.FAIL && server.fix == Fix.OPEN_AA_SETTINGS) {
            link.copy(level = Level.INFO, detail = "Не подключено: сервер головного устройства не запущен (см. выше).", todo = null, fix = Fix.NONE)
        } else {
            link
        }

    /** Most severe first; stable within a level. */
    fun sorted(checks: List<Check>): List<Check> = checks.sortedBy { it.level.ordinal }

    fun summary(checks: List<Check>): String {
        val fails = checks.count { it.level == Level.FAIL }
        val warns = checks.count { it.level == Level.WARN }
        return when {
            fails > 0 -> "Нужно исправить: $fails" + (if (warns > 0) ", обратить внимание: $warns" else "")
            warns > 0 -> "Всё основное готово, обратите внимание: $warns"
            else -> "Всё готово"
        }
    }

    fun report(checks: List<Check>, header: String): String = buildString {
        appendLine(header)
        appendLine(summary(checks))
        appendLine()
        for (c in checks) {
            val mark = when (c.level) {
                Level.OK -> "[OK]  "
                Level.WARN -> "[!]   "
                Level.FAIL -> "[X]   "
                Level.INFO -> "[i]   "
            }
            appendLine("$mark${c.title}: ${c.detail}")
            c.todo?.let { appendLine("      → $it") }
        }
    }
}
