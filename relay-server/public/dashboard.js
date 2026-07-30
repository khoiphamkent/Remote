const deviceList = document.querySelector("#deviceList");
const dashboardStatus = document.querySelector("#dashboardStatus");
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

    row.appendChild(title);
    row.appendChild(status);
    row.appendChild(action);
    deviceList.appendChild(row);
  }
}

connectDashboard();
