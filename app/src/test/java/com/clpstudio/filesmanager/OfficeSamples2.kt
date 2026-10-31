package com.clpstudio.filesmanager

import com.clpstudio.filesmanager.SampleOffice.put
import java.io.File
import java.util.zip.ZipOutputStream

/** Sample .xlsx / .pptx builders for tests and screenshots. */
object SampleOffice2 {
    private const val S = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    private const val R = SampleOffice.R
    private const val PR = SampleOffice.PR
    private const val A = "http://schemas.openxmlformats.org/drawingml/2006/main"
    private const val P = "http://schemas.openxmlformats.org/presentationml/2006/main"
    private const val T = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"

    private val rootRels = { target: String ->
        """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="$PR"><Relationship Id="rId1" Type="$T/officeDocument" Target="$target"/></Relationships>"""
    }

    fun xlsx(f: File): File {
        val workbook = """<?xml version="1.0" encoding="UTF-8"?>
<workbook xmlns="$S" xmlns:r="$R"><sheets><sheet name="Sales" sheetId="1" r:id="rId1"/><sheet name="Notes" sheetId="2" r:id="rId2"/></sheets></workbook>"""
        val wbRels = """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="$PR">
<Relationship Id="rId1" Type="$T/worksheet" Target="worksheets/sheet1.xml"/>
<Relationship Id="rId2" Type="$T/worksheet" Target="worksheets/sheet2.xml"/>
<Relationship Id="rId3" Type="$T/sharedStrings" Target="sharedStrings.xml"/>
<Relationship Id="rId4" Type="$T/styles" Target="styles.xml"/></Relationships>"""
        val sst = """<?xml version="1.0" encoding="UTF-8"?>
<sst xmlns="$S" count="6" uniqueCount="6"><si><t>Item</t></si><si><t>Price</t></si><si><t>Date</t></si><si><r><t>Apple</t></r><r><t xml:space="preserve"> pie</t></r></si><si><t>Mango</t></si><si><t>Just a note</t></si></sst>"""
        val styles = """<?xml version="1.0" encoding="UTF-8"?>
<styleSheet xmlns="$S"><numFmts count="1"><numFmt numFmtId="164" formatCode="&quot;₹&quot;#,##0.00"/></numFmts>
<fonts count="2"><font><sz val="11"/></font><font><b/><sz val="11"/><color rgb="FF1F3864"/></font></fonts>
<fills count="3"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FFDDEBF7"/></patternFill></fill></fills>
<cellXfs count="5"><xf numFmtId="0" fontId="0" fillId="0"/><xf numFmtId="0" fontId="1" fillId="2"/><xf numFmtId="164" fontId="0" fillId="0"/><xf numFmtId="14" fontId="0" fillId="0"/><xf numFmtId="10" fontId="0" fillId="0"/></cellXfs></styleSheet>"""
        val sheet1 = """<?xml version="1.0" encoding="UTF-8"?>
<worksheet xmlns="$S"><cols><col min="1" max="1" width="20" customWidth="1"/></cols><sheetData>
<row r="1"><c r="A1" t="s" s="1"><v>0</v></c><c r="B1" t="s" s="1"><v>1</v></c><c r="C1" t="s" s="1"><v>2</v></c><c r="D1" t="inlineStr" s="1"><is><t>Share</t></is></c></row>
<row r="2"><c r="A2" t="s"><v>3</v></c><c r="B2" s="2"><v>1234.5</v></c><c r="C2" s="3"><v>46326</v></c><c r="D2" s="4"><v>0.125</v></c></row>
<row r="3"><c r="A3" t="s"><v>4</v></c><c r="B3" s="2"><v>99</v></c><c r="C3" s="3"><v>46327</v></c><c r="D3"><v>0.1</v></c></row>
<row r="5"><c r="A5" t="str"><v>Total</v></c><c r="B5"><f>SUM(B2:B3)</f><v>1333.5</v></c><c r="E5" t="b"><v>1</v></c></row>
</sheetData></worksheet>"""
        val sheet2 = """<?xml version="1.0" encoding="UTF-8"?>
<worksheet xmlns="$S"><sheetData><row r="1"><c r="A1" t="s"><v>5</v></c></row></sheetData></worksheet>"""
        ZipOutputStream(f.outputStream()).use { z ->
            z.put("_rels/.rels", rootRels("xl/workbook.xml"))
            z.put("xl/workbook.xml", workbook)
            z.put("xl/_rels/workbook.xml.rels", wbRels)
            z.put("xl/sharedStrings.xml", sst)
            z.put("xl/styles.xml", styles)
            z.put("xl/worksheets/sheet1.xml", sheet1)
            z.put("xl/worksheets/sheet2.xml", sheet2)
        }
        return f
    }

    private fun slide(body: String) = """<?xml version="1.0" encoding="UTF-8"?>
<p:sld xmlns:a="$A" xmlns:r="$R" xmlns:p="$P"><p:cSld><p:spTree><p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr/>$body</p:spTree></p:cSld></p:sld>"""

    private fun sp(ph: String?, y: Long, paras: String) =
        """<p:sp><p:nvSpPr><p:cNvPr id="2" name="s"/><p:cNvSpPr/><p:nvPr>${ph?.let { "<p:ph type=\"$it\"/>" } ?: ""}</p:nvPr></p:nvSpPr><p:spPr><a:xfrm><a:off x="457200" y="$y"/><a:ext cx="8229600" cy="1143000"/></a:xfrm></p:spPr><p:txBody><a:bodyPr/>$paras</p:txBody></p:sp>"""

    fun pptx(f: File, image: ByteArray = SampleOffice.png(160, 90)): File {
        val pres = """<?xml version="1.0" encoding="UTF-8"?>
<p:presentation xmlns:a="$A" xmlns:r="$R" xmlns:p="$P"><p:sldIdLst><p:sldId id="256" r:id="rId2"/><p:sldId id="257" r:id="rId3"/></p:sldIdLst><p:sldSz cx="12192000" cy="6858000"/></p:presentation>"""
        val presRels = """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="$PR">
<Relationship Id="rId3" Type="$T/slide" Target="slides/slide2.xml"/>
<Relationship Id="rId2" Type="$T/slide" Target="slides/slide1.xml"/></Relationships>"""
        // Body before title in the tree: reading order puts the title first (by position).
        val s1 = slide(
            sp("body", 1600000, """<a:p><a:r><a:rPr lang="en-US" b="1"/><a:t>Fast</a:t></a:r><a:r><a:t xml:space="preserve"> and offline</a:t></a:r></a:p><a:p><a:pPr lvl="1"/><a:r><a:rPr sz="1600"><a:solidFill><a:srgbClr val="C00000"/></a:solidFill></a:rPr><a:t>No internet needed</a:t></a:r></a:p><a:p><a:pPr><a:buFont typeface="+mj-lt"/><a:buAutoNum type="arabicPeriod"/></a:pPr><a:r><a:t>First</a:t></a:r></a:p><a:p><a:pPr><a:buAutoNum type="arabicPeriod"/></a:pPr><a:r><a:t>Second</a:t></a:r></a:p>""") +
                sp("title", 300000, """<a:p><a:r><a:rPr lang="en-US"/><a:t>Files app</a:t></a:r></a:p>""") +
                """<p:sp><p:nvSpPr><p:cNvPr id="9" name="Footer"/><p:cNvSpPr/><p:nvPr><p:ph type="ftr"/></p:nvPr></p:nvSpPr><p:spPr/><p:txBody><a:p><a:r><a:t>FOOTER</a:t></a:r></a:p></p:txBody></p:sp>""",
        )
        val s2 = slide(
            sp("title", 300000, """<a:p><a:r><a:t>Second slide</a:t></a:r></a:p>""") +
                """<p:pic><p:nvPicPr><p:cNvPr id="4" name="Picture 3" descr="Chart"/><p:cNvPicPr/><p:nvPr/></p:nvPicPr><p:blipFill><a:blip r:embed="rId2"/></p:blipFill><p:spPr><a:xfrm><a:off x="1000000" y="1600000"/><a:ext cx="4572000" cy="2571750"/></a:xfrm></p:spPr></p:pic>""" +
                """<p:graphicFrame><p:nvGraphicFramePr><p:cNvPr id="5" name="Table"/><p:cNvGraphicFramePr/><p:nvPr/></p:nvGraphicFramePr><p:xfrm><a:off x="1000000" y="4400000"/><a:ext cx="6000000" cy="800000"/></p:xfrm><a:graphic><a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/table"><a:tbl><a:tr h="370840"><a:tc><a:txBody><a:p><a:r><a:t>Name</a:t></a:r></a:p></a:txBody></a:tc><a:tc><a:txBody><a:p><a:r><a:t>Size</a:t></a:r></a:p></a:txBody></a:tc></a:tr><a:tr h="370840"><a:tc><a:txBody><a:p><a:r><a:t>photo.jpg</a:t></a:r></a:p></a:txBody></a:tc><a:tc><a:txBody><a:p><a:r><a:t>2 MB</a:t></a:r></a:p></a:txBody></a:tc></a:tr></a:tbl></a:graphicData></a:graphic></p:graphicFrame>""",
        )
        val s2Rels = """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="$PR"><Relationship Id="rId2" Type="$T/image" Target="../media/image1.png"/></Relationships>"""
        ZipOutputStream(f.outputStream()).use { z ->
            z.put("_rels/.rels", rootRels("ppt/presentation.xml"))
            z.put("ppt/presentation.xml", pres)
            z.put("ppt/_rels/presentation.xml.rels", presRels)
            z.put("ppt/slides/slide1.xml", s1)
            z.put("ppt/slides/slide2.xml", s2)
            z.put("ppt/slides/_rels/slide2.xml.rels", s2Rels)
            z.put("ppt/media/image1.png", image)
        }
        return f
    }
}
