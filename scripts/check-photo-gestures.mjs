// Browser-native contacts, not DOM dispatchEvent or callbacks. WebKit's touch protocol is
// not exposed by Playwright's public touchscreen (tap-only), so use its in-process session.
import assert from 'node:assert/strict';
import { mkdir, writeFile } from 'node:fs/promises';
import { pathToFileURL } from 'node:url';
import { checkPhotoTransitions, comparePixels } from './check-photo-transitions.mjs';

export const nativeContacts = async page => {
  const delegate = page._connection.toImpl(page).delegate;
  const session = delegate._pageProxySession || await page.context().newCDPSession(page);
  let lastPoints = [];
  return async (type, points = []) => {
    const ending = type === 'touchEnd' || type === 'touchCancel';
    const nativePoints = delegate._pageProxySession && ending ? lastPoints : points;
    await session.send('Input.dispatchTouchEvent', { type, touchPoints: nativePoints.map((p, id) => ({ id, x: Math.round(p.x), y: Math.round(p.y) })) });
    lastPoints = ending ? [] : points;
  };
};

export const drag = async (page, from, to, { cancel = false, during, steps = 10 } = {}) => {
  const send = await nativeContacts(page);
  await send('touchStart', [from]);
  for (let step = 1; step <= steps; step++) {
    await send('touchMove', [{ x: from.x + (to.x - from.x) * step / steps, y: from.y + (to.y - from.y) * step / steps }]);
    await page.waitForTimeout(20);
    if (step === Math.ceil(steps / 2) && during) await during();
  }
  await send(cancel ? 'touchCancel' : 'touchEnd');
};

export const swipePhoto = async (page, direction, options = {}) => {
  const area = page.getByLabel(/^Área ampliável de/);
  const b = await area.boundingBox(); assert(b);
  const x = direction > 0 ? .8 : .2, end = 1 - x;
  await drag(page, { x: b.x + b.width * x, y: b.y + b.height * .5 },
    { x: b.x + b.width * end, y: b.y + b.height * .5 }, options);
};

async function checkGestures(base, output) {
  assert(['127.0.0.1', 'localhost'].includes(new URL(base).hostname));
  const status = await fetch(`${base}/api/v1/status`).then(r => r.json());
  assert.equal(status.photoCount, 36); assert.equal(status.writeEnabled, false); assert.equal(status.sync.phase, 'IDLE');
  const { chromium, webkit } = await import(process.env.PLAYWRIGHT_MODULE || 'playwright');
  const engines = [[chromium, 'chromium'], [webkit, 'webkit']];
  const { items } = await fetch(`${base}/api/v1/photos?pageSize=36`).then(r => r.json());
  const start = items.findIndex(p => p.displayName === 'IMG_1025.JPG');
  const [a, b] = items.slice(start, start + 2);
  const ref = await fetch(`${base}/api/v1/photos/${a.id}`).then(r => r.json());
  ref.jpeg = Buffer.from(await fetch(`${base}${ref.previewUrl}`).then(r => r.arrayBuffer())).toString('base64');
  const results = [];
  for (const [engine, name] of engines) {
    const folder = `${output}/${name}`;
    await mkdir(folder, { recursive: true });
    const browser = await engine.launch({ headless: true });
    const context = await browser.newContext({ viewport: { width: 393, height: 680 }, hasTouch: true, isMobile: true,
      deviceScaleFactor: 1, recordVideo: { dir: folder, size: { width: 393, height: 680 } } });
    const page = await context.newPage();
    const frames = [], errors = [], writes = [], contacts = [];
    page.on('pageerror', e => errors.push(e.message));
    page.on('request', r => { if (!['GET', 'HEAD'].includes(r.method())) writes.push(`${r.method()} ${r.url()}`); });
    await page.addInitScript(() => {
      window.touchEvidence = [];
      for (const name of ['pointerdown', 'pointermove', 'pointerup', 'pointercancel', 'touchstart', 'touchend', 'touchcancel']) {
        document.addEventListener(name, e => window.touchEvidence.push({ type: e.type, trusted: e.isTrusted, pointerType: e.pointerType,
          x: e.clientX, y: e.clientY, touches: e.touches?.length, at: performance.now() }), true);
      }
    });
    const area = photo => page.getByLabel(`Área ampliável de ${photo.displayName}`, { exact: false });
    const settled = async photo => {
      await area(photo).waitFor({ timeout: 60000 });
      await page.waitForFunction(id => new URL(location.href).searchParams.get('photo') === id, photo.id);
      await page.waitForTimeout(250);
    };
    const same = async photo => {
      await page.waitForTimeout(250);
      assert.equal(await area(photo).count(), 1);
      assert.equal(new URL(page.url()).searchParams.get('photo'), photo.id);
    };
    const tap = async label => {
      const box = await page.getByRole('button', { name: label, exact: true }).boundingBox(); assert(box);
      await page.touchscreen.tap(box.x + box.width / 2, box.y + box.height / 2);
      await page.waitForTimeout(100);
    };
    const capture = async (label, photo, offset = 0) => {
      const bounds = await area(photo).boundingBox(); assert(bounds);
      const file = `${String(frames.length).padStart(2, '0')}-${label}.png`;
      const png = await page.screenshot({ path: `${folder}/${file}` });
      const pixels = photo.id === a.id ? await comparePixels(page, png, { ...bounds, x: bounds.x + offset }, ref) : null;
      if (pixels) assert(pixels.maximumError < 12, `${label}: expected photograph to follow contact ${JSON.stringify(pixels)}`);
      frames.push({ file, label, photo: photo.displayName, offset, pixels });
    };
    try {
      await page.goto(`${base}/?photo=${a.id}`); await settled(a); await capture('before-contact', a);
      const bounds = await area(a).boundingBox();
      const point = (x, y) => ({ x: bounds.x + bounds.width * x, y: bounds.y + bounds.height * y });
      const send = await nativeContacts(page);
      await send('touchStart', [point(.8, .5)]);
      await send('touchMove', [point(.68, .5)]); await page.waitForTimeout(120);
      assert.equal(new URL(page.url()).searchParams.get('photo'), a.id);
      await capture('finger-held-image-follows', a, Math.round(point(.68, .5).x) - Math.round(point(.8, .5).x));
      await send('touchMove', [point(.2, .5)]); await send('touchEnd');
      await settled(b); await capture('released-next', b);
      await swipePhoto(page, -1); await settled(a); await capture('swipe-right-back', a);
      const noPanel = async () => {
        assert.equal(await page.getByRole('button', { name: 'Fechar curadoria e metadados', exact: true }).count(), 0);
        assert.equal(await page.getByRole('button', { name: 'Fechar miniaturas', exact: true }).count(), 0);
      };
      await drag(page, point(.5, .5), point(.53, .5)); await same(a);
      await drag(page, point(.5, .7), point(.5, .3), { during: async () => {
        await page.getByLabel('Painel flutuante: Curadoria e metadados', { exact: true }).waitFor();
      } }); await same(a);
      await page.getByRole('button', { name: 'Fechar curadoria e metadados', exact: true }).waitFor();
      await page.screenshot({ path: `${folder}/swipe-up-curation.png` });
      await tap('Fechar curadoria e metadados');
      await drag(page, point(.5, .3), point(.5, .7), { during: async () => {
        await page.getByLabel('Painel flutuante: Miniaturas', { exact: true }).waitFor();
      } }); await same(a);
      await page.getByRole('button', { name: 'Fechar miniaturas', exact: true }).waitFor();
      await page.screenshot({ path: `${folder}/swipe-down-thumbnails.png` });
      await tap('Fechar miniaturas');
      await drag(page, point(.5, .5), point(.5, .52)); await same(a); await noPanel();
      await drag(page, point(.5, .7), point(.5, .3), { cancel: true }); await same(a); await noPanel();
      await send('touchStart', [point(.5, .7)]); await send('touchMove', [point(.5, .3)]);
      await send('touchMove', [point(.5, .69)]); await send('touchEnd'); await same(a); await noPanel();
      await drag(page, point(.8, .8), point(.2, .2)); await same(a); await noPanel();
      await swipePhoto(page, 1, { cancel: true }); await same(a); await capture('native-cancel-retains-photo', a);
      await send('touchStart', [point(.8, .5)]); await send('touchMove', [point(.2, .5)]);
      await send('touchMove', [point(.79, .5)]); await send('touchEnd'); await same(a);
      await tap('Aumentar zoom');
      const beforePan = await area(a).screenshot();
      await swipePhoto(page, 1); await same(a);
      assert.notDeepEqual(await area(a).screenshot(), beforePan, 'Zoomed drag must pan pixels, not merely suppress navigation');
      await drag(page, point(.5, .7), point(.5, .3)); await same(a); await noPanel();
      await drag(page, point(.5, .3), point(.5, .7)); await same(a); await noPanel();
      await tap('Ajustar fotografia à janela');
      await send('touchStart', [point(.4, .5), point(.6, .5)]);
      for (let i = 1; i <= 8; i++) { await send('touchMove', [point(.4 - .03 * i, .5), point(.6 + .03 * i, .5)]); await page.waitForTimeout(25); }
      await send('touchEnd'); await same(a);
      assert.equal(await page.getByRole('button', { name: 'Ajustar fotografia à janela', exact: true }).getAttribute('aria-disabled'), null);
      await page.screenshot({ path: `${folder}/pinch-zoom.png` });
      await tap('Ajustar fotografia à janela');
      await tap('Abrir curadoria e metadados');
      const controls = await page.getByLabel('Estado editorial', { exact: true }).boundingBox(); assert(controls);
      await drag(page, { x: controls.x + controls.width * .8, y: controls.y + controls.height / 2 },
        { x: controls.x + controls.width * .2, y: controls.y + controls.height / 2 }); await same(a);
      await tap('Fechar curadoria e metadados');
      await tap('Abrir miniaturas');
      const strip = await page.getByLabel('Faixa de fotografias próximas', { exact: true }).boundingBox(); assert(strip);
      await drag(page, { x: strip.x + strip.width * .8, y: strip.y + strip.height / 2 },
        { x: strip.x + strip.width * .2, y: strip.y + strip.height / 2 }); await same(a);
      await tap('Fechar miniaturas'); await capture('panels-did-not-navigate', a);
      for (const [photo, direction] of [[items[0], -1], [items.at(-1), 1]]) {
        contacts.push(...await page.evaluate(() => window.touchEvidence));
        await page.goto(`${base}/?photo=${photo.id}`); await settled(photo);
        await swipePhoto(page, direction); await same(photo);
      }
      await page.setViewportSize({ width: 1440, height: 1000 });
      await page.goto(`${base}/?photo=${a.id}`); await settled(a);
      const desktop = await area(a).boundingBox(); assert(desktop);
      const desktopPoint = y => ({ x: desktop.x + desktop.width / 2, y: desktop.y + desktop.height * y });
      await drag(page, desktopPoint(.7), desktopPoint(.3)); await same(a); await noPanel();
      await drag(page, desktopPoint(.3), desktopPoint(.7)); await same(a); await noPanel();
      assert(contacts.some(e => e.trusted && e.pointerType === 'touch' && e.type === 'pointermove'));
      assert.deepEqual(errors, []); assert.deepEqual(writes, []);
      results.push({ engine: name, version: browser.version(), nativeTouch: true, passed: true, errors, writes });
      console.log(`PASS gestures ${name}: native contact, in-drag pixel displacement, release, cancel, vertical, short, pinch, pan, overlays and bounds`);
    } catch (error) {
      await page.screenshot({ path: `${folder}/FAILED.png` });
      await writeFile(`${folder}/FAILED.aria.txt`, await page.locator('body').ariaSnapshot());
      throw error;
    } finally {
      await writeFile(`${folder}/sequence.json`, JSON.stringify({ frames, contacts: [...contacts, ...await page.evaluate(() => window.touchEvidence)], errors, writes }, null, 2));
      await context.close(); await browser.close();
      await writeFile(`${output}/gesture-results.json`, JSON.stringify({ results, physicalIPhoneTested: false }, null, 2));
    }
  }
  await checkPhotoTransitions({ base, output: `${output}/transitions`, engines, navigate: swipePhoto });
}
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  await checkGestures(process.argv[2] || 'http://127.0.0.1:8898', process.argv[3] || '/tmp/phototool-swipe-20261115/browser');
}
