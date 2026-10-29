package com.example.testchecker

data class ExamStats(val n: Int, val meanPct: Double, val medianPct: Double, val highPct: Double, val lowPct: Double)
data class ItemStat(val number: Int, val type: QType, val n: Int, val avgPct: Double, val fullPct: Double)

object Analysis {
    fun pct(r: ScoreRecord) = if (r.total > 0) r.score / r.total * 100 else 0.0

    fun stats(records: List<ScoreRecord>): ExamStats? {
        if (records.isEmpty()) return null
        val p = records.map { pct(it) }.sorted()
        val median = if (p.size % 2 == 1) p[p.size / 2] else (p[p.size / 2 - 1] + p[p.size / 2]) / 2
        return ExamStats(p.size, p.average(), median, p.last(), p.first())
    }

    /** Hardest items first. Only papers saved with per-item detail are counted. */
    fun itemStats(records: List<ScoreRecord>): List<ItemStat> =
        records.flatMap { it.items }.groupBy { it.number }.mapNotNull { (num, list) ->
            val usable = list.filter { it.max > 0 }
            if (usable.isEmpty()) null
            else ItemStat(
                num, usable.first().type, usable.size,
                usable.map { it.earned / it.max * 100 }.average(),
                usable.count { it.earned >= it.max - 1e-9 } * 100.0 / usable.size
            )
        }.sortedBy { it.avgPct }
}
