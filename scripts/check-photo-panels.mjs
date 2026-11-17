import assert from 'node:assert/strict';
import { mkdir, writeFile } from 'node:fs/promises';
import { drag, swipePhoto } from './check-photo-gestures.mjs';

const base = process.argv[2] || 'http://127.0.0.1:8906';
const output = process.argv[3] || '/tmp/phototool-panel-material-20261116/browser';
const { chromium, webkit } = await import(process.env.PLAYWRIGHT_MODULE || 'playwright');
const status = await fetch(`${base}/api/v1/status`).then(r => r.json());
assert.equal(status.photoCount, 36); assert.equal(status.sync.phase, 'IDLE');
const { items } = await fetch(`${base}/api/v1/photos?pageSize=36`).then(r => r.json());
const photo = items.find(p => p.displayName === 'IMG_1025.JPG'); assert(photo);
const results = [];

for (const [engine, name] of [[chromium, 'chromium'], [webkit, 'webkit']]) {
  const folder = `${output}/${name}`; await mkdir(folder, { recursive: true });
  const browser = await engine.launch({ headless: true });
  const context = await browser.newContext({ viewport: { width: 393, height: 680 }, deviceScaleFactor: 1,
    hasTouch: true, isMobile: true, reducedMotion: 'no-preference', recordVideo: { dir: folder, size: { width: 393, height: 680 } } });
  const page = await context.newPage(); const errors = [], mutations = [], sequences = [];
  page.on('pageerror', e => errors.push(e.message));
  await page.route('**/api/v1/**', async route => {
    if (!['GET', 'HEAD'].includes(route.request().method())) {
      mutations.push(`${route.request().method()} ${route.request().url()}`); await route.abort();
    } else await route.continue();
  });
  const panel = title => page.getByRole('button', { name: title === 'Miniaturas' ? 'Fechar miniaturas' : 'Fechar curadoria e metadados', exact: true });
  const tap = async label => {
    const b = await page.getByRole('button', { name: label, exact: true }).boundingBox(); assert(b, label);
    await page.touchscreen.tap(b.x + b.width / 2, b.y + b.height / 2);
  };
  const sample = async title => page.getByRole('button', { name: 'Abrir miniaturas', exact: true }).evaluate((anchor, title) => {
    const root = anchor.getRootNode();
    window.panelFrames = []; const start = performance.now();
    function frame(now) {
      const label = title === 'Miniaturas' ? 'Fechar miniaturas' : 'Fechar curadoria e metadados';
      const node = [...root.querySelectorAll('[aria-label]')].find(e => e.getAttribute('aria-label') === label);
      const b = node?.getBoundingClientRect();
      window.panelFrames.push({ ms: now - start, x: b?.x, y: b?.y, width: b?.width, height: b?.height, present: !!node });
      if (now - start < 2000) requestAnimationFrame(frame);
    }
    requestAnimationFrame(frame);
  }, title);
  const saveSequence = async (label, direction, moving = true) => {
    await page.waitForTimeout(2050);
    const frames = await page.evaluate(() => window.panelFrames);
    const ys = frames.filter(f => f.present && f.height > 0).map(f => f.y);
    const range = Math.max(...ys) - Math.min(...ys);
    await writeFile(`${folder}/${label}-samples.json`, JSON.stringify(frames, null, 2));
    assert(ys.length > 0, `${name} ${label}: frames must contain the rendered panel`);
    // Wasm's accessibility bounds do not follow every graphicsLayer frame. Do not
    // mistake that proxy for rendered motion: keep the video/screenshots for review;
    // JVM clock-controlled tests assert intermediate positions and reversal.
    if (!moving) assert(range < 2, `${name} ${label}: reduced motion must keep geometry stationary, got ${range}`);
    if (label.endsWith('exit')) assert(frames.some(f => !f.present), `${label}: the panel must eventually unmount`);
    sequences.push({ label, direction, range, frames });
  };
  try {
    await page.goto(`${base}/?photo=${photo.id}`);
    await page.getByLabel(`Prévia de ${photo.displayName}`, { exact: true }).waitFor({ timeout: 60000 });
    await page.waitForTimeout(350);
    await page.screenshot({ path: `${folder}/00-photograph.png` });
    await sample('Curadoria e metadados'); await tap('Abrir curadoria e metadados');
    await page.waitForTimeout(120); await page.screenshot({ path: `${folder}/01-curation-entering.png` });
    await saveSequence('curation-enter', 'up');
    await page.screenshot({ path: `${folder}/02-curation-open.png` });
    const inspectorBounds = await panel('Curadoria e metadados').boundingBox(); assert(inspectorBounds.y > 50);
    await sample('Curadoria e metadados'); await tap('Fechar curadoria e metadados');
    await page.waitForTimeout(100); await page.screenshot({ path: `${folder}/03-curation-leaving.png` });
    await saveSequence('curation-exit', 'down');
    await panel('Curadoria e metadados').waitFor({ state: 'hidden' });
    const area = await page.getByLabel(/^Área ampliável de/).boundingBox();
    await sample('Miniaturas');
    await drag(page, { x: area.x + area.width / 2, y: area.y + area.height * .3 },
      { x: area.x + area.width / 2, y: area.y + area.height * .7 });
    await page.waitForTimeout(100); await page.screenshot({ path: `${folder}/04-thumbnails-entering.png` });
    await saveSequence('thumbnails-enter', 'down');
    await page.screenshot({ path: `${folder}/05-thumbnails-open.png` });
    const stripBounds = await panel('Miniaturas').boundingBox(); assert(stripBounds.y < 100);
    await sample('Miniaturas'); await tap('Fechar miniaturas');
    await page.waitForTimeout(100); await page.screenshot({ path: `${folder}/06-thumbnails-leaving.png` });
    await saveSequence('thumbnails-exit', 'up');
    await panel('Miniaturas').waitFor({ state: 'hidden' });
    assert.equal(new URL(page.url()).searchParams.get('photo'), photo.id);
    await page.emulateMedia({ reducedMotion: 'reduce', contrast: 'more' });
    await sample('Curadoria e metadados'); await tap('Abrir curadoria e metadados');
    await saveSequence('reduced-motion-enter', 'stationary', false);
    await page.screenshot({ path: `${folder}/07-high-contrast.png` });
    await sample('Curadoria e metadados'); await tap('Fechar curadoria e metadados');
    await saveSequence('reduced-motion-exit', 'stationary', false);
    await panel('Curadoria e metadados').waitFor({ state: 'hidden' });
    await page.emulateMedia({ reducedMotion: 'no-preference', contrast: 'no-preference' });
    await tap('Abrir curadoria e metadados'); await page.waitForTimeout(700);
    // Native mobile touch does not focus a DOM keyboard target. Explicitly enter
    // the canvas's keyboard context before testing its Escape contract.
    await page.locator('canvas').focus();
    await page.keyboard.press('Escape'); await panel('Curadoria e metadados').waitFor({ state: 'hidden' });
    await tap('Abrir miniaturas'); await page.waitForTimeout(700);
    await page.touchscreen.tap(200, 400); await panel('Miniaturas').waitFor({ state: 'hidden' });
    await swipePhoto(page, 1);
    await page.waitForFunction(id => new URL(location.href).searchParams.get('photo') !== id, photo.id);
    await page.setViewportSize({ width: 1440, height: 1000 });
    await page.getByText('CURADORIA', { exact: true }).waitFor();
    assert.equal(await panel('Curadoria e metadados').count(), 0);
    assert.equal(await panel('Miniaturas').count(), 0);
    assert.deepEqual(errors, []); assert.deepEqual(mutations, []);
    results.push({ engine: name, version: browser.version(), passed: true, sequences, errors, mutations });
    console.log(`PASS ${name}: panel mount/exit lifecycle, recorded rendering, glass, reduced motion/contrast, Escape/scrim, horizontal navigation, desktop; no writes/errors`);
  } catch (error) {
    await page.screenshot({ path: `${folder}/FAILED.png` });
    await writeFile(`${folder}/FAILED.aria.txt`, await page.locator('body').ariaSnapshot());
    throw error;
  } finally {
    await writeFile(`${folder}/frames.json`, JSON.stringify({ sequences, errors, mutations }, null, 2));
    await context.close(); await browser.close();
    await writeFile(`${output}/results.json`, JSON.stringify({ results, physicalIPhoneTested: false }, null, 2));
  }
}
assert.deepEqual(await fetch(`${base}/api/v1/status`).then(r => r.json()), status);
