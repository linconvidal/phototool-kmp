// Exercises the real shared Compose canvas and accessibility tree against the isolated JVM fixture.
// PLAYWRIGHT_MODULE may point to an existing playwright/index.mjs; no browser is a physical iPhone.
import assert from 'node:assert/strict';
import { mkdir, writeFile } from 'node:fs/promises';
const { chromium, webkit } = await import(process.env.PLAYWRIGHT_MODULE || 'playwright');
// `detail` checks responsive layout; `transitions` checks delayed navigation and rendered identity.
const [base = 'http://127.0.0.1:8893', output = '/tmp/phototool-compose-web-evidence', mode = 'flows'] = process.argv.slice(2);
assert(['flows', 'detail', 'transitions'].includes(mode), 'Choose flows, detail or transitions');
assert(['127.0.0.1', 'localhost', '[::1]'].includes(new URL(base).hostname), 'Use only the isolated loopback candidate');
await mkdir(output, { recursive: true });
const status = await fetch(`${base}/api/v1/status`).then(response => response.json());
assert.equal(status.photoCount, 36, 'This check requires the synthetic candidate, not a real library');
assert.equal(status.writeEnabled, false);
assert.equal(status.sync.phase, 'IDLE');
const initialPage = await fetch(`${base}/api/v1/photos`).then(response => response.json());
const results = [];

async function exercise(engine, name, width, height, mobile) {
  const browser = await engine.launch({ headless: true });
  const context = await browser.newContext({ viewport: { width, height }, deviceScaleFactor: 1, isMobile: mobile, hasTouch: mobile });
  const page = await context.newPage();
  const errors = [];
  const diagnostics = [];
  const writes = [];
  const previews = [];
  page.on('pageerror', error => errors.push(error.message));
  page.on('console', message => {
    // Skiko's renderer-info probe is a warning in Chromium, logged as an error in WebKit.
    if (message.text() === 'WebGL: INVALID_ENUM: getParameter: invalid parameter name, WEBGL_debug_renderer_info not enabled') diagnostics.push(message.text());
    else if (message.type() === 'error') errors.push(message.text());
  });
  page.on('request', request => { if (!['GET', 'HEAD'].includes(request.method())) writes.push(`${request.method()} ${request.url()}`); });
  page.on('response', async response => {
    if (response.url().includes('/preview?') && response.ok()) previews.push(response.url());
    if (!response.ok()) console.log('HTTP', response.status(), response.url());
  });
  // Compose accessibility nodes sit behind the canvas. Locate semantically, then send real pointer events to its coordinates.
  const activate = async locator => {
    await locator.waitFor();
    const box = await locator.boundingBox();
    assert(box && box.width > 0 && box.height > 0, 'Control must have rendered bounds');
    const x = box.x + box.width / 2;
    const y = box.y + box.height / 2;
    if (mobile) await page.touchscreen.tap(x, y); else await page.mouse.click(x, y);
    await page.waitForTimeout(150);
  };
  const button = label => {
    const locator = page.getByRole('button', { name: label, exact: true });
    return { click: () => activate(locator), waitFor: () => locator.waitFor() };
  };
  const screenshot = async label => {
    await page.waitForLoadState('networkidle');
    await page.waitForTimeout(500); // Allow Skia's completed preview upload to reach the rendered canvas.
    await page.screenshot({ path: `${output}/${name}-${width}x${height}-${label}.png` });
    await writeFile(`${output}/${name}-${width}x${height}-${label}.aria.txt`, await page.locator('body').ariaSnapshot());
  };
  const settled = async () => {
    await page.getByText('Somente leitura · Snapshot READY', { exact: true }).waitFor({ timeout: 60000 });
    await page.waitForTimeout(150);
    await page.waitForFunction(() => {
      const collect = root => [...root.querySelectorAll('*')].flatMap(element => [element, ...(element.shadowRoot ? collect(element.shadowRoot) : [])]);
      return !collect(document).some(element => element.getAttribute('aria-label')?.includes('Carregando prévia'));
    });
  };
  try {
    await page.goto(base);
    await settled();
    await page.getByRole('button', { name: /^Fotografia IMG_/ }).first().waitFor();
    assert(previews.length > 0, 'Actual JPEG previews must load from the API');
    await screenshot('gallery');
    if (name === 'webkit') {
      const pixels = () => page.locator('canvas').evaluate(canvas => canvas.toDataURL('image/png'));
      assert((await pixels()).length > 50000, 'WebKit must retain a populated presented frame');
      await page.setViewportSize({ width: width + 1, height });
      await page.waitForTimeout(200);
      await page.setViewportSize({ width, height });
      await settled();
      await screenshot('resized-gallery');
      assert((await pixels()).length > 50000, 'WebKit must redraw after viewport resize');
    }
    await button('Próxima página').click();
    await settled();
    await page.getByText('Página 2 de 2', { exact: true }).waitFor();
    await button('Página anterior').click();
    await settled();

    const search = { fill: async value => {
      await activate(page.getByRole('textbox', { name: 'Pesquisar fotografias', exact: true }));
      await page.keyboard.press('ControlOrMeta+A');
      await page.keyboard.insertText(value);
      if (!value) await page.keyboard.press('Backspace');
    } };
    await search.fill('-personal -"São Paulo"');
    await settled();
    await page.getByText('12 fotos', { exact: true }).waitFor();
    const filtered = await fetch(`${base}/api/v1/photos?q=${encodeURIComponent('-personal -"São Paulo"')}`).then(response => response.json());
    assert.equal(filtered.total, 12);
    await screenshot('negative-search');
    await search.fill('');
    await settled();
    await page.getByText('36 fotos', { exact: true }).waitFor();
    await button('Filtros').click();
    await button('GPS').click();
    await button('Com GPS').click();
    await button('Aplicar filtros').click();
    await settled();
    await page.getByText('18 fotos', { exact: true }).waitFor();
    await screenshot('gps-filter');
    await button('Remover filtro GPS: presente').click();
    await settled();
    await page.getByText('36 fotos', { exact: true }).waitFor();

    await activate(page.getByRole('button', { name: new RegExp(`^Fotografia ${initialPage.items[0].displayName.replace('.', '\\.')},`) }));
    await settled();
    await button('Próxima fotografia').waitFor();
    await screenshot('detail');
    if (mobile) {
      await button('Abrir curadoria e metadados').click();
      await button('Fechar curadoria e metadados').waitFor();
      // Emulated touch leaves DOM focus on body. Switch to keyboard input on the Compose canvas.
      await page.locator('canvas').focus();
      await page.keyboard.press('Escape');
      await page.getByLabel('Painel de curadoria da fotografia', { exact: true }).waitFor({ state: 'detached' });
      await button('Abrir miniaturas').click();
      await button('Fechar miniaturas').waitFor();
      await page.locator('canvas').focus();
      await page.keyboard.press('Escape');
      await page.getByLabel('Faixa de fotografias próximas', { exact: true }).waitFor({ state: 'detached' });
      await button('Próxima fotografia').waitFor(); // Escape must not leave the detail.
    }
    await button('Aumentar zoom').click();
    await page.getByText('125%', { exact: true }).waitFor();
    await button('Próxima fotografia').click();
    await settled();
    await page.getByText(initialPage.items[1].displayName, { exact: true }).waitFor();
    await page.goBack();
    await settled();
    await page.getByRole('textbox', { name: 'Pesquisar fotografias', exact: true }).waitFor();

    await button('Escolhidas').click();
    await settled();
    await page.getByText('12 fotos', { exact: true }).waitFor();
    await button('Todas as fotos').click();
    await settled();
    if (mobile) await button('Mais opções').click();
    await button('Configurações').click();
    await screenshot('settings');
    await button('Voltar à galeria').click();

    const errorCount = errors.length;
    await page.route('**/api/v1/photos?**', route => route.fulfill({ status: 503, contentType: 'application/json', body: '{"code":"fixture-offline","message":"Fixture error"}' }));
    await button('Escolhidas').click();
    await page.getByText('Falha de conexão', { exact: true }).waitFor();
    await screenshot('error');
    // Expected browser HTTP error only during the deliberately injected failure.
    errors.splice(errorCount);
    await page.unroute('**/api/v1/photos?**');
    await button('Tentar novamente').click();
    await settled();
    await page.getByText('12 fotos', { exact: true }).waitFor();
    await button('Todas as fotos').click();
    await settled();
    await page.getByText('36 fotos', { exact: true }).waitFor();
    assert.deepEqual(writes, [], 'No synchronization or mutations are allowed');
    assert.deepEqual(errors, [], 'No runtime or CSP errors are allowed');
    results.push({ engine: name, version: browser.version(), viewport: `${width}x${height}`, mobileEmulation: mobile,
      physicalIPhone: false, passed: true, jpegResponses: previews.length, writes, diagnostics });
    console.log(`PASS ${name} ${width}x${height}: gallery, pagination, negative search, GPS, detail, zoom, neighbors, picks, settings, error/retry`);
  } catch (error) {
    await screenshot('FAILED');
    await writeFile(`${output}/${name}-${width}x${height}-errors.json`, JSON.stringify({ errors, writes, failure: error.stack }, null, 2));
    throw error;
  } finally { await browser.close(); }
}

if (mode === 'detail') {
  const { checkDetailLayouts } = await import('./check-compose-detail.mjs');
  await checkDetailLayouts({ base, output, engines: [[chromium, 'chromium'], [webkit, 'webkit']] });
} else if (mode === 'transitions') {
  const { checkPhotoTransitions } = await import('./check-photo-transitions.mjs');
  await checkPhotoTransitions({ base, output, engines: [[chromium, 'chromium'], [webkit, 'webkit']] });
} else try {
  await exercise(chromium, 'chromium', 1440, 1000, false);
  await exercise(chromium, 'chromium', 390, 844, true);
  await exercise(webkit, 'webkit', 412, 915, true);
} finally {
  await writeFile(`${output}/results.json`, JSON.stringify({ status, results, physicalIPhoneTested: false }, null, 2));
}
