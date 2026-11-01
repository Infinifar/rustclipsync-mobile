package io.github.infinifar.rustclipsync

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.infinifar.rustclipsync.bindings.FilterConfig
import io.github.infinifar.rustclipsync.bindings.TextFilterMode
import io.github.infinifar.rustclipsync.bindings.testFilter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun FilterSettingsEditor(session: SessionController) {
    val context = LocalContext.current
    var mode by remember(session.filterMode) { mutableStateOf(session.filterMode) }
    var rules by remember(session.filterRules) { mutableStateOf(session.filterRules) }
    var downloads by remember(session.filterDownloads) { mutableStateOf(session.filterDownloads) }
    var sample by remember { mutableStateOf("") }
    var incoming by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    fun config() = FilterConfig(mode, rules.lines().map { it.trim() }.filter { it.isNotEmpty() }, downloads)
    LaunchedEffect(mode, rules, downloads, sample, incoming) { result = "" }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.selectableGroup()) {
            TextFilterMode.entries.forEach { value ->
                Choice(when (value) { TextFilterMode.OFF -> context.translated(R.string.filter_off); TextFilterMode.BLACKLIST -> context.translated(R.string.filter_blacklist); TextFilterMode.WHITELIST -> context.translated(R.string.filter_whitelist) }, mode == value, !session.busy && !testing) { mode = value }
            }
        }
        OutlinedTextField(rules, { if (it.length <= 16_384) rules = it }, label = { Text(context.translated(R.string.regex_expressions)) },
            supportingText = { Text(context.translated(R.string.filter_limits)) },
            modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 6, enabled = !session.busy && !testing)
        SettingSwitch(context.translated(R.string.filter_downloads), context.translated(R.string.filter_downloads_note), downloads, !session.busy && !testing) { downloads = it }
        Button(onClick = { session.configureFilter(config()) }, enabled = !session.busy && !testing, modifier = Modifier.fillMaxWidth()) { Text(context.translated(R.string.save_filter)) }
        Note(session.message)
        HorizontalDivider()
        Text(context.translated(R.string.filter_test_title), style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(sample, { if (it.toByteArray().size <= 8192) sample = it }, label = { Text(context.translated(R.string.filter_sample)) },
            supportingText = { Text(context.translated(R.string.filter_sample_note)) }, modifier = Modifier.fillMaxWidth(), maxLines = 4, enabled = !testing)
        SettingSwitch(context.translated(R.string.filter_test_incoming), context.translated(R.string.filter_test_note), incoming, !testing) { incoming = it }
        OutlinedButton(onClick = {
            val candidate = config(); val text = sample; val download = incoming
            testing = true
            scope.launch {
                try {
                    val allowed = withContext(Dispatchers.Default) { testFilter(candidate, text, download) }
                    result = if (allowed) context.translated(R.string.filter_test_allowed) else context.translated(R.string.filter_test_blocked)
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    result = context.translated(R.string.filter_invalid)
                } finally { testing = false }
            }
        }, enabled = !testing && !session.busy, modifier = Modifier.fillMaxWidth()) { Text(if (testing) context.translated(R.string.testing) else context.translated(R.string.filter_test)) }
        if (result.isNotEmpty()) Text(result)
        Note(context.translated(R.string.regex_semantics_note))
    }
}
