const deviceList = document.querySelector("#deviceList");
const dashboardStatus = document.querySelector("#dashboardStatus");
const commandStatus = document.querySelector("#commandStatus");
let socket;

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
    action.disabled = true;
    action.title = "Screen viewing will be added in phase 2.";

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

connectDashboard();
