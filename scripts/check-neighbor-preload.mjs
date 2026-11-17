// Compare a fully warmed pair while every subsequent API/preview response is held.
// Uses the production Compose renderer and checks pixels, not just accessible labels.
import assert from 'node:assert/strict';
import { mkdir, writeFile } from 'node:fs/promises';
import { comparePixels } from './check-photo-transitions.mjs';
import { swipePhoto } from './check-photo-gestures.mjs';
const { chromium, webkit } = await import(process.env.PLAYWRIGHT_MODULE || 'playwright');
const [base, output, mode = 'fixed'] = process.argv.slice(2);
assert.equal(new URL(base).hostname, '127.0.0.1');
const status = await fetch(`${base}/api/v1/status`).then(r => r.json());
assert.equal(status.photoCount, 36); assert.equal(status.sync.phase, 'IDLE');
const { items } = await fetch(`${base}/api/v1/photos?pageSize=36`).then(r => r.json());
const center = items.find(p => p.displayName === 'IMG_1024.JPG');
const detail = id => fetch(`${base}/api/v1/photos/${id}`).then(r => r.json());
const middle = await detail(center.id);
const refs = {};
for (const id of [middle.id, middle.previousId, middle.nextId]) {
  const photo = await detail(id);
  refs[id] = { ...photo, jpeg: Buffer.from(await fetch(`${base}${photo.previewUrl}`).then(r => r.arrayBuffer())).toString('base64') };
}
const results = [];
for (const [engine, name] of [[chromium, 'chromium'], [webkit, 'webkit']]) {
  const browser = await engine.launch({ headless: true });
  try {
    for (const direction of [-1, 1]) {
      const folder = `${output}/${name}-${direction < 0 ? 'previous' : 'next'}`;
      await mkdir(folder, { recursive: true });
      const context = await browser.newContext({ viewport: { width: 393, height: 680 }, hasTouch: true,
        isMobile: true, deviceScaleFactor: 1, recordVideo: { dir: folder, size: { width: 393, height: 680 } } });
      const page = await context.newPage();
      const requests = [], finished = [], responses = [], errors = [], writes = [], frames = [];
      let held = false, release;
      const gate = new Promise(resolve => { release = resolve; });
      page.on('pageerror', e => errors.push(e.message));
      page.on('request', r => {
        if (!['GET', 'HEAD'].includes(r.method())) writes.push(r.url());
        if (r.url().includes('/api/')) requests.push({ url: r.url(), at: Date.now(), held });
      });
      page.on('requestfinished', r => finished.push(r.url()));
      page.on('response', r => { if (r.url().includes('/api/')) responses.push({ url: r.url(), status: r.status() }); });
      await page.route('**/api/v1/**', async route => {
        if (held) await gate;
        // The baseline's parallel detail reads race the server projection lock (recorded
        // separately as 409). Stagger only its initial preparation to isolate warm latency.
        if (mode === 'baseline' && !held && new URL(route.request().url()).pathname === `/api/v1/photos/${middle.nextId}`) {
          await page.waitForTimeout(180);
        }
        try { await route.continue(); } catch (e) { if (!/closed|handled|canceled|cancelled|disposed/i.test(e.message)) throw e; }
      });
      const area = photo => page.getByLabel(`Área ampliável de ${photo.displayName}`, { exact: false });
      const capture = async (label, photo) => {
        const bounds = await area(photo).boundingBox(); assert(bounds, `Expected ${photo.displayName}`);
        const file = `${label}.png`;
        const png = await page.screenshot({ path: `${folder}/${file}` });
        const pixels = await comparePixels(page, png, bounds, refs[photo.id]);
        assert(pixels.maximumError < 12, `${label}: mismatched pixels ${JSON.stringify(pixels)}`);
        assert.equal(await page.getByText('Carregando fotografia', { exact: true }).count(), 0);
        frames.push({ label, photo: photo.displayName, pixels, at: Date.now() });
        await writeFile(`${folder}/${label}.aria.txt`, await page.locator('body').ariaSnapshot());
      };
      try {
        await page.goto(`${base}/?photo=${middle.id}`);
        await area(middle).waitFor({ timeout: 60000 });
        for (let n = 0; n < 300; n++) {
          if (Object.keys(refs).every(id => finished.some(url => url.includes(`/photos/${id}/preview?max=1600&`)))) break;
          await page.waitForTimeout(50);
        }
        assert(Object.keys(refs).every(id => finished.some(url => url.includes(`/photos/${id}/preview?max=1600&`))), 'Both neighbors must finish preloading before measurement');
        await page.waitForTimeout(300);
        await capture('warmed-center', middle);
        const initialIds = [...new Set(requests.filter(r => /\/photos\/[a-z0-9_-]+\?/.test(r.url)).map(r => new URL(r.url).pathname))];
        assert(initialIds.length <= 3, 'Speculation must not recurse');
        held = true;
        const target = refs[direction < 0 ? middle.previousId : middle.nextId];
        const started = Date.now();
        await swipePhoto(page, direction);
        const inputFinished = Date.now();
        const appearedWhileHeld = await area(target).waitFor({ timeout: 900 }).then(() => true, () => false);
        const observedMs = Date.now() - inputFinished;
        assert.equal(appearedWhileHeld, mode === 'fixed');
        await page.waitForTimeout(300); // Let the existing swipe-return spring settle for pixel comparison.
        await capture('all-network-held', appearedWhileHeld ? target : middle);
        if (mode === 'fixed') {
          // Immediate reversal must reuse the former center even before permission validation returns.
          await swipePhoto(page, -direction); await area(middle).waitFor({ timeout: 900 });
          await page.waitForTimeout(300); await capture('reversed-network-held', middle);
          await swipePhoto(page, direction); await area(target).waitFor({ timeout: 900 });
          await page.waitForTimeout(300); await capture('forward-again-network-held', target);
        }
        const heldRequests = requests.filter(r => r.held);
        if (mode === 'fixed') {
          assert.equal(heldRequests.filter(r => new URL(r.url).pathname === `/api/v1/photos/${target.id}`).length, 0, 'Prepared detail should not be downloaded again');
          assert.equal(heldRequests.filter(r => r.url.includes(`/photos/${target.id}/preview?max=1600&`)).length, 0, 'Prepared JPEG should not be fetched again');
        }
        if (mode === 'fixed' && status.writeEnabled) {
          const tap = async label => {
            const box = await page.getByLabel(label, { exact: true }).boundingBox(); assert(box);
            await page.touchscreen.tap(box.x + box.width / 2, box.y + box.height / 2);
          };
          await tap('Abrir curadoria e metadados');
          await page.getByLabel('Painel de curadoria da fotografia', { exact: true }).waitFor();
          await tap('Marcar como escolhida');
          await page.waitForTimeout(150);
          assert.deepEqual(writes, [], 'A warmed pair cannot reuse prior write permission while validation is held');
          await tap('Fechar curadoria e metadados');
        }
        held = false; release();
        await area(target).waitFor({ timeout: 30000 });
        if (status.writeEnabled) await page.getByText('Edição XMP · Snapshot READY', { exact: true }).waitFor();
        await page.waitForTimeout(300); await capture('released', target);
        assert.deepEqual(errors, []); assert.deepEqual(writes, []);
        results.push({ engine: name, version: browser.version(), direction, mode, appearedWhileHeld, observedMs,
          inputMs: inputFinished - started, initialIds, heldRequests, frames, passed: true });
        console.log(`PASS ${mode} ${name} ${direction}: presented before network release=${appearedWhileHeld}, observation=${observedMs}ms`);
      } catch (e) {
        await page.screenshot({ path: `${folder}/FAILED.png` });
        await writeFile(`${folder}/FAILED.aria.txt`, await page.locator('body').ariaSnapshot());
        throw e;
      } finally {
        held = false; release();
        await writeFile(`${folder}/sequence.json`, JSON.stringify({ requests, finished, responses, frames, errors, writes }, null, 2));
        await context.close();
      }
    }
  } finally {
    await browser.close();
    await writeFile(`${output}/results.json`, JSON.stringify({ results, physicalIPhoneTested: false }, null, 2));
  }
}
