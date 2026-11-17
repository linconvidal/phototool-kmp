import { test } from 'node:test';
import assert from 'node:assert/strict';
import { installWebKitPresentationWorkaround } from '../webApp/src/wasmJsMain/resources/webgl-presentation.mjs';

function fixture(userAgent = 'AppleWebKit/605.1.15 Version/26.5 Safari/605.1.15') {
  const frames = [];
  const calls = [];
  class Canvas {
    isConnected = true;
    lost = false;
    flushes = 0;
    getContext(type, attributes) {
      calls.push({ canvas: this, type, attributes });
      return { canvas: this, flush: () => this.flushes++, isContextLost: () => this.lost };
    }
  }
  const host = { navigator: { userAgent }, HTMLCanvasElement: Canvas, requestAnimationFrame: callback => frames.push(callback) };
  return { host, frames, calls, Canvas };
}

test('only WebKit is changed; Chromium and Firefox keep their context and frame functions', () => {
  for (const agent of ['AppleWebKit/537.36 Chrome/151.0 Safari/537.36', 'Gecko/20100101 Firefox/153.0']) {
    const { host, Canvas } = fixture(agent);
    const context = Canvas.prototype.getContext;
    const frame = host.requestAnimationFrame;
    assert.equal(installWebKitPresentationWorkaround(host), false);
    assert.equal(Canvas.prototype.getContext, context);
    assert.equal(host.requestAnimationFrame, frame);
  }
});

test('WebKit preserves WebGL buffers without mutating options or 2D contexts', () => {
  const { host, calls, Canvas } = fixture();
  assert.equal(installWebKitPresentationWorkaround(host), true);
  const original = { alpha: false, preserveDrawingBuffer: false };
  new Canvas().getContext('webgl2', original);
  new Canvas().getContext('2d', original);
  assert.deepEqual(calls[0].attributes, { alpha: false, preserveDrawingBuffer: true });
  assert.equal(calls[1].attributes, original);
  assert.equal(original.preserveDrawingBuffer, false);
});

test('flush follows requested frames, without creating a perpetual animation loop', () => {
  const { host, frames, Canvas } = fixture();
  installWebKitPresentationWorkaround(host);
  const canvas = new Canvas();
  canvas.getContext('webgl');
  let time;
  host.requestAnimationFrame(value => { time = value; assert.equal(canvas.flushes, 0); });
  frames[0](42);
  assert.equal(time, 42);
  assert.equal(canvas.flushes, 1);
  assert.equal(frames.length, 1);
  canvas.isConnected = false;
  host.requestAnimationFrame(() => {});
  frames[1](43);
  assert.equal(canvas.flushes, 1);
});

test('installation is idempotent and callback failures are not hidden', () => {
  const { host, frames, Canvas } = fixture();
  installWebKitPresentationWorkaround(host);
  const frame = host.requestAnimationFrame;
  installWebKitPresentationWorkaround(host);
  assert.equal(host.requestAnimationFrame, frame);
  const canvas = new Canvas();
  canvas.getContext('webgl');
  host.requestAnimationFrame(() => { throw new Error('callback failed'); });
  assert.throws(() => frames[0](1), /callback failed/);
  assert.equal(canvas.flushes, 1);
  canvas.lost = true;
  assert.throws(() => frames[0](2), /callback failed/);
  assert.equal(canvas.flushes, 1);
});
