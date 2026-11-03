package io.github.infinifar.rustclipsync

internal enum class ShizukuAccessState(val message: Int) {
    UNAVAILABLE(R.string.shizuku_unavailable),
    SHELL_REQUIRED(R.string.shizuku_shell_required),
    NOT_AUTHORIZED(R.string.shizuku_not_authorized),
    REQUESTING(R.string.shizuku_requesting),
    DENIED(R.string.shizuku_denied),
    AUTHORIZED(R.string.shizuku_authorized),
}

/** Permission lifecycle is independent of whether a clipboard UserService is needed. */
internal class ShizukuAccess(
    private val probe: () -> ShizukuAccessState,
    private val request: (Int) -> Unit,
    private val rationale: () -> Boolean,
    private val changed: (ShizukuAccessState) -> Unit,
) {
    var state = ShizukuAccessState.UNAVAILABLE; private set
    private var requesting = false
    private var deniedByCallback = false

    fun refresh() {
        val current = try { probe() } catch (_: RuntimeException) { ShizukuAccessState.UNAVAILABLE }
        publish(when {
            current == ShizukuAccessState.AUTHORIZED && deniedByCallback -> ShizukuAccessState.DENIED
            current != ShizukuAccessState.NOT_AUTHORIZED -> current
            requesting -> ShizukuAccessState.REQUESTING
            state == ShizukuAccessState.DENIED -> ShizukuAccessState.DENIED
            else -> current
        })
    }

    fun requestPermission() {
        refresh()
        if (state != ShizukuAccessState.NOT_AUTHORIZED && state != ShizukuAccessState.DENIED) return
        try {
            if (rationale()) { publish(ShizukuAccessState.DENIED); return }
            requesting = true
            publish(ShizukuAccessState.REQUESTING)
            request(REQUEST_CODE)
        } catch (_: RuntimeException) { requesting = false; publish(ShizukuAccessState.UNAVAILABLE) }
    }

    fun result(code: Int, granted: Boolean): Boolean {
        if (code != REQUEST_CODE) return false
        requesting = false
        deniedByCallback = !granted
        refresh() // Grant callbacks do not bypass live Binder, server UID, version or permission checks.
        if (!granted && state == ShizukuAccessState.NOT_AUTHORIZED) publish(ShizukuAccessState.DENIED)
        return true
    }

    fun binderDied() { requesting = false; deniedByCallback = false; publish(ShizukuAccessState.UNAVAILABLE) }
    fun binderReceived() { requesting = false; deniedByCallback = false; refresh() }

    private fun publish(value: ShizukuAccessState) { state = value; changed(value) }
    companion object { const val REQUEST_CODE = 41 }
}
