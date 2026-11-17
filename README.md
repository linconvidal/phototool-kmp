# phototool-kmp

PhotoTool is a Kotlin Multiplatform and Compose Multiplatform photo curation application. The desktop application indexes CR2, CR3, DNG, RAF, JPG, and JPEG files. Editorial changes are stored only in adjacent XMP sidecars. Shared domain, state, filtering, and production UI code also compile for Android, iOS, and WebAssembly. Desktop is the fully functional release target; Android and iOS are compile-only hosts. The web candidate uses the same production Compose UI with a read-only HTTP adapter.

## Safety contract

RAW and JPEG files are immutable. Application construction never scans a library. Synchronization starts only after the user chooses **Synchronize** or runs the explicit smoke command. Normal synchronization never prebuilds thumbnails. Cache and thumbnail paths must be outside the selected library.

Desktop launches are read only by default. Settings is the single source of truth for write mode: the read-only toggle works in both directions and the preference persists in the cache directory, so it survives restarts. The `--enable-write` and `--read-only` flags only seed the initial value on the first run, before any preference has been saved. Write mode is limited to canonical adjacent XMP and an existing editable exact-stem FP2 file. FP3 is always read only. Before a mutation, PhotoTool pins the library root and traverses every directory component through no-follow `SecureDirectoryStream` handles, then checks the file key, size, modification time, complete exact-stem topology, sidecar bytes, and link safety. Write mode fails closed on hosts without secure descriptor-relative access. A semantic no-op keeps exact bytes and modification time. Changed files use a fsynced sibling temporary file, authoritative readback, a unique `.previous` artifact for replaced bytes, and a unique `.conflict` artifact if concurrent bytes are displaced. Publication uses descriptor-relative same-directory move operations with a conservative displace, compare, restore, and fail-closed handling for detected identity/content divergence. Same-process edits are serialized. Fuji↔XMP transfers hold one process-wide reentrant lock per library/photo from source read through target readback, and revalidate the source immediately before the target's final move. The JVM does not expose an atomic transaction across the FP2 and XMP files (nor a portable no-replace flag for every final move), so non-cooperating external writers must keep the copied library quiescent during an enabled-write session. Recovery artifacts block further writes when the canonical authority is absent.

Test write mode only on a copied library.

## Delivered behavior

- Exact case-insensitive stem pairing within one directory, RAW technical authority, and exact JPEG preview authority for pairs.
- Kim 0.26.2 metadata and embedded RAW JPEG extraction implementation for CR2, CR3, DNG, and RAF. Format-level release attestation still requires redistributable representative RAW fixtures.
- Bounded, orientation-aware disk previews generated on demand for visible photos, with up to four loads in parallel. They are keyed by indexed media identity, requested dimension, and cache version, use one high-quality downscale, validated JPEG publication, safe placeholders, deduplicated generation, and throttled least-recently-used cleanup.
- Startup loading of a valid complete SQLite snapshot without scanning.
- Indexed capture time, camera make and model, lens, focal length, aperture, exposure, ISO, GPS, dimensions, metadata status and error, editorial fields, and normalized keyword rows.
- Search with inclusive terms, quoted phrases, and negative terms such as `-personal` or `-"São Paulo"`; exact keyword, Gregorian date interval validation, flag, camera, lens, minimum rating, and GPS filters remain composable with query-aware detail neighbors. New keywords are flat; observed hierarchical paths remain readable and exactly removable.
- Real gallery and detail images, contact-sheet proof annotations for formats such as `JPEG`, `RAF`, or `CR3 + JPEG`, and explicit RAW/JPEG authority rows in the detail inspector. Detail zoom runs from 100% to 800% with a persistent visible level, wheel/pinch zoom, bounded panning and fit reset. The application also includes a low-height-scrollable 58 dp navigation rail, responsive toolbar, 370 dp detail inspector, non-blocking in-root progressive filters, a responsive masonry gallery with bounded native proportions, viewport-bounded quick curation, visible selection, spatial arrow selection, a detail filmstrip, and shortcut help. Text inputs keep the Material minimum readable height instead of clipping labels or entered text.
- Detail editorial controls, flat keyword creation with conservative hierarchical preservation, observed metadata, external OpenStreetMap links, Fuji recipe controls, Lightroom HDR controls, and only-evidenced Fuji and Lightroom transfer actions. Every editorial batch is bounded to the original safe limit of 100 photographs and reports a request without inventing global success; per-photo indicators remain authoritative. Sensitive batches are explicit per channel, bounded to the same 100-photo limit, continue after individual failures, and report saved, ignored, and failed counts without claiming cross-file atomicity. Fuji batch commands alter only the explicitly chosen simulation, exposure, or DR field. HDR batch commands expose maximum 1–4 plus all seven SDR controls; individual HDR maximum is also editable from 1–4.
- Calendar navigation drills from year to month to observed day. Synchronization becomes determinate after pairing and restores a bounded path-free summary with duration and photo deltas without scanning.
- XMP DOM preservation for unknown namespaces, elements, attributes, and comments. Changed writes retain the declaration policy, encoding, BOM, and newline style. Managed duplicates, contradictions, malformed XML, and incomplete HDR blocks fail closed.
- Full X-Pro2 FP2 and FP3 parsing for the evidenced field set and ranges. Existing editable FP2 files can be changed with exact no-op and readback guarantees. FP3 is never changed.
- iOS arm64 and iOS simulator arm64 static framework configuration. Android and iOS remain compile-only shared UI hosts; desktop owns the functional filesystem, SQLite, synchronization, and editing services.

The UI does not use animated navigation transitions, so reduced-motion users are not required to disable application animation.

## Requirements

- JDK 21
- Android SDK for Android compilation
- Linux packaging with `dpkg-deb` for the validated DEB output

The project pins Kotlin 2.3.21, Compose Multiplatform 1.11.0, Gradle 9.1, and Kim 0.26.2.

## Run desktop

```bash
./gradlew --no-daemon :desktopApp:run
./gradlew --no-daemon :desktopApp:run --args="--library /path/to/copied-library --cache /path/outside/library/cache --read-only"
./gradlew --no-daemon :desktopApp:run --args="--library /path/to/copied-library --cache /path/outside/library/cache --enable-write"
```

Without `--library`, choose a folder in Settings. The default cache is `~/.cache/phototool-kmp`.

On Linux Wayland, the GUI defaults to Skiko software rendering so display or GPU context loss during screen lock does not leave the window frozen after unlock. An explicit `skiko.renderApi` property or `SKIKO_RENDER_API` environment variable remains authoritative.

## Shared Compose Web candidate

`webApp` calls `shared/.../ui/PhotoToolApp.kt` through `ComposeViewport`. Gallery, filters, detail, zoom, filmstrip and editorial controls are the existing Compose components. `RemoteCurationStore` supplies one server-ordered page and query-aware detail neighbors; it does not filter incomplete HTTP summaries locally. `BrowserCurationApi` is GET-only, and `HttpPreviewLoader` loads same-origin JPEG previews with four concurrent loads and a 32 MiB decoded cache budget. Filmstrip thumbnails cover the loaded page; detail arrows can navigate beyond it.

```bash
# Build and test the pinned Kotlin 2.3.21 / Compose 1.11.0 implementation
ANDROID_HOME=/home/linconvidal/Android/Sdk CHROME_BIN=/usr/bin/google-chrome ./gradlew --no-daemon :webApp:wasmJsBrowserDistribution :shared:jvmTest :shared:wasmJsBrowserTest :desktopApp:test :desktopApp:compileKotlin
node --test scripts/webgl-presentation.test.mjs scripts/compose-input-focus.test.mjs

# Serve a fresh copied synthetic fixture (36 JPEGs, external disposable cache), without scanning
./gradlew --no-daemon :desktopApp:composeWebCandidate --args=8893
# http://127.0.0.1:8893/

# Alternatively, use an already-published snapshot in an isolated copied library/cache
./gradlew --no-daemon :desktopApp:run --args="web --library /path/to/copied-library --cache /path/outside/library/cache --port 8893 --read-only"

# Rendered interaction checks against the synthetic candidate; requires Playwright + its browsers
# PLAYWRIGHT_MODULE can name an existing absolute path to playwright/index.mjs
node scripts/check-compose-web.mjs http://127.0.0.1:8893 /tmp/phototool-compose-evidence
```

Desktop resources package `webApp/build/dist/wasmJs/productionExecutable/`. The server serves that bundle at `/`; the previous `src/main/resources/web/` implementation is retained locally but excluded from resource processing and no longer routed. HTML/CSS/JavaScript in the candidate only initializes the viewport, reports startup failure and handles renderer compatibility. No second set of screens is implemented there.

This candidate cannot enable writes, synchronize, choose local folders or edit metadata, even if a server advertises write capability. Fuji/HDR, transfers and paired-file names are not exposed by the current API. Calendar and hierarchical folder browsing are disabled rather than inferred from a partial page; folder facets exist in the API but are not integrated here. Settings explains these limits. No published service is replaced by these commands.

### Browser requirements and validation limits

- [Compose 1.11 requires Kotlin 2.3.10+ for web](https://kotlinlang.org/docs/multiplatform/whats-new-compose-111.html); the pinned 2.3.21 is retained.
- [Kotlin/Wasm requires WasmGC](https://kotlinlang.org/docs/wasm-configuration.html): Chrome 119+, Firefox 120+, Safari/WebKit 18.2+. [Safari 18.2 ships on iOS 18.2](https://webkit.org/blog/16301/webkit-features-in-safari-18-2/). There is no legacy JavaScript fallback.
- Chromium desktop/mobile emulation and GTK WebKit 26.5 were exercised with real API responses and copied synthetic JPEGs. These are **not physical iPhone acceptance**. VoiceOver, device memory, battery use and the full canonical library remain outside this candidate's validation.
- GTK WebKit exhibited stale/blank canvas frames after asynchronous image loads and resizing. `webgl-presentation.mjs` preserves WebGL buffers and flushes requested frames only on WebKit, using the workaround discussed in [CMP-11109](https://youtrack.jetbrains.com/issue/CMP-11109). Automated captures pass with it; physical Safari still needs review. It adds framebuffer memory and must be reassessed when upgrading Compose/Skiko. Web rail tooltips are omitted and the existing mobile menu items stay in-root to avoid stale accessibility mirrors from transient popup owners in Compose 1.11.
- `compose-input-focus.mjs` applies two iOS WebKit input guards: the active-input check from [Compose PR #3446](https://github.com/JetBrains/compose-multiplatform-core/pull/3446) ([CMP-10825](https://youtrack.jetbrains.com/issue/CMP-10825)), and the consumed-release `touchend` cancellation from [PR #3232](https://github.com/JetBrains/compose-multiplatform-core/pull/3232) ([CMP-10438](https://youtrack.jetbrains.com/issue/CMP-10438)). Canceling `pointerup` alone does not prevent the browser's subsequent touch defaults from moving focus. The latter guard listens after Compose's canvas handler, cancels only the matching consumed touch release and resets on cancellation or a new touch. It leaves unconsumed gestures, unrelated elements and non-iOS browsers unchanged; it does not force focus or replace controls. Remove both guards when the pinned Compose version contains the fixes. `scripts/check-keyword-focus.mjs` checks trusted touch consumption, search, keywords, focus, typing, resize, reopening and horizontal/vertical navigation; `fixture-write` permits keyword persistence tests only against a 36-photo loopback fixture. Browser emulation does not verify the physical iPhone keyboard.
- The initial JS/Wasm payload is approximately 13.3 MiB uncompressed. Skiko's renderer-info probe emits a recorded WebGL diagnostic in the automated browsers; runtime/CSP errors are checked separately.

## Arquivo fotográfico pela linha de comando

Os subcomandos `archive` são headless e são decididos antes da construção da janela Compose. As duas raízes precisam existir e ser diretórios fisicamente distintos e não sobrepostos; a validação segue aliases de symlink e compara caminho canônico, `FileStore` e `fileKey`.

```bash
# Verificação estritamente somente leitura, com SHA-256 em streaming
./gradlew --no-daemon :desktopApp:run --args="archive verify --card /media/CARD/DCIM --archive /mnt/archive/imgs"

# A verificação apenas por tamanho é diagnóstica e nunca autoriza apagar o cartão
./gradlew --no-daemon :desktopApp:run --args="archive verify --card /media/CARD/DCIM --archive /mnt/archive/imgs --size-only"

# Importação que somente copia e preserva vídeos por padrão
./gradlew --no-daemon :desktopApp:run --args="archive import --source /media/CARD/DCIM --destination /mnt/archive/imgs"
./gradlew --no-daemon :desktopApp:run --args="archive import --source /media/CARD/DCIM --destination /mnt/archive/imgs --skip-videos"

# Prévia diagnóstica itemizada; NÃO sincroniza nem oferece token de execução
./gradlew --no-daemon :desktopApp:run --args="archive rsync --source /mnt/archive/imgs --destination /mnt/backup/imgs --checksum"

# --delete também produz somente uma prévia diagnóstica; nenhuma execução rsync é permitida
./gradlew --no-daemon :desktopApp:run --args="archive rsync --source /mnt/archive/imgs --destination /mnt/backup/imgs --checksum --delete"
```

A importação ignora nomes ocultos, recusa links simbólicos, hardlinks (inclusive alias fora da raiz) e filesystems/mountpoints internos detectáveis, não move nem sobrescreve mídia e mantém original, derivados e sidecars no mesmo mês. O manifesto é produzido por traversal relativo a descritores; para fotografias, a metadata é lida somente de uma cópia temporária bounded criada fora das duas raízes, validada por identidade e SHA-256 e removida pelo próprio processo. A data observada pela infraestrutura Kim tem prioridade para fotografias; esta versão não extrai data interna de vídeo, portanto vídeos usam `mtime`. Um `mtime` plausível é o fallback e os demais arquivos vão para `Unknown-Date`. Cada cópia reserva o nome final relativamente ao descritor com `CREATE_NEW`/`NOFOLLOW_LINKS`, grava e faz `fsync` nesse handle, confirma SHA-256 e revalida a origem sem jamais substituir uma entrada existente; o host falha fechado se `SecureDirectoryStream` ou a prova Linux de `nlink` não estiver disponível. Uma queda do processo durante a gravação pode deixar um arquivo final parcial: ele não é tratado como sucesso nem sobrescrito na repetição, e deve ser removido manualmente somente após diagnóstico de hash/identidade. Como a JVM não oferece `mkdir` relativo a esse descritor, todas as pastas mensais necessárias (`AAAA.MM` e, quando aplicável, `Unknown-Date`) devem existir como diretórios reais antes da importação; a ausência é detectada no preflight e nenhuma cópia começa. Conteúdo idêntico é ignorado; uma colisão diferente é preservada como `__dupN`.

A verificação reconhece apenas sufixos `__dupN` gerados depois do nome original, não escreve em nenhuma raiz e percorre, reabre e hasheia entradas relativamente a `SecureDirectoryStream`, com `NOFOLLOW_LINKS`, identidade dos intermediários e recusa de filesystems internos detectáveis. Cartão vazio, hardlink, entrada insegura/ilegível ou troca de identidade bloqueiam o veredito. “SEGURO PARA APAGAR” exige SHA-256 completo **e `FileStore` distinto**; raízes no mesmo volume continuam sendo diagnosticadas, mas sempre retornam bloqueio. `FileStore` distinto não comprova discos/hardware fisicamente independentes. Mantenha o cartão quiescente e preferencialmente somente leitura até a decisão humana posterior de apagar. Resultado inseguro ou inconclusivo retorna `1`, uso inválido retorna `2`, e `--size-only` nunca autoriza apagar.

O subcomando `archive rsync` executa **somente uma prévia diagnóstica `--dry-run`**, rotulada “NÃO EXECUTADA”. Não existe token de confirmação e nenhuma sincronização real, com ou sem `--delete`, está disponível: caminhos textuais do rsync mantêm uma janela TOCTOU que a JVM não consegue eliminar. A prévia usa lista de argumentos sem shell, executável absoluto fixado, saída limitada e, no Linux, grupo de processos isolado por `setsid`; timeout mata e aguarda o grupo inteiro ou falha fechado antes do lançamento quando esse isolamento não pode ser comprovado. O total itemizado é calculado antes do limite visual e todas as exclusões são mostradas.

Teste importação e a prévia somente com cópias. A ferramenta nunca apaga o cartão e não sincroniza via rsync; o veredito de verificação é apenas um gate explícito para uma decisão humana posterior.

## Read-only smoke verification

The smoke harness does not open a window and never writes XMP or FP2. It scans only the supplied library, extracts metadata, builds and decodes bounded thumbnails, publishes and reloads the index, prints bounded JSON, and exits.

```bash
./gradlew --no-daemon :desktopApp:smoke --args="--library /path/to/copied-library --cache /path/outside/library/cache"
```

The `smoke` task supplies `--smoke --read-only` automatically.

## Test and package

```bash
ANDROID_HOME=/home/linconvidal/Android/Sdk ./gradlew --no-daemon :shared:jvmTest :desktopApp:test :desktopApp:compileKotlin :androidApp:assembleDebug :androidApp:lintDebug
# macOS CI also runs simulator tests and links simulator plus device release frameworks:
./gradlew --no-daemon :shared:iosSimulatorArm64Test :shared:linkReleaseFrameworkIosSimulatorArm64 :shared:linkReleaseFrameworkIosArm64
ANDROID_HOME=/home/linconvidal/Android/Sdk ./gradlew --no-daemon :desktopApp:packageDeb
./scripts/package-host.sh
git diff --check
```

Production-layout Compose evidence using injected illustrative JPEG fixtures is written to `desktopApp/build/evidence/`:

- Gallery and responsiveness: `gallery-normal.png`, `all-photos-1280x800.png`, `library-900x600.png`, and `library-600x800.png`.
- Curation: `quick-editor.png`, `quick-editor-narrow-600x480.png`, `multi-selection.png`, `multi-selection-900x600.png`, `batch-editorial-result-1280x760.png`, `batch-fuji-hdr-controls-1280x900.png`, and `batch-hdr-result-1280x900.png`.
- Navigation and filters: `calendar.png`, `calendar-months.png`, `calendar-days.png`, `folders.png`, `filters-overlay.png`, `filters-narrow-600x800.png`, and `active-filters.png`.
- Detail and exceptional states: `detail.png`, `detail-zoom-150.png`, `detail-hdr-editable.png`, `detail-low-600x360.png`, `detail-filmstrip-index-10-600x700.png`, `settings.png`, `settings-running.png`, `error.png`, `photo-error-indicator.png`, `photo-error-explained-900x600.png`, `rail-low-600x360.png`, `rail-low-scrolled-600x360.png`, and `empty.png`.
- Optional copied-library validation: `showcase-gallery.png`, `showcase-detail.png`, `showcase-calendar.png`, and `showcase-folders.png`, generated only when `PHOTOTOOL_SHOWCASE_LIBRARY` and `PHOTOTOOL_SHOWCASE_CACHE` are supplied.

`desktopApp/build/evidence/README.md` records provenance for the generated evidence set.

Lightroom runtime compatibility still requires external acceptance with an installed Lightroom version and a copied catalog. The automated suite verifies the documented XMP Camera Raw DOM mappings and round trips without launching Lightroom. Representative CR2/CR3/DNG/RAF decoder evidence and packaged GUI launch behavior remain external release validation.

Filtering and ordering still run synchronously over the current in-memory snapshot. This keeps reducer semantics deterministic, but very large libraries may cause UI latency; an asynchronous generation-keyed projection remains a measured follow-up rather than an unevidenced concurrency change.
