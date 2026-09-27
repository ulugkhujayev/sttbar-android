package com.example.util.simpletimetracker.api

import com.example.util.simpletimetracker.domain.notifications.interactor.UpdateExternalViewsInteractor
import com.example.util.simpletimetracker.domain.record.interactor.AddRecordMediator
import com.example.util.simpletimetracker.domain.record.interactor.AddRunningRecordMediator
import com.example.util.simpletimetracker.domain.record.interactor.RecordInteractor
import com.example.util.simpletimetracker.domain.record.interactor.RemoveRecordMediator
import com.example.util.simpletimetracker.domain.record.interactor.RunningRecordInteractor
import com.example.util.simpletimetracker.domain.record.model.Record
import com.example.util.simpletimetracker.domain.record.model.RunningRecord
import com.example.util.simpletimetracker.domain.recordType.interactor.RecordTypeInteractor
import com.example.util.simpletimetracker.domain.recordType.model.RecordType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions

class WebApiRecordsInteractorImplTest {

    private val recordInteractor: RecordInteractor = mock()
    private val recordTypeInteractor: RecordTypeInteractor = mock {
        onBlocking { get(TYPE_ID) } doReturn RecordType(
            id = TYPE_ID,
            name = "Work",
            icon = "",
            color = com.example.util.simpletimetracker.domain.color.model.AppColor(0, ""),
            defaultDuration = 0L,
            note = "",
        )
    }
    private val runningRecordInteractor: RunningRecordInteractor = mock()
    private val addRecordMediator: AddRecordMediator = mock()
    private val addRunningRecordMediator: AddRunningRecordMediator = mock()
    private val removeRecordMediator: RemoveRecordMediator = mock()
    private val externalViewsInteractor: UpdateExternalViewsInteractor = mock()

    private val subject = WebApiRecordsInteractorImpl(
        recordInteractor = recordInteractor,
        recordTypeInteractor = recordTypeInteractor,
        runningRecordInteractor = runningRecordInteractor,
        addRecordMediator = addRecordMediator,
        addRunningRecordMediator = addRunningRecordMediator,
        removeRecordMediator = removeRecordMediator,
        externalViewsInteractor = externalViewsInteractor,
    )

    @Test
    fun addRecordInsertsAndReturnsId() = runBlocking {
        // Given
        val record = record(id = 0L)
        recordInteractor.stub { onBlocking { add(record) } doReturn 7L }

        // When
        val result = subject.addRecord(record, deduplicate = false)

        // Then
        assertEquals(7L, result.id)
        verify(recordInteractor).add(record)
        verify(addRecordMediator).doAfterAdd(typeIds = listOf(TYPE_ID), tagIds = emptyList())
    }

    @Test
    fun addRecordWithDeduplicateReturnsEqualExistingRecord() = runBlocking {
        // Given
        val record = record(id = 0L)
        val existing = record(id = 9L)
        val other = record(id = 10L, comment = "different")
        recordInteractor.stub {
            onBlocking { getWithParams(any()) } doReturn listOf(other, existing)
        }

        // When
        val result = subject.addRecord(record, deduplicate = true)

        // Then
        assertSame(existing, result)
        verify(recordInteractor, never()).add(any())
        verifyNoInteractions(addRecordMediator)
    }

    @Test
    fun addRecordWithDeduplicateInsertsWhenNothingEqualExists() = runBlocking {
        // Given
        val record = record(id = 0L)
        recordInteractor.stub {
            onBlocking { getWithParams(any()) } doReturn listOf(record(id = 10L, comment = "different"))
            onBlocking { add(record) } doReturn 11L
        }

        // When
        val result = subject.addRecord(record, deduplicate = true)

        // Then
        assertEquals(11L, result.id)
        verify(recordInteractor).add(record)
        Unit
    }

    @Test
    fun updateRecordReplacesThroughAddMediatorAndReportsTypeChange() = runBlocking {
        // Given
        val previous = record(id = 5L)
        recordInteractor.stub { onBlocking { get(5L) } doReturn previous }

        // When
        val result = subject.updateRecord(
            recordId = 5L,
            typeId = 8L,
            timeStarted = null,
            timeEnded = 2_500L,
            comment = null,
        )

        // Then
        val expected = previous.copy(typeId = 8L, timeEnded = 2_500L)
        assertEquals(WebApiRecordUpdateResult.Updated(expected), result)
        verify(addRecordMediator).add(expected)
        verify(externalViewsInteractor).onRecordChangeType(listOf(TYPE_ID))
    }

    @Test
    fun updateRecordWithSameTypeDoesNotReportTypeChange() = runBlocking {
        // Given
        val previous = record(id = 5L)
        recordInteractor.stub { onBlocking { get(5L) } doReturn previous }

        // When
        subject.updateRecord(
            recordId = 5L,
            typeId = null,
            timeStarted = null,
            timeEnded = null,
            comment = "new",
        )

        // Then
        verify(addRecordMediator).add(previous.copy(comment = "new"))
        verify(externalViewsInteractor, never()).onRecordChangeType(any())
    }

    @Test
    fun updateRecordRejectsInvalidRangeAndUnknownId() = runBlocking {
        // Given
        recordInteractor.stub { onBlocking { get(5L) } doReturn record(id = 5L) }

        // When
        val invalid = subject.updateRecord(5L, null, 2_000L, null, null)
        val unknown = subject.updateRecord(6L, null, null, null, null)

        // Then
        assertEquals(WebApiRecordUpdateResult.InvalidRange, invalid)
        assertEquals(WebApiRecordUpdateResult.NotFound, unknown)
        verifyNoInteractions(addRecordMediator)
    }

    @Test
    fun startActivityStartsTimerFromGivenTime() = runBlocking {
        // When
        subject.startActivity(typeId = TYPE_ID, timeStarted = 1_000L, comment = "c")

        // Then
        verify(addRunningRecordMediator).startTimer(
            typeId = eq(TYPE_ID),
            tags = eq(emptyList()),
            comment = eq("c"),
            timeStarted = eq(AddRunningRecordMediator.StartTime.Timestamp(1_000L)),
            updateNotificationSwitch = any(),
            checkDefaultDuration = any(),
            useSelectedTags = any(),
        )
        verify(externalViewsInteractor, never()).onInstantRecordAdd()
    }

    @Test
    fun startActivityWithoutTimeStartsNow() = runBlocking {
        // When
        subject.startActivity(typeId = TYPE_ID, timeStarted = null, comment = "")

        // Then
        verify(addRunningRecordMediator).startTimer(
            typeId = eq(TYPE_ID),
            tags = eq(emptyList()),
            comment = eq(""),
            timeStarted = eq(AddRunningRecordMediator.StartTime.TakeCurrent),
            updateNotificationSwitch = any(),
            checkDefaultDuration = any(),
            useSelectedTags = any(),
        )
    }

    @Test
    fun startActivityRetryForRunningTimerIsNoOp() = runBlocking {
        // Given
        runningRecordInteractor.stub {
            onBlocking { get(TYPE_ID) } doReturn RunningRecord(
                id = TYPE_ID,
                timeStarted = 1_000L,
                comment = "",
                tags = emptyList(),
            )
        }

        // When
        subject.startActivity(typeId = TYPE_ID, timeStarted = 1_000L, comment = "")
        subject.startActivity(typeId = TYPE_ID, timeStarted = 3_000L, comment = "")

        // Then
        verify(addRunningRecordMediator, times(1)).startTimer(
            typeId = any(),
            tags = any(),
            comment = any(),
            timeStarted = eq(AddRunningRecordMediator.StartTime.Timestamp(3_000L)),
            updateNotificationSwitch = any(),
            checkDefaultDuration = any(),
            useSelectedTags = any(),
        )
    }

    @Test
    fun startActivityIgnoresUnknownType() = runBlocking {
        // When
        subject.startActivity(typeId = 404L, timeStarted = null, comment = "")

        // Then
        verifyNoInteractions(addRunningRecordMediator)
    }

    private fun record(id: Long, comment: String = "") = Record(
        id = id,
        typeId = TYPE_ID,
        timeStarted = 1_000L,
        timeEnded = 2_000L,
        comment = comment,
        tags = emptyList(),
    )

    private fun <T : Any> T.stub(block: org.mockito.kotlin.KStubbing<T>.(T) -> Unit) {
        org.mockito.kotlin.KStubbing(this).block(this)
    }

    companion object {
        private const val TYPE_ID = 3L
    }
}
