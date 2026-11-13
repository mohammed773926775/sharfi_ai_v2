package com.sharafi.offlineassistant.nlu

import java.util.Locale

/**
 * MANDATE 3: Deterministic, rule-based Natural Language Understanding (NLU) Engine (Zero Generative AI).
 * Supports both English and Arabic (MSA + Yemeni/Gulf dialect phrasing) across all 13 mandated system domains
 * and splits compound utterances into ordered IntentCommand pipelines.
 */
class NluEngine {

    enum class ActionType {
        FLASHLIGHT,
        BATTERY,
        WIFI,
        BLUETOOTH,
        CAMERA,
        ALARM,
        TIMER,
        MEDIA_CONTROL,
        APPS_LAUNCH,
        CALLS,
        SMS,
        VOLUME,
        SCREEN_NAVIGATION,
        UNKNOWN
    }

    data class IntentCommand(
        val actionType: ActionType,
        val operation: String,
        val parameters: Map<String, String> = emptyMap(),
        val rawSegment: String = ""
    )

    private val compoundDelimiterRegex = Regex(
        pattern = "\\b(and then|then|and also|and|also|ثم|وبعدين|وكمان|وايضا|وأيضاً)\\b|(?<=\\S)\\s+و(?=\\s*(شغل|افتح|طفي|اطفئ|أطفئ|قفل|اتصل|ارسل|أرسل|اضبط|نبهني|كم|اعطني|قولي|ارفع|وطي|خفض|كتم|ارجع|ادخل))",
        options = setOf(RegexOption.IGNORE_CASE)
    )

    private val arabicNumberWords = mapOf(
        "واحد" to 1, "واحدة" to 1, "دقيقة" to 1, "ساعة" to 1,
        "اثنين" to 2, "إثنين" to 2, "دقيقتين" to 2, "ساعتين" to 2,
        "ثلاث" to 3, "ثلاثة" to 3,
        "اربع" to 4, "أربع" to 4, "اربعة" to 4, "أربعة" to 4,
        "خمس" to 5, "خمسة" to 5,
        "ست" to 6, "ستة" to 6,
        "سبع" to 7, "سبعة" to 7,
        "ثمان" to 8, "ثمانية" to 8,
        "تسع" to 9, "تسعة" to 9,
        "عشر" to 10, "عشرة" to 10,
        "عشرين" to 20, "ثلاثين" to 30, "نص" to 30, "نصف" to 30,
        "اربعين" to 40, "خمسين" to 50, "ستين" to 60
    )

    private val englishNumberWords = mapOf(
        "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
        "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10,
        "fifteen" to 15, "twenty" to 20, "thirty" to 30, "forty" to 40,
        "fifty" to 50, "sixty" to 60
    )

    /**
     * Parses a spoken utterance (single or compound command) into an ordered list of [IntentCommand]s.
     * Example: "Turn on the flashlight and tell me the battery" -> [FLASHLIGHT(ON), BATTERY(STATUS)]
     */
    fun parse(utterance: String): List<IntentCommand> {
        val cleaned = normalizeText(utterance)
        if (cleaned.isBlank()) return emptyList()

        val segments = cleaned
            .split(compoundDelimiterRegex)
            .map { it.trim() }
            .filter { it.isNotBlank() }

        if (segments.isEmpty()) {
            return listOf(parseSingleSegment(cleaned))
        }

        return segments.map { segment -> parseSingleSegment(segment) }
    }

    private fun parseSingleSegment(segment: String): IntentCommand {
        val lower = segment.lowercase(Locale.ROOT)

        // 1. FLASHLIGHT
        if (containsAny(lower, "flashlight", "torch", "flash", "كشاف", "الكشاف", "فلاش", "الفلاش", "ضوء", "النور")) {
            val turnOff = containsAny(lower, "off", "disable", "close", "stop", "طفي", "اطفئ", "أطفئ", "قفل", "اغلق", "أغلق", "ايقاف")
            val op = if (turnOff) "OFF" else "ON"
            return IntentCommand(ActionType.FLASHLIGHT, op, rawSegment = segment)
        }

        // 2. BATTERY
        if (containsAny(lower, "battery", "charge", "power level", "بطاريه", "بطارية", "البطاريه", "البطارية", "الشحن", "نسبة الشحن")) {
            return IntentCommand(ActionType.BATTERY, "STATUS", rawSegment = segment)
        }

        // 3. WI-FI
        if (containsAny(lower, "wifi", "wi-fi", "wi fi", "wireless", "واي فاي", "الواي فاي", "وايفاي", "الشبكة", "شبكة الواي فاي")) {
            val turnOff = containsAny(lower, "off", "disable", "disconnect", "طفي", "اطفئ", "أطفئ", "قفل", "اغلق", "افصل")
            val op = if (turnOff) "OFF" else "ON"
            return IntentCommand(ActionType.WIFI, op, rawSegment = segment)
        }

        // 4. BLUETOOTH
        if (containsAny(lower, "bluetooth", "بلوتوث", "البلوتوث")) {
            val turnOff = containsAny(lower, "off", "disable", "disconnect", "طفي", "اطفئ", "أطفئ", "قفل", "اغلق", "افصل")
            val op = if (turnOff) "OFF" else "ON"
            return IntentCommand(ActionType.BLUETOOTH, op, rawSegment = segment)
        }

        // 5. CAMERA
        if (containsAny(lower, "camera", "photo", "picture", "selfie", "video", "كاميرا", "الكاميرا", "صوره", "صورة", "تصوير", "سيلفي", "فيديو")) {
            val isVideo = containsAny(lower, "video", "record", "فيديو", "تسجيل فيديو")
            val op = if (isVideo) "VIDEO" else "PHOTO"
            return IntentCommand(ActionType.CAMERA, op, rawSegment = segment)
        }

        // 6. ALARM
        if (containsAny(lower, "alarm", "wake me", "منبه", "المنبه", "صحيني", "نبهني الساعة")) {
            val timeNumbers = extractAllNumbers(lower)
            val hour = timeNumbers.getOrNull(0) ?: 7
            val minute = timeNumbers.getOrNull(1) ?: 0
            return IntentCommand(
                actionType = ActionType.ALARM,
                operation = "SET",
                parameters = mapOf("hour" to hour.toString(), "minute" to minute.toString()),
                rawSegment = segment
            )
        }

        // 7. TIMER
        if (containsAny(lower, "timer", "countdown", "مؤقت", "المؤقت", "تايمر", "عداد")) {
            val number = extractAllNumbers(lower).firstOrNull() ?: 5
            val isSeconds = containsAny(lower, "second", "seconds", "ثانيه", "ثانية", "ثواني")
            val isHours = containsAny(lower, "hour", "hours", "ساعه", "ساعة", "ساعات")
            val seconds = when {
                isSeconds -> number
                isHours -> number * 3600
                else -> number * 60
            }
            return IntentCommand(
                actionType = ActionType.TIMER,
                operation = "SET",
                parameters = mapOf("seconds" to seconds.toString(), "displayValue" to number.toString()),
                rawSegment = segment
            )
        }

        // 8. MEDIA CONTROL
        if (containsAny(
                lower,
                "music", "song", "track", "media", "pause", "resume", "next song", "previous song",
                "موسيقى", "الموسيقى", "اغنيه", "أغنية", "الاغنية", "المقطع", "شغل الموسيقى", "وقف المؤقت", "التالي", "السابق"
            )
        ) {
            val op = when {
                containsAny(lower, "next", "skip", "التالي", "اللي بعده") -> "NEXT"
                containsAny(lower, "previous", "back track", "السابق", "اللي قبله") -> "PREVIOUS"
                containsAny(lower, "pause", "stop", "وقف", "ايقاف", "إيقاف") -> "PAUSE"
                else -> "PLAY"
            }
            return IntentCommand(ActionType.MEDIA_CONTROL, op, rawSegment = segment)
        }

        // 9. CALLS
        if (containsAny(lower, "call", "dial", "phone", "اتصل", "اتصال", "دق على", "كلم", "مكالمه", "مكالمة")) {
            val target = extractTargetAfterKeywords(
                lower,
                listOf("call", "dial", "phone", "اتصل ب", "اتصل على", "اتصل", "دق على", "كلم")
            )
            return IntentCommand(
                actionType = ActionType.CALLS,
                operation = "DIAL",
                parameters = mapOf("target" to target),
                rawSegment = segment
            )
        }

        // 10. SMS
        if (containsAny(lower, "sms", "text message", "send message", "message to", "رساله", "رسالة", "ارسل رساله", "أرسل رسالة", "مسج")) {
            val parsedSms = extractSmsRecipientAndBody(lower)
            return IntentCommand(
                actionType = ActionType.SMS,
                operation = "SEND",
                parameters = mapOf(
                    "recipient" to parsedSms.first,
                    "body" to parsedSms.second
                ),
                rawSegment = segment
            )
        }

        // 11. VOLUME
        if (containsAny(lower, "volume", "sound", "mute", "unmute", "louder", "quieter", "صوت", "الصوت", "كتم", "صامت", "ارفع الصوت", "وطي الصوت", "خفض الصوت")) {
            val op = when {
                containsAny(lower, "mute", "silent", "كتم", "صامت", "اكتم") -> "MUTE"
                containsAny(lower, "unmute", "فك الكتم", "الغاء الكتم") -> "UNMUTE"
                containsAny(lower, "down", "lower", "quieter", "decrease", "وطي", "خفض", "نزل", "قلل") -> "DOWN"
                containsAny(lower, "up", "higher", "louder", "increase", "raise", "ارفع", "علي", "زيد") -> "UP"
                else -> {
                    val level = extractAllNumbers(lower).firstOrNull()
                    if (level != null) "SET" else "UP"
                }
            }
            val levelParam = extractAllNumbers(lower).firstOrNull()?.toString() ?: "70"
            return IntentCommand(
                actionType = ActionType.VOLUME,
                operation = op,
                parameters = mapOf("level" to levelParam),
                rawSegment = segment
            )
        }

        // 12. SCREEN NAVIGATION
        if (containsAny(
                lower,
                "go home", "home screen", "go back", "recent apps", "recents", "notifications", "scroll down", "scroll up",
                "الشاشة الرئيسية", "الرئيسيه", "الرئيسية", "ارجع", "رجوع", "الخلف", "التطبيقات الاخيرة", "الاشعارات", "الإشعارات"
            )
        ) {
            val op = when {
                containsAny(lower, "home", "الرئيسيه", "الرئيسية") -> "HOME"
                containsAny(lower, "recent", "recents", "الاخيره", "الأخيرة") -> "RECENTS"
                containsAny(lower, "notification", "notifications", "الاشعارات", "الإشعارات") -> "NOTIFICATIONS"
                containsAny(lower, "scroll up", "فوق", "اعلى", "أعلى") -> "SCROLL_UP"
                containsAny(lower, "scroll down", "تحت", "اسفل", "أسفل") -> "SCROLL_DOWN"
                else -> "BACK"
            }
            return IntentCommand(ActionType.SCREEN_NAVIGATION, op, rawSegment = segment)
        }

        // 13. APPS LAUNCH
        if (containsAny(lower, "open", "launch", "start app", "run", "افتح", "شغل تطبيق", "ادخل")) {
            val appName = extractTargetAfterKeywords(
                lower,
                listOf("open app", "open", "launch", "start", "افتح تطبيق", "شغل تطبيق", "افتح", "ادخل")
            )
            return IntentCommand(
                actionType = ActionType.APPS_LAUNCH,
                operation = "LAUNCH",
                parameters = mapOf("appName" to appName),
                rawSegment = segment
            )
        }

        return IntentCommand(ActionType.UNKNOWN, "NONE", rawSegment = segment)
    }

    private fun normalizeText(input: String): String {
        return input
            .replace("[،,.;؟?!]".toRegex(), " ")
            .replace("\\s+".toRegex(), " ")
            .trim()
    }

    private fun containsAny(text: String, vararg keywords: String): Boolean {
        return keywords.any { kw -> text.contains(kw) }
    }

    private fun extractAllNumbers(text: String): List<Int> {
        val results = mutableListOf<Int>()
        val digitMatches = Regex("\\d+").findAll(text)
        for (match in digitMatches) {
            match.value.toIntOrNull()?.let { results.add(it) }
        }
        if (results.isNotEmpty()) return results

        val tokens = text.split(" ")
        for (token in tokens) {
            arabicNumberWords[token]?.let { results.add(it) }
            englishNumberWords[token]?.let { results.add(it) }
        }
        return results
    }

    private fun extractTargetAfterKeywords(text: String, prefixes: List<String>): String {
        for (prefix in prefixes) {
            val idx = text.indexOf(prefix)
            if (idx >= 0) {
                val remainder = text.substring(idx + prefix.length).trim()
                if (remainder.isNotBlank()) return remainder
            }
        }
        return text
    }

    private fun extractSmsRecipientAndBody(text: String): Pair<String, String> {
        // English pattern: "send message to <recipient> saying <body>"
        val enRegex = Regex("(?:send\\s+(?:sms|message|text)\\s+to|message\\s+to)\\s+([^\\s]+(?:\\s+[^\\s]+)?)\\s+(?:saying|that|body)?\\s*(.*)")
        enRegex.find(text)?.let { match ->
            val recipient = match.groupValues[1].trim()
            val body = match.groupValues[2].trim().ifBlank { "Hello from SHARAFI AI" }
            return recipient to body
        }

        // Arabic pattern: "ارسل رسالة الى <recipient> <body?>"
        val arRegex = Regex("(?:ارسل|أرسل)\\s+(?:رساله|رسالة)\\s+(?:الى|إلى|ل)\\s+([^\\s]+)\\s*(.*)")
        arRegex.find(text)?.let { match ->
            val recipient = match.groupValues[1].trim()
            val body = match.groupValues[2].trim().ifBlank { "مرحباً من مساعد الشرفي الذكي" }
            return recipient to body
        }

        return "" to text
    }
}
