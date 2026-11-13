package com.sharafi.offlineassistant.system

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import android.telephony.SmsManager
import android.view.KeyEvent
import com.sharafi.offlineassistant.nlu.NluEngine
import com.sharafi.offlineassistant.nlu.NluEngine.ActionType
import com.sharafi.offlineassistant.nlu.NluEngine.IntentCommand
import java.util.Locale

/**
 * MANDATE 3: Plugin-based SystemActionExecutor architecture.
 * Every device capability implements [ActionPlugin] and registers dynamically in [pluginRegistry],
 * making it trivial to add new custom offline plugins without modifying core orchestration logic.
 */
class SystemActionExecutor(private val context: Context) {

    interface ActionPlugin {
        val supportedAction: ActionType
        fun execute(context: Context, command: IntentCommand, preferArabic: Boolean): ActionResult
    }

    data class ActionResult(
        val success: Boolean,
        val spokenResponse: String,
        val actionType: ActionType
    )

    private val pluginRegistry = mutableMapOf<ActionType, ActionPlugin>()

    init {
        // Register all 13 built-in offline system action plugins
        registerPlugin(FlashlightPlugin())
        registerPlugin(BatteryPlugin())
        registerPlugin(WifiPlugin())
        registerPlugin(BluetoothPlugin())
        registerPlugin(CameraPlugin())
        registerPlugin(AlarmPlugin())
        registerPlugin(TimerPlugin())
        registerPlugin(MediaControlPlugin())
        registerPlugin(AppsLaunchPlugin())
        registerPlugin(CallsPlugin())
        registerPlugin(SmsPlugin())
        registerPlugin(VolumePlugin())
        registerPlugin(ScreenNavigationPlugin())
    }

    /**
     * Public API to register or override any [ActionPlugin] at runtime.
     */
    fun registerPlugin(plugin: ActionPlugin) {
        pluginRegistry[plugin.supportedAction] = plugin
    }

    /**
     * Executes a single or compound list of [IntentCommand]s sequentially and aggregates the spoken response.
     */
    fun executeCommands(
        commands: List<IntentCommand>,
        preferArabic: Boolean
    ): List<ActionResult> {
        if (commands.isEmpty()) {
            return listOf(
                ActionResult(
                    success = false,
                    spokenResponse = if (preferArabic) "لم أتمكن من سماع الأمر." else "I did not catch any command.",
                    actionType = ActionType.UNKNOWN
                )
            )
        }

        return commands.map { cmd ->
            val plugin = pluginRegistry[cmd.actionType]
            if (plugin != null) {
                try {
                    plugin.execute(context, cmd, preferArabic)
                } catch (e: Exception) {
                    ActionResult(
                        success = false,
                        spokenResponse = if (preferArabic) {
                            "حدث خطأ أثناء تنفيذ الأمر: ${e.localizedMessage}"
                        } else {
                            "Error executing command: ${e.localizedMessage}"
                        },
                        actionType = cmd.actionType
                    )
                }
            } else {
                ActionResult(
                    success = false,
                    spokenResponse = if (preferArabic) {
                        "عذراً، لم أفهم الأمر: ${cmd.rawSegment}"
                    } else {
                        "Sorry, I did not understand: ${cmd.rawSegment}"
                    },
                    actionType = ActionType.UNKNOWN
                )
            }
        }
    }

    // =========================================================================
    // 1. FLASHLIGHT PLUGIN
    // =========================================================================
    private class FlashlightPlugin : ActionPlugin {
        override val supportedAction = ActionType.FLASHLIGHT

        override fun execute(context: Context, command: IntentCommand, preferArabic: Boolean): ActionResult {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return ActionResult(
                success = false,
                spokenResponse = if (preferArabic) "الكشاف غير متوفر في هذا الجهاز." else "Flashlight is not available on this device.",
                actionType = supportedAction
            )

            val enable = command.operation == "ON"
            cameraManager.setTorchMode(cameraId, enable)
            val msg = if (preferArabic) {
                if (enable) "تم تشغيل الكشاف." else "تم إيقاف الكشاف."
            } else {
                if (enable) "Flashlight turned on." else "Flashlight turned off."
            }
            return ActionResult(true, msg, supportedAction)
        }
    }

    // =========================================================================
    // 2. BATTERY PLUGIN
    // =========================================================================
    private class BatteryPlugin : ActionPlugin {
        override val supportedAction = ActionType.BATTERY

        override fun execute(context: Context, command: IntentCommand, preferArabic: Boolean): ActionResult {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL

            val pct = if (level >= 0 && scale > 0) (level * 100) / scale else 0
            val msg = if (preferArabic) {
                val stateStr = if (isCharging) "والهاتف قيد الشحن" else "والهاتف غير متصل بالشاحن"
                "نسبة البطارية الحالية $pct بالمائة $stateStr."
            } else {
                val stateStr = if (isCharging) "and charging" else "on battery power"
                "Battery level is $pct percent $stateStr."
            }
            return ActionResult(true, msg, supportedAction)
        }
    }

    // =========================================================================
    // 3. WI-FI PLUGIN
    // =========================================================================
    private class WifiPlugin : ActionPlugin {
        override val supportedAction = ActionType.WIFI

        @Suppress("DEPRECATION")
        override fun execute(context: Context, command: IntentCommand, preferArabic: Boolean): ActionResult {
            val enable = command.operation == "ON"
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val panelIntent = Intent(Settings.Panel.ACTION_WIFI).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(panelIntent)
                ActionResult(
                    success = true,
                    spokenResponse = if (preferArabic) "تم فتح لوحة التحكم بالواي فاي." else "Opened Wi-Fi control panel.",
                    actionType = supportedAction
                )
            } else {
                val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                wifiManager.isWifiEnabled = enable
                ActionResult(
                    success = true,
                    spokenResponse = if (preferArabic) {
                        if (enable) "تم تشغيل الواي فاي." else "تم إيقاف الواي فاي."
                    } else {
                        if (enable) "Wi-Fi enabled." else "Wi-Fi disabled."
                    },
                    actionType = supportedAction
                )
            }
        }
    }

    // =========================================================================
    // 4. BLUETOOTH PLUGIN
    // =========================================================================
    private class BluetoothPlugin : ActionPlugin {
        override val supportedAction = ActionType.BLUETOOTH

        @SuppressLint("MissingPermission")
        @Suppress("DEPRECATION")
        override fun execute(context: Context, command: IntentCommand, preferArabic: Boolean): ActionResult {
            val enable = command.operation == "ON"
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val btIntent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(btIntent)
                ActionResult(
                    success = true,
                    spokenResponse = if (preferArabic) "تم فتح إعدادات البلوتوث." else "Opened Bluetooth settings.",
                    actionType = supportedAction
                )
            } else {
                val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
                val adapter = btManager.adapter
                if (enable) adapter?.enable() else adapter?.disable()
                ActionResult(
                    success = true,
                    spokenResponse = if (preferArabic) {
                        if (enable) "تم تشغيل البلوتوث." else "تم إيقاف البلوتوث."
                    } else {
                        if (enable) "Bluetooth turned on." else "Bluetooth turned off."
                    },
                    actionType = supportedAction
                )
            }
        }
    }

    // =========================================================================
    // 5. CAMERA PLUGIN
    // =========================================================================
    private class CameraPlugin : ActionPlugin {
        override val supportedAction = ActionType.CAMERA

        override fun execute(context: Context, command: IntentCommand, preferArabic: Boolean): ActionResult {
            val action = if (command.operation == "VIDEO") {
                MediaStore.INTENT_ACTION_VIDEO_CAMERA
            } else {
                MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA
            }
            val intent = Intent(action).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            return ActionResult(
                success = true,
                spokenResponse = if (preferArabic) "تم فتح الكاميرا." else "Opening camera.",
                actionType = supportedAction
            )
        }
    }

    // =========================================================================
    // 6. ALARM PLUGIN
    // =========================================================================
    private class AlarmPlugin : ActionPlugin {
        override val supportedAction = ActionType.ALARM

        override fun execute(context: Context, command: IntentCommand, preferArabic: Boolean): ActionResult {
            val hour = command.parameters["hour"]?.toIntOrNull()?.coerceIn(0, 23) ?: 7
            val minute = command.parameters["minute"]?.toIntOrNull()?.coerceIn(0, 59) ?: 0
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, hour)
                putExtra(AlarmClock.EXTRA_MINUTES, minute)
                putExtra(AlarmClock.EXTRA_MESSAGE, "SHARAFI AI Alarm")
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            val formatted = String.format(Locale.US, "%02d:%02d", hour, minute)
            return ActionResult(
                success = true,
                spokenResponse = if (preferArabic) "تم ضبط المنبه على الساعة $formatted." else "Alarm set for $formatted.",
                actionType = supportedAction
            )
        }
    }

    // =========================================================================
    // 7. TIMER PLUGIN
    // =========================================================================
    private class TimerPlugin : ActionPlugin {
        override val supportedAction = ActionType.TIMER

        override fun execute(context: Context, command: IntentCommand, preferArabic: Boolean): ActionResult {
            val seconds = command.parameters["seconds"]?.toIntOrNull()?.coerceAtLeast(1) ?: 60
            val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                putExtra(AlarmClock.EXTRA_MESSAGE, "SHARAFI AI Timer")
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            val minutes = seconds / 60
            return ActionResult(
                success = true,
                spokenResponse = if (preferArabic) {
                    if (minutes > 0) "تم ضبط المؤقت لمدة $minutes دقيقة." else "تم ضبط المؤقت لمدة $seconds ثانية."
                } else {
                    if (minutes > 0) "Timer set for $minutes minutes." else "Timer set for $seconds seconds."
                },
                actionType = supportedAction
            )
        }
    }

    // =========================================================================
    // 8. MEDIA CONTROL PLUGIN
    // =========================================================================
    private class MediaControlPlugin : ActionPlugin {
        override val supportedAction = ActionType.MEDIA_CONTROL

        override fun execute(context: Context, command: IntentCommand, preferArabic: Boolean): ActionResult {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val keyCode = when (command.operation) {
                "NEXT" -> KeyEvent.KEYCODE_MEDIA_NEXT
                "PREVIOUS" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
                "PAUSE" -> KeyEvent.KEYCODE_MEDIA_PAUSE
                else -> KeyEvent.KEYCODE_MEDIA_PLAY
            }
            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))

            val msg = if (preferArabic) {
                when (command.operation) {
                    "NEXT" -> "تم الانتقال للمقطع التالي."
                    "PREVIOUS" -> "تم الرجوع للمقطع السابق."
                    "PAUSE" -> "تم إيقاف الوسائط مؤقتاً."
                    else -> "تم تشغيل الوسائط."
                }
            } else {
                when (command.operation) {
                    "NEXT" -> "Skipped to next track."
                    "PREVIOUS" -> "Returned to previous track."
                    "PAUSE" -> "Media paused."
                    else -> "Playing media."
                }
            }
            return ActionResult(true, msg, supportedAction)
        }
    }

    // =========================================================================
    // 9. APPS LAUNCH PLUGIN
    // =========================================================================
    private class AppsLaunchPlugin : ActionPlugin {
        override val supportedAction = ActionType.APPS_LAUNCH

        private val knownAliases = mapOf(
            "واتساب" to "com.whatsapp",
            "whatsapp" to "com.whatsapp",
            "يوتيوب" to "com.google.android.youtube",
            "youtube" to "com.google.android.youtube",
            "تيليجرام" to "org.telegram.messenger",
            "تليجرام" to "org.telegram.messenger",
            "telegram" to "org.telegram.messenger",
            "كروم" to "com.android.chrome",
            "chrome" to "com.android.chrome",
            "الاعدادات" to "com.android.settings",
            "الإعدادات" to "com.android.settings",
            "settings" to "com.android.settings"
        )

        override fun execute(context: Context, command: IntentCommand, preferArabic: Boolean): ActionResult {
            val targetName = (command.parameters["appName"] ?: "").lowercase(Locale.ROOT).trim()
            val pm = context.packageManager

            // 1. Check direct alias map
            val aliasPkg = knownAliases.entries.firstOrNull { targetName.contains(it.key) }?.value
            if (aliasPkg != null) {
                val launchIntent = pm.getLaunchIntentForPackage(aliasPkg)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(launchIntent)
                    return ActionResult(
                        true,
                        if (preferArabic) "تم فتح $targetName." else "Launched $targetName.",
                        supportedAction
                    )
                }
            }

            // 2. Scan installed launchable applications by label
            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val apps = pm.queryIntentActivities(mainIntent, 0)
            val matchedApp = apps.firstOrNull { resolveInfo ->
                val label = resolveInfo.loadLabel(pm).toString().lowercase(Locale.ROOT)
                label.contains(targetName) || targetName.contains(label)
            }

            return if (matchedApp != null) {
                val pkg = matchedApp.activityInfo.packageName
                val launchIntent = pm.getLaunchIntentForPackage(pkg)?.apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (launchIntent != null) {
                    context.startActivity(launchIntent)
                }
                ActionResult(
                    true,
                    if (preferArabic) "جاري فتح التطبيق." else "Opening application.",
                    supportedAction
                )
            } else {
                ActionResult(
                    false,
                    if (preferArabic) "لم أجد تطبيقاً باسم $targetName." else "Could not find app $targetName.",
                    supportedAction
                )
            }
        }
    }

    // =========================================================================
    // 10. CALLS PLUGIN
    // =========================================================================
    private class CallsPlugin : ActionPlugin {
        override val supportedAction = ActionType.CALLS

        override fun execute(context: Context, command: IntentCommand, preferArabic: Boolean): ActionResult {
            val target = command.parameters["target"]?.trim().orEmpty()
            val digits = target.filter { it.isDigit() || it == '+' }
            val uri = if (digits.length >= 3) Uri.parse("tel:$digits") else Uri.parse("tel:")
            val action = if (digits.length >= 3) Intent.ACTION_CALL else Intent.ACTION_DIAL
            val intent = Intent(action, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            return try {
                context.startActivity(intent)
                ActionResult(
                    true,
                    if (preferArabic) "جاري الاتصال بـ $target." else "Calling $target.",
                    supportedAction
                )
            } catch (se: SecurityException) {
                val fallbackDial = Intent(Intent.ACTION_DIAL, uri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallbackDial)
                ActionResult(
                    true,
                    if (preferArabic) "تم فتح لوحة الاتصال لـ $target." else "Opened dialer for $target.",
                    supportedAction
                )
            }
        }
    }

    // =========================================================================
    // 11. SMS PLUGIN
    // =========================================================================
    private class SmsPlugin : ActionPlugin {
        override val supportedAction = ActionType.SMS

        override fun execute(context: Context, command: IntentCommand, preferArabic: Boolean): ActionResult {
            val recipient = command.parameters["recipient"]?.trim().orEmpty()
            val body = command.parameters["body"]?.trim().orEmpty()
            val digits = recipient.filter { it.isDigit() || it == '+' }

            return if (digits.length >= 3 && body.isNotBlank()) {
                try {
                    val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        context.getSystemService(SmsManager::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        SmsManager.getDefault()
                    }
                    smsManager.sendTextMessage(digits, null, body, null, null)
                    ActionResult(
                        true,
                        if (preferArabic) "تم إرسال الرسالة النصية إلى $digits." else "SMS sent to $digits.",
                        supportedAction
                    )
                } catch (e: Exception) {
                    openSmsComposer(context, digits, body, preferArabic)
                }
            } else {
                openSmsComposer(context, digits, body, preferArabic)
            }
        }

        private fun openSmsComposer(
            context: Context,
            digits: String,
            body: String,
            preferArabic: Boolean
        ): ActionResult {
            val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$digits")).apply {
                putExtra("sms_body", body)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            return ActionResult(
                true,
                if (preferArabic) "تم تجهيز الرسالة في تطبيق الرسائل." else "Prepared SMS in messaging app.",
                supportedAction
            )
        }
    }

    // =========================================================================
    // 12. VOLUME PLUGIN
    // =========================================================================
    private class VolumePlugin : ActionPlugin {
        override val supportedAction = ActionType.VOLUME

        override fun execute(context: Context, command: IntentCommand, preferArabic: Boolean): ActionResult {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val stream = AudioManager.STREAM_MUSIC
            val maxVol = audioManager.getStreamMaxVolume(stream).coerceAtLeast(1)

            when (command.operation) {
                "MUTE" -> audioManager.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
                "UNMUTE" -> audioManager.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI)
                "DOWN" -> audioManager.adjustStreamVolume(stream, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                "SET" -> {
                    val pct = command.parameters["level"]?.toIntOrNull()?.coerceIn(0, 100) ?: 70
                    val targetVol = (pct * maxVol) / 100
                    audioManager.setStreamVolume(stream, targetVol, AudioManager.FLAG_SHOW_UI)
                }
                else -> audioManager.adjustStreamVolume(stream, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            }

            val currentVol = audioManager.getStreamVolume(stream)
            val currentPct = (currentVol * 100) / maxVol
            return ActionResult(
                true,
                if (preferArabic) "تم تعديل مستوى الصوت إلى $currentPct بالمائة." else "Volume adjusted to $currentPct percent.",
                supportedAction
            )
        }
    }

    // =========================================================================
    // 13. SCREEN NAVIGATION PLUGIN
    // =========================================================================
    private class ScreenNavigationPlugin : ActionPlugin {
        override val supportedAction = ActionType.SCREEN_NAVIGATION

        override fun execute(context: Context, command: IntentCommand, preferArabic: Boolean): ActionResult {
            val a11y = SharafiNavigationAccessibilityService.instance
            if (command.operation == "HOME" && a11y == null) {
                val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(homeIntent)
                return ActionResult(
                    true,
                    if (preferArabic) "تم الانتقال إلى الشاشة الرئيسية." else "Navigated to Home screen.",
                    supportedAction
                )
            }

            if (a11y != null) {
                val actionId = when (command.operation) {
                    "HOME" -> AccessibilityService.GLOBAL_ACTION_HOME
                    "RECENTS" -> AccessibilityService.GLOBAL_ACTION_RECENTS
                    "NOTIFICATIONS" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
                    else -> AccessibilityService.GLOBAL_ACTION_BACK
                }
                a11y.performGlobalAction(actionId)
                return ActionResult(
                    true,
                    if (preferArabic) "تم تنفيذ أمر التنقل بنجاح." else "Screen navigation executed.",
                    supportedAction
                )
            }

            val settingsIntent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(settingsIntent)
            return ActionResult(
                false,
                if (preferArabic) {
                    "يرجى تفعيل خدمة إمكانية الوصول الخاصة بـ SHARAFI AI للتنقل في الشاشة."
                } else {
                    "Please enable SHARAFI AI Accessibility Service for global screen navigation."
                },
                supportedAction
            )
        }
    }
}
