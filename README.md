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
  keystore.jks                    # release signing key (gitignored; CI gets it from secrets)
  src/commonMain/kotlin/io/hero/app/
    Models.kt      # v1 API data types (NodeView, Session, Turn/TurnPart, Event)
    Api.kt         # Ktor client: login, nodes, sessions, transcript, send, SSE
    App.kt         # root: width classes, login, MainScreen chrome, shortcuts
    Sessions.kt    # Sessions section: sidebar (collapsible), conversation pane
    Conversation.kt# ConvoState reducer + turn/part renderers (harness-neutral)
    Dock.kt        # bottom dock: four section blocks + account (all chrome)
    Panels.kt      # sidebar kit: TopToolbar, InspectorHost, PanelSection
    Switcher.kt    # Ctrl/Cmd+K quick switcher (fleet-wide session jump)
    Manage.kt      # Nodes/Users/Audit/Control screens + inspectors
    Markdown.kt    # minimal markdown renderer (links clickable)
    Widgets.kt     # shared small controls: Pill, PasswordField, hover, keys
    Glyphs.kt      # hand-drawn line glyphs (dock marks, password eye)
    Menus.kt       # expect: right-click context menus (desktop actual)
    Theme.kt / Brand.kt / ParticleLoader.kt / Identicon.kt  # ink identity
    Update.kt      # in-app updater (checkForUpdate + expect installUpdate)
  src/androidMain/…               # MainActivity, manifest, FileProvider, APK updater
  src/desktopMain/kotlin/main.kt  # desktop entry: window sizing, key routing
gradle/libs.versions.toml         # version catalog
.github/workflows/release.yml     # tag v* → build APK + desktop jars → GitHub release
```

## UI

One chrome for both form factors: a floating bottom **dock** in four blocks —
workspace (Sessions) | infrastructure (Nodes, Control plane) | people &
permissions (Users, Audit) | system (Settings, account) — with a dot under the
selected glyph; there is no top bar on desktop. Sidebar placement is a
language: **left navigates** (the Sessions node/session list, collapsible
240dp ⇄ 52dp rail, `Ctrl/Cmd+B`, persisted), **right inspects** (per-screen
inspector panels), **top acts on the collection** (toolbars/filters). Below
600dp the app switches to stacked phone navigation (list ⇄ fullscreen
conversation ⇄ fullscreen inspector; chrome hides in conversations, predictive
back unwinds the stack).

Inspectors are wired to real endpoints only: Sessions (`Ctrl/Cmd+I`) shows
session facts + the node's pending-permission center + subagent drill-ins +
quick actions; Nodes covers access (share/unshare, transfer ownership),
per-harness config pages (each backend opens its own page inside the
inspector) and two-click removal, with a join-script generator; Users covers
password/admin/delete/create, its owns/shared entries deep-linking into
Nodes. Audit has a live filter and a fetch-limit picker. The Nodes fleet
renders as cards by default (switchable to a dense list, persisted) and shows
each machine's CPU/MEM/DISK meters when the node reports them (control-plane
`Metrics` RPC; older nodes simply omit the fields).
Node and session rows carry the harness marks (claude / codex glyphs),
connection dots and status; the conversation header shows the runtime model, a
pulsing row signals thinking/stalled, and "Load earlier" pages the transcript
backwards. The composer has a model + effort switcher (the session's own
backend catalog; applies to the next message), and the new-session dialog
offers an explicit harness selector (enabled backends only — the choice is
sent to the node, so blank model means *that* harness's default), a
recent-working-directory picker derived from the node's sessions, and a model
list filtered to the chosen harness. Desktop niceties: hover states, tooltips,
right-click menus, `Ctrl/Cmd+1..5` section jumps, `Ctrl/Cmd+K` quick switcher,
Enter-to-send (Shift+Enter for newline).

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

Settings has an **Updates** section: tap **Check for updates**, then
**Install**. The release is public, so no token is involved. Every download is
bounded and validated — the response must be `2xx`, any declared `Content-Length`
must agree with what actually arrived, and a byte ceiling guards a runaway or
mis-served body. The bytes stream into a same-directory temp file and are only
renamed onto the install path after a complete, verified transfer, so a
cancel/timeout/error never leaves a partial file there. Android then fires the
system installer via a `FileProvider` on the committed APK. Desktop is a portable
jar with no installer to hand off to and no launcher path it can safely overwrite,
so it does **not** auto-relaunch: it downloads and verifies the new per-OS jar,
publishes it to a stable path (under your home directory), and shows you the exact
path plus the `java -jar …` command to run it / swap it in. `AppVersion` in
`Update.kt` gates "is this newer".

## Status

Working: signed APK + desktop jar build in CI, the API client + core Compose UI
(login → nodes → sessions → live events + send), and the in-app updater. Rich
per-harness rendering and the Phase 5 direct-P2P (WebRTC data channel to a node)
build on this.
