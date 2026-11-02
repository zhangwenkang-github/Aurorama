import { spawn } from "node:child_process";
import { mkdtemp, rm } from "node:fs/promises";
import { createServer } from "node:net";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const edge = "C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe";
const root = dirname(fileURLToPath(import.meta.url));
const profile = await mkdtemp(join(tmpdir(), "aurorama-dom-audit-"));

const pages = [
  { name: "reference", html: "reference-board.html", query: "", width: 1600, height: 1800 },
  { name: "overview", html: "directions-overview.html", query: "", width: 1600, height: 1300 },
  { name: "proof-polar", html: "proof.html", query: "direction=polar", width: 1600, height: 1100 },
  { name: "proof-ridge", html: "proof.html", query: "direction=ridge", width: 1600, height: 1100 },
  { name: "proof-tail", html: "proof.html", query: "direction=tail", width: 1600, height: 1100 },
  { name: "qa-contact-sheet", html: "logo-proof-contact-sheet.html", query: "", width: 1600, height: 1200 },
];

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

async function getFreePort() {
  return new Promise((resolve, reject) => {
    const server = createServer();
    server.once("error", reject);
    server.listen(0, "127.0.0.1", () => {
      const address = server.address();
      const port = address.port;
      server.close(() => resolve(port));
    });
  });
}

async function waitForDebugger(port) {
  const endpoint = `http://127.0.0.1:${port}/json/version`;
  for (let attempt = 0; attempt < 100; attempt += 1) {
    try {
      const response = await fetch(endpoint);
      if (response.ok) {
        return;
      }
    } catch {
      // Browser startup is still in progress.
    }
    await sleep(60);
  }
  throw new Error("Edge remote debugging endpoint did not become ready.");
}

async function createTarget(port) {
  const response = await fetch(`http://127.0.0.1:${port}/json/new?about:blank`, {
    method: "PUT",
  });
  if (!response.ok) {
    throw new Error(`Unable to create Edge target: ${response.status}`);
  }
  return response.json();
}

class CdpSession {
  constructor(webSocketUrl) {
    this.socket = new WebSocket(webSocketUrl);
    this.nextId = 1;
    this.pending = new Map();
    this.opened = new Promise((resolve, reject) => {
      this.socket.addEventListener("open", resolve, { once: true });
      this.socket.addEventListener("error", reject, { once: true });
    });
    this.socket.addEventListener("message", (event) => {
      const message = JSON.parse(event.data);
      if (!message.id || !this.pending.has(message.id)) {
        return;
      }
      const { resolve, reject } = this.pending.get(message.id);
      this.pending.delete(message.id);
      if (message.error) {
        reject(new Error(message.error.message));
      } else {
        resolve(message.result);
      }
    });
  }

  async send(method, params = {}) {
    await this.opened;
    const id = this.nextId;
    this.nextId += 1;
    const promise = new Promise((resolve, reject) => {
      this.pending.set(id, { resolve, reject });
    });
    this.socket.send(JSON.stringify({ id, method, params }));
    return promise;
  }

  close() {
    this.socket.close();
  }
}

const port = await getFreePort();
const browser = spawn(
  edge,
  [
    "--headless=new",
    "--disable-gpu",
    "--hide-scrollbars",
    "--no-first-run",
    "--disable-extensions",
    "--force-device-scale-factor=1",
    `--remote-debugging-port=${port}`,
    `--user-data-dir=${profile}`,
    "about:blank",
  ],
  {
    stdio: "ignore",
    windowsHide: true,
  },
);

const results = [];
try {
  await waitForDebugger(port);
  for (const page of pages) {
    const target = await createTarget(port);
    const session = new CdpSession(target.webSocketDebuggerUrl);
    try {
      await session.send("Runtime.enable");
      await session.send("Page.enable");
      await session.send("Emulation.setDeviceMetricsOverride", {
        width: page.width,
        height: page.height,
        deviceScaleFactor: 1,
        mobile: false,
      });
      const baseUrl = new URL(`file:///${join(root, page.html).replaceAll("\\", "/")}`);
      if (page.query) {
        baseUrl.search = page.query;
      }
      await session.send("Page.navigate", { url: baseUrl.href });

      let status = null;
      for (let attempt = 0; attempt < 120; attempt += 1) {
        const evaluation = await session.send("Runtime.evaluate", {
          expression: "document.body?.dataset.renderStatus || null",
          returnByValue: true,
        });
        const value = evaluation.result?.value;
        if (value) {
          status = JSON.parse(value);
          break;
        }
        await sleep(50);
      }

      if (!status) {
        const diagnostic = await session.send("Runtime.evaluate", {
          expression: "({url: location.href, ready: document.readyState, body: !!document.body})",
          returnByValue: true,
        });
        results.push({
          name: page.name,
          ready: false,
          detail: `render status timeout; ${JSON.stringify(diagnostic.result?.value)}`,
        });
      } else {
        results.push({
          name: page.name,
          ready: status.ok === true,
          detail: JSON.stringify(status),
        });
      }
    } finally {
      session.close();
      await fetch(`http://127.0.0.1:${port}/json/close/${target.id}`).catch(() => undefined);
    }
  }
} finally {
  browser.kill();
  await sleep(250);
  await rm(profile, { recursive: true, force: true }).catch(() => undefined);
}

process.stdout.write(JSON.stringify(results));
if (results.some((result) => !result.ready)) {
  process.exitCode = 1;
}
