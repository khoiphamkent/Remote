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
          socket.send(JSON.stringify({ type: "registered", sessionId, role }));
          broadcastDevices();
          return;
        }

        if (role === "dashboard") {
          dashboards.add(socket);
          socket.send(JSON.stringify({ type: "devices", devices: getDeviceList() }));
          return;
        }

        const room = rooms.get(sessionId) || {};
      if (room[role] && room[role].readyState === WebSocket.OPEN) {
        room[role].close(4000, "Replaced by a new connection");
      }
      room[role] = socket;
      rooms.set(sessionId, room);
      socket.send(JSON.stringify({ type: "registered", sessionId, role }));
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

      if (role === "dashboard" && message.type === "agent-command") {
        forwardAgentCommand(socket, message);
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
  const command = message.command || {};
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

function broadcastDashboards(message) {
  const payload = JSON.stringify(message);
  for (const socket of dashboards) {
    if (socket.readyState === WebSocket.OPEN) {
      socket.send(payload);
    }
  }
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
