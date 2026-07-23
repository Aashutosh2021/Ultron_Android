package com.flexfly.ultron.ai.memory

import android.content.Context
import android.util.Log
import com.flexfly.ultron.ai.brain.BrainEngine
import com.flexfly.ultron.ai.brain.BrainEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.system.measureTimeMillis

/**
 * Top-Level Memory Engine for ULTRON.
 *
 * CRITICAL ARCHITECTURAL GUARANTEE:
 *  - The Memory Engine provides storage and retrieval services ONLY.
 *  - It NEVER makes decisions or executes tools directly.
 */
class MemoryEngine(
    private val context: Context,
    val provider: MemoryProvider = PersistentFileStoreProvider(context),
    private val brainEngine: BrainEngine,
    private val rankingEngine: MemoryRanking = MemoryRanking(),
    private val retentionPolicy: MemoryRetentionPolicy = MemoryRetentionPolicy(),
) {
    companion object {
        private const val TAG = "MemoryEngine"
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var brainObservationJob: Job? = null

    private val _state = MutableStateFlow<MemoryState>(MemoryState.Idle)
    val state: StateFlow<MemoryState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<MemoryEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<MemoryEvent> = _events.asSharedFlow()

    private val _metrics = MutableStateFlow(MemoryMetrics())
    val metrics: StateFlow<MemoryMetrics> = _metrics.asStateFlow()

    init {
        populateDefaultMemories()
    }

    private fun populateDefaultMemories() {
        scope.launch {
            // Seed only on a fresh store — persistence means these must not duplicate
            // on every process start (previously re-added each launch into volatile memory).
            if (provider.getAll().isNotEmpty()) {
                Log.d(TAG, "🌱 Skipping default-memory seed — store already has ${provider.getAll().size} records")
                return@launch
            }
            storeRecord(
                MemoryRecord(
                    summary = "User preferred workout playlist",
                    content = "Workout playlist contains high-tempo electronic tracks",
                    category = MemoryCategory.Preference,
                    importance = MemoryImportance.HIGH,
                )
            )
            storeRecord(
                MemoryRecord(
                    summary = "Device owner identity",
                    content = "ULTRON Primary Owner Profile",
                    category = MemoryCategory.Personal,
                    importance = MemoryImportance.CRITICAL,
                    scope = MemoryScope.PERMANENT,
                )
            )
        }
    }

    fun start() {
        if (brainObservationJob?.isActive == true) return

        brainObservationJob = scope.launch {
            Log.d(TAG, "🚀 MemoryEngine subscribing to BrainEngine.events")
            brainEngine.events.collect { brainEvent ->
                if (brainEvent is BrainEvent.MemoryRequested) {
                    Log.d(TAG, "⚡ MemoryRequested event received for query: '${brainEvent.query}'")
                    queryMemories(MemoryQuery(queryText = brainEvent.query))
                }
            }
        }
    }

    suspend fun storeRecord(record: MemoryRecord): Boolean {
        updateState(MemoryState.Writing(record.id))
        val success = provider.store(record)
        if (success) {
            _events.emit(MemoryEvent.MemoryStored(record.id))
            val allCount = provider.getAll().size
            _metrics.value = _metrics.value.copy(
                totalMemories = allCount,
                writesCount = _metrics.value.writesCount + 1,
            )
            updateState(MemoryState.Completed("Stored record ${record.id}"))
        } else {
            updateState(MemoryState.Error("Failed to store record ${record.id}"))
        }
        return success
    }

    suspend fun queryMemories(query: MemoryQuery): MemoryContext {
        updateState(MemoryState.Searching(query.queryText))

        var records: List<MemoryRecord>
        var rankedRecords: List<MemoryRecord> = emptyList()
        var rankingMs = 0L

        val totalMs = measureTimeMillis {
            records = provider.search(query)
            records = retentionPolicy.purgeExpired(records)

            updateState(MemoryState.Ranking(records.size))
            rankingMs = measureTimeMillis {
                rankedRecords = rankingEngine.rank(records, query)
            }
        }

        _metrics.value = _metrics.value.copy(
            readsCount = _metrics.value.readsCount + rankedRecords.size,
            searchCount = _metrics.value.searchCount + 1,
            retrievalTimeMs = totalMs,
            rankingTimeMs = rankingMs,
        )

        _events.emit(MemoryEvent.MemorySearchCompleted(query.queryText, rankedRecords.size))
        updateState(MemoryState.Completed("Retrieved ${rankedRecords.size} memories"))

        return MemoryContext(
            query = query,
            retrievedRecords = rankedRecords,
            totalFound = records.size,
            executionTimeMs = totalMs,
        )
    }

    private fun updateState(newState: MemoryState) {
        if (_state.value != newState) {
            Log.d(TAG, "🔄 [MEMORY TRANSITION] ${_state.value.javaClass.simpleName} ⬇ ${newState.javaClass.simpleName}")
            _state.value = newState
        }
    }

    fun stop() {
        Log.d(TAG, "🛑 Stopping MemoryEngine")
        brainObservationJob?.cancel()
        brainObservationJob = null
        updateState(MemoryState.Idle)
    }

    fun release() {
        stop()
    }
}
