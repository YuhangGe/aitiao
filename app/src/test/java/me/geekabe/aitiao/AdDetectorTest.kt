package me.geekabe.aitiao

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdDetectorTest {

    @Test
    fun parsesPureJson() {
        val result = AdDetector.extractAdResultJson(
            """{"is_ad":true,"has_skip":true,"skip_x":0.9,"skip_y":0.12}"""
        )
        assertTrue(result.isAd)
        assertTrue(result.hasSkip)
        assertEquals(0.9f, result.skipXRatio, 0.0001f)
        assertEquals(0.12f, result.skipYRatio, 0.0001f)
        assertNull(result.error)
    }

    @Test
    fun parsesMarkdownCodeBlock() {
        val result = AdDetector.extractAdResultJson(
            "分析结果如下：\n```json\n" +
                """{"is_ad":true,"has_skip":true,"skip_x":0.88,"skip_y":0.92}""" +
                "\n```\n请查收。"
        )
        assertTrue(result.isAd)
        assertTrue(result.hasSkip)
        assertEquals(0.88f, result.skipXRatio, 0.0001f)
    }

    @Test
    fun parsesJsonWithProse() {
        val result = AdDetector.extractAdResultJson(
            """这是一张开屏广告截图，结果如下 {"is_ad":true,"has_skip":true,"skip_x":0.5,"skip_y":0.8} 以上。"""
        )
        assertTrue(result.isAd)
        assertTrue(result.hasSkip)
        assertEquals(0.5f, result.skipXRatio, 0.0001f)
    }

    @Test
    fun handlesNoSkipButton() {
        val result = AdDetector.extractAdResultJson(
            """{"is_ad":true,"has_skip":false,"skip_x":0,"skip_y":0}"""
        )
        assertTrue(result.isAd)
        assertFalse(result.hasSkip)
        assertNull(result.error)
    }

    @Test
    fun rejectsMissingCoordinatesWhenSkipDeclared() {
        val result = AdDetector.extractAdResultJson(
            """{"is_ad":true,"has_skip":true}"""
        )
        assertTrue(result.isAd)
        assertFalse(result.hasSkip)
        assertNotNull(result.error)
    }

    @Test
    fun rejectsNonNumericCoordinates() {
        val result = AdDetector.extractAdResultJson(
            """{"is_ad":true,"has_skip":true,"skip_x":"abc","skip_y":0.1}"""
        )
        assertTrue(result.isAd)
        assertFalse(result.hasSkip)
        assertNotNull(result.error)
    }

    @Test
    fun rejectsOutOfRangeCoordinates() {
        val result = AdDetector.extractAdResultJson(
            """{"is_ad":true,"has_skip":true,"skip_x":1.5,"skip_y":0.1}"""
        )
        assertTrue(result.isAd)
        assertFalse(result.hasSkip)
        assertNotNull(result.error)
    }

    @Test
    fun rejectsZeroCoordinates() {
        val result = AdDetector.extractAdResultJson(
            """{"is_ad":true,"has_skip":true,"skip_x":0,"skip_y":0}"""
        )
        assertTrue(result.isAd)
        assertFalse(result.hasSkip)
        assertNotNull(result.error)
    }

    @Test
    fun supportsLegacyFormatWithoutHasSkip() {
        val result = AdDetector.extractAdResultJson(
            """{"is_ad":true,"skip_x":0.93,"skip_y":0.08}"""
        )
        assertTrue(result.isAd)
        assertTrue(result.hasSkip)
        assertEquals(0.93f, result.skipXRatio, 0.0001f)
    }

    @Test
    fun returnsNotAdForNonAdPage() {
        val result = AdDetector.extractAdResultJson(
            """{"is_ad":false,"has_skip":false,"skip_x":0,"skip_y":0}"""
        )
        assertFalse(result.isAd)
        assertFalse(result.hasSkip)
        assertNull(result.error)
    }

    @Test
    fun returnsErrorForUnparseableContent() {
        val result = AdDetector.extractAdResultJson("not json at all")
        assertFalse(result.isAd)
        assertNotNull(result.error)
    }

    @Test
    fun confirmParsesClickedSkip() {
        val result = AdDetector.extractConfirmResultJson(
            """{"is_ad":true,"clicked_is_skip":true,"has_skip":true,"skip_x":0.9,"skip_y":0.1}"""
        )
        assertTrue(result.isAd)
        assertTrue(result.clickedIsSkip)
        assertTrue(result.hasSkip)
        assertEquals(0.9f, result.skipXRatio, 0.0001f)
        assertEquals(0.1f, result.skipYRatio, 0.0001f)
        assertNull(result.error)
    }

    @Test
    fun confirmParsesRealSkipWhenClickedTextWasWrong() {
        val result = AdDetector.extractConfirmResultJson(
            """{"is_ad":true,"clicked_is_skip":false,"has_skip":true,"skip_x":0.8,"skip_y":0.9}"""
        )
        assertTrue(result.isAd)
        assertFalse(result.clickedIsSkip)
        assertTrue(result.hasSkip)
        assertEquals(0.8f, result.skipXRatio, 0.0001f)
    }

    @Test
    fun confirmForcesClickedSkipFalseWhenNotAd() {
        val result = AdDetector.extractConfirmResultJson(
            """{"is_ad":false,"clicked_is_skip":true,"has_skip":true,"skip_x":0.9,"skip_y":0.1}"""
        )
        assertFalse(result.isAd)
        assertFalse(result.clickedIsSkip)
        assertFalse(result.hasSkip)
    }

    @Test
    fun confirmAcceptsClickedSkipWithoutCoordinates() {
        val result = AdDetector.extractConfirmResultJson(
            """{"is_ad":true,"clicked_is_skip":true,"has_skip":false}"""
        )
        assertTrue(result.isAd)
        assertTrue(result.clickedIsSkip)
        assertFalse(result.hasSkip)
    }

    @Test
    fun confirmRejectsInvalidRealSkipCoordinates() {
        val result = AdDetector.extractConfirmResultJson(
            """{"is_ad":true,"clicked_is_skip":false,"has_skip":true,"skip_x":1.5,"skip_y":0.1}"""
        )
        assertTrue(result.isAd)
        assertFalse(result.clickedIsSkip)
        assertFalse(result.hasSkip)
        assertNotNull(result.error)
    }

    @Test
    fun confirmKeepsClickedSkipWhenRealSkipCoordsInvalid() {
        val result = AdDetector.extractConfirmResultJson(
            """{"is_ad":true,"clicked_is_skip":true,"has_skip":true,"skip_x":1.5,"skip_y":0.1}"""
        )
        assertTrue(result.isAd)
        assertTrue(result.clickedIsSkip)
        assertFalse(result.hasSkip)
        assertNull(result.error)
    }

    @Test
    fun confirmPromptCarriesClickedTextAndSchema() {
        val prompt = AdDetector.confirmPrompt("跳过")
        assertTrue(prompt.contains("跳过"))
        assertTrue(prompt.contains("clicked_is_skip"))
        assertTrue(prompt.contains("红圈"))
    }
}
