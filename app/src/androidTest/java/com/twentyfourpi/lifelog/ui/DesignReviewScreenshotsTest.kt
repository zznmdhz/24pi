package com.twentyfourpi.lifelog.ui

import android.graphics.Bitmap
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.twentyfourpi.lifelog.LifeLogApp
import com.twentyfourpi.lifelog.data.AppSessionEntity
import com.twentyfourpi.lifelog.data.NotificationEventEntity
import com.twentyfourpi.lifelog.data.PlaceEntity
import com.twentyfourpi.lifelog.data.PlaceVisitEntity
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Captures actual native surfaces on a clean emulator, without importing a personal archive. */
@RunWith(AndroidJUnit4::class)
class DesignReviewScreenshotsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun captureTimeArchiveAndMy() {
        val app = ApplicationProvider.getApplicationContext<LifeLogApp>()
        val store = ViewModelStore()
        val vm = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(app))[MainViewModel::class.java]
        val page = mutableIntStateOf(0)
        val day = LocalDate.now().minusDays(2)
        val dayStart = day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val previousStartedAt = vm.settings.startedAt
        val previousOnboarding = vm.settings.onboardingCompleted
        vm.settings.startedAt = dayStart
        vm.settings.onboardingCompleted = true
        val fixture = runBlocking {
            val dao = app.databaseProvider.get().dao()
            val home = dao.insertPlace(PlaceEntity(name = "示例 · 家", address = "演示地址 A", latitude = 31.0, longitude = 121.0, isCustomName = true))
            val office = dao.insertPlace(PlaceEntity(name = "示例 · 公司", address = "演示地址 B", latitude = 31.01, longitude = 121.01, isCustomName = true))
            val firstVisit = dao.insertVisit(PlaceVisitEntity(placeId = home, startMs = dayStart + 7 * 3_600_000L, endMs = dayStart + 9 * 3_600_000L))
            val secondVisit = dao.insertVisit(PlaceVisitEntity(placeId = office, startMs = dayStart + 10 * 3_600_000L, endMs = dayStart + 16 * 3_600_000L))
            val appId = dao.insertAppSession(AppSessionEntity(packageName = "example.designreview", appLabel = "示例阅读", startMs = dayStart + 11 * 3_600_000L, endMs = dayStart + 11 * 3_600_000L + 26 * 60_000L))
            val notificationId = dao.insertNotification(NotificationEventEntity(packageName = "example.designreview", appLabel = "示例阅读", occurredMs = dayStart + 12 * 3_600_000L, action = "POSTED"))
            listOf(home, office, firstVisit, secondVisit, appId, notificationId)
        }
        vm.selectedDate.value = day
        try {
            compose.setContent {
                LifeLogTheme {
                    when (page.intValue) {
                        0 -> TimeArchiveDayScreen(vm, onSelectDate = {}, onOpenChapter = { _, _ -> }, onOpenCollectionHealth = {})
                        1 -> ArchiveIndexScreen(vm, onOpenEvidence = {}, onOpenPlaces = {}, onOpenNotificationSettings = {})
                        2 -> SettingsScreen(vm, onSources = {})
                        else -> LifeLogRoot(vm)
                    }
                }
            }
            val output = File(app.getExternalFilesDir("design-review"), "v019")
            output.mkdirs()
            listOf("time", "archive", "my", "root-time").forEachIndexed { index, name ->
                compose.runOnUiThread { page.intValue = index }
                if (index == 0) compose.waitUntil(15_000) {
                    vm.day.value.date == day && vm.day.value.data.visits.any { it.name == "示例 · 公司" }
                }
                compose.waitForIdle()
                FileOutputStream(File(output, "$name.png")).use { stream ->
                    compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, stream)
                }
                if (index == 0) {
                    compose.onRoot().performTouchInput { swipeUp() }
                    compose.onRoot().performTouchInput { swipeUp() }
                    compose.waitForIdle()
                    FileOutputStream(File(output, "time-timeline.png")).use { stream ->
                        compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, stream)
                    }
                }
            }
        } finally {
            store.clear()
            runBlocking {
                val db = app.databaseProvider.get().openHelper.writableDatabase
                db.execSQL("DELETE FROM notification_events WHERE id=${fixture[5]}")
                db.execSQL("DELETE FROM app_sessions WHERE id=${fixture[4]}")
                db.execSQL("DELETE FROM place_visits WHERE id IN (${fixture[2]}, ${fixture[3]})")
                db.execSQL("DELETE FROM places WHERE id IN (${fixture[0]}, ${fixture[1]})")
            }
            vm.settings.startedAt = previousStartedAt
            vm.settings.onboardingCompleted = previousOnboarding
        }
    }
}
