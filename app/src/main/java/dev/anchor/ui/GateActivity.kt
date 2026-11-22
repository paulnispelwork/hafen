package dev.anchor.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.anchor.enforce.Apps
import dev.anchor.enforce.Enforcer
import dev.anchor.enforce.Usage
import dev.anchor.policy.AppRule
import dev.anchor.policy.PolicyStore
import kotlinx.coroutines.delay

/** Stands between you and a limited app: a short wait, a reason, a count of today's opens. */
class GateActivity : ComponentActivity() {
    companion object { const val EXTRA_PKG = "pkg" }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(SystemBarStyle.dark(C.Bg.toArgb()), SystemBarStyle.dark(C.Bg.toArgb()))
        super.onCreate(savedInstanceState)
        val pkg = intent.getStringExtra(EXTRA_PKG) ?: run { finish(); return }
        onBackPressedDispatcher.addCallback(this) { goHome() }
        setContent { AnchorTheme { GateScreen(pkg, ::goHome) { reason -> open(pkg, reason) } } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        recreate()
    }

    private fun goHome() {
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    private fun open(pkg: String, reason: String) {
        val rule = PolicyStore.policy.value.apps[pkg] ?: AppRule()
        Usage.addOpen(pkg)
        Usage.startSession(pkg, rule.sessionMinutes)
        Usage.logReason(pkg, reason)
        Enforcer.enforceAsync(this)
        Apps.launch(this, pkg)
        finish()
    }
}

@Composable
private fun GateScreen(pkg: String, onLeave: () -> Unit, onOpen: (String) -> Unit) {
    val ctx = LocalContext.current
    val rule = remember { PolicyStore.policy.value.apps[pkg] ?: AppRule() }
    val label = remember { Apps.label(ctx, pkg) }
    var left by remember { mutableIntStateOf(rule.waitSeconds) }
    var reason by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { while (left > 0) { delay(1000); left-- } }

    val opens = Usage.opens(pkg)
    val usedMin = Usage.seconds(pkg) / 60
    val outOfOpens = rule.maxOpensPerDay > 0 && opens >= rule.maxOpensPerDay
    val reasonOk = !rule.requireReason || reason.trim().length >= 12

    Column(
        Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(pkg, label, 44.dp)
            Text(label, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(start = 14.dp))
        }
        // today so far, against the limits you set
        Row(Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            GateStat("opened today", if (rule.maxOpensPerDay > 0) "$opens / ${rule.maxOpensPerDay}" else "$opens")
            GateStat("minutes today", if (rule.dailyBudgetMin > 0) "$usedMin / ${rule.dailyBudgetMin}" else "$usedMin")
        }

        Text("What are you about to do in there?", fontSize = 26.sp, fontWeight = FontWeight.Light, lineHeight = 32.sp, modifier = Modifier.padding(top = 36.dp))
        if (rule.requireReason) OutlinedTextField(
            reason, { reason = it }, Modifier.fillMaxWidth().padding(top = 14.dp), minLines = 2,
            placeholder = { Text("In a full sentence") }, shape = MaterialTheme.shapes.medium,
        )

        Spacer(Modifier.height(32.dp))
        if (left > 0 && !outOfOpens) {
            // the pause itself, made visible
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        progress = { left.toFloat() / rule.waitSeconds.coerceAtLeast(1) }, modifier = Modifier.size(44.dp),
                        color = C.Accent, trackColor = C.Card3, strokeWidth = 3.dp, strokeCap = StrokeCap.Round,
                    )
                    Text("$left", style = MaterialTheme.typography.labelLarge)
                }
                Text("Take a breath first.", color = C.Muted, modifier = Modifier.padding(start = 14.dp))
            }
            Spacer(Modifier.height(20.dp))
        }
        Button(
            onClick = { onOpen(reason) }, enabled = left == 0 && reasonOk && !outOfOpens,
            modifier = Modifier.fillMaxWidth().height(54.dp),
            colors = ButtonDefaults.buttonColors(containerColor = C.Card3, contentColor = C.Text, disabledContainerColor = C.Card2, disabledContentColor = C.Muted),
        ) {
            Text(when {
                outOfOpens -> "No opens left today"
                left > 0 -> "Open in ${left}s"
                !reasonOk -> "Write a reason first"
                else -> "Open for ${rule.sessionMinutes} min"
            })
        }
        TextButton(onClick = onLeave, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Text("Never mind", color = C.Accent, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun GateStat(label: String, value: String) = Column {
    Text(value, style = MaterialTheme.typography.titleLarge)
    Text(label, style = MaterialTheme.typography.bodySmall, color = C.Muted)
}
