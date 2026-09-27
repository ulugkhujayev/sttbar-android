package com.example.util.simpletimetracker.domain.webApi.interactor

import com.example.util.simpletimetracker.domain.prefs.interactor.PrefsInteractor
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bearer token that every Web API request must carry.
 * Generated once on first use and kept in prefs until the user regenerates it.
 */
@Singleton
class WebApiTokenInteractor @Inject constructor(
    private val prefsInteractor: PrefsInteractor,
) {

    private val mutex = Mutex()
    private val secureRandom by lazy { SecureRandom() }

    suspend fun getOrCreate(): String = mutex.withLock {
        prefsInteractor.getWebApiToken().takeIf(String::isNotEmpty)
            ?: generate().also { prefsInteractor.setWebApiToken(it) }
    }

    suspend fun regenerate(): String = mutex.withLock {
        generate().also { prefsInteractor.setWebApiToken(it) }
    }

    private fun generate(): String {
        val bytes = ByteArray(TOKEN_BYTE_COUNT).also(secureRandom::nextBytes)
        return bytes.joinToString(separator = "") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
    }

    companion object {
        private const val TOKEN_BYTE_COUNT = 32
    }
}
