package dev.anchor.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.anchor.enforce.Apps
import dev.anchor.enforce.DayStats
import dev.anchor.enforce.Stats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private fun hm(ms: Long): String {
    val m = ms / 60_000
    return if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m"
}

private val WEEKDAY = DateTimeFormatter.ofPattern("EEE d MMM")

private fun plural(n: Int, word: String) = if (n == 1) "1 $word" else "$n ${word}s"

@Composable
internal fun StatsScreen(refresh: Int) {
    val ctx = LocalContext.current
    var range by remember { mutableIntStateOf(7) }
    var all by remember { mutableStateOf<List<DayStats>?>(null) }
    androidx.compose.runtime.LaunchedEffect(refresh) { all = withContext(Dispatchers.IO) { Stats.history(ctx, 30) } }
    val access = remember(refresh) { Stats.hasAccess(ctx) }

    Screen("Stats", "Where your time goes, and how often the phone pulls you back.") {
        if (!access) {
            Banner("Needs usage access to show your history. Without it, only what Hafen counted itself shows up.", C.Accent, Modifier.padding(top = 8.dp))
            TextButton(onClick = { ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }) { Text("Allow usage access") }
        }
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("7 days", range == 7) { range = 7 }
            Pill("30 days", range == 30) { range = 30 }
        }
        val days = all
        if (days == null) {
            Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = C.Accent) }
            return@Screen
        }
        val shown = days.takeLast(range)
        val today = shown.last()
        val past = shown.dropLast(1).filter { it.total > 0 || it.unlocks > 0 }
        val avg = if (past.isEmpty()) 0L else past.sumOf { it.total } / past.size

        // headline: today vs. the average of the other days in range
        Spacer(Modifier.height(20.dp))
        Text("Today", color = C.Muted, style = MaterialTheme.typography.titleSmall)
        Text(hm(today.total), style = MaterialTheme.typography.displayMedium)
        if (past.isNotEmpty()) {
            val diff = today.total - avg
            Text(
                (if (diff <= 0) "${hm(-diff)} less" else "${hm(diff)} more") + " than your ${range}-day average (${hm(avg)})",
                color = C.Muted, style = MaterialTheme.typography.bodyMedium,
            )
        }

        Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Tile("Unlocks", "${today.unlocks}", past.takeIf { it.isNotEmpty() }?.let { "avg ${it.sumOf { d -> d.unlocks } / it.size}" }, Modifier.weight(1f))
            Tile("Blocked", "${today.attempts}", "tries today", Modifier.weight(1f))
            Tile("Book instead", if (today.nudges == 0) "–" else "${today.nudgesHeeded}/${today.nudges}", "nudges", Modifier.weight(1f))
        }

        var selDay by remember(range) { mutableIntStateOf(shown.lastIndex) }
        var appDetail by remember { mutableStateOf<String?>(null) }
        SectionTitle("Screen time per day")
        Hint("Tap a day to see its apps.", Modifier.padding(start = 4.dp, bottom = 8.dp))
        ChartCard {
            BarChart(
                selected = selDay, onSelect = { selDay = it },
                values = shown.map { it.total.toFloat() },
                labels = shown.map { d -> LocalDate.parse(d.day).let { if (range == 7) it.dayOfWeek.name.take(2).lowercase().replaceFirstChar(Char::uppercase) else "${it.dayOfMonth}" } },
                labelEvery = if (range == 7) 1 else 5,
                average = if (past.isEmpty()) null else avg.toFloat(),
                describe = { i -> "${LocalDate.parse(shown[i].day).format(WEEKDAY)} · ${hm(shown[i].total)}" },
            )
        }

        // that day's apps
        val day = shown[selDay.coerceIn(0, shown.lastIndex)]
        val dayApps = day.apps.entries.filter { it.value >= 30_000 }.sortedByDescending { it.value }
        SectionTitle("Apps on " + LocalDate.parse(day.day).let { if (it == LocalDate.now()) "today" else it.format(WEEKDAY) })
        AppTimeList(dayApps.map { it.key to it.value }, day.total.coerceAtLeast(1), empty = "No app used for more than 30 seconds.") { appDetail = it }

        SectionTitle("Unlocks per day")
        ChartCard {
            BarChart(
                values = shown.map { it.unlocks.toFloat() },
                labels = shown.map { d -> LocalDate.parse(d.day).let { if (range == 7) it.dayOfWeek.name.take(2).lowercase().replaceFirstChar(Char::uppercase) else "${it.dayOfMonth}" } },
                labelEvery = if (range == 7) 1 else 5,
                average = null,
                describe = { i -> "${LocalDate.parse(shown[i].day).format(WEEKDAY)} · ${plural(shown[i].unlocks, "unlock")}" },
            )
        }

        SectionTitle("Time of day")
        val activeDays = shown.count { it.total > 0 }.coerceAtLeast(1)
        val hours = (0 until 24).map { h -> shown.sumOf { it.hours.getOrElse(h) { 0L } } / activeDays }
        ChartCard {
            BarChart(
                values = hours.map { it.toFloat() },
                labels = (0 until 24).map { "$it" },
                labelEvery = 6,
                average = null,
                initial = java.time.LocalTime.now().hour,
                describe = { i -> "%02d:00–%02d:00 · %s on an average day".format(i, (i + 1) % 24, hm(hours[i])) },
            )
        }
        val evening = hours.drop(17).sum()
        if (hours.sum() > 0) Hint("${hm(evening)} of an average day happens after 17:00 (${evening * 100 / hours.sum()}%).", Modifier.padding(top = 8.dp, start = 4.dp))

        SectionTitle("Top apps")
        val perApp = HashMap<String, Long>()
        shown.forEach { d -> d.apps.forEach { (k, v) -> perApp.merge(k, v, Long::plus) } }
        val top = perApp.entries.sortedByDescending { it.value }.take(8)
        val max = top.firstOrNull()?.value ?: 1L
        Hint("Tap an app for its days.", Modifier.padding(start = 4.dp, bottom = 8.dp))
        AppTimeList(top.map { it.key to it.value / shown.size }, max / shown.size, suffix = "/day", empty = "Nothing yet.") { appDetail = it }
        appDetail?.let { pkg -> AppDaysSheet(pkg, shown, range) { appDetail = null } }
        Hint("History is kept on this phone only. Android keeps detail for about a week; Hafen archives every day from then on.", Modifier.padding(top = 12.dp, start = 4.dp))
    }
}

@Composable
private fun Tile(title: String, value: String, sub: String?, modifier: Modifier) =
    Surface(modifier, shape = MaterialTheme.shapes.large, color = C.Card) {
        Column(Modifier.padding(14.dp)) {
            Text(title, color = C.Muted, style = MaterialTheme.typography.labelMedium)
            Text(value, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 4.dp))
            if (sub != null) Text(sub, color = C.Muted, style = MaterialTheme.typography.bodySmall)
        }
    }

@Composable
private fun ChartCard(content: @Composable () -> Unit) =
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = C.Card) { Box(Modifier.padding(16.dp)) { content() } }

/**
 * Single-series bar chart. Tap a bar to read its value (shown above the plot);
 * the last bar is selected at first. Optional dashed average line.
 */
@Composable
private fun BarChart(
    values: List<Float>, labels: List<String>, labelEvery: Int, average: Float?, initial: Int = values.lastIndex,
    selected: Int? = null, onSelect: ((Int) -> Unit)? = null, describe: (Int) -> String,
) {
    var own by remember(values.size) { mutableIntStateOf(initial.coerceIn(0, values.lastIndex)) }
    val sel = (selected ?: own).coerceIn(0, values.lastIndex)
    fun pick(i: Int) { own = i; onSelect?.invoke(i) }
    val max = maxOf(values.maxOrNull() ?: 0f, average ?: 0f).takeIf { it > 0f } ?: 1f
    Column {
        Text(describe(sel), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = 10.dp))
        Canvas(
            Modifier.fillMaxWidth().height(120.dp).pointerInput(values.size) {
                detectTapGestures { o -> pick((o.x / (size.width / values.size)).toInt().coerceIn(0, values.lastIndex)) }
            },
        ) {
            val slot = size.width / values.size
            val gap = 2.dp.toPx().coerceAtLeast(slot * 0.28f)
            val r = 4.dp.toPx()
            values.forEachIndexed { i, v ->
                val h = (v / max) * size.height
                if (h <= 0f) return@forEachIndexed
                val x = i * slot + gap / 2
                val w = slot - gap
                val c = if (i == sel) C.Accent else C.Accent.copy(alpha = 0.4f)
                // rounded data end, square at the baseline
                drawRoundRect(c, Offset(x, size.height - h), Size(w, h), CornerRadius(minOf(r, w / 2)))
                if (h > r) drawRect(c, Offset(x, size.height - minOf(h, r)), Size(w, minOf(h, r)))
            }
            drawLine(C.Line, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
            average?.let {
                val y = size.height - (it / max) * size.height
                drawLine(C.Muted, Offset(0f, y), Offset(size.width, y), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            labels.forEachIndexed { i, l ->
                Text(if (i % labelEvery == 0) l else "", Modifier.weight(1f), color = if (i == sel) C.Text else C.Muted,
                    style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, maxLines = 1)
            }
        }
        if (average != null) Text("- - -  average", color = C.Muted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 6.dp))
    }
}


/** Apps with time and a share bar; tap one for its history. */
@Composable
private fun AppTimeList(rows: List<Pair<String, Long>>, max: Long, suffix: String = "", empty: String, onOpen: (String) -> Unit) {
    val ctx = LocalContext.current
    Group {
        if (rows.isEmpty()) Text(empty, color = C.Muted, modifier = Modifier.padding(16.dp))
        rows.forEachIndexed { i, (pkg, ms) ->
            val label = remember(pkg) { Apps.label(ctx, pkg) }
            Column(Modifier.fillMaxWidth().clickable { onOpen(pkg) }.padding(horizontal = 16.dp, vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(pkg, label, 32.dp)
                    Text(label, Modifier.weight(1f).padding(start = 12.dp), maxLines = 1)
                    Text(hm(ms) + suffix, color = C.Muted, style = MaterialTheme.typography.bodyMedium)
                }
                Box(Modifier.padding(start = 44.dp, top = 6.dp).fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(C.Card3)) {
                    Box(Modifier.fillMaxWidth((ms.toFloat() / max.coerceAtLeast(1)).coerceIn(0f, 1f)).height(4.dp).clip(RoundedCornerShape(2.dp)).background(C.Accent))
                }
            }
            if (i < rows.lastIndex) Divider()
        }
    }
}

/** One app over the chosen range: time per day. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun AppDaysSheet(pkg: String, days: List<DayStats>, range: Int, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val label = remember(pkg) { Apps.label(ctx, pkg) }
    val per = days.map { it.apps[pkg] ?: 0L }
    val used = per.count { it > 0 }
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss, containerColor = C.Card,
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(pkg, label, 40.dp)
                Column(Modifier.padding(start = 14.dp)) {
                    Text(label, style = MaterialTheme.typography.titleLarge)
                    Text("${hm(per.sum())} in $range days · ${hm(per.sum() / range)}/day · used on ${plural(used, "day")}", color = C.Muted, style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(Modifier.height(20.dp))
            BarChart(
                values = per.map { it.toFloat() },
                labels = days.map { d -> LocalDate.parse(d.day).let { if (range == 7) it.dayOfWeek.name.take(2).lowercase().replaceFirstChar(Char::uppercase) else "${it.dayOfMonth}" } },
                labelEvery = if (range == 7) 1 else 5,
                average = if (used > 0) (per.sum() / range).toFloat() else null,
                describe = { i -> "${LocalDate.parse(days[i].day).format(WEEKDAY)} · ${hm(per[i])}" },
            )
        }
    }
}
