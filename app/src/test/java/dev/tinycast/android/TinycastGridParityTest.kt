package dev.tinycast.android

import dev.tinycast.android.extensions.GridMove
import dev.tinycast.android.extensions.gridMove
import org.junit.Assert.assertEquals
import org.junit.Test

/** Expected results come from the pinned, unchanged Swift model, not this implementation. */
class TinycastGridParityTest {
    @Test fun verticalNavigationMatchesTinycastAcrossSectionsAndRaggedRows() {
        var scenarios=0
        requireNotNull(javaClass.getResourceAsStream("/tinycast-grid-navigation.tsv")).bufferedReader().useLines { lines ->
            lines.filter { it.isNotBlank() && !it.startsWith('#') }.forEach { line ->
                val fields=line.split('\t')
                val counts=fields[0].split(',').map(String::toInt)
                val columns=fields[1].toInt()
                val index=fields[2].toInt()
                val label="sections=$counts columns=$columns index=$index"
                assertEquals("$label up",fields[3].toInt(),gridMove(index,GridMove.UP,counts,columns))
                assertEquals("$label down",fields[4].toInt(),gridMove(index,GridMove.DOWN,counts,columns))
                scenarios++
            }
        }
        assertEquals("Complete upstream matrix",608,scenarios)
    }
}
