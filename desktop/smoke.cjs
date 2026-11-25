const { _electron: electron } = require("../frontend/node_modules/playwright");
const assert = require("node:assert/strict");
const { createServer } = require("node:http");

(async () => {
  const server = createServer((req, res) => {
    res.setHeader("Content-Type", "text/html");
    res.end('<!doctype html><title>Soleil</title><h1>Soleil</h1><a href="http://example.test/">Outside</a>');
  });
  await new Promise(resolve => server.listen(0, "127.0.0.1", resolve));
  const origin = "http://127.0.0.1:" + server.address().port;
  let app;
  try {
    const packaged = process.env.SOLEIL_PACKAGED_EXE;
    app = await electron.launch({
      executablePath: packaged || require("electron"),
      args: packaged ? ["--server=" + origin] : [__dirname, "--server=" + origin],
    });
    const view = await app.firstWindow();
    await view.getByRole("heading", { name: "Soleil" }).waitFor();
    assert.equal(await view.evaluate(() => typeof require), "undefined");
    await app.evaluate(({ BrowserWindow }) => {
      const contents = BrowserWindow.getAllWindows()[0].webContents;
      globalThis.blockedNavigation = new Promise(resolve => {
        contents.once("will-navigate", event => resolve(event.defaultPrevented));
      });
    });
    await view.getByRole("link", { name: "Outside" }).evaluate(link => link.click());
    assert.equal(await app.evaluate(() => globalThis.blockedNavigation), true);
    assert.equal(view.url(), origin + "/");
    console.log("Soleil Electron window loaded its server and kept navigation inside the configured origin.");
  } finally {
    await app?.close();
    server.close();
  }
})().catch(error => { console.error(error); process.exitCode = 1; });
