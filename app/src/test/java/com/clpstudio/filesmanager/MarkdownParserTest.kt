package com.clpstudio.filesmanager

import com.clpstudio.filesmanager.viewer.MarkdownParser
import com.clpstudio.filesmanager.viewer.MdBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownParserTest {
    private val sample = """
        # Title

        Some **bold** and *italic* text with `code` and a [link](https://example.com).

        ## List
        - one
        - [x] done
        - [ ] todo
          - nested
        1. first
        2. second

        > quoted line

        | Name | Size |
        |:-----|-----:|
        | a.txt | 1 KB |
        | b.pdf | 2 MB |

        ```kotlin
        val x = 1
        ```

        ---
        ![logo](img/logo.png)
    """.trimIndent()

    @Test fun parsesEveryBlockKind() {
        val b = MarkdownParser.parse(sample)
        assertEquals(MdBlock.Heading(1, "Title"), b[0])
        assertTrue(b[1] is MdBlock.Paragraph)
        assertEquals(MdBlock.Heading(2, "List"), b[2])
        assertEquals(MdBlock.ListItem(0, null, null, "one"), b[3])
        assertEquals(MdBlock.ListItem(0, null, true, "done"), b[4])
        assertEquals(MdBlock.ListItem(0, null, false, "todo"), b[5])
        assertEquals(MdBlock.ListItem(1, null, null, "nested"), b[6])
        assertEquals(MdBlock.ListItem(0, 1, null, "first"), b[7])
        assertEquals(MdBlock.Quote("quoted line"), b[9])
        val t = b[10] as MdBlock.Table
        assertEquals(listOf("Name", "Size"), t.header)
        assertEquals(2, t.rows.size)
        assertEquals(MdBlock.Code("kotlin", "val x = 1"), b[11])
        assertEquals(MdBlock.Rule, b[12])
        assertEquals(MdBlock.Image("logo", "img/logo.png"), b[13])
    }

    @Test fun inlineStylesAndLinks() {
        val s = MarkdownParser.inline("a **b** *c* `d` [e](https://x.y) ~~f~~", androidx.compose.ui.graphics.Color.Gray, androidx.compose.ui.graphics.Color.Blue)
        assertEquals("a b c  d  e f", s.text)
        assertEquals(1, s.getLinkAnnotations(0, s.length).size)
    }

    @Test fun setextAndPlainParagraphs() {
        val b = MarkdownParser.parse("Title\n=====\n\nline one\nline two")
        assertEquals(MdBlock.Heading(1, "Title"), b[0])
        assertEquals(MdBlock.Paragraph("line one line two"), b[1])
    }
}
