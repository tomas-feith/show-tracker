package com.showtracker.app.ui

import kotlinx.coroutines.CancellationException

/**
 * Run [block], turning any failure into a [Result] the UI can show as a message.
 *
 * A user-facing action has to survive anything the network or TMDB produces, so the catch
 * is deliberately broad - there is no useful list of "expected" failures when the outcome
 * is the same either way: a sentence on screen instead of a crash.
 *
 * Cancellation is rethrown rather than captured. It is not a failure: it is how a coroutine
 * scope tells its children to stop, and swallowing it into a `Result.failure` leaves a
 * cancelled ViewModel still running its work and reporting a spurious error. `runCatching`
 * cannot be used for exactly this reason - it catches `Throwable`, cancellation included.
 */
internal suspend inline fun <T> catchingUserFacing(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (
        @Suppress("TooGenericExceptionCaught") e: Exception,
    ) {
        Result.failure(e)
    }
