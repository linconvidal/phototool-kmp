// WebKit can retain stale/blank Skiko frames although Compose semantics and image loads completed.
// The presentation workaround discussed in CMP-11109 also fixes our GTK WebKit 26.5 reproduction.
// This is page-local renderer setup, not application UI. Recheck when upgrading pinned Compose/Skiko.
// https://youtrack.jetbrains.com/issue/CMP-11109
export function installWebKitPresentationWorkaround(host = window) {
  const agent = host.navigator.userAgent;
  if (!/AppleWebKit/.test(agent) || /Chrome\/|Chromium\/|Edg\/|OPR\//.test(agent)) return false;
  const marker = Symbol.for('phototool.webkit.presentation');
  if (host[marker]) return true;
  host[marker] = true;

  const contexts = new Set();
  const getContext = host.HTMLCanvasElement.prototype.getContext;
  host.HTMLCanvasElement.prototype.getContext = function (type, attributes) {
    if (type !== 'webgl' && type !== 'webgl2') return getContext.call(this, type, attributes);
    const context = getContext.call(this, type, { ...attributes, preserveDrawingBuffer: true });
    if (context) contexts.add(context);
    return context;
  };
  const requestFrame = host.requestAnimationFrame.bind(host);
  host.requestAnimationFrame = callback => requestFrame(time => {
    try { callback(time); }
    finally {
      for (const context of contexts) {
        if (!context.canvas.isConnected) contexts.delete(context);
        else if (!context.isContextLost()) context.flush();
      }
    }
  });
  return true;
}
