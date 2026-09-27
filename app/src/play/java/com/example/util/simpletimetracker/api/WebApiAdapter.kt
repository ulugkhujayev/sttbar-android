package com.example.util.simpletimetracker.api

import com.example.util.simpletimetracker.domain.extension.dropMillis
import com.example.util.simpletimetracker.domain.record.model.Record
import com.example.util.simpletimetracker.domain.webApi.interactor.WebApiTokenInteractor
import com.example.util.simpletimetracker.wear_api.WearCommunicationAPI
import com.example.util.simpletimetracker.wear_api.WearStopActivityRequest
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.security.MessageDigest
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Local HTTP API, see the routes in [serve].
 * Reads reuse the Wear OS API, record edits go through [WebApiRecordsInteractor].
 * Every route except GET /api/ping and OPTIONS needs "Authorization: Bearer <token>".
 */
@Singleton
class WebApiAdapter @Inject constructor(
    private val wearApi: WearCommunicationAPI,
    private val tokenInteractor: WebApiTokenInteractor,
    private val recordsInteractor: WebApiRecordsInteractor,
) : NanoHTTPD(PORT) {

    @Synchronized
    fun startWebApi(
        readTimeout: Int = SOCKET_READ_TIMEOUT,
        daemon: Boolean = true,
    ) {
        if (isAlive) return

        setAsyncRunner(createBoundedAsyncRunner())
        start(readTimeout, daemon)
    }

    @Synchronized
    fun stopWebApi() {
        stop()
    }

    /**
     * Small fixed worker pool instead of NanoHTTPD's unbounded thread-per-request runner.
     * Requests over the queue capacity are dropped, so a flood cannot exhaust threads.
     */
    internal fun createBoundedAsyncRunner(
        corePoolSize: Int = CORE_POOL_SIZE,
        maxPoolSize: Int = MAX_POOL_SIZE,
        queueCapacity: Int = REQUEST_QUEUE_CAPACITY,
    ): AsyncRunner {
        val requestExecutor = ThreadPoolExecutor(
            corePoolSize,
            maxPoolSize,
            0L,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(queueCapacity),
            ThreadPoolExecutor.AbortPolicy(),
        )
        val runningHandlers = CopyOnWriteArrayList<ClientHandler>()

        return object : AsyncRunner {
            override fun closeAll() {
                runningHandlers.forEach(ClientHandler::close)
                runningHandlers.clear()
                requestExecutor.shutdownNow()
            }

            override fun closed(clientHandler: ClientHandler) {
                runningHandlers.remove(clientHandler)
            }

            override fun exec(clientHandler: ClientHandler) {
                runningHandlers.add(clientHandler)
                try {
                    requestExecutor.execute(clientHandler)
                } catch (e: RejectedExecutionException) {
                    runningHandlers.remove(clientHandler)
                    clientHandler.close()
                    Timber.w("Web API request rejected because the worker queue is full")
                }
            }
        }
    }

    override fun serve(session: IHTTPSession): Response {
        val headers = mapOf(
            "Access-Control-Allow-Origin" to "*",
            "Access-Control-Allow-Methods" to "GET, POST, PUT, DELETE, OPTIONS",
            "Access-Control-Allow-Headers" to "Content-Type, Authorization, Idempotency-Key",
        )

        if (session.method == Method.OPTIONS) {
            return jsonResponse(Response.Status.OK, "", headers)
        }

        if (session.uri == PING_PATH && session.method == Method.GET) {
            return jsonResponse(Response.Status.OK, PING_BODY, headers)
        }

        if (!isAuthorized(session)) {
            return jsonError(Response.Status.UNAUTHORIZED, "Unauthorized", headers)
        }

        return try {
            runBlocking {
                when {
                    // GET /api/activities
                    session.uri == "/api/activities" && session.method == Method.GET -> {
                        getAllActivities(headers)
                    }
                    // GET /api/running
                    session.uri == "/api/running" && session.method == Method.GET -> {
                        getRunningActivities(headers)
                    }
                    // GET /api/records?from=:from&to=:to
                    session.uri == RECORDS_PATH && session.method == Method.GET -> {
                        getRecords(session, headers)
                    }
                    // POST /api/records
                    session.uri == RECORDS_PATH && session.method == Method.POST -> {
                        addRecord(session, headers)
                    }
                    // PUT /api/records/:id
                    session.uri.startsWith("$RECORDS_PATH/") && session.method == Method.PUT -> {
                        updateRecord(session, headers)
                    }
                    // DELETE /api/records/:id
                    session.uri.startsWith("$RECORDS_PATH/") && session.method == Method.DELETE -> {
                        deleteRecord(session, headers)
                    }
                    // POST /api/start/:id
                    session.uri.startsWith("/api/start/") && session.method == Method.POST -> {
                        startActivity(session, headers)
                    }
                    // POST /api/stop/:id
                    session.uri.startsWith("/api/stop/") && session.method == Method.POST -> {
                        stopActivity(session, headers)
                    }
                    // POST /api/repeat
                    session.uri == "/api/repeat" && session.method == Method.POST -> {
                        repeatActivity(headers)
                    }
                    else -> {
                        jsonError(Response.Status.NOT_FOUND, "Not found", headers)
                    }
                }
            }
        } catch (e: BadRequestException) {
            jsonError(Response.Status.BAD_REQUEST, e.message.orEmpty(), headers)
        } catch (e: Exception) {
            Timber.e(e, "Web API request failed")
            // Never echo exception details, they can contain data.
            jsonError(Response.Status.INTERNAL_ERROR, "Internal error", headers)
        }
    }

    private fun isAuthorized(session: IHTTPSession): Boolean {
        val providedToken = session.header("Authorization")
            ?.takeIf { it.startsWith(BEARER_PREFIX) }
            ?.substring(BEARER_PREFIX.length)
            ?: return false
        val expectedToken = runBlocking { tokenInteractor.getOrCreate() }
        // Constant time comparison.
        return MessageDigest.isEqual(
            expectedToken.toByteArray(Charsets.UTF_8),
            providedToken.toByteArray(Charsets.UTF_8),
        )
    }

    private suspend fun getAllActivities(headers: Map<String, String>): Response {
        val activities = wearApi.queryActivities()
        val currentState = wearApi.queryCurrentActivities()
        val runningIds = currentState.currentActivities.map { it.id }.toSet()

        val json = JSONArray()
        activities.forEach { activity ->
            json.put(
                JSONObject().apply {
                    put("id", activity.id)
                    put("name", activity.name)
                    put("icon", activity.icon)
                    put("color", activity.color)
                    put("isRunning", runningIds.contains(activity.id))
                },
            )
        }

        return jsonResponse(Response.Status.OK, json.toString(), headers)
    }

    private suspend fun getRunningActivities(headers: Map<String, String>): Response {
        val currentState = wearApi.queryCurrentActivities()
        val activities = wearApi.queryActivities().associateBy { it.id }

        val json = JSONArray()
        currentState.currentActivities.forEach { current ->
            val activity = activities[current.id]
            json.put(
                JSONObject().apply {
                    put("id", current.id)
                    put("name", activity?.name ?: UNKNOWN_TYPE_NAME)
                    put("timeStarted", current.startedAt)
                    put(
                        "duration",
                        (System.currentTimeMillis() - current.startedAt).coerceAtLeast(0L),
                    )
                },
            )
        }

        return jsonResponse(Response.Status.OK, json.toString(), headers)
    }

    private suspend fun startActivity(
        session: IHTTPSession,
        headers: Map<String, String>,
    ): Response {
        val typeId = session.uri.substringAfterLast("/").toLongOrNull()
            ?: return jsonError(Response.Status.BAD_REQUEST, "Invalid ID", headers)

        // Optional body: {"timeStarted": ms, "comment": "..."}.
        val options = session.readOptionalJsonBody()
        val timeStarted = options?.optionalLong("timeStarted")?.dropMillis()
        val comment = options?.optionalString("comment").orEmpty()
        if (timeStarted != null && timeStarted > System.currentTimeMillis().dropMillis()) {
            return jsonError(
                Response.Status.BAD_REQUEST,
                "timeStarted must not be in the future",
                headers,
            )
        }
        if (recordsInteractor.getTypeName(typeId) == null) {
            return jsonError(Response.Status.NOT_FOUND, "unknown typeId", headers)
        }

        recordsInteractor.startActivity(
            typeId = typeId,
            timeStarted = timeStarted,
            comment = comment,
        )

        return jsonResponse(Response.Status.OK, SUCCESS_BODY, headers)
    }

    private suspend fun stopActivity(
        session: IHTTPSession,
        headers: Map<String, String>,
    ): Response {
        val typeId = session.uri.substringAfterLast("/").toLongOrNull()
            ?: return jsonError(Response.Status.BAD_REQUEST, "Invalid ID", headers)

        wearApi.stopActivity(WearStopActivityRequest(id = typeId))

        return jsonResponse(Response.Status.OK, SUCCESS_BODY, headers)
    }

    private suspend fun repeatActivity(headers: Map<String, String>): Response {
        val result = wearApi.repeatActivity().result

        return jsonResponse(
            Response.Status.OK,
            JSONObject().put("success", true).put("result", result.name).toString(),
            headers,
        )
    }

    private suspend fun getRecords(
        session: IHTTPSession,
        headers: Map<String, String>,
    ): Response {
        val from = session.parameters["from"]?.singleOrNull()?.toLongOrNull()
        val to = session.parameters["to"]?.singleOrNull()?.toLongOrNull()
        if (from == null || to == null || from >= to) {
            return jsonError(Response.Status.BAD_REQUEST, "invalid from/to", headers)
        }

        val records = recordsInteractor.getRecords(from, to)
            .sortedByDescending(Record::timeStarted)
        val typeNames = records.map(Record::typeId).distinct().associateWith { typeId ->
            recordsInteractor.getTypeName(typeId) ?: UNKNOWN_TYPE_NAME
        }
        val body = JSONArray()
        records.forEach { record ->
            body.put(record.toJson(typeNames.getValue(record.typeId)))
        }

        return jsonResponse(Response.Status.OK, body.toString(), headers)
    }

    private suspend fun addRecord(
        session: IHTTPSession,
        headers: Map<String, String>,
    ): Response {
        val body = session.readJsonBody()
        val typeId = body.requireLong("typeId")
        val timeStarted = body.requireLong("timeStarted").dropMillis()
        val timeEnded = body.requireLong("timeEnded").dropMillis()
        val comment = body.optionalString("comment").orEmpty()
        // A client that retries an upload marks the request with a client id
        // (body field, or the Idempotency-Key header). Such inserts are deduplicated.
        val clientId = body.optionalString("clientId") ?: session.header("Idempotency-Key")
        if (clientId != null && clientId.length > MAX_CLIENT_ID_LENGTH) {
            return jsonError(Response.Status.BAD_REQUEST, "invalid clientId", headers)
        }
        if (timeEnded <= timeStarted) {
            return jsonError(
                Response.Status.BAD_REQUEST,
                "timeEnded must be greater than timeStarted",
                headers,
            )
        }
        val typeName = recordsInteractor.getTypeName(typeId)
            ?: return jsonError(Response.Status.NOT_FOUND, "unknown typeId", headers)

        val record = recordsInteractor.addRecord(
            record = Record(
                typeId = typeId,
                timeStarted = timeStarted,
                timeEnded = timeEnded,
                comment = comment,
                tags = emptyList(),
            ),
            deduplicate = clientId != null,
        )

        return jsonResponse(Response.Status.OK, record.toJson(typeName).toString(), headers)
    }

    private suspend fun updateRecord(
        session: IHTTPSession,
        headers: Map<String, String>,
    ): Response {
        val recordId = session.recordId()
            ?: return jsonError(Response.Status.NOT_FOUND, "unknown id", headers)
        val body = session.readJsonBody()
        val typeId = body.optionalLong("typeId")
        val timeStarted = body.optionalLong("timeStarted")?.dropMillis()
        val timeEnded = body.optionalLong("timeEnded")?.dropMillis()
        val comment = body.optionalString("comment")
        if (typeId != null && recordsInteractor.getTypeName(typeId) == null) {
            return jsonError(Response.Status.NOT_FOUND, "unknown typeId", headers)
        }

        val result = recordsInteractor.updateRecord(
            recordId = recordId,
            typeId = typeId,
            timeStarted = timeStarted,
            timeEnded = timeEnded,
            comment = comment,
        )

        return when (result) {
            is WebApiRecordUpdateResult.Updated -> {
                val typeName = recordsInteractor.getTypeName(result.record.typeId)
                    ?: UNKNOWN_TYPE_NAME
                jsonResponse(
                    Response.Status.OK,
                    result.record.toJson(typeName).toString(),
                    headers,
                )
            }
            WebApiRecordUpdateResult.NotFound -> {
                jsonError(Response.Status.NOT_FOUND, "unknown id", headers)
            }
            WebApiRecordUpdateResult.InvalidRange -> {
                jsonError(
                    Response.Status.BAD_REQUEST,
                    "timeEnded must be greater than timeStarted",
                    headers,
                )
            }
        }
    }

    private suspend fun deleteRecord(
        session: IHTTPSession,
        headers: Map<String, String>,
    ): Response {
        val recordId = session.recordId()
            ?: return jsonError(Response.Status.NOT_FOUND, "unknown id", headers)
        val record = recordsInteractor.getRecord(recordId)
            ?: return jsonError(Response.Status.NOT_FOUND, "unknown id", headers)

        recordsInteractor.removeRecord(record)

        return jsonResponse(Response.Status.OK, SUCCESS_BODY, headers)
    }

    private fun IHTTPSession.header(name: String): String? {
        // NanoHTTPD lower cases header names, match case insensitively anyway.
        return headers.entries
            .firstOrNull { (key, _) -> key.equals(name, ignoreCase = true) }
            ?.value
    }

    private fun IHTTPSession.readJsonBody(): JSONObject {
        return readOptionalJsonBody() ?: throw BadRequestException("missing Content-Length")
    }

    /**
     * Reads the body bytes directly and decodes UTF-8. NanoHTTPD's parseBody
     * guesses the charset from Content-Type (US-ASCII when absent), which
     * destroys non-ASCII comments, and it may spool to temp files.
     * Returns null when there is no Content-Length or the body is blank.
     */
    private fun IHTTPSession.readOptionalJsonBody(): JSONObject? {
        val contentLengthHeader = header("Content-Length")
        val contentLength = contentLengthHeader?.trim()?.toLongOrNull()
        if (contentLengthHeader != null && (contentLength == null || contentLength < 0L)) {
            throw BadRequestException("invalid Content-Length")
        }
        if (contentLength == null) return null
        if (contentLength > MAX_BODY_BYTES) {
            throw BadRequestException("request body too large")
        }

        val length = contentLength.toInt()
        val buffer = ByteArray(length)
        var read = 0
        val readStartedAt = System.nanoTime()
        try {
            while (read < length) {
                if (System.nanoTime() - readStartedAt >= BODY_READ_TIMEOUT_NANOS) {
                    throw BadRequestException("request body timeout")
                }
                val n = inputStream.read(buffer, read, length - read)
                if (n < 0) break
                read += n
            }
        } catch (e: BadRequestException) {
            throw e
        } catch (e: Exception) {
            throw BadRequestException("invalid request body")
        }
        if (read < length) {
            throw BadRequestException("invalid request body")
        }

        val body = String(buffer, Charsets.UTF_8)
        if (body.isBlank()) return null
        return try {
            JSONObject(body)
        } catch (e: Exception) {
            throw BadRequestException("invalid JSON body")
        }
    }

    private fun JSONObject.requireLong(name: String): Long {
        if (!has(name) || isNull(name)) throw BadRequestException("invalid $name")
        return when (val value = get(name)) {
            is Byte -> value.toLong()
            is Short -> value.toLong()
            is Int -> value.toLong()
            is Long -> value
            else -> throw BadRequestException("invalid $name")
        }
    }

    private fun JSONObject.optionalLong(name: String): Long? {
        return if (has(name)) requireLong(name) else null
    }

    private fun JSONObject.optionalString(name: String): String? {
        if (!has(name) || isNull(name)) return null
        return get(name) as? String ?: throw BadRequestException("invalid $name")
    }

    private fun IHTTPSession.recordId(): Long? {
        return uri.removePrefix("$RECORDS_PATH/")
            .takeIf { it.isNotEmpty() && '/' !in it }
            ?.toLongOrNull()
    }

    private fun Record.toJson(typeName: String): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("typeId", typeId)
            put("name", typeName)
            put("timeStarted", timeStarted)
            put("timeEnded", timeEnded)
            put("comment", comment)
        }
    }

    private fun jsonError(
        status: Response.IStatus,
        error: String,
        headers: Map<String, String>,
    ): Response {
        return jsonResponse(status, JSONObject().put("error", error).toString(), headers)
    }

    private fun jsonResponse(
        status: Response.IStatus,
        body: String,
        headers: Map<String, String>,
    ): Response {
        return newFixedLengthResponse(status, MIME_JSON, body).apply {
            headers.forEach { (key, value) -> addHeader(key, value) }
        }
    }

    private class BadRequestException(message: String) : IllegalArgumentException(message)

    companion object {
        const val PORT = 8080

        private const val MIME_JSON = "application/json"
        private const val CORE_POOL_SIZE = 4
        private const val MAX_POOL_SIZE = 4
        private const val REQUEST_QUEUE_CAPACITY = 8
        private const val BEARER_PREFIX = "Bearer "
        private const val PING_PATH = "/api/ping"
        private const val PING_BODY = """{"app":"simpletimetracker","api":1}"""
        private const val SUCCESS_BODY = """{"success":true}"""
        private const val RECORDS_PATH = "/api/records"
        private const val MAX_BODY_BYTES = 64 * 1024
        private const val MAX_CLIENT_ID_LENGTH = 128
        private const val BODY_READ_TIMEOUT_NANOS = 8_000_000_000L
        private const val UNKNOWN_TYPE_NAME = "Unknown"
    }
}
