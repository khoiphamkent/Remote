const startButton = document.querySelector("#startButton");
const preview = document.querySelector("#preview");
const urls = document.querySelector("#urls");
const statusBox = document.querySelector("#status");
const relayUrlInput = document.querySelector("#relayUrl");
const sessionCodeInput = document.querySelector("#sessionCode");

let socket;
let peer;
let stream;
let signalBaseUrl = "";
let sessionId = "";
let iceServers = [{ urls: "stun:stun.l.google.com:19302" }];

function setStatus(text) {
  statusBox.textContent = text;
}

async function renderUrls() {
  const info = await window.hostApi.getNetworkInfo();
  urls.innerHTML = "";

  const relayUrl = normalizeHttpUrl(relayUrlInput.value.trim());
  if (relayUrl) {
    addUrl(`${relayUrl}/client.html?session=${encodeURIComponent(sessionId)}`);
  }

  for (const address of info.addresses) {
    addUrl(`http://${address}:${info.port}/client.html?session=${encodeURIComponent(sessionId)}`);
  }
}

function addUrl(value) {
  const item = document.createElement("div");
  item.className = "url";
  item.textContent = value;
  urls.appendChild(item);
}

function createSessionCode() {
  return Math.random().toString(36).slice(2, 8).toUpperCase();
}

function normalizeHttpUrl(value) {
  return value.replace(/\/+$/, "");
}

function toWebSocketUrl(value) {
  const url = new URL(value);
  url.protocol = url.protocol === "https:" ? "wss:" : "ws:";
  url.pathname = "/";
  url.search = "";
  url.hash = "";
  return url.toString();
}

function connectSignal() {
  const wsUrl = signalBaseUrl ? toWebSocketUrl(signalBaseUrl) : `ws://${location.host}`;
  socket = new WebSocket(wsUrl);

  socket.addEventListener("open", () => {
    socket.send(JSON.stringify({ type: "register", role: "host", sessionId }));
    setStatus(`Ready. Open the client URL on Android. Code: ${sessionId}`);
  });

  socket.addEventListener("message", async (event) => {
    const message = JSON.parse(event.data);

    if (message.type === "offer-request") {
      await createPeer();
      const offer = await peer.createOffer();
      await peer.setLocalDescription(offer);
      socket.send(JSON.stringify({ type: "offer", sdp: offer }));
    }

    if (message.type === "answer") {
      await peer.setRemoteDescription(message.sdp);
      setStatus("Client connected.");
    }

    if (message.type === "ice" && peer) {
      await peer.addIceCandidate(message.candidate);
    }
  });
}

async function createPeer() {
  if (peer) peer.close();

  peer = new RTCPeerConnection({
    iceServers
  });

  stream.getTracks().forEach((track) => peer.addTrack(track, stream));

  peer.onicecandidate = (event) => {
    if (event.candidate) {
      socket.send(JSON.stringify({ type: "ice", candidate: event.candidate }));
    }
  };

  peer.ondatachannel = (event) => {
    const channel = event.channel;
    channel.onmessage = (inputEvent) => {
      setStatus(`Input from client: ${inputEvent.data}`);
    };
  };
}

startButton.addEventListener("click", async () => {
  startButton.disabled = true;
  setStatus("Selecting screen...");
  sessionId = sessionCodeInput.value.trim().toUpperCase() || createSessionCode();
  sessionCodeInput.value = sessionId;
  signalBaseUrl = normalizeHttpUrl(relayUrlInput.value.trim());

  stream = await navigator.mediaDevices.getDisplayMedia({
    video: true,
    audio: false
  });
  preview.srcObject = stream;

  connectSignal();
  await renderUrls();
});

window.hostApi.getNetworkInfo().then((info) => {
  relayUrlInput.value = info.defaultRelayUrl || "";
  iceServers = info.iceServers || iceServers;
  sessionCodeInput.value = createSessionCode();
});
