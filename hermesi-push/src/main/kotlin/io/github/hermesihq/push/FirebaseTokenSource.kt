package io.github.hermesihq.push

import android.content.SharedPreferences
import com.google.android.gms.tasks.Task
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** The device's token from Firebase Messaging. */
internal class FirebaseTokenSource : TokenSource {
    override suspend fun currentToken(): String = FirebaseMessaging.getInstance().token.await()

    override suspend fun deleteToken() {
        FirebaseMessaging.getInstance().deleteToken().await()
    }
}

/** The registered token, kept in the app's private preferences. */
internal class SharedPreferencesTokenStore(
    private val preferences: SharedPreferences,
    private val key: String,
) : TokenStore {
    override fun get(): String? = preferences.getString(key, null)

    // `commit`, not `apply`: the token must be on disk before the call that follows it, because a process
    // killed in between would otherwise forget a token it had registered and never retire it.
    override fun set(token: String?) {
        preferences.edit().apply { if (token == null) remove(key) else putString(key, token) }.commit()
    }
}

/**
 * Suspends until a Play services `Task` completes. Written here so that the SDK does not need the
 * `kotlinx-coroutines-play-services` artifact, which an app may not have.
 */
private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addOnCompleteListener { task ->
        val error = task.exception
        when {
            error != null -> continuation.resumeWithException(error)
            task.isCanceled -> continuation.cancel()
            else -> @Suppress("UNCHECKED_CAST") continuation.resume(task.result as T)
        }
    }
}
