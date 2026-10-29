package com.example.testchecker

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

class ExamStore(private val dir: File) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val examsFile get() = File(dir, "exams.json")
    private fun scoresFile(id: String) = File(dir, "scores-$id.json")

    fun loadExams(): List<Exam> =
        runCatching { json.decodeFromString<List<Exam>>(examsFile.readText()) }.getOrDefault(emptyList())

    fun saveExams(list: List<Exam>) = examsFile.writeText(json.encodeToString(list))

    fun loadScores(examId: String): List<ScoreRecord> =
        runCatching { json.decodeFromString<List<ScoreRecord>>(scoresFile(examId).readText()) }
            .getOrDefault(emptyList())

    fun addScore(examId: String, record: ScoreRecord) =
        scoresFile(examId).writeText(json.encodeToString(loadScores(examId) + record))

    fun saveScores(examId: String, list: List<ScoreRecord>) = scoresFile(examId).writeText(json.encodeToString(list))

    fun deleteScores(examId: String) { scoresFile(examId).delete() }
}
