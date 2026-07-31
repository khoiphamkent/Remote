package com.example.remiolike.client

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.TimeUnit

class LcdAgentService : Service() {
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("lcd_agent", Context.MODE_PRIVATE) }

    private var socket: WebSocket? = null
    private var socketOpen = false
    private var reconnectScheduled = false
    private var heartbeatThread: Thread? = null
    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private var captureActive = false
    private var rootCaptureThread: Thread? = null
    private var rootCaptureActive = false
    private var rootCaptureFailureCount = 0
    private var lastFrameAt = 0L
    private var screenWidth = 0
    private var screenHeight = 0
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            hasProjection = false
            stopScreenCapture(keepProjection = true)
            mediaProjection = null
        }
    }

    private val deviceCode: String by lazy {
        prefs.getString(KEY_DEVICE_CODE, null) ?: createAndStoreDeviceCode()
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("LCD Agent running"))
        connectionStatus = "Relay: connecting"
        connectAgent()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SET_PROJECTION -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val data = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(EXTRA_PROJECTION_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_PROJECTION_DATA)
                }
                if (resultCode != 0 && data != null) {
                    val accepted = runCatching {
                        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                        mediaProjection = manager.getMediaProjection(resultCode, data)
                        mediaProjection?.registerCallback(projectionCallback, mainHandler)
                    }.isSuccess
                    hasProjection = accepted && mediaProjection != null
                    sendCommandResult(
                        "screen-permission",
                        hasProjection,
                        if (hasProjection) "Screen capture permission accepted" else "Screen capture permission failed"
                    )
                    if (hasProjection && captureActive) {
                        mainHandler.post { startScreenCaptureSafely() }
                    }
                }
            }
            ACTION_STOP -> stopSelf()
            else -> connectAgent()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        hasProjection = false
        mainHandler.removeCallbacksAndMessages(null)
        stopScreenCapture()
        socket?.close(1000, "Service stopped")
        socketOpen = false
        connectionStatus = "Relay: stopped"
        heartbeatThread?.interrupt()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        mainHandler.postDelayed({
            startForegroundService(
                Intent(this, LcdAgentService::class.java)
                    .setAction(ACTION_START)
            )
        }, 1000)
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun connectAgent() {
        if (socketOpen && socket?.send("""{"type":"heartbeat"}""") == true) return
        reconnectScheduled = false
        connectionStatus = "Relay: connecting"

        val request = Request.Builder().url(toWebSocketUrl(DEFAULT_RELAY_URL)).build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                socketOpen = true
                connectionStatus = "Relay: connected"
                webSocket.send(buildAgentStatusMessage("register"))
                startHeartbeat(webSocket)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                socketOpen = false
                connectionStatus = "Relay: reconnecting"
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                socketOpen = false
                connectionStatus = "Relay: ${t.message ?: "connection failed"}"
                scheduleReconnect()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleServerMessage(text)
            }
        })
    }

    private fun scheduleReconnect() {
        if (reconnectScheduled) return
        reconnectScheduled = true
        mainHandler.postDelayed({
            socket = null
            connectAgent()
        }, 5000)
    }

    private fun startHeartbeat(webSocket: WebSocket) {
        heartbeatThread?.interrupt()
        heartbeatThread = Thread {
            while (!Thread.currentThread().isInterrupted) {
                try {
                    Thread.sleep(15000)
                    if (!webSocket.send(buildAgentStatusMessage("heartbeat"))) {
                        socketOpen = false
                        connectionStatus = "Relay: heartbeat failed"
                        scheduleReconnect()
                        return@Thread
                    }
                } catch (_: InterruptedException) {
                    return@Thread
                } catch (error: Exception) {
                    socketOpen = false
                    connectionStatus = "Relay: ${error.message ?: "heartbeat error"}"
                    scheduleReconnect()
                    return@Thread
                }
            }
        }.also { it.start() }
    }

    private fun handleServerMessage(text: String) {
        val message = runCatching { JSONObject(text) }.getOrNull() ?: return
        if (message.optString("type") != "agent-command") return

        val commandId = message.optString("commandId")
        val command = message.optJSONObject("command") ?: JSONObject()
        when (command.optString("type")) {
            "identify" -> sendCommandResult(commandId, true, "LCD Agent is running")
            "open-url" -> {
                val ok = openUrl(command.optString("url"))
                sendCommandResult(commandId, ok, if (ok) "URL opened" else "Invalid URL")
            }
            "open-accessibility" -> {
                val ok = openAccessibilitySettings()
                sendCommandResult(commandId, ok, if (ok) "Accessibility settings opened on LCD" else "Could not open Accessibility settings")
            }
            "enable-control" -> {
                val ok = isAccessibilityEnabled() || enableAccessibilityWithRoot()
                if (!ok) openAccessibilitySettings()
                sendCommandResult(commandId, ok, if (ok) "Accessibility service is enabled" else "Open Accessibility on LCD and enable LCD Agent")
            }
            "accessibility-status" -> {
                val ok = isAccessibilityEnabled()
                sendCommandResult(commandId, ok, if (ok) "Accessibility service is enabled" else "Accessibility service is not enabled")
            }
            "start-screen" -> {
                captureActive = true
                val rootStarted = startRootCaptureSafely()
                if (rootStarted) {
                    sendCommandResult(commandId, true, "Root screen stream started")
                } else if (mediaProjection == null) {
                    openScreenCapturePermission()
                    sendCommandResult(commandId, false, "Screen share permission needed. Accept it on the LCD device.")
                } else {
                    val ok = startScreenCaptureSafely()
                    sendCommandResult(commandId, ok, if (ok) "Screen stream started" else "Screen stream failed")
                }
            }
            "stop-screen" -> {
                captureActive = false
                stopScreenCapture(keepProjection = true)
                sendCommandResult(commandId, true, "Screen stream stopped")
            }
            "start-webrtc" -> {
                captureActive = true
                val rootStarted = startRootCaptureSafely()
                if (rootStarted) {
                    sendCommandResult(commandId, true, "Root screen stream started")
                } else if (mediaProjection == null) {
                    openScreenCapturePermission()
                    sendCommandResult(commandId, false, "Screen share permission needed. Accept it on the LCD device.")
                } else {
                    val ok = startScreenCaptureSafely()
                    sendCommandResult(commandId, ok, if (ok) "Screen stream started" else "Screen stream failed")
                }
            }
            "stop-webrtc" -> {
                captureActive = false
                stopScreenCapture(keepProjection = true)
                sendCommandResult(commandId, true, "Screen stream stopped")
            }
            "tap" -> {
                val ok = performTap(command.optDouble("x", -1.0), command.optDouble("y", -1.0))
                sendCommandResult(commandId, ok, if (ok) "Tap sent" else "Accessibility service is not enabled")
            }
            "swipe" -> {
                val ok = performSwipe(
                    command.optDouble("startX", -1.0),
                    command.optDouble("startY", -1.0),
                    command.optDouble("endX", -1.0),
                    command.optDouble("endY", -1.0)
                )
                sendCommandResult(commandId, ok, if (ok) "Swipe sent" else "Accessibility service is not enabled")
            }
            "back" -> {
                val ok = LcdAccessibilityService.back()
                sendCommandResult(commandId, ok, if (ok) "Back sent" else "Accessibility service is not enabled")
            }
            "home" -> {
                val ok = LcdAccessibilityService.home()
                sendCommandResult(commandId, ok, if (ok) "Home sent" else "Accessibility service is not enabled")
            }
            "recents" -> {
                val ok = LcdAccessibilityService.recents()
                sendCommandResult(commandId, ok, if (ok) "Recents sent" else "Accessibility service is not enabled")
            }
            else -> sendCommandResult(commandId, false, "Unknown command")
        }
    }

    private fun startScreenCaptureSafely(): Boolean {
        return runCatching {
            startScreenCapture()
            virtualDisplay != null
        }.getOrDefault(false)
    }

    private fun startRootCaptureSafely(): Boolean {
        if (rootCaptureThread?.isAlive == true) return true
        if (!hasRootScreencap()) {
            rootCaptureAvailable = false
            captureMode = if (mediaProjection != null) "MediaProjection" else "None"
            return false
        }

        rootCaptureAvailable = true
        captureMode = "Root"
        rootCaptureFailureCount = 0
        rootCaptureActive = true
        rootCaptureThread = Thread {
            while (rootCaptureActive && !Thread.currentThread().isInterrupted) {
                val startedAt = System.currentTimeMillis()
                val sent = runCatching {
                    val png = captureRootScreenshotBytes()
                    val bitmap = BitmapFactory.decodeByteArray(png, 0, png.size) ?: return@runCatching false
                    screenWidth = bitmap.width
                    screenHeight = bitmap.height
                    val frame = encodeBitmap(bitmap)
                    bitmap.recycle()
                    if (frame != null) {
                        sendScreenFrame(frame)
                        true
                    } else {
                        false
                    }
                }.getOrDefault(false)

                if (!sent) {
                    rootCaptureFailureCount++
                    if (!hasRootScreencap()) {
                        rootCaptureAvailable = false
                        captureMode = if (mediaProjection != null) "MediaProjection" else "None"
                        rootCaptureActive = false
                        return@Thread
                    }

                    if (rootCaptureFailureCount >= 10) {
                        rootCaptureFailureCount = 0
                    }

                    try {
                        Thread.sleep(500L)
                    } catch (_: InterruptedException) {
                        return@Thread
                    }
                    continue
                }

                rootCaptureFailureCount = 0
                val elapsed = System.currentTimeMillis() - startedAt
                val sleepMs = (ROOT_FRAME_INTERVAL_MS - elapsed).coerceAtLeast(20L)
                try {
                    Thread.sleep(sleepMs)
                } catch (_: InterruptedException) {
                    return@Thread
                }
            }
        }.also { it.start() }
        return true
    }

    private fun hasRootScreencap(): Boolean {
        return runCatching {
            val output = runRootCommandBytes("id", ROOT_COMMAND_TIMEOUT_MS)
            output.decodeToString().contains("uid=0")
        }.getOrDefault(false)
    }

    private fun startScreenCapture() {
        val projection = mediaProjection ?: return
        if (virtualDisplay != null) return
        captureMode = "MediaProjection"

        val metrics = resources.displayMetrics
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels

        captureThread = HandlerThread("lcd-screen-capture").also { it.start() }
        captureHandler = Handler(captureThread!!.looper)
        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
        virtualDisplay = projection.createVirtualDisplay(
            "lcd-agent-screen",
            screenWidth,
            screenHeight,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface,
            null,
            captureHandler
        )

        imageReader?.setOnImageAvailableListener({ reader ->
            if (!captureActive) {
                reader.acquireLatestImage()?.close()
                return@setOnImageAvailableListener
            }

            val now = System.currentTimeMillis()
            if (now - lastFrameAt < FRAME_INTERVAL_MS) {
                reader.acquireLatestImage()?.close()
                return@setOnImageAvailableListener
            }

            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val frame = encodeImage(image)
                if (frame != null) {
                    lastFrameAt = now
                    sendScreenFrame(frame)
                }
            } finally {
                image.close()
            }
        }, captureHandler)
    }

    private fun stopScreenCapture(keepProjection: Boolean = false) {
        rootCaptureActive = false
        rootCaptureThread?.interrupt()
        rootCaptureThread = null

        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        captureThread?.quitSafely()
        captureThread = null
        captureHandler = null

        if (!keepProjection) {
            mediaProjection?.stop()
            mediaProjection = null
            hasProjection = false
        }

        if (!rootCaptureActive && virtualDisplay == null) {
            captureMode = if (mediaProjection != null) "MediaProjection" else "None"
        }
    }

    private fun encodeImage(image: Image): String? {
        val plane = image.planes.firstOrNull() ?: return null
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width
        val paddedWidth = image.width + rowPadding / pixelStride

        val bitmap = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(buffer)
        val cropped = Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
        bitmap.recycle()

        return encodeBitmap(cropped).also {
            cropped.recycle()
        }
    }

    private fun encodeBitmap(bitmap: Bitmap): String? {
        val targetWidth = ROOT_TARGET_WIDTH.coerceAtMost(bitmap.width).coerceAtLeast(1)
        val targetHeight = (targetWidth.toFloat() / bitmap.width * bitmap.height).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
        val output = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, ROOT_JPEG_QUALITY, output)
        scaled.recycle()
        return Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
    }

    private fun sendScreenFrame(frame: String) {
        socket?.send(
            JSONObject()
                .put("type", "screen-frame")
                .put("deviceCode", deviceCode)
                .put("width", screenWidth)
                .put("height", screenHeight)
                .put("frame", frame)
                .put("ts", System.currentTimeMillis())
                .toString()
        )
    }

    private fun performTap(x: Double, y: Double): Boolean {
        if (x !in 0.0..1.0 || y !in 0.0..1.0) return false
        val width = if (screenWidth > 0) screenWidth else resources.displayMetrics.widthPixels
        val height = if (screenHeight > 0) screenHeight else resources.displayMetrics.heightPixels
        return LcdAccessibilityService.tap((x * width).toFloat(), (y * height).toFloat())
    }

    private fun performSwipe(startX: Double, startY: Double, endX: Double, endY: Double): Boolean {
        if (
            startX !in 0.0..1.0 ||
            startY !in 0.0..1.0 ||
            endX !in 0.0..1.0 ||
            endY !in 0.0..1.0
        ) return false

        val width = if (screenWidth > 0) screenWidth else resources.displayMetrics.widthPixels
        val height = if (screenHeight > 0) screenHeight else resources.displayMetrics.heightPixels
        return LcdAccessibilityService.swipe(
            (startX * width).toFloat(),
            (startY * height).toFloat(),
            (endX * width).toFloat(),
            (endY * height).toFloat()
        )
    }

    private fun openUrl(value: String): Boolean {
        if (!value.startsWith("http://") && !value.startsWith("https://")) return false
        return runCatching {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(value))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        }.isSuccess
    }

    private fun openAccessibilitySettings(): Boolean {
        return runCatching {
            startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.isSuccess
    }

    private fun openScreenCapturePermission(): Boolean {
        return runCatching {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .setAction(MainActivity.ACTION_REQUEST_SCREEN_CAPTURE)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        }.isSuccess
    }

    private fun enableAccessibilityWithRoot(): Boolean {
        val services = desiredAccessibilityServices()
        val enabledWithSystemPermission = runCatching {
            Settings.Secure.putString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                services
            )
            Settings.Secure.putInt(contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            isAccessibilityEnabled()
        }.getOrDefault(false)
        if (enabledWithSystemPermission) return true

        return runCatching {
            val escapedServices = services.replace("'", "'\\''")
            runRootCommandBytes(
                "settings put secure enabled_accessibility_services '$escapedServices'; settings put secure accessibility_enabled 1",
                ROOT_COMMAND_TIMEOUT_MS
            )
            isAccessibilityEnabled()
        }.getOrDefault(false)
    }

    private fun desiredAccessibilityServices(): String {
        val service = ComponentName(this, LcdAccessibilityService::class.java).flattenToString()
        val current = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        val entries = current.split(":").filter { it.isNotBlank() }.toMutableList()
        if (entries.none { it.equals(service, ignoreCase = true) }) {
            entries.add(service)
        }
        return entries.joinToString(":")
    }

    private fun isAccessibilityEnabled(): Boolean {
        val expected = ComponentName(this, LcdAccessibilityService::class.java).flattenToString()
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        return enabledServices.split(":").any { it.equals(expected, ignoreCase = true) }
    }

    private fun buildAgentStatusMessage(type: String): String {
        return JSONObject()
            .put("type", type)
            .put("role", "agent")
            .put("sessionId", deviceCode)
            .put("deviceCode", deviceCode)
            .put("accessibilityEnabled", isAccessibilityEnabled())
            .put("rootCaptureAvailable", rootCaptureAvailable)
            .put("captureMode", captureMode)
            .toString()
    }

    private fun sendCommandResult(commandId: String, ok: Boolean, message: String) {
        socket?.send(
            JSONObject()
                .put("type", "command-result")
                .put("commandId", commandId)
                .put("deviceCode", deviceCode)
                .put("ok", ok)
                .put("message", message)
                .put("accessibilityEnabled", isAccessibilityEnabled())
                .put("rootCaptureAvailable", rootCaptureAvailable)
                .put("captureMode", captureMode)
                .toString()
        )
    }

    private fun runRootCommandBytes(command: String, timeoutMs: Long): ByteArray {
        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
        val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroyForcibly()
            throw IllegalStateException("Root command timed out")
        }
        val output = process.inputStream.readBytes()
        if (process.exitValue() != 0) {
            val error = process.errorStream.readBytes().decodeToString()
            throw IllegalStateException(error.ifBlank { "Root command failed" })
        }
        return output
    }

    private fun captureRootScreenshotBytes(): ByteArray {
        return runRootCommandBytes("screencap -p", ROOT_COMMAND_TIMEOUT_MS)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "LCD Agent",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setContentTitle("LCD Agent")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.presence_online)
            .setOngoing(true)
            .build()
    }

    private fun createAndStoreDeviceCode(): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val random = SecureRandom()
        val suffix = (1..6).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
        val code = "LCD-$suffix"
        prefs.edit().putString(KEY_DEVICE_CODE, code).apply()
        return code
    }

    private fun toWebSocketUrl(httpUrl: String): String {
        val normalized = httpUrl.lowercase(Locale.US)
        return when {
            normalized.startsWith("https://") -> "wss://" + httpUrl.substringAfter("https://")
            normalized.startsWith("http://") -> "ws://" + httpUrl.substringAfter("http://")
            else -> "wss://$httpUrl"
        }
    }

    companion object {
        const val ACTION_START = "com.example.remiolike.client.START"
        const val ACTION_STOP = "com.example.remiolike.client.STOP"
        const val ACTION_SET_PROJECTION = "com.example.remiolike.client.SET_PROJECTION"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_PROJECTION_DATA = "projection_data"

        private const val DEFAULT_RELAY_URL = "https://remote-4617.onrender.com"
        private const val KEY_DEVICE_CODE = "device_code"
        private const val CHANNEL_ID = "lcd_agent"
        private const val NOTIFICATION_ID = 1001
        private const val FRAME_INTERVAL_MS = 180L
        private const val ROOT_FRAME_INTERVAL_MS = 220L
        private const val ROOT_COMMAND_TIMEOUT_MS = 5000L
        private const val ROOT_TARGET_WIDTH = 640
        private const val ROOT_JPEG_QUALITY = 48

        @Volatile var isRunning = false
        @Volatile var hasProjection = false
        @Volatile var connectionStatus = "Relay: stopped"
        @Volatile var rootCaptureAvailable = false
        @Volatile var captureMode = "None"
    }
}
