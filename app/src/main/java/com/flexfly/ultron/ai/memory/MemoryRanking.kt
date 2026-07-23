package com.flexfly.ultron.ai.memory

import java.util.Locale

/**
 * Multi-Strategy Ranking Engine scoring candidate memory records.
 */
class MemoryRanking {

    fun rank(records: List<MemoryRecord>, query: MemoryQuery): List<MemoryRecord> {
        val activeRecords = records.filter { it.lifecycle == MemoryLifecycle.ACTIVE }

        return when (query.strategy) {
            MemoryRetrievalStrategy.RECENT_FIRST -> {
                activeRecords.sortedByDescending { it.timestamp }.take(query.limit)
            }

            MemoryRetrievalStrategy.IMPORTANCE_FIRST -> {
                activeRecords.sortedByDescending { it.importance.ordinal }.take(query.limit)
            }

            MemoryRetrievalStrategy.CATEGORY_MATCH -> {
                if (query.categories.isEmpty()) {
                    activeRecords.take(query.limit)
                } else {
                    activeRecords.sortedByDescending { if (query.categories.contains(it.category)) 1 else 0 }.take(query.limit)
                }
            }

            MemoryRetrievalStrategy.HYBRID_RANKING, MemoryRetrievalStrategy.SEMANTIC_SIMILARITY, MemoryRetrievalStrategy.CONTEXT_AWARE -> {
                val queryTerm = query.queryText.lowercase(Locale.getDefault())
                activeRecords.map { record ->
                    var score = 0.0
                    val text = (record.summary + " " + record.content).lowercase(Locale.getDefault())

                    if (queryTerm.isNotEmpty() && text.contains(queryTerm)) {
                        score += 50.0
                    }
                    score += (record.importance.ordinal * 10.0)
                    val ageHours = (System.currentTimeMillis() - record.timestamp) / (1000 * 3600)
                    score += (100.0 / (1.0 + ageHours))

                    Pair(record, score)
                }.sortedByDescending { it.second }
                 .map { it.first }
                 .take(query.limit)
            }
        }
    }
}
