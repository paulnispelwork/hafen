package dev.anchor.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.anchor.enforce.Apps
import dev.anchor.enforce.Enforcer
import dev.anchor.enforce.Usage
import dev.anchor.policy.PolicyStore
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** Evening nudge: before an app opens, suggest a book instead. */
class NudgeActivity : ComponentActivity() {
    companion object { const val EXTRA_PKG = "pkg" }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(SystemBarStyle.dark(C.Bg.toArgb()), SystemBarStyle.dark(C.Bg.toArgb()))
        super.onCreate(savedInstanceState)
        val pkg = intent.getStringExtra(EXTRA_PKG) ?: run { finish(); return }
        onBackPressedDispatcher.addCallback(this) { putAway(lock = false) }
        setContent { AnchorTheme { NudgeScreen(pkg) } }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); recreate() }

    private fun goHome() = startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    /** The good choice: home, and (if possible) screen off. */
    fun putAway(lock: Boolean) {
        Usage.bump("nudgeHeeded")
        goHome()
        if (lock) runCatching { Enforcer.dpm(this).lockNow() }
        finish()
    }

    fun openInstead(pkg: String) { Usage.bump("nudgeHeeded"); Apps.launch(this, pkg); finish() }

    fun openAnyway(pkg: String) {
        Usage.nudgePass(pkg, PolicyStore.policy.value.nudgePassMinutes)
        Apps.launch(this, pkg)
        finish()
    }

    @Composable
    private fun NudgeScreen(pkg: String) {
        val p by PolicyStore.policy.collectAsState()
        val label = remember { Apps.label(this, pkg) }
        val readLabel = remember { p.nudgeApp?.takeIf { AppCache.installed(this, it) }?.let { Apps.label(this, it) } }
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(32.dp), verticalArrangement = Arrangement.Center) {
            Text("📖", fontSize = 56.sp)
            Spacer(Modifier.height(20.dp))
            Text("It's ${LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))}.", fontSize = 18.sp, color = C.Muted)
            Text(p.nudgeText, fontSize = 34.sp, fontWeight = FontWeight.Light, lineHeight = 40.sp, modifier = Modifier.padding(top = 6.dp))
            Spacer(Modifier.height(40.dp))
            Button(
                onClick = { putAway(lock = true) }, modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = C.Accent, contentColor = C.OnAccent),
            ) { Text("Okay, phone away", fontSize = 17.sp) }
            if (readLabel != null) {
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = { openInstead(p.nudgeApp!!) }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Text("Open $readLabel instead", color = C.Text)
                }
            }
            Spacer(Modifier.height(18.dp))
            TextButton(onClick = { openAnyway(pkg) }, modifier = Modifier.fillMaxWidth()) {
                Text("Open $label anyway", color = C.Muted)
            }
        }
    }
}
