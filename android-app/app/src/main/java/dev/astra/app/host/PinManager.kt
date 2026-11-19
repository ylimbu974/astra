package dev.astra.app.host

import android.os.SystemClock
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The rotating 6-digit PIN this phone shows while it shares its screen. Same rules as the
 * host agent's pin.py:
 *  * a new PIN every 10 minutes;
 *  * 3 wrong tries -> a new PIN and PIN entry locks for 5 minutes;
 *  * the PIN is only shown on this phone's screen, never sent to the Pi by this phone.
 */
class PinManager(private val ttlMs: Long = 10 * 60_000L) {
    data class Result(val ok: Boolean, val error: String? = null, val attemptsLeft: Int = MAX_ATTEMPTS, val retryAfter: Int = 0)
    data class Snapshot(val pin: String, val secondsLeft: Int, val lockedFor: Int)

    private val random = SecureRandom()
    private var pin = ""
    private var expires = 0L
    private var failures = 0
    private var lockedUntil = 0L

    init { newPin() }

    private fun now() = SystemClock.elapsedRealtime()

    private fun newPin() {
        pin = "%06d".format(random.nextInt(1_000_000))
        expires = now() + ttlMs
        failures = 0
    }

    private fun maybeRotate() { if (now() >= expires) newPin() }

    @Synchronized
    fun snapshot(): Snapshot {
        maybeRotate()
        val t = now()
        return Snapshot(pin, ((expires - t) / 1000).toInt().coerceAtLeast(0), ((lockedUntil - t) / 1000).toInt().coerceAtLeast(0))
    }

    @Synchronized
    fun rotate() = newPin()

    @Synchronized
    fun check(candidate: String): Result {
        maybeRotate()
        val t = now()
        if (t < lockedUntil) return Result(false, "locked", 0, ((lockedUntil - t) / 1000).toInt() + 1)
        if (MessageDigest.isEqual(candidate.toByteArray(), pin.toByteArray())) {
            failures = 0
            return Result(true)
        }
        failures++
        return if (failures >= MAX_ATTEMPTS) {
            newPin()
            lockedUntil = t + LOCK_MS
            Result(false, "locked", 0, (LOCK_MS / 1000).toInt())
        } else {
            Result(false, "wrong_pin", MAX_ATTEMPTS - failures)
        }
    }

    companion object {
        const val MAX_ATTEMPTS = 3
        const val LOCK_MS = 5 * 60_000L
    }
}
