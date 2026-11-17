// Responsive detail regression for the copied synthetic ComposeWebCandidate fixture.
// Called by check-compose-web.mjs with its existing Playwright engines and loopback gate.
import assert from 'node:assert/strict';
import { writeFile } from 'node:fs/promises';

export async function checkDetailLayouts({ base, output, engines }) {
  const { items } = await fetch(`${base}/api/v1/photos?pageSize=36`).then(r => r.json());
  const results = [];
  for (const [engine, name] of engines) {
    const browser = await engine.launch({ headless: true });
    try {
      for (const [width, height] of [[390, 740], [393, 680], [844, 390], [1440, 1000]]) {
        const compact = width < 900;
        const context = await browser.newContext({ viewport: { width, height }, deviceScaleFactor: 1, isMobile: compact, hasTouch: compact });
        const page = await context.newPage();
        const writes = [], errors = [];
        page.on('request', request => {
          if (!['GET', 'HEAD'].includes(request.method())) writes.push(`${request.method()} ${request.url()}`);
        });
        page.on('pageerror', error => errors.push(error.message));
        page.on('console', message => {
          if (message.type() === 'error' && !message.text().includes('WEBGL_debug_renderer_info not enabled')) errors.push(message.text());
        });
        const button = label => page.getByRole('button', { name: label, exact: true });
        const activate = async locator => {
          await locator.waitFor();
          const box = await locator.boundingBox();
          assert(box && box.width >= 44 && box.height >= 44, 'Touch target must be at least 44px');
          const x = box.x + box.width / 2, y = box.y + box.height / 2;
          assert(y >= 0 && y <= height && x >= 0 && x <= width, 'Control must be in the viewport');
          if (compact) await page.touchscreen.tap(x, y); else await page.mouse.click(x, y);
          await page.waitForTimeout(350);
        };
        const settled = async filename => {
          await page.getByText('Somente leitura · Snapshot READY', { exact: true }).waitFor({ timeout: 60000 });
          await page.getByLabel(`Prévia de ${filename}`, { exact: true }).first().waitFor();
          await page.waitForLoadState('networkidle');
          await page.waitForTimeout(350);
        };
        let prefix;
        const screenshot = async state => {
          await page.waitForTimeout(450); // Include the completed sheet transition and Skia presentation.
          const png = await page.screenshot({ path: `${output}/${prefix}-${state}.png` });
          await writeFile(`${output}/${prefix}-${state}.aria.txt`, await page.locator('body').ariaSnapshot());
          return png;
        };
        try {
          for (const filename of ['IMG_1024.JPG', 'IMG_1025.JPG']) {
            const photo = items.find(item => item.displayName === filename);
            assert(photo, 'Known portrait and landscape synthetic fixtures are required');
            prefix = `${name}-${width}x${height}-${filename.slice(0, -4)}`;
            await page.goto(`${base}/?photo=${photo.id}`);
            await settled(filename);
            const preview = page.getByLabel(`Área ampliável de ${filename}`, { exact: false });
            const bounds = await preview.boundingBox();
            const png = await screenshot('closed');
            if (compact) {
              assert(bounds.width >= width * .95, `Preview width ${bounds.width}/${width}: inspector must not steal phone width`);
              assert(bounds.height >= height * .65, `Preview height ${bounds.height}/${height}: photo must dominate the first fold`);
              assert.equal(await page.getByLabel('Painel de curadoria da fotografia', { exact: true }).count(), 0);
              assert.equal(await page.getByLabel('Faixa de fotografias próximas', { exact: true }).count(), 0);
              for (const label of ['Voltar à biblioteca', 'Abrir miniaturas', 'Abrir curadoria e metadados',
                'Fotografia anterior', 'Próxima fotografia', 'Aumentar zoom', 'Diminuir zoom', 'Ajustar fotografia à janela']) {
                const control = await button(label).boundingBox();
                assert(control.width >= 44 && control.height >= 44, `${label}: small touch target`);
                assert(control.y + control.height <= bounds.y || control.y >= bounds.y + bounds.height, `${label} covers the photograph`);
              }
              // A populated canvas is not sufficient: measure the actual colored JPEG pixels in the screenshot.
              const pixels = await measureFixturePixels(page, png, bounds, photo.aspectRatio);
              for (const [open, close, content, state] of [
                ['Abrir curadoria e metadados', 'Fechar curadoria e metadados', 'Painel de curadoria da fotografia', 'inspector'],
                ['Abrir miniaturas', 'Fechar miniaturas', 'Faixa de fotografias próximas', 'filmstrip'],
              ]) {
                await activate(button(open));
                await page.getByLabel(content, { exact: true }).waitFor();
                await screenshot(`${state}-open`);
                if (state === 'inspector') {
                  // Compose's Wasm accessibility bridge does not expose disabled on these custom selectors.
                  // Send a real tap instead; JVM semantics also assert the read-only gate.
                  await activate(button('Marcar como rejeitada'));
                  assert.deepEqual(writes, [], 'Read-only editorial controls must not send mutations');
                }
                await activate(button(close));
                await page.getByLabel(content, { exact: true }).waitFor({ state: 'detached' });
                const restored = await preview.boundingBox();
                assert.deepEqual(restored, bounds, 'Closing the panel must restore the entire preview');
                const closed = await screenshot(`${state}-reclosed`);
                await measureFixturePixels(page, closed, restored, photo.aspectRatio);
              }
              results.push({ engine: name, version: browser.version(), viewport: { width, height }, photo: filename,
                preview: bounds, previewHeightRatio: bounds.height / height, pixels, panelsRestored: true });
            } else {
              await page.getByLabel('Painel de curadoria da fotografia', { exact: true }).waitFor();
              await page.getByLabel('Faixa de fotografias próximas', { exact: true }).waitFor();
              assert.equal(await button('Abrir curadoria e metadados').count(), 0);
              assert.equal(bounds.width, 1070, 'Desktop must keep its existing 370px inspector');
              assert.equal(bounds.height, 824, 'Desktop preview height must not regress');
              results.push({ engine: name, version: browser.version(), viewport: { width, height }, photo: filename,
                preview: bounds, desktopInspectorPreserved: true });
            }
            await activate(button('Aumentar zoom'));
            await page.getByText('125%', { exact: true }).waitFor();
            await activate(button('Ajustar fotografia à janela'));
            await page.getByText('100%', { exact: true }).waitFor();
            await activate(button('Próxima fotografia'));
            await page.waitForFunction(id => new URL(location.href).searchParams.get('photo') !== id, photo.id);
            await activate(button('Fotografia anterior'));
            await settled(filename);
            if (compact && filename === 'IMG_1024.JPG') {
              // Same session: Safari bar/rotation resize, then return, with no stale panel geometry.
              await page.setViewportSize({ width: 844, height: 390 });
              await settled(filename);
              assert((await preview.boundingBox()).height >= 390 * .65);
              await page.setViewportSize({ width, height });
              await settled(filename);
              assert.deepEqual(await preview.boundingBox(), bounds);
            }
            await activate(button('Voltar à biblioteca'));
            await page.getByRole('textbox', { name: 'Pesquisar fotografias', exact: true }).waitFor();
          }
          assert.deepEqual(writes, [], 'No synchronization or mutations');
          assert.deepEqual(errors, [], 'No runtime or CSP errors');
          console.log(`PASS detail ${name} ${width}x${height}: Fit pixels, panels, restore, touch targets, zoom, navigation, back`);
        } catch (error) {
          await screenshot('FAILED');
          await writeFile(`${output}/${prefix}-errors.json`, JSON.stringify({ failure: error.stack, writes, errors }, null, 2));
          throw error;
        } finally { await context.close(); }
      }
    } finally {
      await browser.close();
      await writeFile(`${output}/detail-results.json`, JSON.stringify({ physicalIPhoneTested: false, results }, null, 2));
    }
  }
  const status = await fetch(`${base}/api/v1/status`).then(r => r.json());
  assert.equal(status.writeEnabled, false); assert.equal(status.sync.phase, 'IDLE'); assert.equal(status.photoCount, 36);
}

async function measureFixturePixels(page, png, bounds, aspectRatio) {
  const height = Math.min(bounds.height, bounds.width / aspectRatio), width = height * aspectRatio;
  const expected = { x: bounds.x + (bounds.width - width) / 2, y: bounds.y + (bounds.height - height) / 2, width, height };
  // The two known synthetic JPEGs are blue/purple; no UI surface has this chroma.
  const actual = await page.evaluate(async ({ base64, bounds }) => {
    const image = new Image(); image.src = `data:image/png;base64,${base64}`; await image.decode();
    const canvas = document.createElement('canvas'); canvas.width = image.width; canvas.height = image.height;
    const context = canvas.getContext('2d'); context.drawImage(image, 0, 0);
    const { data } = context.getImageData(0, 0, canvas.width, canvas.height);
    let left = Infinity, top = Infinity, right = -1, bottom = -1, count = 0;
    for (let y = Math.ceil(bounds.y); y < Math.floor(bounds.y + bounds.height); y++) {
      for (let x = Math.ceil(bounds.x); x < Math.floor(bounds.x + bounds.width); x++) {
        const i = (y * canvas.width + x) * 4, r = data[i], g = data[i + 1], b = data[i + 2];
        if (b > 70 && b - r > 20 && b - g > 30) {
          left = Math.min(left, x); top = Math.min(top, y); right = Math.max(right, x); bottom = Math.max(bottom, y); count++;
        }
      }
    }
    return { x: left, y: top, width: right - left + 1, height: bottom - top + 1, count };
  }, { base64: png.toString('base64'), bounds });
  for (const key of ['x', 'y', 'width', 'height']) assert(Math.abs(actual[key] - expected[key]) < 3, `Rendered Fit ${key}: ${actual[key]} vs ${expected[key]}`);
  const coverage = actual.count / (width * height);
  assert(coverage > .97, `JPEG coverage ${coverage}: blank/stale canvas or controls obscure the subject`);
  return { ...actual, coverage, expectedFit: expected };
}
