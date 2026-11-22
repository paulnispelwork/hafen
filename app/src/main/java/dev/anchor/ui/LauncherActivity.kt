@file:OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)

package dev.anchor.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Telephony
import android.telecom.TelecomManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.anchor.enforce.Apps
import dev.anchor.enforce.Held
import dev.anchor.enforce.Inbox
import dev.anchor.enforce.Rules
import dev.anchor.enforce.Usage
import dev.anchor.policy.PolicyStore
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Calm, text-only home screen: your favorites, held notifications, everything else behind a wait. */
class LauncherActivity : ComponentActivity() {
    private var resumeTick by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(SystemBarStyle.dark(C.Bg.toArgb()), SystemBarStyle.dark(C.Bg.toArgb()))
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this) { /* home stays home */ }
        setContent { AnchorTheme { LauncherScreen(resumeTick) } }
    }

    override fun onResume() { super.onResume(); resumeTick++ }
}

private fun defaultApps(ctx: Context): List<String> = listOfNotNull(
    runCatching { ctx.getSystemService(TelecomManager::class.java)?.defaultDialerPackage }.getOrNull(),
    runCatching { Telephony.Sms.getDefaultSmsPackage(ctx) }.getOrNull(),
)

private fun fmtDuration(ms: Long): String {
    val m = ms / 60_000
    return if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m"
}

private fun toast(ctx: Context, s: String) = Toast.makeText(ctx, s, Toast.LENGTH_SHORT).show()

/** Favorites edits from the home screen go through the same rules as the app (more apps = loosening). */
private fun setFavorites(ctx: Context, list: List<String>) {
    val p = PolicyStore.policy.value
    when (val o = PolicyStore.propose(ctx, p.copy(launcherApps = list))) {
        is PolicyStore.Outcome.Queued -> toast(ctx, "Added after the waiting period: ${fmtDate(o.at)}")
        is PolicyStore.Outcome.Locked -> toast(ctx, "Settings are locked until ${fmtDate(o.until)}")
        else -> {}
    }
}

@Composable
private fun LauncherScreen(resumeTick: Int) {
    val ctx = LocalContext.current
    val p by PolicyStore.policy.collectAsState()
    val held by Inbox.items.collectAsState()
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    var showAll by remember { mutableStateOf(false) }
    var inbox by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<Apps.Info?>(null) }
    val canBrowse = p.allAppsDelaySec >= 0

    LaunchedEffect(resumeTick) {
        showAll = false
        // tick on the minute, so the clock is never late
        while (true) { now = LocalDateTime.now(); delay(60_000 - System.currentTimeMillis() % 60_000 + 50) }
    }

    val favorites = remember(p.launcherApps, resumeTick) {
        p.launcherApps.ifEmpty { defaultApps(ctx) }.filter { AppCache.installed(ctx, it) }.map { Apps.Info(it, Apps.label(ctx, it)) }
    }
    val screenTime = remember(now, resumeTick) { Usage.today().filter { it.first != ctx.packageName }.sumOf { it.second } }

    fun open(a: Apps.Info) {
        val reason = Rules.blockReason(p, a.pkg)
        if (reason != null) toast(ctx, reason) else Apps.launch(ctx, a.pkg)
    }

    if (showAll) {
        AllApps(p, now, onOpen = ::open, onLong = { menuFor = it }, onClose = { showAll = false })
    } else Column(
        Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 32.dp)
            // swipe up anywhere = all apps
            .pointerInput(canBrowse) {
                if (!canBrowse) return@pointerInput
                var dy = 0f
                detectVerticalDragGestures(onDragStart = { dy = 0f }, onDragEnd = { if (dy < -120f) showAll = true }) { _, d -> dy += d }
            },
    ) {
        Spacer(Modifier.height(28.dp))
        // clock: tap opens your alarms
        Text(
            now.format(DateTimeFormatter.ofPattern("HH:mm")), fontSize = 80.sp, fontWeight = FontWeight.Thin, letterSpacing = (-2).sp,
            modifier = Modifier.combinedClickable(onClick = {
                runCatching { ctx.startActivity(Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }),
        )
        val battery = remember(now, resumeTick) {
            val bm = ctx.getSystemService(android.os.BatteryManager::class.java)
            val pct = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
            if (bm.isCharging) "$pct% charging" else "$pct%"
        }
        val alarm = remember(now, resumeTick) {
            ctx.getSystemService(android.app.AlarmManager::class.java).nextAlarmClock?.triggerTime?.let { "alarm ${fmtDate(it)}" }
        }
        Text(now.format(DateTimeFormatter.ofPattern("EEEE d MMMM")).lowercase() + "  ·  " + battery, color = C.Muted, fontSize = 15.sp)
        Text(
            listOfNotNull("${fmtDuration(screenTime)} on the phone", Usage.count("unlock").let { if (it == 1) "1 unlock" else "$it unlocks" }, alarm).joinToString("  ·  "),
            color = C.Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp),
        )
        if (Rules.inBedtime(p)) Text("bedtime. put it down.", color = C.Accent, modifier = Modifier.padding(top = 10.dp))

        Spacer(Modifier.weight(1f))
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            favorites.forEach { a ->
                val reason = Rules.blockReason(p, a.pkg)
                Column(Modifier.combinedClickable(onClick = { open(a) }, onLongClick = { menuFor = a })) {
                    Text(a.label.lowercase(), fontSize = 34.sp, fontWeight = FontWeight.Light, color = if (reason != null) C.Muted.copy(alpha = 0.45f) else C.Text)
                    if (reason != null) Text(reason, fontSize = 12.sp, color = C.Muted.copy(alpha = 0.6f))
                }
            }
        }
        Spacer(Modifier.weight(1.3f))

        // bottom bar: held notifications · all apps · hafen
        Row(Modifier.fillMaxWidth().padding(vertical = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            // held notifications show up only in your reading times, and quietly
            val readable = p.inboxWindows.isEmpty() || dev.anchor.policy.Schedule.contains(p.inboxWindows, dev.anchor.policy.TrustedTime.localNow())
            val n = if (readable) held.size else 0
            Text(
                if (n == 0) "" else if (n == 1) "1 notification" else "$n notifications",
                color = C.Muted, fontSize = 15.sp,
                modifier = Modifier.weight(1f).combinedClickable(enabled = n > 0, onClick = { inbox = true }),
            )
            if (canBrowse) Text(
                "all apps", color = C.Muted, fontSize = 15.sp,
                modifier = Modifier.padding(start = 24.dp).combinedClickable(onClick = { showAll = true }),
            )
            Text("hafen", color = C.Muted, fontSize = 15.sp, modifier = Modifier.padding(start = 24.dp).combinedClickable(onClick = {
                ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }))
        }
    }

    menuFor?.let { a ->
        val shownFavs = p.launcherApps.ifEmpty { defaultApps(ctx) } // phone + messages count as favorites too
        val fav = a.pkg in shownFavs
        AlertDialog(
            onDismissRequest = { menuFor = null }, containerColor = C.Card,
            title = { Text(a.label) },
            confirmButton = {
                TextButton(onClick = {
                    setFavorites(ctx, if (fav) shownFavs - a.pkg else shownFavs + a.pkg)
                    menuFor = null
                }) { Text(if (fav) "Remove from home" else "Add to home") }
            },
            dismissButton = { TextButton(onClick = { menuFor = null }) { Text("Cancel") } },
        )
    }

    if (inbox) InboxSheet(held, p, onDismiss = { inbox = false })
}

private val TIME = DateTimeFormatter.ofPattern("HH:mm")

/** Who makes an app, from its package name: com.google… → google, com.azure… → microsoft. */
private fun vendorOf(pkg: String): String {
    val parts = pkg.split('.')
    val v = parts.getOrNull(1)?.takeIf { parts.size > 2 } ?: parts.first()
    return when (v) {
        "azure", "microsoft", "skype" -> "microsoft"
        "android" -> "android"
        else -> v
    }
}

/** Every app, searchable. Keyboard opens straight away; Enter opens the first hit. */
@Composable
private fun AllApps(p: dev.anchor.policy.Policy, now: LocalDateTime, onOpen: (Apps.Info) -> Unit, onLong: (Apps.Info) -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    BackHandler(onBack = onClose)
    val apps by androidx.compose.runtime.produceState<List<Apps.Info>?>(null) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { AppCache.apps(ctx) }
    }
    var q by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    // names that appear more than once get their maker underneath ("authenticator" ×3)
    val dupes = remember(apps) { apps.orEmpty().groupBy { it.label.lowercase() }.filterValues { it.size > 1 }.keys }
    val t = q.trim()
    // best hits first: name starts with it, then a word starts with it, then anywhere
    val shown = if (t.isEmpty()) apps.orEmpty() else apps.orEmpty().filter { it.label.contains(t, ignoreCase = true) }
        .sortedBy { a ->
            when {
                a.label.startsWith(t, ignoreCase = true) -> 0
                a.label.split(' ', '-', '.').any { w -> w.startsWith(t, ignoreCase = true) } -> 1
                else -> 2
            }
        }

    Column(Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(horizontal = 32.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 28.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                if (q.isEmpty()) Text("search", fontSize = 30.sp, fontWeight = FontWeight.Light, color = C.Muted.copy(alpha = 0.6f))
                BasicTextField(
                    q, { q = it }, singleLine = true,
                    textStyle = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Light, color = C.Text),
                    cursorBrush = SolidColor(C.Accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go, autoCorrectEnabled = false, capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.None),
                    keyboardActions = KeyboardActions(onGo = { shown.firstOrNull()?.let(onOpen) }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
            Text(if (q.isEmpty()) "close" else "clear", color = C.Muted, fontSize = 15.sp,
                modifier = Modifier.padding(start = 16.dp).combinedClickable(onClick = { if (q.isEmpty()) onClose() else q = "" }))
        }
        HorizontalDivider(color = C.Line)
        if (apps != null && shown.isEmpty()) Text("nothing called \"$q\"", color = C.Muted, modifier = Modifier.padding(top = 24.dp))
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(vertical = 12.dp)) {
            val groups = if (q.isBlank()) shown.groupBy { it.label.firstOrNull()?.uppercaseChar()?.takeIf { c -> c.isLetter() } ?: '#' } else mapOf(' ' to shown)
            groups.forEach { (letter, list) ->
                if (letter != ' ') stickyHeader(key = "h$letter") {
                    Text(letter.toString(), color = C.Accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.fillMaxWidth().background(C.Bg).padding(top = 14.dp, bottom = 4.dp))
                }
                items(list, key = { it.pkg }) { a ->
                    val reason = Rules.blockReason(p, a.pkg)
                    val sub = reason ?: if (a.label.lowercase() in dupes) vendorOf(a.pkg) else null
                    Row(
                        Modifier.fillMaxWidth().combinedClickable(onClick = { onOpen(a) }, onLongClick = { onLong(a) }).padding(vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.alpha(if (reason != null) 0.35f else 1f)) { AppIcon(a.pkg, a.label, 34.dp) }
                        Column(Modifier.padding(start = 16.dp)) {
                            Text(a.label.lowercase(), fontSize = 23.sp, fontWeight = FontWeight.Light, color = if (reason != null) C.Muted.copy(alpha = 0.45f) else C.Text)
                            if (sub != null) Text(sub, fontSize = 12.sp, color = C.Muted.copy(alpha = if (reason != null) 0.6f else 1f))
                        }
                    }
                }
            }
        }
        Text("long-press an app to add it to your home screen", color = C.Muted.copy(alpha = 0.6f), fontSize = 12.sp, modifier = Modifier.padding(vertical = 12.dp))
    }
}

@Composable
private fun InboxSheet(held: List<Held>, p: dev.anchor.policy.Policy, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = C.Card) {
        Column(Modifier.fillMaxHeight(0.85f).padding(horizontal = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Held notifications", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = { Inbox.clear(); onDismiss() }) { Text("Clear all") }
            }
            Hint("Collected quietly instead of buzzing. Tap an app to open it and clear its messages.", Modifier.padding(bottom = 12.dp))
            val groups = held.groupBy { it.pkg }.entries.sortedByDescending { e -> e.value.maxOf { it.at } }
            LazyColumn(Modifier.weight(1f)) {
                items(groups.toList(), key = { it.key }) { (pkg, list) ->
                    val label = remember(pkg) { Apps.label(ctx, pkg) }
                    val reason = Rules.blockReason(p, pkg)
                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp).combinedClickable(onClick = {
                        if (reason != null) toast(ctx, reason) else { Inbox.removeApp(pkg); Apps.launch(ctx, pkg); onDismiss() }
                    })) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AppIcon(pkg, label, 28.dp)
                            Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 10.dp).weight(1f))
                            Text("${list.size}", color = C.Muted, style = MaterialTheme.typography.labelLarge)
                        }
                        if (reason != null) {
                            Text("Hidden until the block ends.", color = C.Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 38.dp, top = 4.dp))
                        } else list.sortedByDescending { it.at }.take(4).forEach { h ->
                            val t = LocalDateTime.ofInstant(Instant.ofEpochMilli(h.at), ZoneId.systemDefault()).format(TIME)
                            Text(
                                listOf(h.title, h.text).filter { it.isNotBlank() }.joinToString(": ") + "  · $t",
                                color = C.Muted, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(start = 38.dp, top = 4.dp),
                            )
                        }
                    }
                    Divider()
                }
            }
        }
    }
}
