package com.example.util.simpletimetracker.api

import com.example.util.simpletimetracker.domain.extension.dropMillis
import com.example.util.simpletimetracker.domain.notifications.interactor.UpdateExternalViewsInteractor
import com.example.util.simpletimetracker.domain.record.interactor.AddRecordMediator
import com.example.util.simpletimetracker.domain.record.interactor.AddRunningRecordMediator
import com.example.util.simpletimetracker.domain.record.interactor.RecordInteractor
import com.example.util.simpletimetracker.domain.record.interactor.RecordInteractor.GetParam
import com.example.util.simpletimetracker.domain.record.interactor.RemoveRecordMediator
import com.example.util.simpletimetracker.domain.record.interactor.RunningRecordInteractor
import com.example.util.simpletimetracker.domain.record.model.Range
import com.example.util.simpletimetracker.domain.record.model.Record
import com.example.util.simpletimetracker.domain.record.model.RecordBase
import com.example.util.simpletimetracker.domain.recordType.interactor.RecordTypeInteractor
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Record operations the Web API exposes. Interface so that the server can be
 * unit tested with a fake.
 */
interface WebApiRecordsInteractor {

    suspend fun getRecords(from: Long, to: Long): List<Record>

    suspend fun getRecord(id: Long): Record?

    suspend fun getTypeName(typeId: Long): String?

    /** [timeStarted] null starts now. */
    suspend fun startActivity(typeId: Long, timeStarted: Long?, comment: String)

    /**
     * Inserts [record] and returns it with its id.
     * With [deduplicate] an equal record (same type, times and comment) is returned instead
     * of inserting a second one, so that a client can safely retry an upload.
     */
    suspend fun addRecord(record: Record, deduplicate: Boolean): Record

    suspend fun updateRecord(
        recordId: Long,
        typeId: Long?,
        timeStarted: Long?,
        timeEnded: Long?,
        comment: String?,
    ): WebApiRecordUpdateResult

    suspend fun removeRecord(record: Record)
}

sealed interface WebApiRecordUpdateResult {
    data class Updated(val record: Record) : WebApiRecordUpdateResult

    data object NotFound : WebApiRecordUpdateResult

    data object InvalidRange : WebApiRecordUpdateResult
}

@Singleton
class WebApiRecordsInteractorImpl @Inject constructor(
    private val recordInteractor: RecordInteractor,
    private val recordTypeInteractor: RecordTypeInteractor,
    private val runningRecordInteractor: RunningRecordInteractor,
    private val addRecordMediator: AddRecordMediator,
    private val addRunningRecordMediator: AddRunningRecordMediator,
    private val removeRecordMediator: RemoveRecordMediator,
    private val externalViewsInteractor: UpdateExternalViewsInteractor,
) : WebApiRecordsInteractor {

    private val addMutex = Mutex()

    override suspend fun getRecords(from: Long, to: Long): List<Record> {
        return recordInteractor.getWithParams(
            GetParam.FromRange(Range(timeStarted = from, timeEnded = to)),
        )
    }

    override suspend fun getRecord(id: Long): Record? {
        return recordInteractor.get(id)
    }

    override suspend fun getTypeName(typeId: Long): String? {
        return recordTypeInteractor.get(typeId)?.name
    }

    override suspend fun startActivity(typeId: Long, timeStarted: Long?, comment: String) {
        val type = recordTypeInteractor.get(typeId) ?: return
        // A retried request for a timer that is already running from that moment is a no-op.
        val running = runningRecordInteractor.get(typeId)
        if (timeStarted != null && running?.timeStarted?.dropMillis() == timeStarted) return

        // Same path as the Wear OS start.
        addRunningRecordMediator.startTimer(
            typeId = typeId,
            tags = emptyList(),
            comment = comment,
            timeStarted = timeStarted
                ?.let(AddRunningRecordMediator.StartTime::Timestamp)
                ?: AddRunningRecordMediator.StartTime.TakeCurrent,
        )
        if (type.defaultDuration > 0L) {
            externalViewsInteractor.onInstantRecordAdd()
        }
    }

    override suspend fun addRecord(record: Record, deduplicate: Boolean): Record {
        if (!deduplicate) return insert(record)

        return addMutex.withLock {
            findEqual(record) ?: insert(record)
        }
    }

    override suspend fun updateRecord(
        recordId: Long,
        typeId: Long?,
        timeStarted: Long?,
        timeEnded: Long?,
        comment: String?,
    ): WebApiRecordUpdateResult {
        val previous = recordInteractor.get(recordId)
            ?: return WebApiRecordUpdateResult.NotFound
        val updated = previous.copy(
            typeId = typeId ?: previous.typeId,
            timeStarted = timeStarted ?: previous.timeStarted,
            timeEnded = timeEnded ?: previous.timeEnded,
            comment = comment ?: previous.comment,
        )
        if (updated.timeEnded <= updated.timeStarted) {
            return WebApiRecordUpdateResult.InvalidRange
        }

        // Same path as the change record screen: adding with an id replaces the record.
        addRecordMediator.add(updated)
        if (previous.typeId != updated.typeId) {
            externalViewsInteractor.onRecordChangeType(listOf(previous.typeId))
        }
        return WebApiRecordUpdateResult.Updated(updated)
    }

    override suspend fun removeRecord(record: Record) {
        removeRecordMediator.remove(
            recordIds = listOf(record.id),
            typeIds = listOf(record.typeId),
            tagIds = record.tags.map(RecordBase.Tag::tagId),
        )
    }

    private suspend fun insert(record: Record): Record {
        val recordId = recordInteractor.add(record)
        addRecordMediator.doAfterAdd(
            typeIds = listOf(record.typeId),
            tagIds = record.tags.map(RecordBase.Tag::tagId),
        )
        return record.copy(id = recordId)
    }

    private suspend fun findEqual(record: Record): Record? {
        return getRecords(from = record.timeStarted, to = record.timeEnded).firstOrNull {
            it.typeId == record.typeId &&
                it.timeStarted == record.timeStarted &&
                it.timeEnded == record.timeEnded &&
                it.comment == record.comment
        }
    }
}
