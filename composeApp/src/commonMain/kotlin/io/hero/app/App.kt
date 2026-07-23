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

@Composable
private fun AppContent(settings: Settings, themeMode: ThemeMode, onThemeMode: (ThemeMode) -> Unit) {
    // Edge-to-edge: the Surface paints under the system bars; this keeps content
    // inside the safe area, and imePadding lifts the composer above the keyboard.
    // The order is load-bearing: imePadding after systemBars adds only the
    // remainder, so the composer sits flush on the IME. Desktop insets are zero.
    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars).imePadding()) {
        var api by remember { mutableStateOf<Api?>(null) }
        var me by remember { mutableStateOf(Me()) }
        var showSettings by remember { mutableStateOf(false) }
        var booting by remember { mutableStateOf(true) }

        // Silent re-login: if "remember me" saved a session cookie, try it.
        LaunchedEffect(Unit) {
            val url = settings.getString(Keys.ServerUrl)
            val cookie = settings.getString(Keys.Cookie)
            if (settings.getString(Keys.Remember) == "1" && !url.isNullOrBlank() && !cookie.isNullOrBlank()) {
                val a = Api(url, cookie)
                val m = runCatching { a.me() }.getOrNull()
                if (m != null) { api = a; me = m }
            }
            booting = false
        }

        val screen = when {
            booting -> "boot"
            showSettings -> "settings"
            api == null -> "login"
            else -> "main"
        }
        // Gentle crossfade between top-level screens — subtle, ink-quiet.
        Crossfade(targetState = screen, animationSpec = tween(260), label = "screen") { s ->
            when (s) {
                "boot" -> BootSplash()
                "settings" -> SettingsScreen(
                    settings = settings,
                    themeMode = themeMode,
                    onThemeMode = { onThemeMode(it); settings.putString(Keys.ThemeMode, it.id) },
                    onForget = {
                        settings.remove(Keys.Cookie); settings.remove(Keys.Remember); settings.remove(Keys.Username)
                    },
                    onClose = { showSettings = false },
                )
                "login" -> LoginScreen(
                    settings = settings,
                    onLogin = { a, m -> api = a; me = m },
                    onOpenSettings = { showSettings = true },
                )
                else -> MainScreen(
                    api!!, me, settings,
                    themeMode = themeMode,
                    onThemeMode = { onThemeMode(it); settings.putString(Keys.ThemeMode, it.id) },
                    onForget = {
                        settings.remove(Keys.Cookie); settings.remove(Keys.Remember); settings.remove(Keys.Username)
                    },
                    onSignOut = {
                        api = null; me = Me()
                        FleetCache.clear()
                        settings.remove(Keys.Cookie); settings.remove(Keys.Remember)
                    },
                )
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
                                        val a = Api(url.trim().trimEnd('/'))
                                        if (a.probe()) { checkedApi = a; step = 1 }
                                        else error = "Not reachable, or not a HERO control plane."
                                        busy = false
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
                                            val a = checkedApi ?: Api(url.trim().trimEnd('/'))
                                            if (a.login(user, pass)) {
                                                val m = runCatching { a.me() }.getOrDefault(Me(user = user))
                                                settings.putString(Keys.ServerUrl, url.trim().trimEnd('/'))
                                                settings.putString(Keys.Username, user)
                                                if (remember) {
                                                    settings.putString(Keys.Remember, "1")
                                                    a.sessionCookie?.let { settings.putString(Keys.Cookie, it) }
                                                } else {
                                                    settings.remove(Keys.Remember); settings.remove(Keys.Cookie)
                                                }
                                                onLogin(a, m)
                                            } else error = "Invalid credentials"
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
    var attentionCount by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (isActive) {
            runCatching { FleetCache.nodes.value = api.nodes() }
            runCatching {
                val items = api.attention()
                FleetCache.attention.value = items
                attentionCount = items.count { it.kind == "permission" || it.kind == "question" }
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


