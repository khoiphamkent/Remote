const deviceList = document.querySelector("#deviceList");
const dashboardStatus = document.querySelector("#dashboardStatus");
const commandStatus = document.querySelector("#commandStatus");
const viewerPanel = document.querySelector("#viewerPanel");
const viewerTitle = document.querySelector("#viewerTitle");
const closeViewer = document.querySelector("#closeViewer");
const enableControlButton = document.querySelector("#enableControlButton");
const fitModeButton = document.querySelector("#fitModeButton");
const fullscreenButton = document.querySelector("#fullscreenButton");
const backButton = document.querySelector("#backButton");
const homeButton = document.querySelector("#homeButton");
const recentsButton = document.querySelector("#recentsButton");
const screenImage = document.querySelector("#screenImage");
const viewerStatus = document.querySelector("#viewerStatus");
const editDialog = document.querySelector("#editDialog");
const editDeviceCode = document.querySelector("#editDeviceCode");
const editNameInput = document.querySelector("#editNameInput");
const resetEditButton = document.querySelector("#resetEditButton");
const cancelEditButton = document.querySelector("#cancelEditButton");
const saveEditButton = document.querySelector("#saveEditButton");
let socket;
let activeDeviceCode = "";
let editingDeviceCode = "";
const devicesByCode = new Map();
let lastFrameTime = 0;
let pointerStart = null;
let waitingForFirstFrame = false;
let fitMode = localStorage.getItem("lcd-dashboard-fit-mode") || "stretch";
if (fitMode === "contain") fitMode = "stretch";
let frameCount = 0;
let fpsStartedAt = Date.now();
let currentFps = 0;
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
      devicesByCode.clear();
      for (const device of message.devices || []) {
        devicesByCode.set(device.deviceCode, device);
      }
      renderDevices(message.devices || []);
      refreshViewerControls();
    }

    if (message.type === "command-result") {
      commandStatus.textContent = `${message.deviceCode}: ${message.ok ? "OK" : "Failed"} - ${message.message || ""}`;
      if (message.deviceCode === activeDeviceCode) {
        updateViewerStatusFromCommand(message);
        if (typeof message.accessibilityEnabled !== "undefined") {
          const current = devicesByCode.get(message.deviceCode) || { deviceCode: message.deviceCode, online: true };
          devicesByCode.set(message.deviceCode, {
            ...current,
            accessibilityEnabled: Boolean(message.accessibilityEnabled),
            rootCaptureAvailable: Boolean(message.rootCaptureAvailable),
            captureMode: message.captureMode || current.captureMode || "None"
          });
          refreshViewerControls();
        }
        if (message.ok && message.message === "Screen capture permission accepted") {
          window.setTimeout(() => {
            if (activeDeviceCode === message.deviceCode) {
              waitingForFirstFrame = true;
              viewerStatus.textContent = "Screen permission accepted. Waiting for first frame...";
              sendAgentCommand(activeDeviceCode, { type: "start-screen" });
            }
          }, 500);
        }
      }
    }

    if (message.type === "screen-frame" && message.deviceCode === activeDeviceCode) {
      lastFrameTime = Date.now();
      frameCount++;
      if (lastFrameTime - fpsStartedAt >= 1000) {
        currentFps = Math.round((frameCount * 1000) / (lastFrameTime - fpsStartedAt));
        frameCount = 0;
        fpsStartedAt = lastFrameTime;
      }
      waitingForFirstFrame = false;
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

    const control = document.createElement("span");
    control.className = device.accessibilityEnabled ? "device-status online" : "device-status warning";
    control.textContent = device.accessibilityEnabled ? "Control: Enabled" : "Control: Needs Accessibility";

    const capture = document.createElement("span");
    capture.className = device.captureMode === "Root" ? "device-status online" : "device-status";
    capture.textContent = `Capture: ${device.captureMode || "None"}`;

    const enableControl = document.createElement("button");
    enableControl.className = "button secondary-button";
    enableControl.textContent = "Enable Control";
    enableControl.onclick = () => sendAgentCommand(device.deviceCode, { type: "enable-control" });
    enableControl.hidden = Boolean(device.accessibilityEnabled);

    const edit = document.createElement("button");
    edit.className = "button secondary-button";
    edit.textContent = "Edit";
    edit.onclick = () => openEditDialog(device.deviceCode);

    const action = document.createElement("button");
    action.className = "button";
    action.textContent = "View";
    action.onclick = () => openViewer(device.deviceCode);

    row.appendChild(titleGroup);
    row.appendChild(status);
    row.appendChild(control);
    row.appendChild(capture);
    row.appendChild(enableControl);
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
  waitingForFirstFrame = true;
  frameCount = 0;
  fpsStartedAt = Date.now();
  currentFps = 0;
  viewerStatus.textContent = "Starting screen stream...";
  viewerPanel.hidden = false;
  applyFitMode();
  commandStatus.textContent = `${deviceCode}: starting screen view...`;
  socket.send(JSON.stringify({ type: "watch-device", deviceCode }));
  sendAgentCommand(deviceCode, { type: "accessibility-status" });
  window.setTimeout(() => {
    if (activeDeviceCode === deviceCode) {
      sendAgentCommand(deviceCode, { type: "start-screen" });
    }
  }, 250);
  refreshViewerControls();
}

function refreshViewerControls() {
  const device = devicesByCode.get(activeDeviceCode);
  const enabled = Boolean(device?.accessibilityEnabled);
  enableControlButton.hidden = enabled || !activeDeviceCode;
  backButton.disabled = !enabled;
  homeButton.disabled = !enabled;
  recentsButton.disabled = !enabled;
}

function openEditDialog(deviceCode) {
  const labels = getDeviceLabels();
  editingDeviceCode = deviceCode;
  editDeviceCode.textContent = deviceCode;
  editNameInput.value = labels[deviceCode] || "";
  editDialog.hidden = false;
  window.setTimeout(() => {
    editNameInput.focus();
    editNameInput.select();
  }, 0);
}

function closeEditDialog() {
  editingDeviceCode = "";
  editDialog.hidden = true;
}

function saveEditLabel() {
  if (!editingDeviceCode) return;
  const labels = getDeviceLabels();
  const cleaned = editNameInput.value.trim();
  if (!cleaned || cleaned === editingDeviceCode) {
    delete labels[editingDeviceCode];
    commandStatus.textContent = `${editingDeviceCode}: name reset`;
  } else {
    labels[editingDeviceCode] = cleaned.slice(0, 60);
    commandStatus.textContent = `${editingDeviceCode}: name saved`;
  }
  localStorage.setItem(DEVICE_LABELS_KEY, JSON.stringify(labels));
  closeEditDialog();
  socket?.send(JSON.stringify({ type: "list-devices" }));
}

function resetEditLabel() {
  if (!editingDeviceCode) return;
  editNameInput.value = "";
  saveEditLabel();
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

function updateViewerStatusFromCommand(message) {
  const text = message.message || "Waiting for screen frame...";
  const lower = text.toLowerCase();
  const isControlStatus =
    lower.includes("accessibility") ||
    lower.includes("back sent") ||
    lower.includes("home sent") ||
    lower.includes("recents sent") ||
    lower.includes("tap sent") ||
    lower.includes("swipe sent");

  if (waitingForFirstFrame && isControlStatus) {
    if (!viewerStatus.textContent || viewerStatus.textContent.includes("Accessibility")) {
      viewerStatus.textContent = "Waiting for LCD screen frame...";
    }
    return;
  }

  viewerStatus.textContent = text;
}

closeViewer.addEventListener("click", () => {
  if (activeDeviceCode) {
    sendAgentCommand(activeDeviceCode, { type: "stop-screen" });
  }
  activeDeviceCode = "";
  waitingForFirstFrame = false;
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

enableControlButton.addEventListener("click", () => {
  if (activeDeviceCode) sendAgentCommand(activeDeviceCode, { type: "enable-control" });
});

fitModeButton.addEventListener("click", () => {
  fitMode = fitMode === "stretch" ? "width" : fitMode === "width" ? "contain" : "stretch";
  localStorage.setItem("lcd-dashboard-fit-mode", fitMode);
  applyFitMode();
});

fullscreenButton.addEventListener("click", () => {
  const target = viewerPanel;
  if (!document.fullscreenElement) {
    target.requestFullscreen?.();
  } else {
    document.exitFullscreen?.();
  }
});

saveEditButton.addEventListener("click", saveEditLabel);
resetEditButton.addEventListener("click", resetEditLabel);
cancelEditButton.addEventListener("click", closeEditDialog);

editDialog.addEventListener("click", (event) => {
  if (event.target === editDialog) closeEditDialog();
});

editNameInput.addEventListener("keydown", (event) => {
  if (event.key === "Enter") saveEditLabel();
  if (event.key === "Escape") closeEditDialog();
});

screenImage.addEventListener("load", () => {
  if (activeDeviceCode) {
    screenImage.hidden = false;
    viewerStatus.textContent = `Last frame: ${new Date(lastFrameTime).toLocaleTimeString()} | ${currentFps} fps`;
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

function applyFitMode() {
  viewerPanel.classList.toggle("fit-stretch", fitMode === "stretch");
  viewerPanel.classList.toggle("fit-contain", fitMode === "contain");
  viewerPanel.classList.toggle("fit-width", fitMode === "width");
  fitModeButton.textContent = fitMode === "stretch" ? "Stretch" : fitMode === "width" ? "Width" : "Fit";
}

connectDashboard();
