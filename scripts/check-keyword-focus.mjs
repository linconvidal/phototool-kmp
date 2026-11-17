// iPhone-profile browser regression; no physical software keyboard is emulated here.
import assert from 'node:assert/strict';
import { mkdir, writeFile } from 'node:fs/promises';
import { drag, swipePhoto } from './check-photo-gestures.mjs';
const { chromium, webkit, devices } = await import(process.env.PLAYWRIGHT_MODULE || 'playwright');
const [base = 'http://127.0.0.1:8906', output = '/tmp/phototool-keyword-focus', mode = 'readonly'] = process.argv.slice(2);
const save = mode === 'fixture-write';
if (save) assert.equal(new URL(base).hostname, '127.0.0.1', 'Writes only to the isolated loopback fixture');
const status = await fetch(`${base}/api/v1/status`).then(r => r.json());
assert.equal(status.photoCount, 36); assert.equal(status.sync.phase, 'IDLE');
if (save) assert.equal(status.writeEnabled, true);
const { items } = await fetch(`${base}/api/v1/photos?pageSize=36`).then(r => r.json());
const photo = items.find(p => p.displayName === 'IMG_1025.JPG'); assert(photo);
const detail = () => fetch(`${base}/api/v1/photos/${photo.id}`).then(r => r.json());
const results = [];
await mkdir(output, { recursive: true });
for (const [engine, name] of [[chromium, 'chromium'], [webkit, 'webkit']]) {
  const browser = await engine.launch();
  const page = await browser.newPage({ ...devices['iPhone 13'], viewport: { width: 393, height: 680 }, deviceScaleFactor: 1 });
  const errors = [], writes = [];
  page.on('pageerror', e => errors.push(e.message));
  page.on('request', r => { if (!['GET', 'HEAD'].includes(r.method())) writes.push({ method: r.method(), path: new URL(r.url()).pathname }); });
  if (!save) await page.route('**/api/v1/**', r => ['GET', 'HEAD'].includes(r.request().method()) ? r.continue() : r.abort());
  await page.addInitScript(() => {
    window.nativeInputFocuses = 0;
    window.composeTouchEvents = [];
    for (const type of ['pointerup', 'touchend']) document.addEventListener(type, event => {
      const sample = { type, target: event.composedPath()[0]?.tagName, trusted: event.isTrusted };
      setTimeout(() => window.composeTouchEvents.push({ ...sample, prevented: event.defaultPrevented }), 0);
    });
    const focus = HTMLElement.prototype.focus;
    HTMLElement.prototype.focus = function (...args) {
      if (/^(INPUT|TEXTAREA)$/.test(this.tagName) && this.style.top.includes('--compose-internal-web-backing-input-top')) window.nativeInputFocuses++;
      return focus.apply(this, args);
    };
  });
  const active = () => page.evaluate(() => {
    let e = document.activeElement;
    while (e?.shadowRoot?.activeElement) e = e.shadowRoot.activeElement;
    return { tag: e?.tagName, value: e?.value, nativeFocuses: window.nativeInputFocuses };
  });
  const tap = async label => {
    const box = await page.getByLabel(label, { exact: true }).boundingBox(); assert(box);
    await page.touchscreen.tap(box.x + box.width / 2, box.y + box.height / 2);
  };
  const assertConsumedTouch = async label => {
    const events = await page.evaluate(() => window.composeTouchEvents.filter(e => e.target === 'CANVAS'));
    assert(events.some(e => e.type === 'pointerup' && e.trusted && e.prevented), `${name} ${label}: real consumed pointerup`);
    assert(events.some(e => e.type === 'touchend' && e.trusted && e.prevented), `${name} ${label}: consumed pointerup must also cancel touchend`);
  };
  const resetInputEvents = () => page.evaluate(() => { window.nativeInputFocuses = 0; window.composeTouchEvents = []; });
  const checkSearch = async () => {
    await page.goto(base);
    await page.getByLabel('Pesquisar fotografias', { exact: true }).waitFor({ timeout: 60000 });
    await page.waitForTimeout(500); await resetInputEvents();
    await tap('Pesquisar fotografias'); await page.waitForTimeout(700);
    assert.equal((await active()).tag, 'INPUT');
    await assertConsumedTouch('search');
    await page.keyboard.insertText('-personal -"São Paulo"');
    await page.getByText('12 fotos', { exact: true }).waitFor();
    assert.equal((await active()).value, '-personal -"São Paulo"');
    await page.setViewportSize({ width: 393, height: 380 }); await page.waitForTimeout(350);
    assert.equal((await active()).tag, 'INPUT');
    assert.equal((await active()).value, '-personal -"São Paulo"');
    await page.keyboard.press('ControlOrMeta+A'); await page.keyboard.press('Backspace');
    await page.getByText('36 fotos', { exact: true }).waitFor();
    await page.keyboard.insertText('IMG_1025'); await page.waitForTimeout(400);
    assert.equal((await active()).value, 'IMG_1025');
    await page.setViewportSize({ width: 393, height: 680 });
    await page.screenshot({ path: `${output}/${name}-search.png` });
  };
  const openAndFocus = async () => {
    await page.getByRole('button', { name: 'Abrir curadoria e metadados', exact: true }).waitFor({ timeout: 60000 });
    await page.waitForTimeout(300); await tap('Abrir curadoria e metadados'); await page.waitForTimeout(850);
    for (let i = 0; i < 12; i++) {
      const b = await page.getByLabel('Nova palavra-chave', { exact: true }).boundingBox();
      if (b && b.y >= 180 && b.y + b.height < 670) break;
      await drag(page, { x: 200, y: 560 }, { x: 200, y: 290 }); await page.waitForTimeout(160);
    }
    await resetInputEvents();
    await tap('Nova palavra-chave'); await page.waitForTimeout(700);
    const observed = await active();
    assert.equal(observed.tag, 'INPUT');
    assert.equal(observed.nativeFocuses, 1, `${name}: redundant native focus overwrites the iOS keyboard request`);
    await assertConsumedTouch('keyword');
  };
  try {
    await checkSearch();
    await page.goto(`${base}/?photo=${photo.id}`); await openAndFocus();
    const keyword = `São João ${name}`;
    await page.keyboard.insertText('São João'); await page.waitForTimeout(150);
    // Resize while editing, rather than confusing viewport emulation with a real iOS keyboard.
    await page.setViewportSize({ width: 393, height: 380 }); await page.waitForTimeout(350);
    assert.equal((await active()).value, 'São João');
    await page.keyboard.insertText(` ${name}`); await page.waitForTimeout(150);
    assert.deepEqual(await active(), { tag: 'INPUT', value: keyword, nativeFocuses: 1 });
    await page.setViewportSize({ width: 393, height: 680 }); await page.waitForTimeout(350);
    assert.equal((await active()).value, keyword);
    if (save) {
      const before = await detail(); assert(!before.editorial.keywords.includes(keyword));
      const response = page.waitForResponse(r => r.request().method() === 'POST' && new URL(r.url()).pathname === `/api/v1/photos/${photo.id}/keywords`);
      await tap('Adicionar palavra-chave'); assert.equal((await response).status(), 200);
      await page.getByText('Alterações salvas e verificadas', { exact: true }).waitFor({ state: 'attached' });
      assert.equal(await page.getByLabel('Atualizar snapshot', { exact: true }).count(), 0);
      assert.deepEqual(writes, [{ method: 'POST', path: `/api/v1/photos/${photo.id}/keywords` }]);
      assert.deepEqual((await detail()).editorial.keywords, [...before.editorial.keywords, keyword].sort());
      await page.reload();
      assert((await detail()).editorial.keywords.includes(keyword));
      await openAndFocus(); // A new input session still opens and accepts text.
      await page.keyboard.insertText('rascunho'); await page.waitForTimeout(200);
      assert.equal((await active()).value, 'rascunho');
    } else assert.deepEqual(writes, []);
    await page.screenshot({ path: `${output}/${name}-keyword.png` });
    await tap('Fechar curadoria e metadados');
    await page.getByRole('button', { name: 'Fechar curadoria e metadados', exact: true }).waitFor({ state: 'hidden' });
    await page.waitForTimeout(100);
    assert.notEqual((await active()).tag, 'INPUT', 'Closing must not trap or forcibly restore input focus');
    await openAndFocus();
    assert.equal((await active()).tag, 'INPUT');
    await tap('Fechar curadoria e metadados');
    await page.getByRole('button', { name: 'Fechar curadoria e metadados', exact: true }).waitFor({ state: 'hidden' });
    await swipePhoto(page, 1);
    await page.waitForFunction(id => new URL(location.href).searchParams.get('photo') !== id, photo.id);
    const area = await page.getByLabel(/^Área ampliável de/).boundingBox();
    await drag(page, { x: area.x + area.width / 2, y: area.y + area.height * .3 },
      { x: area.x + area.width / 2, y: area.y + area.height * .7 });
    await page.getByRole('button', { name: 'Fechar miniaturas', exact: true }).waitFor();
    await page.waitForTimeout(700); await tap('Fechar miniaturas');
    await page.getByRole('button', { name: 'Fechar miniaturas', exact: true }).waitFor({ state: 'hidden' });
    assert.deepEqual(errors, []);
    results.push({ engine: name, version: browser.version(), passed: true, nativeFocusesPerActivation: 1,
      searchAndKeywords: true, trustedConsumedTouchEnd: true, horizontalAndVerticalGestures: true, resizeRetainsDraft: true, closeAndReopen: true, writes, errors });
    console.log(`PASS ${name}: search + keyword consumed touchend, one native focus, accents/continuous typing, resize, explicit close/reopen${save ? ', keyword saved once and read after reload' : ', no mutations'}`);
  } catch (error) {
    await page.screenshot({ path: `${output}/${name}-FAILED.png` });
    await writeFile(`${output}/${name}-FAILED.aria.txt`, await page.locator('body').ariaSnapshot());
    throw error;
  } finally {
    await browser.close();
    await writeFile(`${output}/results.json`, JSON.stringify({ results, physicalIPhoneTested: false, softwareKeyboardEmulated: false }, null, 2));
  }
}
