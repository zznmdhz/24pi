package com.twentyfourpi.lifelog.ui

import android.graphics.Bitmap
import android.content.ContextWrapper
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.twentyfourpi.lifelog.ai.AiConfig
import com.twentyfourpi.lifelog.ai.AiSettingsStore
import com.twentyfourpi.lifelog.ai.EvidenceRow
import com.twentyfourpi.lifelog.ai.ReviewEvidence
import com.twentyfourpi.lifelog.ai.ReviewRange
import com.twentyfourpi.lifelog.ui.LifeLogTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class AiScreensUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun configurationShowsMaskedSavedKeyAndSavesWithoutExposingIt() {
        var saved = false
        compose.setContent { LifeLogTheme { AiSettingsContent(AiConfig("https://example.com/v1", "demo", true), {}, { _, _, key, _ -> saved = key == null; "已保存" }, { "已清除" }, { callback -> callback("连接成功") }) } }
        compose.onNodeWithText("API Key（已保存；留空则保留）").assertIsDisplayed()
        compose.onNodeWithText("保存配置").performClick()
        assertEquals(true, saved)
        screenshot("ai-settings")
    }

    @Test fun reviewShowsLocalEvidenceAndExplicitDestinationBeforeSend() {
        val day = LocalDate.of(2026, 9, 25)
        val evidence = ReviewEvidence(ReviewRange(day, day.plusDays(1), "合成数据"), day.atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli(), ZoneId.of("UTC"), 1,
            listOf(EvidenceRow(1, "合成公司", 3_600_000, 2)), listOf(EvidenceRow(1, "合成公司", 3_600_000, 2)), 3_600_000,
            listOf(EvidenceRow(null, "合成应用", 900_000)), 2, emptyMap())
        var sent = false
        compose.setContent { LifeLogTheme { AiReviewContent(AiReviewState(question = "这个月在公司待了多长时间", range = evidence.range, evidence = evidence, preview = evidence.preview("这个月在公司待了多长时间"), status = "本地依据已就绪"),
            AiConfig("https://example.com/v1", "demo", true), {}, {}, { _, _, _, _ -> }, {}, {}, {}, { sent = true }, {}) } }
        compose.onNodeWithTag("aiReviewList").performScrollToNode(hasText("公司地点合计：60 分钟（重叠已去除）"))
        compose.onNodeWithText("公司地点合计：60 分钟（重叠已去除）").assertIsDisplayed()
        compose.onNodeWithTag("aiReviewList").performScrollToNode(hasText("将发送给"))
        compose.onNodeWithText("将发送给").assertIsDisplayed()
        compose.onNodeWithText("https://example.com/v1").assertIsDisplayed()
        screenshot("ai-review-evidence")
        compose.onNodeWithText("确认发送并生成总结").performScrollTo()
        compose.onNodeWithText("确认发送并生成总结").performClick()
        assertEquals(true, sent)
    }

    @Test fun keystoreConfigRoundtripHasNoPlaintextAndClearsKey() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dir = File(context.cacheDir, "ai-store-test-${System.nanoTime()}").apply { mkdirs() }
        val isolated = object : ContextWrapper(context) { override fun getNoBackupFilesDir(): File = dir }
        val store = AiSettingsStore(isolated)
        val syntheticKey = "synthetic-test-key-123"
        try {
            store.save("https://example.com/v1", "demo", syntheticKey, "max_completion_tokens")
            assertEquals(syntheticKey, store.key())
            assertEquals("max_completion_tokens", store.read().tokenParameter)
            assertFalse(File(dir, "ai_service.properties").readText().contains(syntheticKey))
            store.clearKey()
            assertEquals(null, store.key())
            assertFalse(store.read().hasKey)
        } finally { File(dir, "ai_service.properties").delete(); dir.delete() }
    }

    private fun screenshot(name: String) {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dir = File(context.getExternalFilesDir("design-review"), "").apply { mkdirs() }
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        File(dir, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
