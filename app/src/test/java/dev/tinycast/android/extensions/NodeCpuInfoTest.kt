package dev.tinycast.android.extensions

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

class NodeCpuInfoTest {
    private val stat = "cpu 100 200 300 400 500 600 700\ncpu0 10 20 30 40 50 60 70 0 0 0\ncpu2 1 2 3 4 5 6 7\n"
    private fun snapshot(statText: String? = stat, info: String? = null, speed: String? = null, ticks: Long = 100) =
        NodeCpuInfo.fromSources(ticks) { path, _ -> when (path) {
            "/proc/stat" -> statText
            "/proc/cpuinfo" -> info
            else -> speed
        } }

    @Test fun convertsEachCoreAndKeepsSparseIdsWithoutInventingCores() {
        val cpus = snapshot().cpus
        assertEquals(listOf(0, 2), cpus.map { it.id })
        assertEquals(listOf(100L, 200L, 300L, 400L, 600L), cpus[0].times)
        assertEquals(listOf(10L, 20L, 30L, 40L, 60L), cpus[1].times)
    }
    @Test fun convertsNonHundredTickClocksAndTruncatesFractionalMilliseconds() {
        assertEquals(listOf(3333L, 6666L, 10000L, 13333L, 20000L), snapshot(ticks = 3).cpus[0].times)
    }
    @Test fun missingOrDeniedStatisticsReturnAnEmptyArrayWithoutOtherReads() {
        val visited = mutableListOf<String>()
        val value = NodeCpuInfo.fromSources(100) { path, _ -> visited += path; throw IOException("permission denied") }
        assertTrue(value.cpus.isEmpty());assertEquals("stat-unavailable", value.availability)
        assertEquals(listOf("/proc/stat"), visited)
    }
    @Test fun securityDenialAndInvalidClocksReturnUnavailable() {
        assertTrue(NodeCpuInfo.fromSources(100) { _, _ -> throw SecurityException() }.cpus.isEmpty())
        assertTrue(snapshot(ticks = 0).cpus.isEmpty());assertTrue(snapshot(ticks = Long.MAX_VALUE).cpus.isEmpty())
    }
    @Test fun absentOptionalMetadataUsesNodeUnknownAndZeroConventions() {
        val cpu = snapshot().cpus.first();assertEquals("unknown", cpu.model);assertEquals(0, cpu.speed)
    }
    @Test fun readsOnlyModelFieldsAndNeverReturnsSerialOrHardwareIdentifiers() {
        val info = "processor : 0\nmodel name : Example CPU\nSerial : PRIVATE_SERIAL\nHardware : PRIVATE_BOARD\n\nprocessor : 2\nCPU part : 0xd0c\nSerial : OTHER_SERIAL\n"
        val value = snapshot(info = info, speed = "2412300\n")
        assertEquals(listOf("Example CPU", "ARM CPU part 0xd0c"), value.cpus.map { it.model })
        assertEquals(2412, value.cpus.first().speed);assertFalse(value.toString().contains("PRIVATE"))
    }
    @Test fun invalidCoreRowsRejectTheSnapshotInsteadOfChangingItsDenominator() {
        for (bad in listOf("cpu0 1 2", "cpu0 -1 2 3 4 5 6", "cpu0 1 2 3 4 5 6\ncpu0 1 2 3 4 5 6", "cpu999999 1 2 3 4 5 6"))
            assertTrue(bad, snapshot(statText = bad).cpus.isEmpty())
    }
    @Test fun refusesOverflowAndUnsafeJavascriptIntegers() {
        for (bad in listOf("cpu0 9999999999999999999999 2 3 4 5 6", "cpu0 9223372036854775807 2 3 4 5 6"))
            assertTrue(snapshot(statText = bad, ticks = 1).cpus.isEmpty())
    }
    @Test fun rejectsOversizedAndExcessiveCoreData() {
        assertTrue(snapshot(statText = "x".repeat(NodeCpuInfo.STAT_LIMIT + 1)).cpus.isEmpty())
        assertTrue(snapshot(statText = (0..256).joinToString("\n") { "cpu$it 1 2 3 4 5 6" }).cpus.isEmpty())
    }
    @Test fun optionalFrequencyErrorsDoNotFabricateAReportedFrequency() {
        for (bad in listOf("bad", "-1", Long.MAX_VALUE.toString())) assertEquals(0, snapshot(speed = bad).cpus[0].speed)
    }
    @Test fun boundedStreamsAcceptTheExactLimitAndRejectOneMoreByte() {
        assertEquals("1234", NodeCpuInfo.boundedText(ByteArrayInputStream("1234".toByteArray()), 4))
        assertNull(NodeCpuInfo.boundedText(ByteArrayInputStream("12345".toByteArray()), 4))
    }
    @Test fun aSourceThatStopsMakingProgressDoesNotSpin() {
        val stream = object : InputStream() {
            override fun read() = 0
            override fun read(buffer: ByteArray, offset: Int, length: Int) = 0
        }
        assertNull(NodeCpuInfo.boundedText(stream, 4))
    }
    @Test fun modelsAreBoundedAndControlCharactersAreExcluded() {
        val model = snapshot(info = "processor : 0\nmodel name : " + "A".repeat(255) + "\uD83D\uDE00\u0000\n").cpus.first().model
        assertEquals("A".repeat(255), model)
        assertTrue(model.none { it.isISOControl() || it.isSurrogate() })
    }
    @Test fun readersReceiveOnlyFixedPathsAndValidatedCoreIndices() {
        val visited = mutableListOf<Pair<String, Int>>()
        NodeCpuInfo.fromSources(100) { path, limit -> visited += path to limit; if (path == "/proc/stat") stat else null }
        assertEquals(listOf("/proc/stat", "/proc/cpuinfo", "/sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq", "/sys/devices/system/cpu/cpu2/cpufreq/scaling_cur_freq"), visited.map { it.first })
        assertTrue(visited.all { it.second in 1..NodeCpuInfo.INFO_LIMIT })
    }
}
