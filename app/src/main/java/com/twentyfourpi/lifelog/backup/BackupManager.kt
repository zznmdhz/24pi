package com.twentyfourpi.lifelog.backup

import android.content.Context
import android.net.Uri
import com.twentyfourpi.lifelog.collector.CollectorWatchdogScheduler
import com.twentyfourpi.lifelog.collector.LifeLogCollectorService
import com.twentyfourpi.lifelog.collector.UsageSyncWorker
import com.twentyfourpi.lifelog.collector.shouldRunCollectorService
import com.twentyfourpi.lifelog.data.DatabaseProvider
import com.twentyfourpi.lifelog.data.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.*
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class BackupManager(
    private val context: Context,
    private val provider: DatabaseProvider,
    private val settings: SettingsStore,
) {
    suspend fun create(uri: Uri, password: CharArray) = withContext(Dispatchers.IO) {
        val tempZip = File.createTempFile("24pi-export-", ".zip", context.cacheDir)
        try {
            provider.get().openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").close()
            ZipOutputStream(tempZip.outputStream().buffered()).use { zip ->
                zip.putNextEntry(ZipEntry("manifest.properties"))
                Properties().apply {
                    setProperty("format", "24pi-life-log")
                    setProperty("version", "1")
                    setProperty("databaseSchema", "9")
                    setProperty("createdAt", System.currentTimeMillis().toString())
                }.store(zip, null)
                zip.closeEntry()

                zip.putNextEntry(ZipEntry("life-log.db"))
                provider.databaseFile().inputStream().use { it.copyTo(zip) }
                zip.closeEntry()

                zip.putNextEntry(ZipEntry("settings.properties"))
                val exported = Properties()
                encodedSettings(settings.export())
                    .filterKeys { it !in RUNTIME_SETTING_KEYS }
                    .forEach { (key, value) ->
                        exported.setProperty(key, value)
                }
                exported.store(zip, null)
                zip.closeEntry()
            }
            context.contentResolver.openOutputStream(uri, "w")?.use { output ->
                tempZip.inputStream().buffered().use { input -> BackupCrypto.encrypt(input, output, password) }
            } ?: error("无法打开目标文件")
        } finally { tempZip.delete() }
    }

    suspend fun restore(uri: Uri, password: CharArray) = withContext(Dispatchers.IO) {
        val decrypted = File.createTempFile("24pi-restore-", ".zip", context.cacheDir)
        val extractedDb = File.createTempFile("24pi-db-", ".sqlite", context.cacheDir)
        val restoredSettings = linkedMapOf<String, String>()
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                decrypted.outputStream().buffered().use { output -> BackupCrypto.decrypt(input, output, password) }
            } ?: error("无法读取备份文件")

            var validManifest = false
            var foundDb = false
            ZipInputStream(decrypted.inputStream().buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    when (entry.name) {
                        "manifest.properties" -> Properties().also { it.load(zip) }.let {
                            validManifest = it.getProperty("format") == "24pi-life-log" && it.getProperty("version") == "1"
                        }
                        "life-log.db" -> extractedDb.outputStream().use { zip.copyTo(it) }.also { foundDb = true }
                        "settings.properties" -> Properties().also { it.load(zip) }.forEach { key, value -> restoredSettings[key.toString()] = value.toString() }
                    }
                    zip.closeEntry()
                }
            }
            require(validManifest) { "备份版本不兼容" }
            require(foundDb && extractedDb.inputStream().use { String(it.readNBytes(16), Charsets.US_ASCII) } == "SQLite format 3\u0000") {
                "备份中的数据库无效"
            }

            val previousSettings = encodedSettings(settings.export())
            val wasEnabled = settings.collectionEnabled
            // Flush every accepted row before the active database is moved aside. Deleting WAL
            // files before this checkpoint could otherwise violate the permanent-record promise.
            provider.get().openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").close()
            // NLS 由系统托管，不会随普通 stopService 停止。先关掉总开关，
            // 防止替换数据库期间仍有通知回调写入旧句柄。
            settings.collectionEnabled = false
            UsageSyncWorker.cancel(context)
            CollectorWatchdogScheduler.cancel(context)
            LifeLogCollectorService.stop(context)
            provider.close()
            val target = provider.databaseFile()
            val rollback = File(target.parentFile, "${target.name}.rollback")
            File(target.path + "-wal").delete(); File(target.path + "-shm").delete()
            if (rollback.exists()) archiveDatabase(rollback)
            if (target.exists() && !target.renameTo(rollback)) error("无法准备数据库恢复")
            try {
                extractedDb.copyTo(target, overwrite = true)
                provider.get().openHelper.readableDatabase.query("PRAGMA user_version").use { cursor ->
                    require(cursor.moveToFirst() && cursor.getInt(0) in 1..9) { "数据库版本不兼容" }
                }
                settings.replace(restoredSettings)
                // 候选停留、采集心跳与监听断档属于运行态，不能跨设备或跨备份时间恢复，
                // 否则下一次定位可能把几天前的访问错误延长到现在。
                settings.remove(
                    "dwell_start", "dwell_last", "dwell_lat", "dwell_lon", "dwell_samples",
                    "dwell_visit", "dwell_place", "dwell_required_ms",
                    "collector_heartbeat_at", "notification_listener_gap_started_at",
                    "notification_listener_heartbeat_at", "location_callback_gap_started_at",
                    "usage_cursor", "usage_active_pkg", "usage_active_start", "screen_active_start",
                    "step_last_at", "step_last_value",
                )
            } catch (error: Exception) {
                provider.close()
                target.delete()
                if (rollback.exists()) rollback.renameTo(target)
                settings.replace(previousSettings)
                if (wasEnabled) {
                    UsageSyncWorker.schedule(context)
                    CollectorWatchdogScheduler.schedule(context, soon = true)
                    if (context.shouldRunCollectorService(settings)) {
                        runCatching { LifeLogCollectorService.start(context) }
                    }
                }
                throw error
            }
            // 恢复会改变当前可见数据库，但恢复前已经记录的历史仍必须永久保留。
            // 将事务回滚副本复制进不可自动清理的本机档案，供未来合并/人工恢复。
            if (rollback.exists()) {
                archiveDatabase(rollback)
            }
            if (settings.collectionEnabled) {
                UsageSyncWorker.schedule(context)
                CollectorWatchdogScheduler.schedule(context, soon = true)
                if (context.shouldRunCollectorService(settings)) {
                    runCatching { LifeLogCollectorService.start(context) }
                }
            }
        } finally { decrypted.delete(); extractedDb.delete() }
    }

    private fun encodedSettings(values: Map<String, *>): Map<String, String> = values.mapValues { (_, value) ->
        when (value) {
            is Boolean -> "b:$value"
            is Long -> "l:$value"
            is Int -> "i:$value"
            is Float -> "f:$value"
            else -> "s:${value ?: ""}"
        }
    }

    private fun archiveDatabase(database: File): File {
        val archiveDirectory = File(context.filesDir, "permanent-database-archives").apply { mkdirs() }
        val archived = File(
            archiveDirectory,
            "life-log-before-restore-${System.currentTimeMillis()}-${java.util.UUID.randomUUID()}.db",
        )
        if (!database.renameTo(archived)) {
            database.copyTo(archived, overwrite = false)
            check(database.delete()) { "恢复前数据库已经归档，但无法移除事务副本" }
        }
        return archived
    }

    companion object {
        private val RUNTIME_SETTING_KEYS = setOf(
            "dwell_start", "dwell_last", "dwell_lat", "dwell_lon", "dwell_samples",
            "dwell_visit", "dwell_place", "dwell_required_ms",
            "collector_heartbeat_at", "notification_listener_gap_started_at",
            "notification_listener_heartbeat_at", "location_callback_gap_started_at",
            "usage_cursor", "usage_active_pkg", "usage_active_start", "screen_active_start",
            "step_last_at", "step_last_value",
        )
    }
}
