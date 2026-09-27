package com.example.util.simpletimetracker.api

import com.example.util.simpletimetracker.wear_api.WearActivityDTO
import com.example.util.simpletimetracker.wear_api.WearCommunicationAPI
import com.example.util.simpletimetracker.wear_api.WearCurrentActivityDTO
import com.example.util.simpletimetracker.wear_api.WearCurrentStateDTO
import com.example.util.simpletimetracker.wear_api.WearRecordRepeatResponse
import com.example.util.simpletimetracker.wear_api.WearSetSettingsRequest
import com.example.util.simpletimetracker.wear_api.WearSettingsDTO
import com.example.util.simpletimetracker.wear_api.WearShouldShowTagSelectionRequest
import com.example.util.simpletimetracker.wear_api.WearShouldShowTagSelectionResponse
import com.example.util.simpletimetracker.wear_api.WearShouldShowTagValueSelectionRequest
import com.example.util.simpletimetracker.wear_api.WearShouldShowTagValueSelectionResponse
import com.example.util.simpletimetracker.wear_api.WearStartActivityRequest
import com.example.util.simpletimetracker.wear_api.WearStatisticsDTO
import com.example.util.simpletimetracker.wear_api.WearStatisticsRequest
import com.example.util.simpletimetracker.wear_api.WearStopActivityRequest
import com.example.util.simpletimetracker.wear_api.WearTagDTO
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Hand written fake of [WearCommunicationAPI] with canned data.
 * Only the methods that [WebApiAdapter] calls are implemented.
 */
class FakeWearCommunicationAPI : WearCommunicationAPI {

    @Volatile
    var activities: List<WearActivityDTO> = DEFAULT_ACTIVITIES

    @Volatile
    var currentState: WearCurrentStateDTO = DEFAULT_STATE

    /** When set, every implemented method throws this instead of returning data. */
    @Volatile
    var failure: RuntimeException? = null

    val startRequests: MutableList<WearStartActivityRequest> = CopyOnWriteArrayList()
    val stopRequests: MutableList<WearStopActivityRequest> = CopyOnWriteArrayList()
    var repeatCalls: Int = 0

    override suspend fun queryActivities(): List<WearActivityDTO> {
        failure?.let { throw it }
        return activities
    }

    override suspend fun queryCurrentActivities(): WearCurrentStateDTO {
        failure?.let { throw it }
        return currentState
    }

    override suspend fun startActivity(request: WearStartActivityRequest) {
        failure?.let { throw it }
        startRequests.add(request)
    }

    override suspend fun stopActivity(request: WearStopActivityRequest) {
        failure?.let { throw it }
        stopRequests.add(request)
    }

    override suspend fun queryStatistics(request: WearStatisticsRequest): List<WearStatisticsDTO>? =
        unused()

    override suspend fun repeatActivity(): WearRecordRepeatResponse {
        failure?.let { throw it }
        repeatCalls++
        return WearRecordRepeatResponse(WearRecordRepeatResponse.ActionResult.STARTED)
    }

    override suspend fun queryTagsForActivity(activityId: Long): List<WearTagDTO> = unused()

    override suspend fun queryShouldShowTagSelection(
        request: WearShouldShowTagSelectionRequest,
    ): WearShouldShowTagSelectionResponse? = unused()

    override suspend fun queryShouldShowTagValueSelection(
        request: WearShouldShowTagValueSelectionRequest,
    ): WearShouldShowTagValueSelectionResponse? = unused()

    override suspend fun querySettings(): WearSettingsDTO = unused()

    override suspend fun setSettings(settings: WearSetSettingsRequest) = unused<Unit>()

    override suspend fun openPhoneApp() = unused<Unit>()

    private fun <T> unused(): T =
        throw UnsupportedOperationException("WebApiAdapter must not call this method")

    companion object {
        const val RUNNING_STARTED_AT = 1_700_000_000_000L

        val DEFAULT_ACTIVITIES: List<WearActivityDTO> = listOf(
            WearActivityDTO(id = 1L, name = "Reading", icon = "ic_book", color = 0xFF0000L),
            WearActivityDTO(id = 5L, name = "Running", icon = "ic_run", color = 0x00FF00L),
            WearActivityDTO(id = 42L, name = "Sleeping", icon = "ic_bed", color = 0x0000FFL),
        )

        val DEFAULT_STATE: WearCurrentStateDTO = WearCurrentStateDTO(
            currentActivities = listOf(
                WearCurrentActivityDTO(
                    id = 5L,
                    startedAt = RUNNING_STARTED_AT,
                    tags = emptyList(),
                ),
            ),
            lastRecords = emptyList(),
            suggestionIds = emptyList(),
        )
    }
}
