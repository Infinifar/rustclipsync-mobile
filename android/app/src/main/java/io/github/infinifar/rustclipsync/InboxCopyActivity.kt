package io.github.infinifar.rustclipsync

import android.app.KeyguardManager
import android.os.Bundle
import android.view.WindowManager
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/** Direct notification Activity: gain focus before copying; no service-to-Activity trampoline. */
class InboxCopyActivity : ComponentActivity() {
    private val session get() = (application as SyncApplication).session
    private var request: SessionController.CopyRequest? = null
    private var resumed = false
    private var copying = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        // This Activity is nonexported. Still require a current notification capability.
        request = if (savedInstanceState == null) session.claimNotificationCopy(intent) else null
        if (request == null || getSystemService(KeyguardManager::class.java).isDeviceLocked) { finish(); return }
        setContentView(TextView(this).apply { text = "正在显式复制加密收件箱…" })
    }
    override fun onResume() {
        super.onResume()
        if (request != null) { resumed = true; session.uiVisible(true); copyIfFocused() }
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (request != null) { session.clipboardFocus(hasFocus); copyIfFocused() }
    }
    private fun copyIfFocused() {
        val item = request ?: return
        if (!resumed || !hasWindowFocus() || copying) return
        copying = true
        lifecycleScope.launch {
            session.copyFromNotification(item)
            finish()
        }
    }
    override fun onPause() {
        if (resumed) { resumed = false; session.uiVisible(false) }
        super.onPause()
    }
}
