package io.github.mappledm.vibenotes.ui.update

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.mappledm.vibenotes.AppContainer
import io.github.mappledm.vibenotes.core.update.ReleaseInfo
import io.github.mappledm.vibenotes.data.UpdateState
import io.github.mappledm.vibenotes.ui.components.MarkdownView
import io.github.mappledm.vibenotes.ui.components.containerFactory
import io.github.mappledm.vibenotes.ui.theme.LocalExtraColors

class UpdateViewModel(container: AppContainer) : ViewModel() {
    private val updater = container.updater
    val state = updater.state
    val settings = container.settings.state
    val currentVersion: String = updater.currentVersionName

    fun check() = updater.check()
    fun download(release: ReleaseInfo) = updater.download(release)
    fun cancel() = updater.cancel()
    fun install() = updater.install()
    fun dismiss(release: ReleaseInfo) = updater.dismiss(release)
}

@Composable
private fun updateViewModel(): UpdateViewModel = viewModel(factory = containerFactory { UpdateViewModel(it) })

/** A quiet card on top of the Inbox while a new version is available or being installed. */
@Composable
fun UpdateBanner() {
    val vm = updateViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val release = when (val s = state) {
        is UpdateState.Available -> s.release.takeIf { it.versionCode != settings.dismissedUpdate }
        is UpdateState.Downloading -> s.release
        is UpdateState.Ready -> s.release
        is UpdateState.Failed -> s.release?.takeIf { it.versionCode != settings.dismissedUpdate }
        else -> null
    } ?: return

    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.SystemUpdate, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(bannerTitle(state, release), style = MaterialTheme.typography.bodyMedium)
                    bannerSubtitle(state, release)?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (state is UpdateState.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            (state as? UpdateState.Downloading)?.let { ProgressLine(it.progress, Modifier.padding(top = 8.dp, end = 8.dp)) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                when (val s = state) {
                    is UpdateState.Available -> {
                        TextButton(onClick = { vm.dismiss(s.release) }) { Text("Позже") }
                        TextButton(onClick = { vm.download(s.release) }) { Text("Обновить") }
                    }
                    is UpdateState.Downloading -> TextButton(onClick = vm::cancel) { Text("Отмена") }
                    is UpdateState.Ready -> TextButton(onClick = vm::install) { Text("Установить") }
                    is UpdateState.Failed -> {
                        TextButton(onClick = { vm.dismiss(release) }) { Text("Позже") }
                        TextButton(onClick = { vm.download(release) }) { Text("Повторить") }
                    }
                    else -> Unit
                }
            }
        }
    }
}

/** Version, "check for updates", what's new and the download — for Settings → About. */
@Composable
fun UpdatePanel() {
    val vm = updateViewModel()
    val state by vm.state.collectAsStateWithLifecycle()

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Установлена версия ${vm.currentVersion}", style = MaterialTheme.typography.bodyLarge)
        when (val s = state) {
            UpdateState.Idle -> OutlinedButton(onClick = vm::check) { Text("Проверить обновления") }
            UpdateState.Checking -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text("Проверяю GitHub…", style = MaterialTheme.typography.bodyMedium)
            }
            UpdateState.UpToDate -> {
                StatusLine(ok = true, text = "Это последняя версия")
                OutlinedButton(onClick = vm::check) { Text("Проверить ещё раз") }
            }
            is UpdateState.Available -> {
                ReleaseNotes(s.release)
                Button(onClick = { vm.download(s.release) }) { Text("Скачать и установить") }
            }
            is UpdateState.Downloading -> {
                ReleaseNotes(s.release)
                ProgressLine(s.progress)
                TextButton(onClick = vm::cancel) { Text("Отмена") }
            }
            is UpdateState.Ready -> {
                ReleaseNotes(s.release)
                if (s.waitingForPermission) {
                    Text(
                        "Разрешите установку приложений из VibeNotes в открывшихся настройках и вернитесь — установка продолжится.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Button(onClick = vm::install) { Text("Установить") }
            }
            is UpdateState.Failed -> {
                StatusLine(ok = false, text = s.message)
                val release = s.release
                if (release != null) {
                    Button(onClick = { vm.download(release) }) { Text("Повторить загрузку") }
                } else {
                    OutlinedButton(onClick = vm::check) { Text("Повторить проверку") }
                }
            }
        }
    }
}

@Composable
private fun ReleaseNotes(release: ReleaseInfo) {
    Text("Доступна версия ${release.versionName}", style = MaterialTheme.typography.titleMedium)
    if (release.notes.isNotBlank()) {
        MarkdownView(release.notes, compact = true, textStyle = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ProgressLine(progress: Float?, modifier: Modifier = Modifier) {
    if (progress == null) {
        LinearProgressIndicator(modifier.fillMaxWidth())
    } else {
        LinearProgressIndicator(progress = { progress }, modifier = modifier.fillMaxWidth())
    }
}

@Composable
private fun StatusLine(ok: Boolean, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (ok) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline,
            null,
            Modifier.size(18.dp),
            tint = if (ok) LocalExtraColors.current.success else MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun bannerTitle(state: UpdateState, release: ReleaseInfo): String = when (state) {
    is UpdateState.Downloading -> "Загружаю обновление" + (state.progress?.let { " · ${(it * 100).toInt()}%" } ?: "…")
    is UpdateState.Ready -> "Обновление скачано"
    is UpdateState.Failed -> "Не удалось обновить"
    else -> "Доступна новая версия ${release.versionName}"
}

private fun bannerSubtitle(state: UpdateState, release: ReleaseInfo): String? = when (state) {
    is UpdateState.Failed -> state.message
    is UpdateState.Ready -> if (state.waitingForPermission) "Разрешите установку из VibeNotes и вернитесь" else "Нажмите «Установить» и подтвердите"
    is UpdateState.Available -> release.notes.lineSequence()
        .map { it.trim().removePrefix("-").removePrefix("*").trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .take(2)
        .joinToString(" · ")
        .ifEmpty { null }
    else -> null
}
