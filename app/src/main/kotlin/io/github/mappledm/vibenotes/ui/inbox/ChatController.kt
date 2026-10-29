package io.github.mappledm.vibenotes.ui.inbox

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mappledm.vibenotes.core.agent.ActionProposal
import io.github.mappledm.vibenotes.core.agent.ActivityKind
import io.github.mappledm.vibenotes.core.agent.ToolActivity
import io.github.mappledm.vibenotes.core.calendar.CalendarInfo
import io.github.mappledm.vibenotes.core.inbox.ChatEntry
import io.github.mappledm.vibenotes.core.inbox.ChatRole
import io.github.mappledm.vibenotes.core.inbox.ProposalItem
import io.github.mappledm.vibenotes.data.AndroidCalendarGateway
import io.github.mappledm.vibenotes.ui.components.MarkdownView
import java.time.ZoneId

private const val LOAD_ONLY = "load"

/**
 * What a card can do, wherever it is shown: in the chat or in the answer panel over another tab.
 * One per home screen, so the calendar permission dialog and the card editor exist once.
 */
class ChatController internal constructor(
    val vm: InboxViewModel,
    private val calendarsState: State<List<CalendarInfo>>,
    private val confirmAction: (String, ProposalItem) -> Unit,
    private val permissionAction: (String, ProposalItem) -> Unit,
    val loadCalendars: () -> Unit,
    private val openTargetState: State<(ProposalItem) -> Unit>,
    private val wikiLinkState: State<(String) -> Unit>,
    private val context: Context,
) {
    val calendars: List<CalendarInfo> get() = calendarsState.value
    internal var editing by mutableStateOf<Pair<String, ProposalItem>?>(null)

    fun confirm(entryId: String, item: ProposalItem) = confirmAction(entryId, item)
    fun requestPermission(entryId: String, item: ProposalItem) = permissionAction(entryId, item)
    fun openTarget(item: ProposalItem) = openTargetState.value(item)
    fun openWikiLink(target: String) = wikiLinkState.value(target)
    fun openInCalendarApp(item: ProposalItem) = openInCalendarApp(context, item.proposal)
}

@Composable
fun rememberChatController(
    vm: InboxViewModel,
    onOpenTarget: (ProposalItem) -> Unit,
    onOpenNote: (String) -> Unit,
): ChatController {
    val context = LocalContext.current
    val calendars = vm.calendars.collectAsStateWithLifecycle()
    var pendingConfirm by rememberSaveable { mutableStateOf<String?>(null) }
    var permissionDenied by rememberSaveable { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        val target = pendingConfirm
        pendingConfirm = null
        val granted = grants.isNotEmpty() && grants.values.all { it }
        if (!granted) permissionDenied = true
        if (target != null && target != LOAD_ONLY) {
            // Without permission the card shows an honest failure with next steps;
            // with it, the event is added unless a calendar still has to be chosen.
            val (entryId, proposalId) = target.split('|', limit = 2)
            vm.afterPermission(entryId, proposalId, granted)
        } else {
            vm.loadCalendars()
        }
    }

    fun openAppSettings() {
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
    }

    val confirm = { entryId: String, item: ProposalItem ->
        if (item.proposal.needsCalendarPermission() && !vm.hasCalendarPermission()) {
            pendingConfirm = "$entryId|${item.id}"
            permissionLauncher.launch(AndroidCalendarGateway.PERMISSIONS)
        } else {
            vm.confirm(entryId, item.id)
        }
    }
    val requestPermission = { entryId: String, item: ProposalItem ->
        when {
            vm.hasCalendarPermission() -> vm.afterPermission(entryId, item.id, granted = true)
            // After a denial Android may stop showing the dialog: send the user to app settings.
            permissionDenied -> openAppSettings()
            else -> {
                pendingConfirm = "$entryId|${item.id}"
                permissionLauncher.launch(AndroidCalendarGateway.PERMISSIONS)
            }
        }
    }
    val loadCalendars = {
        when {
            vm.hasCalendarPermission() -> vm.loadCalendars()
            permissionDenied -> openAppSettings()
            else -> {
                pendingConfirm = LOAD_ONLY
                permissionLauncher.launch(AndroidCalendarGateway.PERMISSIONS)
            }
        }
    }
    val confirmState = rememberUpdatedState(confirm)
    val permissionState = rememberUpdatedState(requestPermission)
    val loadState = rememberUpdatedState(loadCalendars)
    val openTarget = rememberUpdatedState(onOpenTarget)
    val openWikiLink: (String) -> Unit = { target -> vm.resolveNote(target)?.let(onOpenNote) }
    val wikiLink = rememberUpdatedState(openWikiLink)

    val controller = remember(vm) {
        ChatController(
            vm = vm,
            calendarsState = calendars,
            confirmAction = { e, i -> confirmState.value(e, i) },
            permissionAction = { e, i -> permissionState.value(e, i) },
            loadCalendars = { loadState.value() },
            openTargetState = openTarget,
            wikiLinkState = wikiLink,
            context = context,
        )
    }
    return controller
}

/** The card editor; shown once for the whole home screen. */
@Composable
fun ChatDialogs(chat: ChatController) {
    val boards by chat.vm.boards.collectAsStateWithLifecycle()
    chat.editing?.let { (entryId, item) ->
        ProposalEditorDialog(
            proposal = item.proposal,
            calendars = chat.calendars,
            boards = boards,
            onDismiss = { chat.editing = null },
            onSave = { proposal, done -> chat.vm.edit(entryId, item.id, proposal, done) },
        )
    }
}

/** One message of the chat: the user's bubble, a notice, or the assistant's answer with its cards. */
@Composable
fun ChatEntryItem(entry: ChatEntry, chat: ChatController) {
    when (entry.role) {
        ChatRole.USER -> Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
            entry.focus?.let { focus ->
                Text(
                    focus.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 6.dp, bottom = 2.dp),
                )
            }
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.widthIn(max = 320.dp),
            ) {
                SelectionContainer {
                    Text(entry.text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp))
                }
            }
        }
        ChatRole.NOTICE -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Icon(Icons.Outlined.Info, null, Modifier.size(16.dp).padding(top = 2.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.size(8.dp))
            Text(entry.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        ChatRole.ASSISTANT -> Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (entry.activities.isNotEmpty()) ActivityLine(entry.activities)
            if (entry.streaming && entry.text.isEmpty() && entry.proposals.isEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(8.dp))
                    Text("Думаю…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (entry.text.isNotBlank()) {
                SelectionContainer {
                    MarkdownView(entry.text, compact = true, onWikiLink = chat::openWikiLink)
                }
            }
            entry.proposals.forEach { item ->
                val vm = chat.vm
                val calendars = chat.calendars
                ProposalCard(
                    item = item,
                    calendars = calendars,
                    onConfirm = { chat.confirm(entry.id, item) },
                    onDismiss = { vm.dismiss(entry.id, item.id) },
                    onEdit = { chat.editing = entry.id to item },
                    onOpen = { chat.openTarget(item) },
                    onUndo = { vm.undo(entry.id, item.id) },
                    onRequestPermission = { chat.requestPermission(entry.id, item) },
                    onOpenInCalendarApp = { chat.openInCalendarApp(item) },
                    selectedCalendar = (item.proposal as? ActionProposal.CreateEvent)?.let { vm.effectiveCalendar(it, calendars) },
                    needsCalendarChoice = vm.needsCalendarChoice(item.proposal, calendars),
                    onChooseCalendar = { id -> vm.chooseCalendar(entry.id, item.id, id) },
                    onLoadCalendars = chat.loadCalendars,
                )
            }
            entry.error?.let { error ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.ErrorOutline, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.size(8.dp))
                    Text(error, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                    TextButton(onClick = { chat.vm.retry(entry.id) }) { Text("Повторить") }
                }
            }
        }
    }
}

/**
 * What the assistant looked at (this content was sent to the AI provider), folded into one line;
 * a tap lists every source.
 */
@Composable
private fun ActivityLine(activities: List<ToolActivity>) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    val foldable = activities.size > 1
    Column(
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = if (foldable) Modifier.clickable { expanded = !expanded } else Modifier,
    ) {
        if (expanded || !foldable) {
            activities.forEach { activity ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(activityIcon(activity.kind), null, Modifier.size(14.dp), tint = color)
                    Spacer(Modifier.size(6.dp))
                    Text(activity.label, style = MaterialTheme.typography.labelMedium, color = color)
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Private notes and errors stay visible even folded: their icon comes first.
                val first = activities.firstOrNull { it.kind == ActivityKind.PRIVATE || it.kind == ActivityKind.ERROR } ?: activities.first()
                Icon(activityIcon(first.kind), null, Modifier.size(14.dp), tint = color)
                Spacer(Modifier.size(6.dp))
                Text(
                    activities.joinToString(" · ") { it.label },
                    style = MaterialTheme.typography.labelMedium,
                    color = color,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Icon(Icons.Filled.ExpandMore, "Показать всё", Modifier.size(16.dp), tint = color)
            }
        }
        if (expanded && foldable) Icon(Icons.Filled.ExpandLess, "Свернуть", Modifier.size(16.dp), tint = color)
    }
}

private fun activityIcon(kind: ActivityKind): ImageVector = when (kind) {
    ActivityKind.SEARCH -> Icons.Outlined.Search
    ActivityKind.READ -> Icons.Outlined.Description
    ActivityKind.TASKS -> Icons.Outlined.TaskAlt
    ActivityKind.CALENDAR -> Icons.Outlined.CalendarMonth
    ActivityKind.PRIVATE -> Icons.Outlined.Lock
    ActivityKind.ERROR -> Icons.Outlined.ErrorOutline
}

private fun ActionProposal.needsCalendarPermission(): Boolean =
    this is ActionProposal.CreateEvent || this is ActionProposal.UpdateEvent || this is ActionProposal.DeleteEvent

/** Fallback without calendar permission: open the system "new event" screen prefilled. */
private fun openInCalendarApp(context: Context, proposal: ActionProposal) {
    val p = proposal as? ActionProposal.CreateEvent ?: return
    val zone = ZoneId.systemDefault()
    val intent = Intent(Intent.ACTION_INSERT)
        .setData(CalendarContract.Events.CONTENT_URI)
        .putExtra(CalendarContract.Events.TITLE, p.title)
        .putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, p.allDay)
        .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, p.startDateTime.atZone(zone).toInstant().toEpochMilli())
        .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, p.endDateTime.atZone(zone).toInstant().toEpochMilli())
    p.location?.let { intent.putExtra(CalendarContract.Events.EVENT_LOCATION, it) }
    p.description?.let { intent.putExtra(CalendarContract.Events.DESCRIPTION, it) }
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
        )
    }
}
