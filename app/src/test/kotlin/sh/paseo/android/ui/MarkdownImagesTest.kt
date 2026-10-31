package sh.paseo.android.ui

import org.junit.Assert.*
import org.junit.Test

class MarkdownImagesTest {
    @Test fun extractsImagesWithoutInterpretingEscapesOrInlineCode() {
        val text = "Before ![Image](file:///work/picture.png) and `![code](file:///no.png)` plus \\![escaped](no.png). After ![Web](https://example.org/a(b).png \"title\")"
        val parts = markdownImageParts(text)
        val images = parts.filter { it.imageSource != null }
        assertEquals(listOf("Image", "Web"), images.map { it.text })
        assertEquals(listOf("file:///work/picture.png", "https://example.org/a(b).png"), images.map { it.imageSource })
        assertTrue(parts.filter { it.imageSource == null }.joinToString("") { it.text }.contains("`![code](file:///no.png)`"))
        assertTrue(parts.filter { it.imageSource == null }.joinToString("") { it.text }.contains("\\![escaped](no.png)"))
    }
    @Test fun supportsEncodedSpacesAngleDestinationsAndLeavesIncompleteMarkupAsText() {
        val parts = markdownImageParts("![A](<file:///work/a b%23c.png>) ![unfinished](file:///work/x")
        assertEquals("file:///work/a b%23c.png", parts.first().imageSource)
        assertTrue(parts.last().text.contains("![unfinished]"))
    }
    @Test fun resolvesRemotePathsAndAvoidsConfusingPlusSignsWithSpaces() {
        assertEquals(ImageFileTarget("/work/app", "/work/app/screenshot +1.png"), imageFileTarget("file:///work/app/screenshot%20+1.png", "/work/app"))
        assertEquals(ImageFileTarget("/", "/tmp/outside.png"), imageFileTarget("file:///tmp/outside.png", "/work/app"))
        assertEquals(ImageFileTarget("/", "/outside.png"), imageFileTarget("file:///work/app/../../outside.png", "/work/app"))
        assertEquals(ImageFileTarget("/work/app", "images/one two.png"), imageFileTarget("images/one%20two.png", "/work/app"))
        assertEquals(ImageFileTarget("~", "~/picture.png"), imageFileTarget("~/picture.png", "/work/app"))
        assertNull(imageFileTarget("https://example.org/p.png", "/work/app"))
        assertNull(imageFileTarget("data:image/png;base64,AA==", "/work/app"))
        assertNull(imageFileTarget("images/p.png", ""))
    }
    @Test fun windowsAndNetworkSharesUseTheirOwnFilesystemRoot() {
        assertEquals(ImageFileTarget("C:/work", "C:/work/picture.png"), imageFileTarget("file:///C:/work/picture.png", "C:/work"))
        assertEquals(ImageFileTarget("D:/", "D:/other/picture.png"), imageFileTarget("file:///D:/other/picture.png", "C:/work"))
        assertEquals(ImageFileTarget("//server/share", "//server/share/a.png"), imageFileTarget("file://server/share/a.png", "/work"))
    }
}
