package com.example.testchecker

/** Single source of truth for where everything sits on the A4 sheet (units = PDF points). */
object SheetLayout {
    const val PAGE_W = 595f
    const val PAGE_H = 842f
    const val MARKER_SIZE = 24f
    /** Corner marker centres in order: top-left, top-right, bottom-left, bottom-right. */
    val MARKER_CENTERS = listOf(36f to 36f, 559f to 36f, 36f to 806f, 559f to 806f)

    const val ROWS_PER_COL = 25
    const val MAX_ITEMS = 100
    const val COL_X0 = 60f
    const val COL_W = 118f
    const val ROW_Y0 = 170f
    const val ROW_H = 24f
    const val BUBBLE_R = 7f
    const val BUBBLE_DX = 19f
    const val BUBBLE_X_OFFSET = 30f

    fun bubbleCenter(index: Int, choice: Int): Pair<Float, Float> {
        val col = index / ROWS_PER_COL
        val row = index % ROWS_PER_COL
        return (COL_X0 + col * COL_W + BUBBLE_X_OFFSET + choice * BUBBLE_DX) to (ROW_Y0 + row * ROW_H)
    }

    fun labelX(index: Int) = COL_X0 + (index / ROWS_PER_COL) * COL_W
}
