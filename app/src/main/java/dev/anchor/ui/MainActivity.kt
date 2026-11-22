@file:OptIn(ExperimentalMaterial3Api::class)

package dev.anchor.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.anchor.R
import dev.anchor.enforce.Apps
import dev.anchor.enforce.Enforcer
import dev.anchor.enforce.Rules
import dev.anchor.enforce.Usage
import dev.anchor.policy.AppRule
import dev.anchor.policy.Mode
import dev.anchor.policy.Policy
import dev.anchor.policy.PolicyStore
import dev.anchor.policy.RESTRICTIONS
import dev.anchor.policy.Schedule
import dev.anchor.policy.Strictness
import dev.anchor.policy.Targets
import dev.anchor.policy.TimeWindow
import dev.anchor.service.DnsVpnService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(SystemBarStyle.dark(C.Bg.toArgb()), SystemBarStyle.dark(C.Bg.toArgb()))
        super.onCreate(savedInstanceState)
        setContent { AnchorTheme { AnchorRoot() } }
    }
}

private enum class Tab(val label: String) { BLOCKS("Blocks"), STATS("Stats"), GRAY("Grayscale"), ESSENTIALS("Essentials"), SETTINGS("Settings") }
private enum class Sub { STATUS, SAFETY, LIMITS, BEDTIME, WEB, SHORTS, HOME, DEVICE, NUDGE }

private data class Health(val owner: Boolean, val admin: Boolean, val watcher: Boolean, val secure: Boolean) {
    val ok get() = (owner || admin) && watcher && secure
}

@Composable
private fun AnchorRoot() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    val current by PolicyStore.policy.collectAsState()
    var draft by remember { mutableStateOf(current) }
    var base by remember { mutableStateOf(current) }
    // A background change (expired block, new app) updates the draft only if nothing is being edited.
    // base = what the draft was started from. Background changes only move it while nothing is being edited.
    LaunchedEffect(current) { if (draft == base) { draft = current; base = current } }
    var tab by remember { mutableStateOf(Tab.BLOCKS) }
    var statsTick by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    var sub by remember { mutableStateOf<Sub?>(null) }
    var creating by remember { mutableStateOf<Set<String>?>(null) }
    var outcome by remember { mutableStateOf<PolicyStore.Outcome?>(null) }
    var now by remember { mutableLongStateOf(dev.anchor.policy.TrustedTime.now()) }
    LaunchedEffect(Unit) { while (true) { delay(5_000); now = dev.anchor.policy.TrustedTime.now() } }
    val health = remember(now / 5000) { Health(Enforcer.isOwner(ctx), Enforcer.isAdmin(ctx), Enforcer.watcherEnabled(ctx), Enforcer.canWriteSecure(ctx)) }

    val locked = current.lockedUntil > now
    // your edits on top of whatever changed in the background meanwhile
    val merged = remember(draft, base, current) { dev.anchor.policy.Merge.rebase(draft, base, current) }
    val dirty = merged != current
    val looser = dirty && current.armed && Strictness.isLooser(current, merged)
    fun say(msg: String) = scope.launch { snack.currentSnackbarData?.dismiss(); snack.showSnackbar(msg) }
    val set: (Policy) -> Unit = { cand ->
        if (locked && Strictness.isLooser(current, dev.anchor.policy.Merge.rebase(cand, base, current))) say("Locked until ${fmtDate(current.lockedUntil)}. Only stricter changes.")
        else draft = cand
    }
    fun handle(o: PolicyStore.Outcome, okMsg: String) {
        if (o is PolicyStore.Outcome.Applied) say(okMsg) else outcome = o
    }

    creating?.let { pre ->
        NewBlockScreen(pre, onClose = { creating = null }) { targets, until, windows ->
            creating = null
            handle(PolicyStore.addBlocks(ctx, targets, until, windows), "Locked in until ${fmtDate(until)}.")
        }
        return
    }
    BackHandler(enabled = sub != null) { sub = null }

    Scaffold(
        containerColor = C.Bg,
        snackbarHost = { SnackbarHost(snack) { Snackbar(it, containerColor = C.Card3, contentColor = C.Text) } },
        floatingActionButton = {
            if (tab == Tab.BLOCKS && !dirty) ExtendedFloatingActionButton(
                onClick = { creating = emptySet() }, containerColor = C.Accent, contentColor = C.OnAccent,
                icon = { Icon(Icons.Default.Add, null) }, text = { Text("New block") },
            )
        },
        bottomBar = {
            Column {
                if (dirty) ChangesBar(
                    looser, current.cooldownHours,
                    onDiscard = { draft = current; base = current },
                    onApply = {
                        val o = PolicyStore.propose(ctx, merged)
                        if (o !is PolicyStore.Outcome.Refused && o !is PolicyStore.Outcome.Locked) { draft = PolicyStore.policy.value; base = draft }
                        handle(o, "Saved.")
                    },
                )
                NavigationBar(containerColor = C.Card, tonalElevation = 0.dp) {
                    Tab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = t == tab, onClick = { tab = t; sub = null; if (t == Tab.STATS) statsTick++ },
                            icon = { TabIcon(t) }, label = { Text(t.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = C.OnAccent, indicatorColor = C.Accent, selectedTextColor = C.Text,
                                unselectedIconColor = C.Muted, unselectedTextColor = C.Muted,
                            ),
                        )
                    }
                }
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad)) {
            when (tab) {
                Tab.BLOCKS -> BlocksScreen(current, if (!health.ok) PillState.SETUP else if (dev.anchor.enforce.Rules.protecting(current)) PillState.PROTECTED else PillState.UNARMED, onNew = { creating = it }, onOutcome = { handle(it, "Extended.") }, onStatus = { tab = Tab.SETTINGS; sub = Sub.STATUS }, onEnded = { say(it) })
                Tab.STATS -> StatsScreen(statsTick)
                Tab.GRAY -> GrayScreen(draft, current, health, set)
                Tab.ESSENTIALS -> EssentialsScreen(draft, set)
                Tab.SETTINGS -> when (sub) {
                    null -> SettingsScreen(draft, current, health, now, set) { sub = it }
                    Sub.STATUS -> StatusScreen(current, health) { sub = null }
                    Sub.SAFETY -> SafetyScreen(draft, current, health, now, set) { sub = null }
                    Sub.LIMITS -> LimitsScreen(draft, set) { sub = null }
                    Sub.BEDTIME -> BedtimeScreen(draft, set) { sub = null }
                    Sub.WEB -> WebScreen(draft, set) { sub = null }
                    Sub.SHORTS -> ShortsScreen(draft, set) { sub = null }
                    Sub.HOME -> HomeScreen(draft, set) { sub = null }
                    Sub.DEVICE -> DeviceScreen(draft, set) { sub = null }
                    Sub.NUDGE -> NudgeSettings(draft, set) { sub = null }
                }
            }
        }
    }

    outcome?.let { o -> OutcomeDialog(o) { outcome = null } }
}

@Composable
private fun TabIcon(t: Tab) = when (t) {
    Tab.BLOCKS -> Icon(Icons.Default.Lock, null)
    Tab.STATS -> Icon(painterResource(R.drawable.ic_bars), null)
    Tab.GRAY -> Icon(painterResource(R.drawable.ic_contrast), null)
    Tab.ESSENTIALS -> Icon(Icons.Default.Star, null)
    Tab.SETTINGS -> Icon(Icons.Default.Settings, null)
}

@Composable
private fun ChangesBar(looser: Boolean, cooldownH: Int, onDiscard: () -> Unit, onApply: () -> Unit) {
    Surface(color = C.Card2) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Unsaved changes", style = MaterialTheme.typography.titleSmall)
                Text(if (looser) "Loosens a rule: takes effect in ${cooldownH}h" else "Stricter or neutral: applies now", style = MaterialTheme.typography.bodySmall, color = C.Muted)
            }
            TextButton(onClick = onDiscard) { Text("Discard", color = C.Muted) }
            Button(onClick = onApply) { Text(if (looser) "Queue" else "Apply") }
        }
    }
}

@Composable
private fun OutcomeDialog(o: PolicyStore.Outcome, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = C.Card,
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
        title = {
            Text(when (o) {
                is PolicyStore.Outcome.Queued -> "Queued"
                is PolicyStore.Outcome.Refused -> "Not possible"
                is PolicyStore.Outcome.Locked -> "Settings are locked"
                else -> "Done"
            })
        },
        text = {
            Text(when (o) {
                is PolicyStore.Outcome.Locked -> "Until ${fmtDate(o.until)} nothing can be loosened:\n\n• " +
                    o.reasons.joinToString("\n• ") + "\n\nStricter changes still work. Nothing was saved."
                is PolicyStore.Outcome.Refused -> "These blocks are still running and can't be shortened or removed:\n\n" +
                    o.broken.joinToString("\n") { "• ${Targets.resolve(it.target, ctx).label} until ${fmtDate(it.until)}" } +
                    "\n\nNothing was saved."
                is PolicyStore.Outcome.Queued -> "This loosens your rules:\n\n• ${o.reasons.joinToString("\n• ")}\n\n" +
                    "It takes effect ${fmtDate(o.at)}. Until then nothing changes, and you can cancel it under Settings › Protection status."
                else -> "Active now."
            })
        },
    )
}

// ---------------------------------------------------------------- grayscale

@Composable
private fun GrayScreen(p: Policy, current: Policy, health: Health, set: (Policy) -> Unit) {
    val grayNow = Enforcer.grayNow(current)
    Screen("Grayscale", "Color makes apps more tempting. Take it away, all the time, at set times, or only inside certain apps.") {
        Spacer(Modifier.height(12.dp))
        Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = C.Card) {
            Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_contrast), null, tint = if (grayNow) C.Text else C.Accent, modifier = Modifier.size(36.dp))
                Column(Modifier.padding(start = 16.dp)) {
                    Text(if (grayNow) "Gray right now" else "In color right now", style = MaterialTheme.typography.titleMedium)
                    Text(
                        when {
                            current.grayscale -> "Always on"
                            current.grayscaleWindows.isNotEmpty() || current.grayscaleApps.isNotEmpty() -> "Follows your schedule and apps"
                            else -> "Nothing set up yet"
                        },
                        style = MaterialTheme.typography.bodySmall, color = C.Muted,
                    )
                }
            }
        }
        if (!health.secure) Banner("Grayscale needs one permission from the Mac: run ./setup.sh again.", C.Danger, Modifier.padding(top = 12.dp))

        SectionTitle("Always")
        Group { ToggleRow("Gray all the time", "The whole screen, all day.", p.grayscale) { set(p.copy(grayscale = it)) } }

        SectionTitle("At set times")
        WindowsEditor(p.grayscaleWindows, "Add a time", TimeWindow(days = (1..7).toSet(), start = 20 * 60, end = 7 * 60)) { set(p.copy(grayscaleWindows = it)) }

        SectionTitle("Inside these apps")
        Hint("The screen turns gray the moment one of these opens, and back to color when you leave.", Modifier.padding(bottom = 10.dp, start = 4.dp))
        AppListEditor(p.grayscaleApps, "Gray inside") { set(p.copy(grayscaleApps = it)) }
    }
}

// ---------------------------------------------------------------- essentials

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun EssentialsScreen(p: Policy, set: (Policy) -> Unit) {
    val ctx = LocalContext.current
    val hard = remember { Rules.essentials(ctx) }
    val hardApps = remember { AppCache.apps(ctx).filter { it.pkg in hard } }
    val suggested = remember(p.essentials) { SUGGESTED_ESSENTIALS.filter { it !in p.essentials && AppCache.installed(ctx, it) } }
    Screen("Essentials", "Apps you need for life. Bedtime, game detection and the new-app block never touch them. A block you set on purpose still wins.") {
        if (suggested.isNotEmpty()) {
            SectionTitle("Suggested")
            Hint("Logins and payments depend on these. Keep them safe from bedtime and the new-app block.", Modifier.padding(bottom = 10.dp, start = 4.dp))
            Group {
                suggested.forEachIndexed { i, pkg ->
                    val label = remember(pkg) { Apps.label(ctx, pkg) }
                    AppRow(pkg, label) { TextButton(onClick = { set(p.copy(essentials = p.essentials + pkg)) }) { Text("Add") } }
                    if (i < suggested.lastIndex) Divider()
                }
            }
        }
        SectionTitle("Your essentials")
        AppListEditor(p.essentials, "Essentials", exclude = hard, emptyText = "None yet. Add banking, maps, authenticator…") { set(p.copy(essentials = it)) }

        SectionTitle("Always allowed")
        Hint("Calls, messages, keyboard, clock and Settings can never be blocked.", Modifier.padding(bottom = 10.dp, start = 4.dp))
        Group {
            hardApps.forEachIndexed { i, a ->
                AppRow(a.pkg, a.label) { Icon(Icons.Default.Lock, "Always allowed", tint = C.Muted, modifier = Modifier.padding(end = 8.dp).size(18.dp)) }
                if (i < hardApps.lastIndex) Divider()
            }
        }
    }
}

/** 2FA, banking and payment apps: offered as essentials when installed. */
private val SUGGESTED_ESSENTIALS = listOf(
    "com.google.android.apps.authenticator2", // Google Authenticator
    "de.number26.android",                     // N26
    "com.azure.authenticator",                 // Microsoft Authenticator
    "com.google.android.apps.walletnfcrel",    // Google Wallet
    "com.salesforce.authenticator",            // Salesforce Authenticator
    "de.traderepublic.app",                    // Trade Republic
    "com.authy.authy", "com.paypal.android.p2pmobile", "com.revolut.revolut",
)

// ---------------------------------------------------------------- settings

@Composable
private fun SettingsScreen(p: Policy, current: Policy, health: Health, now: Long, set: (Policy) -> Unit, open: (Sub) -> Unit) {
    val limits = p.apps.count { it.value.mode != Mode.ALLOW }
    Screen("Settings") {
        Spacer(Modifier.height(12.dp))
        LockCard(p, current, now, set)

        SectionTitle("Protection")
        Group {
            NavRow("Protection status", if (!health.ok) "Needs setup" else if (health.owner) "Device owner" else "Strong soft mode", if (health.ok) C.Ok else C.Accent) { open(Sub.STATUS) }
            Divider()
            NavRow("Strictness & partner code", if (current.armed) "Armed · ${current.cooldownHours}h" else "Not armed", if (current.armed) C.Muted else C.Accent) { open(Sub.SAFETY) }
        }

        SectionTitle("More rules")
        Group {
            NavRow("App limits", if (limits == 0) "None" else "$limits apps") { open(Sub.LIMITS) }
            Divider()
            NavRow("Evening nudge", if (p.nudgeEnabled) p.nudgeWindows.firstOrNull()?.let { Schedule.describe(it) } ?: "On" else "Off") { open(Sub.NUDGE) }
            Divider()
            NavRow("Bedtime", p.bedtimeWindows.firstOrNull()?.let { Schedule.describe(it) } ?: "Off") { open(Sub.BEDTIME) }
            Divider()
            NavRow("Websites & Chrome", "${p.blockedDomains.size} sites") { open(Sub.WEB) }
            Divider()
            NavRow("Reels & Shorts", if (p.blockShortForm) "Blocked" else "Allowed") { open(Sub.SHORTS) }
            Divider()
            NavRow("Home & notifications", listOfNotNull(if (p.launcherEnabled) "Hafen home" else null, if (p.notifyFilter) "filter on" else null).joinToString(" · ").ifEmpty { "Off" }) { open(Sub.HOME) }
            Divider()
            NavRow("New apps & device", if (p.blockNewApps) "New apps blocked" else "New apps allowed") { open(Sub.DEVICE) }
        }
        Hint("Hafen 0.2 · everything stays on this phone.", Modifier.padding(top = 24.dp, start = 4.dp))
    }
}

@Composable
private fun LockCard(p: Policy, current: Policy, now: Long, set: (Policy) -> Unit) {
    val ctx = LocalContext.current
    val locked = current.lockedUntil > now
    var unlocking by remember { mutableStateOf(false) }
    if (unlocking) CodeDialog("Lift the settings lock?", "Your partner types their code. Settings can be loosened again right away (still with the waiting period if armed). This is logged.", onDismiss = { unlocking = false }) {
        PolicyStore.overrideWithCode(ctx, it, unlockSettings = true)
    }
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = if (locked) C.AccentDim else C.Card) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Lock, null, tint = C.Accent, modifier = Modifier.size(20.dp))
                Text(if (locked) "Settings locked" else "Lock settings", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 10.dp))
            }
            Text(
                if (locked) "Until ${fmtDate(current.lockedUntil)} (${fmtLeft(current.lockedUntil - now)} left). Every rule, rhythm, grayscale setting and essential can only get stricter."
                else "Pick a date. Until then every rule, rhythm, grayscale setting and essential can only get stricter.",
                style = MaterialTheme.typography.bodySmall, color = C.Muted, modifier = Modifier.padding(top = 6.dp, bottom = 14.dp),
            )
            val base = maxOf(current.lockedUntil, now)
            FlowPills {
                DURATIONS.drop(1).forEach { (label, d) ->
                    val target = base + d
                    val sel = p.lockedUntil > current.lockedUntil && kotlin.math.abs(p.lockedUntil - target) < 120_000
                    Pill("+ $label", sel) { set(p.copy(lockedUntil = if (sel) current.lockedUntil else target)) }
                }
            }
            if (p.lockedUntil > current.lockedUntil)
                Text("Locks until ${fmtDate(p.lockedUntil)} when you apply.", color = C.Accent, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 12.dp))
            if (locked && current.partnerCode != null) {
                TextButton(onClick = { unlocking = true }, modifier = Modifier.padding(top = 4.dp)) { Text("Unlock early with partner code", color = C.Muted) }
            }
        }
    }
}

@Composable
private fun StatusScreen(p: Policy, health: Health, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val pending by PolicyStore.pending.collectAsState()
    var now by remember { mutableLongStateOf(dev.anchor.policy.TrustedTime.now()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = dev.anchor.policy.TrustedTime.now() } }
    var code by remember { mutableStateOf("") }

    Screen("Protection status", onBack = onBack) {
        SectionTitle("Engine")
        Group {
            if (health.owner) { Check("Device owner", "Switches blocked apps off for real, blocks sites in Chrome, closes every back door.", true); Divider() }
            else {
                Check("Uninstall protection", "Device admin: Android won't remove Hafen while it's on.", health.admin)
                if (!health.admin) TextButton(onClick = {
                    ctx.startActivity(android.content.Intent(android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                        .putExtra(android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN, Enforcer.admin(ctx))
                        .putExtra(android.app.admin.DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Keeps Hafen from being uninstalled while your blocks run."))
                }, modifier = Modifier.padding(start = 8.dp)) { Text("Switch on") }
                Divider()
            }
            Check("Watcher", "Sees which app is open: grayscale per app, limits, Reels.", health.watcher); Divider()
            Check("Secure settings", "Grayscale and keeping the watcher on.", health.secure); Divider()
            Check("Notification access", "Holds back notifications, hides those of blocked apps.", Enforcer.notifyFilterConnected(ctx))
            if (p.dnsFilterEnabled) { Divider(); Check("DNS filter", "Local filter for all apps.", DnsVpnService.running) }
        }
        if (!health.ok) {
            Text("Finish setup from the Mac:", color = C.Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp, start = 4.dp))
            Text("./setup.sh <phone>", fontFamily = FontFamily.Monospace, modifier = Modifier.padding(start = 4.dp, top = 2.dp))
        }

        pending?.let { pd ->
            SectionTitle("Waiting to loosen")
            Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = C.Card) {
                Column(Modifier.padding(20.dp)) {
                    Text(pd.reasons.joinToString("\n") { "• $it" })
                    val left = (pd.applyAt - now).coerceAtLeast(0) / 1000
                    Text("in %dh %02dm %02ds".format(left / 3600, left / 60 % 60, left % 60), style = MaterialTheme.typography.headlineSmall, color = C.Accent, modifier = Modifier.padding(vertical = 12.dp))
                    Button(onClick = { PolicyStore.cancelPending() }, modifier = Modifier.fillMaxWidth()) { Text("Cancel it. I'm good.") }
                    if (p.partnerCode != null) {
                        OutlinedTextField(code, { code = it }, Modifier.fillMaxWidth().padding(top = 12.dp), label = { Text("Partner code") }, singleLine = true)
                        var codeMsg by remember { mutableStateOf<String?>(null) }
                        TextButton(onClick = {
                            codeMsg = when (val r = PolicyStore.applyWithCode(ctx, code)) {
                                PolicyStore.CodeResult.Ok -> null
                                is PolicyStore.CodeResult.Wrong -> "Wrong code. ${r.triesLeft} tries left."
                                is PolicyStore.CodeResult.TooMany -> "Not possible before ${fmtDate(r.until)}."
                            }
                            code = ""
                        }) { Text("Apply now with code") }
                        codeMsg?.let { Text(it, color = C.Danger, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }

        SectionTitle("Today")
        val used = remember(now / 30_000) { Usage.today().filter { it.first != ctx.packageName }.take(8) }
        Group {
            if (used.isEmpty()) Text("Nothing tracked yet.", color = C.Muted, modifier = Modifier.padding(16.dp))
            used.forEachIndexed { i, (pkg, ms) ->
                val label = remember(pkg) { Apps.label(ctx, pkg) }
                AppRow(pkg, label) { Text("${ms / 60_000} min · ${Usage.opens(pkg)}×", color = C.Muted, modifier = Modifier.padding(end = 4.dp)) }
                if (i < used.lastIndex) Divider()
            }
        }

        val reasons = remember(now / 30_000) { Usage.reasons(10) }
        if (reasons.isNotEmpty()) {
            SectionTitle("Reasons you gave")
            Group { reasons.forEach { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) } }
        }
    }
}

@Composable
private fun Check(label: String, detail: String, ok: Boolean) = Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
    Box(Modifier.size(10.dp).padding(0.dp)) { Surface(Modifier.fillMaxSize(), shape = MaterialTheme.shapes.extraLarge, color = if (ok) C.Ok else C.Danger) {} }
    Column(Modifier.padding(start = 14.dp).weight(1f)) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Text(detail, style = MaterialTheme.typography.bodySmall, color = C.Muted)
    }
    Text(if (ok) "On" else "Off", color = if (ok) C.Ok else C.Danger, style = MaterialTheme.typography.labelLarge)
}

@Composable
private fun SafetyScreen(p: Policy, current: Policy, health: Health, now: Long, set: (Policy) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var newCode by remember { mutableStateOf("") }
    var confirmRelease by remember { mutableStateOf(false) }
    Screen("Strictness & partner code", "What happens when you make a rule weaker. Running blocks and the settings lock can't be loosened at all, only ended early with the partner code.", onBack = onBack) {
        SectionTitle("Waiting period")
        Group {
            ToggleRow("Armed", "Off: every change applies at once, good while setting up. On: anything that loosens a rule waits, and Hafen guards itself even without a running block.", p.armed) { set(p.copy(armed = it)) }
            Divider()
            Stepper("Wait before loosening", "Applies only when armed.", p.cooldownHours, 1, 0..336, { "${it}h" }) { set(p.copy(cooldownHours = it)) }
        }

        SectionTitle("Partner code")
        Hint("Someone you trust (a friend, your partner) types a code here that only they know. With it they can end a block or the settings lock early, or apply a waiting change right away. Every use is logged. 5 wrong tries lock the code for an hour, then longer.", Modifier.padding(bottom = 10.dp, start = 4.dp))
        Group {
            if (p.partnerCode == null) {
                Column(Modifier.padding(16.dp)) {
                    OutlinedTextField(newCode, { newCode = it }, Modifier.fillMaxWidth(), label = { Text("Their code (6+ characters)") }, singleLine = true,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
                    TextButton(enabled = newCode.trim().length >= 6, onClick = { set(p.copy(partnerCode = PolicyStore.hashCode(newCode))); newCode = "" }) { Text("Set code") }
                }
            } else {
                Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (current.partnerCode == p.partnerCode) "A code is set." else "New code saves when you apply.", Modifier.weight(1f))
                    TextButton(onClick = { set(p.copy(partnerCode = null)) }) { Text("Remove") }
                }
            }
        }

        SectionTitle("Leaving Hafen")
        Group {
            ToggleRow("Allow removing Hafen", "Shows a button below that lifts everything and gives up device owner. Only works with no running blocks and no settings lock.", p.releaseAllowed) { set(p.copy(releaseAllowed = it)) }
            if ((health.owner || health.admin) && current.releaseAllowed && Targets.active(current, now).isEmpty() && current.lockedUntil < now) {
                Divider()
                TextButton(onClick = { confirmRelease = true }, modifier = Modifier.padding(8.dp)) { Text("Remove Hafen completely", color = C.Danger) }
            }
        }
    }
    if (confirmRelease) AlertDialog(
        onDismissRequest = { confirmRelease = false }, containerColor = C.Card,
        title = { Text("Remove Hafen?") },
        text = { Text("Lifts every restriction, unblocks all apps and gives up device owner. After that Hafen can be uninstalled.") },
        confirmButton = { TextButton(onClick = { Enforcer.release(ctx); confirmRelease = false }) { Text("Remove", color = C.Danger) } },
        dismissButton = { TextButton(onClick = { confirmRelease = false }) { Text("Keep") } },
    )
}

// ---------------------------------------------------------------- more rules

@Composable
private fun LimitsScreen(p: Policy, set: (Policy) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var editing by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }
    val ruled = p.apps.keys.sortedBy { Apps.label(ctx, it).lowercase() }
    Screen("App limits", "Softer than a block: a daily budget, a number of opens, a wait, or a reason before an app opens.", onBack = onBack) {
        Spacer(Modifier.height(12.dp))
        Group {
            ruled.forEach { pkg ->
                val r = p.apps.getValue(pkg)
                AppRow(pkg, Apps.label(ctx, pkg), Rules.summary(r), if (r.mode == Mode.BLOCK) C.Danger else C.Muted, onClick = { editing = pkg })
                Divider()
            }
            Row(Modifier.fillMaxWidth().padding(4.dp)) {
                TextButton(onClick = { picking = true }) { Icon(Icons.Default.Add, null); Text("Add an app", Modifier.padding(start = 8.dp)) }
            }
        }
    }
    if (picking) AppPickerSheet("Limit an app", emptyList(), exclude = p.apps.keys, single = true, onDismiss = { picking = false }) {
        picking = false; editing = it.first()
    }
    editing?.let { pkg ->
        RuleSheet(pkg, p.apps[pkg], onDismiss = { editing = null }) { r ->
            set(p.copy(apps = if (r == null) p.apps - pkg else p.apps + (pkg to r))); editing = null
        }
    }
}

@Composable
private fun RuleSheet(pkg: String, initial: AppRule?, onDismiss: () -> Unit, onSave: (AppRule?) -> Unit) {
    val ctx = LocalContext.current
    var r by remember { mutableStateOf(initial ?: AppRule(mode = Mode.LIMIT, dailyBudgetMin = 30)) }
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = C.Card) {
        Column(Modifier.padding(horizontal = 20.dp).verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(pkg, size = 40.dp)
                Text(Apps.label(ctx, pkg), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 14.dp).weight(1f))
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Close") }
            }
            Spacer(Modifier.height(16.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                Mode.entries.forEachIndexed { i, m ->
                    SegmentedButton(
                        selected = r.mode == m, onClick = { r = r.copy(mode = m) }, shape = SegmentedButtonDefaults.itemShape(i, Mode.entries.size),
                        colors = SegmentedButtonDefaults.colors(activeContainerColor = C.AccentDim, activeContentColor = C.Accent, inactiveContainerColor = C.Card2, inactiveBorderColor = C.Line, activeBorderColor = C.Line),
                    ) { Text(m.name.lowercase().replaceFirstChar { it.uppercase() }) }
                }
            }
            if (r.mode == Mode.LIMIT) {
                SectionTitle("Limits")
                Surface(shape = MaterialTheme.shapes.large, color = C.Card2) {
                    Column {
                        Stepper("Minutes per day", null, r.dailyBudgetMin, 5, 0..600, { if (it == 0) "∞" else "$it" }) { r = r.copy(dailyBudgetMin = it) }
                        Stepper("Opens per day", null, r.maxOpensPerDay, 1, 0..100, { if (it == 0) "∞" else "$it" }) { r = r.copy(maxOpensPerDay = it) }
                        Stepper("Wait before opening", null, r.waitSeconds, 5, 0..300, { "${it}s" }) { r = r.copy(waitSeconds = it) }
                        Stepper("Session length", "Before it asks again", r.sessionMinutes, 5, 1..120, { "${it}m" }) { r = r.copy(sessionMinutes = it) }
                        ToggleRow("Type a reason first", null, r.requireReason) { r = r.copy(requireReason = it) }
                    }
                }
                SectionTitle("Only usable during")
                if (r.windows.isEmpty()) Hint("Any time.", Modifier.padding(start = 4.dp, bottom = 8.dp))
                WindowsEditor(r.windows, "Add a time") { r = r.copy(windows = it) }
            }
            Spacer(Modifier.height(20.dp))
            Button(onClick = { onSave(if (r.mode == Mode.ALLOW) null else r) }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Done") }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun BedtimeScreen(p: Policy, set: (Policy) -> Unit, onBack: () -> Unit) {
    Screen("Bedtime", "During these times every app is switched off except calls, messages, your essentials and the apps below.", onBack = onBack) {
        SectionTitle("When")
        WindowsEditor(p.bedtimeWindows, "Add bedtime", TimeWindow(days = (1..7).toSet(), start = 23 * 60, end = 7 * 60)) { set(p.copy(bedtimeWindows = it)) }
        SectionTitle("Still allowed at bedtime")
        AppListEditor(p.bedtimeAllowed, "Allowed at bedtime") { set(p.copy(bedtimeAllowed = it)) }
    }
}

@Composable
private fun WebScreen(p: Policy, set: (Policy) -> Unit, onBack: () -> Unit) {
    var dom by remember { mutableStateOf("") }
    Screen("Websites & Chrome", "Sites from your blocks are added automatically. These are blocked on top, always.", onBack = onBack) {
        val owner = Enforcer.isOwner(LocalContext.current)
        if (!owner) Banner("Hafen watches Chrome's address bar and leaves blocked sites the moment they load.", C.Muted, Modifier.padding(top = 12.dp))
        if (owner) {
            SectionTitle("Chrome")
            Group { ToggleRow("Harden Chrome", "No incognito, no secure-DNS bypass, SafeSearch forced.", p.chromeHardening) { set(p.copy(chromeHardening = it)) } }
        }

        SectionTitle("Always blocked")
        Group {
            Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(dom, { dom = it }, Modifier.weight(1f), placeholder = { Text("e.g. 9gag.com") }, singleLine = true, shape = MaterialTheme.shapes.medium)
                TextButton(enabled = Targets.normDomain(dom).contains('.'), onClick = {
                    val d = Targets.normDomain(dom)
                    if (d !in p.blockedDomains) set(p.copy(blockedDomains = p.blockedDomains + d))
                    dom = ""
                }) { Text("Add") }
            }
            p.blockedDomains.forEach { d ->
                Divider()
                Row(Modifier.padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(d, Modifier.weight(1f))
                    IconButton(onClick = { set(p.copy(blockedDomains = p.blockedDomains - d)) }) { Icon(Icons.Default.Close, "Remove $d", tint = C.Muted) }
                }
            }
        }

        if (!owner) return@Screen
        SectionTitle("DNS filter (all apps)")
        Banner("Uses Android's VPN slot, so it can't run next to another VPN app. Leave off if you use one.", C.Muted)
        Spacer(Modifier.height(10.dp))
        Group {
            ToggleRow("Filter websites in every app", "Local VPN, only DNS passes through it. Also catches in-app browsers.", p.dnsFilterEnabled) { set(p.copy(dnsFilterEnabled = it)) }
        }
    }
}

@Composable
private fun ShortsScreen(p: Policy, set: (Policy) -> Unit, onBack: () -> Unit) {
    var vid by remember { mutableStateOf("") }
    Screen("Reels & Shorts", "Keep YouTube or Instagram for messages and search, but get thrown out of the endless video feed.", onBack = onBack) {
        Spacer(Modifier.height(12.dp))
        Group { ToggleRow("Kick out of Reels, Shorts and Spotlight", null, p.blockShortForm) { set(p.copy(blockShortForm = it)) } }
        SectionTitle("Detection")
        Hint("Found by on-screen view IDs. Apps rename these sometimes. Add new ones here.", Modifier.padding(start = 4.dp, bottom = 10.dp))
        Group {
            Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(vid, { vid = it }, Modifier.weight(1f), placeholder = { Text("package:id/view_id") }, singleLine = true, shape = MaterialTheme.shapes.medium)
                TextButton(enabled = ":id/" in vid, onClick = { set(p.copy(shortFormViewIds = p.shortFormViewIds + vid.trim())); vid = "" }) { Text("Add") }
            }
            p.shortFormViewIds.forEach { v ->
                Divider()
                Row(Modifier.padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(v, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    IconButton(onClick = { set(p.copy(shortFormViewIds = p.shortFormViewIds - v)) }) { Icon(Icons.Default.Close, "Remove", tint = C.Muted) }
                }
            }
        }
    }
}

@Composable
private fun HomeScreen(p: Policy, set: (Policy) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { while (true) { delay(2000); tick++ } }
    val isHome = remember(tick) {
        ctx.packageManager.resolveActivity(android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME), 0)
            ?.activityInfo?.packageName == ctx.packageName
    }
    val notifyOn = remember(tick) { Enforcer.notifyFilterConnected(ctx) }
    Screen("Home & notifications", "A calm home screen with only your favorites, and a filter that holds back notifications instead of letting them pull you in.", onBack = onBack) {
        SectionTitle("Home screen")
        Group {
            ToggleRow("Use Hafen as home screen", "Your favorites as plain text, everything else one tap away under \"all apps\".", p.launcherEnabled) { set(p.copy(launcherEnabled = it)) }
            if (p.launcherEnabled && !isHome) {
                Divider()
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Not your default home yet. Apply first, then:", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = C.Accent)
                    TextButton(onClick = {
                        val rm = ctx.getSystemService(android.app.role.RoleManager::class.java)
                        runCatching { (ctx as android.app.Activity).startActivityForResult(rm.createRequestRoleIntent(android.app.role.RoleManager.ROLE_HOME), 7) }
                            .onFailure { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_HOME_SETTINGS)) }
                    }) { Text("Make default") }
                }
            }
            Divider()
            ToggleRow("Show \"all apps\"", "Off = no app list at all, only your favorites. Very strict.", p.allAppsDelaySec >= 0) {
                set(p.copy(allAppsDelaySec = if (it) 0 else -1))
            }
        }
        SectionTitle("Favorites")
        Hint("Shown big on the home screen. You can also long-press any app there to add or remove it.", Modifier.padding(bottom = 10.dp, start = 4.dp))
        AppListEditor(p.launcherApps, "Favorites", emptyText = "Just phone and messages.") { set(p.copy(launcherApps = it)) }

        SectionTitle("Notification filter")
        if (!notifyOn) {
            Banner("Needs notification access for Hafen.", C.Accent)
            TextButton(onClick = {
                val i = android.content.Intent("android.settings.NOTIFICATION_LISTENER_DETAIL_SETTINGS")
                    .putExtra("android.provider.extra.NOTIFICATION_LISTENER_COMPONENT_NAME", android.content.ComponentName(ctx, dev.anchor.service.NotifyService::class.java).flattenToString())
                runCatching { ctx.startActivity(i) }.onFailure { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
            }) { Text("Allow access") }
            Spacer(Modifier.height(8.dp))
        }
        Group {
            ToggleRow("Hold back notifications", "Only the apps below can buzz you. Calls, SMS and your essentials always can. The rest wait quietly on the home screen.", p.notifyFilter) { set(p.copy(notifyFilter = it)) }
        }
        Hint("Blocked apps never notify, filter or not. Their messages wait until the block ends.", Modifier.padding(top = 10.dp, start = 4.dp))
        SectionTitle("When you can read them")
        Hint("Held notifications stay invisible outside these times. Then they show quietly at the bottom of the home screen. Up to 3 a day.", Modifier.padding(bottom = 10.dp, start = 4.dp))
        WindowsEditor(p.inboxWindows, "Add a reading time", TimeWindow(days = (1..7).toSet(), start = 17 * 60, end = 17 * 60 + 30), max = 3) { set(p.copy(inboxWindows = it)) }
        SectionTitle("Allowed to notify")
        AppListEditor(p.notifyAllowed, "Allowed to notify", emptyText = "Only calls, SMS and essentials.") { set(p.copy(notifyAllowed = it)) }
    }
}

@Composable
private fun NudgeSettings(p: Policy, set: (Policy) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var picking by remember { mutableStateOf(false) }
    Screen("Evening nudge", "Opening an app in the evening first asks: wouldn't you rather read? You can always go on, it just makes you choose.", onBack = onBack) {
        Spacer(Modifier.height(12.dp))
        Group {
            ToggleRow("Suggest a book", "Before apps from \"all apps\" open. Never for your essentials or the favorites on your home screen.", p.nudgeEnabled) { set(p.copy(nudgeEnabled = it)) }
            Divider()
            Stepper("Then leave me alone for", "After \"open anyway\", per app.", p.nudgePassMinutes, 5, 5..60, { "${it}m" }) { set(p.copy(nudgePassMinutes = it)) }
        }
        SectionTitle("When")
        WindowsEditor(p.nudgeWindows, "Add a time", TimeWindow(days = (1..7).toSet(), start = 17 * 60, end = 0)) { set(p.copy(nudgeWindows = it)) }
        SectionTitle("What it says")
        OutlinedTextField(p.nudgeText, { set(p.copy(nudgeText = it.take(80))) }, Modifier.fillMaxWidth(), singleLine = true, shape = MaterialTheme.shapes.medium)
        SectionTitle("Offer instead")
        Group {
            val app = p.nudgeApp
            if (app != null && AppCache.installed(ctx, app)) {
                AppRow(app, Apps.label(ctx, app), "Shown as the better option") {
                    IconButton(onClick = { set(p.copy(nudgeApp = null)) }) { Icon(Icons.Default.Close, "Remove", tint = C.Muted) }
                }
            } else {
                NavRow("Choose an app (e.g. Audible)", null) { picking = true }
            }
        }
    }
    if (picking) AppPickerSheet("Offer instead", emptyList(), single = true, onDismiss = { picking = false }) {
        set(p.copy(nudgeApp = it.first())); picking = false
    }
}

@Composable
private fun DeviceScreen(p: Policy, set: (Policy) -> Unit, onBack: () -> Unit) {
    val owner = Enforcer.isOwner(LocalContext.current)
    Screen("New apps & device", "What happens with apps you install later, and the back doors around Hafen.", onBack = onBack) {
        SectionTitle("New apps")
        Group {
            ToggleRow("Block newly installed apps", "A new app stays blocked until you give it a rule or mark it essential.", p.blockNewApps) { set(p.copy(blockNewApps = it)) }
        }
        if (!owner) {
            SectionTitle("Device restrictions")
            Banner("Factory reset, safe mode, clock and VPN locks need Device Owner, which isn't set up on this phone. Instead Hafen guards its own uninstall, accessibility, notification and home screen settings, and ignores clock changes.", C.Muted)
            return@Screen
        }
        SectionTitle("Clock")
        Group { ToggleRow("Force network time", "The clock can't be set by hand.", p.forceAutoTime) { set(p.copy(forceAutoTime = it)) } }
        SectionTitle("Restrictions")
        Group {
            RESTRICTIONS.forEachIndexed { i, r ->
                ToggleRow(r.title, r.detail, p.restrictions[r.key] == true, warn = r.danger) { set(p.copy(restrictions = p.restrictions + (r.key to it))) }
                if (i < RESTRICTIONS.lastIndex) Divider()
            }
        }
        SectionTitle("Message on blocked apps")
        OutlinedTextField(p.blockedMessage, { set(p.copy(blockedMessage = it.take(200))) }, Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium)
    }
}

