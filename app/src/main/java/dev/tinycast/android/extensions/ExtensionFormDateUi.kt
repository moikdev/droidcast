package dev.tinycast.android.extensions

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.tinycast.android.PaletteTheme
import org.json.JSONObject
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

private data class DateParameters(val kind: FormDateKind?, val value: FormDateValue,
                                  val minimum: FormDateValue, val maximum: FormDateValue) {
    val first get() = (minimum as? FormDateValue.Selected)?.instant
    val last get() = (maximum as? FormDateValue.Selected)?.instant
    fun unavailable(zone: ZoneId): Boolean = runCatching {
        val firstDay = first?.atZone(zone)?.toLocalDate()
        val lastDay = last?.atZone(zone)?.toLocalDate()
        kind == null || minimum == FormDateValue.Invalid || maximum == FormDateValue.Invalid ||
            (firstDay != null && lastDay != null && if (kind == FormDateKind.DATE) firstDay > lastDay else first!! > last!!)
    }.getOrDefault(true)
}

private fun dateProperty(props: JSONObject, key: String): FormDateValue {
    if (!props.has(key) || props.isNull(key)) return FormDateValue.Empty
    val value = props.optJSONObject(key) ?: return FormDateValue.Invalid
    return if (value.length() == 1 && value.opt("\$date") is String) formDateValue(value.getString("\$date")) else FormDateValue.Invalid
}
private fun dateParameters(field: JSONObject): DateParameters {
    val p = field.props()
    return DateParameters(formDateKind(p.opt("type") as? String), dateProperty(p, "value"), dateProperty(p, "min"), dateProperty(p, "max"))
}
private fun dateLabel(value: FormDateValue, kind: FormDateKind?, zone: ZoneId): String = when (value) {
    FormDateValue.Empty -> if (kind == FormDateKind.DATE) "Choose date" else "Choose date and time"
    FormDateValue.Invalid -> "Choose another date"
    is FormDateValue.Selected -> runCatching {
        val fullDay = formDateIsFullDay(value.instant)
        val formatter = if (kind == FormDateKind.DATE || fullDay) DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
        else DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
        val text = formatter.withLocale(Locale.getDefault()).format(value.instant.atZone(zone))
        if (kind == FormDateKind.DATE_TIME && fullDay) "$text · All day" else text
    }.getOrDefault("Choose another date")
}

/** Compose 1.7 measures custom-width Dialogs against the full display even
 * when Android has reduced their visible frame for the IME. Bound this Dialog
 * by the parent's actual visible frame instead of padding its full-screen
 * measurement. The observer belongs only to the open picker.
 */
@Composable
private fun pickerVisibleHeight(parent: android.view.View, parentIme: WindowInsets): Dp {
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    fun visibleHeight(): Int = android.graphics.Rect().also(parent::getWindowVisibleDisplayFrame).height()
    var observedHeight by remember(parent) { mutableIntStateOf(visibleHeight()) }
    DisposableEffect(parent) {
        val observer = parent.viewTreeObserver
        val listener = android.view.ViewTreeObserver.OnGlobalLayoutListener { observedHeight = visibleHeight() }
        observer.addOnGlobalLayoutListener(listener)
        onDispose { if (observer.isAlive) observer.removeOnGlobalLayoutListener(listener) }
    }
    // Insets invalidate composition even on edge-to-edge windows whose root
    // keeps the same dimensions while the keyboard opens or closes.
    val imeBottom = parentIme.getBottom(density)
    val height = remember(parent, observedHeight, imeBottom, configuration) { visibleHeight() }
    return if (height > 0) minOf(with(density) { height.toDp() }, configuration.screenHeightDp.dp)
        else configuration.screenHeightDp.dp
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NativeFormDatePicker(field: JSONObject, owner: FormFieldOwner?, liveRoot: () -> JSONObject?,
                                  modifier: Modifier, onOpen: () -> Unit, change: (Instant?) -> Boolean) {
    val id = field.props().optString("id")
    val title = (field.props().opt("title") as? String).orEmpty().ifBlank { "Choose date" }
    val zone = ZoneId.systemDefault()
    // Capture the parent before entering the Dialog's separate composition.
    val parentView = LocalView.current
    val parentIme = WindowInsets.ime
    val parameters = dateParameters(field)
    val label = dateLabel(parameters.value, parameters.kind, zone)
    var opening by remember(owner) { mutableStateOf<Any?>(null) }
    var initial by remember(owner) { mutableStateOf(parameters) }
    DisposableEffect(owner) { onDispose { opening = null } }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedButton(onClick = {
            val current = owner?.let { liveFormField(it, liveRoot()) } ?: return@OutlinedButton
            val next = dateParameters(current)
            if (!next.unavailable(zone)) { onOpen(); initial = next; opening = Any() }
        }, enabled = owner != null && !parameters.unavailable(zone),
            modifier = modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("extension-date-$id")
                .semantics { contentDescription = title; stateDescription = label }) {
            Text(label, fontSize = 14.sp, lineHeight = 20.sp)
        }
        if (parameters.unavailable(zone)) Text("No dates are available for this field.", color = MaterialTheme.colorScheme.error,
            fontSize = 13.sp, lineHeight = 20.sp, modifier = Modifier.testTag("extension-date-unavailable-$id"))
        if (parameters.value != FormDateValue.Empty) TextButton(onClick = {
            if (owner != null && liveFormField(owner, liveRoot()) != null) { onOpen(); change(null) }
        }, modifier = Modifier.heightIn(min = 48.dp).testTag("extension-date-clear-$id")) { Text("Clear date") }
    }
    val token = opening
    if (token != null) {
        key(token) {
            val initialValue = (initial.value as? FormDateValue.Selected)?.instant?.let { runCatching { it.atZone(zone) }.getOrNull() }
            val firstDay = initial.first?.atZone(zone)?.toLocalDate()
            val lastDay = initial.last?.atZone(zone)?.toLocalDate()
            val selectable = remember(firstDay, lastDay) { object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                    val day = formPickerDay(utcTimeMillis)
                    return (firstDay == null || day >= firstDay) && (lastDay == null || day <= lastDay)
                }
                override fun isSelectableYear(year: Int): Boolean = (firstDay == null || year >= firstDay.year) && (lastDay == null || year <= lastDay.year)
            } }
            val state = rememberDatePickerState(initialSelectedDateMillis = initialValue?.toLocalDate()?.takeIf { it.year in 1..9999 }?.let(::formPickerMillis),
                yearRange = 1..9999, selectableDates = selectable)
            val time = initialValue?.toLocalTime() ?: LocalTime.now(zone).withNano(0)
            val initiallyFullDay = initialValue?.toInstant()?.let(::formDateIsFullDay) == true
            var allDay by remember { mutableStateOf(initial.kind == FormDateKind.DATE || initiallyFullDay) }
            var hour by remember { mutableStateOf(time.hour.toString().padStart(2, '0')) }
            var minute by remember { mutableStateOf(time.minute.toString().padStart(2, '0')) }
            var second by remember { mutableStateOf(time.second.toString().padStart(2, '0')) }
            var message by remember { mutableStateOf<String?>(null) }
            fun selected(): Instant? {
                val day = state.selectedDateMillis?.let(::formPickerDay) ?: return null
                val clock = if (allDay) LocalTime.MIDNIGHT else {
                    val h = hour.toIntOrNull()?.takeIf { it in 0..23 } ?: return null
                    val m = minute.toIntOrNull()?.takeIf { it in 0..59 } ?: return null
                    val s = second.toIntOrNull()?.takeIf { it in 0..59 } ?: return null
                    // A specific time must not keep the reserved all-day
                    // marker. Ordinary fractional seconds remain unchanged.
                    LocalTime.of(h, m, s, if (initiallyFullDay) 0 else time.nano)
                }
                return formDateFromLocal(day, clock, if (allDay) FormDateKind.DATE else FormDateKind.DATE_TIME, zone, initialValue?.offset)
            }
            Dialog(onDismissRequest = { if (opening === token) opening = null },
                properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
                FormPickerBackdrop(resizeForIme = true)
                val visibleHeight = pickerVisibleHeight(parentView, parentIme)
                val compact = visibleHeight < 200.dp
                Surface(Modifier.heightIn(max = visibleHeight)
                    .padding(horizontal = 12.dp, vertical = if (compact) 0.dp else 6.dp).widthIn(max = 560.dp).fillMaxWidth()
                    .testTag("extension-date-dialog-$id"), shape = MaterialTheme.shapes.extraLarge, color = PaletteTheme.surface) {
                    Column(Modifier.padding(horizontal = 8.dp, vertical = if (compact) 0.dp else 8.dp),
                        verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 12.dp)) {
                        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(title, fontSize = 18.sp, lineHeight = 26.sp, color = PaletteTheme.ink)
                            DatePicker(state, title = null, headline = null, modifier = Modifier.fillMaxWidth().testTag("extension-date-calendar-$id"))
                            if (initial.kind == FormDateKind.DATE_TIME) {
                                ExtensionCheckboxRow("All day", allDay, { allDay = it; message = null },
                                    Modifier.testTag("extension-date-all-day-$id"))
                            }
                            if (initial.kind == FormDateKind.DATE_TIME && !allDay) {
                                Text("Time (${zone.id})", color = PaletteTheme.secondary, fontSize = 13.sp, lineHeight = 20.sp)
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    DateTimeNumber("Hour", hour, 24, { hour = it }, Modifier.weight(1f), "$id-hour")
                                    DateTimeNumber("Minute", minute, 60, { minute = it }, Modifier.weight(1f), "$id-minute")
                                    DateTimeNumber("Second", second, 60, { second = it }, Modifier.weight(1f), "$id-second")
                                }
                            }
                            message?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp, lineHeight = 20.sp,
                                modifier = Modifier.testTag("extension-date-picker-error-$id")) }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { if (opening === token) opening = null }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                                .testTag("extension-date-cancel-$id")) { Text("Cancel") }
                            Button(onClick = {
                                if (opening !== token) return@Button
                                val current = owner?.let { liveFormField(it, liveRoot()) }
                                if (current == null) { opening = null; return@Button }
                                val now = dateParameters(current)
                                if (now.kind != initial.kind || now.unavailable(zone)) { opening = null; return@Button }
                                val value = selected()
                                if (value == null) message = "Choose a valid date and time. This local time may not exist when the clocks change."
                                else if (!formDateWithinBounds(value, requireNotNull(now.kind), now.first, now.last, zone)) message = "Choose a date and time within the allowed range."
                                else if (change(value)) opening = null
                            }, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("extension-date-confirm-$id")) { Text("Use date") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DateTimeNumber(title: String, value: String, period: Int, change: (String) -> Unit, modifier: Modifier, tag: String) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, color = PaletteTheme.secondary, fontSize = 13.sp, lineHeight = 20.sp)
        OutlinedTextField(value, { if (it.length <= 2 && it.all(Char::isDigit)) change(it) }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth().testTag("extension-date-time-$tag")
                .semantics { contentDescription = title })
        Row(Modifier.fillMaxWidth()) {
            TextButton(onClick = { change(Math.floorMod((value.toIntOrNull() ?: 0) - 1, period).toString().padStart(2, '0')) },
                contentPadding = PaddingValues(0.dp), modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("extension-date-time-minus-$tag")
                    .semantics { contentDescription = "Decrease $title" }) { Text("−") }
            TextButton(onClick = { change(Math.floorMod((value.toIntOrNull() ?: 0) + 1, period).toString().padStart(2, '0')) },
                contentPadding = PaddingValues(0.dp), modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("extension-date-time-plus-$tag")
                    .semantics { contentDescription = "Increase $title" }) { Text("+") }
        }
    }
}
