package com.twentyfourpi.lifelog.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.twentyfourpi.lifelog.LifeLogApp
import com.twentyfourpi.lifelog.data.PlaceEntity
import com.twentyfourpi.lifelog.data.PlaceKind
import com.twentyfourpi.lifelog.data.PlaceVisitEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** UI workflow uses only synthetic places and removes its rows after capture. */
@RunWith(AndroidJUnit4::class)
class PlaceManagementUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun renameMarkMergePreviewAndUndo() {
        val app = ApplicationProvider.getApplicationContext<LifeLogApp>()
        val dao = app.databaseProvider.get().dao()
        val source = runBlocking { dao.insertPlace(PlaceEntity(name="合成 A", address="合成旧地址", latitude=31.0, longitude=121.0)) }
        val target = runBlocking { dao.insertPlace(PlaceEntity(name="合成 B", address="合成目标地址", latitude=31.001, longitude=121.001)) }
        val visit = runBlocking { dao.insertVisit(PlaceVisitEntity(placeId=source, startMs=1000, endMs=5000)) }
        val store = ViewModelStore()
        val vm = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(app))[MainViewModel::class.java]
        try {
            compose.setContent { LifeLogTheme { PlacesScreen(vm, initialManagement = true) } }
            compose.waitUntil(10_000) { vm.places.value.any { it.id == source } && vm.places.value.any { it.id == target } }
            compose.onNodeWithTag("placeSearchInput").performTextInput("合成")
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("placeEdit-$source").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("placesList").performScrollToNode(hasTestTag("placeEdit-$source"))
            compose.onNodeWithTag("placeEdit-$source").performClick()
            compose.onNodeWithTag("placeNameInput").performTextReplacement("合成客户")
            compose.onNodeWithText("客户").performClick()
            compose.onNodeWithText("保存").performClick()
            compose.waitUntil(10_000) { runBlocking { dao.placeById(source)?.name == "合成客户" } }
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("placeNameInput").fetchSemanticsNodes().isEmpty() }
            assertEquals(PlaceKind.CUSTOMER, runBlocking { dao.placeById(source)!!.kind })
            val screenshot = File(app.getExternalFilesDir("design-review"), "place-management.png")
            screenshot.parentFile?.mkdirs()
            screenshot.outputStream().use {
                compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            compose.onNodeWithTag("placesList").performScrollToNode(hasTestTag("placeMenu-$source"))
            compose.onNodeWithTag("placeMenu-$source").performClick()
            compose.onNodeWithText("归入另一地点").performClick()
            compose.onNodeWithText("合成 B ·", substring = true).performClick()
            compose.onNodeWithText("把 合成客户 归入 合成 B").assertIsDisplayed()
            compose.onNodeWithText("确认归并").performClick()
            compose.waitUntil(10_000) { runBlocking { dao.placeById(source)?.mergedIntoPlaceId == target } }
            assertEquals(source, runBlocking { dao.visitById(visit)!!.placeId })
            assertEquals(target, runBlocking { dao.visitsInRange(0,6000).first { it.id == visit }.placeId })
            compose.onNodeWithTag("placesList").performScrollToNode(hasTestTag("placeMenu-$source"))
            compose.onNodeWithTag("placeMenu-$source").performClick()
            compose.onNodeWithText("撤销归并").performClick()
            compose.waitUntil(10_000) { runBlocking { dao.placeById(source)?.mergedIntoPlaceId == null } }
            assertNull(runBlocking { dao.placeById(source)!!.mergedIntoPlaceId })
            assertEquals(source, runBlocking { dao.visitsInRange(0,6000).first { it.id == visit }.placeId })
        } finally {
            store.clear()
            app.databaseProvider.get().openHelper.writableDatabase.apply {
                execSQL("DELETE FROM place_visits WHERE id=$visit")
                execSQL("DELETE FROM attribution_revisions WHERE target_key IN ('place:$source','place:$target')")
                execSQL("DELETE FROM places WHERE id IN ($source,$target)")
            }
        }
    }
}
