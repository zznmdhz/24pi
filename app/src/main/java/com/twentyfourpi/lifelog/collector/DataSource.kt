package com.twentyfourpi.lifelog.collector

import com.twentyfourpi.lifelog.data.SourceId
import com.twentyfourpi.lifelog.data.SourceState

data class DataSourceHealth(
    val source: SourceId,
    val state: SourceState,
    val detail: String,
    val lastUpdatedMs: Long? = null,
)

interface LifeLogDataSource {
    val id: SourceId
    suspend fun health(): DataSourceHealth
    suspend fun collect(nowMs: Long = System.currentTimeMillis())
}
