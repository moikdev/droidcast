package dev.tinycast.android

import dev.tinycast.android.extensions.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ExtensionGridNavigationTest {
    @Test fun fourWayNavigationUsesVisualRowsAndClampsEdges() {
        assertEquals(4, gridMove(1, GridMove.DOWN, listOf(8), 3))
        assertEquals(1, gridMove(4, GridMove.UP, listOf(8), 3))
        assertEquals(2, gridMove(3, GridMove.LEFT, listOf(8), 3))
        assertEquals(3, gridMove(2, GridMove.RIGHT, listOf(8), 3))
        assertEquals(0, gridMove(0, GridMove.LEFT, listOf(8), 3))
        assertEquals(7, gridMove(7, GridMove.DOWN, listOf(8), 3))
    }
    @Test fun sectionHeadersAndRaggedRowsDoNotStealSelection() {
        assertEquals(3, gridMove(2, GridMove.DOWN, listOf(4, 5), 3))
        assertEquals(4, gridMove(3, GridMove.DOWN, listOf(4, 5), 3))
        assertEquals(3, gridMove(6, GridMove.UP, listOf(4, 5), 3))
        assertEquals(8, gridMove(6, GridMove.DOWN, listOf(4, 5), 3))
    }
    @Test fun homeEndEmptyAndStaleIndicesAreSafe() {
        assertEquals(0, gridMove(7, GridMove.HOME, listOf(2, 6), 3))
        assertEquals(7, gridMove(0, GridMove.END, listOf(2, 6), 3))
        assertEquals(0, gridMove(100, GridMove.DOWN, emptyList(), 3))
        assertEquals(7, gridMove(100, GridMove.RIGHT, listOf(8), 3))
        assertEquals(0, gridMove(-1, GridMove.UP, listOf(8), 3))
    }
    @Test fun adaptiveColumnsRespectUsableTouchWidth() {
        assertEquals(4, gridColumns(680f, 0, "medium"))
        assertEquals(2, gridColumns(340f, 0, "medium"))
        assertEquals(3, gridColumns(340f, 99, "small"))
        assertEquals(1, gridColumns(20f, 99, "small"))
        assertTrue(gridColumns(680f, 0, "small") > gridColumns(680f, 0, "large"))
    }
    @Test fun aspectRatiosUseOnlySupportedFiniteValues() {
        assertEquals(1.5f, gridAspectRatio("3/2"))
        assertEquals(9f / 16, gridAspectRatio("9/16"))
        assertEquals(1f, gridAspectRatio("NaN"))
        assertEquals(1f, gridAspectRatio("0"))
    }
    @Test fun assetsResolveInsidePackageAndEscapesAreRejected() {
        val parent = Files.createTempDirectory("grid-assets").toFile()
        try {
            val pkg = File(parent, "package").apply { mkdirs() }
            val assets = File(pkg, "assets").apply { mkdirs() }
            val image = File(assets, "space image.png").apply { writeBytes(byteArrayOf(1)) }
            assertEquals(image.canonicalFile, gridAsset(pkg, "space image.png"))
            assertEquals(image.canonicalFile, gridAsset(pkg, image.toURI().toString()))
            for (source in listOf("../../secret.png", File(parent, "secret.png").path, "file:///data/data/private.png", "content://provider/private", "http://example.com/image.png", "file://host/path")) {
                assertThrows(source, IllegalArgumentException::class.java) { gridAsset(pkg, source) }
            }
            val external = File(parent, "external.png").apply { writeBytes(byteArrayOf(2)) }
            Files.createSymbolicLink(File(assets, "link.png").toPath(), external.toPath())
            assertThrows(IllegalArgumentException::class.java) { gridAsset(pkg, "link.png") }
        } finally { parent.deleteRecursively() }
    }
}
