const connectButton = document.querySelector("#connectButton");
const sessionCodeInput = document.querySelector("#sessionCode");
const remoteVideo = document.querySelector("#remoteVideo");
const clientStatus = document.querySelector("#clientStatus");

let socket;
let peer;
let inputChannel;
let iceServers = [{ urls: "stun:stun.l.google.com:19302" }];

const params = new URLSearchParams(location.search);
sessionCodeInput.value = (params.get("session") || "").toUpperCase();

function setStatus(text) {
  clientStatus.textContent = text;
}

async function loadConfig() {
  try {
    const response = await fetch("/config");
    const config = await response.json();
    if (Array.isArray(config.iceServers)) {
      iceServers = config.iceServers;
    }
  } catch {
    // Keep the default STUN server when no config endpoint is available.
  }
}

function sendInput(type, event) {
  if (!inputChannel || inputChannel.readyState !== "open") return;

  const rect = remoteVideo.getBoundingClientRect();
  const payload = {
    type,
    x: Math.max(0, Math.min(1, (event.clientX - rect.left) / rect.width)),
    y: Math.max(0, Math.min(1, (event.clientY - rect.top) / rect.height)),
    button: event.button || 0,
    ts: Date.now()
  };

  inputChannel.send(JSON.stringify(payload));
}

async function connect() {
  const sessionId = sessionCodeInput.value.trim().toUpperCase();
  if (!sessionId) {
    setStatus("Enter code");
    return;
  }

  connectButton.disabled = true;
  setStatus("Connecting...");
  const protocol = location.protocol === "https:" ? "wss:" : "ws:";
  socket = new WebSocket(`${protocol}//${location.host}`);

  socket.addEventListener("open", () => {
    socket.send(JSON.stringify({ type: "register", role: "client", sessionId }));
    socket.send(JSON.stringify({ type: "offer-request" }));
    setStatus("Waiting for host...");
  });

  socket.addEventListener("message", async (event) => {
    const message = JSON.parse(event.data);

    if (message.type === "offer") {
      peer = new RTCPeerConnection({ iceServers });

      inputChannel = peer.createDataChannel("input");

      peer.ontrack = (trackEvent) => {
        remoteVideo.srcObject = trackEvent.streams[0];
        setStatus("Connected");
      };

      peer.onicecandidate = (iceEvent) => {
        if (iceEvent.candidate) {
          socket.send(JSON.stringify({ type: "ice", candidate: iceEvent.candidate }));
        }
      };

      await peer.setRemoteDescription(message.sdp);
      const answer = await peer.createAnswer();
      await peer.setLocalDescription(answer);
      socket.send(JSON.stringify({ type: "answer", sdp: answer }));
    }

    if (message.type === "ice" && peer) {
      await peer.addIceCandidate(message.candidate);
    }
  });

  socket.addEventListener("close", () => {
    setStatus("Disconnected");
    connectButton.disabled = false;
  });
}

loadConfig();
connectButton.addEventListener("click", connect);
remoteVideo.addEventListener("pointerdown", (event) => sendInput("pointerdown", event));
remoteVideo.addEventListener("pointermove", (event) => sendInput("pointermove", event));
remoteVideo.addEventListener("pointerup", (event) => sendInput("pointerup", event));
