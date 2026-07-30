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

app.use(express.static(path.join(__dirname, "..", "public")));

app.get("/health", (_req, res) => {
  res.json({ ok: true, rooms: rooms.size });
});

app.get("/config", (_req, res) => {
  res.json({ iceServers: ICE_SERVERS });
});

app.get("/", (_req, res) => {
  res.redirect("/client.html");
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
      role = message.role === "host" ? "host" : "client";
      if (!sessionId) return;

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

    const room = rooms.get(sessionId);
    const target = role === "host" ? room?.client : room?.host;
    if (target && target.readyState === WebSocket.OPEN) {
      target.send(JSON.stringify(message));
    }
  });

  socket.on("close", () => {
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
