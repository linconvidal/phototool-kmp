import { spawn } from "node:child_process";
import { readFile, writeFile } from "node:fs/promises";
import process from "node:process";

const [url, output, widthText, heightText, profile] = process.argv.slice(2);
if (!url || !output || !widthText || !heightText || !profile) {
  throw new Error("Usage: capture-mobile-web.mjs URL OUTPUT WIDTH HEIGHT PROFILE");
}
const width = Number(widthText);
const height = Number(heightText);
if (!Number.isInteger(width) || !Number.isInteger(height) || width < 320 || height < 320 || width > 4096 || height > 4096) {
  throw new Error("Viewport is invalid");
}

const chrome = spawn("/usr/bin/google-chrome", [
  "--headless=new",
  "--disable-gpu",
  "--hide-scrollbars",
  "--no-first-run",
  "--no-default-browser-check",
  "--remote-debugging-address=127.0.0.1",
  "--remote-debugging-port=0",
  `--user-data-dir=${profile}`,
  "about:blank",
], { stdio: ["ignore", "inherit", "inherit"] });
console.log(`mobile-web-evidence-chrome pid=${chrome.pid}`);

const sleep = (milliseconds) => new Promise((resolve) => setTimeout(resolve, milliseconds));

async function devToolsPort() {
  const target = `${profile}/DevToolsActivePort`;
  for (let attempt = 0; attempt < 100; attempt += 1) {
    try {
      const lines = (await readFile(target, "utf8")).trim().split("\n");
      if (lines[0]) return Number(lines[0]);
    } catch {
      // Chrome publishes the file after its profile and debugger are ready.
    }
    await sleep(50);
  }
  throw new Error("Chrome DevTools port was not published");
}

async function openTarget(port) {
  const response = await fetch(`http://127.0.0.1:${port}/json/new?${encodeURIComponent(url)}`, { method: "PUT" });
  if (!response.ok) throw new Error(`Could not create DevTools target: ${response.status}`);
  return response.json();
}

function protocol(webSocketUrl) {
  const socket = new WebSocket(webSocketUrl);
  let sequence = 0;
  const pending = new Map();
  const opened = new Promise((resolve, reject) => {
    socket.addEventListener("open", resolve, { once: true });
    socket.addEventListener("error", reject, { once: true });
  });
  socket.addEventListener("message", (event) => {
    const message = JSON.parse(event.data);
    if (!message.id) return;
    const request = pending.get(message.id);
    if (!request) return;
    pending.delete(message.id);
    if (message.error) request.reject(new Error(message.error.message));
    else request.resolve(message.result);
  });
  const send = async (method, params = {}) => {
    await opened;
    const id = ++sequence;
    const result = new Promise((resolve, reject) => pending.set(id, { resolve, reject }));
    socket.send(JSON.stringify({ id, method, params }));
    return result;
  };
  return { socket, send };
}

async function waitUntilRendered(send) {
  const expression = `(() => {
    const collect = root => [...root.querySelectorAll('*')].flatMap(element => [element, ...(element.shadowRoot ? collect(element.shadowRoot) : [])]);
    const elements = collect(document);
    const labels = elements.map(element => element.getAttribute('aria-label') || '');
    const requestedDetail = new URL(location.href).searchParams.has('photo');
    const detailReady = !requestedDetail || labels.includes('Voltar à biblioteca');
    return elements.some(element => element.tagName === 'CANVAS') && labels.some(label => label.includes('Prévia de ')) && !labels.some(label => label.includes('Carregando prévia')) && detailReady;
  })()`;
  for (let attempt = 0; attempt < 400; attempt += 1) {
    const result = await send("Runtime.evaluate", { expression, returnByValue: true });
    if (result.result?.value === true) return;
    await sleep(100);
  }
  const diagnostic = await send("Runtime.evaluate", {
    expression: `JSON.stringify({
      startup: document.querySelector('#startup')?.textContent,
      title: document.title,
      url: location.href,
    })`,
    returnByValue: true,
  });
  console.error(`mobile-web-evidence-diagnostic ${diagnostic.result?.value}`);
  throw new Error("PhotoTool page did not finish rendering bounded previews");
}

let client;
try {
  const port = await devToolsPort();
  const target = await openTarget(port);
  client = protocol(target.webSocketDebuggerUrl);
  await client.send("Runtime.enable");
  await client.send("Page.enable");
  await client.send("Emulation.setDeviceMetricsOverride", {
    width,
    height,
    deviceScaleFactor: 1,
    mobile: width <= 500,
    screenWidth: width,
    screenHeight: height,
  });
  await client.send("Page.navigate", { url });
  await waitUntilRendered(client.send);
  await sleep(500);
  await client.send("Runtime.evaluate", {
    expression: `Promise.all([...document.images].filter(image => image.src).map(image => image.decode())).then(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))))`,
    awaitPromise: true,
    returnByValue: true,
  });
  await client.send("Page.bringToFront");
  const screenshot = await client.send("Page.captureScreenshot", {
    format: "png",
    fromSurface: true,
    captureBeyondViewport: false,
  });
  await writeFile(output, Buffer.from(screenshot.data, "base64"));
  console.log(`mobile-web-evidence-written output=${output} viewport=${width}x${height}`);
} finally {
  if (client) {
    try { await Promise.race([client.send("Browser.close"), sleep(1000)]); } catch { /* The browser may close before acknowledging. */ }
    client.socket.close();
  }
  const exited = chrome.exitCode !== null || await Promise.race([
    new Promise((resolve) => chrome.once("exit", () => resolve(true))),
    sleep(5000).then(() => false),
  ]);
  if (!exited) chrome.kill("SIGTERM");
}
