package io.github.infinifar.rustclipsync

/** Admission only; protocol, sensitive policy and retries remain in core. */
internal object SyncSchedule {
    enum class Tick { NONE, FOREGROUND_RECEIVE, BACKGROUND }

    fun tick(enabled: Boolean, running: Boolean, connected: Boolean, busy: Boolean,
             foreground: Boolean, focused: Boolean, receives: Boolean): Tick = when {
        !enabled || !running || !connected || busy -> Tick.NONE
        !foreground -> Tick.BACKGROUND
        focused && receives -> Tick.FOREGROUND_RECEIVE
        else -> Tick.NONE
    }

    fun keepServiceOnTaskRemoval(enabled: Boolean, connected: Boolean) = enabled && connected
}
