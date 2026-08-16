package me.pipi.easyshare

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionSecurityTest {
    @Test
    fun tokensAreUniqueAndUrlSafe() {
        val first = SessionSecurity.generateToken()
        val second = SessionSecurity.generateToken()

        assertNotEquals(first, second)
        assertTrue(first.matches(Regex("[A-Za-z0-9_-]{43}")))
    }

    @Test
    fun constantTimeComparisonRejectsMissingOrDifferentValues() {
        assertTrue(SessionSecurity.constantTimeEquals("same", "same"))
        assertFalse(SessionSecurity.constantTimeEquals("same", "other"))
        assertFalse(SessionSecurity.constantTimeEquals("same", null))
    }
}
