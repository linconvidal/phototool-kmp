import { test } from 'node:test';
import assert from 'node:assert/strict';
import { installComposeInputFocusWorkaround } from '../webApp/src/wasmJsMain/resources/compose-input-focus.mjs';

function fixture(navigator = { userAgent: 'iPhone AppleWebKit/605.1.15 Version/27.0 Safari/604.1' }) {
  const calls = [];
  const root = { host: { closest: selector => selector === '#webApp' }, activeElement: null };
  class Element {
    tagName = 'INPUT';
    style = { top: 'calc(var(--compose-internal-web-backing-input-top) * 1px)' };
    root = root;
    getRootNode() { return this.root; }
    focus(...args) { calls.push({ element: this, args }); this.root.activeElement = this; }
  }
  const listeners = new Map();
  const document = { addEventListener(type, handler, options) { listeners.set(type, { handler, options }); } };
  const host = { navigator, HTMLElement: Element, document };
  const canvas = new Element(); canvas.tagName = 'CANVAS';
  const event = (type, options = {}) => {
    const e = { pointerType: 'touch', cancelable: true, defaultPrevented: false,
      composedPath: () => [canvas], preventDefault() { this.defaultPrevented = true; }, ...options };
    listeners.get(type)?.handler(e);
    return e;
  };
  return { host, Element, calls, root, canvas, event, listeners };
}

test('one native focus request survives the synchronous and rAF Compose duplicates', () => {
  const { host, Element, calls, root } = fixture();
  installComposeInputFocusWorkaround(host);
  const input = new Element();
  const options = { preventScroll: true };
  input.focus(options); // The user-initiated request must remain synchronous.
  assert.equal(root.activeElement, input);
  input.focus(); input.focus(); input.focus(); // Compose updateState and two rAF callbacks.
  assert.equal(calls.length, 1);
  assert.equal(calls[0].element, input);
  assert.equal(calls[0].args[0], options);
});

test('blur, a different input and a new editing session may acquire focus normally', () => {
  const { host, Element, calls, root } = fixture();
  installComposeInputFocusWorkaround(host);
  const input = new Element(), next = new Element(); next.tagName = 'TEXTAREA';
  input.focus(); root.activeElement = null; input.focus(); next.focus(); input.focus();
  assert.equal(calls.length, 4);
  assert.equal(root.activeElement, input);
});

test('ordinary inputs, other apps and non-input controls preserve repeated focus calls', () => {
  for (const configure of [
    e => { e.style.top = '0px'; },
    e => { e.root = { activeElement: null }; },
    e => { e.root = { host: { closest: () => null }, activeElement: null }; },
    e => { e.tagName = 'CANVAS'; },
  ]) {
    const { host, Element, calls } = fixture(); installComposeInputFocusWorkaround(host);
    const input = new Element(); configure(input); input.focus(); input.focus();
    assert.equal(calls.length, 2);
  }
});

test('desktop and non-iOS browsers are untouched; iPad desktop mode and iOS Chrome are covered', () => {
  for (const navigator of [
    { userAgent: 'AppleWebKit/537.36 Chrome/151 Safari/537.36', platform: 'Linux' },
    { userAgent: 'AppleWebKit/605.1.15 Version/26.5 Safari/605.1.15', platform: 'MacIntel', maxTouchPoints: 0 },
    { userAgent: 'Firefox/150', platform: 'Linux' },
  ]) {
    const { host, Element } = fixture(navigator); const original = Element.prototype.focus;
    assert.equal(installComposeInputFocusWorkaround(host), false);
    assert.equal(Element.prototype.focus, original);
  }
  for (const navigator of [
    { userAgent: 'iPhone AppleWebKit/605.1.15 CriOS/151 Mobile/15 Safari/604.1' },
    { userAgent: 'Macintosh AppleWebKit/605.1.15 Safari/605.1.15', platform: 'MacIntel', maxTouchPoints: 5 },
  ]) assert.equal(installComposeInputFocusWorkaround(fixture(navigator).host), true);
});

test('idempotent installation does not stack wrappers or touch listeners', () => {
  const { host, Element, listeners } = fixture(); installComposeInputFocusWorkaround(host);
  const installed = Element.prototype.focus, touchend = listeners.get('touchend');
  installComposeInputFocusWorkaround(host);
  assert.equal(Element.prototype.focus, installed);
  assert.equal(listeners.get('touchend'), touchend);
});

test('a consumed touch release cancels touchend once, after Compose, without refocusing', () => {
  const { host, event, calls, listeners } = fixture(); installComposeInputFocusWorkaround(host);
  event('pointerup', { defaultPrevented: true });
  assert.equal(event('touchend').defaultPrevented, true);
  assert.equal(event('touchend').defaultPrevented, false);
  assert.deepEqual(listeners.get('touchend').options, { passive: false });
  assert.deepEqual(calls, [], 'Never force focus or reopen a keyboard');
});

test('unconsumed and mouse releases retain browser defaults', () => {
  for (const release of [{}, { defaultPrevented: true, pointerType: 'mouse' }]) {
    const { host, event } = fixture(); installComposeInputFocusWorkaround(host);
    event('pointerup', release);
    assert.equal(event('touchend').defaultPrevented, false);
  }
});

test('cancellation and new touches clear pending release consumption', () => {
  for (const type of ['pointercancel', 'touchcancel', 'pointerdown']) {
    const { host, event } = fixture(); installComposeInputFocusWorkaround(host);
    event('pointerup', { defaultPrevented: true }); event(type);
    assert.equal(event('touchend').defaultPrevented, false);
  }
});

test('non-cancelable touchend resets consumption without calling preventDefault', () => {
  const { host, event } = fixture(); installComposeInputFocusWorkaround(host);
  event('pointerup', { defaultPrevented: true });
  event('touchend', { cancelable: false, preventDefault() { assert.fail('non-cancelable'); } });
  assert.equal(event('touchend').defaultPrevented, false);
});

test('ordinary controls and canvases outside this Compose root are untouched', () => {
  for (const configure of [
    c => { c.tagName = 'INPUT'; },
    c => { c.root = { host: { closest: () => null } }; },
    c => { c.root = {}; },
  ]) {
    const { host, event, canvas } = fixture(); installComposeInputFocusWorkaround(host);
    configure(canvas); event('pointerup', { defaultPrevented: true });
    assert.equal(event('touchend').defaultPrevented, false);
  }
});

test('unrelated gestures do not inherit cancellation and the shim does not intercept start/move', () => {
  const { host, event, listeners } = fixture(); installComposeInputFocusWorkaround(host);
  event('pointerup', { defaultPrevented: true }); event('pointerup');
  assert.equal(event('touchend').defaultPrevented, false);
  assert.equal(listeners.has('touchstart'), false);
  assert.equal(listeners.has('touchmove'), false);
});
