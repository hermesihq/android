package io.github.hermesihq.push

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Where the device's push token comes from. Firebase Messaging in an app; a fake in a test. */
public interface TokenSource {
    /** The token to register now. */
    public suspend fun currentToken(): String

    /** Discards the token, so that the next [currentToken] is a new one. */
    public suspend fun deleteToken()
}

/** Remembers the token that was registered, across restarts. */
public interface TokenStore {
    public fun get(): String?
    public fun set(token: String?)
}

/**
 * Keeps this device registered with Hermesi as the push token changes.
 *
 * The one thing it adds over calling the API is the rule for a rotated token. Firebase replaces a device's token
 * from time to time, and the old one stops working. Registering the new token first and removing the old one
 * second means a failure in between leaves the device registered under at least one token, and never under none.
 * A failure to remove the old one is reported through [onWarning] and does not fail the call, because Hermesi
 * removes a token Firebase reports as dead the next time it tries to send to it.
 *
 * Calls are serialised, so a refresh arriving while [register] is in flight cannot interleave with it.
 */
public class DeviceRegistrar(
    private val api: DeviceApi,
    private val source: TokenSource,
    private val store: TokenStore,
    private val metadata: () -> Map<String, Any?>,
    private val onWarning: (message: String, cause: Throwable) -> Unit = { _, _ -> },
) {
    private val lock = Mutex()

    /** True if this device has been registered and not unregistered since. */
    public fun isRegistered(): Boolean = store.get() != null

    /**
     * Registers the current token, and retires the previous one if it changed. Safe to call on every app start:
     * registering a token Hermesi already has is an update, and it also reactivates a device Hermesi had marked
     * invalid. Returns the token.
     */
    public suspend fun register(): String = lock.withLock { registerAndRetire(source.currentToken()) }

    /**
     * For the token-changed callback. Does nothing, and returns false, if this device was never registered:
     * there is no subscriber to register it for until the person has signed in, and [register] will do it then.
     */
    public suspend fun refresh(newToken: String): Boolean = lock.withLock {
        if (store.get() == null) return@withLock false
        registerAndRetire(newToken)
        true
    }

    /**
     * Removes this device from Hermesi and discards its Firebase token, for sign-out or when the person turns
     * notifications off. If Hermesi cannot be told, nothing changes and the failure is thrown, so the call can be
     * repeated.
     */
    public suspend fun unregister(): Unit = lock.withLock {
        val token = store.get() ?: return@withLock
        api.unregisterDevice(token)
        // Forgotten before the Firebase token is discarded: if that fails, the device is simply unregistered
        // here and the next register() puts it back.
        store.set(null)
        source.deleteToken()
    }

    private suspend fun registerAndRetire(token: String): String {
        api.registerDevice(token, metadata())
        val previous = store.get()
        store.set(token)
        if (previous != null && previous != token) {
            try {
                api.unregisterDevice(previous)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                onWarning("Could not remove the previous push token; Hermesi will drop it when Firebase reports it dead.", e)
            }
        }
        return token
    }
}
