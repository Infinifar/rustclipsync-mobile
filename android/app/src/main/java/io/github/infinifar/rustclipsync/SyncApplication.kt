package io.github.infinifar.rustclipsync

import android.app.Application

/** One storage owner per process, including Activity recreation while an old close is in flight. */
class SyncApplication : Application() {
    // Internal dependency injection also lets instrumentation supply isolated adapters.
    internal val privilegedClipboard by lazy { ShizukuClipboard(this) }
    internal lateinit var session: SessionController
    override fun onCreate() { super.onCreate(); session = SessionController(this) }
}
