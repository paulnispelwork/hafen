package dev.anchor.ui

import android.app.AppOpsManager
import android.app.admin.DevicePolicyManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.anchor.R
import dev.anchor.enforce.Enforcer
import dev.anchor.enforce.Stats
import dev.anchor.service.NotifyService
import dev.anchor.service.WatchService
import kotlinx.coroutines.delay

private const val GRANT_CMD = "adb shell pm grant dev.anchor android.permission.WRITE_SECURE_SETTINGS"

/** One permission Hafen needs, how to tell if it's there, and where Android lets you switch it on. */
private class Step(
    val title: String,
    val why: String,
    val required: Boolean,
    val done: (Context) -> Boolean,
    val action: String,
    val open: (Context) -> Unit,
    val visible: (Context) -> Boolean = { true },
)

/** Opens an Android settings screen on top of Hafen, so Back returns to the checklist. */
private fun Context.go(i: Intent, fallback: Intent? = null) {
    fun fix(x: Intent) = if (this is android.app.Activity) x else x.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { startActivity(fix(i)) }
        .onFailure { fallback?.let { f -> runCatching { startActivity(fix(f)) } } }
}

/** Android 13+ blocks accessibility and notification access for apps installed from a downloaded file until you allow it. */
private fun restrictedAllowed(ctx: Context): Boolean {
    if (Build.VERSION.SDK_INT < 33) return true
    val ops = ctx.getSystemService(AppOpsManager::class.java)
    return runCatching {
        ops.unsafeCheckOpNoThrow("android:access_restricted_settings", android.os.Process.myUid(), ctx.packageName) == AppOpsManager.MODE_ALLOWED
    }.getOrDefault(true)
}

private fun isHome(ctx: Context) =
    ctx.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)?.activityInfo?.packageName == ctx.packageName

private val STEPS = listOf(
    Step(
        "Allow restricted settings",
        "If the next two steps say \"Restricted setting\": Android protects them for apps that don't come from the Play Store. In App info, tap ⋮ (top right), then \"Allow restricted settings\", then come back. Not needed if the next steps just work.",
        required = true,
        done = { restrictedAllowed(it) || Enforcer.watcherEnabled(it) },
        action = "Open App info",
        open = { it.go(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${it.packageName}"))) },
        visible = { Build.VERSION.SDK_INT >= 33 && !(Enforcer.watcherEnabled(it) && Enforcer.notifyFilterConnected(it)) },
    ),
    Step(
        "Watcher",
        "Lets Hafen see which app is in front, so it can send you home from a blocked app or site. It reads nothing else and sends nothing anywhere.",
        required = true,
        done = { Enforcer.watcherEnabled(it) },
        action = "Turn on",
        open = { c ->
            c.go(
                Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
                    .putExtra(Intent.EXTRA_COMPONENT_NAME, ComponentName(c, WatchService::class.java).flattenToString()),
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
            )
        },
    ),
    Step(
        "Uninstall protection",
        "Makes Android refuse to uninstall Hafen while it's on. Hafen keeps you away from this switch while a block runs.",
        required = true,
        done = { Enforcer.isAdmin(it) || Enforcer.isOwner(it) },
        action = "Turn on",
        open = { c ->
            c.go(Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, Enforcer.admin(c))
                .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Keeps Hafen from being uninstalled while your blocks run."))
        },
    ),
    Step(
        "Notification filter",
        "Holds back notifications from apps you don't allow, and hides those of blocked apps.",
        required = false,
        done = { Enforcer.notifyFilterConnected(it) },
        action = "Allow",
        open = { c ->
            c.go(
                Intent("android.settings.NOTIFICATION_LISTENER_DETAIL_SETTINGS")
                    .putExtra("android.provider.extra.NOTIFICATION_LISTENER_COMPONENT_NAME", ComponentName(c, NotifyService::class.java).flattenToString()),
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
            )
        },
    ),
    Step(
        "Usage history",
        "For the stats: screen time per day and per app, from Android's own records. Stays on the phone.",
        required = false,
        done = { Stats.hasAccess(it) },
        action = "Allow",
        open = { c -> c.go(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:${c.packageName}")), Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) },
    ),
    Step(
        "Home screen",
        "Replace your launcher with Hafen's calm home screen: your favorites as text, everything else one swipe away. In Android's list, tap Hafen, then \"Set as default\".",
        required = false,
        done = { isHome(it) },
        action = "Make default",
        open = { c ->
            // switch Hafen's home screen on first, then ask Android to make it the default
            val p = dev.anchor.policy.PolicyStore.policy.value
            if (!p.launcherEnabled) dev.anchor.policy.PolicyStore.propose(c, p.copy(launcherEnabled = true))
            Enforcer.setLauncherComponent(c, true)
            val rm = c.getSystemService(android.app.role.RoleManager::class.java)
            val i = runCatching { rm.createRequestRoleIntent(android.app.role.RoleManager.ROLE_HOME) }.getOrNull()
            if (c is android.app.Activity && i != null) runCatching { c.startActivityForResult(i, 7) }.onFailure { c.go(Intent(Settings.ACTION_HOME_SETTINGS)) }
            else c.go(Intent(Settings.ACTION_HOME_SETTINGS))
        },
    ),
)

/** The checklist: live ticks, one button per missing permission. */
@Composable
internal fun SetupChecklist() {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(1500); tick++ } } // pick up changes when you come back from Android's settings
    val steps = remember(tick) { STEPS.filter { it.visible(ctx) }.map { it to it.done(ctx) } }
    val secure = remember(tick) { Enforcer.canWriteSecure(ctx) }
    var showGrayHelp by remember { mutableStateOf(false) }

    Group {
        steps.forEachIndexed { i, (s, ok) ->
            StepRow(s.title, s.why, ok, if (s.required) null else "optional", s.action) { s.open(ctx) }
            if (i < steps.lastIndex) Divider()
        }
        Divider()
        StepRow(
            "Grayscale and self-repair",
            "One permission Android only grants from a computer: grayscale, and the watcher switching itself back on if something turns it off.",
            secure, "optional", if (showGrayHelp) "Hide how" else "How?",
        ) { showGrayHelp = !showGrayHelp }
        if (showGrayHelp && !secure) Column(Modifier.padding(start = 52.dp, end = 16.dp, bottom = 16.dp)) {
            Hint("With a computer: connect the phone with USB debugging on, then run")
            CommandBox(GRANT_CMD)
            Hint("Without a computer: turn on Wireless debugging (Developer options) and run the same command in an on-phone adb app such as Shizuku or LADB.", Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
private fun CommandBox(cmd: String) {
    val ctx = LocalContext.current
    Surface(Modifier.fillMaxWidth().padding(vertical = 8.dp), shape = MaterialTheme.shapes.small, color = C.Card3) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(cmd, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f).padding(12.dp))
            TextButton(onClick = {
                ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("command", cmd))
                Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
            }) { Text("Copy") }
        }
    }
}

@Composable
private fun StepRow(title: String, why: String, ok: Boolean, tag: String?, action: String, onAction: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.Top) {
        Box(
            Modifier.size(24.dp).clip(CircleShape).background(if (ok) C.Ok else C.Card3),
            contentAlignment = Alignment.Center,
        ) { if (ok) Icon(Icons.Default.Check, null, tint = C.Bg, modifier = Modifier.size(16.dp)) }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                if (tag != null) Text(tag, color = C.Muted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 8.dp))
            }
            Text(why, style = MaterialTheme.typography.bodySmall, color = C.Muted, modifier = Modifier.padding(top = 2.dp))
            if (!ok) OutlinedButton(onClick = onAction, modifier = Modifier.padding(top = 10.dp)) { Text(action) }
        }
    }
}

/** First start: what Hafen is, then the checklist. */
@Composable
internal fun SetupScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(1500); tick++ } }
    val ready = remember(tick) { Enforcer.watcherEnabled(ctx) && (Enforcer.isAdmin(ctx) || Enforcer.isOwner(ctx)) }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(32.dp))
            Icon(painterResource(R.drawable.ic_anchor), null, tint = C.Accent, modifier = Modifier.size(40.dp))
            Text("Welcome to Hafen", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(top = 12.dp))
            Text(
                "Lock the apps you choose, for as long as you choose. To do that on your phone, Hafen needs a few permissions. " +
                    "Each button opens the right Android screen; come back here afterwards.",
                style = MaterialTheme.typography.bodyMedium, color = C.Muted, modifier = Modifier.padding(top = 8.dp, bottom = 20.dp),
            )
            SetupChecklist()
            Hint("Nothing leaves your phone. You can change all of this later under Settings › Setup & status.", Modifier.padding(top = 16.dp, start = 4.dp))
            Hint("Moving from another Hafen install?", Modifier.padding(top = 16.dp, start = 4.dp))
            BackupRow(showExport = false)
            Spacer(Modifier.height(24.dp))
        }
        Surface(color = C.Card) {
            Column(Modifier.fillMaxWidth().padding(20.dp)) {
                Button(
                    onClick = onDone, enabled = true, modifier = Modifier.fillMaxWidth().height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = if (ready) C.Accent else C.Card3, contentColor = if (ready) C.OnAccent else C.Text),
                ) { Text(if (ready) "Done" else "Skip for now") }
                if (!ready) Hint("Blocks only hold once the watcher and uninstall protection are on.", Modifier.padding(top = 8.dp).align(Alignment.CenterHorizontally))
            }
        }
    }
}


/** Back up to a file / restore from one (Android's file picker, nothing leaves the phone). */
@Composable
internal fun BackupRow(showExport: Boolean = true) {
    val ctx = LocalContext.current
    val export = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) runCatching {
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(dev.anchor.policy.PolicyStore.exportJson().toByteArray()) }
            Toast.makeText(ctx, "Backup saved", Toast.LENGTH_SHORT).show()
        }
    }
    val restore = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val text = runCatching { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } }.getOrNull()
            val err = if (text == null) "Couldn't read that file." else dev.anchor.policy.PolicyStore.restore(ctx, text)
            Toast.makeText(ctx, err ?: "Restored: your blocks and settings are back", Toast.LENGTH_LONG).show()
        }
    }
    Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        if (showExport) TextButton(onClick = { export.launch("hafen-backup.json") }) { Text("Back up settings") }
        TextButton(onClick = { restore.launch(arrayOf("application/json", "text/plain", "*/*")) }) { Text("Restore from backup") }
    }
}
