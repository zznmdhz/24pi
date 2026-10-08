package com.twentyfourpi.lifelog.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.twentyfourpi.lifelog.LifeLogApp
import com.twentyfourpi.lifelog.data.LocationPointEntity
import com.twentyfourpi.lifelog.data.TripSummary
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class TripFlowUiTest {
    @get:Rule val compose=createComposeRule()

    @Test fun historicalTripOpensDetailsAndReturnsToListWithoutMapKey() {
        val app=ApplicationProvider.getApplicationContext<LifeLogApp>()
        val store=ViewModelStore()
        val date=LocalDate.of(2001,1,2)
        val start=date.atStartOfDay(ZoneId.systemDefault()).plusHours(9).toInstant().toEpochMilli()
        val ids=runBlocking {
            (0..2).map { i -> app.databaseProvider.get().dao().insertLocationPoint(LocationPointEntity(
                recordedMs=start+i*30_000,measuredMs=start+i*30_000,latitude=31.0+i*0.005,
                longitude=121.0,accuracyM=10f,provider="ui-test",speedMps=2f)) }
        }
        val vm=ViewModelProvider(store,ViewModelProvider.AndroidViewModelFactory(app))[MainViewModel::class.java]
        try {
            compose.setContent {
                var selected by remember { mutableStateOf<TripSummary?>(null) }
                MaterialTheme {
                    if(selected==null) TripArchiveScreen(vm,date,onBack={},onTrip={selected=it})
                    else TripDetailScreen(vm,date,selected!!.key,onBack={selected=null},onRecords={_,_->})
                }
            }
            compose.waitUntil(30_000) { compose.onAllNodesWithText("09:00—09:01").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("09:00—09:01").performScrollTo().performClick()
            compose.onNodeWithText("行程详情").assertIsDisplayed()
            compose.waitUntil(30_000) { compose.onAllNodesWithText("查看这一时段的记录").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("查看这一时段的记录").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("返回").performClick()
            compose.waitUntil(30_000) { compose.onAllNodesWithText("09:00—09:01").fetchSemanticsNodes().isNotEmpty() }
        } finally {
            store.clear()
            runBlocking { app.databaseProvider.get().openHelper.writableDatabase.execSQL("DELETE FROM location_points WHERE id IN (${ids.joinToString()})") }
        }
    }
}
