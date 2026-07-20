# HERO app

Native **Compose Multiplatform** (Android + desktop) client for HERO's control
plane — Phase 4 of the plan in the backend repo's `docs/control-plane.md`. It is
a pure client of the **v1 control-plane API** (`docs/control-plane-api.md` in the
backend repo): sign in, list your nodes, open a session, watch it stream, and
send messages.

> **Build environment.** This is a Kotlin/Gradle project, built with the Kotlin
> toolchain (Android Studio / IntelliJ, or `./gradlew`), **not** the Go backend.
> It is intentionally a separate repository so its Gradle/Kotlin/Android
> dependencies never enter the backend's auditable Go module (see the backend's
> `docs/control-plane.md` §repo layout).

## Layout

```
composeApp/
  build.gradle.kts               # androidTarget() + jvm("desktop"), signing
  keystore.jks                    # release signing key (committed; private repo)
  src/commonMain/kotlin/io/hero/app/
    Models.kt   # v1 API data types (NodeView, Session, Event)
    Api.kt      # Ktor client: login, nodes, sessions, send, events (SSE)
    App.kt      # Compose UI: login → nodes → sessions → live events + send
    Update.kt   # in-app updater (checkForUpdate + expect installUpdate)
  src/androidMain/…               # MainActivity, manifest, FileProvider, APK updater
  src/desktopMain/kotlin/main.kt  # desktop entry point + per-OS jar updater
gradle/libs.versions.toml         # version catalog
.github/workflows/release.yml     # tag v* → build APK + desktop jars → GitHub release
```

## Build & run

```sh
./gradlew :composeApp:run                              # desktop (JVM), quickest
./gradlew :composeApp:assembleRelease                  # signed Android APK
./gradlew :composeApp:packageReleaseUberJarForCurrentOS # desktop uber jar
# Android UI in an emulator/device: open in Android Studio, run composeApp
```

On first launch it asks for the **control-plane URL** (e.g.
`http://127.0.0.1:7801`) and your username/password, then talks to the same
endpoints the web console uses.

## Releases & in-app update

Pushing a `v*` tag (or running the **Release** workflow manually) builds on
GitHub Actions and publishes a release with:

- `hero-app.apk` — signed Android APK
- `hero-app-linux.jar` / `hero-app-macos.jar` / `hero-app-windows.jar` — desktop

The login screen has an **Updates** panel: paste a GitHub token (the repo is
private, so the releases API needs `Bearer` auth), tap **Check**, then
**Update**. Android downloads the APK and fires the system installer via a
`FileProvider`; desktop downloads the matching per-OS jar to the temp dir and
tells you how to launch it. `AppVersion` in `Update.kt` gates "is this newer".

## Status

Working: signed APK + desktop jar build in CI, the API client + core Compose UI
(login → nodes → sessions → live events + send), and the in-app updater. Rich
per-harness rendering and the Phase 5 direct-P2P (WebRTC data channel to a node)
build on this.
