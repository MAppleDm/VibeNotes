package io.github.mappledm.vibenotes.data

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.github.mappledm.vibenotes.AppContainer
import io.github.mappledm.vibenotes.MainActivity
import io.github.mappledm.vibenotes.R
import io.github.mappledm.vibenotes.VibeNotesApp
import io.github.mappledm.vibenotes.core.kanban.TaskItem
import io.github.mappledm.vibenotes.core.reminders.ReminderSchedule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Schedules the weekly "look at your tasks" notification. No server, no sync. */
object TaskReminderScheduler {
    private const val REQUEST_CODE = 4101
    const val CHANNEL_ID = "task_reminders"
    private const val CHANNEL_NAME = "Напоминания о задачах"

    fun schedule(context: Context, settings: AppSettings) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, TaskReminderReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        manager.cancel(pending)
        if (!settings.taskReminderEnabled) return

        val next = ReminderSchedule.next(
            now = ZonedDateTime.now(ZoneId.systemDefault()),
            day = DayOfWeek.of(settings.taskReminderDay.coerceIn(1, 7)),
            hour = settings.taskReminderHour,
            minute = settings.taskReminderMinute,
        )
        // Inexact on purpose: a weekly digest does not need exact-alarm permission.
        runCatching {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.toInstant().toEpochMilli(), pending)
        }
        ensureChannel(context)
    }

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Раз в неделю показывает задачи и просроченное"
            },
        )
    }
}

/** Builds the notification from the local Markdown boards; sends nothing anywhere. */
object TaskReminderNotifier {
    private const val NOTIFICATION_ID = 4101
    private val dateFormat = DateTimeFormatter.ofPattern("d MMM", Locale.forLanguageTag("ru"))

    suspend fun show(context: Context, container: AppContainer) {
        if (!container.settings.current.taskReminderEnabled) return
        val vault = container.vault.value ?: return
        runCatching { vault.notes.refresh() }
        val open = vault.kanban.allTasks()
            .filterNot { vault.notes.isHiddenFromAi(it.boardPath) }
            .filterNot { it.card.checked || it.laneIsComplete }
        if (open.isEmpty()) return

        val today = LocalDate.now()
        val overdue = open.filter { it.card.dueDate?.isBefore(today) == true }.sortedBy { it.card.dueDate }
        val dueToday = open.filter { it.card.dueDate == today }
        val thisWeek = open.filter { task ->
            task.card.dueDate?.let { !it.isBefore(today.plusDays(1)) && !it.isAfter(today.plusDays(7)) } == true
        }
        val noDue = open.filter { it.card.dueDate == null }

        val summary = buildList {
            if (overdue.isNotEmpty()) add("Просрочено: ${overdue.size}")
            if (dueToday.isNotEmpty()) add("На сегодня: ${dueToday.size}")
            if (thisWeek.isNotEmpty()) add("На неделе: ${thisWeek.size}")
            if (isEmpty()) add("Открытых задач: ${open.size}")
        }.joinToString(" · ")

        val shown = (overdue + dueToday + thisWeek + noDue)
            .distinctBy { "${it.boardPath}|${it.laneTitle}|${it.card.text}" }
            .take(5)
        val lines = shown.joinToString("\n") { task -> "• ${task.card.title}${dueLabel(task, today)}" }
        val bigText = if (lines.isBlank()) summary else "$summary\n\n$lines"

        TaskReminderScheduler.ensureChannel(context)
        val content = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val notification = NotificationCompat.Builder(context, TaskReminderScheduler.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("Задачи")
            .setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    0,
                    content,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()
        runCatching {
            if (NotificationManagerCompat.from(context).areNotificationsEnabled()) {
                NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
            }
        }
    }

    private fun dueLabel(task: TaskItem, today: LocalDate): String {
        val due = task.card.dueDate ?: return ""
        val prefix = if (due.isBefore(today)) "просрочено " else ""
        val time = task.card.dueTime?.let { ", $it" } ?: ""
        return " — $prefix${due.format(dateFormat)}$time"
    }
}

class TaskReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? VibeNotesApp ?: return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                TaskReminderNotifier.show(context, app.container)
            } finally {
                runCatching { TaskReminderScheduler.schedule(context, app.container.settings.current) }
                pending.finish()
            }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val app = context.applicationContext as? VibeNotesApp ?: return
        runCatching { TaskReminderScheduler.schedule(context, app.container.settings.current) }
    }
}
