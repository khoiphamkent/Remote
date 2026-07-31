package com.example.remiolike.client

import android.app.Activity
import android.app.AlertDialog
import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import java.security.SecureRandom

class MainActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("lcd_agent", Context.MODE_PRIVATE) }
    private lateinit var projectionManager: MediaProjectionManager
    private lateinit var permissionStatusText: TextView
    private lateinit var relayInput: EditText
    private lateinit var devicePolicyManager: DevicePolicyManager
    private lateinit var adminComponent: ComponentName
    private val statusRefresh = object : Runnable {
        override fun run() {
            refreshPermissionStatus()
            permissionStatusText.postDelayed(this, 1500)
        }
    }

    private val deviceCode: String by lazy {
        prefs.getString(KEY_DEVICE_CODE, null) ?: createAndStoreDeviceCode()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        devicePolicyManager = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        adminComponent = ComponentName(this, LcdDeviceAdminReceiver::class.java)
        buildUi()
        startAgentService()
        requestNotificationPermissionIfNeeded()
        applyKioskIfDeviceOwner()
        handleAction(intent)

        val needsCodeAcknowledgement = !prefs.getBoolean(KEY_CODE_ACKNOWLEDGED, false)
        showDeviceCodeOnce()
        relayInput.post {
            if (!needsCodeAcknowledgement && !prefs.getBoolean(KEY_INITIAL_SETUP_DONE, false)) {
                startInitialPermissionSetup()
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAction(intent)
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionStatus()
        startAgentService()
        applyKioskIfDeviceOwner()
        if (::permissionStatusText.isInitialized) {
            permissionStatusText.removeCallbacks(statusRefresh)
            permissionStatusText.postDelayed(statusRefresh, 1500)
        }
    }

    override fun onPause() {
        if (::permissionStatusText.isInitialized) {
            permissionStatusText.removeCallbacks(statusRefresh)
        }
        super.onPause()
    }

    override fun onBackPressed() {
        if (!applyKioskIfDeviceOwner()) {
            moveTaskToBack(true)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_MEDIA_PROJECTION) return

        if (resultCode == RESULT_OK && data != null) {
            prefs.edit()
                .putBoolean(KEY_SCREEN_CAPTURE_ACCEPTED, true)
                .putBoolean(KEY_INITIAL_SETUP_DONE, true)
                .apply()
            val serviceIntent = Intent(this, LcdAgentService::class.java)
                .setAction(LcdAgentService.ACTION_SET_PROJECTION)
                .putExtra(LcdAgentService.EXTRA_RESULT_CODE, resultCode)
                .putExtra(LcdAgentService.EXTRA_PROJECTION_DATA, data)
            startForegroundService(serviceIntent)
        }

        refreshPermissionStatus()
        if (!isAccessibilityEnabled()) {
            showAccessibilityHelp()
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
            text = "This device runs in the background as a fixed LCD endpoint."
            textSize = 16f
            setPadding(0, 16, 0, 20)
        }

        relayInput = EditText(this).apply {
            setSingleLine(true)
            setText(DEFAULT_RELAY_URL)
            isFocusable = false
            isCursorVisible = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val setupButton = Button(this).apply {
            text = "Setup All Permissions"
            setOnClickListener { startInitialPermissionSetup() }
        }

        val accessibilityButton = Button(this).apply {
            text = "Open Accessibility Settings"
            setOnClickListener { showAccessibilityHelp() }
        }

        val hideButton = Button(this).apply {
            text = "Run In Background"
            setOnClickListener { moveTaskToBack(true) }
        }

        val kioskButton = Button(this).apply {
            text = "Enter Kiosk Mode"
            setOnClickListener { enterKioskOrShowSetup() }
        }

        permissionStatusText = TextView(this).apply {
            text = "Checking permissions..."
            textSize = 15f
            setPadding(0, 18, 0, 0)
        }

        root.addView(title)
        root.addView(description)
        root.addView(relayInput)
        root.addView(setupButton)
        root.addView(kioskButton)
        root.addView(accessibilityButton)
        root.addView(hideButton)
        root.addView(permissionStatusText)
        setContentView(root)
    }

    private fun startAgentService() {
        val intent = Intent(this, LcdAgentService::class.java)
            .setAction(LcdAgentService.ACTION_START)
        startForegroundService(intent)
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
        requestNotificationPermissionIfNeeded()
        requestIgnoreBatteryOptimizationsIfNeeded()
        requestScreenCapture()
    }

    private fun handleAction(intent: Intent?) {
        if (intent?.action != ACTION_REQUEST_SCREEN_CAPTURE) return
        relayInput.post {
            requestScreenCapture()
        }
    }

    private fun enterKioskOrShowSetup() {
        if (applyKioskIfDeviceOwner()) return

        AlertDialog.Builder(this)
            .setTitle("Device Owner required")
            .setMessage(
                "To lock this LCD into kiosk mode, set LCD Agent as Device Owner once with ADB before normal use:\n\n" +
                    "adb shell dpm set-device-owner com.example.remiolike.client/.LcdDeviceAdminReceiver\n\n" +
                    "This usually requires a freshly reset device with no Google account added yet."
            )
            .setPositiveButton("OK", null)
            .show()
    }

    private fun applyKioskIfDeviceOwner(): Boolean {
        if (!::devicePolicyManager.isInitialized) return false
        if (!devicePolicyManager.isDeviceOwnerApp(packageName)) return false

        runCatching { devicePolicyManager.setLockTaskPackages(adminComponent, arrayOf(packageName)) }
        runCatching { devicePolicyManager.setKeyguardDisabled(adminComponent, true) }
        if (Build.VERSION.SDK_INT >= 23) {
            runCatching { devicePolicyManager.setStatusBarDisabled(adminComponent, true) }
        }
        runCatching { startLockTask() }
        return true
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < 33) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_POST_NOTIFICATIONS)
    }

    private fun requestScreenCapture() {
        startActivityForResult(
            projectionManager.createScreenCaptureIntent(),
            REQUEST_MEDIA_PROJECTION
        )
    }

    private fun requestIgnoreBatteryOptimizationsIfNeeded() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (powerManager.isIgnoringBatteryOptimizations(packageName)) return

        runCatching {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
            )
        }
    }

    private fun showAccessibilityHelp() {
        AlertDialog.Builder(this)
            .setTitle("Enable tap control")
            .setMessage(
                "Android blocks Accessibility for apps installed from APK files.\n\n" +
                    "1. Tap Open App Settings.\n" +
                    "2. Open the top-right menu and choose Allow restricted settings.\n" +
                    "3. Return here and tap Open Accessibility Settings.\n" +
                    "4. Select LCD Agent and turn it on."
            )
            .setPositiveButton("Open Accessibility") { _, _ -> openAccessibilitySettings() }
            .setNegativeButton("Open App Settings") { _, _ -> openAppSettings() }
            .setNeutralButton("Later", null)
            .show()
    }

    private fun openAccessibilitySettings() {
        runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
    }

    private fun openAppSettings() {
        runCatching {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:$packageName"))
            )
        }
    }

    private fun refreshPermissionStatus() {
        if (!::permissionStatusText.isInitialized) return

        val service = if (LcdAgentService.isRunning) "Agent service: running" else "Agent service: starting"
        val relay = LcdAgentService.connectionStatus
        val kiosk = if (devicePolicyManager.isDeviceOwnerApp(packageName)) {
            "Kiosk: device owner active"
        } else {
            "Kiosk: needs Device Owner setup"
        }
        val screen = when {
            LcdAgentService.hasProjection -> "Screen view: enabled"
            prefs.getBoolean(KEY_SCREEN_CAPTURE_ACCEPTED, false) -> "Screen view: accepted once, reopen setup only if Android killed it"
            else -> "Screen view: needs one-time accept"
        }
        val tap = when {
            LcdAccessibilityService.isReady() -> "Remote control: ready"
            isAccessibilityEnabled() -> "Remote control: enabled, waiting for service"
            else -> "Remote control: needs Accessibility"
        }
        val root = if (LcdAgentService.rootCaptureAvailable) "Root capture: available" else "Root capture: not active"
        val capture = "Capture mode: ${LcdAgentService.captureMode}"
        permissionStatusText.text = "$service\n$relay\n$kiosk\n$screen\n$root\n$capture\n$tap\nDevice: $deviceCode"
    }

    private fun isAccessibilityEnabled(): Boolean {
        val expected = ComponentName(this, LcdAccessibilityService::class.java).flattenToString()
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        return enabledServices.split(":").any { it.equals(expected, ignoreCase = true) }
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

    companion object {
        const val ACTION_REQUEST_SCREEN_CAPTURE = "com.example.remiolike.client.REQUEST_SCREEN_CAPTURE"

        private const val DEFAULT_RELAY_URL = "https://remote-4617.onrender.com"
        private const val KEY_DEVICE_CODE = "device_code"
        private const val KEY_CODE_ACKNOWLEDGED = "device_code_acknowledged"
        private const val KEY_SCREEN_CAPTURE_ACCEPTED = "screen_capture_accepted"
        private const val KEY_INITIAL_SETUP_DONE = "initial_setup_done"
        private const val REQUEST_MEDIA_PROJECTION = 4201
        private const val REQUEST_POST_NOTIFICATIONS = 4202
    }
}
