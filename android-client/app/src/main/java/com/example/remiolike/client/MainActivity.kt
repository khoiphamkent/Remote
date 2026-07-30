package com.example.remiolike.client

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.provider.Settings
import android.util.Base64
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
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

class MainActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("lcd_agent", Context.MODE_PRIVATE) }
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private lateinit var projectionManager: MediaProjectionManager
    private lateinit var relayInput: EditText
    private lateinit var statusText: TextView
    private lateinit var permissionStatusText: TextView
    private lateinit var connectButton: Button
    private lateinit var captureButton: Button
    private lateinit var setupButton: Button
    private lateinit var accessibilityButton: Button

    private var socket: WebSocket? = null
    private var heartbeatThread: Thread? = null
    private val reconnectHandler = Handler(Looper.getMainLooper())
    private var shouldReconnect = true
    private var reconnectScheduled = false

    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private var captureRequestedByPc = false
    private var lastFrameAt = 0L
    private var screenWidth = 0
    private var screenHeight = 0

    private val deviceCode: String by lazy {
        prefs.getString(KEY_DEVICE_CODE, null) ?: createAndStoreDeviceCode()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        buildUi()
        val needsCodeAcknowledgement = !prefs.getBoolean(KEY_CODE_ACKNOWLEDGED, false)
        showDeviceCodeOnce()
        relayInput.post {
            connectAgent()
            if (!needsCodeAcknowledgement) {
                startInitialPermissionSetup()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionStatus()
    }

    override fun onDestroy() {
        shouldReconnect = false
        reconnectHandler.removeCallbacksAndMessages(null)
        stopScreenCapture()
        socket?.close(1000, "Activity closed")
        heartbeatThread?.interrupt()
        super.onDestroy()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_MEDIA_PROJECTION) return

        if (resultCode == RESULT_OK && data != null) {
            mediaProjection = projectionManager.getMediaProjection(resultCode, data)
            startScreenCapture()
            captureButton.text = "Screen View Enabled"
            setStatus("Screen view enabled")
            refreshPermissionStatus()
            if (!isAccessibilityEnabled()) {
                showAccessibilityHelp()
            }
        } else {
            captureRequestedByPc = false
            setStatus("Screen capture permission denied")
            refreshPermissionStatus()
            socket?.let { sendCommandResult(it, "screen-permission", false, "Screen capture permission denied") }
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 28, 28, 28)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val title = TextView(this).apply {
            text = "LCD Agent"
            textSize = 28f
        }

        val description = TextView(this).apply {
            text = "This device registers itself as a fixed LCD endpoint."
            textSize = 16f
            setPadding(0, 16, 0, 20)
        }

        relayInput = EditText(this).apply {
            hint = "https://your-relay.onrender.com"
            setSingleLine(true)
            setText(DEFAULT_RELAY_URL)
            isFocusable = false
            isCursorVisible = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        connectButton = Button(this).apply {
            text = "Start Agent"
            setOnClickListener { connectAgent() }
        }

        captureButton = Button(this).apply {
            text = "Enable Screen View"
            setOnClickListener { requestScreenCapture() }
        }

        setupButton = Button(this).apply {
            text = "Setup Permissions"
            setOnClickListener { startInitialPermissionSetup() }
        }

        accessibilityButton = Button(this).apply {
            text = "Open Accessibility Settings"
            setOnClickListener { showAccessibilityHelp() }
        }

        statusText = TextView(this).apply {
            text = "Offline"
            textSize = 16f
            setPadding(0, 18, 0, 0)
        }

        permissionStatusText = TextView(this).apply {
            text = "Checking permissions..."
            textSize = 15f
            setPadding(0, 18, 0, 0)
        }

        root.addView(title)
        root.addView(description)
        root.addView(relayInput)
        root.addView(connectButton)
        root.addView(captureButton)
        root.addView(setupButton)
        root.addView(accessibilityButton)
        root.addView(statusText)
        root.addView(permissionStatusText)
        setContentView(root)
    }

    private fun showDeviceCodeOnce() {
        if (prefs.getBoolean(KEY_CODE_ACKNOWLEDGED, false)) return

        AlertDialog.Builder(this)
            .setTitle("Device code")
            .setMessage("$deviceCode\n\nSave this code. It is generated once and cannot be edited.")
            .setPositiveButton("I saved it") { _, _ ->
                prefs.edit().putBoolean(KEY_CODE_ACKNOWLEDGED, true).apply()
                startInitialPermissionSetup()
            }
            .setCancelable(false)
            .show()
    }

    private fun startInitialPermissionSetup() {
        refreshPermissionStatus()

        if (mediaProjection == null) {
            requestScreenCapture()
            return
        }

        if (!isAccessibilityEnabled()) {
            showAccessibilityHelp()
        }
    }

    private fun showAccessibilityHelp() {
        AlertDialog.Builder(this)
            .setTitle("Enable LCD Agent control")
            .setMessage(
                "Step 1: If Android says this app is restricted, open App settings and choose Allow restricted settings from the top-right menu.\n\n" +
                    "Step 2: Open Accessibility settings, select LCD Agent, then turn it on.\n\n" +
                    "Android does not allow apps to enable this permission automatically."
            )
            .setPositiveButton("Open Accessibility") { _, _ -> openAccessibilitySettings() }
            .setNegativeButton("Open App Settings") { _, _ -> openAppSettings() }
            .setNeutralButton("Later", null)
            .show()
    }

    private fun openAccessibilitySettings() {
        runCatching {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    private fun openAppSettings() {
        runCatching {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:$packageName"))
            startActivity(intent)
        }
    }

    private fun refreshPermissionStatus() {
        if (!::permissionStatusText.isInitialized) return

        val screen = if (mediaProjection == null) "Screen view: needs accept" else "Screen view: enabled"
        val tap = if (isAccessibilityEnabled()) "Tap control: enabled" else "Tap control: needs Accessibility"
        permissionStatusText.text = "$screen\n$tap"
    }

    private fun isAccessibilityEnabled(): Boolean {
        val expected = ComponentName(this, LcdAccessibilityService::class.java).flattenToString()
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        return enabledServices.split(":").any { it.equals(expected, ignoreCase = true) }
    }

    private fun connectAgent() {
        reconnectScheduled = false
        val relayUrl = relayInput.text.toString().trim().removeSuffix("/")
        if (relayUrl.isEmpty()) {
            setStatus("Enter relay URL")
            return
        }

        val wsUrl = toWebSocketUrl(relayUrl)
        val request = Request.Builder().url(wsUrl).build()

        socket?.close(1000, "Reconnect")
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send("""{"type":"register","role":"agent","sessionId":"$deviceCode"}""")
                runOnUiThread {
                    connectButton.text = "Reconnect"
                    setStatus("Online as LCD agent")
                }
                startHeartbeat(webSocket)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                runOnUiThread {
                    setStatus("Offline. Reconnecting...")
                    scheduleReconnect()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                runOnUiThread {
                    setStatus("Connection failed: ${t.message ?: "unknown"}. Reconnecting...")
                    scheduleReconnect()
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleServerMessage(webSocket, text)
            }
        })
    }

    private fun scheduleReconnect() {
        if (!shouldReconnect || reconnectScheduled) return

        reconnectScheduled = true
        reconnectHandler.postDelayed({
            if (shouldReconnect) {
                connectAgent()
            }
        }, 5000)
    }

    private fun startHeartbeat(webSocket: WebSocket) {
        heartbeatThread?.interrupt()
        heartbeatThread = Thread {
            while (!Thread.currentThread().isInterrupted) {
                try {
                    Thread.sleep(15000)
                    webSocket.send("""{"type":"heartbeat"}""")
                } catch (_: InterruptedException) {
                    return@Thread
                }
            }
        }.also { it.start() }
    }

    private fun handleServerMessage(webSocket: WebSocket, text: String) {
        val message = runCatching { JSONObject(text) }.getOrNull() ?: return
        if (message.optString("type") != "agent-command") return

        val commandId = message.optString("commandId")
        val command = message.optJSONObject("command") ?: JSONObject()

        when (command.optString("type")) {
            "identify" -> {
                runOnUiThread {
                    Toast.makeText(this, "Identify: $deviceCode", Toast.LENGTH_LONG).show()
                    AlertDialog.Builder(this)
                        .setTitle(deviceCode)
                        .setMessage("This LCD Agent was selected from PC Dashboard.")
                        .setPositiveButton("OK", null)
                        .show()
                }
                sendCommandResult(webSocket, commandId, true, "Identify shown")
            }

            "open-url" -> {
                val url = command.optString("url")
                val ok = openUrl(url)
                sendCommandResult(webSocket, commandId, ok, if (ok) "URL opened" else "Invalid URL")
            }

            "start-screen" -> {
                captureRequestedByPc = true
                if (mediaProjection == null) {
                    runOnUiThread { requestScreenCapture() }
                    sendCommandResult(webSocket, commandId, true, "Waiting for Android screen permission")
                } else {
                    startScreenCapture()
                    sendCommandResult(webSocket, commandId, true, "Screen stream started")
                }
            }

            "stop-screen" -> {
                captureRequestedByPc = false
                stopScreenCapture()
                sendCommandResult(webSocket, commandId, true, "Screen stream stopped")
            }

            "tap" -> {
                val x = command.optDouble("x", -1.0)
                val y = command.optDouble("y", -1.0)
                val ok = performTap(x, y)
                if (!ok) runOnUiThread { showAccessibilityHelp() }
                sendCommandResult(webSocket, commandId, ok, if (ok) "Tap sent" else "Accessibility service is not enabled")
            }

            else -> sendCommandResult(webSocket, commandId, false, "Unknown command")
        }
    }

    private fun requestScreenCapture() {
        startActivityForResult(
            projectionManager.createScreenCaptureIntent(),
            REQUEST_MEDIA_PROJECTION
        )
    }

    private fun startScreenCapture() {
        val projection = mediaProjection ?: return
        if (!captureRequestedByPc && virtualDisplay != null) return

        stopScreenCapture(keepProjection = true)

        val metrics = resources.displayMetrics
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        val density = metrics.densityDpi

        captureThread = HandlerThread("lcd-screen-capture").also { it.start() }
        captureHandler = Handler(captureThread!!.looper)
        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
        virtualDisplay = projection.createVirtualDisplay(
            "lcd-agent-screen",
            screenWidth,
            screenHeight,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface,
            null,
            captureHandler
        )

        imageReader?.setOnImageAvailableListener({ reader ->
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
            captureRequestedByPc = false
            runOnUiThread { refreshPermissionStatus() }
        }
    }

    private fun encodeImage(image: android.media.Image): String? {
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

        val targetWidth = 720
        val targetHeight = (targetWidth.toFloat() / cropped.width * cropped.height).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(cropped, targetWidth, targetHeight, true)
        cropped.recycle()

        val output = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 45, output)
        scaled.recycle()
        return Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
    }

    private fun sendScreenFrame(frame: String) {
        val payload = JSONObject()
            .put("type", "screen-frame")
            .put("deviceCode", deviceCode)
            .put("width", screenWidth)
            .put("height", screenHeight)
            .put("frame", frame)
            .put("ts", System.currentTimeMillis())
        socket?.send(payload.toString())
    }

    private fun performTap(x: Double, y: Double): Boolean {
        if (x !in 0.0..1.0 || y !in 0.0..1.0) return false

        val width = if (screenWidth > 0) screenWidth else resources.displayMetrics.widthPixels
        val height = if (screenHeight > 0) screenHeight else resources.displayMetrics.heightPixels
        return LcdAccessibilityService.tap((x * width).toFloat(), (y * height).toFloat())
    }

    private fun openUrl(value: String): Boolean {
        if (!value.startsWith("http://") && !value.startsWith("https://")) return false

        return runCatching {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(value))
            startActivity(intent)
        }.isSuccess
    }

    private fun sendCommandResult(webSocket: WebSocket, commandId: String, ok: Boolean, message: String) {
        val payload = JSONObject()
            .put("type", "command-result")
            .put("commandId", commandId)
            .put("deviceCode", deviceCode)
            .put("ok", ok)
            .put("message", message)
        webSocket.send(payload.toString())
    }

    private fun setStatus(value: String) {
        statusText.text = value
    }

    private fun createAndStoreDeviceCode(): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val random = SecureRandom()
        val suffix = (1..6)
            .map { alphabet[random.nextInt(alphabet.length)] }
            .joinToString("")
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
        private const val DEFAULT_RELAY_URL = "https://remote-4617.onrender.com"
        private const val KEY_DEVICE_CODE = "device_code"
        private const val KEY_CODE_ACKNOWLEDGED = "device_code_acknowledged"
        private const val REQUEST_MEDIA_PROJECTION = 4201
        private const val FRAME_INTERVAL_MS = 900L
    }
}
