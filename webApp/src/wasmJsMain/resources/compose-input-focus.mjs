// Compose 1.11 refocuses its backing input synchronously and again in rAF.
// iOS 27 replaces the pending user-initiated keyboard request with the rAF request,
// which is outside user interaction, then cancels the keyboard (upstream #3446).
// A separate missing touchend cancellation lets Safari move focus after Compose
// already handled a tap (upstream #3232, CMP-10438/CMP-10467). Both guards belong
// to the browser input bridge, not individual text fields. Remove after upgrading
// the pinned Compose toolchain to a version containing both upstream fixes.
export function installComposeInputFocusWorkaround(host = window) {
  const { userAgent, platform, maxTouchPoints } = host.navigator;
  const ios = /iPhone|iPad|iPod/.test(userAgent) || (platform === 'MacIntel' && maxTouchPoints > 1);
  if (!ios || !/AppleWebKit/.test(userAgent)) return false;
  const marker = Symbol.for('phototool.compose.input-focus');
  if (host[marker]) return true;
  host[marker] = true;
  preserveConsumedTouchFocus(host.document);
  const focus = host.HTMLElement.prototype.focus;
  host.HTMLElement.prototype.focus = function (...args) {
    // Only Compose's existing invisible input in this app's Shadow DOM. Do not
    // replace text controls, refocus on blur, or change unrelated native elements.
    const root = this.getRootNode();
    if ((this.tagName === 'INPUT' || this.tagName === 'TEXTAREA') &&
        root.host?.closest('#webApp') &&
        this.style.top.includes('--compose-internal-web-backing-input-top') &&
        root.activeElement === this) return;
    return focus.apply(this, args);
  };
  return true;
}

// Equivalent to the touchend branch of JetBrains/compose-multiplatform-core#3232.
// Compose 1.11 prevents pointerup when it consumes a release, but Pointer Events
// cancellation does not cancel the corresponding Touch Event's default actions.
// Listen in bubble phase, after the canvas handler has recorded consumption.
function preserveConsumedTouchFocus(document) {
  const consumedRelease = new WeakMap();
  const canvasOf = event => {
    const target = event.composedPath()[0];
    return target?.tagName === 'CANVAS' && target.getRootNode().host?.closest('#webApp')
      ? target : null;
  };
  for (const type of ['pointerdown', 'pointercancel', 'touchcancel']) {
    document.addEventListener(type, event => {
      const canvas = canvasOf(event);
      if (canvas) consumedRelease.delete(canvas);
    });
  }
  document.addEventListener('pointerup', event => {
    const canvas = canvasOf(event);
    if (canvas && event.pointerType === 'touch') consumedRelease.set(canvas, event.defaultPrevented);
  });
  document.addEventListener('touchend', event => {
    const canvas = canvasOf(event);
    if (!canvas) return;
    const consumed = consumedRelease.get(canvas);
    consumedRelease.delete(canvas); // Single-use, including non-cancelable releases.
    if (consumed && event.cancelable) event.preventDefault();
  }, { passive: false });
}
