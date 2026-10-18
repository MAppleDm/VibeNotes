package io.github.mappledm.vibenotes.data

import android.Manifest
import android.accounts.Account
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Instances
import android.provider.CalendarContract.Reminders
import androidx.core.content.ContextCompat
import io.github.mappledm.vibenotes.core.calendar.CalendarEvent
import io.github.mappledm.vibenotes.core.calendar.CalendarException
import io.github.mappledm.vibenotes.core.calendar.CalendarGateway
import io.github.mappledm.vibenotes.core.calendar.CalendarInfo
import io.github.mappledm.vibenotes.core.calendar.NewCalendarEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneOffset

/**
 * Android Calendar Provider. Events written here are synced by the system account
 * (e.g. Google Calendar) — no OAuth or Google Cloud project needed.
 */
class AndroidCalendarGateway(private val context: Context) : CalendarGateway {
    private val resolver = context.contentResolver

    override fun hasPermission(): Boolean = PERMISSIONS.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    private suspend inline fun <T> guarded(block: suspend () -> T): T {
        if (!hasPermission()) throw CalendarException(CalendarException.Kind.PERMISSION, "Нет доступа к календарю")
        return try {
            block()
        } catch (e: CalendarException) {
            throw e
        } catch (e: SecurityException) {
            throw CalendarException(CalendarException.Kind.PERMISSION, "Нет доступа к календарю", e)
        } catch (e: Exception) {
            throw CalendarException(CalendarException.Kind.FAILED, "Ошибка календаря: ${e.message ?: e.javaClass.simpleName}", e)
        }
    }

    override suspend fun writableCalendars(): List<CalendarInfo> = withContext(Dispatchers.IO) {
        guarded {
            val projection = arrayOf(
                Calendars._ID,
                Calendars.CALENDAR_DISPLAY_NAME,
                Calendars.ACCOUNT_NAME,
                Calendars.CALENDAR_COLOR,
                Calendars.IS_PRIMARY,
                Calendars.OWNER_ACCOUNT,
                Calendars.ACCOUNT_TYPE,
            )
            val selection = "${Calendars.CALENDAR_ACCESS_LEVEL} >= ? AND ${Calendars.VISIBLE} = 1"
            val args = arrayOf(Calendars.CAL_ACCESS_CONTRIBUTOR.toString())
            val result = mutableListOf<CalendarInfo>()
            resolver.query(Calendars.CONTENT_URI, projection, selection, args, null)?.use { c ->
                while (c.moveToNext()) {
                    val account = c.getString(2).orEmpty()
                    val owner = c.getString(5).orEmpty()
                    val primaryFlag = if (c.isNull(4)) null else c.getInt(4) == 1
                    result += CalendarInfo(
                        id = c.getLong(0),
                        name = c.getString(1) ?: account,
                        accountName = account,
                        color = if (c.isNull(3)) null else c.getInt(3),
                        isPrimary = primaryFlag ?: (account.isNotEmpty() && account == owner),
                        accountType = c.getString(6).orEmpty(),
                    )
                }
            }
            // Google main calendars first, then other Google calendars, then device/other accounts.
            result.sortedWith(
                compareByDescending<CalendarInfo> { it.isGoogle && it.isPrimary }
                    .thenByDescending { it.isGoogle }
                    .thenByDescending { it.isPrimary }
                    .thenBy { it.accountName.lowercase() }
                    .thenBy { it.name.lowercase() },
            )
        }
    }

    override suspend fun events(from: Instant, to: Instant): List<CalendarEvent> = withContext(Dispatchers.IO) {
        guarded {
            val uri = Instances.CONTENT_URI.buildUpon().also {
                ContentUris.appendId(it, from.toEpochMilli())
                ContentUris.appendId(it, to.toEpochMilli())
            }.build()
            val projection = arrayOf(
                Instances.EVENT_ID,
                Instances.CALENDAR_ID,
                Instances.TITLE,
                Instances.BEGIN,
                Instances.END,
                Instances.ALL_DAY,
                Instances.EVENT_LOCATION,
                Instances.DISPLAY_COLOR,
            )
            val result = mutableListOf<CalendarEvent>()
            resolver.query(uri, projection, "${Instances.VISIBLE} = 1", null, "${Instances.BEGIN} ASC")?.use { c ->
                while (c.moveToNext()) {
                    result += CalendarEvent(
                        eventId = c.getLong(0),
                        calendarId = c.getLong(1),
                        title = c.getString(2).orEmpty().ifEmpty { "(без названия)" },
                        start = Instant.ofEpochMilli(c.getLong(3)),
                        end = Instant.ofEpochMilli(c.getLong(4)),
                        allDay = c.getInt(5) == 1,
                        location = c.getString(6)?.takeIf { it.isNotBlank() },
                        color = if (c.isNull(7)) null else c.getInt(7),
                    )
                }
            }
            result
        }
    }

    override suspend fun event(eventId: Long): CalendarEvent? = withContext(Dispatchers.IO) {
        guarded {
            val projection = arrayOf(
                Events._ID,
                Events.CALENDAR_ID,
                Events.TITLE,
                Events.DTSTART,
                Events.DTEND,
                Events.ALL_DAY,
                Events.EVENT_LOCATION,
                Events.DESCRIPTION,
            )
            var event: CalendarEvent? = null
            resolver.query(eventUri(eventId), projection, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    event = CalendarEvent(
                        eventId = c.getLong(0),
                        calendarId = c.getLong(1),
                        title = c.getString(2).orEmpty().ifEmpty { "(без названия)" },
                        start = Instant.ofEpochMilli(c.getLong(3)),
                        end = Instant.ofEpochMilli(c.getLong(4)),
                        allDay = c.getInt(5) == 1,
                        location = c.getString(6)?.takeIf { it.isNotBlank() },
                        color = null,
                        description = c.getString(7)?.takeIf { it.isNotBlank() },
                    )
                }
            }
            event?.copy(reminderMinutes = firstReminder(eventId))
        }
    }

    private fun firstReminder(eventId: Long): Int? =
        resolver.query(
            Reminders.CONTENT_URI,
            arrayOf(Reminders.MINUTES),
            "${Reminders.EVENT_ID} = ?",
            arrayOf(eventId.toString()),
            "${Reminders.MINUTES} ASC",
        )?.use { c -> if (c.moveToFirst()) c.getInt(0) else null }

    override suspend fun update(eventId: Long, event: NewCalendarEvent) = withContext(Dispatchers.IO) {
        guarded {
            val values = eventValues(event)
            val changed = resolver.update(eventUri(eventId), values, null, null)
            if (changed <= 0) throw CalendarException(CalendarException.Kind.FAILED, "Событие не найдено")

            resolver.delete(Reminders.CONTENT_URI, "${Reminders.EVENT_ID} = ?", arrayOf(eventId.toString()))
            event.reminderMinutes?.let { minutes ->
                resolver.insert(
                    Reminders.CONTENT_URI,
                    ContentValues().apply {
                        put(Reminders.EVENT_ID, eventId)
                        put(Reminders.MINUTES, minutes)
                        put(Reminders.METHOD, Reminders.METHOD_ALERT)
                    },
                )
            }

            val saved = event(eventId)
            if (saved == null || saved.title != event.title) {
                throw CalendarException(CalendarException.Kind.FAILED, "Событие не сохранилось")
            }
            requestSync(event.calendarId)
        }
        Unit
    }

    override suspend fun delete(eventId: Long) = withContext(Dispatchers.IO) {
        guarded {
            val removed = resolver.delete(eventUri(eventId), null, null)
            if (removed <= 0) throw CalendarException(CalendarException.Kind.FAILED, "Событие уже удалено")
            if (event(eventId) != null) throw CalendarException(CalendarException.Kind.FAILED, "Событие не удалилось")
        }
        Unit
    }

    private fun eventValues(event: NewCalendarEvent): ContentValues = ContentValues().apply {
        put(Events.CALENDAR_ID, event.calendarId)
        put(Events.TITLE, event.title)
        event.description?.let { put(Events.DESCRIPTION, it) } ?: putNull(Events.DESCRIPTION)
        event.location?.let { put(Events.EVENT_LOCATION, it) } ?: putNull(Events.EVENT_LOCATION)
        if (event.allDay) {
            // The provider requires all-day events at UTC midnight with a UTC time zone.
            put(Events.ALL_DAY, 1)
            put(Events.EVENT_TIMEZONE, "UTC")
            put(Events.DTSTART, event.startDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
            put(Events.DTEND, event.endDateExclusive.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        } else {
            put(Events.ALL_DAY, 0)
            put(Events.EVENT_TIMEZONE, event.zone.id)
            put(Events.DTSTART, event.start.atZone(event.zone).toInstant().toEpochMilli())
            put(Events.DTEND, event.end.atZone(event.zone).toInstant().toEpochMilli())
        }
        put(Events.HAS_ALARM, if (event.reminderMinutes != null) 1 else 0)
    }

    override suspend fun insert(event: NewCalendarEvent): Long = withContext(Dispatchers.IO) {
        guarded {
            val uri = resolver.insert(Events.CONTENT_URI, eventValues(event))
                ?: throw CalendarException(CalendarException.Kind.FAILED, "Календарь не принял событие")
            val id = ContentUris.parseId(uri)

            event.reminderMinutes?.let { minutes ->
                val reminder = ContentValues().apply {
                    put(Reminders.EVENT_ID, id)
                    put(Reminders.MINUTES, minutes)
                    put(Reminders.METHOD, Reminders.METHOD_ALERT)
                }
                resolver.insert(Reminders.CONTENT_URI, reminder)
            }

            // Verify: never report success for an event that is not actually there.
            val exists = resolver.query(
                ContentUris.withAppendedId(Events.CONTENT_URI, id),
                arrayOf(Events._ID),
                null,
                null,
                null,
            )?.use { it.moveToFirst() } ?: false
            if (!exists) throw CalendarException(CalendarException.Kind.FAILED, "Событие не найдено после создания")
            requestSync(event.calendarId)
            id
        }
    }

    /**
     * Asks the account's sync adapter to push the new event now instead of on its next
     * periodic run, so it shows up in Google Calendar on other devices quickly. Best effort.
     */
    private fun requestSync(calendarId: Long) {
        runCatching {
            resolver.query(
                ContentUris.withAppendedId(Calendars.CONTENT_URI, calendarId),
                arrayOf(Calendars.ACCOUNT_NAME, Calendars.ACCOUNT_TYPE),
                null,
                null,
                null,
            )?.use { c ->
                if (!c.moveToFirst()) return
                val name = c.getString(0) ?: return
                val type = c.getString(1) ?: return
                if (type.equals(CalendarContract.ACCOUNT_TYPE_LOCAL, ignoreCase = true)) return
                val extras = Bundle().apply {
                    putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
                    putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
                }
                ContentResolver.requestSync(Account(name, type), CalendarContract.AUTHORITY, extras)
            }
        }
    }

    companion object {
        val PERMISSIONS = arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)

        fun eventUri(eventId: Long) = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)

        /**
         * Opens the calendar app on the day and time of an event.
         * Opening by event id is unreliable: Google Calendar keeps its own ids and answers
         * "event not found" for ids from the Calendar Provider, while the day view always works.
         */
        fun viewTimeIntent(beginMillis: Long): android.content.Intent {
            val uri = CalendarContract.CONTENT_URI.buildUpon().appendPath("time").also {
                ContentUris.appendId(it, beginMillis)
            }.build()
            return android.content.Intent(android.content.Intent.ACTION_VIEW, uri)
        }
    }
}
