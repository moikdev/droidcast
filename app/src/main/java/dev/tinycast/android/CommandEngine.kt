package dev.tinycast.android

import java.math.BigDecimal
import java.math.MathContext
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.*

enum class Kind(val label: String) {
    APP("Applications"), COMMAND("Commands"), NOTE("Notes"), LINK("Quicklinks"), CLIP("Saved clips"), FILE("Files"), CALC("Calculator"), EXTENSION("Extensions")
}

data class Command(
    val id: String,
    val title: String,
    val subtitle: String,
    val kind: Kind = Kind.COMMAND,
    val icon: String = "command",
    val keywords: String = "",
    val payload: String = "",
    val alias: String = "",
    val mimeType: String? = null
)

object CommandSearch {
    fun score(query: String, text: String): Int {
        val q = query.trim().lowercase(Locale.ROOT)
        val t = text.lowercase(Locale.ROOT)
        if (q.isEmpty()) return 0
        if (q == t) return 1200
        if (t.startsWith(q)) return 950 - (t.length - q.length).coerceAtMost(200)
        val at = t.indexOf(q)
        if (at >= 0) return 700 - at.coerceAtMost(200)
        var cursor = 0
        var gaps = 0
        for (c in q) {
            val next = t.indexOf(c, cursor)
            if (next < 0) return -1
            gaps += next - cursor
            cursor = next + 1
        }
        return 300 - gaps.coerceAtMost(250)
    }

    fun find(commands: List<Command>, query: String, pins: Set<String>, usage: Map<String, Int>): List<Command> {
        val q = query.trim()
        return commands.mapNotNull { command ->
            val aliasScore = if (command.alias.isNotBlank() && q.equals(command.alias, true)) 1500 else -1
            val titleScore = score(q, command.title)
            val keywordScore = score(q, command.keywords).let { if (it < 0) -1 else it / 2 }
            val value = maxOf(aliasScore, titleScore, keywordScore)
            if (value < 0) null else command to (value + (if (command.id in pins) 100 else 0) + (usage[command.id] ?: 0).coerceAtMost(40))
        }.sortedByDescending { it.second }.map { it.first }
    }
}

data class Calculation(val expression: String, val result: String, val explanation: String)

object Calculator {
    private data class UnitDef(val dimension: String, val factor: Double)
    private val units = mapOf(
        "mm" to UnitDef("length", .001), "cm" to UnitDef("length", .01), "m" to UnitDef("length", 1.0),
        "km" to UnitDef("length", 1000.0), "in" to UnitDef("length", .0254), "ft" to UnitDef("length", .3048), "mi" to UnitDef("length", 1609.344),
        "g" to UnitDef("mass", 1.0), "kg" to UnitDef("mass", 1000.0), "lb" to UnitDef("mass", 453.59237), "oz" to UnitDef("mass", 28.349523125),
        "ms" to UnitDef("time", .001), "s" to UnitDef("time", 1.0), "min" to UnitDef("time", 60.0), "h" to UnitDef("time", 3600.0), "day" to UnitDef("time", 86400.0),
        "b" to UnitDef("data", 1.0), "kb" to UnitDef("data", 1000.0), "mb" to UnitDef("data", 1e6), "gb" to UnitDef("data", 1e9),
        "kib" to UnitDef("data", 1024.0), "mib" to UnitDef("data", 1048576.0), "gib" to UnitDef("data", 1073741824.0)
    )
    private const val EXPONENT = "(?:e[+-]?\\d+)?"
    private val numericLiteral = Regex("(?:\\d+(?:\\.\\d*)?|\\.\\d+)$EXPONENT")
    private const val QUANTITY_NUMBER = "-?\\d+(?:\\.\\d+)?$EXPONENT"
    private val conversionPattern = Regex("^($QUANTITY_NUMBER)\\s*([a-z°]+)\\s+(?:to|in)\\s+([a-z°]+)$")
    private val quantityPattern = Regex("^($QUANTITY_NUMBER)\\s+([a-z]+)$")

    private fun parseAmount(text: String): Double {
        val amount = text.toDouble()
        require(amount.isFinite())
        require(amount != 0.0 || text.substringBefore('e').none { it in '1'..'9' })
        return amount
    }

    private val smallPrecision = MathContext(11, java.math.RoundingMode.HALF_EVEN)

    fun format(value: Double): String = when {
        value == 0.0 -> "0"
        abs(value) < 1e-10 -> {
            val rounded = BigDecimal.valueOf(value).round(smallPrecision).stripTrailingZeros()
            // A rounded mantissa can cross into the ordinary decimal range.
            if (abs(rounded.toDouble()) >= 1e-10) rounded.toPlainString() else rounded.toString()
        }
        else -> DecimalFormat("0.##########", DecimalFormatSymbols(Locale.US)).format(value)
    }

    fun evaluate(input: String): Calculation? = calculate(input, requireIntent = false)

    // Root search remains a search for ordinary numbers. Explicit calculator
    // mode, operations, functions, constants and conversions express intent.
    fun evaluateSearch(input: String): Calculation? = calculate(input, requireIntent = true)

    private fun calculate(input: String, requireIntent: Boolean): Calculation? = runCatching {
        val source = input.trim()
        val expression = source.removePrefix("=").trimStart()
        val text = expression.lowercase(Locale.ROOT)
        require(text.isNotBlank() && text.length <= 256)
        val conversion = conversionPattern.matchEntire(text)
        val quantity = if (source.startsWith('=')) quantityPattern.matchEntire(text) else null
        if (conversion != null) {
            val value = parseAmount(conversion.groupValues[1])
            val from = conversion.groupValues[2].removePrefix("°")
            val to = conversion.groupValues[3].removePrefix("°")
            val converted = if (from in listOf("c", "f", "k") && to in listOf("c", "f", "k")) {
                val c = when (from) { "f" -> (value - 32) * 5 / 9; "k" -> value - 273.15; else -> value }
                when (to) { "f" -> c * 9 / 5 + 32; "k" -> c + 273.15; else -> c }
            } else {
                val a = units.getValue(from)
                val b = units.getValue(to)
                require(a.dimension == b.dimension)
                value * a.factor / b.factor
            }
            require(converted.isFinite())
            Calculation(input, "${format(converted)} $to", "Unit conversion · computed on device")
        } else if (quantity != null) {
            val (number, unit) = quantity.destructured
            val amount = parseAmount(number)
            require(unit in units || unit in listOf("c", "f", "k"))
            Calculation(source, "${format(amount)} $unit", "Quantity · computed on device")
        } else {
            val parser = Parser(text.replace('÷', '/').replace('−', '-'))
            val value = parser.parse()
            require(value.isFinite())
            require(!requireIntent || source.startsWith('=') || parser.hasOperation || text in listOf("pi", "e"))
            val prefix = source.dropLast(expression.length)
            Calculation(prefix + parser.displayExpression(expression), format(value), "Calculation · computed on device")
        }
    }.getOrNull()

    private class Parser(private val text: String) {
        private var pos = 0
        var hasOperation = false; private set
        private val multiplicationPositions = mutableSetOf<Int>()
        fun displayExpression(original: String): String = original.mapIndexed { index, c ->
            if (index in multiplicationPositions) '×' else c
        }.joinToString("").replace(Regex("\\s*×\\s*"), " × ")
        private fun spaces() { while (pos < text.length && text[pos].isWhitespace()) pos++ }
        private fun take(c: Char): Boolean { spaces(); return if (pos < text.length && text[pos] == c) { pos++; true } else false }
        private fun operation(c: Char): Boolean = take(c).also { if (it) hasOperation = true }
        private fun multiplication(): Boolean {
            spaces()
            if (pos >= text.length || text[pos] !in "*x×") return false
            multiplicationPositions += pos++
            hasOperation = true
            return true
        }
        private fun constant(name: String): Boolean {
            if (!text.startsWith(name, pos)) return false
            val end = pos + name.length
            // x can directly follow a known constant, as in pix2 or ex2.
            // Other letters belong to an identifier; unknown identifiers and
            // any invalid remainder still fail the complete parse.
            if (end < text.length && text[end].isLetter() && text[end] != 'x') return false
            pos = end
            return true
        }
        fun parse(): Double { val v = sum(); spaces(); require(pos == text.length); return v }
        private fun sum(): Double {
            var v = product()
            while (true) v = when { operation('+') -> v + product(); operation('-') -> v - product(); else -> return v }
        }
        private fun product(): Double {
            var v = unary()
            while (true) v = when { multiplication() -> v * unary(); operation('/') -> { val d = unary(); require(d != 0.0); v / d }; else -> return v }
        }
        private fun unary(): Double = when { operation('+') -> unary(); operation('-') -> -unary(); else -> power() }
        private fun power(): Double { var v = atom(); while (operation('%')) v /= 100; if (operation('^')) v = v.pow(unary()); return v }
        private fun atom(): Double {
            if (take('(')) { val v = sum(); require(take(')')); return v }
            spaces()
            if (constant("pi")) return PI
            if (constant("e")) return E
            val start = pos
            if (pos < text.length && text[pos].isLetter()) {
                while (pos < text.length && text[pos].isLetter()) pos++
                val name = text.substring(start, pos)
                require(take('(')); hasOperation = true; val v = sum(); require(take(')'))
                return when (name) { "sqrt" -> sqrt(v); "abs" -> abs(v); "sin" -> sin(v); "cos" -> cos(v); "tan" -> tan(v); "ln" -> ln(v); "log" -> log10(v); "round" -> round(v); else -> error("Unknown function") }
            }
            val number = requireNotNull(numericLiteral.matchAt(text, pos))
            pos = number.range.last + 1
            return parseAmount(number.value)
        }
    }
}
