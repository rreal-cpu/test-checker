package com.example.testchecker

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
enum class QType(val label: String) {
    MULTIPLE_CHOICE("Multiple choice"),
    IDENTIFICATION("Identification"),
    NUMERIC("Numeric answer"),
    PROBLEM_SOLVING("Problem solving"),
    ESSAY("Essay")
}

@Serializable
data class Item(
    val number: Int,
    val type: QType = QType.MULTIPLE_CHOICE,
    val question: String = "",   // optional question text, helps the AI grade
    val answer: String = "",
    val points: Double = 1.0,
    val rubric: String = ""   // used by AI grading in stage 2
)

@Serializable
data class Exam(
    val id: String = UUID.randomUUID().toString().take(8),
    val title: String,
    val choices: Int = 4,
    val items: List<Item>
)

@Serializable
data class ItemRecord(
    val number: Int, val type: QType, val given: String,
    val earned: Double, val max: Double, val feedback: String = ""
)

@Serializable
data class ScoreRecord(
    val name: String, val score: Double, val total: Double, val time: Long,
    val items: List<ItemRecord> = emptyList()
)

enum class Status { CORRECT, PARTIAL, WRONG, BLANK, MULTIPLE, PENDING }

data class ItemResult(
    val item: Item, val given: String, val earned: Double, val status: Status,
    val feedback: String = "", val confidence: Double = 1.0
)

object Grader {
    /** scanned: item number -> "A".."E", "" = blank, "*" = more than one mark */
    fun grade(exam: Exam, scanned: Map<Int, String>): List<ItemResult> = exam.items.map { item ->
        if (item.type != QType.MULTIPLE_CHOICE) return@map ItemResult(item, "", 0.0, Status.PENDING)
        val given = scanned[item.number] ?: ""
        when {
            given == "" -> ItemResult(item, given, 0.0, Status.BLANK)
            given == "*" -> ItemResult(item, given, 0.0, Status.MULTIPLE)
            given.equals(item.answer, ignoreCase = true) -> ItemResult(item, given, item.points, Status.CORRECT)
            else -> ItemResult(item, given, 0.0, Status.WRONG)
        }
    }
}
