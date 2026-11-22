package dev.anchor.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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

/** Stands between you and a limited app: wait, say why, count opens. */
class GateActivity : ComponentActivity() {
    companion object { const val EXTRA_PKG = "pkg" }

    override fun onCreate(savedInstanceState: Bundle?) {
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
    val rule = PolicyStore.policy.value.apps[pkg] ?: AppRule()
    val label = remember { Apps.label(ctx, pkg) }
    var left by remember { mutableIntStateOf(rule.waitSeconds) }
    var reason by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { while (left > 0) { delay(1000); left-- } }

    val opens = Usage.opens(pkg)
    val usedMin = Usage.seconds(pkg) / 60
    val outOfOpens = rule.maxOpensPerDay > 0 && opens >= rule.maxOpensPerDay
    val reasonOk = !rule.requireReason || reason.trim().length >= 12

    Column(
        Modifier.fillMaxSize().background(Color.Black).padding(32.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.Start,
    ) {
        Text(label.lowercase(), fontSize = 40.sp, fontWeight = FontWeight.Light)
        Spacer(Modifier.height(12.dp))
        Text(
            buildString {
                append("opened $opens"); if (rule.maxOpensPerDay > 0) append(" / ${rule.maxOpensPerDay}"); append(" today")
                append(" · $usedMin"); if (rule.dailyBudgetMin > 0) append(" / ${rule.dailyBudgetMin}"); append(" min used")
            },
            color = MaterialTheme.colorScheme.outline,
        )
        Spacer(Modifier.height(32.dp))
        Text("What are you about to do in there?", fontSize = 20.sp)
        if (rule.requireReason) {
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                reason, { reason = it }, Modifier.fillMaxWidth(), minLines = 2,
                label = { Text("Say it in a full sentence") },
            )
        }
        Spacer(Modifier.height(32.dp))
        Button(onClick = { onOpen(reason) }, enabled = left == 0 && reasonOk && !outOfOpens, modifier = Modifier.fillMaxWidth()) {
            Text(when {
                outOfOpens -> "No opens left today"
                left > 0 -> "Wait ${left}s"
                !reasonOk -> "Write a reason first"
                else -> "Open for ${rule.sessionMinutes} min"
            })
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onLeave, modifier = Modifier.fillMaxWidth()) { Text("Never mind") }
    }
}
