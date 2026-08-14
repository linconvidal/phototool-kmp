# Project rules

- RAW and JPEG files are immutable. Persist editorial changes only in adjacent XMP sidecars.
- Never scan on application construction. Synchronization must follow an explicit user action.
- Cache paths must remain outside the selected library.
- Keep shared domain, reducer, and Compose UI multiplatform-compatible. Keep JVM filesystem and SQLite code in desktopApp.
- Add tests for every safety boundary and run against temporary copied fixtures only.
- Use Kotlin 2.3.21, Compose Multiplatform 1.11.0, Gradle 9.1, and JDK 21.
