@file:OptIn(ExperimentalMaterial3Api::class)

package dev.anchor.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.anchor.R
import dev.anchor.enforce.Rules
import dev.anchor.policy.Commitment
import dev.anchor.policy.Policy
import dev.anchor.policy.PolicyStore
import dev.anchor.policy.Schedule
import dev.anchor.policy.Targets
import dev.anchor.policy.TimeWindow
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

internal const val DAY = 86_400_000L

internal val DURATIONS = listOf(
    "Rest of today" to -1L, "24 hours" to DAY, "3 days" to 3 * DAY,
    "1 week" to 7 * DAY, "2 weeks" to 14 * DAY, "1 month" to 30 * DAY,
)

/** Icon for a block target: the installed app's icon, or a glyph. */
@Composable
fun TargetIcon(key: String, size: androidx.compose.ui.unit.Dp = 44.dp) {
    val ctx = LocalContext.current
    val t = remember(key) { Targets.resolve(key) }
    when {
        key == "games" -> LetterIcon("G", size, glyph = "🎮")
        key.startsWith("site:") -> LetterIcon("W", size, glyph = "🌐")
        else -> {
            val pkg = remember(key) { t.packages.firstOrNull { AppCache.installed(ctx, it) } }
            if (pkg != null) AppIcon(pkg, t.label, size) else LetterIcon(t.label, size)
        }
    }
}

// ---------------------------------------------------------------- running blocks

@Composable
internal fun BlocksScreen(
    current: Policy, pill: PillState,
    onNew: (Set<String>) -> Unit, onOutcome: (PolicyStore.Outcome) -> Unit, onStatus: () -> Unit, onEnded: (String) -> Unit,
) {
    val ctx = LocalContext.current
    var now by remember { mutableLongStateOf(dev.anchor.policy.TrustedTime.now()) }
    LaunchedEffect(Unit) { while (true) { delay(20_000); now = dev.anchor.policy.TrustedTime.now() } }
    val active = Targets.active(current, now).sortedBy { it.until }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_anchor), null, tint = C.Accent, modifier = Modifier.size(26.dp))
            Text("hafen", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 8.dp).weight(1f))
            StatusPill(pill, onStatus)
        }
        Spacer(Modifier.height(28.dp))
        Text(
            when (active.size) { 0 -> "Nothing blocked"; 1 -> "1 block running"; else -> "${active.size} blocks running" },
            style = MaterialTheme.typography.headlineLarge,
        )
        Text(
            if (active.isEmpty()) "Pick what you want gone and for how long. Once it's locked, there's no way back until it ends."
            else if (current.partnerCode != null) "These can't be paused or shortened. Only extended, or ended early with your partner's code."
            else "These can't be paused, shortened or removed. Only extended.",
            style = MaterialTheme.typography.bodyMedium, color = C.Muted, modifier = Modifier.padding(top = 6.dp, bottom = 16.dp),
        )
        if (current.lockedUntil > now) {
            Banner("Settings locked until ${fmtDate(current.lockedUntil)}. Only stricter changes until then.")
            Spacer(Modifier.height(12.dp))
        }

        active.forEach { c ->
            BlockCard(c, now, hasCode = current.partnerCode != null, onEnded = onEnded) { extra ->
                onOutcome(PolicyStore.addBlocks(ctx, listOf(c.target), c.until + extra, c.windows))
            }
        }

        if (active.isEmpty()) {
            SectionTitle("Start with one")
            FlowPills {
                listOf("instagram", "youtube", "x", "linkedin", "games").forEach { k ->
                    Pill("No ${Targets.resolve(k).label}", selected = false) { onNew(setOf(k)) }
                }
            }
        }
        Spacer(Modifier.height(120.dp))
    }
}

internal enum class PillState(val label: String) { PROTECTED("Protected"), UNARMED("Not armed"), SETUP("Setup needed") }

@Composable
private fun StatusPill(state: PillState, onClick: () -> Unit) {
    val color = when (state) { PillState.PROTECTED -> C.Ok; PillState.UNARMED -> C.Muted; PillState.SETUP -> C.Accent }
    Row(
        Modifier.clip(CircleShape).background(color.copy(alpha = 0.12f)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Text(state.label, color = color, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun BlockCard(c: Commitment, now: Long, hasCode: Boolean, onEnded: (String) -> Unit, onExtend: (Long) -> Unit) {
    val ctx = LocalContext.current
    val t = remember(c.target) { Targets.resolve(c.target, ctx) }
    val rhythm = c.windows.isNotEmpty()
    val nowT = dev.anchor.policy.TrustedTime.localNow()
    val on = !rhythm || Schedule.contains(c.windows, nowT)
    var extending by remember { mutableStateOf(false) }
    var ending by remember { mutableStateOf(false) }
    if (ending) CodeDialog(
        "End \"no ${t.label}\" early?",
        "Your partner types their code. The block ends right away. This is logged.",
        onDismiss = { ending = false },
    ) { code ->
        PolicyStore.overrideWithCode(ctx, code, block = c).also { if (it == PolicyStore.CodeResult.Ok) onEnded("Block on ${t.label} ended.") }
    }

    Surface(Modifier.fillMaxWidth().padding(bottom = 12.dp), shape = MaterialTheme.shapes.large, color = C.Card) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TargetIcon(c.target)
                Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                    Text("No ${t.label}", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        if (rhythm) c.windows.joinToString(", ") { Schedule.describe(it) } else "All the time",
                        style = MaterialTheme.typography.bodySmall, color = C.Muted,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(fmtLeft(c.until - now), style = MaterialTheme.typography.titleLarge, color = C.Accent)
                    Text("left", style = MaterialTheme.typography.bodySmall, color = C.Muted)
                }
            }
            if (c.createdAt in 1 until c.until) {
                val f = ((now - c.createdAt).toFloat() / (c.until - c.createdAt)).coerceIn(0f, 1f)
                LinearProgressIndicator(
                    progress = { f }, modifier = Modifier.fillMaxWidth().padding(top = 14.dp).height(6.dp),
                    color = C.Accent, trackColor = C.Card3, strokeCap = StrokeCap.Round, gapSize = 0.dp, drawStopIndicator = {},
                )
            }
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("until ${fmtDate(c.until)}", style = MaterialTheme.typography.bodySmall, color = C.Muted)
                    if (rhythm) {
                        val next = Schedule.nextChange(c.windows, nowT)
                        val nextTxt = next?.let { if (on) " · ends ${fmtDate(it)}" else " · starts ${fmtDate(it)}" } ?: ""
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                            Box(Modifier.size(7.dp).clip(CircleShape).background(if (on) C.Accent else C.Muted))
                            Text((if (on) "Blocked now" else "Usable now") + nextTxt, style = MaterialTheme.typography.bodySmall,
                                color = if (on) C.Accent else C.Muted, modifier = Modifier.padding(start = 6.dp))
                        }
                    }
                }
                TextButton(
                    onClick = { extending = !extending }, modifier = Modifier.height(34.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
                ) { Text(if (extending) "Close" else "Extend") }
            }
            if (extending) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                listOf("+1 day" to DAY, "+3 days" to 3 * DAY, "+1 week" to 7 * DAY).forEach { (l, d) ->
                    Pill(l, selected = false) { onExtend(d); extending = false }
                }
            }
            if (extending && hasCode) TextButton(onClick = { ending = true; extending = false }) {
                Text("End early with partner code", color = C.Muted)
            }
        }
    }
}

// ---------------------------------------------------------------- new block

@Composable
internal fun NewBlockScreen(preselected: Set<String>, onClose: () -> Unit, onLock: (List<String>, Long, List<TimeWindow>) -> Unit) {
    val ctx = LocalContext.current
    BackHandler(onBack = onClose)
    var chosen by remember { mutableStateOf(preselected) }
    var duration by remember { mutableLongStateOf(7 * DAY) }
    var customDays by remember { mutableIntStateOf(0) }
    var custom by remember { mutableStateOf(false) }
    var rhythm by remember { mutableStateOf(false) }
    var windows by remember { mutableStateOf(listOf(TimeWindow(days = (1..5).toSet(), start = 8 * 60, end = 18 * 60))) }
    var pickApps by remember { mutableStateOf(false) }
    var siteOpen by remember { mutableStateOf(false) }
    var site by remember { mutableStateOf("") }
    var tick by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); tick = System.currentTimeMillis() } }

    val until = remember(duration, customDays, custom, tick) {
        val now = dev.anchor.policy.TrustedTime.now()
        when {
            custom -> now + customDays.coerceAtLeast(1) * DAY
            duration < 0 -> dev.anchor.policy.TrustedTime.localNow().toLocalDate().plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            else -> now + duration
        }
    }
    fun toggle(k: String) { chosen = if (k in chosen) chosen - k else chosen + k }
    val windowsOk = !rhythm || (windows.isNotEmpty() && windows.all { it.days.isNotEmpty() })
    val ready = chosen.isNotEmpty() && windowsOk

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Cancel") }
            Text("New block", style = MaterialTheme.typography.titleLarge)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Step(1, "What should be gone?")
            val tiles = Targets.BUILTIN.map { it.key }
            (tiles + listOf("+app", "+site")).chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 10.dp)) {
                    row.forEach { k ->
                        val mod = Modifier.weight(1f)
                        when (k) {
                            "+app" -> Tile("Other app", selected = false, mod, icon = { LetterIcon("+", 40.dp, glyph = "+") }) { pickApps = true }
                            "+site" -> Tile("Website", selected = siteOpen, mod, icon = { LetterIcon("W", 40.dp, glyph = "🌐") }) { siteOpen = !siteOpen }
                            else -> Tile(Targets.resolve(k).label, selected = k in chosen, mod, icon = { TargetIcon(k, 40.dp) }) { toggle(k) }
                        }
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            if (siteOpen) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                OutlinedTextField(site, { site = it }, Modifier.weight(1f), placeholder = { Text("e.g. reddit.com") }, singleLine = true, shape = MaterialTheme.shapes.medium)
                TextButton(enabled = Targets.normDomain(site).contains('.'), onClick = { chosen = chosen + Targets.siteKey(site); site = "" }) { Text("Add") }
            }
            val extra = chosen.filter { it.startsWith("app:") || it.startsWith("site:") }
            if (extra.isNotEmpty()) Group(Modifier.padding(bottom = 8.dp)) {
                extra.forEachIndexed { i, k ->
                    val label = remember(k) { Targets.resolve(k, ctx).label }
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        TargetIcon(k, 32.dp)
                        Text(label, Modifier.weight(1f).padding(start = 12.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        IconButton(onClick = { toggle(k) }) { Icon(Icons.Default.Close, "Remove $label", tint = C.Muted) }
                    }
                    if (i < extra.lastIndex) Divider()
                }
            }
            if ("games" in chosen) Hint("Games covers every app Android lists as a game, including ones installed later, plus the usual game sites.")
            if (chosen.any { Targets.resolve(it).domains.isNotEmpty() })
                Hint("Websites are blocked in Chrome. Other browsers are switched off while a website block runs.", Modifier.padding(top = 4.dp))

            Step(2, "When?")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf("All the time", "Daily rhythm").forEachIndexed { i, l ->
                    SegmentedButton(
                        selected = rhythm == (i == 1), onClick = { rhythm = i == 1 },
                        shape = SegmentedButtonDefaults.itemShape(i, 2),
                        colors = SegmentedButtonDefaults.colors(activeContainerColor = C.AccentDim, activeContentColor = C.Accent, inactiveContainerColor = C.Card, inactiveBorderColor = C.Line, activeBorderColor = C.Line),
                    ) { Text(l) }
                }
            }
            if (rhythm) {
                Hint("Blocked only during these times, every week, until the end date.", Modifier.padding(top = 10.dp, bottom = 10.dp))
                WindowsEditor(windows, "Add another time", TimeWindow(days = (1..7).toSet(), start = 21 * 60, end = 7 * 60)) { windows = it }
            }

            Step(3, if (rhythm) "Until when?" else "For how long?")
            FlowPills {
                DURATIONS.forEach { (label, d) -> Pill(label, selected = !custom && duration == d) { duration = d; custom = false } }
                Pill("Custom", selected = custom) { custom = true; if (customDays == 0) customDays = 10 }
            }
            if (custom) Group(Modifier.padding(top = 12.dp)) {
                Stepper("Days", value = customDays, step = 1, range = 1..365) { customDays = it }
            }
            Text("Ends ${fmtDate(until)}", style = MaterialTheme.typography.bodyMedium, color = C.Muted, modifier = Modifier.padding(top = 12.dp, start = 4.dp))
            Spacer(Modifier.height(24.dp))
        }

        // summary + confirm
        Surface(color = C.Card, shape = MaterialTheme.shapes.extraLarge.copy(bottomStart = androidx.compose.foundation.shape.CornerSize(0.dp), bottomEnd = androidx.compose.foundation.shape.CornerSize(0.dp))) {
            Column(Modifier.fillMaxWidth().padding(20.dp)) {
                val names = chosen.map { Targets.resolve(it, ctx).label }
                Text(
                    if (names.isEmpty()) "Pick at least one thing." else "No ${names.joinToString(", ")}" +
                        (if (rhythm) " during ${windows.joinToString(", ") { Schedule.describe(it) }}" else "") + " until ${fmtDate(until)}.",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp, bottom = 14.dp)) {
                    Icon(Icons.Default.Lock, null, tint = C.Muted, modifier = Modifier.size(14.dp))
                    Text("No way back before then. Hold the button to lock it.", style = MaterialTheme.typography.bodySmall, color = C.Muted, modifier = Modifier.padding(start = 6.dp))
                }
                HoldButton("Hold to lock it", enabled = ready) { onLock(chosen.toList(), until, if (rhythm) windows else emptyList()) }
            }
        }
    }

    if (pickApps) {
        val hard = remember { Rules.essentials(ctx) }
        val selectedPkgs = chosen.filter { it.startsWith("app:") }.map { it.removePrefix("app:") }
        AppPickerSheet("Block apps", selectedPkgs, exclude = hard, onDismiss = { pickApps = false }) { pkgs ->
            chosen = chosen.filterNot { it.startsWith("app:") }.toSet() + pkgs.map(Targets::appKey)
            pickApps = false
        }
    }
}

@Composable
private fun Step(n: Int, title: String) = Row(Modifier.padding(top = 24.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
    Box(Modifier.size(24.dp).clip(CircleShape).background(C.Card3), contentAlignment = Alignment.Center) {
        Text("$n", style = MaterialTheme.typography.labelMedium, color = C.Muted)
    }
    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 10.dp))
}

@Composable
private fun Tile(label: String, selected: Boolean, modifier: Modifier, icon: @Composable () -> Unit, onClick: () -> Unit) {
    Box(
        modifier.clip(MaterialTheme.shapes.medium).background(if (selected) C.AccentDim else C.Card)
            .border(BorderStroke(if (selected) 2.dp else 0.dp, if (selected) C.Accent else C.Card), MaterialTheme.shapes.medium)
            .clickable(onClick = onClick).padding(vertical = 14.dp, horizontal = 6.dp),
    ) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            icon()
            Text(label, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center, maxLines = 2,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
        }
        if (selected) Box(Modifier.align(Alignment.TopEnd).padding(end = 6.dp).size(18.dp).clip(CircleShape).background(C.Accent), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Check, null, tint = C.OnAccent, modifier = Modifier.size(12.dp))
        }
    }
}

