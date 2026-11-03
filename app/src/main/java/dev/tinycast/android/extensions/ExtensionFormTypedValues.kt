package dev.tinycast.android.extensions

import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

internal enum class FormDateKind { DATE, DATE_TIME }

internal sealed interface FormDateValue {
    data object Empty : FormDateValue
    data object Invalid : FormDateValue
    data class Selected(val instant: Instant) : FormDateValue
}

internal fun formDateKind(value: String?): FormDateKind? = when (value) {
    null, "date_time" -> FormDateKind.DATE_TIME
    "date" -> FormDateKind.DATE
    else -> null
}

internal fun formDateValue(iso: String?): FormDateValue = if (iso == null) FormDateValue.Empty else try {
    FormDateValue.Selected(Instant.parse(iso))
} catch (_: DateTimeException) { FormDateValue.Invalid }

/** Calendar bounds compare local days; time bounds compare exact instants. */
internal fun formDateWithinBounds(value: Instant, kind: FormDateKind, minimum: Instant?, maximum: Instant?, zone: ZoneId): Boolean = try {
    if (kind == FormDateKind.DATE) {
        val day = value.atZone(zone).toLocalDate()
        val first = minimum?.atZone(zone)?.toLocalDate()
        val last = maximum?.atZone(zone)?.toLocalDate()
        (first == null || day >= first) && (last == null || day <= last)
    } else (minimum == null || value >= minimum) && (maximum == null || value <= maximum)
} catch (_: DateTimeException) { false }

/** Reject a missing local time rather than silently moving a DST-gap choice.
 * An existing offset keeps an unchanged fall-back time on the same occurrence.
 */
internal fun formDateFromLocal(day: LocalDate, time: LocalTime, kind: FormDateKind, zone: ZoneId,
                               preferredOffset: ZoneOffset? = null): Instant? = try {
    if (kind == FormDateKind.DATE) {
        // Raycast reserves one millisecond as the full-day Date marker.
        // Keep the selected local calendar day across the Date/JSON boundary.
        day.atStartOfDay(zone).takeIf { it.toLocalDate() == day }?.toInstant()?.plusMillis(1)
    } else {
        val local = day.atTime(time)
        val offsets = zone.rules.getValidOffsets(local)
        val offset = preferredOffset?.takeIf { it in offsets } ?: offsets.firstOrNull()
        offset?.let { ZonedDateTime.ofStrict(local, it, zone).toInstant() }
    }
} catch (_: DateTimeException) { null }

/** Material date pickers encode a calendar label at UTC midnight, not the
 * instant represented by that date in the device's current time zone.
 */
internal fun formPickerDay(utcMillis: Long): LocalDate = Instant.ofEpochMilli(utcMillis).atZone(ZoneOffset.UTC).toLocalDate()
internal fun formPickerMillis(day: LocalDate): Long = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

/** Retain unrelated/temporarily unavailable values. A deselection removes the
 * requested value only; repeated selection never adds a duplicate.
 */
internal fun updateFormTags(values: List<String>, value: String, selected: Boolean): List<String> =
    if (!selected) values.filterNot { it == value } else if (value in values) values else values + value
