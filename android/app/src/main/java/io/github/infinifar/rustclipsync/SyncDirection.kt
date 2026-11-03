package io.github.infinifar.rustclipsync

/** Desktop-compatible scheduling preferences, independent from core protocol state. */
internal enum class SyncDirection(val key: String, val title: Int, val sends: Boolean, val receives: Boolean) {
    BOTH("both", R.string.direction_both, true, true),
    UPLOAD("upload", R.string.direction_upload, true, false),
    DOWNLOAD("download", R.string.direction_download, false, true);
}
