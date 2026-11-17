package com.zhangwenkang.cinefin.film.presentation.detail

import android.content.SharedPreferences
import com.zhangwenkang.cinefin.settings.domain.AppPreferences
import java.io.IOException
import java.lang.reflect.Proxy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class DetailEnrichmentTest {
    @Test
    fun `optional detail rethrows cancellation`() = runBlocking {
        val cancellation = CancellationException("cancelled")

        val thrown = runCatching {
            readOptionalDetail<Unit> { throw cancellation }
        }
            .exceptionOrNull()

        assertSame(cancellation, thrown)
    }

    @Test
    fun `optional detail returns non-cancellation failure`() = runBlocking {
        val result = readOptionalDetail<Unit> { throw IOException("enrichment failed") }

        assertEquals("enrichment failed", result.exceptionOrNull()?.message)
    }

    @Test
    fun `optional preference failure is returned by enrichment`() = runBlocking {
        val sharedPreferences =
            Proxy.newProxyInstance(
                SharedPreferences::class.java.classLoader,
                arrayOf(SharedPreferences::class.java),
            ) { proxy, method, args ->
                when (method.name) {
                    "getBoolean",
                    "edit" -> throw IllegalStateException("prefs failed")
                    "toString" -> "DetailEnrichmentTestPreferences"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    else -> throw AssertionError("Unexpected preference call: ${method.name}")
                }
            } as SharedPreferences
        val appPreferences = AppPreferences(sharedPreferences)

        val result = readOptionalDetail { appPreferences.getValue(appPreferences.displayExtraInfo) }
        val failureMessage =
            generateSequence(result.exceptionOrNull()) { error -> error.cause }
                .mapNotNull { error -> error.message }
                .firstOrNull { message -> message == "prefs failed" }

        assertEquals("prefs failed", failureMessage)
    }
}
