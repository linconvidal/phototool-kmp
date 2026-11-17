// Real Compose renderer, copied fixture only. Gates hold detail and JPEG independently.
// Sequential PNGs plus a continuous video prove the in-between states, not just final screens.
import assert from 'node:assert/strict';
import { mkdir, writeFile } from 'node:fs/promises';

export async function checkPhotoTransitions({ base, output, engines, navigate }) {
  const { items } = await fetch(`${base}/api/v1/photos?pageSize=36`).then(r => r.json());
  const start = items.findIndex(p => p.displayName === 'IMG_1025.JPG');
  const photos = items.slice(start, start + 6);
  assert.equal(photos.length, 6);
  const references = {};
  for (const photo of photos) {
    const detail = await fetch(`${base}/api/v1/photos/${photo.id}`).then(r => r.json());
    const jpeg = await fetch(`${base}${detail.previewUrl}`).then(r => r.arrayBuffer());
    references[photo.id] = { ...detail, jpeg: Buffer.from(jpeg).toString('base64') };
  }
  const results = [];
  for (const [engine, name] of engines) {
    const browser = await engine.launch({ headless: true });
    try {
      for (const [width, height] of [[393, 680], [1440, 1000]]) {
        const folder = `${output}/${name}-${width}x${height}`;
        await mkdir(folder, { recursive: true });
        const mobile = width < 900;
        const context = await browser.newContext({ viewport: { width, height }, deviceScaleFactor: 1,
          isMobile: mobile, hasTouch: mobile, recordVideo: { dir: folder, size: { width, height } } });
        const page = await context.newPage();
        const [a, b, c, d, e, f] = photos;
        const requests = [], writes = [], errors = [], frames = [];
        const gates = new Map();
        // Keep c genuinely cold: fail its speculative detail too, not only navigation.
        // Otherwise a correct prepared-detail hit would bypass this error/retry scenario.
        const failDetails = new Set([c.id]), failPreviews = new Set([c.id]);
        const hold = key => {
          let release;
          const promise = new Promise(resolve => { release = resolve; });
          gates.set(key, promise);
          return () => { gates.delete(key); release(); };
        };
        const releaseBDetail = hold(`detail:${b.id}`), releaseBJpeg = hold(`jpeg:${b.id}`);
        const releaseDDetail = hold(`detail:${d.id}`), releaseEJpeg = hold(`jpeg:${e.id}`), releaseFJpeg = hold(`jpeg:${f.id}`);
        page.on('request', request => { if (!['GET', 'HEAD'].includes(request.method())) writes.push(`${request.method()} ${request.url()}`); });
        page.on('pageerror', error => errors.push(error.message));
        page.on('console', message => {
          if (message.type() === 'error' && !message.text().includes('WEBGL_debug_renderer_info not enabled') &&
              !/503|Failed to load resource/.test(message.text())) errors.push(message.text());
        });
        await page.route('**/api/v1/photos/**', async route => {
          const url = new URL(route.request().url());
          const match = url.pathname.match(/^\/api\/v1\/photos\/([a-z0-9_-]+)(\/preview)?$/i);
          if (!match) return route.continue();
          const [, id, preview] = match;
          // Gallery/filmstrip sizes are independent of the prepared detail preview.
          const kind = preview ? 'jpeg' : 'detail';
          const full = !preview || url.searchParams.get('max') === '1600';
          requests.push({ kind, id, dimension: url.searchParams.get('max'), at: Date.now() });
          if (full) await gates.get(`${kind}:${id}`);
          try {
            if (full && (preview ? failPreviews : failDetails).has(id)) {
              await route.fulfill({ status: 503, contentType: 'application/json', body: '{"code":"injected-failure"}' });
            } else await route.continue();
          } catch (error) {
            // Cancellation is expected when a newer intent or Back abandons the request.
            if (!/closed|handled|canceled|cancelled|disposed/i.test(error.message)) throw error;
          }
        });
        const button = label => page.getByRole('button', { name: label, exact: true });
        const tap = async label => {
          if (mobile && navigate && ['Próxima fotografia', 'Fotografia anterior'].includes(label)) {
            await navigate(page, label === 'Próxima fotografia' ? 1 : -1);
            return;
          }
          const control = button(label);
          await control.waitFor();
          const box = await control.boundingBox();
          assert(box && box.width >= 44 && box.height >= 44);
          if (mobile) await page.touchscreen.tap(box.x + box.width / 2, box.y + box.height / 2);
          else await page.mouse.click(box.x + box.width / 2, box.y + box.height / 2);
        };
        const area = photo => page.getByLabel(`Área ampliável de ${photo.displayName}`, { exact: false });
        const settled = async photo => {
          await page.getByText('Somente leitura · Snapshot READY', { exact: true }).waitFor({ timeout: 60000 });
          await area(photo).waitFor();
          await page.waitForFunction(id => new URL(location.href).searchParams.get('photo') === id, photo.id);
          await page.waitForTimeout(100);
        };
        const untilRequest = async (kind, photo) => {
          for (let i = 0; i < 300; i++) {
            if (requests.some(r => r.kind === kind && r.id === photo.id && (kind === 'detail' || r.dimension === '1600'))) return;
            await page.waitForTimeout(50);
          }
          assert.fail(`No ${kind} request for ${photo.displayName}`);
        };
        const capture = async (label, photo) => {
          // Gesture tracking is separately pixel-checked while held. Compare Fit pixels after
          // its short return spring; the continuous video also includes that settling interval.
          if (mobile && navigate) await page.waitForTimeout(300);
          assert.equal(await page.getByText('Carregando fotografia', { exact: true }).count(), 0, 'Viewer replaced by loading screen');
          assert.equal(await page.getByText('Fotografia indisponível', { exact: true }).count(), 0, 'Existing photo replaced by error screen');
          const viewer = area(photo);
          const bounds = await viewer.boundingBox();
          assert(bounds, `Wrong displayed identity, expected ${photo.displayName}`);
          assert.equal(await viewer.getByLabel(`Prévia de ${photo.displayName}`, { exact: true }).count(), 1);
          await page.getByText(photo.displayName, { exact: true }).first().waitFor();
          const file = `${String(frames.length).padStart(3, '0')}-${label}.png`;
          const png = await page.screenshot({ path: `${folder}/${file}` });
          const pixels = await comparePixels(page, png, bounds, references[photo.id]);
          assert(pixels.maximumError < 12, `${label}: bitmap/name mismatch or blank frame (${JSON.stringify(pixels)})`);
          assert(bounds.height >= (mobile ? height * .65 : 800), 'Approved photo-first layout regressed');
          frames.push({ label, file, at: Date.now(), photo: photo.displayName, bounds, pixels });
          await writeFile(`${folder}/${file}.aria.txt`, await page.locator('body').ariaSnapshot());
        };
        const heldFrames = async (label, photo) => {
          for (let i = 0; i < 4; i++) { await capture(`${label}-${i}`, photo); await page.waitForTimeout(100); }
        };
        const metadata = async photo => {
          if (mobile) await tap('Abrir curadoria e metadados');
          const inspector = page.getByLabel('Painel de curadoria da fotografia', { exact: true });
          await inspector.waitFor();
          const text = await inspector.ariaSnapshot();
          const ref = references[photo.id];
          assert(text.includes(ref.metadata.camera), `Wrong camera for ${photo.displayName}`);
          assert(text.includes(ref.metadata.lens), `Wrong lens for ${photo.displayName}`);
          if (mobile) await tap('Fechar curadoria e metadados');
          await page.waitForTimeout(100);
        };
        try {
          await page.goto(`${base}/?photo=${a.id}`);
          await settled(a); await capture('established', a);
          await untilRequest('detail', b);
          const beforeNavigation = new Set(requests.filter(r => r.kind === 'detail').map(r => r.id));
          assert(beforeNavigation.size <= 3, 'Preparation must not walk the collection');
          await tap('Próxima fotografia');
          await heldFrames('detail-held', a); await metadata(a);
          releaseBDetail(); await untilRequest('jpeg', b);
          await heldFrames('jpeg-held', a); await metadata(a);
          releaseBJpeg(); await settled(b); await capture('committed-b', b); await metadata(b);
          const count = photo => requests.filter(r => r.kind === 'jpeg' && r.dimension === '1600' && r.id === photo.id).length;
          const aLoads = count(a), bLoads = count(b);
          await tap('Fotografia anterior'); await settled(a); await capture('normal-back', a);
          await tap('Próxima fotografia'); await settled(b); await capture('normal-next', b);
          assert.equal(count(a), aLoads, 'Back should reuse the bounded decoded preview');
          assert.equal(count(b), bLoads, 'Forward should reuse the bounded decoded preview');

          failDetails.add(c.id);
          await tap('Próxima fotografia');
          await page.getByText('Não foi possível carregar a fotografia.', { exact: false }).waitFor();
          await capture('detail-failed-keeps-b', b);
          failDetails.delete(c.id);
          await tap('Atualizar snapshot');
          await page.getByText('Prévia indisponível.', { exact: false }).waitFor();
          await capture('jpeg-failed-keeps-b', b);
          failPreviews.delete(c.id);
          await tap('Atualizar snapshot'); await settled(c); await capture('retry-commits-c', c); await metadata(c);

          await tap('Próxima fotografia'); await untilRequest('detail', d);
          await tap('Próxima fotografia'); await tap('Próxima fotografia'); await tap('Fotografia anterior');
          await capture('rapid-input-detail-held', c);
          releaseDDetail(); await untilRequest('jpeg', e);
          await heldFrames('latest-intent-jpeg-held', c);
          await tap('Fotografia anterior'); await settled(d); await capture('reversed-to-d', d);
          releaseEJpeg(); await page.waitForTimeout(350); await capture('late-e-cannot-win', d);
          await tap('Aumentar zoom'); await page.getByText('125%', { exact: true }).waitFor();
          await tap('Ajustar fotografia à janela'); await page.getByText('100%', { exact: true }).waitFor();
          await metadata(d); await metadata(d); await capture('zoom-and-panel-reopened', d);
          if (mobile) {
            await tap('Abrir miniaturas'); await button('Fechar miniaturas').waitFor();
            await tap('Fechar miniaturas'); await capture('filmstrip-reclosed', d);
          }
          await tap('Próxima fotografia'); await settled(e);
          await tap('Próxima fotografia'); await untilRequest('jpeg', f);
          await capture('close-while-f-pending', e);
          await tap('Voltar à biblioteca'); releaseFJpeg();
          await page.getByRole('textbox', { name: 'Pesquisar fotografias', exact: true }).waitFor();
          await page.waitForTimeout(350);
          assert.equal(await page.getByLabel('Visualizador da fotografia', { exact: true }).count(), 0);
          assert.equal(new URL(page.url()).searchParams.get('photo'), null);
          // Browser Back is a second independent exit path from a gallery-opened detail.
          const tile = page.getByRole('button', { name: new RegExp(`^Fotografia ${e.displayName.replace('.', '\\.')},`) });
          const box = await tile.boundingBox();
          assert(box);
          if (mobile) await page.touchscreen.tap(box.x + box.width / 2, box.y + box.height / 2);
          else await page.mouse.click(box.x + box.width / 2, box.y + box.height / 2);
          await settled(e); await page.goBack();
          await page.getByRole('textbox', { name: 'Pesquisar fotografias', exact: true }).waitFor();
          assert.deepEqual(writes, []); assert.deepEqual(errors, []);
          results.push({ engine: name, version: browser.version(), viewport: { width, height }, frames: frames.length,
            passed: true, writes, errors, decodedPreviewReuse: true, preparationDetailIds: [...beforeNavigation] });
          console.log(`PASS transitions ${name} ${width}x${height}: ${frames.length} pixel-checked frames, separate detail/JPEG holds, retry, rapid reversal, back, zoom and panels`);
        } catch (error) {
          await page.screenshot({ path: `${folder}/FAILED.png` });
          await writeFile(`${folder}/FAILED.aria.txt`, await page.locator('body').ariaSnapshot());
          await writeFile(`${folder}/failure.json`, JSON.stringify({ failure: error.stack, errors, writes }, null, 2));
          throw error;
        } finally {
          releaseBDetail(); releaseBJpeg(); releaseDDetail(); releaseEJpeg(); releaseFJpeg();
          await writeFile(`${folder}/sequence.json`, JSON.stringify({ frames, requests, writes, errors }, null, 2));
          await context.close();
        }
      }
    } finally {
      await browser.close();
      await writeFile(`${output}/transition-results.json`, JSON.stringify({ results, physicalIPhoneTested: false }, null, 2));
    }
  }
}

export async function comparePixels(page, png, bounds, reference) {
  return page.evaluate(async ({ png, bounds, reference }) => {
    const decode = async src => { const image = new Image(); image.src = src; await image.decode(); return image; };
    const actual = await decode(`data:image/png;base64,${png}`);
    const expected = await decode(`data:image/jpeg;base64,${reference.jpeg}`);
    const canvas = document.createElement('canvas'); canvas.width = actual.width; canvas.height = actual.height;
    const ctx = canvas.getContext('2d'); ctx.drawImage(actual, 0, 0);
    const height = Math.min(bounds.height, bounds.width / reference.aspectRatio), width = height * reference.aspectRatio;
    const x = bounds.x + (bounds.width - width) / 2, y = bounds.y + (bounds.height - height) / 2;
    const points = [[.19, .19], [.38, .18], [.78, .19], [.19, .61], [.39, .61], [.78, .61], [.19, .83], [.39, .83], [.78, .83]];
    const samples = points.map(([px, py]) => [...ctx.getImageData(Math.round(x + px * width), Math.round(y + py * height), 1, 1).data].slice(0, 3));
    ctx.clearRect(0, 0, canvas.width, canvas.height); ctx.drawImage(expected, x, y, width, height);
    const errors = points.map(([px, py], index) => {
      const rgb = ctx.getImageData(Math.round(x + px * width), Math.round(y + py * height), 1, 1).data;
      return Math.max(...samples[index].map((v, channel) => Math.abs(v - rgb[channel])));
    });
    return { maximumError: Math.max(...errors), samples };
  }, { png: png.toString('base64'), bounds, reference });
}
