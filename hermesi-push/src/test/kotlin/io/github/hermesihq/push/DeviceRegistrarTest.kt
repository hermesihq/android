package io.github.hermesihq.push

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * The rule for a rotated token, and for what a failure at each step leaves behind. Everything here is a fake, so
 * what is checked is the order of the calls and the state afterwards, which is the whole of this class.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeviceRegistrarTest {

    private class FakeApi : DeviceApi {
        val calls = mutableListOf<String>()
        var failRegister: Exception? = null
        var failUnregister: Exception? = null
        var gate: CompletableDeferred<Unit>? = null
        val registeredMetadata = mutableListOf<Map<String, Any?>>()

        override suspend fun registerDevice(token: String, metadata: Map<String, Any?>) {
            calls += "register:$token"
            gate?.await()
            failRegister?.let { throw it }
            registeredMetadata += metadata
        }

        override suspend fun unregisterDevice(token: String) {
            calls += "unregister:$token"
            failUnregister?.let { throw it }
        }
    }

    private class FakeSource(var token: String = "t1") : TokenSource {
        var deleted = 0
        var failDelete: Exception? = null
        override suspend fun currentToken(): String = token
        override suspend fun deleteToken() {
            deleted++
            failDelete?.let { throw it }
        }
    }

    private class FakeStore(var token: String? = null) : TokenStore {
        override fun get(): String? = token
        override fun set(token: String?) {
            this.token = token
        }
    }

    private val api = FakeApi()
    private val source = FakeSource()
    private val store = FakeStore()
    private val warnings = mutableListOf<String>()

    private fun registrar() = DeviceRegistrar(api, source, store, { mapOf("platform" to "android") }) { message, _ -> warnings += message }

    @Test
    fun registersTheCurrentTokenAndRemembersIt() = runTest {
        val token = registrar().register()

        assertEquals("t1", token)
        assertEquals(listOf("register:t1"), api.calls)
        assertEquals("t1", store.token)
        assertEquals(mapOf<String, Any?>("platform" to "android"), api.registeredMetadata.single())
    }

    @Test
    fun registeringTheSameTokenAgainIsAnUpdateAndRetiresNothing() = runTest {
        val registrar = registrar()
        registrar.register()
        registrar.register()

        assertEquals(listOf("register:t1", "register:t1"), api.calls)
    }

    @Test
    fun aRotatedTokenIsRegisteredBeforeTheOldOneIsRemoved() = runTest {
        val registrar = registrar()
        registrar.register()
        api.calls.clear()

        assertTrue(registrar.refresh("t2"))

        // New first: a failure in between leaves the device registered under one token, never none.
        assertEquals(listOf("register:t2", "unregister:t1"), api.calls)
        assertEquals("t2", store.token)
    }

    @Test
    fun aRotationThatFailsToRegisterLeavesTheOldTokenAndRemovesNothing() = runTest {
        val registrar = registrar()
        registrar.register()
        api.calls.clear()
        api.failRegister = IOException("offline")

        val error = runCatching { registrar.refresh("t2") }.exceptionOrNull()

        assertTrue(error is IOException)
        assertEquals(listOf("register:t2"), api.calls)
        assertEquals("t1", store.token)
    }

    @Test
    fun failingToRemoveTheOldTokenIsAWarningNotAFailure() = runTest {
        val registrar = registrar()
        registrar.register()
        api.failUnregister = IOException("offline")

        assertTrue(registrar.refresh("t2"))

        assertEquals("t2", store.token)
        assertEquals(1, warnings.size)
    }

    @Test
    fun cancellationWhileRemovingTheOldTokenIsNotSwallowed() = runTest {
        val registrar = registrar()
        registrar.register()
        api.failUnregister = CancellationException("cancelled")

        val error = runCatching { registrar.refresh("t2") }.exceptionOrNull()

        assertTrue(error is CancellationException)
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun aRefreshBeforeTheDeviceWasEverRegisteredDoesNothing() = runTest {
        assertFalse(registrar().refresh("t2"))

        assertTrue("there is no subscriber yet to register it for", api.calls.isEmpty())
        assertNull(store.token)
    }

    @Test
    fun aRegistrationThatFailsRemembersNothing() = runTest {
        api.failRegister = HermesiApiError(401, "authentication_error", "invalid_token", "x", "", emptyList())

        val error = runCatching { registrar().register() }.exceptionOrNull()

        assertTrue(error is HermesiApiError)
        assertNull(store.token)
        assertFalse(registrar().isRegistered())
    }

    @Test
    fun unregisteringTellsHermesiThenForgetsThenDiscardsTheFirebaseToken() = runTest {
        val registrar = registrar()
        registrar.register()
        api.calls.clear()

        registrar.unregister()

        assertEquals(listOf("unregister:t1"), api.calls)
        assertNull(store.token)
        assertEquals(1, source.deleted)
        assertFalse(registrar.isRegistered())
    }

    @Test
    fun unregisteringWhenHermesiCannotBeToldChangesNothing() = runTest {
        val registrar = registrar()
        registrar.register()
        api.failUnregister = IOException("offline")

        val error = runCatching { registrar.unregister() }.exceptionOrNull()

        assertTrue(error is IOException)
        assertEquals("a repeat of the call can still remove it", "t1", store.token)
        assertEquals(0, source.deleted)
    }

    @Test
    fun aFailureDiscardingTheFirebaseTokenStillLeavesTheDeviceUnregistered() = runTest {
        val registrar = registrar()
        registrar.register()
        source.failDelete = IOException("play services")

        val error = runCatching { registrar.unregister() }.exceptionOrNull()

        assertTrue(error is IOException)
        assertNull(store.token)
    }

    @Test
    fun unregisteringADeviceThatWasNeverRegisteredDoesNothing() = runTest {
        registrar().unregister()

        assertTrue(api.calls.isEmpty())
        assertEquals(0, source.deleted)
    }

    @Test
    fun aRefreshArrivingMidRegistrationWaitsForItAndSeesItsToken() = runTest {
        val registrar = registrar()
        api.gate = CompletableDeferred()

        launch { registrar.register() }
        advanceUntilIdle()
        assertEquals("the first registration is in flight", listOf("register:t1"), api.calls)
        val refreshed = launch { registrar.refresh("t2") }
        advanceUntilIdle()
        assertEquals("the refresh must not start until the registration is done", listOf("register:t1"), api.calls)

        api.gate!!.complete(Unit)
        advanceUntilIdle()
        refreshed.join()

        // The refresh saw t1 as the registered token, so it retired it: no interleaving, no lost token.
        assertEquals(listOf("register:t1", "register:t2", "unregister:t1"), api.calls)
        assertEquals("t2", store.token)
    }
}
