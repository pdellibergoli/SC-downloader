package com.streamingcommunity.downloader.core.hls

import kotlin.math.max
import kotlin.math.min

/**
 * AIMD (Additive Increase, Multiplicative Decrease) concurrency limiter
 * ported from `AdaptiveLimiter` in `app/core/m3u8.py`.
 */
class AdaptiveLimiter(
    maximum: Int,
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000L }
) {
    companion object {
        const val MIN_SEGMENT_ALLOWANCE = 2
        const val PENALTY_INTERVAL_MS = 2_000L
    }

    private val lock = Object()

    var maximum: Int = max(1, maximum)
        private set

    private var _allowance: Int = this.maximum
    private var _inFlight: Int = 0
    private var _served: Int = 0
    private var _lastPenaltyMs: Long? = null

    val allowance: Int
        get() = synchronized(lock) { _allowance }

    val inFlight: Int
        get() = synchronized(lock) { _inFlight }

    fun resize(newMaximum: Int) {
        synchronized(lock) {
            val clamped = max(1, newMaximum)
            if (clamped == maximum) return
            val untouched = _allowance >= maximum
            maximum = clamped
            _allowance = if (untouched) clamped else max(1, min(_allowance, clamped))
            lock.notifyAll()
        }
    }

    fun acquire() {
        synchronized(lock) {
            while (_inFlight >= _allowance) {
                lock.wait(500L)
            }
            _inFlight++
        }
    }

    fun release() {
        synchronized(lock) {
            _inFlight = max(0, _inFlight - 1)
            lock.notify()
        }
    }

    inline fun <T> withSlot(block: () -> T): T {
        acquire()
        return try {
            block()
        } finally {
            release()
        }
    }

    fun penalise() {
        synchronized(lock) {
            val now = clockMs()
            val last = _lastPenaltyMs
            if (last != null && now - last < PENALTY_INTERVAL_MS) {
                return
            }
            _lastPenaltyMs = now
            _served = 0
            val floor = min(MIN_SEGMENT_ALLOWANCE, maximum)
            _allowance = max(floor, _allowance / 2)
        }
    }

    fun reward() {
        synchronized(lock) {
            if (_allowance >= maximum) return
            _served++
            if (_served >= _allowance) {
                _served = 0
                _allowance++
                lock.notify()
            }
        }
    }
}
