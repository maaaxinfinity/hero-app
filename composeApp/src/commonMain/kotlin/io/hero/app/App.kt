package io.hero.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// WindowWidth splits the UI into two responsive classes at 600dp: Compact
// (phones — stacked navigation, dock hides in conversations) and Expanded
// (desktop/tablet — sidebar + conversation side by side). Screens read the
// ambient once; leaf components take an explicit parameter instead.
enum class WindowWidth { Compact, Expanded }

val LocalWindowWidth = compositionLocalOf { WindowWidth.Expanded }

@Composable
fun App() {
    val settings = remember { Settings() }
    var themeMode by remember { mutableStateOf(ThemeMode.from(settings.getString(Keys.ThemeMode))) }
    val dark = when (themeMode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    HeroTheme(dark = dark) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            // Width class is a property of the whole window, so it is measured
            // OUTSIDE the insets padding (IME resizes must not flip the class).
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val width = if (maxWidth < 600.dp) WindowWidth.Compact else WindowWidth.Expanded
                CompositionLocalProvider(LocalWindowWidth provides width) {
                    AppContent(settings, themeMode, onThemeMode = { themeMode = it })
                }
            }
        }
    }
}

// Screen models a top-level destination TOGETHER WITH the resources it renders
// from. Crossfade keeps the EXITING content composing until its fade finishes,
// so that content must render from its own immutable state value — Main carries
// its Api+Me — and may never re-read a nullable var that sign-out already
// cleared (the old string-keyed content did exactly that: its lambda captured
// `api!!`, a deterministic null dereference during the sign-out animation).
private sealed interface Screen {
    data object Boot : Screen
    data object Settings : Screen
    data object Login : Screen
    data class Main(val api: Api, val me: Me) : Screen
}

@Composable
private fun AppContent(settings: Settings, themeMode: ThemeMode, onThemeMode: (ThemeMode) -> Unit) {
    // Edge-to-edge: the Surface paints under the system bars; this keeps content
    // inside the safe area, and imePadding lifts the composer above the keyboard.
    // The order is load-bearing: imePadding after systemBars adds only the
    // remainder, so the composer sits flush on the IME. Desktop insets are zero.
    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars).imePadding()) {
        // Persisting a settings change is an atomic off-UI batch; it is launched
        // here (never awaited on a click) so the store I/O never blocks a frame.
        val scope = rememberCoroutineScope()
        var main by remember { mutableStateOf<Screen.Main?>(null) }
        var showSettings by remember { mutableStateOf(false) }
        var booting by remember { mutableStateOf(true) }

        // Silent re-login: if "remember me" saved a session cookie, try it. The
        // probe Api is closed unless it is promoted to the app session, so a
        // failed (or cancelled) silent login never leaks its connection pool.
        // The login is committed only when the AUTHORITATIVE /api/me succeeds —
        // same truth contract as the manual sign-in path.
        LaunchedEffect(Unit) {
            val url = settings.getString(Keys.ServerUrl)
            val cookie = settings.getString(Keys.Cookie)
            if (settings.getString(Keys.Remember) == "1" && !url.isNullOrBlank() && !cookie.isNullOrBlank()) {
                val a = Api(url, cookie)
                var kept = false
                try {
                    val m = runCatchingCancellable { a.me() }.getOrNull()
                    if (m != null) { main = Screen.Main(a, m); kept = true }
                } finally {
                    if (!kept) a.close()
                }
            }
            booting = false
        }

        // Last-resort owner: if AppContent itself leaves composition while a
        // session is live (window teardown), close the current client. The
        // per-screen owner inside the Crossfade below skips this case (the
        // session is still current there), so the close runs exactly once.
        DisposableEffect(Unit) {
            onDispose { main?.api?.close() }
        }

        val screen: Screen = when {
            booting -> Screen.Boot
            showSettings -> Screen.Settings
            else -> main ?: Screen.Login
        }
        // Gentle crossfade between top-level screens — subtle, ink-quiet.
        Crossfade(targetState = screen, animationSpec = tween(260), label = "screen") { s ->
            when (s) {
                Screen.Boot -> BootSplash()
                Screen.Settings -> SettingsScreen(
                    settings = settings,
                    themeMode = themeMode,
                    onThemeMode = { mode -> onThemeMode(mode); scope.launch { settings.update { it[Keys.ThemeMode] = mode.id } } },
                    onForget = {
                        // One batch clears the whole saved login, so the section
                        // recomposes to "Nothing saved" the instant it commits.
                        scope.launch { settings.update { it.remove(Keys.ServerUrl); it.remove(Keys.Username); it.remove(Keys.Remember); it.remove(Keys.Cookie) } }
                    },
                    onClose = { showSettings = false },
                )
                Screen.Login -> LoginScreen(
                    settings = settings,
                    onLogin = { a, m -> main = Screen.Main(a, m) },
                    onOpenSettings = { showSettings = true },
                )
                is Screen.Main -> {
                    // The app-session owner, placed INSIDE the crossfaded content
                    // and FIRST in it: sign-out replaces `main`, but this content
                    // (and its SSE/poll children) stays composed until the exit
                    // fade completes. Only when the old content is truly removed
                    // does disposal run — children first (effects dispose in
                    // reverse order of composition), this owner last — so the
                    // client is closed strictly AFTER every child effect was
                    // cancelled, and a cancelled child cannot issue a new call
                    // (its next suspension throws). Navigating away with the
                    // session still current (teardown) defers to the outer owner.
                    DisposableEffect(s.api) {
                        onDispose { if (main?.api !== s.api) s.api.close() }
                    }
                    MainScreen(
                        s.api, s.me, settings,
                        themeMode = themeMode,
                        onThemeMode = { mode -> onThemeMode(mode); scope.launch { settings.update { it[Keys.ThemeMode] = mode.id } } },
                        onForget = {
                            // One batch clears the whole saved login, so the section
                            // recomposes to "Nothing saved" the instant it commits.
                            scope.launch { settings.update { it.remove(Keys.ServerUrl); it.remove(Keys.Username); it.remove(Keys.Remember); it.remove(Keys.Cookie) } }
                        },
                        onSignOut = {
                            main = null
                            FleetCache.clear()
                            // Sign out keeps server+user (convenience for quick re-login);
                            // only the durable "remember me" cookie pair is dropped.
                            scope.launch { settings.update { it.remove(Keys.Cookie); it.remove(Keys.Remember) } }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun BootSplash() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        ParticleLoader(tint = MaterialTheme.colorScheme.onBackground, modifier = Modifier.size(148.dp))
    }
}

// LoginScreen is a two-step flow: enter the control-plane URL and validate it
// (reachable + really a HERO plane) before asking for credentials. Server + user
// (and, with "remember me", the session cookie) are persisted for next launch.
@Composable
private fun LoginScreen(settings: Settings, onLogin: (Api, Me) -> Unit, onOpenSettings: () -> Unit) {
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf(0) } // 0 = URL, 1 = credentials
    var url by remember { mutableStateOf(settings.getString(Keys.ServerUrl) ?: "https://") }
    var user by remember { mutableStateOf(settings.getString(Keys.Username) ?: "") }
    var pass by remember { mutableStateOf("") }
    var remember by remember { mutableStateOf(settings.getString(Keys.Remember) == "1") }
    var checkedApi by remember { mutableStateOf<Api?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    // checkedApi is the probe client for the entered server; a successful sign-in
    // hands it off to the app session. handedOff guards that handoff so leaving
    // the login screen closes an ABANDONED probe client but never the one now
    // owned by the app session.
    var handedOff by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        onDispose { if (!handedOff) checkedApi?.close() }
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 380.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ParticleLoader(tint = MaterialTheme.colorScheme.onBackground, modifier = Modifier.size(184.dp))
            Text("HERO", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, letterSpacing = 8.sp)
            Spacer(Modifier.height(2.dp))
            Text("Harness Everything Routing Orchestrator", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(24.dp))

            OutlinedCard(Modifier.fillMaxWidth()) {
                // Slide + fade between the two steps; direction follows step order.
                AnimatedContent(
                    targetState = step,
                    transitionSpec = {
                        val forward = targetState > initialState
                        val dir = if (forward) 1 else -1
                        (slideInHorizontally(tween(280)) { w -> dir * w } + fadeIn(tween(280)))
                            .togetherWith(slideOutHorizontally(tween(240)) { w -> -dir * w } + fadeOut(tween(180)))
                    },
                    label = "loginStep",
                ) { s ->
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (s == 0) {
                            OutlinedTextField(
                                url, { url = it; error = null }, Modifier.fillMaxWidth(),
                                label = { Text("Control-plane URL") }, singleLine = true,
                            )
                            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                            Button(
                                onClick = {
                                    busy = true; error = null
                                    scope.launch {
                                        // Explicit temp-Api owner: ownership transfers to
                                        // checkedApi only on a successful adopt; EVERY other
                                        // exit — failed probe, thrown error, or this coroutine
                                        // being cancelled mid-probe (Settings opened, screen
                                        // left) — closes the local client in finally. The
                                        // cancelled path used to leak one engine per probe.
                                        val a = Api(url.trim().trimEnd('/'))
                                        var adopted = false
                                        try {
                                            if (a.probe()) {
                                                // Re-check / Change server: close the previous
                                                // probe client before adopting the new one.
                                                checkedApi?.close()
                                                checkedApi = a; adopted = true; step = 1
                                            } else {
                                                error = "Not reachable, or not a HERO control plane."
                                            }
                                            busy = false
                                        } finally {
                                            if (!adopted) a.close()
                                        }
                                    }
                                },
                                enabled = !busy && url.isNotBlank(),
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(if (busy) "Checking…" else "Continue") }
                        } else {
                            Text(url.trim().trimEnd('/'), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            OutlinedTextField(
                                user, { user = it }, Modifier.fillMaxWidth(),
                                label = { Text("User") }, singleLine = true,
                            )
                            PasswordField(pass, { pass = it }, label = "Password", modifier = Modifier.fillMaxWidth())
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                androidx.compose.material3.Checkbox(remember, { remember = it })
                                Text("Remember me on this device", style = MaterialTheme.typography.bodySmall)
                            }
                            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                            Button(
                                onClick = {
                                    busy = true; error = null
                                    scope.launch {
                                        try {
                                            // A fallback client (probe skipped) is adopted into
                                            // checkedApi immediately, so the screen's owner
                                            // closes it on any non-handoff exit — it used to
                                            // float unowned and leak on a failed sign-in.
                                            val a = checkedApi ?: Api(url.trim().trimEnd('/')).also { checkedApi = it }
                                            if (a.login(user, pass)) {
                                                // The login commits ONLY once the authoritative
                                                // /api/me confirms it. Guessing Me(user=...) on a
                                                // 500/timeout/bad JSON committed an unconfirmed
                                                // identity: admin surfaces hidden, settings
                                                // persisted, and the failure deferred into the
                                                // main screen. Any me() failure lands in the
                                                // catch below — stay on the login page, persist
                                                // nothing. (Cookie alone is not a login.)
                                                val m = a.me()
                                                val server = url.trim().trimEnd('/')
                                                // One atomic batch: server+user (+remember+cookie) or
                                                // the cleared pair — never a half-written combination.
                                                settings.update {
                                                    it[Keys.ServerUrl] = server
                                                    it[Keys.Username] = user
                                                    if (remember) {
                                                        it[Keys.Remember] = "1"
                                                        a.sessionCookie?.let { c -> it[Keys.Cookie] = c }
                                                    } else {
                                                        it.remove(Keys.Remember); it.remove(Keys.Cookie)
                                                    }
                                                }
                                                // a is now owned by the app session; keep the
                                                // login screen's DisposableEffect from closing it.
                                                handedOff = true
                                                onLogin(a, m)
                                            } else error = "Invalid credentials"
                                        } catch (c: CancellationException) {
                                            throw c // a navigation-cancel is not a sign-in failure
                                        } catch (e: Throwable) {
                                            error = e.message ?: "sign-in failed"
                                        } finally {
                                            busy = false
                                        }
                                    }
                                },
                                enabled = !busy && user.isNotBlank() && pass.isNotBlank(),
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(if (busy) "Signing in…" else "Sign in") }
                            TextButton(onClick = { step = 0; error = null }, modifier = Modifier.fillMaxWidth()) {
                                Text("‹  Change server")
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            TextButton(onClick = onOpenSettings) { Text("Settings") }
        }
    }
}

// Section is the top-level navigation target within MainScreen. `group` is the
// dock block it belongs to: 1 workspace (talk to harnesses), 2 infrastructure
// (nodes + the control plane itself), 3 people & permissions, 4 system.
enum class Section(val label: String, val adminOnly: Boolean, val group: Int) {
    Sessions("Sessions", false, 1),
    Attention("Attention", false, 1),
    Nodes("Nodes", false, 2),
    Control("Control plane", true, 2),
    Users("Users", true, 3),
    Audit("Audit", true, 3),
    Prefs("Settings", false, 4),
}

@Composable
private fun MainScreen(
    api: Api, me: Me, settings: Settings,
    themeMode: ThemeMode, onThemeMode: (ThemeMode) -> Unit, onForget: () -> Unit,
    onSignOut: () -> Unit,
) {
    val compact = LocalWindowWidth.current == WindowWidth.Compact
    var section by remember { mutableStateOf(Section.Sessions) }
    var sel by remember { mutableStateOf(SessionSel()) }
    // Cross-section deep link: Users' owns/shared entries land on that node.
    var nodesFocus by remember { mutableStateOf<String?>(null) }
    // Leaving the Sessions tab discards the open session — SessionsScreen used to
    // own this state and left composition on tab switch; keep that contract.
    val selectSection: (Section) -> Unit = { s -> if (s != section) sel = SessionSel(); section = s }
    // Registered BEFORE children compose — SessionsScreen's own handler lands
    // later on the dispatcher stack so it wins (LIFO). Do not move below the Column.
    PredictiveBack(enabled = section != Section.Sessions) { section = Section.Sessions }
    // Desktop: Ctrl/Cmd+1..5 jump between the visible sections; Ctrl/Cmd+K
    // opens the quick switcher.
    var showSwitcher by remember { mutableStateOf(false) }
    KeyHandler { e ->
        if (e.type != KeyEventType.KeyDown || !(e.isCtrlPressed || e.isMetaPressed)) false
        else if (e.key == Key.K) { showSwitcher = true; true }
        else {
            val visible = Section.entries.filter { !it.adminOnly || me.admin }
            val idx = when (e.key) {
                Key.One -> 0; Key.Two -> 1; Key.Three -> 2; Key.Four -> 3; Key.Five -> 4
                else -> -1
            }
            if (idx in visible.indices) { selectSection(visible[idx]); true } else false
        }
    }
    if (showSwitcher) {
        QuickSwitcher(
            api,
            onDismiss = { showSwitcher = false },
            onPick = { n, s ->
                showSwitcher = false
                section = Section.Sessions
                sel = SessionSel(node = n, session = s)
            },
        )
    }

    // Fleet-wide attention: one aggregate call (GET /api/attention) feeds both the
    // dock count and the Attention screen (via FleetCache). This replaces the old
    // N-per-node pending fan-out — one request instead of a fan-out that scaled
    // with fleet size (and could time out). The node fetch still feeds FleetCache
    // so every other screen stays fresh for free.
    // Scope the fleet cache to this {server, user} app-session before anything
    // reads or writes it. Captured once per mount (so a mid-session "Forget",
    // which clears the saved server, can't spuriously reset it); a server switch
    // or re-login remounts MainScreen and re-binds, invalidating the cache by
    // generation instead of relying on a clear() being threaded everywhere.
    //
    // Bound SYNCHRONOUSLY in composition (remember, not LaunchedEffect): the
    // identity/generation boundary must exist BEFORE any child composes or any
    // effect captures FleetCache.generation. The old effect-phase bind let the
    // whole first frame render the previous identity's snapshot, and let any
    // poll that launched first capture the pre-bind generation — after which
    // every one of its puts was silently refused for the rest of its effect
    // lifetime. bindIdentity is idempotent for the same identity, so a retried
    // or discarded composition at worst re-clears an already-cleared cache.
    val cacheServer = remember { settings.getString(Keys.ServerUrl).orEmpty() }
    remember(cacheServer, me.user) { FleetCache.bindIdentity(cacheServer, me.user) }
    // Register for remote push once per app open (Android via UnifiedPush, so a
    // permission prompt reaches the device even when the app is closed). A no-op
    // where unsupported (desktop) or when no distributor is installed.
    LaunchedEffect(Unit) {
        if (RemotePush.supported) runCatchingCancellable { RemotePush.register(api) }
    }

    var attentionCount by remember { mutableStateOf(0) }
    // seen tracks the actionable items already surfaced, so a newly-arrived one
    // fires a local OS notification (desktop tray) exactly once. The first poll
    // only seeds the set — no notification burst on launch.
    var seen by remember { mutableStateOf<Set<String>?>(null) }
    LaunchedEffect(Unit) {
        val gen = FleetCache.generation
        while (isActive) {
            runCatchingCancellable { FleetCache.putNodes(gen, api.nodes()) }
            runCatchingCancellable {
                val items = api.attention()
                FleetCache.putAttention(gen, items)
                val actionable = items.filter { it.kind == "permission" || it.kind == "question" }
                attentionCount = actionable.size
                val ids = actionable.map { it.node + ":" + it.id }.toSet()
                seen?.let { prior ->
                    actionable.filter { (it.node + ":" + it.id) !in prior }.forEach {
                        notifyLocal(
                            "HERO — " + it.node,
                            (if (it.kind == "question") "Question" else "Wants to use " + it.tool_name.ifEmpty { "a tool" }) +
                                " · " + it.title.ifEmpty { "session " + it.session_id.take(8) },
                        )
                    }
                }
                seen = ids
            }
            delay(7000)
        }
    }

    // In a compact conversation the chrome gets out of the way: both bars hide
    // and ConversationHeader carries navigation. Expanded keeps the dock.
    val conversationOpen = compact && sel.session != null
    Column(Modifier.fillMaxSize()) {
        if (compact) {
            AnimatedVisibility(
                visible = !conversationOpen,
                enter = expandVertically(tween(220)), exit = shrinkVertically(tween(220)),
            ) { MiniTopBar(section) }
        }
        // Single call site for the section content: its composition identity must
        // survive width-class changes and chrome show/hide (an open session's SSE
        // stream must not restart), so never fork this into per-mode layouts.
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (section) {
                Section.Sessions -> SessionsScreen(api, settings, sel, onSel = { sel = it })
                Section.Attention -> AttentionScreen(api, onOpen = { node, sess ->
                    sel = SessionSel(node = node, session = sess); section = Section.Sessions
                })
                Section.Nodes -> NodesScreen(api, me, settings, focus = nodesFocus, onFocusConsumed = { nodesFocus = null })
                Section.Control -> ControlScreen(api)
                Section.Users -> UsersScreen(api, me, onOpenNode = { id ->
                    nodesFocus = id; selectSection(Section.Nodes)
                })
                Section.Audit -> AuditScreen(api)
                Section.Prefs -> PrefsContent(settings, themeMode, onThemeMode, onForget)
            }
        }
        AnimatedVisibility(
            visible = !conversationOpen,
            enter = expandVertically(tween(220)), exit = shrinkVertically(tween(220)),
        ) {
            Dock(me, section, selectSection, onSignOut, attentionCount = attentionCount)
        }
    }
}

// MiniTopBar orients compact screens (brand + where you are); actions live in
// the dock's account menu, so it stays a pure label.
@Composable
private fun MiniTopBar(section: Section) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LogoMark(Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.width(10.dp))
            Text(section.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
internal fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        letterSpacing = 1.5.sp,
        modifier = Modifier.padding(start = 8.dp, bottom = 6.dp),
    )
}

@Composable
internal fun HintText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 8.dp, top = 2.dp),
    )
}

@Composable
internal fun PaneLoader() {
    Box(Modifier.fillMaxWidth().height(64.dp), contentAlignment = Alignment.Center) {
        InlineSpinner(size = 20.dp)
    }
}


