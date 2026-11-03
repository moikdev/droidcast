package dev.tinycast.android.extensions

import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler
import java.io.StringReader
import javax.xml.parsers.SAXParserFactory

/** A bounded static illustration subset, before AndroidSVG sees any untrusted XML. */
internal object ExtensionSvgPolicy {
    private val elements = setOf("svg", "g", "defs", "rect", "circle", "ellipse", "path", "line", "polyline", "polygon",
        "linearGradient", "radialGradient", "stop", "title", "desc")
    private val attributes = setOf("id", "version", "width", "height", "viewBox", "preserveAspectRatio", "x", "y", "x1", "y1", "x2", "y2",
        "cx", "cy", "r", "rx", "ry", "fx", "fy", "d", "points", "transform", "fill", "fill-opacity", "fill-rule", "stroke",
        "stroke-width", "stroke-opacity", "stroke-linecap", "stroke-linejoin", "stroke-miterlimit", "stroke-dasharray", "stroke-dashoffset",
        "opacity", "color", "offset", "stop-color", "stop-opacity", "gradientUnits", "gradientTransform", "spreadMethod", "style")
    private val styles = setOf("fill", "fill-opacity", "fill-rule", "stroke", "stroke-width", "stroke-opacity", "stroke-linecap",
        "stroke-linejoin", "stroke-miterlimit", "stroke-dasharray", "stroke-dashoffset", "opacity", "color", "stop-color", "stop-opacity")
    private val numeric = setOf("width", "height", "viewBox", "x", "y", "x1", "y1", "x2", "y2", "cx", "cy", "r", "rx", "ry", "fx", "fy",
        "d", "points", "transform", "gradientTransform", "stroke-width", "stroke-dasharray", "stroke-dashoffset", "stroke-miterlimit", "offset")
    private val number = Regex("[-+]?(?:[0-9]+\\.?[0-9]*|\\.[0-9]+)(?:[eE][-+]?[0-9]+)?")
    private val localPaint = Regex("(?i)url\\(\\s*(['\"]?)#([A-Za-z_][A-Za-z0-9_.-]{0,127})\\1\\s*\\)")

    fun inspect(source: String, cancellationCheck: () -> Unit = {}) {
        require(source.length in 1..256 * 1024)
        require(!source.contains("<!DOCTYPE", true) && !source.contains("<!ENTITY", true)) { "SVG declarations are unsupported" }
        val factory = SAXParserFactory.newInstance().apply { isNamespaceAware = true }
        val reader = factory.newSAXParser().xmlReader
        // Some Android SAX implementations omit these switches. The declaration
        // rejection and refusing resolver remain mandatory independent barriers.
        for (feature in listOf("http://xml.org/sax/features/external-general-entities", "http://xml.org/sax/features/external-parameter-entities")) {
            try { reader.setFeature(feature, false) } catch (_: org.xml.sax.SAXNotRecognizedException) { }
            catch (_: org.xml.sax.SAXNotSupportedException) { }
        }
        val ids = mutableMapOf<String, String>(); val references = mutableListOf<String>()
        var nodes = 0; var depth = 0; var attributeUnits = 0; var paths = 0
        val parents = ArrayDeque<String>()
        val handler = object : DefaultHandler() {
            override fun resolveEntity(publicId: String?, systemId: String?): InputSource = throw SAXException("External SVG resources are unsupported")
            override fun processingInstruction(target: String?, data: String?) { throw IllegalArgumentException("SVG processing instructions are unsupported") }
            override fun error(e: org.xml.sax.SAXParseException) { throw e }
            override fun fatalError(e: org.xml.sax.SAXParseException) { throw e }
            override fun startElement(uri: String, localName: String, qName: String, attrs: Attributes) {
                cancellationCheck()
                require(++nodes <= 1024 && ++depth <= 32 && attrs.length <= 32)
                require(uri.isEmpty() || uri == "http://www.w3.org/2000/svg")
                require(localName in elements && (nodes != 1 || localName == "svg")) { "Unsupported SVG element" }
                if (localName == "path") require(++paths <= 128)
                parents.addLast(localName)
                for (index in 0 until attrs.length) {
                    val name = attrs.getLocalName(index); val value = attrs.getValue(index)
                    require(attrs.getURI(index).isNullOrEmpty() && name in attributes) { "Unsupported SVG attribute" }
                    attributeUnits += value.length; require(attributeUnits <= 256 * 1024 && value.length <= if (name == "d") 32768 else 8192)
                    if (name == "id") {
                        require(value.matches(Regex("[A-Za-z_][A-Za-z0-9_.-]{0,127}")) && value !in ids)
                        ids[value] = localName
                    }
                    fun checkValue(key: String, text: String) {
                        require(!text.contains("NaN", true) && !text.contains("Infinity", true))
                        val urls = localPaint.findAll(text).toList(); references += urls.map { it.groupValues[2] }
                        val remainder = localPaint.replace(text, "")
                        require(!Regex("(?i)url\\s*\\(").containsMatchIn(remainder) && !text.contains('@'))
                        if (urls.isNotEmpty()) require(key in setOf("fill", "stroke"))
                        if (key in numeric) {
                            val values = number.findAll(text).toList(); require(values.size <= 8192)
                            values.forEach { require(it.value.toDouble().let { v -> v.isFinite() && kotlin.math.abs(v) <= 1_000_000 }) }
                        }
                    }
                    if (name == "style") value.split(';').filter(String::isNotBlank).forEach { declaration ->
                        val key = declaration.substringBefore(':').trim(); require(':' in declaration && key in styles)
                        checkValue(key, declaration.substringAfter(':').trim())
                    } else checkValue(name, value)
                }
            }
            override fun endElement(uri: String?, localName: String?, qName: String?) { parents.removeLast(); depth-- }
            override fun characters(ch: CharArray, start: Int, length: Int) {
                require(parents.lastOrNull() in setOf("title", "desc") || (start until start + length).all { ch[it].isWhitespace() })
            }
        }
        reader.contentHandler = handler; reader.entityResolver = handler; reader.errorHandler = handler
        reader.parse(InputSource(StringReader(source)))
        require(nodes > 0 && depth == 0 && references.all { ids[it] in setOf("linearGradient", "radialGradient") })
        cancellationCheck()
    }
}
