package com.example.util.simpletimetracker.api

import com.example.util.simpletimetracker.domain.record.model.Record
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

internal class FakeWebApiRecordsInteractor : WebApiRecordsInteractor {

    val typeNames = mutableMapOf(
        3L to "Work",
        5L to "Exercise",
    )
    val records = CopyOnWriteArrayList(DEFAULT_RECORDS)
    val getRanges = CopyOnWriteArrayList<Pair<Long, Long>>()
    val startedActivities = CopyOnWriteArrayList<StartedActivity>()
    val addedRecords = CopyOnWriteArrayList<Record>()
    val updatedRecords = CopyOnWriteArrayList<Pair<Record, Record>>()
    val removedRecords = CopyOnWriteArrayList<Record>()

    private val nextId = AtomicLong(4L)
    private val lock = Any()

    override suspend fun getRecords(from: Long, to: Long): List<Record> {
        getRanges += from to to
        return records.filter { record ->
            record.timeStarted < to && record.timeEnded > from
        }
    }

    override suspend fun getRecord(id: Long): Record? {
        return records.firstOrNull { it.id == id }
    }

    override suspend fun getTypeName(typeId: Long): String? {
        return typeNames[typeId]
    }

    override suspend fun startActivity(typeId: Long, timeStarted: Long?, comment: String) {
        startedActivities += StartedActivity(typeId, timeStarted, comment)
    }

    override suspend fun addRecord(record: Record, deduplicate: Boolean): Record = synchronized(lock) {
        if (deduplicate) {
            val existing = records.firstOrNull {
                it.typeId == record.typeId &&
                    it.timeStarted == record.timeStarted &&
                    it.timeEnded == record.timeEnded &&
                    it.comment == record.comment
            }
            if (existing != null) return@synchronized existing
        }
        addedRecords += record
        val added = record.copy(id = nextId.getAndIncrement())
        records += added
        added
    }

    override suspend fun updateRecord(
        recordId: Long,
        typeId: Long?,
        timeStarted: Long?,
        timeEnded: Long?,
        comment: String?,
    ): WebApiRecordUpdateResult = synchronized(lock) {
        val previous = records.firstOrNull { it.id == recordId }
            ?: return@synchronized WebApiRecordUpdateResult.NotFound
        val updated = previous.copy(
            typeId = typeId ?: previous.typeId,
            timeStarted = timeStarted ?: previous.timeStarted,
            timeEnded = timeEnded ?: previous.timeEnded,
            comment = comment ?: previous.comment,
        )
        if (updated.timeEnded <= updated.timeStarted) {
            return@synchronized WebApiRecordUpdateResult.InvalidRange
        }

        updatedRecords += previous to updated
        records.remove(previous)
        records += updated
        WebApiRecordUpdateResult.Updated(updated)
    }

    override suspend fun removeRecord(record: Record) {
        removedRecords += record
        records.removeIf { it.id == record.id }
    }

    data class StartedActivity(
        val typeId: Long,
        val timeStarted: Long?,
        val comment: String,
    )

    private companion object {
        val DEFAULT_RECORDS = listOf(
            Record(
                id = 1L,
                typeId = 3L,
                timeStarted = 1_000L,
                timeEnded = 2_000L,
                comment = "",
                tags = emptyList(),
            ),
            Record(
                id = 2L,
                typeId = 5L,
                timeStarted = 3_000L,
                timeEnded = 4_000L,
                comment = "later",
                tags = emptyList(),
            ),
            Record(
                id = 3L,
                typeId = 3L,
                timeStarted = 500L,
                timeEnded = 900L,
                comment = "boundary",
                tags = emptyList(),
            ),
        )
    }
}
