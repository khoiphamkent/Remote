package com.example.remiolike.client

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
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
import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.json.JSONArray
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
    private var reconnectScheduled = false
    private var heartbeatThread: Thread? = null
    private var mediaProjection: MediaProjection? = null
    private var projectionResultCode = 0
    private var projectionData: Intent? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private var captureActive = false
    private var lastFrameAt = 0L
    private var screenWidth = 0
    private var screenHeight = 0
    private var eglBase: EglBase? = null
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var screenCapturer: ScreenCapturerAndroid? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var pendingWebRtcStart = false
    private var rtcIceServers = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
    )
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
        initWebRtcFactory()
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
                    projectionResultCode = resultCode
                    projectionData = Intent(data)
                    hasProjection = true
                    sendCommandResult("screen-permission", true, "Screen capture permission accepted")
                    if (pendingWebRtcStart) {
                        mainHandler.postDelayed({
                            val ok = startWebRtcStream()
                            sendWebRtcState(if (ok) "WebRTC screen stream started" else "WebRTC could not start")
                        }, 300)
                    } else if (captureActive) {
                        mainHandler.post { startScreenCapture() }
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
        stopWebRtcStream()
        socket?.close(1000, "Service stopped")
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
        if (socket?.send("""{"type":"heartbeat"}""") == true) return
        reconnectScheduled = false

        val request = Request.Builder().url(toWebSocketUrl(DEFAULT_RELAY_URL)).build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send("""{"type":"register","role":"agent","sessionId":"$deviceCode"}""")
                startHeartbeat(webSocket)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
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
                    webSocket.send("""{"type":"heartbeat"}""")
                } catch (_: InterruptedException) {
                    return@Thread
                }
            }
        }.also { it.start() }
    }

    private fun handleServerMessage(text: String) {
        val message = runCatching { JSONObject(text) }.getOrNull() ?: return
        when (message.optString("type")) {
            "registered" -> {
                updateIceServers(message.optJSONArray("iceServers"))
                return
            }
            "webrtc-answer" -> {
                applyWebRtcAnswer(message.optString("sdp"))
                return
            }
            "webrtc-ice" -> {
                addRemoteIceCandidate(message.optJSONObject("candidate"))
                return
            }
        }
        if (message.optString("type") != "agent-command") return

        val commandId = message.optString("commandId")
        val command = message.optJSONObject("command") ?: JSONObject()
        when (command.optString("type")) {
            "identify" -> sendCommandResult(commandId, true, "LCD Agent is running")
            "open-url" -> {
                val ok = openUrl(command.optString("url"))
                sendCommandResult(commandId, ok, if (ok) "URL opened" else "Invalid URL")
            }
            "start-screen" -> {
                captureActive = true
                if (!ensureMediaProjection()) {
                    sendCommandResult(commandId, false, "Open LCD Agent and accept screen sharing permission")
                } else {
                    startScreenCapture()
                    sendCommandResult(commandId, true, "Screen stream started")
                }
            }
            "start-webrtc" -> {
                pendingWebRtcStart = true
                captureActive = false
                stopScreenCapture(keepProjection = false)
                val ok = startWebRtcStream()
                sendCommandResult(commandId, ok, if (ok) "WebRTC screen stream started" else "Open LCD Agent and accept screen sharing permission")
            }
            "stop-webrtc" -> {
                pendingWebRtcStart = false
                stopWebRtcStream()
                sendCommandResult(commandId, true, "WebRTC screen stream stopped")
            }
            "stop-screen" -> {
                captureActive = false
                stopScreenCapture(keepProjection = true)
                sendCommandResult(commandId, true, "Screen stream stopped")
            }
            "tap" -> {
                val x = command.optDouble("x", -1.0)
                val y = command.optDouble("y", -1.0)
                val ok = performTap(x, y)
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

    private fun ensureMediaProjection(): Boolean {
        if (mediaProjection != null) return true
        val data = projectionData ?: return false
        if (projectionResultCode == 0) return false

        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = manager.getMediaProjection(projectionResultCode, data)
        mediaProjection?.registerCallback(projectionCallback, mainHandler)
        return mediaProjection != null
    }

    private fun startScreenCapture() {
        val projection = mediaProjection ?: return
        if (virtualDisplay != null) return

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
    }

    private fun initWebRtcFactory() {
        if (peerConnectionFactory != null) return

        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(this)
                .createInitializationOptions()
        )
        eglBase = EglBase.create()
        val eglContext = eglBase!!.eglBaseContext
        val encoderFactory = DefaultVideoEncoderFactory(eglContext, true, true)
        val decoderFactory = DefaultVideoDecoderFactory(eglContext)
        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()
    }

    private fun startWebRtcStream(): Boolean {
        val factory = peerConnectionFactory ?: return false
        val data = projectionData ?: return false

        pendingWebRtcStart = false
        stopWebRtcStream()
        val rtcConfig = PeerConnection.RTCConfiguration(rtcIceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }

        peerConnection = factory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState?) = Unit

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                sendWebRtcState("WebRTC: ${state?.name ?: "unknown"}")
            }

            override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit

            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) = Unit

            override fun onIceCandidate(candidate: IceCandidate?) {
                if (candidate != null) sendLocalIceCandidate(candidate)
            }

            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit

            override fun onAddStream(stream: MediaStream?) = Unit

            override fun onRemoveStream(stream: MediaStream?) = Unit

            override fun onDataChannel(dataChannel: DataChannel?) = Unit

            override fun onRenegotiationNeeded() = Unit

            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) = Unit
        }) ?: return false

        val eglContext = eglBase?.eglBaseContext ?: return false
        surfaceTextureHelper = SurfaceTextureHelper.create("lcd-webrtc-capture", eglContext)
        videoSource = factory.createVideoSource(false)
        screenCapturer = ScreenCapturerAndroid(data, object : MediaProjection.Callback() {
            override fun onStop() {
                hasProjection = false
                stopWebRtcStream()
            }
        })
        screenCapturer?.initialize(surfaceTextureHelper, applicationContext, videoSource!!.capturerObserver)
        screenCapturer?.startCapture(WEBRTC_WIDTH, WEBRTC_HEIGHT, WEBRTC_FPS)
        videoTrack = factory.createVideoTrack("lcd-screen-video", videoSource).apply {
            setEnabled(true)
        }
        peerConnection?.addTrack(videoTrack, listOf("lcd-screen"))
        peerConnection?.createOffer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(description: SessionDescription?) {
                val offer = description ?: return
                peerConnection?.setLocalDescription(SimpleSdpObserver(), offer)
                socket?.send(
                    JSONObject()
                        .put("type", "webrtc-offer")
                        .put("deviceCode", deviceCode)
                        .put("sdp", offer.description)
                        .toString()
                )
            }

            override fun onCreateFailure(error: String?) {
                sendWebRtcState("WebRTC offer failed: ${error ?: "unknown"}")
            }
        }, MediaConstraints())

        hasProjection = true
        sendWebRtcState("WebRTC offer created")
        return true
    }

    private fun stopWebRtcStream() {
        runCatching { screenCapturer?.stopCapture() }
        screenCapturer?.dispose()
        screenCapturer = null
        videoTrack?.dispose()
        videoTrack = null
        videoSource?.dispose()
        videoSource = null
        surfaceTextureHelper?.dispose()
        surfaceTextureHelper = null
        peerConnection?.close()
        peerConnection?.dispose()
        peerConnection = null
    }

    private fun applyWebRtcAnswer(sdp: String) {
        if (sdp.isBlank()) return
        peerConnection?.setRemoteDescription(
            SimpleSdpObserver(),
            SessionDescription(SessionDescription.Type.ANSWER, sdp)
        )
    }

    private fun addRemoteIceCandidate(candidateJson: JSONObject?) {
        val candidate = candidateJson ?: return
        val sdp = candidate.optString("candidate")
        if (sdp.isBlank()) return
        peerConnection?.addIceCandidate(
            IceCandidate(
                candidate.optString("sdpMid"),
                candidate.optInt("sdpMLineIndex"),
                sdp
            )
        )
    }

    private fun sendLocalIceCandidate(candidate: IceCandidate) {
        socket?.send(
            JSONObject()
                .put("type", "webrtc-ice")
                .put("deviceCode", deviceCode)
                .put(
                    "candidate",
                    JSONObject()
                        .put("sdpMid", candidate.sdpMid)
                        .put("sdpMLineIndex", candidate.sdpMLineIndex)
                        .put("candidate", candidate.sdp)
                )
                .toString()
        )
    }

    private fun sendWebRtcState(message: String) {
        socket?.send(
            JSONObject()
                .put("type", "webrtc-state")
                .put("deviceCode", deviceCode)
                .put("message", message)
                .toString()
        )
    }

    private fun updateIceServers(servers: JSONArray?) {
        val parsed = mutableListOf<PeerConnection.IceServer>()
        if (servers != null) {
            for (index in 0 until servers.length()) {
                val item = servers.optJSONObject(index) ?: continue
                val username = item.optString("username")
                val credential = item.optString("credential")
                val urls = item.opt("urls")
                when (urls) {
                    is String -> addIceServer(parsed, urls, username, credential)
                    is JSONArray -> {
                        for (urlIndex in 0 until urls.length()) {
                            addIceServer(parsed, urls.optString(urlIndex), username, credential)
                        }
                    }
                }
            }
        }
        if (parsed.isNotEmpty()) {
            rtcIceServers = parsed
        }
    }

    private fun addIceServer(
        target: MutableList<PeerConnection.IceServer>,
        url: String,
        username: String,
        credential: String
    ) {
        if (url.isBlank()) return
        val builder = PeerConnection.IceServer.builder(url)
        if (username.isNotBlank() || credential.isNotBlank()) {
            builder.setUsername(username)
            builder.setPassword(credential)
        }
        target.add(builder.createIceServer())
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

        val targetWidth = 480
        val targetHeight = (targetWidth.toFloat() / cropped.width * cropped.height).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(cropped, targetWidth, targetHeight, true)
        cropped.recycle()

        val output = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 35, output)
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

    private fun sendCommandResult(commandId: String, ok: Boolean, message: String) {
        socket?.send(
            JSONObject()
                .put("type", "command-result")
                .put("commandId", commandId)
                .put("deviceCode", deviceCode)
                .put("ok", ok)
                .put("message", message)
                .toString()
        )
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
        private const val FRAME_INTERVAL_MS = 250L
        private const val WEBRTC_WIDTH = 720
        private const val WEBRTC_HEIGHT = 1280
        private const val WEBRTC_FPS = 24

        @Volatile var isRunning = false
        @Volatile var hasProjection = false
    }
}

private open class SimpleSdpObserver : SdpObserver {
    override fun onCreateSuccess(description: SessionDescription?) = Unit

    override fun onSetSuccess() = Unit

    override fun onCreateFailure(error: String?) = Unit

    override fun onSetFailure(error: String?) = Unit
}
