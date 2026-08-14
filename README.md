# phototool-kmp

PhotoTool is a Kotlin Multiplatform and Compose Multiplatform photo curation application. The desktop application indexes CR2, CR3, DNG, RAF, JPG, and JPEG files. Editorial changes are stored only in adjacent XMP sidecars. Shared domain, state, filtering, and production UI code also compile for Android and iOS. Desktop is the only fully functional release target; Android and iOS are explicitly compile-only shared-UI hosts.

## Safety contract

RAW and JPEG files are immutable. Application construction never scans a library. Synchronization starts only after the user chooses **Synchronize** or runs the explicit smoke command. Cache and thumbnail paths must be outside the selected library.

Desktop launches are read only unless `--enable-write` is supplied. Write mode is limited to canonical adjacent XMP and an existing editable exact-stem FP2 file. FP3 is always read only. Before a mutation, PhotoTool pins the library root and traverses every directory component through no-follow `SecureDirectoryStream` handles, then checks the file key, size, modification time, complete exact-stem topology, sidecar bytes, and link safety. Write mode fails closed on hosts without secure descriptor-relative access. A semantic no-op keeps exact bytes and modification time. Changed files use a fsynced sibling temporary file, no-replace installation, authoritative readback, a unique `.previous` artifact for replaced bytes, and a unique `.conflict` artifact if concurrent bytes are displaced. Publication uses descriptor-relative same-directory move operations with a conservative displace, compare, restore, and fail-closed sequence. Recovery artifacts block further writes when the canonical authority is absent.

Test write mode only on a copied library.

## Delivered behavior

- Exact case-insensitive stem pairing within one directory, RAW technical authority, and exact JPEG preview authority for pairs.
- Kim 0.26.2 metadata and embedded RAW JPEG extraction implementation for CR2, CR3, DNG, and RAF. Format-level release attestation still requires redistributable representative RAW fixtures.
- Bounded, downsampled disk thumbnails keyed by indexed media identity, decode validation, safe placeholders, and bounded least-recently-used cleanup.
- Startup loading of a valid complete SQLite snapshot without scanning.
- Indexed capture time, camera make and model, lens, focal length, aperture, exposure, ISO, GPS, dimensions, metadata status and error, editorial fields, and normalized keyword rows.
- Search, exact keyword, date interval, flag, camera, lens, minimum rating, and GPS filters with query-aware detail neighbors.
- Real gallery and detail images, three-region wide layout, 264 dp facets, 350 dp inspector, adaptive narrow overlays, staggered aspect-preserving cards, visible selection, spatial arrow selection, and shortcut help.
- Detail editorial controls, flat and hierarchical keyword editing, observed metadata, external OpenStreetMap links, Fuji recipe controls, Lightroom HDR controls, and only-evidenced Fuji and Lightroom transfer actions.
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

Production-layout Compose evidence using injected illustrative synthetic JPEG fixtures is written to:

- `desktopApp/build/evidence/gallery.png`
- `desktopApp/build/evidence/filters.png`
- `desktopApp/build/evidence/detail.png`
- `desktopApp/build/evidence/empty.png`
- `desktopApp/build/evidence/sync.png`
- `desktopApp/build/evidence/error.png`
- `desktopApp/build/evidence/narrow-inspector.png` (320 dp modal inspector)

Lightroom runtime compatibility still requires external acceptance with an installed Lightroom version and a copied catalog. The automated suite verifies the documented XMP Camera Raw DOM mappings and round trips without launching Lightroom. Representative CR2/CR3/DNG/RAF decoder evidence and packaged GUI launch behavior remain external release validation.
