const { app, BrowserWindow, ipcMain, desktopCapturer, session } = require("electron");
const path = require("path");
const os = require("os");
const express = require("express");
const http = require("http");
const WebSocket = require("ws");

const PORT = process.env.REMIO_LIKE_PORT || 4173;
const DEFAULT_RELAY_URL = process.env.REMIO_RELAY_URL || "";
const DEFAULT_ICE_SERVERS = parseIceServers(process.env.REMIO_ICE_SERVERS_JSON);

let mainWindow;
let server;

function getLanAddresses() {
  const interfaces = os.networkInterfaces();
  const addresses = [];

  for (const entries of Object.values(interfaces)) {
    for (const entry of entries || []) {
      if (entry.family === "IPv4" && !entry.internal) {
        addresses.push(entry.address);
      }
    }
  }

  return addresses;
}

function startServer() {
  const expressApp = express();
  const publicDir = path.join(__dirname, "..", "public");

  expressApp.get("/config", (_req, res) => {
    res.json({ iceServers: DEFAULT_ICE_SERVERS });
  });

  expressApp.use(express.static(publicDir));

  server = http.createServer(expressApp);
  const wss = new WebSocket.Server({ server });
  const peers = new Map();

  wss.on("connection", (socket) => {
    let peerId = "";
    let role = "";

    socket.on("message", (raw) => {
      let message;
      try {
        message = JSON.parse(raw.toString());
      } catch {
        return;
      }

      if (message.type === "register") {
        peerId = message.sessionId || "default";
        role = message.role;
        const room = peers.get(peerId) || {};
        room[role] = socket;
        peers.set(peerId, room);
        socket.send(JSON.stringify({ type: "registered", sessionId: peerId }));
        return;
      }

      const room = peers.get(peerId);
      const target = role === "host" ? room?.client : room?.host;
      if (target && target.readyState === WebSocket.OPEN) {
        target.send(JSON.stringify(message));
      }
    });

    socket.on("close", () => {
      if (!peerId || !role) return;
      const room = peers.get(peerId);
      if (room?.[role] === socket) {
        delete room[role];
      }
    });
  });

  return new Promise((resolve) => {
    server.listen(PORT, "0.0.0.0", () => resolve());
  });
}

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

async function createWindow() {
  mainWindow = new BrowserWindow({
    width: 1120,
    height: 760,
    minWidth: 860,
    minHeight: 560,
    title: "Remio Like Host",
    webPreferences: {
      preload: path.join(__dirname, "preload.js"),
      contextIsolation: true,
      nodeIntegration: false
    }
  });

  session.defaultSession.setDisplayMediaRequestHandler((_request, callback) => {
    desktopCapturer.getSources({ types: ["screen", "window"] }).then((sources) => {
      callback({ video: sources[0], audio: "loopback" });
    });
  });

  await mainWindow.loadURL(`http://localhost:${PORT}/host.html`);
}

app.whenReady().then(async () => {
  await startServer();
  await createWindow();
});

app.on("window-all-closed", () => {
  if (server) server.close();
  if (process.platform !== "darwin") app.quit();
});

ipcMain.handle("host:get-network-info", () => ({
  port: PORT,
  defaultRelayUrl: DEFAULT_RELAY_URL,
  iceServers: DEFAULT_ICE_SERVERS,
  addresses: getLanAddresses()
}));
