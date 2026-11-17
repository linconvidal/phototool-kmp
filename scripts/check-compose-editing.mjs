// Only the launcher-created 36-photo fixture. Real Compose controls and HTTP writes/readback.
import assert from 'node:assert/strict';
import { mkdir, writeFile } from 'node:fs/promises';
import { swipePhoto, drag } from './check-photo-gestures.mjs';
const { chromium, webkit } = await import(process.env.PLAYWRIGHT_MODULE || 'playwright');
const [base = 'http://127.0.0.1:8899', output = '/tmp/phototool-editing-20261115/browser', readonly = 'http://127.0.0.1:8900'] = process.argv.slice(2);
for (const url of [base, readonly]) assert.equal(new URL(url).hostname, '127.0.0.1');
await mkdir(output, { recursive: true });
for (const [url, write] of [[base, true], [readonly, false]]) {
  const status = await fetch(`${url}/api/v1/status`).then(r => r.json());
  assert.equal(status.photoCount, 36); assert.equal(status.writeEnabled, write); assert.equal(status.sync.phase, 'IDLE');
}
const { items } = await fetch(`${base}/api/v1/photos?pageSize=36`).then(r => r.json());
const session = await fetch(`${base}/api/v1/session`).then(r => r.json());
const detail = id => fetch(`${base}/api/v1/photos/${id}`).then(r => r.json());
const directMutation = async (url, id, field, value, revision, csrf, origin = url) => {
  const response = await fetch(`${url}/api/v1/photos/${id}/${field}`, { method: 'POST',
    headers: { 'Content-Type': 'application/json', Origin: origin, 'X-PhotoTool-CSRF': csrf },
    body: JSON.stringify({ value, expectedEditorialRevision: revision }) });
  return { status: response.status, body: await response.json() };
};
const results = [];
for (const [engine, name, filename] of [[chromium, 'chromium', 'IMG_1025.JPG'], [webkit, 'webkit', 'IMG_1023.JPG']]) {
  if (process.env.EDITING_ENGINE && process.env.EDITING_ENGINE !== name) continue;
  const a = items.find(p => p.displayName === filename); assert(a);
  const aDetail = await detail(a.id);
  const b = await detail(aDetail.nextId);
  const untouchedB = b.editorial;
  const folder = `${output}/${name}`; await mkdir(folder, { recursive: true });
  const browser = await engine.launch({ headless: true });
  const context = await browser.newContext({ viewport: { width: 393, height: 680 }, hasTouch: true, isMobile: true,
    deviceScaleFactor: 1, recordVideo: { dir: folder, size: { width: 393, height: 680 } } });
  const page = await context.newPage();
  const writes = [], responses = [], errors = [], frames = [];
  const cleanups = [];
  page.on('pageerror', e => errors.push(e.message));
  page.on('request', r => {
    if (!['GET', 'HEAD'].includes(r.method())) writes.push({ path: new URL(r.url()).pathname, method: r.method(), body: r.postDataJSON(), csrfPresent: !!r.headers()['x-phototool-csrf'], at: Date.now() });
  });
  page.on('response', async r => {
    if (!['GET', 'HEAD'].includes(r.request().method())) responses.push({ path: new URL(r.url()).pathname, status: r.status() });
  });
  const area = photo => page.getByLabel(`Área ampliável de ${photo.displayName}`, { exact: false });
  const control = label => page.getByLabel(label, { exact: true });
  const settled = async photo => {
    await area(photo).waitFor({ timeout: 60000 });
    await page.waitForFunction(id => new URL(location.href).searchParams.get('photo') === id, photo.id);
    await page.waitForTimeout(200);
  };
  const reveal = async locator => {
    await locator.waitFor({ state: 'attached' });
    const label = await locator.getAttribute('aria-label') || '';
    const upper = /^(Marcar|Remover flag|Rótulo|Remover rótulo|Avaliação)/.test(label);
    for (let i = 0; i < 12; i++) {
      const box = await locator.boundingBox();
      if (box && box.width > 0 && box.y >= 155 && box.y + box.height <= 672) return box;
      const scrollUp = box && box.width > 0 ? box.y < 155 : upper;
      await drag(page, { x: 200, y: scrollUp ? 300 : 550 }, { x: 200, y: scrollUp ? 550 : 300 });
      await page.waitForTimeout(160);
    }
    assert.fail('Control was not reachable in the inspector');
  };
  const activate = async (locator, scroll = false, fraction = .5) => {
    const box = scroll ? await reveal(locator) : await locator.boundingBox(); assert(box);
    await page.touchscreen.tap(box.x + box.width * fraction, box.y + box.height / 2);
    await page.waitForTimeout(80);
  };
  const tap = (label, scroll = false, fraction = .5) => activate(control(label), scroll, fraction);
  const panel = async () => { await tap('Abrir curadoria e metadados'); await control('Painel de curadoria da fotografia').waitFor(); };
  const capture = async label => {
    const file = `${String(frames.length).padStart(2, '0')}-${label}.png`;
    await page.screenshot({ path: `${folder}/${file}` });
    await writeFile(`${folder}/${file}.aria.txt`, await page.locator('body').ariaSnapshot());
    frames.push({ label, file, at: Date.now() });
  };
  // Compose Web 1.11 omits selected/disabled ARIA attributes. Check its rendered selector
  // marker geometry, actual input refusal, and native Compose disabled-semantics tests instead.
  const selected = async (selector, label) => {
    const chosen = await control(label).boundingBox(); assert(chosen);
    await page.waitForTimeout(350);
    const marker = await control(selector).locator(':scope > div').first().boundingBox(); assert(marker);
    assert(Math.abs(marker.x - chosen.x) < 3, `${selector}: marker does not match ${label}`);
  };
  const mutate = async (field, action, verify, method = 'POST') => {
    const before = await detail(a.id), count = writes.length;
    if (['rating', 'label'].includes(field)) {
      try { verify(before.editorial); return before.editorial; } catch { /* A change is required. */ }
    }
    const responsePromise = page.waitForResponse(r => new URL(r.url()).pathname === `/api/v1/photos/${a.id}/${field}` && r.request().method() === method);
    await action();
    const response = await responsePromise; assert.equal(response.status(), 200, await response.text());
    await page.getByText('Alterações salvas e verificadas', { exact: true }).waitFor({ state: 'attached' });
    assert.equal(await control('Atualizar snapshot').count(), 0, 'Successful saves must not add a permanent status bar');
    await page.waitForTimeout(130);
    assert.equal(writes.length, count + 1, 'Exactly one request per accepted intent');
    assert.equal(writes.at(-1).body.expectedEditorialRevision, before.editorial.revision);
    assert.equal(writes.at(-1).csrfPresent, true);
    const saved = await detail(a.id);
    assert.deepEqual(saved.editorial, (await response.json()).editorial);
    verify(saved.editorial);
    return saved.editorial;
  };
  try {
    await page.goto(`${base}/?photo=${a.id}`); await settled(a); await panel();
    for (const [label, value] of [['Marcar como escolhida', 'PICK'], ['Marcar como rejeitada', 'REJECT'], ['Remover flag', 'UNFLAGGED']]) {
      await mutate('flag', () => tap(label, true), e => assert.equal(e.flag, value));
      await selected('Estado editorial', label);
    }
    const stars = (await detail(a.id)).editorial.rating === 5 ? 4 : 5;
    await mutate('rating', () => tap('Avaliação editorial', true, stars === 5 ? .95 : .8), e => assert.equal(e.rating, stars));
    await mutate('label', () => tap('Rótulo verde', true), e => assert.equal(e.label, 'GREEN'));
    await mutate('label', () => tap('Remover rótulo de cor', true), e => assert.equal(e.label, null));
    await mutate('label', () => tap('Rótulo vermelho', true), e => assert.equal(e.label, 'RED'));
    const keyword = `móvel ${name}`;
    await tap('Nova palavra-chave', true); await page.keyboard.insertText(keyword); await page.waitForTimeout(150);
    await mutate('keywords', () => tap('Adicionar palavra-chave', true), e => assert(e.keywords.includes(keyword)));
    await capture('keyword-saved');
    await mutate('keywords', () => activate(page.getByText(keyword, { exact: true }), true), e => assert(!e.keywords.includes(keyword)), 'DELETE');
    await tap('Nova palavra-chave', true); await page.keyboard.insertText(keyword); await page.waitForTimeout(150);
    const expected = await mutate('keywords', () => tap('Adicionar palavra-chave', true), e => assert(e.keywords.includes(keyword)));
    await page.reload(); await settled(a); await panel();
    assert.deepEqual((await detail(a.id)).editorial, expected);
    await selected('Rótulo cromático', 'Rótulo vermelho');
    await reveal(page.getByText(keyword, { exact: true })); await capture('reload-authoritative-keywords');

    // Delay the outgoing POST, navigate by a native swipe, then deliver it for the captured photo.
    await tap('Fechar curadoria e metadados'); await panel();
    let release; const gate = new Promise(r => { release = r; }); cleanups.push(release);
    await page.route(`**/api/v1/photos/${a.id}/flag`, async route => { await gate; await route.continue(); }, { times: 1 });
    const count = writes.length;
    await tap('Marcar como escolhida', true);
    await page.getByText('Salvando no XMP adjacente', { exact: true }).waitFor({ state: 'attached' });
    await tap('Marcar como rejeitada', true);
    assert.equal(writes.length, count + 1, 'Busy controls must refuse additional input');
    await tap('Fechar curadoria e metadados'); await swipePhoto(page, 1); await settled(b); await panel();
    await tap('Marcar como escolhida', true);
    assert.equal(writes.length, count + 1, 'Adjacent photo cannot be edited while a write is unresolved');
    await capture('navigated-while-a-saving');
    const readbackStatus = page.waitForResponse(r => new URL(r.url()).pathname === '/api/v1/status');
    release(); await readbackStatus; await page.waitForTimeout(200);
    assert.equal(await control('Atualizar snapshot').count(), 0);
    assert.equal(writes.length, count + 1);
    assert.equal((await detail(a.id)).editorial.flag, 'PICK');
    assert.deepEqual((await detail(b.id)).editorial, untouchedB);
    assert.equal(await area(b).count(), 1);
    await tap('Fechar curadoria e metadados'); await swipePhoto(page, -1); await settled(a); await panel();

    // A second client edits XMP. The stale UI revision must fail without overwriting it.
    const beforeConflict = await detail(a.id);
    const external = await directMutation(base, a.id, 'rating', 2, beforeConflict.editorial.revision, session.csrfToken);
    assert.equal(external.status, 200);
    const conflictResponse = page.waitForResponse(r => r.request().method() === 'POST');
    await tap('Marcar como rejeitada', true);
    assert.equal((await conflictResponse).status(), 409);
    await page.getByText(/A fotografia foi alterada por outra sessão/).first().waitFor();
    const beforeBlockedTap = writes.length;
    await tap('Marcar como escolhida', true);
    assert.equal(writes.length, beforeBlockedTap, 'Conflict blocks editing until explicit refresh');
    await capture('conflict-no-false-success');
    assert.equal((await detail(a.id)).editorial.flag, 'PICK');
    const conflictCount = writes.length; await page.waitForTimeout(400); assert.equal(writes.length, conflictCount);
    await tap('Atualizar snapshot'); await settled(a);
    if (!await control('Painel de curadoria da fotografia').count()) await panel();

    // Real server CSRF refusal, then an explicit read-only refresh (never an automatic replay).
    await page.route(`**/api/v1/photos/${a.id}/flag`, route => route.continue({ headers: { ...route.request().headers(), 'X-PhotoTool-CSRF': 'invalid' } }), { times: 1 });
    const csrfResponse = page.waitForResponse(r => r.request().method() === 'POST');
    await tap('Marcar como rejeitada', true); assert.equal((await csrfResponse).status(), 403);
    await page.getByText(/O servidor recusou a autorização/).first().waitFor(); await capture('csrf-blocked');
    const deniedCount = writes.length; await page.waitForTimeout(400); assert.equal(writes.length, deniedCount);
    await tap('Atualizar snapshot'); await settled(a);
    if (!await control('Painel de curadoria da fotografia').count()) await panel();

    // Deliver to the server but lose the response. UI must not claim success or repeat the POST.
    await page.route(`**/api/v1/photos/${a.id}/flag`, async route => {
      const saved = await route.fetch(); assert.equal(saved.status(), 200); await route.abort('failed');
    }, { times: 1 });
    await tap('Marcar como rejeitada', true);
    await page.getByText(/Gravação não confirmada/).first().waitFor(); await capture('lost-response-not-success');
    const lostCount = writes.length; await page.waitForTimeout(500); assert.equal(writes.length, lostCount);
    assert.equal((await detail(a.id)).editorial.flag, 'REJECT');
    await tap('Atualizar snapshot'); await settled(a);
    if (!await control('Painel de curadoria da fotografia').count()) await panel();
    await selected('Estado editorial', 'Marcar como rejeitada');
    await capture('explicit-refresh-confirms-xmp');
    assert(writes.every(w => w.path.startsWith(`/api/v1/photos/${a.id}/`)), 'No write may target adjacent photo or sync');
    assert.deepEqual(errors, []);

    // New build still defaults to readonly, including all apparent editorial controls.
    const beforeReadonly = writes.length;
    await page.goto(`${readonly}/?photo=${a.id}`); await settled(a); await panel();
    for (const label of ['Marcar como escolhida', 'Marcar como rejeitada', 'Remover flag', 'Rótulo vermelho', 'Avaliação editorial']) {
      await tap(label, true);
    }
    assert.equal(writes.length, beforeReadonly); await capture('default-readonly');
    results.push({ engine: name, version: browser.version(), photoId: a.id, displayName: a.displayName,
      adjacentUnchanged: b.id, passed: true, writes: writes.length, errors });
    console.log(`PASS editing ${name}: flags, rating, label, keywords, reload, exact-photo race, conflict, CSRF, lost response and readonly`);
  } catch (error) {
    await page.screenshot({ path: `${folder}/FAILED.png` });
    await writeFile(`${folder}/FAILED.aria.txt`, await page.locator('body').ariaSnapshot());
    throw error;
  } finally {
    cleanups.forEach(release => release());
    await writeFile(`${folder}/sequence.json`, JSON.stringify({ frames, writes, responses, errors }, null, 2));
    await context.close(); await browser.close();
    await writeFile(`${output}/editing-results.json`, JSON.stringify({ results, physicalIPhoneTested: false }, null, 2));
  }
}
// Direct negative probes verify server authority, independently of disabled UI controls.
const target = items.find(p => p.displayName === 'IMG_1025.JPG');
const latest = await detail(target.id);
const wrongOrigin = await directMutation(base, target.id, 'rating', 1, latest.editorial.revision, session.csrfToken, 'https://other.invalid');
assert.equal(wrongOrigin.status, 403); assert.equal(wrongOrigin.body.code, 'origin-invalid');
const stale = await directMutation(base, target.id, 'rating', 1, '0'.repeat(64), session.csrfToken);
assert.equal(stale.status, 409); assert.equal(stale.body.code, 'editorial-revision-conflict');
const readSession = await fetch(`${readonly}/api/v1/session`).then(r => r.json());
const readDetail = await fetch(`${readonly}/api/v1/photos/${target.id}`).then(r => r.json());
const denied = await directMutation(readonly, target.id, 'rating', 1, readDetail.editorial.revision, readSession.csrfToken);
assert.equal(denied.status, 403); assert.equal(denied.body.code, 'write-disabled');
await writeFile(`${output}/server-denials.json`, JSON.stringify({ wrongOrigin, stale, readonly: denied }, null, 2));
