package io.github.infinifar.rustclipsync

import android.app.Application

/** One storage owner per process, including Activity recreation while an old close is in flight. */
class SyncApplication : Application() {
    internal val session by lazy { SessionController(this) }
}
