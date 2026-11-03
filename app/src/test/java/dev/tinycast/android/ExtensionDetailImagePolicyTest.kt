package dev.tinycast.android.extensions

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.CancellationException

class ExtensionDetailImagePolicyTest {
    private fun rejected(action: () -> Unit) {
        try { action(); fail("Unsupported input was accepted") } catch (_: IllegalArgumentException) { }
        catch (_: org.xml.sax.SAXException) { }
    }
    @Test fun renderHintsPreserveUnrelatedServiceQueryBytes() {
        val request = detailImageRequest("https://example.com/a%20b.png?v=a%2Bb&raycast-width=250&token=a+b&raycast-height=auto&raycast-tintColor=%23ff0000")
        assertEquals("https://example.com/a%20b.png?v=a%2Bb&token=a+b", request.source)
        assertEquals(250, request.width); assertNull(request.height); assertEquals("#ff0000", request.tint)
    }
    @Test fun bothDocumentedTintSpellingsAndDataSourcesWork() {
        assertEquals("blue", detailImageRequest("image.png?raycast-tint-color=blue").tint)
        val source = "data:image/svg+xml,%3Csvg%20fill%3D%22%23fff%22%2F%3E"
        val request = detailImageRequest("$source?raycast-height=90&raycast-width=120")
        assertEquals(source, request.source); assertEquals(90, request.height); assertEquals(120, request.width)
    }
    @Test fun invalidOrAmbiguousHintsAreRejected() {
        for (query in listOf("raycast-width=0", "raycast-height=2049", "raycast-width=NaN", "raycast-width=-1",
            "raycast-width=12.5", "raycast-width=2&raycast-width=3", "raycast-tintColor=blue&raycast-tint-color=red")) {
            rejected { detailImageRequest("image.png?$query") }
        }
    }
    @Test fun untrustedSchemesAuthoritiesCredentialsAndPortsAreRejected() {
        for (source in listOf("http://example.com/a.png", "javascript:alert(1)", "content://a/image", "//example.com/image.png",
            "https://user:password@example.com/image.png", "https://example.com:8443/a.png", "file://remote/path", "image.png?service=value")) {
            rejected { detailImageRequest(source) }
        }
    }
    @Test fun packagePathsDecodeBeforeCanonicalContainment() {
        val parent = Files.createTempDirectory("owned-detail-image").toFile()
        try {
            val root = java.io.File(parent, "package").apply { mkdir() }; val assets = java.io.File(root, "assets").apply { mkdir() }
            val image = java.io.File(assets, "日本 image.png").apply { writeText("owned") }
            val request = detailImageRequest("%E6%97%A5%E6%9C%AC%20image.png?raycast-width=100")
            assertEquals(image.canonicalFile, detailImageAsset(root, request.source))
            assertEquals(image.canonicalFile, detailImageAsset(root, image.toURI().toASCIIString()))
            rejected { detailImageAsset(root, "../../outside.png") }
            rejected { detailImageAsset(root, "..%2f..%2foutside.png") }
            rejected { detailImageAsset(root, root.path + "-sibling/image.png") }
            rejected { detailImageAsset(root, "bad%00.png") }
        } finally { parent.deleteRecursively() }
    }
    @Test fun packageSymlinksCannotReadOutsideTheirOwner() {
        val parent = Files.createTempDirectory("owned-detail-symlink").toFile()
        try {
            val root = java.io.File(parent, "package").apply { mkdir() }; val assets = java.io.File(root, "assets").apply { mkdir() }
            val outside = java.io.File(parent, "outside.png").apply { writeText("outside") }
            Files.createSymbolicLink(java.io.File(assets, "link.png").toPath(), outside.toPath())
            rejected { detailImageAsset(root, "link.png") }
        } finally { parent.deleteRecursively() }
    }
    @Test fun boundedStaticSvgSupportsPathsAndLocalGradients() {
        ExtensionSvgPolicy.inspect("""<?xml version="1.0"?><svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100">
            <title>Owned 日本</title><defs><linearGradient id="paint"><stop offset="0%" stop-color="#fff"/><stop offset="100%" stop-color="#00f"/></linearGradient></defs>
            <rect x="0" y="0" width="100" height="100" style="fill:url(#paint);stroke:#000;stroke-width:2"/>
            <path d="M1 1 L10 10 Z" fill="#f00"/></svg>""")
    }
    @Test fun svgRejectsDeclarationsActiveContentAndExternalResources() {
        for (source in listOf("<!DOCTYPE svg [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><svg>&x;</svg>",
            "<svg><script>alert(1)</script></svg>", "<svg><image href='data:image/png;base64,AA=='/></svg>",
            "<svg><use href='#cycle'/></svg>", "<svg><foreignObject/></svg>", "<svg><style>@import 'https://example.com';</style></svg>",
            "<svg><rect onclick='action()'/></svg>", "<svg><rect fill='url(https://example.com/paint)'/></svg>",
            "<svg><?external test?></svg>", "<svg xmlns='https://example.com/other'/>", "<svg><g xmlns:x='urn:x' x:href='file:///outside'/></svg>")) {
            rejected { ExtensionSvgPolicy.inspect(source) }
        }
    }
    @Test fun svgRejectsDanglingNonGradientDuplicateAndInheritedReferences() {
        for (source in listOf("<svg><rect fill='url(#missing)'/></svg>", "<svg><g id='notPaint'/><rect fill='url(#notPaint)'/></svg>",
            "<svg><g id='same'/><g id='same'/></svg>", "<svg><defs><linearGradient id='x' href='#x'/></defs></svg>")) {
            rejected { ExtensionSvgPolicy.inspect(source) }
        }
    }
    @Test fun svgBoundsStructuralAndNumericWork() {
        rejected { ExtensionSvgPolicy.inspect("<svg>" + "<g>".repeat(33) + "</g>".repeat(33) + "</svg>") }
        rejected { ExtensionSvgPolicy.inspect("<svg>" + "<rect/>".repeat(1025) + "</svg>") }
        rejected { ExtensionSvgPolicy.inspect("<svg><path d='" + "M1 2 ".repeat(7000) + "'/></svg>") }
        rejected { ExtensionSvgPolicy.inspect("<svg><rect width='1e99'/></svg>") }
        rejected { ExtensionSvgPolicy.inspect("<svg><rect width='NaN'/></svg>") }
    }
    @Test fun svgInspectionHonorsCancellation() {
        var calls = 0
        try {
            ExtensionSvgPolicy.inspect("<svg><g/><rect/></svg>") { if (++calls == 3) throw CancellationException("owned cancellation") }
            fail("Cancellation was ignored")
        } catch (_: CancellationException) { assertEquals(3, calls) }
    }
}
