// Startup and platform compatibility only. All application UI is shared Kotlin Compose.
import { installWebKitPresentationWorkaround } from './webgl-presentation.mjs';
import { installComposeInputFocusWorkaround } from './compose-input-focus.mjs';
installWebKitPresentationWorkaround();
installComposeInputFocusWorkaround();
import('./phototool.js').catch((error) => {
  const status = document.getElementById('startup') || document.body.appendChild(document.createElement('p'));
  status.id = 'startup';
  status.textContent = 'Não foi possível iniciar o PhotoTool. Requer Safari/iOS 18.2+, Chrome 119+ ou Firefox 120+, com WebAssembly habilitado. Atualize o navegador e recarregue.';
  console.error('PhotoTool Compose initialization failed', error);
});
