const express = require("express");
const http = require("http");
const path = require("path");
const WebSocket = require("ws");

const PORT = process.env.PORT || 4174;
const ICE_SERVERS = parseIceServers(process.env.ICE_SERVERS_JSON);

const app = express();
const server = http.createServer(app);
const wss = new WebSocket.Server({ server });
const rooms = new Map();
const agents = new Map();
const dashboards = new Set();
const viewersByDevice = new Map();

app.use((req, res, next) => {
  if (/\.(html|js|css)$/i.test(req.path)) {
    res.setHeader("Cache-Control", "no-store, no-cache, must-revalidate, proxy-revalidate");
    res.setHeader("Pragma", "no-cache");
    res.setHeader("Expires", "0");
  }
  next();
});

app.use(express.static(path.join(__dirname, "..", "public")));

app.get("/health", (_req, res) => {
  res.json({ ok: true, rooms: rooms.size, agents: agents.size });
});

app.get("/config", (_req, res) => {
  res.json({ iceServers: ICE_SERVERS });
});

app.get("/devices", (_req, res) => {
  res.json({ devices: getDeviceList() });
});

app.get("/", (_req, res) => {
  res.redirect("/dashboard.html");
});

wss.on("connection", (socket) => {
  let sessionId = "";
  let role = "";

  socket.on("message", (raw) => {
    let message;
    try {
      message = JSON.parse(raw.toString());
    } catch {
      return;
    }

      if (message.type === "register") {
        sessionId = String(message.sessionId || "").trim().toUpperCase();
        role = normalizeRole(message.role);
        if (!sessionId) return;

        if (role === "agent") {
          agents.set(sessionId, {
            deviceCode: sessionId,
            socket,
            lastSeen: Date.now()
          });
          socket.send(JSON.stringify({ type: "registered", sessionId, role, iceServers: ICE_SERVERS }));
          broadcastDevices();
          return;
        }

        if (role === "dashboard") {
          dashboards.add(socket);
          socket.send(JSON.stringify({ type: "devices", devices: getDeviceList(), iceServers: ICE_SERVERS }));
          return;
        }

        const room = rooms.get(sessionId) || {};
      if (room[role] && room[role].readyState === WebSocket.OPEN) {
        room[role].close(4000, "Replaced by a new connection");
      }
      room[role] = socket;
      rooms.set(sessionId, room);
      socket.send(JSON.stringify({ type: "registered", sessionId, role, iceServers: ICE_SERVERS }));
      notifyPeerCount(sessionId);
      return;
      }

      if (role === "agent" && message.type === "heartbeat") {
        const agent = agents.get(sessionId);
        if (agent) {
          agent.lastSeen = Date.now();
        }
        return;
      }

      if (role === "dashboard" && message.type === "list-devices") {
        socket.send(JSON.stringify({ type: "devices", devices: getDeviceList() }));
        return;
      }

      if (role === "dashboard" && message.type === "watch-device") {
        watchDevice(socket, message.deviceCode);
        return;
      }

      if (role === "dashboard" && message.type === "agent-command") {
        forwardAgentCommand(socket, message);
        return;
      }

      if (role === "dashboard" && (message.type === "webrtc-answer" || message.type === "webrtc-ice")) {
        forwardWebRtcToAgent(socket, message);
        return;
      }

      if (role === "agent" && message.type === "screen-frame") {
        broadcastScreenFrame(sessionId, message);
        return;
      }

      if (role === "agent" && (message.type === "webrtc-offer" || message.type === "webrtc-ice" || message.type === "webrtc-state")) {
        broadcastToDeviceViewers(sessionId, message);
        return;
      }

      if (role === "agent" && message.type === "command-result") {
        broadcastDashboards(message);
        return;
      }

      const room = rooms.get(sessionId);
    const target = role === "host" ? room?.client : room?.host;
    if (target && target.readyState === WebSocket.OPEN) {
      target.send(JSON.stringify(message));
    }
  });

  socket.on("close", () => {
    dashboards.delete(socket);
    removeViewer(socket);

    if (role === "agent" && sessionId) {
      const agent = agents.get(sessionId);
      if (agent?.socket === socket) {
        agents.delete(sessionId);
        broadcastDevices();
      }
      return;
    }

    if (!sessionId || !role) return;
    const room = rooms.get(sessionId);
    if (!room) return;

    if (room[role] === socket) {
      delete room[role];
    }

    if (!room.host && !room.client) {
      rooms.delete(sessionId);
    } else {
      notifyPeerCount(sessionId);
    }
  });
});

setInterval(() => {
  const staleBefore = Date.now() - 45000;
  let changed = false;

  for (const [deviceCode, agent] of agents.entries()) {
    if (agent.lastSeen < staleBefore || agent.socket.readyState !== WebSocket.OPEN) {
      agents.delete(deviceCode);
      changed = true;
    }
  }

  if (changed) broadcastDevices();
}, 15000);

function normalizeRole(value) {
  if (value === "agent" || value === "dashboard") return value;
  return value === "host" ? "host" : "client";
}

function getDeviceList() {
  return Array.from(agents.values())
    .map((agent) => ({
      deviceCode: agent.deviceCode,
      online: agent.socket.readyState === WebSocket.OPEN,
      lastSeen: agent.lastSeen
    }))
    .sort((a, b) => a.deviceCode.localeCompare(b.deviceCode));
}

function broadcastDevices() {
  const payload = JSON.stringify({ type: "devices", devices: getDeviceList() });
  for (const socket of dashboards) {
    if (socket.readyState === WebSocket.OPEN) {
      socket.send(payload);
    }
  }
}

function forwardAgentCommand(sourceSocket, message) {
  const deviceCode = String(message.deviceCode || "").trim().toUpperCase();
  const agent = agents.get(deviceCode);
  const command = normalizeAgentCommand(message.command || {});
  const commandId = message.commandId || `${Date.now()}-${Math.random().toString(36).slice(2)}`;

  if (!agent || agent.socket.readyState !== WebSocket.OPEN) {
    sourceSocket.send(JSON.stringify({
      type: "command-result",
      commandId,
      deviceCode,
      ok: false,
      message: "LCD agent is offline"
    }));
    return;
  }

  agent.socket.send(JSON.stringify({
    type: "agent-command",
    commandId,
    deviceCode,
    command
  }));
}

function normalizeAgentCommand(command) {
  if (command.type === "start-webrtc") {
    return { ...command, type: "start-screen" };
  }
  if (command.type === "stop-webrtc") {
    return { ...command, type: "stop-screen" };
  }
  return command;
}

function broadcastDashboards(message) {
  const payload = JSON.stringify(message);
  for (const socket of dashboards) {
    if (socket.readyState === WebSocket.OPEN) {
      socket.send(payload);
    }
  }
}

function watchDevice(socket, rawDeviceCode) {
  const deviceCode = String(rawDeviceCode || "").trim().toUpperCase();
  if (!deviceCode) return;

  removeViewer(socket);
  const viewers = viewersByDevice.get(deviceCode) || new Set();
  viewers.add(socket);
  viewersByDevice.set(deviceCode, viewers);
  socket.watchingDeviceCode = deviceCode;
}

function removeViewer(socket) {
  const deviceCode = socket.watchingDeviceCode;
  if (!deviceCode) return;

  const viewers = viewersByDevice.get(deviceCode);
  if (viewers) {
    viewers.delete(socket);
    if (!viewers.size) viewersByDevice.delete(deviceCode);
  }
  delete socket.watchingDeviceCode;
}

function broadcastScreenFrame(deviceCode, message) {
  const viewers = viewersByDevice.get(deviceCode);
  if (!viewers) return;

  const payload = JSON.stringify({
    type: "screen-frame",
    deviceCode,
    width: message.width,
    height: message.height,
    frame: message.frame,
    ts: message.ts || Date.now()
  });

  for (const socket of viewers) {
    if (socket.readyState === WebSocket.OPEN) {
      socket.send(payload);
    }
  }
}

function broadcastToDeviceViewers(deviceCode, message) {
  const viewers = viewersByDevice.get(deviceCode);
  if (!viewers) return;

  const payload = JSON.stringify({ ...message, deviceCode });
  for (const socket of viewers) {
    if (socket.readyState === WebSocket.OPEN) {
      socket.send(payload);
    }
  }
}

function forwardWebRtcToAgent(sourceSocket, message) {
  const deviceCode = String(message.deviceCode || "").trim().toUpperCase();
  const agent = agents.get(deviceCode);

  if (!agent || agent.socket.readyState !== WebSocket.OPEN) {
    sourceSocket.send(JSON.stringify({
      type: "command-result",
      deviceCode,
      ok: false,
      message: "LCD agent is offline"
    }));
    return;
  }

  agent.socket.send(JSON.stringify({ ...message, deviceCode }));
}

function notifyPeerCount(sessionId) {
  const room = rooms.get(sessionId);
  if (!room) return;

  const count = Number(Boolean(room.host)) + Number(Boolean(room.client));
  for (const socket of [room.host, room.client]) {
    if (socket && socket.readyState === WebSocket.OPEN) {
      socket.send(JSON.stringify({ type: "peer-count", count }));
    }
  }
}

server.listen(PORT, "0.0.0.0", () => {
  console.log(`Relay server listening on http://0.0.0.0:${PORT}`);
});

function parseIceServers(value) {
  if (!value) {
    return [{ urls: "stun:stun.l.google.com:19302" }];
  }

  try {
    const parsed = JSON.parse(value);
    return Array.isArray(parsed) ? parsed : [{ urls: "stun:stun.l.google.com:19302" }];
  } catch {
    return [{ urls: "stun:stun.l.google.com:19302" }];
  }
}
