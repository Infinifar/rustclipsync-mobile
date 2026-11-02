package io.github.infinifar.rustclipsync

import android.content.Context
import androidx.annotation.StringRes
import io.github.infinifar.rustclipsync.bindings.SyncOutcome

/** Store message identity, so a locale change also translates an existing status. */
internal data class UiMessage(@param:StringRes val resource: Int, val arguments: List<Any> = emptyList()) {
    fun resolve(context: Context): String = AppLanguages.context(context).getString(resource, *arguments.map { if (it is UiMessage) it.resolve(context) else it }.toTypedArray())

    companion object {
        fun outcome(value: SyncOutcome) = UiMessage(when (value) {
            SyncOutcome.STORED -> R.string.outcome_stored
            SyncOutcome.UPLOADED -> R.string.outcome_uploaded
            SyncOutcome.CURRENT -> R.string.outcome_current
            SyncOutcome.DOWNLOADED -> R.string.outcome_downloaded
            SyncOutcome.SKIPPED -> R.string.outcome_skipped
            SyncOutcome.FILTERED -> R.string.outcome_filtered
            SyncOutcome.PAUSED -> R.string.outcome_paused
            SyncOutcome.UNSUPPORTED -> R.string.outcome_unsupported
            SyncOutcome.INVALID -> R.string.outcome_invalid
            SyncOutcome.CAPACITY -> R.string.outcome_capacity
        })
    }
}
