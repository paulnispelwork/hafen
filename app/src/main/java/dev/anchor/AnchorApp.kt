package dev.anchor

import android.app.Application
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import dev.anchor.enforce.Alarms
import dev.anchor.enforce.Enforcer
import dev.anchor.enforce.Usage
import dev.anchor.policy.PolicyStore

class AnchorApp : Application() {
    override fun onCreate() {
        super.onCreate()
        PolicyStore.init(this)
        Usage.init(this)
        dev.anchor.enforce.Inbox.init(this)
        Alarms.scheduleRepeating(this)

        // If someone switches off the watcher or grayscale, switch it straight back on.
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = Enforcer.enforceAsync(this@AnchorApp)
        }
        listOf(
            Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
            Settings.Secure.getUriFor("accessibility_display_daltonizer_enabled"),
        ).forEach { contentResolver.registerContentObserver(it, false, observer) }

        Enforcer.enforceAsync(this)
    }
}
