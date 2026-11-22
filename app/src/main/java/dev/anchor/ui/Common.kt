@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package dev.anchor.ui

import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import dev.anchor.enforce.Apps
import dev.anchor.policy.Schedule
import dev.anchor.policy.TimeWindow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// ---------------------------------------------------------------- theme

object C {
    val Bg = Color(0xFF0C0C0E)
    val Card = Color(0xFF161619)
    val Card2 = Color(0xFF202024)
    val Card3 = Color(0xFF2A2A2F)
    val Line = Color(0xFF26262B)
    val Muted = Color(0xFF8D8D96)
    val Text = Color(0xFFF2F2F4)
    val Accent = Color(0xFFF0B96B)
    val OnAccent = Color(0xFF241703)
    val AccentDim = Color(0xFF3B2D18)
    val Ok = Color(0xFF7FD39B)
    val Danger = Color(0xFFFF8A80)
}

private val AnchorType = Typography().let { t ->
    t.copy(
        displaySmall = t.displaySmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
        headlineLarge = t.headlineLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
        headlineMedium = t.headlineMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp),
        headlineSmall = t.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    )
}

@Composable
fun AnchorTheme(content: @Composable () -> Unit) = MaterialTheme(
    colorScheme = darkColorScheme(
        primary = C.Accent, onPrimary = C.OnAccent, primaryContainer = C.AccentDim, onPrimaryContainer = Color(0xFFFFDDB0),
        secondary = C.Text, onSecondary = Color.Black, secondaryContainer = C.Card3, onSecondaryContainer = C.Text,
        tertiary = C.Ok, background = C.Bg, onBackground = C.Text, surface = C.Bg, onSurface = C.Text,
        surfaceVariant = C.Card2, onSurfaceVariant = C.Muted,
        surfaceContainerLowest = C.Bg, surfaceContainerLow = C.Card, surfaceContainer = C.Card,
        surfaceContainerHigh = C.Card2, surfaceContainerHighest = C.Card3,
        outline = C.Muted, outlineVariant = C.Line, error = C.Danger, onError = Color.Black,
    ),
    typography = AnchorType,
    shapes = Shapes(
        extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(16.dp),
        large = RoundedCornerShape(22.dp), extraLarge = RoundedCornerShape(28.dp),
    ),
) {
    // Surface provides the content color; without it Text defaults to black on black.
    Surface(Modifier.fillMaxSize(), color = C.Bg, contentColor = C.Text) { content() }
}

// ---------------------------------------------------------------- formatting

private val zone get() = ZoneId.systemDefault()
private val HM = DateTimeFormatter.ofPattern("HH:mm")
private val DAY_HM = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm")

fun fmtDate(t: LocalDateTime): String {
    val today = LocalDate.now()
    return when (t.toLocalDate()) {
        today -> "today ${t.format(HM)}"
        today.plusDays(1) -> "tomorrow ${t.format(HM)}"
        else -> t.format(DAY_HM)
    }
}

fun fmtDate(ms: Long): String = fmtDate(LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), zone))

/** Kept for older call sites. */
internal fun fmtWhen(ms: Long) = fmtDate(ms)

fun fmtLeft(ms: Long): String {
    val m = (ms / 60_000).coerceAtLeast(0)
    return when {
        m >= 2 * 1440 -> "${m / 1440}d ${m % 1440 / 60}h"
        m >= 60 -> "${m / 60}h ${m % 60}m"
        else -> "${m}m"
    }
}

// ---------------------------------------------------------------- layout

/** A screen: big title, optional back arrow and subtitle, scrolling body. */
@Composable
fun Screen(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    trailing: @Composable () -> Unit = {},
    body: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        if (onBack != null) {
            IconButton(onClick = onBack, modifier = Modifier.padding(top = 4.dp).padding(start = 0.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
            }
        } else Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            trailing()
        }
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = C.Muted, modifier = Modifier.padding(top = 6.dp))
        Spacer(Modifier.height(8.dp))
        body()
        Spacer(Modifier.height(120.dp))
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) = Text(
    text, style = MaterialTheme.typography.titleSmall, color = C.Muted,
    modifier = modifier.padding(top = 28.dp, bottom = 10.dp, start = 4.dp),
)

@Composable
fun Hint(text: String, modifier: Modifier = Modifier) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = C.Muted, modifier = modifier)

/** Rounded card holding rows; rows are separated by thin lines. */
@Composable
fun Group(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) =
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = C.Card) { Column(content = content) }

@Composable
fun Divider() = HorizontalDivider(color = C.Line, thickness = 1.dp, modifier = Modifier.padding(start = 16.dp))

@Composable
fun ToggleRow(title: String, detail: String? = null, checked: Boolean, warn: Boolean = false, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = if (warn) C.Danger else C.Text)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = C.Muted, modifier = Modifier.padding(top = 2.dp))
        }
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = C.Accent, checkedThumbColor = C.OnAccent, uncheckedTrackColor = C.Card3, uncheckedBorderColor = C.Card3, uncheckedThumbColor = C.Muted),
        )
    }
}

@Composable
fun NavRow(title: String, value: String? = null, valueColor: Color = C.Muted, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 16.dp, end = 8.dp, top = 16.dp, bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        if (value != null) Text(value, style = MaterialTheme.typography.bodyMedium, color = valueColor, maxLines = 1, modifier = Modifier.padding(start = 12.dp))
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = C.Muted)
    }
}

/** − value + ; for small numbers. */
@Composable
fun Stepper(title: String, detail: String? = null, value: Int, step: Int, range: IntRange, fmt: (Int) -> String = { "$it" }, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = C.Muted)
        }
        RoundButton("−", enabled = value > range.first) { onChange((value - step).coerceIn(range)) }
        Text(fmt(value), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.widthIn(min = 64.dp))
        RoundButton("+", enabled = value < range.last) { onChange((value + step).coerceIn(range)) }
    }
}

@Composable
private fun RoundButton(text: String, enabled: Boolean, onClick: () -> Unit) =
    Box(
        Modifier.size(40.dp).clip(CircleShape).background(if (enabled) C.Card3 else C.Card2).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text, fontSize = 20.sp, color = if (enabled) C.Text else C.Muted.copy(alpha = 0.4f)) }

/** Small rounded pill: a choice that can be selected. */
@Composable
fun Pill(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) =
    Box(
        modifier.clip(RoundedCornerShape(50)).background(if (selected) C.Accent else C.Card2).clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = MaterialTheme.typography.labelLarge, color = if (selected) C.OnAccent else C.Text) }

@Composable
fun Banner(text: String, color: Color = C.Accent, modifier: Modifier = Modifier) =
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = color.copy(alpha = 0.12f)) {
        Text(text, color = color, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
    }

/** Press and hold to confirm; for things that can't be undone. */
@Composable
fun HoldButton(text: String, enabled: Boolean, modifier: Modifier = Modifier, holdMs: Int = 1300, onDone: () -> Unit) {
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    // the gesture handler lives across recompositions: always call the latest onDone (current duration, targets)
    val done by androidx.compose.runtime.rememberUpdatedState(onDone)
    Box(
        modifier.fillMaxWidth().height(58.dp).clip(RoundedCornerShape(29.dp))
            .background(if (enabled) C.AccentDim else C.Card2)
            .semantics { onClick(text) { if (enabled) done(); enabled } }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(onPress = {
                    val job = scope.launch {
                        progress.animateTo(1f, tween(holdMs, easing = LinearEasing))
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        done()
                        progress.snapTo(0f)
                    }
                    tryAwaitRelease()
                    if (progress.value < 1f) {
                        job.cancel()
                        scope.launch { progress.animateTo(0f, tween(250)) }
                    }
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.align(Alignment.CenterStart).fillMaxHeight().fillMaxWidth(progress.value).background(C.Accent))
        Text(
            text, style = MaterialTheme.typography.titleMedium,
            color = when { !enabled -> C.Muted; progress.value > 0.5f -> C.OnAccent; else -> Color(0xFFFFDDB0) },
        )
    }
}

// ---------------------------------------------------------------- app icons

object AppCache {
    private val icons = HashMap<String, ImageBitmap?>()
    private var list: List<Apps.Info>? = null
    private var listAt = 0L

    fun peek(pkg: String): ImageBitmap? = synchronized(icons) { icons[pkg] }

    fun icon(ctx: Context, pkg: String): ImageBitmap? {
        synchronized(icons) { if (icons.containsKey(pkg)) return icons[pkg] }
        val bmp = runCatching { ctx.packageManager.getApplicationIcon(pkg).toBitmap(144, 144).asImageBitmap() }.getOrNull()
        synchronized(icons) { icons[pkg] = bmp }
        return bmp
    }

    @Synchronized
    fun apps(ctx: Context): List<Apps.Info> {
        val now = System.currentTimeMillis()
        list?.let { if (now - listAt < 60_000) return it }
        return Apps.launchable(ctx).filter { it.pkg != ctx.packageName }.also { list = it; listAt = now }
    }

    fun installed(ctx: Context, pkg: String) = runCatching { ctx.packageManager.getApplicationInfo(pkg, 0); true }.getOrDefault(false)
}

@Composable
fun AppIcon(pkg: String, label: String = "", size: Dp = 40.dp) {
    val ctx = LocalContext.current
    // remember(pkg): a reused list slot must not keep the previous app's icon
    var bmp by remember(pkg) { mutableStateOf(AppCache.peek(pkg)) }
    androidx.compose.runtime.LaunchedEffect(pkg) {
        if (bmp == null) bmp = withContext(Dispatchers.IO) { AppCache.icon(ctx, pkg) }
    }
    val b = bmp
    if (b != null) Image(b, null, Modifier.size(size)) else LetterIcon(label.ifEmpty { pkg.substringAfterLast('.') }, size)
}

@Composable
fun LetterIcon(text: String, size: Dp = 40.dp, glyph: String? = null) =
    Box(Modifier.size(size).clip(CircleShape).background(C.Card3), contentAlignment = Alignment.Center) {
        Text(glyph ?: text.take(1).uppercase(), fontSize = (size.value * 0.42f).sp, color = C.Text, fontWeight = FontWeight.SemiBold)
    }

@Composable
fun AppRow(pkg: String, label: String, sub: String? = null, subColor: Color = C.Muted, onClick: (() -> Unit)? = null, trailing: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().let { if (onClick != null) it.clickable(onClick = onClick) else it }.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(pkg, label, 36.dp)
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = subColor, maxLines = 2)
        }
        trailing()
    }
}

/** A chosen set of apps, with remove buttons and a "choose" entry that opens the picker. */
@Composable
fun AppListEditor(selected: List<String>, pickerTitle: String, exclude: Set<String> = emptySet(), emptyText: String = "None yet", onChange: (List<String>) -> Unit) {
    val ctx = LocalContext.current
    var picking by remember { mutableStateOf(false) }
    Group {
        if (selected.isEmpty()) Text(emptyText, color = C.Muted, modifier = Modifier.padding(16.dp))
        selected.forEach { pkg ->
            val label = remember(pkg) { Apps.label(ctx, pkg) }
            AppRow(pkg, label) {
                IconButton(onClick = { onChange(selected - pkg) }) { Icon(Icons.Default.Close, "Remove $label", tint = C.Muted) }
            }
            Divider()
        }
        Row(Modifier.fillMaxWidth().clickable { picking = true }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Add, null, tint = C.Accent)
            Text("Choose apps", color = C.Accent, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 12.dp))
        }
    }
    if (picking) AppPickerSheet(pickerTitle, selected, exclude, onDismiss = { picking = false }) { onChange(it); picking = false }
}

/** Searchable list of installed apps. With [single], a tap picks one app and closes. */
@Composable
fun AppPickerSheet(
    title: String, initial: Collection<String>, exclude: Set<String> = emptySet(), single: Boolean = false,
    onDismiss: () -> Unit, onDone: (List<String>) -> Unit,
) {
    val ctx = LocalContext.current
    val apps by produceState<List<Apps.Info>?>(null) {
        value = withContext(Dispatchers.IO) { AppCache.apps(ctx).filter { it.pkg !in exclude } }
    }
    var sel by remember { mutableStateOf(initial.toSet()) }
    var q by remember { mutableStateOf("") }
    fun done() = onDone(initial.filter { it in sel } + sel.filter { it !in initial })

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = C.Card) {
        Column(Modifier.fillMaxHeight(0.92f)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                if (!single) Button(onClick = ::done) { Text(if (sel.isEmpty()) "Done" else "Done · ${sel.size}") }
            }
            OutlinedTextField(
                q, { q = it }, Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                placeholder = { Text("Search") }, leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true,
                shape = RoundedCornerShape(50),
            )
            val list = apps
            if (list == null) {
                Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = C.Accent) }
            } else {
                val shown = list.filter { q.isBlank() || it.label.contains(q, true) || it.pkg.contains(q, true) }
                LazyColumn(Modifier.weight(1f)) {
                    items(shown, key = { it.pkg }) { a ->
                        AppRow(a.pkg, a.label, onClick = {
                            if (single) onDone(listOf(a.pkg)) else sel = if (a.pkg in sel) sel - a.pkg else sel + a.pkg
                        }) {
                            if (!single) Checkbox(
                                checked = a.pkg in sel, onCheckedChange = null,
                                colors = CheckboxDefaults.colors(checkedColor = C.Accent, checkmarkColor = C.OnAccent, uncheckedColor = C.Muted),
                            )
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- time windows

private val DAY_LETTERS = listOf("M", "T", "W", "T", "F", "S", "S")
private val DAY_NAMES = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")

@Composable
fun DayPicker(days: Set<Int>, onChange: (Set<Int>) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        DAY_LETTERS.forEachIndexed { i, d ->
            val day = i + 1
            val on = day in days
            Box(
                Modifier.size(34.dp).clip(CircleShape).background(if (on) C.Accent else C.Card3)
                    .clickable(onClickLabel = "${if (on) "Remove" else "Add"} ${DAY_NAMES[i]}") { onChange(if (on) days - day else days + day) },
                contentAlignment = Alignment.Center,
            ) { Text(d, color = if (on) C.OnAccent else C.Muted, style = MaterialTheme.typography.labelLarge) }
        }
    }
}

@Composable
fun TimeButton(minutes: Int, isEnd: Boolean, onChange: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val label = if (isEnd && minutes % 1440 == 0) "24:00" else Schedule.fmt(minutes % 1440)
    Box(Modifier.clip(MaterialTheme.shapes.small).background(C.Card3).clickable { open = true }.padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(label, style = MaterialTheme.typography.titleLarge)
    }
    if (open) {
        val st = rememberTimePickerState(minutes / 60 % 24, minutes % 60, is24Hour = true)
        AlertDialog(
            onDismissRequest = { open = false }, containerColor = C.Card,
            confirmButton = { TextButton(onClick = { onChange(st.hour * 60 + st.minute); open = false }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } },
            text = {
                TimePicker(
                    st, colors = TimePickerDefaults.colors(
                        clockDialColor = C.Card2, selectorColor = C.Accent, timeSelectorSelectedContainerColor = C.AccentDim,
                        timeSelectorUnselectedContainerColor = C.Card2, timeSelectorSelectedContentColor = C.Accent,
                        clockDialSelectedContentColor = C.OnAccent,
                    ),
                )
            },
        )
    }
}

private fun lengthLabel(w: TimeWindow): String {
    val len = if (w.end % 1440 > w.start) w.end % 1440 - w.start else w.end % 1440 + 1440 - w.start
    return if (len % 60 == 0) "${len / 60}h" else "${len / 60}h ${len % 60}m"
}

@Composable
fun WindowsEditor(windows: List<TimeWindow>, addLabel: String, newWindow: TimeWindow = TimeWindow(), max: Int = Int.MAX_VALUE, onChange: (List<TimeWindow>) -> Unit) {
    windows.forEachIndexed { idx, w ->
        fun set(nw: TimeWindow) = onChange(windows.toMutableList().also { it[idx] = nw })
        Surface(Modifier.fillMaxWidth().padding(bottom = 10.dp), shape = MaterialTheme.shapes.large, color = C.Card) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { DayPicker(w.days) { set(w.copy(days = it)) } }
                    IconButton(onClick = { onChange(windows.filterIndexed { i, _ -> i != idx }) }) { Icon(Icons.Default.Close, "Remove time", tint = C.Muted) }
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TimeButton(w.start, isEnd = false) { set(w.copy(start = it)) }
                    Text("to", color = C.Muted, modifier = Modifier.padding(horizontal = 12.dp))
                    TimeButton(w.end, isEnd = true) { set(w.copy(end = it)) }
                    Spacer(Modifier.weight(1f))
                    Text(lengthLabel(w) + if (w.end % 1440 <= w.start && w.end % 1440 != 0) "\nnext day" else "", color = C.Muted, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.End)
                }
                if (w.days.isEmpty()) Text("Pick at least one day.", color = C.Danger, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
    if (windows.size < max) Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).background(C.Card).clickable { onChange(windows + newWindow) }.padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Add, null, tint = C.Accent)
        Text(addLabel, color = C.Accent, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 12.dp))
    }
}

@Composable
fun FlowPills(content: @Composable () -> Unit) =
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }

@Composable
fun KeyValue(k: String, v: String, vColor: Color = C.Text) = Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
    Text(k, Modifier.weight(1f), color = C.Muted)
    Text(v, color = vColor, style = TextStyle(fontWeight = FontWeight.Medium))
}

/** Asks for the partner code. [submit] returns the result; the dialog closes on success. */
@Composable
fun CodeDialog(title: String, text: String, onDismiss: () -> Unit, submit: (String) -> dev.anchor.policy.PolicyStore.CodeResult) {
    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = C.Card,
        title = { Text(title) },
        text = {
            Column {
                Text(text, color = C.Muted, style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    code, { code = it; error = null }, Modifier.fillMaxWidth().padding(top = 16.dp),
                    label = { Text("Partner code") }, singleLine = true, isError = error != null,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                )
                error?.let { Text(it, color = C.Danger, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp)) }
            }
        },
        confirmButton = {
            TextButton(enabled = code.isNotBlank(), onClick = {
                when (val r = submit(code)) {
                    dev.anchor.policy.PolicyStore.CodeResult.Ok -> onDismiss()
                    is dev.anchor.policy.PolicyStore.CodeResult.Wrong -> { error = "Wrong code. ${r.triesLeft} tries left."; code = "" }
                    is dev.anchor.policy.PolicyStore.CodeResult.TooMany -> { error = "Too many wrong tries. Next try ${fmtDate(r.until)}."; code = "" }
                }
            }) { Text("Confirm") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
