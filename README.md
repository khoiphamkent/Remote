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
- Android SDK voi `minSdk 23`, `targetSdk 35`.

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
- PC Dashboard co lenh dieu khien co ban: `Identify` de hien thong bao tren LCD, `Open URL` de mo link tren LCD Agent.
- PC Dashboard co `View` de xem anh man hinh LCD Agent qua frame JPEG WebSocket kich thuoc nho de on dinh tren Render.
- PC co the click len anh viewer de gui lenh tap ve LCD Agent.
- Android Agent chay bang foreground service nen van online khi bam Home/Back.
- Android Agent tu khoi dong lai service khi boot/may cai update, va setup share man hinh chi tu dong hoi mot lan luc cai/mo lan dau.
- Stream viewer duoc tang len khoang 4 fps va chi gui frame khi PC dang bam `View`.

Quyen can bat tren Android:

- Khi mo app, Android Agent tu xin quyen share/capture screen. Nguoi lap dat chi can dong y.
- De click/tap tu PC co tac dung tren Android, app se mo huong dan Accessibility. Neu Android bao app bi han che, vao App settings -> menu top-right -> `Allow restricted settings`, sau do vao Accessibility -> LCD Agent -> bat service.

Nac tiep theo can lam:

- Chay nen bang foreground service/kiosk mode.
- Nang cap stream tu JPEG frame sang WebRTC neu can FPS cao/latency thap.

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

## Viec can lam tiep

- Chay nen bang foreground service/kiosk mode.
- Nang cap stream tu JPEG frame sang WebRTC neu can FPS cao/latency thap.
- Deploy relay server voi HTTPS/WSS va TURN server neu can WebRTC stream on dinh.
- Build APK/AAB signed release.
