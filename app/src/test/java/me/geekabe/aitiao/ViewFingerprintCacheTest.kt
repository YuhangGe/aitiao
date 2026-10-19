package me.geekabe.aitiao

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewFingerprintCacheTest {

    private val now = 1_700_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    private fun fingerprint(
        createdAt: Long,
        isAdPage: Boolean = true,
        versionCode: Long = 1L,
        width: Int = 1080,
        height: Int = 2400,
        densityDpi: Int = 420
    ) = AdPageFingerprint(
        fingerprint = "abcdef0123456789",
        isAdPage = isAdPage,
        skipXRatio = 0.9f,
        skipYRatio = 0.1f,
        createdAt = createdAt,
        lastUsedAt = createdAt,
        versionCode = versionCode,
        screenWidth = width,
        screenHeight = height,
        densityDpi = densityDpi,
        confirmedByAi = true
    )

    @Test
    fun validPositiveCacheWithinTtl() {
        assertTrue(
            ViewFingerprintCache.isFingerprintValid(
                fingerprint(now - day), now, 1L, 1080, 2400, 420
            )
        )
    }

    @Test
    fun positiveCacheExpiresAfterThirtyDays() {
        assertFalse(
            ViewFingerprintCache.isFingerprintValid(
                fingerprint(now - 31 * day), now, 1L, 1080, 2400, 420
            )
        )
    }

    @Test
    fun validNegativeCacheWithinOneDay() {
        assertTrue(
            ViewFingerprintCache.isFingerprintValid(
                fingerprint(now - 60 * 60 * 1000, isAdPage = false),
                now, 1L, 1080, 2400, 420
            )
        )
    }

    @Test
    fun negativeCacheExpiresAfterOneDay() {
        assertFalse(
            ViewFingerprintCache.isFingerprintValid(
                fingerprint(now - 25 * 60 * 60 * 1000, isAdPage = false),
                now, 1L, 1080, 2400, 420
            )
        )
    }

    @Test
    fun invalidWhenTargetAppVersionChanged() {
        assertFalse(
            ViewFingerprintCache.isFingerprintValid(
                fingerprint(now - day, versionCode = 1L), now, 2L, 1080, 2400, 420
            )
        )
    }

    @Test
    fun invalidWhenScreenSizeChanged() {
        assertFalse(
            ViewFingerprintCache.isFingerprintValid(
                fingerprint(now - day), now, 1L, 1440, 3120, 420
            )
        )
    }

    @Test
    fun invalidWhenDensityChanged() {
        assertFalse(
            ViewFingerprintCache.isFingerprintValid(
                fingerprint(now - day), now, 1L, 1080, 2400, 560
            )
        )
    }

    @Test
    fun invalidWhenCreatedInTheFuture() {
        assertFalse(
            ViewFingerprintCache.isFingerprintValid(
                fingerprint(now + day), now, 1L, 1080, 2400, 420
            )
        )
    }

    @Test
    fun fingerprintHeaderIncludesScreenConfig() {
        val portrait = ViewFingerprintCache.fingerprintHeader(
            "MainActivity", 1080, 2400, 420
        )
        val landscape = ViewFingerprintCache.fingerprintHeader(
            "MainActivity", 2400, 1080, 420
        )
        val otherDensity = ViewFingerprintCache.fingerprintHeader(
            "MainActivity", 1080, 2400, 560
        )

        assertEquals(
            portrait,
            ViewFingerprintCache.fingerprintHeader("MainActivity", 1080, 2400, 420)
        )
        assertTrue(portrait != landscape)
        assertTrue(portrait != otherDensity)
    }
}
