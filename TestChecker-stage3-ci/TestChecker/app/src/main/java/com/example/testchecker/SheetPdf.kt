package com.example.testchecker

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import java.io.File
import kotlin.math.ceil

object SheetPdf {
    fun create(exam: Exam, out: File) {
        val mc = exam.items.filter { it.type == QType.MULTIPLE_CHOICE }
        require(mc.size <= SheetLayout.MAX_ITEMS) { "Too many multiple-choice items for one sheet." }

        val doc = PdfDocument()
        val page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, 1).create())
        val c = page.canvas

        val fill = Paint().apply { color = Color.BLACK; style = Paint.Style.FILL }
        val line = Paint().apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 1f; isAntiAlias = true }
        val txt = Paint().apply { color = Color.BLACK; textSize = 10f; isAntiAlias = true }
        val title = Paint(txt).apply { textSize = 16f; typeface = Typeface.DEFAULT_BOLD }

        // corner registration squares
        val h = SheetLayout.MARKER_SIZE / 2
        SheetLayout.MARKER_CENTERS.forEach { (x, y) -> c.drawRect(x - h, y - h, x + h, y + h, fill) }

        // header
        c.drawText(exam.title, 60f, 76f, title)
        c.drawText("Name:", 60f, 102f, txt); c.drawLine(92f, 103f, 360f, 103f, line)
        c.drawText("Date:", 380f, 102f, txt); c.drawLine(410f, 103f, 535f, 103f, line)
        c.drawText("Section:", 60f, 124f, txt); c.drawLine(104f, 125f, 260f, 125f, line)
        c.drawText("Score:", 380f, 124f, txt); c.drawLine(414f, 125f, 535f, 125f, line)
        c.drawText("Shade ONE circle per item completely with a dark pen or pencil. Do not mark outside the circles.", 60f, 148f, txt)

        // column letter headers (letters are NOT inside the bubbles so empty bubbles stay white)
        val cols = ceil(mc.size / SheetLayout.ROWS_PER_COL.toDouble()).toInt()
        for (col in 0 until cols) for (ch in 0 until exam.choices) {
            val (x, _) = SheetLayout.bubbleCenter(col * SheetLayout.ROWS_PER_COL, ch)
            val letter = ('A' + ch).toString()
            c.drawText(letter, x - txt.measureText(letter) / 2, SheetLayout.ROW_Y0 - 18f, txt)
        }

        // rows
        mc.forEachIndexed { i, item ->
            val (_, y) = SheetLayout.bubbleCenter(i, 0)
            c.drawText("${item.number}.", SheetLayout.labelX(i), y + 3.5f, txt)
            for (ch in 0 until exam.choices) {
                val (x, yy) = SheetLayout.bubbleCenter(i, ch)
                c.drawCircle(x, yy, SheetLayout.BUBBLE_R, line)
            }
        }

        c.drawText("Exam ID: ${exam.id}", 250f, 812f, txt)

        doc.finishPage(page)
        out.outputStream().use { doc.writeTo(it) }
        doc.close()
    }
}
