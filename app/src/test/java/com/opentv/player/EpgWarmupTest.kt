package com.opentv.player

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 9.2.1: the EPG warm-up starts as soon as the playlist load
 * finishes, with the 2 s delay as a cap — never a fixed wait, and
 * never starved by a stalled playlist load.
 */
class EpgWarmupTest {

    @Test
    fun warmupStartsAsSoonAsPlaylistFinishes() = runBlocking {
        val playlist = async { delay(50); Result.success(emptyList<Channel>()) }
        var startedAt = -1L
        val start = System.currentTimeMillis()
        PlaylistRepository.startEpgWarmupWhenReady(playlist, delayMs = 5_000L) {
            startedAt = System.currentTimeMillis() - start
        }
        // The playlist finished in ~50 ms; the warm-up must not sit
        // out the 5 s cap. The bound is generous — CI runners stall.
        assertTrue("warm-up started at ${startedAt}ms", startedAt in 0..2_000)
    }

    @Test
    fun warmupStartsAtCapWhenPlaylistStalls() = runBlocking {
        val playlist = async<Result<List<Channel>>> { delay(60_000) }
        var startedAt = -1L
        val start = System.currentTimeMillis()
        PlaylistRepository.startEpgWarmupWhenReady(playlist, delayMs = 200L) {
            startedAt = System.currentTimeMillis() - start
        }
        playlist.cancel()
        // The playlist never finished; the warm-up must start at the
        // cap instead of waiting for it. Bounds are generous for CI.
        assertTrue("warm-up started at ${startedAt}ms", startedAt in 100..5_000)
    }

    @Test
    fun warmupStartsAtOnceWhenPlaylistAlreadyDone() = runBlocking {
        val playlist = async { Result.success(emptyList<Channel>()) }
        playlist.await()
        var startedAt = -1L
        val start = System.currentTimeMillis()
        PlaylistRepository.startEpgWarmupWhenReady(playlist, delayMs = 5_000L) {
            startedAt = System.currentTimeMillis() - start
        }
        assertTrue("warm-up started at ${startedAt}ms", startedAt in 0..2_000)
    }

    @Test
    fun warmupStartsEvenWhenPlaylistLoadFails() = runBlocking {
        // A failed playlist load comes back as a Result.failure, not
        // a throw — the EPG warm-up must still start at once instead
        // of sitting out the cap. (Gemini's idea, rewritten: its
        // version used runTest, which isn't a test dependency.)
        val playlist = async {
            Result.failure<List<Channel>>(RuntimeException("no network"))
        }
        var started = false
        PlaylistRepository.startEpgWarmupWhenReady(playlist, delayMs = 5_000L) {
            started = true
        }
        assertTrue(started)
    }
}
