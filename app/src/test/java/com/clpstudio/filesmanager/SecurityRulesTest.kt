package com.clpstudio.filesmanager

import com.clpstudio.filesmanager.security.AppLock
import com.clpstudio.filesmanager.security.AskAfter
import com.clpstudio.filesmanager.security.AttemptLimiter
import com.clpstudio.filesmanager.security.AttemptState
import com.clpstudio.filesmanager.security.CredentialRules
import com.clpstudio.filesmanager.security.CredentialType
import com.clpstudio.filesmanager.security.LockTimer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** PIN / password rules, wrong-try waits, lock timing and the app-lock verifier. */
class SecurityRulesTest {
    @Test fun pinRules() {
        assertNull(CredentialRules.error(CredentialType.PIN, "1234"))
        assertNull(CredentialRules.error(CredentialType.PIN, "1234567890"))
        assertNotNull(CredentialRules.error(CredentialType.PIN, "123"))
        assertNotNull(CredentialRules.error(CredentialType.PIN, "12345678901"))
        assertNotNull(CredentialRules.error(CredentialType.PIN, "12a4"))
        assertNotNull(CredentialRules.error(CredentialType.PIN, ""))
        assertEquals("1234567890", CredentialRules.filterInput(CredentialType.PIN, "12-34 56a7890999"))
    }

    @Test fun passwordRules() {
        assertNull(CredentialRules.error(CredentialType.PASSWORD, "abcdefgh"))
        assertNotNull(CredentialRules.error(CredentialType.PASSWORD, "abcdefg"))
        assertNull(CredentialRules.error(CredentialType.PASSWORD, "x".repeat(72)))
        assertNotNull(CredentialRules.error(CredentialType.PASSWORD, "x".repeat(73)))
        assertNotNull(CredentialRules.error(CredentialType.PASSWORD, "        "))
        assertEquals(72, CredentialRules.filterInput(CredentialType.PASSWORD, "y".repeat(100)).length)
    }

    @Test fun confirmMismatch() {
        assertEquals("The PINs don't match", CredentialRules.confirmError(CredentialType.PIN, "1234", "1235"))
        assertEquals("The passwords don't match", CredentialRules.confirmError(CredentialType.PASSWORD, "abcdefgh", "abcdefgi"))
        assertNull(CredentialRules.confirmError(CredentialType.PIN, "1234", "1234"))
        // the rule error comes first
        assertEquals("At least 4 digits", CredentialRules.confirmError(CredentialType.PIN, "12", "12"))
    }

    @Test fun fiveFreeTriesThenEscalatingLockouts() {
        var s = AttemptState()
        val c0 = com.clpstudio.filesmanager.security.LockClock(1_000_000L, 7)
        repeat(4) {
            s = AttemptLimiter.onFailure(s, c0)
            assertEquals(0L, AttemptLimiter.remaining(s, c0))
        }
        assertEquals("Wrong. 1 try left", AttemptLimiter.message(s, c0))
        s = AttemptLimiter.onFailure(s, c0) // 5th
        assertEquals(30_000L, AttemptLimiter.remaining(s, c0))
        assertEquals("Too many wrong tries. Try again in 0:30", AttemptLimiter.message(s, c0))
        val c1 = c0.copy(elapsed = c0.elapsed + 30_000)
        assertEquals(0L, AttemptLimiter.remaining(s, c1))
        s = AttemptLimiter.onFailure(s, c1) // 6th
        assertEquals(60_000L, AttemptLimiter.remaining(s, c1))
        assertEquals(AttemptLimiter.MAX_WAIT_MS, AttemptLimiter.waitAfter(50))
        assertEquals(AttemptState(), AttemptLimiter.onSuccess())
        assertNull(AttemptLimiter.message(AttemptState(), c0))
    }

    @Test fun lockTimerColdStartAndAskAfter() {
        val t = LockTimer()
        t.onAppStart(enabled = true)
        assertTrue(t.locked)
        t.onUnlocked()
        assertFalse(t.locked)
        // 1 minute: back after 30 s stays open, after 61 s asks
        t.onBackground(true, AskAfter.MIN_1, 1_000)
        t.onForeground(true, AskAfter.MIN_1, 31_000)
        assertFalse(t.locked)
        t.onBackground(true, AskAfter.MIN_1, 40_000)
        t.onForeground(true, AskAfter.MIN_1, 101_000)
        assertTrue(t.locked)
        t.onUnlocked()
        // Immediately: locks as soon as it goes to the background
        t.onBackground(true, AskAfter.IMMEDIATELY, 200_000)
        assertTrue(t.locked)
        t.onDisabled()
        assertFalse(t.locked)
        t.onAppStart(enabled = false)
        assertFalse(t.locked)
        t.onBackground(false, AskAfter.IMMEDIATELY, 0)
        t.onForeground(false, AskAfter.IMMEDIATELY, 10)
        assertFalse(t.locked)
    }

    @Test fun appLockVerifier() {
        val (salt, hash, iters) = AppLock.Verifier.make("2580", iterations = 1000)
        assertTrue(AppLock.Verifier.check("2580", salt, hash, iters))
        assertFalse(AppLock.Verifier.check("2581", salt, hash, iters))
        assertFalse(AppLock.Verifier.check("2580", salt, hash, iters + 1))
        assertFalse(hash.contains("2580"))
        val (salt2, hash2, _) = AppLock.Verifier.make("2580", iterations = 1000)
        assertFalse(salt == salt2 || hash == hash2)
        assertFalse(AppLock.Verifier.check("2580", "%%%", hash, iters))
    }
}
