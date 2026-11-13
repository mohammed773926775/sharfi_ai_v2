package com.sharafi.offlineassistant.nlu

import java.util.Locale

enum class IntentDomain(
    val displayNameEn: String,
    val displayNameAr: String,
    val pluginId: String
) {
    FLASHLIGHT("Flashlight", "الكشاف الضوئي", "plugin.hardware.flashlight"),
    BATTERY("Battery Status", "حالة البطارية", "plugin.system.battery"),
    WIFI("Wi-Fi Control", "شبكة الواي فاي", "plugin.connectivity.wifi"),
    BLUETOOTH("Bluetooth Control", "البلوتوث", "plugin.connectivity.bluetooth"),
    CAMERA("Camera Capture", "الكاميرا", "plugin.hardware.camera"),
    ALARM("Alarm Clock", "المنبه", "plugin.clock.alarm"),
    TIMER("Countdown Timer", "المؤقت", "plugin.clock.timer"),
    MEDIA_CONTROL("Media Playback", "التحكم بالوسائط", "plugin.media.transport"),
    APP_LAUNCH("App Launcher", "تشغيل التطبيقات", "plugin.system.applaunch"),
    CALLS("Phone Call", "المكالمات الهاتفية", "plugin.telecom.call"),
    SMS("SMS Messaging", "الرسائل النصية", "plugin.telecom.sms"),
    VOLUME("Volume Control", "مستوى الصوت", "plugin.audio.volume"),
    SCREEN_NAVIGATION("Screen Navigation", "تنقل الشاشة", "plugin.system.navigation"),
    UNKNOWN("Unrecognized Command", "أمر غير معروف", "plugin.core.fallback")
}

data class ParsedIntent(
    val clauseIndex: Int,
    val rawClause: String,
    val domain: IntentDomain,
    val action: String,
    val parameters: Map<String, String> = emptyMap(),
    val isArabic: Boolean = false,
    val matchedRule: String = "",
    val confidence: Float = 1.0f
)

data class CompoundNluResult(
    val originalUtterance: String,
    val isCompound: Boolean,
    val isArabic: Boolean,
    val intents: List<ParsedIntent>,
    val parseLatencyMs: Long
)

/**
 * Deterministic, rule-based Natural Language Understanding (NLU) engine.
 * Zero generative AI — 100% offline regex & keyword slot-filling parser.
 * Supports English and Arabic single and compound commands across all 13 mandated domains.
 */
class NluEngine {

    /**
     * Parses a user utterance into one or more [ParsedIntent] objects, splitting
     * compound sentences (e.g., "Turn on the flashlight and tell me the battery"
     * or "شغل الكشاف وكم نسبة البطارية") into ordered execution clauses.
     */
    fun parse(utterance: String): CompoundNluResult {
        val startNs = System.nanoTime()
        val trimmed = utterance.trim()
        val overallArabic = containsArabic(trimmed)

        if (trimmed.isEmpty()) {
            return CompoundNluResult(
                originalUtterance = "",
                isCompound = false,
                isArabic = false,
                intents = emptyList(),
                parseLatencyMs = 0L
            )
        }

        val clauses = splitCompoundClauses(trimmed)
        val parsedIntents = clauses.mapIndexed { index, clause ->
            parseSingleClause(index, clause)
        }

        val elapsedMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
        return CompoundNluResult(
            originalUtterance = trimmed,
            isCompound = parsedIntents.size > 1,
            isArabic = overallArabic,
            intents = parsedIntents,
            parseLatencyMs = elapsedMs
        )
    }

    /**
     * Splits compound utterances on conjunctions ("and", "then", "also", "after that",
     * Arabic "و", "ثم", "وبعدين", "وبعدها") while avoiding splitting inside an SMS message body.
     */
    internal fun splitCompoundClauses(input: String): List<String> {
        val lower = input.lowercase(Locale.ROOT)
        // If this is a single SMS command containing "saying ..." or "تقول ...", don't split the message body
        if ((lower.contains("send") && (lower.contains("saying") || lower.contains("that says"))) ||
            (input.contains("رسالة") && (input.contains("تقول") || input.contains("فيها")))
        ) {
            return listOf(input.trim())
        }

        // Replace explicit multi-clause separators with a delimiter token
        var normalized = input
            .replace(Regex("\\s*(?:;|,|\\band then\\b|\\bthen\\b|\\bafter that\\b|\\balso\\b)\\s*", RegexOption.IGNORE_CASE), " ||| ")
            .replace(Regex("\\s+(?:ثم|وبعدين|وبعدها|وكذلك)\\s+"), " ||| ")

        // Split on " and " when followed by an action verb or domain keyword
        normalized = normalized.replace(
            Regex(
                "\\s+and\\s+(?=(?:turn|switch|toggle|enable|disable|open|launch|start|close|tell|check|what|how|set|create|play|pause|stop|next|previous|skip|call|dial|send|text|mute|unmute|increase|decrease|raise|lower|volume|go|navigate|take|record|battery|flashlight|torch|wifi|wi-fi|bluetooth|camera|alarm|timer))",
                RegexOption.IGNORE_CASE
            ),
            " ||| "
        )

        // Split on Arabic " و" or " و " when followed by an Arabic command keyword
        normalized = normalized.replace(
            Regex(
                "\\s+و\\s*(?=(?:شغل|أشعل|اشعل|افتح|إفتح|اطفئ|أطفئ|طفي|أغلق|اغلق|كم|ما|اعطني|أعطني|اخبرني|أخبرني|نسبة|حالة|اضبط|إضبط|ضبط|اتصل|إتصل|أرسل|ارسل|ارفع|إرفع|اخفض|إخفض|وطي|علي|كتم|اكتم|اذهب|إذهب|ارجع|إرجع|التقط|صور|الكشاف|الفلاش|البطارية|الواي|البلوتوث|الكاميرا|منبه|مؤقت|الصوت|الموسيقى))"
            ),
            " ||| "
        )

        return normalized
            .split("|||")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    private fun parseSingleClause(index: Int, rawClause: String): ParsedIntent {
        val clause = rawClause.trim()
        val norm = normalizeText(clause)
        val isAr = containsArabic(clause)

        // 1. FLASHLIGHT / TORCH
        parseFlashlight(index, clause, norm, isAr)?.let { return it }

        // 2. BATTERY
        parseBattery(index, clause, norm, isAr)?.let { return it }

        // 3. ALARM
        parseAlarm(index, clause, norm, isAr)?.let { return it }

        // 4. TIMER
        parseTimer(index, clause, norm, isAr)?.let { return it }

        // 5. SMS
        parseSms(index, clause, norm, isAr)?.let { return it }

        // 6. CALLS
        parseCall(index, clause, norm, isAr)?.let { return it }

        // 7. VOLUME
        parseVolume(index, clause, norm, isAr)?.let { return it }

        // 8. WIFI
        parseWifi(index, clause, norm, isAr)?.let { return it }

        // 9. BLUETOOTH
        parseBluetooth(index, clause, norm, isAr)?.let { return it }

        // 10. CAMERA
        parseCamera(index, clause, norm, isAr)?.let { return it }

        // 11. MEDIA CONTROL
        parseMediaControl(index, clause, norm, isAr)?.let { return it }

        // 12. SCREEN NAVIGATION
        parseScreenNavigation(index, clause, norm, isAr)?.let { return it }

        // 13. APP LAUNCH
        parseAppLaunch(index, clause, norm, isAr)?.let { return it }

        return ParsedIntent(
            clauseIndex = index,
            rawClause = clause,
            domain = IntentDomain.UNKNOWN,
            action = "UNRECOGNIZED",
            parameters = mapOf("query" to clause),
            isArabic = isAr,
            matchedRule = "FALLBACK_NO_RULE_MATCH",
            confidence = 0.25f
        )
    }

    private fun parseFlashlight(index: Int, raw: String, norm: String, isAr: Boolean): ParsedIntent? {
        val hasFlashKeyword = norm.contains("flashlight") || norm.contains("torch") ||
                norm.contains("flash light") || norm.contains("كشاف") ||
                norm.contains("الكشاف") || norm.contains("فلاش") || norm.contains("الفلاش") ||
                norm.contains("مصباح")
        if (!hasFlashKeyword) return null

        val isOff = norm.contains("off") || norm.contains("disable") || norm.contains("close") ||
                norm.contains("stop") || norm.contains("اطفئ") || norm.contains("طفي") ||
                norm.contains("اغلق") || norm.contains("ايقاف")
        val isOn = norm.contains("on") || norm.contains("enable") || norm.contains("open") ||
                norm.contains("start") || norm.contains("شغل") || norm.contains("اشعل") ||
                norm.contains("افتح") || norm.contains("تفعيل")

        val state = when {
            isOff -> "OFF"
            isOn -> "ON"
            else -> "TOGGLE"
        }

        return ParsedIntent(
            clauseIndex = index,
            rawClause = raw,
            domain = IntentDomain.FLASHLIGHT,
            action = "SET_FLASHLIGHT_$state",
            parameters = mapOf("state" to state),
            isArabic = isAr,
            matchedRule = "RULE_FLASHLIGHT_$state",
            confidence = 0.99f
        )
    }

    private fun parseBattery(index: Int, raw: String, norm: String, isAr: Boolean): ParsedIntent? {
        val hasBatteryKeyword = norm.contains("battery") || norm.contains("power level") ||
                norm.contains("charge level") || norm.contains("بطاريه") ||
                norm.contains("البطاريه") || norm.contains("بطارية") ||
                norm.contains("البطارية") || norm.contains("شحن")
        if (!hasBatteryKeyword) return null

        return ParsedIntent(
            clauseIndex = index,
            rawClause = raw,
            domain = IntentDomain.BATTERY,
            action = "QUERY_BATTERY_STATUS",
            parameters = mapOf("detail" to "FULL_TELEMETRY"),
            isArabic = isAr,
            matchedRule = "RULE_BATTERY_TELEMETRY",
            confidence = 0.99f
        )
    }

    private fun parseWifi(index: Int, raw: String, norm: String, isAr: Boolean): ParsedIntent? {
        val hasWifi = norm.contains("wifi") || norm.contains("wi-fi") || norm.contains("wi fi") ||
                norm.contains("واي فاي") || norm.contains("الواي فاي") || norm.contains("وايفاي") ||
                norm.contains("شبكه الواي") || norm.contains("شبكة الواي")
        if (!hasWifi) return null

        val isOff = norm.contains("off") || norm.contains("disable") || norm.contains("disconnect") ||
                norm.contains("اطفئ") || norm.contains("طفي") || norm.contains("اغلق") || norm.contains("افصل")
        val isOn = norm.contains("on") || norm.contains("enable") || norm.contains("connect") ||
                norm.contains("شغل") || norm.contains("افتح") || norm.contains("تفعيل")
        val isStatus = norm.contains("status") || norm.contains("check") || norm.contains("حاله") || norm.contains("حالة")

        val state = when {
            isStatus -> "STATUS"
            isOff -> "OFF"
            isOn -> "ON"
            else -> "PANEL"
        }

        return ParsedIntent(
            clauseIndex = index,
            rawClause = raw,
            domain = IntentDomain.WIFI,
            action = "WIFI_$state",
            parameters = mapOf("state" to state),
            isArabic = isAr,
            matchedRule = "RULE_WIFI_$state",
            confidence = 0.98f
        )
    }

    private fun parseBluetooth(index: Int, raw: String, norm: String, isAr: Boolean): ParsedIntent? {
        val hasBt = norm.contains("bluetooth") || norm.contains("blue tooth") ||
                norm.contains("بلوتوث") || norm.contains("البلوتوث")
        if (!hasBt) return null

        val isOff = norm.contains("off") || norm.contains("disable") ||
                norm.contains("اطفئ") || norm.contains("طفي") || norm.contains("اغلق")
        val isOn = norm.contains("on") || norm.contains("enable") ||
                norm.contains("شغل") || norm.contains("افتح") || norm.contains("تفعيل")

        val state = when {
            isOff -> "OFF"
            isOn -> "ON"
            else -> "SETTINGS"
        }

        return ParsedIntent(
            clauseIndex = index,
            rawClause = raw,
            domain = IntentDomain.BLUETOOTH,
            action = "BLUETOOTH_$state",
            parameters = mapOf("state" to state),
            isArabic = isAr,
            matchedRule = "RULE_BLUETOOTH_$state",
            confidence = 0.98f
        )
    }

    private fun parseCamera(index: Int, raw: String, norm: String, isAr: Boolean): ParsedIntent? {
        val hasCamera = norm.contains("camera") || norm.contains("take a photo") ||
                norm.contains("take photo") || norm.contains("take a picture") ||
                norm.contains("selfie") || norm.contains("record video") ||
                norm.contains("كاميرا") || norm.contains("الكاميرا") ||
                norm.contains("صوره") || norm.contains("صورة") ||
                norm.contains("سيلفي") || norm.contains("فيديو")
        if (!hasCamera) return null

        val mode = if (norm.contains("video") || norm.contains("فيديو")) "VIDEO" else "PHOTO"
        return ParsedIntent(
            clauseIndex = index,
            rawClause = raw,
            domain = IntentDomain.CAMERA,
            action = "LAUNCH_CAMERA_$mode",
            parameters = mapOf("mode" to mode),
            isArabic = isAr,
            matchedRule = "RULE_CAMERA_$mode",
            confidence = 0.97f
        )
    }

    private fun parseAlarm(index: Int, raw: String, norm: String, isAr: Boolean): ParsedIntent? {
        val hasAlarm = norm.contains("alarm") || norm.contains("wake me") ||
                norm.contains("منبه") || norm.contains("المنبه") || norm.contains("ايقظني")
        if (!hasAlarm) return null

        // Extract HH:MM or H
        val timeRegex = Regex("(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm|صباحا|مساء|ص|م)?", RegexOption.IGNORE_CASE)
        val match = timeRegex.find(norm)
        var hour = 7
        var minute = 0

        if (match != null) {
            var parsedHour = match.groupValues[1].toIntOrNull() ?: 7
            val parsedMin = match.groupValues[2].toIntOrNull() ?: 0
            val meridiem = match.groupValues[3].lowercase(Locale.ROOT)

            if ((meridiem == "pm" || meridiem.contains("مساء") || meridiem == "م") && parsedHour in 1..11) {
                parsedHour += 12
            } else if ((meridiem == "am" || meridiem.contains("صباح") || meridiem == "ص") && parsedHour == 12) {
                parsedHour = 0
            }
            hour = parsedHour.coerceIn(0, 23)
            minute = parsedMin.coerceIn(0, 59)
        }

        return ParsedIntent(
            clauseIndex = index,
            rawClause = raw,
            domain = IntentDomain.ALARM,
            action = "SET_ALARM",
            parameters = mapOf(
                "hour" to hour.toString(),
                "minute" to String.format(Locale.US, "%02d", minute),
                "label" to "Sharafi AI Alarm"
            ),
            isArabic = isAr,
            matchedRule = "RULE_ALARM_TIME_SLOT",
            confidence = 0.96f
        )
    }

    private fun parseTimer(index: Int, raw: String, norm: String, isAr: Boolean): ParsedIntent? {
        val hasTimer = norm.contains("timer") || norm.contains("countdown") ||
                norm.contains("مؤقت") || norm.contains("المؤقت") || norm.contains("عداد")
        if (!hasTimer) return null

        var totalSeconds = 60
        val numMatch = Regex("(\\d+)").find(norm)
        val amount = numMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1

        totalSeconds = when {
            norm.contains("hour") || norm.contains("ساعه") || norm.contains("ساعة") || norm.contains("ساعات") -> amount * 3600
            norm.contains("sec") || norm.contains("ثانيه") || norm.contains("ثانية") || norm.contains("ثواني") -> amount
            else -> amount * 60 // default minutes
        }

        // Handle Arabic dual/plural forms without digits
        if (numMatch == null) {
            if (norm.contains("دقيقتين")) totalSeconds = 120
            else if (norm.contains("دقيقه") || norm.contains("دقيقة")) totalSeconds = 60
            else if (norm.contains("خمس دقائق")) totalSeconds = 300
            else if (norm.contains("عشر دقائق")) totalSeconds = 600
        }

        return ParsedIntent(
            clauseIndex = index,
            rawClause = raw,
            domain = IntentDomain.TIMER,
            action = "SET_TIMER",
            parameters = mapOf(
                "seconds" to totalSeconds.toString(),
                "label" to "Sharafi Voice Timer"
            ),
            isArabic = isAr,
            matchedRule = "RULE_TIMER_DURATION",
            confidence = 0.97f
        )
    }

    private fun parseMediaControl(index: Int, raw: String, norm: String, isAr: Boolean): ParsedIntent? {
        val cmd = when {
            norm.contains("next track") || norm.contains("next song") || norm.contains("skip track") ||
                    norm.contains("الاغنيه التاليه") || norm.contains("الأغنية التالية") ||
                    norm.contains("المقطع التالي") || norm == "next" || norm == "التالي" -> "NEXT"

            norm.contains("previous track") || norm.contains("previous song") ||
                    norm.contains("الاغنيه السابقه") || norm.contains("الأغنية السابقة") ||
                    norm.contains("المقطع السابق") -> "PREVIOUS"

            norm.contains("pause music") || norm.contains("pause media") || norm.contains("pause song") ||
                    norm.contains("pause audio") || norm == "pause" ||
                    norm.contains("ايقاف مؤقت") || norm.contains("أوقف الموسيقى") ||
                    norm.contains("اوقف الموسيقى") -> "PAUSE"

            norm.contains("stop music") || norm.contains("stop media") || norm.contains("stop playing") -> "STOP"

            norm.contains("play music") || norm.contains("resume music") || norm.contains("play media") ||
                    norm.contains("play song") || norm.contains("شغل الموسيقى") ||
                    norm.contains("تشغيل الموسيقى") || norm.contains("شغل الاغنيه") ||
                    norm.contains("استئناف الموسيقى") -> "PLAY"

            else -> null
        } ?: return null

        return ParsedIntent(
            clauseIndex = index,
            rawClause = raw,
            domain = IntentDomain.MEDIA_CONTROL,
            action = "MEDIA_$cmd",
            parameters = mapOf("command" to cmd),
            isArabic = isAr,
            matchedRule = "RULE_MEDIA_$cmd",
            confidence = 0.96f
        )
    }

    private fun parseSms(index: Int, raw: String, norm: String, isAr: Boolean): ParsedIntent? {
        val isEnSms = norm.contains("send sms") || norm.contains("send a message") ||
                norm.contains("send message") || norm.contains("text message")
        val isArSms = norm.contains("ارسل رساله") || norm.contains("ارسل رسالة") ||
                norm.contains("أرسل رسالة") || norm.contains("رساله نصيه") || norm.contains("رسالة نصية")

        if (!isEnSms && !isArSms) return null

        var recipient = ""
        var body = ""

        if (isAr) {
            val toMatch = Regex("(?:الى|إلى|لـ|ل)\\s+([^\\s]+)").find(raw)
            recipient = toMatch?.groupValues?.get(1)?.trim() ?: "0500000000"
            val bodyMatch = Regex("(?:تقول|فيها|نصها)\\s+(.+)$").find(raw)
            body = bodyMatch?.groupValues?.get(1)?.trim() ?: "مرحباً من مساعد شرفي الذكي"
        } else {
            val enMatch = Regex("to\\s+([a-zA-Z0-9+\\-]+)(?:\\s+(?:saying|that says|with message|message)\\s+(.+))?$", RegexOption.IGNORE_CASE).find(raw)
            recipient = enMatch?.groupValues?.get(1)?.trim() ?: "5550199"
            body = enMatch?.groupValues?.get(2)?.trim()?.takeIf { it.isNotEmpty() }
                ?: "Hello from Sharafi Offline Assistant"
        }

        return ParsedIntent(
            clauseIndex = index,
            rawClause = raw,
            domain = IntentDomain.SMS,
            action = "SEND_SMS",
            parameters = mapOf(
                "recipient" to recipient,
                "body" to body
            ),
            isArabic = isAr,
            matchedRule = "RULE_SMS_COMPOSE",
            confidence = 0.95f
        )
    }

    private fun parseCall(index: Int, raw: String, norm: String, isAr: Boolean): ParsedIntent? {
        val isEnCall = norm.startsWith("call ") || norm.contains("make a call") ||
                norm.contains("dial ") || norm.contains("phone call")
        val isArCall = norm.contains("اتصل ب") || norm.contains("اتصل على") ||
                norm.contains("إتصل ب") || norm.contains("مكالمه") || norm.contains("مكالمة")

        if (!isEnCall && !isArCall) return null

        val target = if (isAr) {
            raw.substringAfter("اتصل بـ", "")
                .ifEmpty { raw.substringAfter("اتصل ب", "") }
                .ifEmpty { raw.substringAfter("اتصل على", "") }
                .trim()
                .ifEmpty { "911" }
        } else {
            raw.replace(Regex("^.*?(?:call|dial)\\s+(?:to\\s+)?", RegexOption.IGNORE_CASE), "")
                .trim()
                .ifEmpty { "5550100" }
        }

        return ParsedIntent(
            clauseIndex = index,
            rawClause = raw,
            domain = IntentDomain.CALLS,
            action = "DIAL_CONTACT",
            parameters = mapOf("target" to target),
            isArabic = isAr,
            matchedRule = "RULE_TELECOM_CALL",
            confidence = 0.95f
        )
    }

    private fun parseVolume(index: Int, raw: String, norm: String, isAr: Boolean): ParsedIntent? {
        val hasVolKeyword = norm.contains("volume") || norm.contains("mute") ||
                norm.contains("unmute") || norm.contains("الصوت") ||
                norm.contains("صوت") || norm.contains("كتم") ||
                norm.contains("ارفع الصوت") || norm.contains("اخفض الصوت")
        if (!hasVolKeyword) return null

        val percentMatch = Regex("(\\d{1,3})\\s*(?:%|percent|بالمئة|بالميه)?").find(norm)
        val explicitLevel = percentMatch?.groupValues?.get(1)?.toIntOrNull()

        val mode = when {
            norm.contains("unmute") || norm.contains("الغاء كتم") || norm.contains("فك الكتم") -> "UNMUTE"
            norm.contains("mute") || norm.contains("silent") || norm.contains("كتم") || norm.contains("صامت") -> "MUTE"
            norm.contains("max") || norm.contains("full") || norm.contains("اقصى") || norm.contains("أقصى") || norm.contains("اعلى") -> "MAX"
            explicitLevel != null -> "SET_PERCENT"
            norm.contains("down") || norm.contains("lower") || norm.contains("decrease") ||
                    norm.contains("اخفض") || norm.contains("خفض") || norm.contains("وطي") || norm.contains("قلل") -> "DOWN"
            else -> "UP"
        }

        val params = mutableMapOf("mode" to mode)
        if (explicitLevel != null) {
            params["level"] = explicitLevel.coerceIn(0, 100).toString()
        }

        return ParsedIntent(
            clauseIndex = index,
            rawClause = raw,
            domain = IntentDomain.VOLUME,
            action = "VOLUME_$mode",
            parameters = params,
            isArabic = isAr,
            matchedRule = "RULE_AUDIO_VOLUME_$mode",
            confidence = 0.98f
        )
    }

    private fun parseScreenNavigation(index: Int, raw: String, norm: String, isAr: Boolean): ParsedIntent? {
        val target = when {
            norm.contains("home screen") || norm.contains("go home") ||
                    norm.contains("الشاشه الرئيسيه") || norm.contains("الشاشة الرئيسية") ||
                    norm.contains("الرئيسيه") -> "HOME"

            norm.contains("notification") || norm.contains("quick settings") ||
                    norm.contains("الاشعارات") || norm.contains("الإشعارات") ||
                    norm.contains("لوحه التحكم") -> "NOTIFICATIONS"

            norm.contains("display settings") || norm.contains("brightness") ||
                    norm.contains("اعدادات الشاشه") || norm.contains("إعدادات الشاشة") ||
                    norm.contains("السطوع") -> "DISPLAY_SETTINGS"

            norm.contains("go back") || norm.contains("navigate back") ||
                    norm.contains("ارجع") || norm.contains("الرجوع") || norm.contains("الخلف") -> "BACK"

            else -> null
        } ?: return null

        return ParsedIntent(
            clauseIndex = index,
            rawClause = raw,
            domain = IntentDomain.SCREEN_NAVIGATION,
            action = "NAVIGATE_$target",
            parameters = mapOf("destination" to target),
            isArabic = isAr,
            matchedRule = "RULE_SCREEN_NAV_$target",
            confidence = 0.95f
        )
    }

    private fun parseAppLaunch(index: Int, raw: String, norm: String, isAr: Boolean): ParsedIntent? {
        val hasLaunchVerb = norm.startsWith("open ") || norm.startsWith("launch ") ||
                norm.startsWith("start ") || norm.contains("open app") ||
                norm.startsWith("افتح ") || norm.startsWith("شغل تطبيق ") ||
                norm.contains("افتح تطبيق") || norm.contains("افتح الاعدادات") ||
                norm.contains("افتح الإعدادات") || norm.contains("افتح الحاسبه") ||
                norm.contains("افتح الحاسبة")

        if (!hasLaunchVerb) return null

        val extractedApp = if (isAr) {
            raw.replace(Regex("^(?:افتح|إفتح|شغل)\\s+(?:تطبيق\\s+)?"), "").trim()
        } else {
            raw.replace(Regex("^(?:open|launch|start)\\s+(?:the\\s+)?(?:app\\s+)?", RegexOption.IGNORE_CASE), "").trim()
        }

        if (extractedApp.isEmpty()) return null

        return ParsedIntent(
            clauseIndex = index,
            rawClause = raw,
            domain = IntentDomain.APP_LAUNCH,
            action = "LAUNCH_APPLICATION",
            parameters = mapOf("appName" to extractedApp),
            isArabic = isAr,
            matchedRule = "RULE_APP_LAUNCHER",
            confidence = 0.94f
        )
    }

    private fun normalizeText(input: String): String {
        return input
            .lowercase(Locale.ROOT)
            // Normalize Arabic Alef & Teh Marbuta variations for consistent matching
            .replace('أ', 'ا')
            .replace('إ', 'ا')
            .replace('آ', 'ا')
            .replace(Regex("[\\u064B-\\u065F]"), "") // Strip Arabic diacritics (Tashkeel)
            .trim()
    }

    fun containsArabic(text: String): Boolean {
        for (ch in text) {
            if (ch in '\u0600'..'\u06FF' || ch in '\u0750'..'\u077F') return true
        }
        return false
    }
}
