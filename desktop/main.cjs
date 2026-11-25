const { app, BrowserWindow, Menu, clipboard, dialog, session, shell } = require("electron");
const fs = require("node:fs");
const path = require("node:path");
const { serverOrigin, accountLink } = require("./server.cjs");

app.setName("Soleil");
app.setAppUserModelId("org.soleil.community");

let window;
let origin;
const logo = path.join(__dirname, "assets", "logo.png");

function configuration() {
  const argument = process.argv.find(value => value.startsWith("--server="))?.slice("--server=".length);
  if (argument || process.env.SOLEIL_SERVER) return serverOrigin(argument || process.env.SOLEIL_SERVER);
  const config = path.join(app.getPath("userData"), "server.json");
  if (!fs.existsSync(config)) return serverOrigin();
  return serverOrigin(JSON.parse(fs.readFileSync(config, "utf8")).server);
}

function isSoleil(url) {
  try { return new URL(url).origin === origin; }
  catch { return false; }
}

async function show(url = origin) {
  try {
    await window.loadURL(url);
  } catch (error) {
    if (error.code !== "ERR_ABORTED" && window && !window.isDestroyed()) {
      window.setTitle("Soleil");
      await dialog.showMessageBox(window, {
        type: "warning", title: "Soleil is offline", message: "Soleil could not reach its server.",
        detail: "Check that your Soleil server is running and choose Soleil → Reconnect.",
      });
    }
  }
}

function installMenu() {
  Menu.setApplicationMenu(Menu.buildFromTemplate([
    { label: "Soleil", submenu: [
      { label: "Home", click: () => void show() },
      { label: "Reconnect", accelerator: "CmdOrCtrl+R", click: () => void show() },
      { label: "Open copied verification or reset link", click: () => {
        try { void show(accountLink(clipboard.readText(), origin)); }
        catch (error) { dialog.showMessageBox(window, { type: "warning", title: "Soleil", message: error.message }); }
      } },
      { type: "separator" },
      { role: "quit" },
    ] },
    { role: "editMenu" },
    { role: "viewMenu", submenu: [{ role: "resetZoom" }, { role: "zoomIn" }, { role: "zoomOut" }, { role: "togglefullscreen" }] },
  ]));
}

function createWindow() {
  const profile = session.fromPartition("persist:soleil");
  profile.setPermissionCheckHandler(() => false);
  profile.setPermissionRequestHandler((_contents, _permission, reply) => reply(false));
  window = new BrowserWindow({
    title: "Soleil", icon: logo, width: 1320, height: 900, minWidth: 390, minHeight: 560,
    backgroundColor: "#fbf3db",
    webPreferences: { session: profile, contextIsolation: true, sandbox: true, nodeIntegration: false, webviewTag: false },
  });
  window.webContents.on("will-navigate", (event, url) => { if (!isSoleil(url)) event.preventDefault(); });
  window.webContents.on("will-redirect", (event, url) => { if (!isSoleil(url)) event.preventDefault(); });
  window.webContents.on("will-attach-webview", event => event.preventDefault());
  window.webContents.setWindowOpenHandler(({ url, disposition }) => {
    try {
      const target = new URL(url);
      if (disposition !== "new-window" && target.protocol === "https:" && !target.username && !target.password) {
        void shell.openExternal(target.href).catch(() => {});
      }
    } catch {
      // Invalid links and non-browser protocols remain blocked.
    }
    return { action: "deny" };
  });
  installMenu();
  void show();
}

if (!app.requestSingleInstanceLock()) {
  app.quit();
} else {
  app.on("second-instance", () => {
    if (!window || window.isDestroyed()) return;
    if (window.isMinimized()) window.restore();
    window.focus();
  });
  app.whenReady().then(() => {
    try {
      origin = configuration();
      createWindow();
    } catch (error) {
      dialog.showErrorBox("Soleil server configuration", error.message);
      app.quit();
    }
  });
  app.on("window-all-closed", () => app.quit());
}
