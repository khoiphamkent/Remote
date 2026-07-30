const deviceList = document.querySelector("#deviceList");
const dashboardStatus = document.querySelector("#dashboardStatus");
const commandStatus = document.querySelector("#commandStatus");
const viewerPanel = document.querySelector("#viewerPanel");
const viewerTitle = document.querySelector("#viewerTitle");
const closeViewer = document.querySelector("#closeViewer");
const backButton = document.querySelector("#backButton");
const homeButton = document.querySelector("#homeButton");
const recentsButton = document.querySelector("#recentsButton");
const screenVideo = document.querySelector("#screenVideo");
const screenImage = document.querySelector("#screenImage");
const viewerStatus = document.querySelector("#viewerStatus");
let socket;
let activeDeviceCode = "";
let lastFrameTime = 0;
let iceServers = [{ urls: "stun:stun.l.google.com:19302" }];
let peerConnection = null;
let fallbackTimer = null;
let pointerStart = null;

fetch("/config")
  .then((response) => response.json())
  .then((config) => {
    if (Array.isArray(config.iceServers)) iceServers = config.iceServers;
  })
  .catch(() => {});

function connectDashboard() {
  const protocol = location.protocol === "https:" ? "wss:" : "ws:";
  socket = new WebSocket(`${protocol}//${location.host}`);

  socket.addEventListener("open", () => {
    dashboardStatus.textContent = "Online";
    socket.send(JSON.stringify({ type: "register", role: "dashboard", sessionId: "DASHBOARD" }));
  });

  socket.addEventListener("message", (event) => {
    const message = JSON.parse(event.data);
    if (message.type === "devices") {
      renderDevices(message.devices || []);
    }
    if (message.type === "command-result") {
      commandStatus.textContent = `${message.deviceCode}: ${message.ok ? "OK" : "Failed"} - ${message.message || ""}`;
      if (message.deviceCode === activeDeviceCode) {
        viewerStatus.textContent = message.message || "Waiting for screen frame...";
      }
    }
    if (message.type === "screen-frame" && message.deviceCode === activeDeviceCode) {
      lastFrameTime = Date.now();
      viewerStatus.textContent = "Receiving screen...";
      screenImage.dataset.frameWidth = message.width || "";
      screenImage.dataset.frameHeight = message.height || "";
      screenImage.src = `data:image/jpeg;base64,${message.frame}`;
    }
    if (message.type === "webrtc-offer" && message.deviceCode === activeDeviceCode) {
      acceptWebRtcOffer(message);
    }
    if (message.type === "webrtc-ice" && message.deviceCode === activeDeviceCode && peerConnection) {
      peerConnection.addIceCandidate(message.candidate).catch(() => {});
    }
    if (message.type === "webrtc-state" && message.deviceCode === activeDeviceCode) {
      viewerStatus.textContent = message.message || "WebRTC status updated.";
    }
  });

  socket.addEventListener("close", () => {
    dashboardStatus.textContent = "Disconnected";
    setTimeout(connectDashboard, 3000);
  });
}

function renderDevices(devices) {
  deviceList.innerHTML = "";

  if (!devices.length) {
    const empty = document.createElement("div");
    empty.className = "empty-state";
    empty.textContent = "No LCD agents online.";
    deviceList.appendChild(empty);
    return;
  }

  for (const device of devices) {
    const row = document.createElement("article");
    row.className = "device-card";

    const title = document.createElement("h2");
    title.textContent = device.deviceCode;

    const status = document.createElement("span");
    status.className = device.online ? "device-status online" : "device-status";
    status.textContent = device.online ? "Online" : "Offline";

    const action = document.createElement("button");
    action.className = "button";
    action.textContent = "View";
    action.onclick = () => openViewer(device.deviceCode);

    const identify = document.createElement("button");
    identify.className = "button secondary-button";
    identify.textContent = "Identify";
    identify.onclick = () => sendAgentCommand(device.deviceCode, { type: "identify" });

    const openUrl = document.createElement("button");
    openUrl.className = "button secondary-button";
    openUrl.textContent = "Open URL";
    openUrl.onclick = () => {
      const url = prompt("URL to open on LCD agent", "https://remote-4617.onrender.com/dashboard.html");
      if (url) {
        sendAgentCommand(device.deviceCode, { type: "open-url", url });
      }
    };

    row.appendChild(title);
    row.appendChild(status);
    row.appendChild(identify);
    row.appendChild(openUrl);
    row.appendChild(action);
    deviceList.appendChild(row);
  }
}

function openViewer(deviceCode) {
  activeDeviceCode = deviceCode;
  viewerTitle.textContent = `${deviceCode} Viewer`;
  closePeerConnection();
  window.clearTimeout(fallbackTimer);
  screenVideo.srcObject = null;
  screenVideo.hidden = true;
  screenImage.removeAttribute("src");
  screenImage.hidden = true;
  viewerStatus.textContent = "Starting WebRTC screen stream...";
  viewerPanel.hidden = false;
  commandStatus.textContent = `${deviceCode}: starting screen view...`;
  socket.send(JSON.stringify({ type: "watch-device", deviceCode }));
  sendAgentCommand(deviceCode, { type: "start-webrtc" });
  fallbackTimer = window.setTimeout(() => {
    if (activeDeviceCode === deviceCode && screenVideo.hidden) {
      viewerStatus.textContent = "WebRTC not ready. Falling back to JPEG stream...";
      sendAgentCommand(deviceCode, { type: "start-screen" });
    }
  }, 8000);
}

function sendAgentCommand(deviceCode, command) {
  if (!socket || socket.readyState !== WebSocket.OPEN) {
    commandStatus.textContent = "Dashboard is disconnected.";
    return;
  }

  const commandId = `${Date.now()}-${Math.random().toString(36).slice(2)}`;
  commandStatus.textContent = `${deviceCode}: sending ${command.type}...`;
  socket.send(JSON.stringify({
    type: "agent-command",
    commandId,
    deviceCode,
    command
  }));
}

closeViewer.addEventListener("click", () => {
  if (activeDeviceCode) {
    sendAgentCommand(activeDeviceCode, { type: "stop-webrtc" });
    sendAgentCommand(activeDeviceCode, { type: "stop-screen" });
  }
  activeDeviceCode = "";
  closePeerConnection();
  window.clearTimeout(fallbackTimer);
  screenVideo.srcObject = null;
  screenVideo.hidden = true;
  viewerPanel.hidden = true;
  screenImage.removeAttribute("src");
  screenImage.hidden = true;
});

backButton.addEventListener("click", () => {
  if (activeDeviceCode) sendAgentCommand(activeDeviceCode, { type: "back" });
});

homeButton.addEventListener("click", () => {
  if (activeDeviceCode) sendAgentCommand(activeDeviceCode, { type: "home" });
});

recentsButton.addEventListener("click", () => {
  if (activeDeviceCode) sendAgentCommand(activeDeviceCode, { type: "recents" });
});

screenImage.addEventListener("load", () => {
  if (activeDeviceCode) {
    if (!screenVideo.hidden) return;
    screenImage.hidden = false;
    viewerStatus.textContent = `Last frame: ${new Date(lastFrameTime).toLocaleTimeString()}`;
  }
});

screenImage.addEventListener("error", () => {
  screenImage.hidden = true;
  viewerStatus.textContent = "Frame received but image decode failed. Waiting for next frame...";
});

screenImage.addEventListener("pointerdown", onPointerDown);
screenImage.addEventListener("pointerup", onPointerUp);
screenVideo.addEventListener("pointerdown", onPointerDown);
screenVideo.addEventListener("pointerup", onPointerUp);

async function acceptWebRtcOffer(message) {
  closePeerConnection();
  peerConnection = new RTCPeerConnection({ iceServers });

  peerConnection.ontrack = (event) => {
    window.clearTimeout(fallbackTimer);
    screenImage.hidden = true;
    screenVideo.hidden = false;
    screenVideo.srcObject = event.streams[0];
    viewerStatus.textContent = "Receiving WebRTC screen...";
  };

  peerConnection.onicecandidate = (event) => {
    if (!event.candidate || !activeDeviceCode) return;
    socket.send(JSON.stringify({
      type: "webrtc-ice",
      deviceCode: activeDeviceCode,
      candidate: event.candidate
    }));
  };

  peerConnection.onconnectionstatechange = () => {
    if (!peerConnection) return;
    viewerStatus.textContent = `WebRTC: ${peerConnection.connectionState}`;
  };

  await peerConnection.setRemoteDescription({ type: "offer", sdp: message.sdp });
  const answer = await peerConnection.createAnswer();
  await peerConnection.setLocalDescription(answer);
  socket.send(JSON.stringify({
    type: "webrtc-answer",
    deviceCode: activeDeviceCode,
    sdp: answer.sdp
  }));
}

function closePeerConnection() {
  if (!peerConnection) return;
  peerConnection.close();
  peerConnection = null;
}

function onPointerDown(event) {
  const point = getNormalizedPoint(event.currentTarget, event);
  if (!point) return;
  pointerStart = point;
  event.currentTarget.setPointerCapture?.(event.pointerId);
}

function onPointerUp(event) {
  if (!activeDeviceCode || !pointerStart) return;
  const end = getNormalizedPoint(event.currentTarget, event);
  const start = pointerStart;
  pointerStart = null;
  if (!end) return;

  const dx = end.x - start.x;
  const dy = end.y - start.y;
  const distance = Math.hypot(dx, dy);
  if (distance < 0.025) {
    sendAgentCommand(activeDeviceCode, { type: "tap", x: end.x, y: end.y });
  } else {
    sendAgentCommand(activeDeviceCode, {
      type: "swipe",
      startX: start.x,
      startY: start.y,
      endX: end.x,
      endY: end.y
    });
  }
}

function getNormalizedPoint(target, event) {
  const rect = target.getBoundingClientRect();
  if (!rect.width || !rect.height) return null;
  return {
    x: Math.max(0, Math.min(1, (event.clientX - rect.left) / rect.width)),
    y: Math.max(0, Math.min(1, (event.clientY - rect.top) / rect.height))
  };
}

connectDashboard();
