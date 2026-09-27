package com.example.util.simpletimetracker.domain.webApi.interactor

import com.example.util.simpletimetracker.domain.prefs.interactor.PrefsInteractor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify

class WebApiTokenInteractorTest {

    private var storedToken: String = ""
    private val prefsInteractor: PrefsInteractor = mock {
        onBlocking { getWebApiToken() } doAnswer { storedToken }
        onBlocking { setWebApiToken(any()) } doAnswer { storedToken = it.getArgument(0) }
    }
    private val subject = WebApiTokenInteractor(prefsInteractor)

    @Test
    fun emptyPrefsGeneratesAndStoresToken() = runBlocking {
        // When
        val token = subject.getOrCreate()

        // Then
        assertEquals(64, token.length)
        assertTrue(token.all { it in '0'..'9' || it in 'a'..'f' })
        assertEquals(token, storedToken)
        assertEquals(token, subject.getOrCreate())
    }

    @Test
    fun existingTokenIsReused() = runBlocking {
        // Given
        storedToken = "saved-token"

        // When
        val token = subject.getOrCreate()

        // Then
        assertEquals("saved-token", token)
        verify(prefsInteractor, never()).setWebApiToken(any())
    }

    @Test
    fun regenerateReplacesToken() = runBlocking {
        // Given
        val first = subject.getOrCreate()

        // When
        val second = subject.regenerate()

        // Then
        assertNotEquals(first, second)
        assertEquals(64, second.length)
        assertEquals(second, storedToken)
        assertEquals(second, subject.getOrCreate())
    }
}
