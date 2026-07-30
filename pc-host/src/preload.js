const { contextBridge, ipcRenderer } = require("electron");

contextBridge.exposeInMainWorld("hostApi", {
  getNetworkInfo: () => ipcRenderer.invoke("host:get-network-info")
});
