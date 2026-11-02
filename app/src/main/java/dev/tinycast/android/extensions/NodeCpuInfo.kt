package dev.tinycast.android.extensions

import android.system.Os
import android.system.OsConstants
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

/** Node's empty-array result is used when per-core information is unavailable. */
internal object NodeCpuInfo {
    const val STAT_LIMIT = 256 * 1024
    const val INFO_LIMIT = 1024 * 1024
    private const val MAX_CPUS = 256
    private const val MAX_CPU_ID = 4095
    private const val MAX_JS_INTEGER = 9_007_199_254_740_991L
    data class Cpu(val id: Int, val model: String, val speed: Int, val times: List<Long>)
    data class Snapshot(val cpus: List<Cpu>, val availability: String) {
        fun json() = JSONArray().apply {
            cpus.forEach { cpu -> put(JSONObject().put("model", cpu.model).put("speed", cpu.speed)
                .put("times", JSONObject().apply {
                    listOf("user", "nice", "sys", "idle", "irq").zip(cpu.times).forEach { (key, value) -> put(key, value) }
                })) }
        }
    }

    fun read(): Snapshot {
        val ticks = try { Os.sysconf(OsConstants._SC_CLK_TCK) } catch (_: Exception) { 0L }
        return fromSources(ticks) { path, limit ->
            File(path).inputStream().use { boundedText(it, limit) }
        }
    }

    internal fun boundedText(stream: InputStream, limit: Int): String? {
        require(limit in 1..INFO_LIMIT)
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val count = stream.read(buffer, 0, minOf(buffer.size, limit + 1 - out.size()))
            if (count < 0) return out.toString(Charsets.UTF_8.name())
            if (count == 0) return null
            out.write(buffer, 0, count)
            if (out.size() > limit) return null
        }
    }

    internal fun fromSources(ticks: Long, read: (String, Int) -> String?): Snapshot {
        if (ticks !in 1..1_000_000) return Snapshot(emptyList(), "clock-unavailable")
        fun source(path: String, limit: Int): String? = try {
            read(path, limit)?.takeIf { it.length <= limit }
        } catch (_: IOException) { null } catch (_: SecurityException) { null }
        val stat = source("/proc/stat", STAT_LIMIT) ?: return Snapshot(emptyList(), "stat-unavailable")
        val rows = parseStat(stat, ticks) ?: return Snapshot(emptyList(), "stat-invalid")
        val models = parseModels(source("/proc/cpuinfo", INFO_LIMIT).orEmpty())
        val cpus = rows.map { (id, times) ->
            val frequency = source("/sys/devices/system/cpu/cpu$id/cpufreq/scaling_cur_freq", 64)?.trim()?.toLongOrNull()
            val speed = frequency?.takeIf { it >= 0 && it / 1000 <= Int.MAX_VALUE }?.div(1000)?.toInt() ?: 0
            Cpu(id, models[id] ?: "unknown", speed, times)
        }
        return Snapshot(cpus, "available")
    }

    private fun millis(value: Long, ticks: Long): Long? {
        val seconds = value / ticks
        if (seconds > MAX_JS_INTEGER / 1000) return null
        val result = seconds * 1000 + (value % ticks) * 1000 / ticks
        return result.takeIf { it <= MAX_JS_INTEGER }
    }

    internal fun parseStat(text: String, ticks: Long): List<Pair<Int, List<Long>>>? {
        if (text.length > STAT_LIMIT || ticks !in 1..1_000_000) return null
        val rows = linkedMapOf<Int, List<Long>>()
        for (line in text.lineSequence()) {
            if (!line.startsWith("cpu") || line.getOrNull(3)?.isDigit() != true) continue
            val fields = line.trim().split(Regex("\\s+"))
            val id = fields[0].removePrefix("cpu").toIntOrNull() ?: return null
            if (id !in 0..MAX_CPU_ID || id in rows || rows.size >= MAX_CPUS || fields.size < 7) return null
            val values = fields.drop(1).map { field -> field.toLongOrNull()?.takeIf { it >= 0 } ?: return null }
            val times = listOf(0, 1, 2, 3, 5).map { index -> millis(values[index], ticks) ?: return null }
            rows[id] = times
        }
        return rows.takeIf { it.isNotEmpty() }?.toSortedMap()?.toList()
    }

    private fun parseModels(text: String): Map<Int, String> {
        val models = mutableMapOf<Int, String>()
        var id: Int? = null
        for (line in text.lineSequence()) {
            if (line.isBlank()) { id = null; continue }
            val parts = line.split(':', limit = 2)
            if (parts.size != 2) continue
            val key = parts[0].trim()
            val value = parts[1].trim()
            if (key == "processor") id = value.toIntOrNull()?.takeIf { it in 0..MAX_CPU_ID }
            else if (id != null && key in setOf("model name", "Processor", "cpu model", "CPU part")) {
                val model = value.filterNot { it.isISOControl() }.take(256).trimEnd { it.isHighSurrogate() }
                if (model.isNotBlank()) models.putIfAbsent(id, if (key == "CPU part") "ARM CPU part $model" else model)
            }
        }
        return models
    }
}
