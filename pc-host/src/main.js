const { app, BrowserWindow, shell, session } = require("electron");

const DASHBOARD_URL = process.env.LCD_DASHBOARD_URL || "https://remote-4617.onrender.com/dashboard.html";
const DASHBOARD_VERSION = "stable-064-mouse";

let mainWindow;

async function createWindow() {
  mainWindow = new BrowserWindow({
    width: 1280,
    height: 820,
    minWidth: 960,
    minHeight: 640,
    title: "LCD Dashboard",
    webPreferences: {
      contextIsolation: true,
      nodeIntegration: false
    }
  });

  mainWindow.webContents.setWindowOpenHandler(({ url }) => {
    shell.openExternal(url);
    return { action: "deny" };
  });

  await session.defaultSession.clearCache();
  await mainWindow.loadURL(withCacheBust(DASHBOARD_URL));
}

function withCacheBust(url) {
  const separator = url.includes("?") ? "&" : "?";
  return `${url}${separator}v=${DASHBOARD_VERSION}`;
}

app.whenReady().then(createWindow);

app.on("window-all-closed", () => {
  if (process.platform !== "darwin") app.quit();
});
