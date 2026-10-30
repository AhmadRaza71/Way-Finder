const { app, BrowserWindow, session, ipcMain, shell } = require('electron');
const fs = require('fs'), path = require('path');

const cfg = path.join(app.getPath('userData'), 'config.json');
const readKey = () => { try { return JSON.parse(fs.readFileSync(cfg, 'utf8')).key || ''; } catch { return ''; } };
// Electron's built-in geolocation needs a Google key (enable "Geolocation API" on it).
if (readKey()) process.env.GOOGLE_API_KEY = readKey();

ipcMain.handle('key:get', () => readKey());
ipcMain.handle('key:set', (_e, k) => {
  fs.mkdirSync(path.dirname(cfg), { recursive: true });
  fs.writeFileSync(cfg, JSON.stringify({ key: String(k).trim() }));
  app.relaunch(); app.exit(0);
});
// Routes API is called from the main process so the browser's CORS rules don't get in the way.
ipcMain.handle('routes', async (_e, body, mask) => {
  const r = await fetch('https://routes.googleapis.com/directions/v2:computeRoutes', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-Goog-Api-Key': readKey(), 'X-Goog-FieldMask': mask },
    body: JSON.stringify(body)
  });
  return r.json();
});

function createWindow() {
  const win = new BrowserWindow({
    width: 1360, height: 860, minWidth: 820, minHeight: 560, title: 'Wayfinder',
    backgroundColor: '#0c0d10', autoHideMenuBar: true, icon: path.join(__dirname, 'build/icon.png'),
    webPreferences: { preload: path.join(__dirname, 'preload.js'), contextIsolation: true }
  });
  win.webContents.setWindowOpenHandler(({ url }) => { shell.openExternal(url); return { action: 'deny' }; });
  win.loadFile('index.html');
}
app.whenReady().then(() => {
  session.defaultSession.setPermissionRequestHandler((_w, p, cb) => cb(p === 'geolocation'));
  createWindow();
});
app.on('window-all-closed', () => app.quit());
