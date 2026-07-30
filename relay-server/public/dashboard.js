const deviceList = document.querySelector("#deviceList");
const dashboardStatus = document.querySelector("#dashboardStatus");
const commandStatus = document.querySelector("#commandStatus");
const viewerPanel = document.querySelector("#viewerPanel");
const viewerTitle = document.querySelector("#viewerTitle");
const closeViewer = document.querySelector("#closeViewer");
const backButton = document.querySelector("#backButton");
const homeButton = document.querySelector("#homeButton");
const recentsButton = document.querySelector("#recentsButton");
const screenImage = document.querySelector("#screenImage");
const viewerStatus = document.querySelector("#viewerStatus");
let socket;
let activeDeviceCode = "";
let lastFrameTime = 0;
let pointerStart = null;
const DEVICE_LABELS_KEY = "lcd-dashboard-device-labels";

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
        if (message.ok && message.message === "Screen capture permission accepted") {
          window.setTimeout(() => {
            if (activeDeviceCode === message.deviceCode) {
              sendAgentCommand(activeDeviceCode, { type: "start-screen" });
            }
          }, 500);
        }
      }
    }

    if (message.type === "screen-frame" && message.deviceCode === activeDeviceCode) {
      lastFrameTime = Date.now();
      screenImage.dataset.frameWidth = message.width || "";
      screenImage.dataset.frameHeight = message.height || "";
      screenImage.src = `data:image/jpeg;base64,${message.frame}`;
    }
  });

  socket.addEventListener("close", () => {
    dashboardStatus.textContent = "Disconnected";
    setTimeout(connectDashboard, 3000);
  });
}

function renderDevices(devices) {
  deviceList.innerHTML = "";
  const labels = getDeviceLabels();

  if (!devices.length) {
    const empty = document.createElement("div");
    empty.className = "empty-state";
    empty.textContent = "No LCD agents online.";
    deviceList.appendChild(empty);
    return;
  }

  for (const device of devices) {
    const label = labels[device.deviceCode] || device.deviceCode;
    const row = document.createElement("article");
    row.className = "device-card";

    const titleGroup = document.createElement("div");
    titleGroup.className = "device-title";

    const title = document.createElement("h2");
    title.textContent = label;

    const code = document.createElement("span");
    code.textContent = device.deviceCode;

    titleGroup.appendChild(title);
    titleGroup.appendChild(code);

    const status = document.createElement("span");
    status.className = device.online ? "device-status online" : "device-status";
    status.textContent = device.online ? "Online" : "Offline";

    const edit = document.createElement("button");
    edit.className = "button secondary-button";
    edit.textContent = "Edit";
    edit.onclick = () => editDeviceLabel(device.deviceCode);

    const action = document.createElement("button");
    action.className = "button";
    action.textContent = "View";
    action.onclick = () => openViewer(device.deviceCode);

    row.appendChild(titleGroup);
    row.appendChild(status);
    row.appendChild(edit);
    row.appendChild(action);
    deviceList.appendChild(row);
  }
}

function openViewer(deviceCode) {
  activeDeviceCode = deviceCode;
  const label = getDeviceLabels()[deviceCode] || deviceCode;
  viewerTitle.textContent = label === deviceCode ? `${deviceCode} Viewer` : `${label} (${deviceCode})`;
  screenImage.removeAttribute("src");
  screenImage.hidden = true;
  viewerStatus.textContent = "Starting screen stream...";
  viewerPanel.hidden = false;
  commandStatus.textContent = `${deviceCode}: starting screen view...`;
  socket.send(JSON.stringify({ type: "watch-device", deviceCode }));
  sendAgentCommand(deviceCode, { type: "start-screen" });
}

function editDeviceLabel(deviceCode) {
  const labels = getDeviceLabels();
  const currentLabel = labels[deviceCode] || "";
  const nextLabel = prompt("Display name for this LCD", currentLabel || deviceCode);
  if (nextLabel === null) return;

  const cleaned = nextLabel.trim();
  if (!cleaned || cleaned === deviceCode) {
    delete labels[deviceCode];
    commandStatus.textContent = `${deviceCode}: name reset`;
  } else {
    labels[deviceCode] = cleaned.slice(0, 60);
    commandStatus.textContent = `${deviceCode}: name saved`;
  }
  localStorage.setItem(DEVICE_LABELS_KEY, JSON.stringify(labels));
  socket?.send(JSON.stringify({ type: "list-devices" }));
}

function getDeviceLabels() {
  try {
    return JSON.parse(localStorage.getItem(DEVICE_LABELS_KEY) || "{}");
  } catch {
    return {};
  }
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
    sendAgentCommand(activeDeviceCode, { type: "stop-screen" });
  }
  activeDeviceCode = "";
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
    screenImage.hidden = false;
    viewerStatus.textContent = `Last frame: ${new Date(lastFrameTime).toLocaleTimeString()}`;
  }
});

screenImage.addEventListener("error", () => {
  screenImage.hidden = true;
  viewerStatus.textContent = "Frame received but image decode failed. Waiting for next frame...";
});

screenImage.addEventListener("pointerdown", (event) => {
  pointerStart = getNormalizedPoint(screenImage, event);
  screenImage.setPointerCapture?.(event.pointerId);
});

screenImage.addEventListener("pointerup", (event) => {
  if (!activeDeviceCode || !pointerStart) return;
  const end = getNormalizedPoint(screenImage, event);
  const start = pointerStart;
  pointerStart = null;
  if (!end) return;

  const distance = Math.hypot(end.x - start.x, end.y - start.y);
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
});

function getNormalizedPoint(target, event) {
  const rect = target.getBoundingClientRect();
  if (!rect.width || !rect.height) return null;
  return {
    x: Math.max(0, Math.min(1, (event.clientX - rect.left) / rect.width)),
    y: Math.max(0, Math.min(1, (event.clientY - rect.top) / rect.height))
  };
}

connectDashboard();
