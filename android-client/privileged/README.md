# LCD Agent privileged/root deployment

These files are for Android boxes/tablets that you own and manage as kiosk/LCD devices.

## Rooted device path

1. Install the normal APK.
2. Open LCD Agent once.
3. From the PC dashboard, click `View`.
4. Android/Magisk should ask for `su` access for LCD Agent. Grant it permanently.
5. Dashboard should show `Capture: Root`, and screen sharing no longer needs the Android MediaProjection popup.

Root capture uses:

```sh
su -c "screencap -p"
```

## Firmware / priv-app path

For a custom ROM or writable system image, install the APK as a privileged app:

```sh
adb root
adb remount
adb shell mkdir -p /system/priv-app/LcdAgent
adb push app-debug.apk /system/priv-app/LcdAgent/LcdAgent.apk
adb push privileged/privapp-permissions-com.example.remiolike.client.xml /system/etc/permissions/
adb shell chmod 0644 /system/priv-app/LcdAgent/LcdAgent.apk
adb shell chmod 0644 /system/etc/permissions/privapp-permissions-com.example.remiolike.client.xml
adb reboot
```

On Android 10+, privileged permission allowlists are enforced. If the ROM refuses boot or logs a privapp permission violation, keep only the permissions supported by that build in the XML.

## Accessibility control

When rooted or privileged with `WRITE_SECURE_SETTINGS`, the dashboard `Enable Control` button attempts to enable LCD Agent Accessibility automatically. If that fails, Android will open Accessibility Settings and the installer must enable it manually.

## Kiosk

Device Owner is still recommended for kiosk behavior:

```sh
adb shell dpm set-device-owner com.example.remiolike.client/.LcdDeviceAdminReceiver
```

Set Device Owner immediately after factory reset, before adding Google accounts.
