# Remio-like PC Host + Android 11 Client

Bo project nay la starter kit de lam ung dung tuong tu mo hinh Remio:

- `pc-host`: ung dung Windows/Electron de chia se man hinh qua WebRTC.
- `android-client`: ung dung Android 11 LCD Agent de dang ky man hinh LCD len relay.
- `relay-server`: signaling server cong khai de host va client ket noi khi khac Wi-Fi.

> Luu y: hai file `.exe` Remio trong Downloads la installer da build san, khong phai source code. Project nay duoc dung moi theo cung mo hinh host/client.

## Yeu cau

### PC Host

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

Voi mo hinh cu PC share sang Android viewer, mo PC Host, nhap URL nay vao `Relay URL`, bam `Start sharing`, app se hien link:

```text
https://your-relay.example.com/client.html?session=<CODE>
```

Voi mo hinh LCD Agent moi, cai APK Android, mo app, luu `LCD-XXXXXX` hien lan dau, nhap `Relay URL`, bam `Start Agent`. PC mo dashboard se thay LCD online.

## Mo hinh LCD Agent

Nac hien tai da co:

- Android tu tao `LCD-XXXXXX` mot lan va luu co dinh trong may.
- Code hien lan dau de nguoi lap dat ghi lai, khong co o sua code.
- Android ket noi Render relay va bao online.
- Relay co `/dashboard.html` va `/devices` de PC xem LCD online.

Nac tiep theo can lam:

- Android xin quyen capture man hinh bang MediaProjection.
- Android stream man hinh len PC viewer bang WebRTC.
- Chay nen bang foreground service/kiosk mode.

## Chay cung Wi-Fi / LAN

```powershell
cd pc-host
npm.cmd install
npm.cmd start
```

Trong app PC, de trong `Relay URL`, bam `Start sharing`, chon man hinh/cua so can chia se. App se hien IP LAN va URL dang:

```text
http://<PC-IP>:4173/client.html
```

## Chay Android Client

1. Mo thu muc `android-client` bang Android Studio.
2. Sync Gradle.
3. Chay tren may Android 11.
4. Nhap URL cua PC Host, vi du `http://192.168.1.10:4173/client.html`.

May Android va PC can nam chung mang LAN. Neu khong ket noi duoc, hay cho phep Windows Firewall mo cong `4173`.

## Cau hinh PC Host mac dinh

Co the dat relay/TURN mac dinh truoc khi chay app:

```powershell
$env:REMIO_RELAY_URL="https://your-relay.example.com"
$env:REMIO_ICE_SERVERS_JSON='[{"urls":"stun:stun.l.google.com:19302"}]'
npm.cmd start
```

## Dong goi ban PC

```powershell
cd pc-host
npm.cmd run dist
```

File installer Windows se nam trong `pc-host/dist`.

Ban build hien tai chua ky code certificate, nen Windows SmartScreen co the can ban bam `More info` -> `Run anyway` khi cai dat noi bo.

## Tinh nang hien co

- PC host tao signaling server cuc bo.
- Relay server cho ket noi khac Wi-Fi qua Internet.
- Stream man hinh PC sang client bang WebRTC.
- Android 11 client mo viewer bang WebView.
- Client gui su kien cham/chuot qua WebRTC data channel ve host.

## Viec can lam tiep de giong remote-control hoan chinh

- Gan input client vao Windows bang native automation library nhu `@nut-tree/nut-js`.
- Them ma PIN/password cho session.
- Deploy relay server voi HTTPS/WSS va TURN server on dinh.
- Build APK/AAB signed release.
