# LCD Dashboard + Android Agent

Bo project nay la starter kit de quan ly Android gan sau man LCD:

- `pc-host`: ung dung Windows/Electron mo LCD dashboard tren PC.
- `android-client`: ung dung Android 11 LCD Agent de dang ky man hinh LCD len relay.
- `relay-server`: signaling server cong khai de host va client ket noi khi khac Wi-Fi.

> Luu y: hai file `.exe` Remio trong Downloads la installer da build san, khong phai source code. Project nay duoc dung moi theo cung mo hinh host/client.

## Yeu cau

### PC Dashboard

- Node.js 20+.
- Windows 10/11.

### Android Client

- Android Studio.
- JDK 17.
- Android SDK voi `minSdk 23`. Ban debug stable hien tai target `targetSdk 33` de giam rui ro foreground-service khi cai noi bo.

## Chay khac Wi-Fi qua relay

Can co 1 server public HTTPS/WSS, vi du VPS, Render, Railway, Fly.io, hoac may nha da mo port/domain.

```powershell
cd relay-server
npm.cmd install
npm.cmd start
```

Mac dinh relay chay cong `4174`. Khi deploy, dat bien moi truong:

```text
PORT=4174
ICE_SERVERS_JSON=[{"urls":"stun:stun.l.google.com:19302"}]
```

Neu ket noi khac Wi-Fi bi ket o trang thai waiting, ban can TURN server. Khi do dat:

```text
ICE_SERVERS_JSON=[{"urls":"stun:stun.l.google.com:19302"},{"urls":"turn:YOUR_TURN_DOMAIN:3478","username":"USER","credential":"PASS"}]
```

Sau khi relay co URL cong khai, vi du:

```text
https://your-relay.example.com
```

Dashboard quan sat LCD nam tai:

```text
https://your-relay.example.com/dashboard.html
```

Voi mo hinh LCD Agent moi, cai APK Android, mo app, luu `LCD-XXXXXX` hien lan dau. App mac dinh tro toi `https://remote-4617.onrender.com` va tu ket noi. PC mo dashboard se thay LCD online.

## Mo hinh LCD Agent

Nac hien tai da co:

- Android tu tao `LCD-XXXXXX` mot lan va luu co dinh trong may.
- Code hien lan dau de nguoi lap dat ghi lai, khong co o sua code.
- Android auto-fill `https://remote-4617.onrender.com`, tu ket noi Render relay va bao online.
- Relay co `/dashboard.html` va `/devices` de PC xem LCD online.
- PC Dashboard co `Edit` de dat ten de nho cho tung LCD theo ma `LCD-XXXXXX`.
- PC Dashboard co `View` de xem man hinh LCD Agent qua JPEG WebSocket stable.
- PC co the tap, swipe, Back, Home, Recents tren LCD Agent neu Accessibility da duoc bat.
- Tren thiet bi root/managed firmware, Android Agent uu tien `Root Capture` bang `su -c screencap -p` de xem man hinh khong can popup `Chia se toan bo man hinh`.
- Android Agent chay bang foreground service nen van online khi bam Home/Back neu he thong khong kill service.
- Android Agent tu khoi dong lai service khi boot/may cai update. Neu app la Device Owner, app tu mo lai va vao kiosk/lock-task.
- Kiosk mode: khi duoc set Device Owner, LCD Agent allowlist lock-task, khoa status bar/keyguard neu thiet bi ho tro, va khong cho nguoi dung thoat app bang thao tac thuong.

Quyen can bat tren Android:

- Khi mo app, Android Agent tu xin quyen share/capture screen. Nguoi lap dat chi can dong y.
- De dieu khien tu PC co tac dung tren Android, app se mo huong dan Accessibility. Neu Android bao app bi han che, vao App settings -> menu top-right -> `Allow restricted settings`, sau do vao Accessibility -> LCD Agent -> bat service.
- Android khong cho APK thuong bo qua popup `Chia se toan bo man hinh`. Popup nay phai accept it nhat mot lan sau moi lan reboot/kill projection. Device Owner/kiosk giup app luon song va nguoi dung khong thoat, nhung khong bypass duoc MediaProjection prompt tren Android goc.
- Neu thiet bi da root va LCD Agent duoc grant `su`, dashboard `View` se thu root capture truoc. Khi thanh cong, dashboard hien `Capture: Root` va khong can popup share man hinh.

## Device Owner / Kiosk

De kiosk dung nghia, nen set ngay sau factory reset, truoc khi them Google account:

```powershell
adb install -r "C:\Users\khoip\OneDrive\Documents\New project\android-client\app\build\outputs\apk\debug\app-debug.apk"
adb shell dpm set-device-owner com.example.remiolike.client/.LcdDeviceAdminReceiver
adb shell monkey -p com.example.remiolike.client 1
```

Sau do tren LCD:

1. Luu ma `LCD-XXXXXX` hien lan dau.
2. Bam `Setup All Permissions`.
3. Accept bo toi uu pin neu may hien.
4. Accept `Chia se toan bo man hinh`.
5. Bat Accessibility `LCD Agent` neu can dieu khien tap/swipe/back/home.

Neu `dpm set-device-owner` bao loi do may da co account/owner, can factory reset hoac dung MDM/OEM enrollment.

## Root / Firmware Capture

Huong nay danh cho Android box/tablet do ban so huu va quan ly.

### Rooted Android box

1. Cai APK moi.
2. Mo LCD Agent va de `Relay: connected`.
3. Tren PC Dashboard bam `View`.
4. Neu Magisk/SuperSU hoi quyen `su`, chon grant/allow forever.
5. Dashboard se hien `Capture: Root` khi thanh cong. Tu luc do xem man hinh khong can popup `Chia se toan bo man hinh`.

Nut `Enable Control` se thu bat Accessibility bang root. Neu root khong cho sua secure settings, app se mo man Accessibility de bat tay.

### Custom firmware / priv-app

File mau nam tai:

```text
android-client/privileged/privapp-permissions-com.example.remiolike.client.xml
android-client/privileged/README.md
```

Lenh mau:

```powershell
adb root
adb remount
adb shell mkdir -p /system/priv-app/LcdAgent
adb push android-client\app\build\outputs\apk\debug\app-debug.apk /system/priv-app/LcdAgent/LcdAgent.apk
adb push android-client\privileged\privapp-permissions-com.example.remiolike.client.xml /system/etc/permissions/
adb shell chmod 0644 /system/priv-app/LcdAgent/LcdAgent.apk
adb shell chmod 0644 /system/etc/permissions/privapp-permissions-com.example.remiolike.client.xml
adb reboot
```

## Chay PC Dashboard

```powershell
cd pc-host
npm.cmd install
npm.cmd start
```

App PC mac dinh mo:

```text
https://remote-4617.onrender.com/dashboard.html
```

Co the doi URL dashboard khi test local:

```powershell
$env:LCD_DASHBOARD_URL="https://your-relay.example.com/dashboard.html"
npm.cmd start
```

## Chay Android Agent

1. Mo thu muc `android-client` bang Android Studio.
2. Sync Gradle.
3. Chay tren may Android 11.
4. App tu hien `LCD-XXXXXX` lan dau va tu ket noi `https://remote-4617.onrender.com`.

## Dong goi ban PC

```powershell
cd pc-host
npm.cmd run dist
```

File installer Windows se nam trong `pc-host/dist`.

Ban build hien tai chua ky code certificate, nen Windows SmartScreen co the can ban bam `More info` -> `Run anyway` khi cai dat noi bo.

## Tinh nang hien co

- PC Dashboard mo dashboard LCD mac dinh.
- Android Agent tu tao ma LCD co dinh va bao online.
- Relay server quan ly danh sach LCD online/offline.
- JPEG stable stream.
- Root capture mode cho thiet bi rooted/managed firmware de bo popup share man hinh.
- Remote tap/swipe/back/home/recents qua Accessibility.
- Foreground service + boot receiver + Device Owner kiosk hooks.

## Viec can lam tiep

- Deploy relay server voi HTTPS/WSS va TURN server neu can WebRTC stream on dinh.
- Build APK/AAB signed release.
