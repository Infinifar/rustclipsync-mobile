package io.github.infinifar.rustclipsync

import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/** Public release metadata only. Never receives account credentials or GitHub tokens. */
internal object ProjectUpdates {
    const val PROJECT = "https://github.com/Infinifar/rustclipsync-mobile"
    const val RELEASES = "$PROJECT/releases"
    private const val API = "https://api.github.com/repos/Infinifar/rustclipsync-mobile/releases/latest"

    fun check(current: String): UiMessage {
        val connection = URL(API).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "RustClipSync-Mobile/$current")
            when (connection.responseCode) {
                200 -> {
                    val bytes = connection.inputStream.use { input ->
                        val buffer = ByteArray(65_537)
                        var count = 0
                        while (count < buffer.size) {
                            val read = input.read(buffer, count, buffer.size - count)
                            if (read < 0) break
                            count += read
                        }
                        buffer.copyOf(count)
                    }
                    require(bytes.size <= 65_536)
                    val tag = JSONObject(String(bytes, Charsets.UTF_8)).getString("tag_name")
                    describe(current, tag)
                }
                404 -> UiMessage(R.string.updates_unavailable)
                403, 429 -> UiMessage(R.string.updates_limited)
                else -> UiMessage(R.string.updates_retry)
            }
        } catch (_: Exception) {
            UiMessage(R.string.updates_failed)
        } finally { connection.disconnect() }
    }

    internal fun describe(current: String, latest: String): UiMessage {
        fun version(value: String): List<Int>? {
            if (!Regex("v?[0-9]+\\.[0-9]+\\.[0-9]+").matches(value) || value.length > 40) return null
            return value.removePrefix("v").split('.').map { it.toIntOrNull() ?: return null }
        }
        val local = version(current) ?: return UiMessage(R.string.updates_development)
        val remote = version(latest) ?: return UiMessage(R.string.updates_unknown_tag)
        val comparison = local.zip(remote).firstOrNull { it.first != it.second }
        return if (comparison != null && comparison.first < comparison.second) UiMessage(R.string.updates_new, listOf(latest))
            else UiMessage(R.string.updates_current, listOf(current))
    }
}
