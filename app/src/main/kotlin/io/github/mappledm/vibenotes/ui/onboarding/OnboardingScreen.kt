package io.github.mappledm.vibenotes.ui.onboarding

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mappledm.vibenotes.AppContainer
import io.github.mappledm.vibenotes.data.StorageLocation

/** First run: choose where the Markdown files live. Everything else can wait. */
@Composable
fun OnboardingScreen(container: AppContainer) {
    val problem by container.storageProblem.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) container.chooseStorage(StorageLocation.Folder(uri.toString()))
    }
    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(horizontal = 28.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("VibeNotes", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "Заметки, задачи и календарь через одно окно чата. Пишете как в мессенджере — AI предлагает событие или задачу, вы подтверждаете.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(32.dp))
        Text("Где хранить заметки?", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            "Это обычные Markdown-файлы. Если выбрать папку, которую синхронизирует Syncthing, Google Drive или Obsidian, они будут доступны и на компьютере.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        problem?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(20.dp))
        Button(onClick = { picker.launch(null) }, modifier = Modifier.fillMaxWidth()) { Text("Выбрать папку") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { container.chooseStorage(StorageLocation.Internal) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Хранить внутри приложения") }
        Spacer(Modifier.height(16.dp))
        Text(
            "Без регистрации и серверов. AI подключается позже, своим ключом.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
