package io.github.mappledm.vibenotes.ui.inbox

import android.content.Intent
import android.speech.RecognizerIntent
import android.view.KeyCharacterMap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mappledm.vibenotes.core.inbox.ChatFocus

/**
 * The message field under every tab. [focus] is what the tab adds to the message (a day, a board),
 * shown as a chip the user can remove.
 */
@Composable
fun Composer(
    vm: InboxViewModel,
    generating: Boolean,
    aiConfigured: Boolean,
    focus: ChatFocus?,
    onClearFocus: () -> Unit,
    onSend: () -> Unit,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val speechAvailable = remember {
        context.packageManager.queryIntentActivities(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH), 0).isNotEmpty()
    }
    val speechLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val text = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!text.isNullOrBlank()) vm.appendToComposer(text)
    }
    val value = vm.composer

    Surface(color = MaterialTheme.colorScheme.surface, modifier = modifier) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            if (focus != null && aiConfigured) {
                InputChip(
                    selected = false,
                    onClick = onClearFocus,
                    label = { Text(focus.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    trailingIcon = { Icon(Icons.Filled.Close, "Убрать", Modifier.size(InputChipDefaults.AvatarSize)) },
                    modifier = Modifier.padding(start = 12.dp, top = 4.dp),
                )
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Box(Modifier.padding(horizontal = 14.dp, vertical = 11.dp)) {
                        if (value.text.isEmpty()) {
                            Text(
                                if (aiConfigured) "Событие, задача, мысль…" else "Запишите мысль…",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        BasicTextField(
                            value = value,
                            onValueChange = vm::onComposerChange,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            maxLines = 6,
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 24.dp)
                                .focusRequester(focusRequester)
                                .onPreviewKeyEvent { event ->
                                    // Hardware keyboard: Enter sends, Shift+Enter is a new line.
                                    val hardware = event.nativeKeyEvent.deviceId != KeyCharacterMap.VIRTUAL_KEYBOARD
                                    if (event.type == KeyEventType.KeyDown && event.key == Key.Enter &&
                                        (event.isCtrlPressed || (hardware && !event.isShiftPressed))
                                    ) {
                                        onSend()
                                        true
                                    } else false
                                },
                        )
                    }
                }
                Spacer(Modifier.size(8.dp))
                when {
                    generating -> FilledIconButton(onClick = vm::stop) {
                        Icon(Icons.Filled.Stop, contentDescription = "Остановить")
                    }
                    value.text.isBlank() && speechAvailable -> FilledIconButton(onClick = {
                        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Говорите…")
                        runCatching { speechLauncher.launch(intent) }
                    }) {
                        Icon(Icons.Filled.Mic, contentDescription = "Голосовой ввод")
                    }
                    else -> FilledIconButton(onClick = onSend, enabled = value.text.isNotBlank()) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Отправить")
                    }
                }
            }
        }
    }
}
