package com.example.util.simpletimetracker.api

import com.example.util.simpletimetracker.domain.prefs.interactor.PrefsInteractor
import com.example.util.simpletimetracker.domain.webApi.interactor.WebApiTokenInteractor
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * JVM tests that drive the real NanoHTTPD server in [WebApiAdapter] over real HTTP.
 *
 * [WebApiAdapter] uses port 8080, so the port cannot be randomized. Each test binds and
 * releases that one port; NanoHTTPD sets SO_REUSEADDR, and [startServer] retries the bind so
 * a lingering socket does not break the run.
 */
class WebApiAdapterTest {

    private lateinit var fake: FakeWearCommunicationAPI
    private lateinit var records: FakeWebApiRecordsInteractor
    private lateinit var adapter: WebApiAdapter

    @Before
    fun setUp() {
        // Avoid connection reuse so every test leaves the port clean.
        System.setProperty("http.keepAlive", "false")
        fake = FakeWearCommunicationAPI()
        records = FakeWebApiRecordsInteractor()
        val prefsInteractor: PrefsInteractor = mock {
            onBlocking { getWebApiToken() } doReturn TEST_TOKEN
        }
        adapter = WebApiAdapter(
            wearApi = fake,
            tokenInteractor = WebApiTokenInteractor(prefsInteractor),
            recordsInteractor = records,
        )
        startServer(adapter)
    }

    @After
    fun tearDown() {
        adapter.stop()
    }

    // Case 1: no Authorization header -> 401 everywhere.

    @Test
    fun missingTokenIsRejectedOnEveryRoute() {
        val routes = listOf(
            "GET" to "/api/activities",
            "GET" to "/api/running",
            "POST" to "/api/start/1",
            "POST" to "/api/stop/1",
            "GET" to "/api/records?from=0&to=1",
            "POST" to "/api/records",
            "PUT" to "/api/records/1",
            "DELETE" to "/api/records/1",
            "POST" to "/api/repeat",
            "POST" to "/api/ping",
            "GET" to "/api/definitely-not-a-route",
        )
        routes.forEach { (method, path) ->
            val result = call(method, path)
            assertEquals("$method $path without token", 401, result.code)
            assertTrue(
                "$method $path body should be the JSON error, was: ${result.body}",
                result.body.contains("\"error\""),
            )
        }
    }

    /**
     * Ground truth for the 401 body on POST, read straight off the socket.
     * HttpURLConnection in fixed-length streaming mode hides the error body, so this
     * confirms the server really does write it.
     */
    @Test
    fun rawSocketPostWithoutTokenReturnsJsonErrorBody() {
        val raw = rawRequest(
            "POST /api/start/1 HTTP/1.1\r\n" +
                "Host: 127.0.0.1:$PORT\r\n" +
                "Content-Length: 0\r\n" +
                "Connection: close\r\n\r\n",
        )
        assertTrue("raw response was: $raw", raw.startsWith("HTTP/1.1 401"))
        assertTrue("raw response was: $raw", raw.contains("""{"error":"Unauthorized"}"""))
        assertTrue("CORS headers missing from 401: $raw", raw.contains("Access-Control-Allow-Origin: *"))
    }

    // Case 2: wrong token -> 401, correct token -> 200 with the fake's data.

    @Test
    fun pingSucceedsWithoutToken() {
        val result = call("GET", "/api/ping")
        assertEquals(200, result.code)

        val json = JSONObject(result.body)
        assertEquals("simpletimetracker", json.getString("app"))
        assertEquals(1, json.getInt("api"))
        assertEquals("*", result.header("Access-Control-Allow-Origin"))
    }

    @Test
    fun wrongTokenIsRejected() {
        listOf(
            "Bearer wrong-token",
            "Bearer ",
            TEST_TOKEN,
            "Basic $TEST_TOKEN",
            "bearer $TEST_TOKEN",
        ).forEach { header ->
            val result = call("GET", "/api/activities", authValue = header)
            assertEquals("header '$header' must not authenticate", 401, result.code)
        }
    }

    @Test
    fun activitiesReturnFakeData() {
        val result = call("GET", "/api/activities", authValue = bearer())
        assertEquals(200, result.code)

        val json = JSONArray(result.body)
        assertEquals(3, json.length())

        val first = json.getJSONObject(0)
        assertEquals(1L, first.getLong("id"))
        assertEquals("Reading", first.getString("name"))
        assertEquals("ic_book", first.getString("icon"))
        assertEquals(0xFF0000L, first.getLong("color"))
        assertFalse(first.getBoolean("isRunning"))

        val second = json.getJSONObject(1)
        assertEquals(5L, second.getLong("id"))
        assertEquals("Running", second.getString("name"))
        assertTrue("activity 5 is the running one", second.getBoolean("isRunning"))

        assertFalse(json.getJSONObject(2).getBoolean("isRunning"))
    }

    @Test
    fun runningReturnsFakeData() {
        val result = call("GET", "/api/running", authValue = bearer())
        assertEquals(200, result.code)

        val json = JSONArray(result.body)
        assertEquals(1, json.length())

        val running = json.getJSONObject(0)
        assertEquals(5L, running.getLong("id"))
        assertEquals("Running", running.getString("name"))
        assertEquals(FakeWearCommunicationAPI.RUNNING_STARTED_AT, running.getLong("timeStarted"))
        assertTrue("duration must be positive", running.getLong("duration") > 0L)
    }

    @Test
    fun runningWithoutCurrentActivitiesReturnsEmptyArray() {
        fake.currentState = FakeWearCommunicationAPI.DEFAULT_STATE.copy(currentActivities = emptyList())
        val result = call("GET", "/api/running", authValue = bearer())
        assertEquals(200, result.code)
        assertEquals(0, JSONArray(result.body).length())
    }

    @Test
    fun runningDurationIsClampedWhenClockMovesBackwards() {
        val futureStart = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(1)
        fake.currentState = FakeWearCommunicationAPI.DEFAULT_STATE.copy(
            currentActivities = listOf(
                FakeWearCommunicationAPI.DEFAULT_STATE.currentActivities.single().copy(
                    startedAt = futureStart,
                ),
            ),
        )

        val result = call("GET", "/api/running", authValue = bearer())

        assertEquals(200, result.code)
        assertEquals(0L, JSONArray(result.body).getJSONObject(0).getLong("duration"))
    }

    @Test
    fun recordsCrudUsesDomainInteractorAndReturnsFrozenJsonShape() {
        val listed = call("GET", "/api/records?from=900&to=3500", authValue = bearer())
        assertEquals(200, listed.code)
        assertTrue(listed.header("Content-Type").orEmpty().startsWith("application/json"))
        assertEquals("*", listed.header("Access-Control-Allow-Origin"))
        assertEquals(listOf(900L to 3_500L), records.getRanges)

        val listedJson = JSONArray(listed.body)
        assertEquals(2, listedJson.length())
        assertRecordJson(
            json = listedJson.getJSONObject(0),
            id = 2L,
            typeId = 5L,
            name = "Exercise",
            timeStarted = 3_000L,
            timeEnded = 4_000L,
            comment = "later",
        )
        assertRecordJson(
            json = listedJson.getJSONObject(1),
            id = 1L,
            typeId = 3L,
            name = "Work",
            timeStarted = 1_000L,
            timeEnded = 2_000L,
            comment = "",
        )

        val unicodeComment = "Работа с кофе ☕"
        val created = call(
            method = "POST",
            path = "/api/records",
            authValue = bearer(),
            body = JSONObject()
                .put("typeId", 3L)
                .put("timeStarted", 5_000L)
                .put("timeEnded", 6_000L)
                .put("comment", unicodeComment)
                .toString(),
        )
        assertEquals(200, created.code)
        assertRecordJson(
            json = JSONObject(created.body),
            id = 4L,
            typeId = 3L,
            name = "Work",
            timeStarted = 5_000L,
            timeEnded = 6_000L,
            comment = unicodeComment,
        )
        assertEquals(unicodeComment, records.addedRecords.single().comment)

        val updated = call(
            method = "PUT",
            path = "/api/records/4",
            authValue = bearer(),
            body = JSONObject()
                .put("typeId", 5L)
                .put("timeStarted", 5_100L)
                .toString(),
        )
        assertEquals(200, updated.code)
        assertRecordJson(
            json = JSONObject(updated.body),
            id = 4L,
            typeId = 5L,
            name = "Exercise",
            timeStarted = 5_000L,
            timeEnded = 6_000L,
            comment = unicodeComment,
        )
        assertEquals(1, records.updatedRecords.size)

        val deleted = call("DELETE", "/api/records/4", authValue = bearer())
        assertEquals(200, deleted.code)
        assertTrue(JSONObject(deleted.body).getBoolean("success"))
        assertEquals(4L, records.removedRecords.single().id)
        assertFalse(records.records.any { it.id == 4L })
    }

    @Test
    fun recordsListRejectsMissingInvalidAndEmptyRanges() {
        listOf(
            "/api/records",
            "/api/records?from=1",
            "/api/records?to=2",
            "/api/records?from=nope&to=2",
            "/api/records?from=1&to=nope",
            "/api/records?from=2&to=2",
            "/api/records?from=3&to=2",
        ).forEach { path ->
            val result = call("GET", path, authValue = bearer())
            assertEquals("GET $path", 400, result.code)
            assertTrue(JSONObject(result.body).has("error"))
        }
        assertTrue(records.getRanges.isEmpty())
    }

    @Test
    fun recordsRejectUnknownTypeId() {
        val post = call(
            method = "POST",
            path = "/api/records",
            authValue = bearer(),
            body = """{"typeId":999,"timeStarted":1000,"timeEnded":2000}""",
        )
        assertEquals(404, post.code)
        assertEquals("unknown typeId", JSONObject(post.body).getString("error"))

        val put = call(
            method = "PUT",
            path = "/api/records/1",
            authValue = bearer(),
            body = """{"typeId":999}""",
        )
        assertEquals(404, put.code)
        assertEquals("unknown typeId", JSONObject(put.body).getString("error"))
        assertTrue(records.addedRecords.isEmpty())
        assertTrue(records.updatedRecords.isEmpty())
    }

    @Test
    fun recordsPostDefaultsMissingCommentToEmpty() {
        val result = call(
            method = "POST",
            path = "/api/records",
            authValue = bearer(),
            body = """{"typeId":3,"timeStarted":10000,"timeEnded":20000}""",
        )
        assertEquals(200, result.code)
        assertEquals("", JSONObject(result.body).getString("comment"))
        assertEquals("", records.addedRecords.single().comment)
    }

    @Test
    fun recordsPostWithSameClientIdInsertsOnce() {
        val body = recordBody(clientId = "offline-record-1")

        val first = call("POST", "/api/records", authValue = bearer(), body = body)
        val second = call("POST", "/api/records", authValue = bearer(), body = body)

        assertEquals(200, first.code)
        assertEquals(200, second.code)
        assertEquals(JSONObject(first.body).getLong("id"), JSONObject(second.body).getLong("id"))
        assertEquals(1, records.addedRecords.size)
    }

    @Test
    fun recordsPostWithIdempotencyKeyHeaderInsertsOnce() {
        val body = recordBody()
        val headers = mapOf("Idempotency-Key" to "0D7B7C2E-6B6B-4B8B-9C0F-3E6E4B2A1F00")

        val first = call("POST", "/api/records", authValue = bearer(), body = body, extraHeaders = headers)
        val second = call("POST", "/api/records", authValue = bearer(), body = body, extraHeaders = headers)

        assertEquals(200, first.code)
        assertEquals(200, second.code)
        assertEquals(JSONObject(first.body).getLong("id"), JSONObject(second.body).getLong("id"))
        assertEquals(1, records.addedRecords.size)
    }

    @Test
    fun recordsPostWithClientIdInsertsDifferentRecords() {
        val first = call(
            "POST",
            "/api/records",
            authValue = bearer(),
            body = recordBody(clientId = "a"),
        )
        val second = call(
            "POST",
            "/api/records",
            authValue = bearer(),
            body = recordBody(clientId = "b", comment = "other"),
        )

        assertEquals(200, first.code)
        assertEquals(200, second.code)
        assertNotEquals(JSONObject(first.body).getLong("id"), JSONObject(second.body).getLong("id"))
        assertEquals(2, records.addedRecords.size)
    }

    @Test
    fun recordsRejectTooLongClientIds() {
        val result = call(
            "POST",
            "/api/records",
            authValue = bearer(),
            body = recordBody(clientId = "x".repeat(129)),
        )

        assertEquals(400, result.code)
        assertEquals("invalid clientId", JSONObject(result.body).getString("error"))
        assertTrue(records.addedRecords.isEmpty())
    }

    @Test
    fun recordsPostWithoutClientIdStillInsertsTwice() {
        val body = recordBody()

        val first = call("POST", "/api/records", authValue = bearer(), body = body)
        val second = call("POST", "/api/records", authValue = bearer(), body = body)

        assertEquals(200, first.code)
        assertEquals(200, second.code)
        assertEquals(4L, JSONObject(first.body).getLong("id"))
        assertEquals(5L, JSONObject(second.body).getLong("id"))
        assertEquals(2, records.addedRecords.size)
    }

    @Test
    fun recordsPostWithClientIdAppearsOnceInRecordsList() {
        val created = call(
            "POST",
            "/api/records",
            authValue = bearer(),
            body = recordBody(clientId = "offline-record-2"),
        )
        val replayed = call(
            "POST",
            "/api/records",
            authValue = bearer(),
            body = recordBody(clientId = "offline-record-2"),
        )
        val listed = call("GET", "/api/records?from=9000&to=21000", authValue = bearer())

        assertEquals(200, created.code)
        assertEquals(200, replayed.code)
        assertEquals(200, listed.code)
        val listedRecords = JSONArray(listed.body)
        assertEquals(1, listedRecords.length())
        assertEquals(
            JSONObject(created.body).getLong("id"),
            listedRecords.getJSONObject(0).getLong("id"),
        )
        assertEquals(1, records.addedRecords.size)
    }

    @Test
    fun concurrentRecordsPostsWithSameClientIdInsertOnce() {
        val requestCount = 10
        val body = recordBody(clientId = "offline-record-concurrent")
        val results = CopyOnWriteArrayList<HttpResult>()
        val failures = CopyOnWriteArrayList<Exception>()
        val pool = Executors.newFixedThreadPool(requestCount)
        val ready = CountDownLatch(requestCount)
        val go = CountDownLatch(1)

        repeat(requestCount) {
            pool.execute {
                ready.countDown()
                go.await()
                try {
                    results += call("POST", "/api/records", authValue = bearer(), body = body)
                } catch (e: Exception) {
                    failures += e
                }
            }
        }

        assertTrue("workers did not start", ready.await(30, TimeUnit.SECONDS))
        go.countDown()
        pool.shutdown()
        assertTrue("requests did not finish", pool.awaitTermination(120, TimeUnit.SECONDS))
        assertTrue("request failures: $failures", failures.isEmpty())
        assertEquals(requestCount, results.size)
        assertTrue(results.all { it.code == 200 })
        assertEquals(setOf(4L), results.map { JSONObject(it.body).getLong("id") }.toSet())
        assertEquals(1, records.addedRecords.size)
    }

    @Test
    fun recordsRejectEndNotAfterStartOnPostAndPut() {
        listOf(
            """{"typeId":3,"timeStarted":10,"timeEnded":10}""",
            """{"typeId":3,"timeStarted":10,"timeEnded":9}""",
        ).forEach { body ->
            val result = call("POST", "/api/records", authValue = bearer(), body = body)
            assertEquals(400, result.code)
        }

        listOf(
            """{"timeEnded":1000}""",
            """{"timeEnded":999}""",
        ).forEach { body ->
            val result = call("PUT", "/api/records/1", authValue = bearer(), body = body)
            assertEquals(400, result.code)
        }
        assertTrue(records.addedRecords.isEmpty())
        assertTrue(records.updatedRecords.isEmpty())
    }

    @Test
    fun recordsTruncateTimestampsBeforeValidationAndPersistence() {
        val sameSecond = call(
            "POST",
            "/api/records",
            authValue = bearer(),
            body = """{"typeId":3,"timeStarted":1500,"timeEnded":1600}""",
        )
        assertEquals(400, sameSecond.code)
        assertTrue(records.addedRecords.isEmpty())

        val created = call(
            "POST",
            "/api/records",
            authValue = bearer(),
            body = """{"typeId":3,"timeStarted":1500,"timeEnded":2600}""",
        )
        assertEquals(200, created.code)
        val createdJson = JSONObject(created.body)
        assertEquals(1_000L, createdJson.getLong("timeStarted"))
        assertEquals(2_000L, createdJson.getLong("timeEnded"))

        val listed = call("GET", "/api/records?from=0&to=3000", authValue = bearer())
        val listedJson = JSONArray(listed.body)
        val storedJson = (0 until listedJson.length())
            .map(listedJson::getJSONObject)
            .single { it.getLong("id") == createdJson.getLong("id") }
        assertEquals(createdJson.toString(), storedJson.toString())
    }

    @Test
    fun recordsRejectUnknownIdsOnPutAndDelete() {
        val put = call("PUT", "/api/records/404", authValue = bearer(), body = "{}")
        assertEquals(404, put.code)
        assertEquals("unknown id", JSONObject(put.body).getString("error"))

        val delete = call("DELETE", "/api/records/404", authValue = bearer())
        assertEquals(404, delete.code)
        assertEquals("unknown id", JSONObject(delete.body).getString("error"))
        assertTrue(records.updatedRecords.isEmpty())
        assertTrue(records.removedRecords.isEmpty())
    }

    @Test
    fun recordsRejectBodiesLargerThan64KiB() {
        val body = JSONObject()
            .put("typeId", 3L)
            .put("timeStarted", 1L)
            .put("timeEnded", 2L)
            .put("comment", "x".repeat(64 * 1024))
            .toString()
        val result = call("POST", "/api/records", authValue = bearer(), body = body)
        assertEquals(400, result.code)
        assertEquals("request body too large", JSONObject(result.body).getString("error"))
        assertTrue(records.addedRecords.isEmpty())
    }

    // Case 3: OPTIONS preflight needs no token.

    @Test
    fun optionsPreflightSucceedsWithoutToken() {
        listOf("/api/activities", "/api/start/1", "/api/whatever").forEach { path ->
            val result = call("OPTIONS", path)
            assertEquals("OPTIONS $path", 200, result.code)

            val allowHeaders = result.header("Access-Control-Allow-Headers")
            assertNotNull("Access-Control-Allow-Headers missing for $path", allowHeaders)
            assertTrue(
                "Access-Control-Allow-Headers must allow Authorization, was: $allowHeaders",
                allowHeaders.orEmpty().contains("Authorization"),
            )
            assertEquals("*", result.header("Access-Control-Allow-Origin"))
            assertTrue(
                "Access-Control-Allow-Methods was: ${result.header("Access-Control-Allow-Methods")}",
                result.header("Access-Control-Allow-Methods").orEmpty().contains("POST"),
            )
            assertTrue(result.header("Access-Control-Allow-Methods").orEmpty().contains("PUT"))
            assertTrue(result.header("Access-Control-Allow-Methods").orEmpty().contains("DELETE"))
        }
    }

    // Case 4: start goes through the records interactor, stop through the wear API.

    @Test
    fun startWithoutBodyStartsNow() {
        val result = call("POST", "/api/start/5", authValue = bearer())
        assertEquals(200, result.code)
        assertTrue(JSONObject(result.body).getBoolean("success"))

        assertEquals(
            FakeWebApiRecordsInteractor.StartedActivity(typeId = 5L, timeStarted = null, comment = ""),
            records.startedActivities.single(),
        )
        assertTrue(fake.startRequests.isEmpty())
        assertTrue(fake.stopRequests.isEmpty())
    }

    @Test
    fun startWithPastTimeForwardsTimeAndComment() {
        val timeStarted = System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(30)
        val comment = "Offline handoff"
        val result = call(
            method = "POST",
            path = "/api/start/5",
            authValue = bearer(),
            body = JSONObject()
                .put("timeStarted", timeStarted)
                .put("comment", comment)
                .toString(),
        )

        assertEquals(200, result.code)
        assertTrue(JSONObject(result.body).getBoolean("success"))
        assertEquals(
            FakeWebApiRecordsInteractor.StartedActivity(
                typeId = 5L,
                timeStarted = timeStarted / 1_000L * 1_000L,
                comment = comment,
            ),
            records.startedActivities.single(),
        )
    }

    @Test
    fun startRejectsFutureTime() {
        val result = call(
            method = "POST",
            path = "/api/start/5",
            authValue = bearer(),
            body = JSONObject()
                .put("timeStarted", System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(5))
                .toString(),
        )

        assertEquals(400, result.code)
        assertEquals("timeStarted must not be in the future", JSONObject(result.body).getString("error"))
        assertTrue(records.startedActivities.isEmpty())
    }

    @Test
    fun startRejectsUnknownTypeId() {
        val result = call("POST", "/api/start/999", authValue = bearer())

        assertEquals(404, result.code)
        assertEquals("unknown typeId", JSONObject(result.body).getString("error"))
        assertTrue(records.startedActivities.isEmpty())
    }

    @Test
    fun startWithBodyStillRequiresToken() {
        val result = call(
            method = "POST",
            path = "/api/start/5",
            body = """{"timeStarted":1}""",
        )

        assertEquals(401, result.code)
        assertTrue(records.startedActivities.isEmpty())
    }

    @Test
    fun startWithoutContentLengthStartsNow() {
        val raw = rawRequest(
            "POST /api/start/5 HTTP/1.1\r\n" +
                "Host: 127.0.0.1:$PORT\r\n" +
                "Authorization: ${bearer()}\r\n" +
                "Connection: close\r\n\r\n",
        )

        assertTrue("raw response was: $raw", raw.startsWith("HTTP/1.1 200"))
        assertNull(records.startedActivities.single().timeStarted)
    }

    @Test
    fun repeatForwardsToWearApi() {
        val result = call("POST", "/api/repeat", authValue = bearer())

        assertEquals(200, result.code)
        assertTrue(JSONObject(result.body).getBoolean("success"))
        assertEquals("STARTED", JSONObject(result.body).getString("result"))
        assertEquals(1, fake.repeatCalls)
    }

    @Test
    fun stopForwardsId() {
        val result = call("POST", "/api/stop/5", authValue = bearer())
        assertEquals(200, result.code)
        assertTrue(result.body.contains("\"success\""))

        assertEquals(1, fake.stopRequests.size)
        assertEquals(5L, requireNotNull(fake.stopRequests.single().id))
        assertTrue(records.startedActivities.isEmpty())
    }

    // Case 5: bad id -> 400, unknown route -> 404.

    @Test
    fun nonNumericIdIsRejected() {
        listOf("/api/start/abc", "/api/stop/abc", "/api/start/", "/api/stop/1.5").forEach { path ->
            val result = call("POST", path, authValue = bearer())
            assertEquals("POST $path", 400, result.code)
        }
        assertTrue(records.startedActivities.isEmpty())
        assertTrue(fake.stopRequests.isEmpty())
    }

    /** A browser must be able to read the JSON 400 response from either start or stop. */
    @Test
    fun badRequestResponseCarriesCorsHeaders() {
        val raw = rawRequest(
            "POST /api/start/abc HTTP/1.1\r\n" +
                "Host: 127.0.0.1:$PORT\r\n" +
                "Authorization: ${bearer()}\r\n" +
                "Content-Length: 0\r\n" +
                "Connection: close\r\n\r\n",
        )
        assertTrue("raw response was: $raw", raw.startsWith("HTTP/1.1 400"))
        assertTrue(
            "400 response is missing Access-Control-Allow-Origin: $raw",
            raw.contains("Access-Control-Allow-Origin: *"),
        )
    }

    @Test
    fun unknownRouteWithTokenIsNotFound() {
        listOf(
            "GET" to "/",
            "GET" to "/api/unknown",
            "GET" to "/api/start/1",
            "POST" to "/api/activities",
            "POST" to "/api/ping",
        ).forEach { (method, path) ->
            val result = call(method, path, authValue = bearer())
            assertEquals("$method $path", 404, result.code)
        }
    }

    // Case 6: a failing wear API becomes a 500, and the server keeps serving.

    @Test
    fun apiFailureBecomes500AndServerStaysAlive() {
        fake.failure = RuntimeException("fake blew up")

        val failed = call("GET", "/api/activities", authValue = bearer())
        assertEquals(500, failed.code)
        val error = JSONObject(failed.body)
        assertEquals("Internal error", error.getString("error"))
        assertFalse("500 body leaked the exception detail", failed.body.contains("fake blew up"))

        val failedPost = call("POST", "/api/stop/5", authValue = bearer())
        assertEquals(500, failedPost.code)

        fake.failure = null
        val recovered = call("GET", "/api/activities", authValue = bearer())
        assertEquals("server must answer the next request", 200, recovered.code)
        assertEquals(3, JSONArray(recovered.body).length())
    }

    /** Internal failures must stay valid JSON without exposing exception details. */
    @Test
    fun errorBodyIsValidJsonWhenMessageContainsQuotes() {
        fake.failure = RuntimeException("""db said "no" and then \ broke""")
        val result = call("GET", "/api/activities", authValue = bearer())
        assertEquals(500, result.code)
        val error = JSONObject(result.body)
        assertEquals("Internal error", error.getString("error"))
        assertFalse("500 body leaked the exception detail", result.body.contains("db said"))
    }

    // Case 7: concurrent load.

    @Test
    fun handlesConcurrentRequests() {
        val threads = 8
        val perThread = 10
        val expectations = listOf(
            Expectation("GET", "/api/activities", bearer(), 200),
            Expectation("GET", "/api/running", bearer(), 200),
            Expectation("POST", "/api/start/5", bearer(), 200),
            Expectation("POST", "/api/stop/5", bearer(), 200),
            Expectation("POST", "/api/start/nope", bearer(), 400),
            Expectation("GET", "/api/nope", bearer(), 404),
            Expectation("GET", "/api/activities", null, 401),
            Expectation("POST", "/api/start/5", "Bearer nope", 401),
        )

        val problems = CopyOnWriteArrayList<String>()
        val completed = AtomicInteger()
        val pool = Executors.newFixedThreadPool(threads)
        val ready = CountDownLatch(threads)
        val go = CountDownLatch(1)

        repeat(threads) { threadIndex ->
            pool.execute {
                ready.countDown()
                go.await()
                repeat(perThread) { requestIndex ->
                    val expected = expectations[(threadIndex + requestIndex) % expectations.size]
                    try {
                        val result = call(expected.method, expected.path, authValue = expected.auth)
                        if (result.code != expected.code) {
                            problems.add(
                                "${expected.method} ${expected.path} expected ${expected.code} " +
                                    "got ${result.code} body=${result.body}",
                            )
                        }
                    } catch (e: Exception) {
                        problems.add("${expected.method} ${expected.path} threw $e")
                    }
                    completed.incrementAndGet()
                }
            }
        }

        assertTrue("workers did not start", ready.await(30, TimeUnit.SECONDS))
        go.countDown()
        pool.shutdown()
        assertTrue("requests did not finish in time", pool.awaitTermination(120, TimeUnit.SECONDS))
        assertEquals("every request must have run", threads * perThread, completed.get())
        assertTrue("failures: ${problems.take(10)} (total ${problems.size})", problems.isEmpty())

        // The server is still healthy afterwards.
        assertEquals(200, call("GET", "/api/activities", authValue = bearer()).code)
        assertTrue(records.startedActivities.isNotEmpty())
        assertTrue(fake.stopRequests.isNotEmpty())
        assertTrue(records.startedActivities.all { it.typeId == 5L })
        assertTrue(fake.stopRequests.all { it.id == 5L })
    }

    @Test
    fun saturatedWorkerQueueClosesRejectedHandler() {
        val runner = adapter.createBoundedAsyncRunner(
            corePoolSize = 1,
            maxPoolSize = 1,
            queueCapacity = 1,
        )
        val runningStarted = CountDownLatch(1)
        val releaseRunning = CountDownLatch(1)
        // ClientHandler.run() opens the socket's output stream before reading the
        // request, so the sockets must be connected or run() aborts before our
        // blocking input stream is ever touched.
        val peers = mutableListOf<Socket>()
        val server = java.net.ServerSocket(0, 8, java.net.InetAddress.getLoopbackAddress())
        fun connectedSocket(): Socket {
            val client = Socket(server.inetAddress, server.localPort)
            peers.add(server.accept())
            return client
        }
        val runningSocket = connectedSocket()
        val queuedSocket = connectedSocket()
        val rejectedSocket = connectedSocket()
        val running = clientHandler(
            inputStream = object : InputStream() {
                override fun read(): Int {
                    runningStarted.countDown()
                    releaseRunning.await()
                    return -1
                }
            },
            socket = runningSocket,
        )
        val queued = clientHandler(
            inputStream = ByteArrayInputStream(ByteArray(0)),
            socket = queuedSocket,
        )
        val rejected = clientHandler(
            inputStream = ByteArrayInputStream(ByteArray(0)),
            socket = rejectedSocket,
        )

        try {
            runner.exec(running)
            assertTrue("worker did not start", runningStarted.await(10, TimeUnit.SECONDS))
            runner.exec(queued)
            runner.exec(rejected)

            assertTrue("rejected handler was not closed", rejectedSocket.isClosed)
            assertFalse("running handler should still own the worker", runningSocket.isClosed)
            assertFalse("queued handler should remain queued", queuedSocket.isClosed)
        } finally {
            releaseRunning.countDown()
            runner.closeAll()
            peers.forEach { runCatching { it.close() } }
            runCatching { server.close() }
        }
    }

    // Case 8: the token never leaks back to the caller.

    @Test
    fun tokenNeverAppearsInResponses() {
        val token = TEST_TOKEN
        assertTrue("test is meaningless with a blank token", token.isNotBlank())

        fake.failure = RuntimeException("wear api failure")
        val failing = call("GET", "/api/activities", authValue = bearer())
        fake.failure = null

        val results = listOf(
            call("GET", "/api/activities"),
            call("GET", "/api/activities", authValue = bearer()),
            call("GET", "/api/activities", authValue = "Bearer wrong"),
            call("GET", "/api/running", authValue = bearer()),
            call("POST", "/api/start/5", authValue = bearer()),
            call("POST", "/api/stop/5", authValue = bearer()),
            call("POST", "/api/start/abc", authValue = bearer()),
            call("GET", "/api/unknown", authValue = bearer()),
            call("OPTIONS", "/api/activities"),
            call("OPTIONS", "/api/activities", authValue = bearer()),
        )

        // `failing` is the 500 path, included so the error branch is covered too.
        (results + failing).forEach { result ->
            assertFalse(
                "token leaked into body of ${result.body}",
                result.body.contains(token),
            )
            result.headers.forEach { (name, values) ->
                values.forEach { value ->
                    assertFalse("token leaked into header $name: $value", value.contains(token))
                }
            }
        }
    }

    // Case 9: NanoHTTPD lowercases header names, so a lowercase header must still work.

    @Test
    fun lowercaseAuthorizationHeaderIsAccepted() {
        listOf("authorization", "AUTHORIZATION", "AuThOrIzAtIoN").forEach { name ->
            val result = call("GET", "/api/activities", authName = name, authValue = bearer())
            assertEquals("header name '$name'", 200, result.code)
            assertEquals(3, JSONArray(result.body).length())
        }
    }

    // Helpers.

    private data class Expectation(
        val method: String,
        val path: String,
        val auth: String?,
        val code: Int,
    )

    private class HttpResult(
        val code: Int,
        val body: String,
        val headers: Map<String, List<String>>,
    ) {
        fun header(name: String): String? = headers.entries
            .firstOrNull { it.key.equals(name, ignoreCase = true) }
            ?.value
            ?.joinToString(", ")
    }

    private fun clientHandler(
        inputStream: InputStream,
        socket: Socket,
    ): NanoHTTPD.ClientHandler {
        val constructor = NanoHTTPD.ClientHandler::class.java.getDeclaredConstructor(
            NanoHTTPD::class.java,
            InputStream::class.java,
            Socket::class.java,
        )
        return constructor.newInstance(adapter, inputStream, socket)
    }

    private fun bearer(): String = "Bearer $TEST_TOKEN"

    private fun recordBody(clientId: String? = null, comment: String? = null): String {
        return JSONObject()
            .put("typeId", 3L)
            .put("timeStarted", 10_000L)
            .put("timeEnded", 20_000L)
            .apply { clientId?.let { put("clientId", it) } }
            .apply { comment?.let { put("comment", it) } }
            .toString()
    }

    private fun assertRecordJson(
        json: JSONObject,
        id: Long,
        typeId: Long,
        name: String,
        timeStarted: Long,
        timeEnded: Long,
        comment: String,
    ) {
        assertEquals(
            setOf("id", "typeId", "name", "timeStarted", "timeEnded", "comment"),
            json.keys().asSequence().toSet(),
        )
        assertEquals(id, json.getLong("id"))
        assertEquals(typeId, json.getLong("typeId"))
        assertEquals(name, json.getString("name"))
        assertEquals(timeStarted, json.getLong("timeStarted"))
        assertEquals(timeEnded, json.getLong("timeEnded"))
        assertEquals(comment, json.getString("comment"))
    }

    private fun call(
        method: String,
        path: String,
        authName: String = "Authorization",
        authValue: String? = null,
        body: String? = null,
        extraHeaders: Map<String, String> = emptyMap(),
    ): HttpResult {
        val connection = URL("http://127.0.0.1:$PORT$path").openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 10_000
        connection.readTimeout = 30_000
        connection.instanceFollowRedirects = false
        if (authValue != null) connection.setRequestProperty(authName, authValue)
        extraHeaders.forEach { (name, value) -> connection.setRequestProperty(name, value) }
        if (method == "POST" || method == "PUT") {
            val bytes = body.orEmpty().toByteArray(Charsets.UTF_8)
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            // No fixed-length streaming mode: it makes HttpURLConnection discard
            // error bodies on 4xx (see rawSocketPostWithoutTokenReturnsJsonErrorBody).
            connection.outputStream.use { it.write(bytes) }
        }
        return try {
            val code = connection.responseCode
            val stream = if (code < 400) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val headers = connection.headerFields.entries
                .filter { it.key != null }
                .associate { it.key to it.value.orEmpty() }
            HttpResult(code, body, headers)
        } finally {
            connection.disconnect()
        }
    }

    private fun rawRequest(request: String): String {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", PORT), 10_000)
            socket.soTimeout = 30_000
            socket.getOutputStream().apply {
                write(request.toByteArray(Charsets.US_ASCII))
                flush()
            }
            return socket.getInputStream().readBytes().toString(Charsets.UTF_8)
        }
    }

    private fun startServer(server: WebApiAdapter) {
        var last: IOException? = null
        repeat(BIND_ATTEMPTS) {
            try {
                server.startWebApi(NanoHTTPD.SOCKET_READ_TIMEOUT, true)
                return
            } catch (e: IOException) {
                last = e
                Thread.sleep(200)
            }
        }
        throw IllegalStateException("Could not bind port $PORT for the test server", last)
    }

    companion object {
        private const val PORT = WebApiAdapter.PORT
        private const val BIND_ATTEMPTS = 25
        private const val TEST_TOKEN =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    }
}
