package com.example.remiolike.client

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("lcd_agent", Context.MODE_PRIVATE) }
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private lateinit var relayInput: EditText
    private lateinit var statusText: TextView
    private lateinit var connectButton: Button
    private var socket: WebSocket? = null
    private var heartbeatThread: Thread? = null
    private val reconnectHandler = Handler(Looper.getMainLooper())
    private var shouldReconnect = true
    private var reconnectScheduled = false

    private val deviceCode: String by lazy {
        prefs.getString(KEY_DEVICE_CODE, null) ?: createAndStoreDeviceCode()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        showDeviceCodeOnce()
        relayInput.post { connectAgent() }
    }

    override fun onDestroy() {
        shouldReconnect = false
        reconnectHandler.removeCallbacksAndMessages(null)
        socket?.close(1000, "Activity closed")
        heartbeatThread?.interrupt()
        super.onDestroy()
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

        statusText = TextView(this).apply {
            text = "Offline"
            textSize = 16f
            setPadding(0, 18, 0, 0)
        }

        root.addView(title)
        root.addView(description)
        root.addView(relayInput)
        root.addView(connectButton)
        root.addView(statusText)
        setContentView(root)
    }

    private fun showDeviceCodeOnce() {
        if (prefs.getBoolean(KEY_CODE_ACKNOWLEDGED, false)) return

        AlertDialog.Builder(this)
            .setTitle("Device code")
            .setMessage("$deviceCode\n\nSave this code. It is generated once and cannot be edited.")
            .setPositiveButton("I saved it") { _, _ ->
                prefs.edit().putBoolean(KEY_CODE_ACKNOWLEDGED, true).apply()
            }
            .setCancelable(false)
            .show()
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

    private fun setStatus(value: String) {
        statusText.text = value
    }

    private fun handleServerMessage(webSocket: WebSocket, text: String) {
        val message = runCatching { JSONObject(text) }.getOrNull() ?: return
        if (message.optString("type") != "agent-command") return

        val commandId = message.optString("commandId")
        val command = message.optJSONObject("command") ?: JSONObject()
        val commandType = command.optString("type")

        when (commandType) {
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

            else -> sendCommandResult(webSocket, commandId, false, "Unknown command")
        }
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
    }
}
