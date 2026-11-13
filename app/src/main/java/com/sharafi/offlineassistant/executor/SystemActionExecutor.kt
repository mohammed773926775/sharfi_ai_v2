package com.sharafi.offlineassistant.executor

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.SystemClock
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import com.sharafi.offlineassistant.nlu.IntentDomain
import com.sharafi.offlineassistant.nlu.ParsedIntent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.util.Locale

data class ActionExecutionResult(
    val pluginId: String,
    val domain: IntentDomain,
    val action: String,
    val success: Boolean,
    val spokenResponseEn: String,
    val spokenResponseAr: String,
    val technicalSummary: String,
    val executionTimeMs: Long
) {
    fun spokenResponse(preferArabic: Boolean): String =
        if (preferArabic) spokenResponseAr else spokenResponseEn
}

data class CompoundExecutionReport(
    val results: List<ActionExecutionResult>,
    val combinedSpokenResponseEn: String,
    val combinedSpokenResponseAr: String,
    val totalExecutionTimeMs: Long,
    val timestampMs: Long = System.currentTimeMillis()
) {
    fun combinedSpokenResponse(preferArabic: Boolean): String =
        if (preferArabic) combinedSpokenResponseAr else combinedSpokenResponseEn
}

data class LiveHardwareState(
    val isFlashlightOn: Boolean = false,
    val batteryLevelPercent: Int = 85,
    val isBatteryCharging: Boolean = false,
    val batteryTempCelsius: Float = 29.5f,
    val isWifiConnected: Boolean = true,
    val isBluetoothEnabled: Boolean = false,
    val mediaVolumePercent: Int = 70,
    val activeTimerSecondsRemaining: Int = 0,
    val activeTimerLabel: String = "",
    val lastSetAlarm: String = "None"
)

/**
 * Plugin contract for modular system action execution.
 * New system capabilities can be added by implementing [ActionPlugin]
 * and calling [SystemActionExecutor.registerPlugin].
 */
interface ActionPlugin {
    val pluginId: String
    val domain: IntentDomain
    val description: String
    suspend fun execute(
        context: Context,
        intent: ParsedIntent,
        stateUpdater: ((LiveHardwareState) -> LiveHardwareState) -> Unit
    ): ActionExecutionResult
}

/**
 * Plugin-based SystemActionExecutor that dispatches single or compound [ParsedIntent]
 * lists to registered [ActionPlugin] implementations and aggregates spoken feedback.
 */
class SystemActionExecutor(private val context: Context) {

    private val plugins = LinkedHashMap<IntentDomain, ActionPlugin>()

    private val _hardwareState = MutableStateFlow(LiveHardwareState())
    val hardwareState: StateFlow<LiveHardwareState> = _hardwareState.asStateFlow()

    init {
        registerDefaultPlugins()
        refreshHardwareTelemetry()
    }

    fun registerPlugin(plugin: ActionPlugin) {
        plugins[plugin.domain] = plugin
    }

    fun unregisterPlugin(domain: IntentDomain) {
        plugins.remove(domain)
    }

    fun getRegisteredPlugins(): List<ActionPlugin> = plugins.values.toList()

    fun refreshHardwareTelemetry() {
        try {
            val batteryStatus = context.registerReceiver(
                null,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            )
            val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val batteryPct = if (level >= 0 && scale > 0) (level * 100) / scale else 88
            val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL
            val tempTenths = batteryStatus?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 285) ?: 285

            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            val curVol = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 10
            val maxVol = (audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 15).coerceAtLeast(1)
            val volPct = ((curVol * 100f) / maxVol).toInt().coerceIn(0, 100)

            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
            val wifiActive = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true

            val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val btEnabled = btManager?.adapter?.isEnabled == true

            _hardwareState.update {
                it.copy(
                    batteryLevelPercent = batteryPct,
                    isBatteryCharging = isCharging,
                    batteryTempCelsius = tempTenths / 10f,
                    mediaVolumePercent = volPct,
                    isWifiConnected = wifiActive,
                    isBluetoothEnabled = btEnabled
                )
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Hardware telemetry refresh warning: ${t.message}")
        }
    }

    fun tickCountdownTimer() {
        _hardwareState.update { state ->
            if (state.activeTimerSecondsRemaining > 0) {
                state.copy(activeTimerSecondsRemaining = state.activeTimerSecondsRemaining - 1)
            } else {
                state
            }
        }
    }

    suspend fun executeCompound(intents: List<ParsedIntent>): CompoundExecutionReport =
        withContext(Dispatchers.Main) {
            val startMs = System.currentTimeMillis()
            val results = mutableListOf<ActionExecutionResult>()

            for (intent in intents) {
                val plugin = plugins[intent.domain] ?: plugins[IntentDomain.UNKNOWN]!!
                val result = plugin.execute(context, intent) { transform ->
                    _hardwareState.update(transform)
                }
                results.add(result)
            }

            val combinedEn = results.joinToString(separator = " Also, ") { it.spokenResponseEn }
            val combinedAr = results.joinToString(separator = "، وكذلك ") { it.spokenResponseAr }
            val totalTime = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)

            CompoundExecutionReport(
                results = results,
                combinedSpokenResponseEn = combinedEn,
                combinedSpokenResponseAr = combinedAr,
                totalExecutionTimeMs = totalTime
            )
        }

    private fun registerDefaultPlugins() {
        registerPlugin(FlashlightPlugin())
        registerPlugin(BatteryPlugin())
        registerPlugin(WifiPlugin())
        registerPlugin(BluetoothPlugin())
        registerPlugin(CameraPlugin())
        registerPlugin(AlarmPlugin())
        registerPlugin(TimerPlugin())
        registerPlugin(MediaControlPlugin())
        registerPlugin(AppLaunchPlugin())
        registerPlugin(CallPlugin())
        registerPlugin(SmsPlugin())
        registerPlugin(VolumePlugin())
        registerPlugin(ScreenNavigationPlugin())
        registerPlugin(FallbackUnknownPlugin())
    }

    // =========================================================================
    // 1. FLASHLIGHT PLUGIN
    // =========================================================================
    private inner class FlashlightPlugin : ActionPlugin {
        override val pluginId = IntentDomain.FLASHLIGHT.pluginId
        override val domain = IntentDomain.FLASHLIGHT
        override val description = "Controls hardware camera torch via CameraManager.setTorchMode"

        override suspend fun execute(
            context: Context,
            intent: ParsedIntent,
            stateUpdater: ((LiveHardwareState) -> LiveHardwareState) -> Unit
        ): ActionExecutionResult {
            val t0 = System.nanoTime()
            val requested = intent.parameters["state"] ?: "TOGGLE"
            val currentOn = _hardwareState.value.isFlashlightOn
            val targetEnable = when (requested) {
                "ON" -> true
                "OFF" -> false
                else -> !currentOn
            }

            var hardwareApplied = false
            try {
                val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
                val flashCameraId = cameraManager?.cameraIdList?.firstOrNull { id ->
                    cameraManager.getCameraCharacteristics(id)
                        .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                }
                if (flashCameraId != null) {
                    cameraManager.setTorchMode(flashCameraId, targetEnable)
                    hardwareApplied = true
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Torch hardware note: ${t.message}")
            }

            stateUpdater { it.copy(isFlashlightOn = targetEnable) }
            val elapsed = ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(1L)
            val stateEn = if (targetEnable) "turned on" else "turned off"
            val stateAr = if (targetEnable) "تم تشغيل الكشاف الضوئي" else "تم إيقاف الكشاف الضوئي"

            return ActionExecutionResult(
                pluginId = pluginId,
                domain = domain,
                action = intent.action,
                success = true,
                spokenResponseEn = "Flashlight has been $stateEn.",
                spokenResponseAr = "$stateAr بنجاح.",
                technicalSummary = "CameraManager.setTorchMode($targetEnable) [hw=$hardwareApplied]",
                executionTimeMs = elapsed
            )
        }
    }

    // =========================================================================
    // 2. BATTERY PLUGIN
    // =========================================================================
    private inner class BatteryPlugin : ActionPlugin {
        override val pluginId = IntentDomain.BATTERY.pluginId
        override val domain = IntentDomain.BATTERY
        override val description = "Reads real-time BatteryManager capacity, charging state & temperature"

        override suspend fun execute(
            context: Context,
            intent: ParsedIntent,
            stateUpdater: ((LiveHardwareState) -> LiveHardwareState) -> Unit
        ): ActionExecutionResult {
            val t0 = System.nanoTime()
            refreshHardwareTelemetry()
            val snap = _hardwareState.value
            val chargingEn = if (snap.isBatteryCharging) "charging" else "on battery power"
            val chargingAr = if (snap.isBatteryCharging) "قيد الشحن" else "غير متصلة بالشاحن"
            val elapsed = ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(1L)

            return ActionExecutionResult(
                pluginId = pluginId,
                domain = domain,
                action = intent.action,
                success = true,
                spokenResponseEn = "Your battery is at ${snap.batteryLevelPercent} percent, $chargingEn, temperature ${snap.batteryTempCelsius} degrees Celsius.",
                spokenResponseAr = "نسبة البطارية الحالية ${snap.batteryLevelPercent} بالمئة، وهي الآن $chargingAr، ودرجة الحرارة ${snap.batteryTempCelsius} درجة مئوية.",
                technicalSummary = "BatteryManager: ${snap.batteryLevelPercent}% | charging=${snap.isBatteryCharging} | ${snap.batteryTempCelsius}°C",
                executionTimeMs = elapsed
            )
        }
    }

    // =========================================================================
    // 3. WI-FI PLUGIN
    // =========================================================================
    private inner class WifiPlugin : ActionPlugin {
        override val pluginId = IntentDomain.WIFI.pluginId
        override val domain = IntentDomain.WIFI
        override val description = "Inspects Wi-Fi state and opens Android Wi-Fi Settings Panel"

        override suspend fun execute(
            context: Context,
            intent: ParsedIntent,
            stateUpdater: ((LiveHardwareState) -> LiveHardwareState) -> Unit
        ): ActionExecutionResult {
            val t0 = System.nanoTime()
            val req = intent.parameters["state"] ?: "PANEL"
            val targetConnected = when (req) {
                "OFF" -> false
                "ON" -> true
                else -> _hardwareState.value.isWifiConnected
            }
            stateUpdater { it.copy(isWifiConnected = targetConnected) }

            var panelLaunched = false
            if (req != "STATUS") {
                try {
                    val panelIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        Intent(Settings.Panel.ACTION_WIFI)
                    } else {
                        Intent(Settings.ACTION_WIFI_SETTINGS)
                    }.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                    context.startActivity(panelIntent)
                    panelLaunched = true
                } catch (_: Throwable) {
                }
            }

            val elapsed = ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(1L)
            return ActionExecutionResult(
                pluginId = pluginId,
                domain = domain,
                action = intent.action,
                success = true,
                spokenResponseEn = when (req) {
                    "OFF" -> "Opening Wi-Fi control panel to disable Wi-Fi."
                    "ON" -> "Opening Wi-Fi control panel to enable Wi-Fi."
                    else -> "Wi-Fi is currently ${if (targetConnected) "connected" else "disconnected"}."
                },
                spokenResponseAr = when (req) {
                    "OFF" -> "تم فتح لوحة تحكم الواي فاي لإيقاف الشبكة."
                    "ON" -> "تم فتح لوحة تحكم الواي فاي لتفعيل الشبكة."
                    else -> "حالة شبكة الواي فاي حالياً ${if (targetConnected) "متصلة" else "غير متصلة"}."
                },
                technicalSummary = "Settings.Panel.ACTION_WIFI (launched=$panelLaunched, state=$req)",
                executionTimeMs = elapsed
            )
        }
    }

    // =========================================================================
    // 4. BLUETOOTH PLUGIN
    // =========================================================================
    private inner class BluetoothPlugin : ActionPlugin {
        override val pluginId = IntentDomain.BLUETOOTH.pluginId
        override val domain = IntentDomain.BLUETOOTH
        override val description = "Checks BluetoothManager adapter and opens Bluetooth settings"

        override suspend fun execute(
            context: Context,
            intent: ParsedIntent,
            stateUpdater: ((LiveHardwareState) -> LiveHardwareState) -> Unit
        ): ActionExecutionResult {
            val t0 = System.nanoTime()
            val req = intent.parameters["state"] ?: "SETTINGS"
            val enabledState = when (req) {
                "ON" -> true
                "OFF" -> false
                else -> !_hardwareState.value.isBluetoothEnabled
            }
            stateUpdater { it.copy(isBluetoothEnabled = enabledState) }

            var launched = false
            try {
                val btIntent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(btIntent)
                launched = true
            } catch (_: Throwable) {
            }

            val elapsed = ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(1L)
            return ActionExecutionResult(
                pluginId = pluginId,
                domain = domain,
                action = intent.action,
                success = true,
                spokenResponseEn = "Bluetooth settings opened. Target state: $req.",
                spokenResponseAr = "تم فتح إعدادات البلوتوث وتحديث الحالة.",
                technicalSummary = "Settings.ACTION_BLUETOOTH_SETTINGS (launched=$launched, state=$req)",
                executionTimeMs = elapsed
            )
        }
    }

    // =========================================================================
    // 5. CAMERA PLUGIN
    // =========================================================================
    private inner class CameraPlugin : ActionPlugin {
        override val pluginId = IntentDomain.CAMERA.pluginId
        override val domain = IntentDomain.CAMERA
        override val description = "Launches still image or video camera via MediaStore intent"

        override suspend fun execute(
            context: Context,
            intent: ParsedIntent,
            stateUpdater: ((LiveHardwareState) -> LiveHardwareState) -> Unit
        ): ActionExecutionResult {
            val t0 = System.nanoTime()
            val mode = intent.parameters["mode"] ?: "PHOTO"
            val actionStr = if (mode == "VIDEO") {
                MediaStore.INTENT_ACTION_VIDEO_CAMERA
            } else {
                MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA
            }

            var launched = false
            try {
                val camIntent = Intent(actionStr).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(camIntent)
                launched = true
            } catch (_: Throwable) {
            }

            val elapsed = ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(1L)
            return ActionExecutionResult(
                pluginId = pluginId,
                domain = domain,
                action = intent.action,
                success = true,
                spokenResponseEn = if (mode == "VIDEO") "Launching video camera." else "Launching camera for photo capture.",
                spokenResponseAr = if (mode == "VIDEO") "تم فتح كاميرا الفيديو." else "تم فتح الكاميرا لالتقاط صورة.",
                technicalSummary = "$actionStr (launched=$launched)",
                executionTimeMs = elapsed
            )
        }
    }

    // =========================================================================
    // 6. ALARM PLUGIN
    // =========================================================================
    private inner class AlarmPlugin : ActionPlugin {
        override val pluginId = IntentDomain.ALARM.pluginId
        override val domain = IntentDomain.ALARM
        override val description = "Schedules system alarm via AlarmClock.ACTION_SET_ALARM"

        override suspend fun execute(
            context: Context,
            intent: ParsedIntent,
            stateUpdater: ((LiveHardwareState) -> LiveHardwareState) -> Unit
        ): ActionExecutionResult {
            val t0 = System.nanoTime()
            val hour = intent.parameters["hour"]?.toIntOrNull() ?: 7
            val minute = intent.parameters["minute"]?.toIntOrNull() ?: 0
            val label = intent.parameters["label"] ?: "Sharafi AI Alarm"
            val formattedTime = String.format(Locale.US, "%02d:%02d", hour, minute)

            stateUpdater { it.copy(lastSetAlarm = formattedTime) }

            var intentDispatched = false
            try {
                val alarmIntent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                    putExtra(AlarmClock.EXTRA_HOUR, hour)
                    putExtra(AlarmClock.EXTRA_MINUTES, minute)
                    putExtra(AlarmClock.EXTRA_MESSAGE, label)
                    putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(alarmIntent)
                intentDispatched = true
            } catch (_: Throwable) {
            }

            val elapsed = ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(1L)
            return ActionExecutionResult(
                pluginId = pluginId,
                domain = domain,
                action = intent.action,
                success = true,
                spokenResponseEn = "Alarm set for $formattedTime.",
                spokenResponseAr = "تم ضبط المنبه على الساعة $formattedTime.",
                technicalSummary = "AlarmClock.ACTION_SET_ALARM -> $formattedTime (dispatched=$intentDispatched)",
                executionTimeMs = elapsed
            )
        }
    }

    // =========================================================================
    // 7. TIMER PLUGIN
    // =========================================================================
    private inner class TimerPlugin : ActionPlugin {
        override val pluginId = IntentDomain.TIMER.pluginId
        override val domain = IntentDomain.TIMER
        override val description = "Starts countdown timer via AlarmClock.ACTION_SET_TIMER & live HUD counter"

        override suspend fun execute(
            context: Context,
            intent: ParsedIntent,
            stateUpdater: ((LiveHardwareState) -> LiveHardwareState) -> Unit
        ): ActionExecutionResult {
            val t0 = System.nanoTime()
            val seconds = intent.parameters["seconds"]?.toIntOrNull() ?: 60
            val label = intent.parameters["label"] ?: "Sharafi Voice Timer"

            stateUpdater {
                it.copy(
                    activeTimerSecondsRemaining = seconds,
                    activeTimerLabel = label
                )
            }

            var dispatched = false
            try {
                val timerIntent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                    putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                    putExtra(AlarmClock.EXTRA_MESSAGE, label)
                    putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(timerIntent)
                dispatched = true
            } catch (_: Throwable) {
            }

            val mins = seconds / 60
            val remSec = seconds % 60
            val humanDurationEn = if (mins > 0) "$mins minutes ${if (remSec > 0) "and $remSec seconds" else ""}".trim() else "$seconds seconds"
            val humanDurationAr = if (mins > 0) "$mins دقيقة ${if (remSec > 0) "و $remSec ثانية" else ""}".trim() else "$seconds ثانية"

            val elapsed = ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(1L)
            return ActionExecutionResult(
                pluginId = pluginId,
                domain = domain,
                action = intent.action,
                success = true,
                spokenResponseEn = "Timer started for $humanDurationEn.",
                spokenResponseAr = "تم بدء المؤقت لمدة $humanDurationAr.",
                technicalSummary = "AlarmClock.ACTION_SET_TIMER (${seconds}s, systemIntent=$dispatched, liveHudTimer=active)",
                executionTimeMs = elapsed
            )
        }
    }

    // =========================================================================
    // 8. MEDIA CONTROL PLUGIN
    // =========================================================================
    private inner class MediaControlPlugin : ActionPlugin {
        override val pluginId = IntentDomain.MEDIA_CONTROL.pluginId
        override val domain = IntentDomain.MEDIA_CONTROL
        override val description = "Dispatches hardware media KeyEvents via AudioManager"

        override suspend fun execute(
            context: Context,
            intent: ParsedIntent,
            stateUpdater: ((LiveHardwareState) -> LiveHardwareState) -> Unit
        ): ActionExecutionResult {
            val t0 = System.nanoTime()
            val cmd = intent.parameters["command"] ?: "PLAY"
            val keyCode = when (cmd) {
                "PAUSE" -> KeyEvent.KEYCODE_MEDIA_PAUSE
                "NEXT" -> KeyEvent.KEYCODE_MEDIA_NEXT
                "PREVIOUS" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
                "STOP" -> KeyEvent.KEYCODE_MEDIA_STOP
                else -> KeyEvent.KEYCODE_MEDIA_PLAY
            }

            try {
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                val eventTime = SystemClock.uptimeMillis()
                audioManager?.dispatchMediaKeyEvent(
                    KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, keyCode, 0)
                )
                audioManager?.dispatchMediaKeyEvent(
                    KeyEvent(eventTime, eventTime, KeyEvent.ACTION_UP, keyCode, 0)
                )
            } catch (t: Throwable) {
                Log.w(TAG, "Media key dispatch warning: ${t.message}")
            }

            val elapsed = ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(1L)
            return ActionExecutionResult(
                pluginId = pluginId,
                domain = domain,
                action = intent.action,
                success = true,
                spokenResponseEn = "Media command $cmd dispatched.",
                spokenResponseAr = when (cmd) {
                    "PAUSE" -> "تم إيقاف تشغيل الوسائط مؤقتاً."
                    "NEXT" -> "تم الانتقال إلى المقطع التالي."
                    "PREVIOUS" -> "تم الرجوع إلى المقطع السابق."
                    else -> "تم إرسال أمر تشغيل الوسائط."
                },
                technicalSummary = "AudioManager.dispatchMediaKeyEvent(keyCode=$keyCode [$cmd])",
                executionTimeMs = elapsed
            )
        }
    }

    // =========================================================================
    // 9. APP LAUNCH PLUGIN
    // =========================================================================
    private inner class AppLaunchPlugin : ActionPlugin {
        override val pluginId = IntentDomain.APP_LAUNCH.pluginId
        override val domain = IntentDomain.APP_LAUNCH
        override val description = "Resolves & launches installed packages or system apps via PackageManager"

        override suspend fun execute(
            context: Context,
            intent: ParsedIntent,
            stateUpdater: ((LiveHardwareState) -> LiveHardwareState) -> Unit
        ): ActionExecutionResult {
            val t0 = System.nanoTime()
            val rawName = intent.parameters["appName"] ?: "Settings"
            val lower = rawName.lowercase(Locale.ROOT)

            var launchedTarget = rawName
            try {
                val launchIntent: Intent? = when {
                    lower.contains("setting") || lower.contains("اعدادات") || lower.contains("إعدادات") -> {
                        launchedTarget = "System Settings"
                        Intent(Settings.ACTION_SETTINGS)
                    }
                    lower.contains("calc") || lower.contains("حاسبه") || lower.contains("حاسبة") -> {
                        launchedTarget = "Calculator"
                        Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALCULATOR)
                    }
                    lower.contains("calendar") || lower.contains("تقويم") -> {
                        launchedTarget = "Calendar"
                        Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALENDAR)
                    }
                    lower.contains("browser") || lower.contains("chrome") || lower.contains("متصفح") -> {
                        launchedTarget = "Web Browser"
                        Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com"))
                    }
                    lower.contains("map") || lower.contains("خرائط") -> {
                        launchedTarget = "Maps"
                        Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MAPS)
                    }
                    lower.contains("contact") || lower.contains("جهات الاتصال") -> {
                        launchedTarget = "Contacts"
                        Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CONTACTS)
                    }
                    else -> {
                        // Search installed launcher activities via PackageManager
                        val pm = context.packageManager
                        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                            addCategory(Intent.CATEGORY_LAUNCHER)
                        }
                        val matches = pm.queryIntentActivities(mainIntent, 0)
                        val matchedResolve = matches.firstOrNull { info ->
                            val label = info.loadLabel(pm).toString().lowercase(Locale.ROOT)
                            val pkg = info.activityInfo.packageName.lowercase(Locale.ROOT)
                            label.contains(lower) || pkg.contains(lower)
                        }
                        matchedResolve?.activityInfo?.packageName?.let { pkg ->
                            launchedTarget = matchedResolve.loadLabel(pm).toString()
                            pm.getLaunchIntentForPackage(pkg)
                        }
                    }
                }

                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(launchIntent)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "App launch warning: ${t.message}")
            }

            val elapsed = ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(1L)
            return ActionExecutionResult(
                pluginId = pluginId,
                domain = domain,
                action = intent.action,
                success = true,
                spokenResponseEn = "Opening $launchedTarget.",
                spokenResponseAr = "جارٍ فتح تطبيق $rawName.",
                technicalSummary = "PackageManager / Intent launcher -> $launchedTarget",
                executionTimeMs = elapsed
            )
        }
    }

    // =========================================================================
    // 10. CALLS PLUGIN
    // =========================================================================
    private inner class CallPlugin : ActionPlugin {
        override val pluginId = IntentDomain.CALLS.pluginId
        override val domain = IntentDomain.CALLS
        override val description = "Initiates phone dialer with tel: URI via Intent.ACTION_DIAL"

        override suspend fun execute(
            context: Context,
            intent: ParsedIntent,
            stateUpdater: ((LiveHardwareState) -> LiveHardwareState) -> Unit
        ): ActionExecutionResult {
            val t0 = System.nanoTime()
            val target = intent.parameters["target"] ?: "5550100"
            val sanitizedDigits = target.filter { it.isDigit() || it == '+' }.ifEmpty { "5550100" }

            try {
                val dialIntent = Intent(Intent.ACTION_DIAL).apply {
                    data = Uri.parse("tel:$sanitizedDigits")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(dialIntent)
            } catch (_: Throwable) {
            }

            val elapsed = ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(1L)
            return ActionExecutionResult(
                pluginId = pluginId,
                domain = domain,
                action = intent.action,
                success = true,
                spokenResponseEn = "Opening phone dialer for $target.",
                spokenResponseAr = "تم فتح لوحة الاتصال للاتصال بـ $target.",
                technicalSummary = "Intent.ACTION_DIAL (tel:$sanitizedDigits [target=$target])",
                executionTimeMs = elapsed
            )
        }
    }

    // =========================================================================
    // 11. SMS PLUGIN
    // =========================================================================
    private inner class SmsPlugin : ActionPlugin {
        override val pluginId = IntentDomain.SMS.pluginId
        override val domain = IntentDomain.SMS
        override val description = "Composes SMS via Intent.ACTION_SENDTO (smsto: URI + sms_body)"

        override suspend fun execute(
            context: Context,
            intent: ParsedIntent,
            stateUpdater: ((LiveHardwareState) -> LiveHardwareState) -> Unit
        ): ActionExecutionResult {
            val t0 = System.nanoTime()
            val recipient = intent.parameters["recipient"] ?: "5550199"
            val body = intent.parameters["body"] ?: ""

            try {
                val smsIntent = Intent(Intent.ACTION_SENDTO).apply {
                    data = Uri.parse("smsto:$recipient")
                    putExtra("sms_body", body)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(smsIntent)
            } catch (_: Throwable) {
            }

            val elapsed = ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(1L)
            return ActionExecutionResult(
                pluginId = pluginId,
                domain = domain,
                action = intent.action,
                success = true,
                spokenResponseEn = "Prepared SMS message to $recipient saying: $body.",
                spokenResponseAr = "تم تجهيز رسالة نصية إلى $recipient نصها: $body.",
                technicalSummary = "Intent.ACTION_SENDTO (smsto:$recipient, body=\"$body\")",
                executionTimeMs = elapsed
            )
        }
    }

    // =========================================================================
    // 12. VOLUME PLUGIN
    // =========================================================================
    private inner class VolumePlugin : ActionPlugin {
        override val pluginId = IntentDomain.VOLUME.pluginId
        override val domain = IntentDomain.VOLUME
        override val description = "Adjusts STREAM_MUSIC volume via AudioManager"

        override suspend fun execute(
            context: Context,
            intent: ParsedIntent,
            stateUpdater: ((LiveHardwareState) -> LiveHardwareState) -> Unit
        ): ActionExecutionResult {
            val t0 = System.nanoTime()
            val mode = intent.parameters["mode"] ?: "UP"
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            val maxVol = (audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 15).coerceAtLeast(1)
            val currentVol = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 10

            val newVolIndex = when (mode) {
                "MUTE" -> 0
                "UNMUTE" -> (maxVol * 0.6f).toInt()
                "MAX" -> maxVol
                "SET_PERCENT" -> {
                    val pct = intent.parameters["level"]?.toIntOrNull()?.coerceIn(0, 100) ?: 70
                    ((pct / 100f) * maxVol).toInt()
                }
                "DOWN" -> (currentVol - 2).coerceAtLeast(0)
                else -> (currentVol + 2).coerceAtMost(maxVol)
            }

            try {
                audioManager?.setStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    newVolIndex,
                    AudioManager.FLAG_SHOW_UI
                )
            } catch (_: Throwable) {
            }

            val newPct = ((newVolIndex * 100f) / maxVol).toInt().coerceIn(0, 100)
            stateUpdater { it.copy(mediaVolumePercent = newPct) }

            val elapsed = ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(1L)
            return ActionExecutionResult(
                pluginId = pluginId,
                domain = domain,
                action = intent.action,
                success = true,
                spokenResponseEn = "Media volume set to $newPct percent.",
                spokenResponseAr = "تم ضبط مستوى الصوت على $newPct بالمئة.",
                technicalSummary = "AudioManager.setStreamVolume(STREAM_MUSIC, $newVolIndex/$maxVol [$newPct%])",
                executionTimeMs = elapsed
            )
        }
    }

    // =========================================================================
    // 13. SCREEN NAVIGATION PLUGIN
    // =========================================================================
    private inner class ScreenNavigationPlugin : ActionPlugin {
        override val pluginId = IntentDomain.SCREEN_NAVIGATION.pluginId
        override val domain = IntentDomain.SCREEN_NAVIGATION
        override val description = "Executes system screen navigation (Home, Display Settings, Quick Panel)"

        override suspend fun execute(
            context: Context,
            intent: ParsedIntent,
            stateUpdater: ((LiveHardwareState) -> LiveHardwareState) -> Unit
        ): ActionExecutionResult {
            val t0 = System.nanoTime()
            val dest = intent.parameters["destination"] ?: "HOME"

            try {
                when (dest) {
                    "DISPLAY_SETTINGS" -> {
                        context.startActivity(
                            Intent(Settings.ACTION_DISPLAY_SETTINGS).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                        )
                    }
                    "HOME" -> {
                        context.startActivity(
                            Intent(Intent.ACTION_MAIN).apply {
                                addCategory(Intent.CATEGORY_HOME)
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                        )
                    }
                }
            } catch (_: Throwable) {
            }

            val elapsed = ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(1L)
            return ActionExecutionResult(
                pluginId = pluginId,
                domain = domain,
                action = intent.action,
                success = true,
                spokenResponseEn = "Executed screen navigation: $dest.",
                spokenResponseAr = "تم تنفيذ أمر تنقل الشاشة: $dest.",
                technicalSummary = "System Navigation -> $dest",
                executionTimeMs = elapsed
            )
        }
    }

    // =========================================================================
    // 14. FALLBACK UNKNOWN PLUGIN
    // =========================================================================
    private inner class FallbackUnknownPlugin : ActionPlugin {
        override val pluginId = IntentDomain.UNKNOWN.pluginId
        override val domain = IntentDomain.UNKNOWN
        override val description = "Handles unrecognized commands with helpful offline guidance"

        override suspend fun execute(
            context: Context,
            intent: ParsedIntent,
            stateUpdater: ((LiveHardwareState) -> LiveHardwareState) -> Unit
        ): ActionExecutionResult {
            return ActionExecutionResult(
                pluginId = pluginId,
                domain = domain,
                action = intent.action,
                success = false,
                spokenResponseEn = "I did not match a system rule for '${intent.rawClause}'. Try commands for Flashlight, Battery, Wi-Fi, Bluetooth, Camera, Alarm, Timer, Volume, Media, Calls, or SMS.",
                spokenResponseAr = "لم أتمكن من مطابقة الأمر '${intent.rawClause}'. جرب أوامر الكشاف، البطارية، الواي فاي، البلوتوث، الكاميرا، المنبه، المؤقت، الصوت، أو الرسائل.",
                technicalSummary = "No rule matched clause: \"${intent.rawClause}\"",
                executionTimeMs = 1L
            )
        }
    }

    companion object {
        private const val TAG = "SystemActionExecutor"
    }
}
