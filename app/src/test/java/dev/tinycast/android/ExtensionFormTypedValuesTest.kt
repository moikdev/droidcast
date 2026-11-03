package dev.tinycast.android

import dev.tinycast.android.extensions.*
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.*
import org.junit.Test

class ExtensionFormTypedValuesTest {
    private val utc = ZoneOffset.UTC
    private val newYork = ZoneId.of("America/New_York")

    @Test fun absentDatesAreDistinctFromMalformedValuesAndKinds() {
        assertEquals(FormDateValue.Empty, formDateValue(null))
        listOf("", "not-a-date", "2024-02-30T00:00:00Z").forEach { assertEquals(FormDateValue.Invalid, formDateValue(it)) }
        assertEquals(FormDateKind.DATE_TIME, formDateKind(null))
        assertEquals(FormDateKind.DATE, formDateKind("date"))
        assertNull(formDateKind("unexpected-kind"))
    }
    @Test fun exactInstantAndFractionalSecondsSurviveParsing() {
        val value = "2024-02-29T12:34:56.123456789Z"
        assertEquals(FormDateValue.Selected(Instant.parse(value)), formDateValue(value))
    }
    @Test fun dateBoundsIncludeTheWholeLocalDayRatherThanOnlyTheirInstants() {
        val minimum = Instant.parse("2024-03-10T22:00:00Z")
        val maximum = Instant.parse("2024-03-11T06:00:00Z")
        assertTrue(formDateWithinBounds(Instant.parse("2024-03-10T05:00:00Z"), FormDateKind.DATE, minimum, maximum, newYork))
        assertFalse(formDateWithinBounds(Instant.parse("2024-03-10T04:59:59Z"), FormDateKind.DATE, minimum, maximum, newYork))
        assertTrue(formDateWithinBounds(Instant.parse("2024-03-12T03:59:59Z"), FormDateKind.DATE, minimum, maximum, newYork))
        assertFalse(formDateWithinBounds(Instant.parse("2024-03-12T04:00:00Z"), FormDateKind.DATE, minimum, maximum, newYork))
    }
    @Test fun datetimeBoundsAreInclusiveAtTheExactInstant() {
        val minimum = Instant.parse("2024-02-29T12:34:56.100Z")
        val maximum = minimum.plusSeconds(60)
        assertTrue(formDateWithinBounds(minimum, FormDateKind.DATE_TIME, minimum, maximum, utc))
        assertTrue(formDateWithinBounds(maximum, FormDateKind.DATE_TIME, minimum, maximum, utc))
        assertFalse(formDateWithinBounds(minimum.minusNanos(1), FormDateKind.DATE_TIME, minimum, maximum, utc))
        assertFalse(formDateWithinBounds(maximum.plusNanos(1), FormDateKind.DATE_TIME, minimum, maximum, utc))
        assertFalse(formDateWithinBounds(minimum, FormDateKind.DATE_TIME, maximum, minimum, utc))
    }
    @Test fun aNonexistentDstWallTimeIsRejectedInsteadOfShifted() {
        assertNull(formDateFromLocal(LocalDate.of(2024, 3, 10), LocalTime.of(2, 30), FormDateKind.DATE_TIME, newYork))
        assertEquals(Instant.parse("2024-03-10T07:30:00Z"),
            formDateFromLocal(LocalDate.of(2024, 3, 10), LocalTime.of(3, 30), FormDateKind.DATE_TIME, newYork))
    }
    @Test fun anAmbiguousUneditedWallTimeKeepsItsExistingOffsetAndPrecision() {
        val day = LocalDate.of(2024, 11, 3)
        val time = LocalTime.of(1, 30, 12, 345000000)
        assertEquals(Instant.parse("2024-11-03T06:30:12.345Z"), formDateFromLocal(day, time, FormDateKind.DATE_TIME, newYork, ZoneOffset.ofHours(-5)))
        assertEquals(Instant.parse("2024-11-03T05:30:12.345Z"), formDateFromLocal(day, time, FormDateKind.DATE_TIME, newYork, ZoneOffset.ofHours(-4)))
    }
    @Test fun dateOnlyUsesTheFullDayMarkerAtLocalStartAndRejectsASkippedDay() {
        assertEquals(Instant.parse("2024-03-10T05:00:00.001Z"),
            formDateFromLocal(LocalDate.of(2024, 3, 10), LocalTime.NOON, FormDateKind.DATE, newYork))
        assertNull(formDateFromLocal(LocalDate.of(2011, 12, 30), LocalTime.MIDNIGHT, FormDateKind.DATE, ZoneId.of("Pacific/Apia")))
    }
    @Test fun calendarPickerLabelsDoNotShiftInNonUtcZonesOrBefore1970() {
        val day = LocalDate.of(1969, 12, 31)
        assertEquals(-86400000L, formPickerMillis(day))
        assertEquals(day, formPickerDay(formPickerMillis(day)))
        val tokyo = ZoneId.of("Asia/Tokyo")
        assertEquals(Instant.parse("1969-12-30T15:00:00.001Z"), formDateFromLocal(day, LocalTime.NOON, FormDateKind.DATE, tokyo))
    }
    @Test fun tagChangesPreserveExactOrderedValuesAndDoNotInventSelections() {
        val original = listOf("internal-two", "unavailable-🌱", "internal-one")
        assertEquals(listOf("internal-two", "unavailable-🌱"), updateFormTags(original, "internal-one", false))
        assertEquals(original, updateFormTags(original, "internal-one", true))
        assertEquals(original + "café é 日本", updateFormTags(original, "café é 日本", true))
        assertEquals(listOf("other"), updateFormTags(listOf("same", "other", "same"), "same", false))
        assertEquals(listOf("internal-two", "unavailable-🌱", "internal-one"), original)
    }
}
