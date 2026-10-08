package com.twentyfourpi.lifelog.collector

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.twentyfourpi.lifelog.debug.DiagnosticLog
import java.util.concurrent.ConcurrentHashMap

internal data class ResolvedAppLabel(
    val value: String,
    val source: String,
    /** Only authoritative labels may rewrite historical database rows. */
    val authoritative: Boolean,
)

/**
 * One package-name resolver shared by usage collection and historical label repair.
 *
 * PackageManager is the source of truth. Launcher labels are a second Android-owned source for
 * OEM packages whose ApplicationInfo lookup is temporarily unavailable. A small set of stable
 * package aliases covers common apps after they have been uninstalled. The final fallback is only
 * for display and is deliberately never written over a previously resolved historical label.
 */
internal class AppLabelResolver(private val context: Context) {
    private val cache = ConcurrentHashMap<String, ResolvedAppLabel>()

    fun resolve(packageName: String): ResolvedAppLabel = cache.getOrPut(packageName) {
        resolveUncached(packageName).also { result ->
            DiagnosticLog.event(
                "usage",
                "app_label_resolution",
                mapOf(
                    "package" to packageName,
                    "label" to result.value,
                    "source" to result.source,
                    "authoritative" to result.authoritative,
                ),
            )
        }
    }

    private fun resolveUncached(packageName: String): ResolvedAppLabel {
        val manager = context.packageManager
        runCatching {
            manager.getApplicationLabel(
                manager.getApplicationInfo(
                    packageName,
                    PackageManager.ApplicationInfoFlags.of(
                        PackageManager.MATCH_UNINSTALLED_PACKAGES.toLong(),
                    ),
                ),
            ).toString()
        }.getOrNull()?.usableLabel(packageName)?.let {
            return ResolvedAppLabel(it, "PACKAGE_MANAGER", authoritative = true)
        }

        runCatching {
            manager.queryIntentActivities(
                Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .setPackage(packageName),
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DISABLED_COMPONENTS.toLong()),
            ).firstNotNullOfOrNull { it.loadLabel(manager)?.toString()?.usableLabel(packageName) }
        }.getOrNull()?.let {
            return ResolvedAppLabel(it, "LAUNCHER_ACTIVITY", authoritative = true)
        }

        knownPackageLabel(packageName)?.let {
            return ResolvedAppLabel(it, "KNOWN_PACKAGE", authoritative = true)
        }
        return ResolvedAppLabel(humanReadablePackageFallback(packageName), "HUMANIZED_FALLBACK", authoritative = false)
    }
}

private fun String.usableLabel(packageName: String): String? = trim()
    .takeIf { it.isNotBlank() && it != packageName && !looksLikePackageOrHost(it) }

internal fun knownPackageLabel(packageName: String): String? = KNOWN_PACKAGE_LABELS[packageName]

/** Never expose a full Java package or host-looking value as the app's primary UI name. */
internal fun humanReadablePackageFallback(packageName: String): String {
    knownPackageLabel(packageName)?.let { return it }
    val candidates = packageName
        .split('.', ':', '/', '_', '-')
        .asReversed()
        .map(String::trim)
        .filter { it.length >= 2 && it.lowercase() !in GENERIC_PACKAGE_PARTS }
    val token = candidates.firstOrNull().orEmpty()
    return token.takeIf(String::isNotBlank)
        ?.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        ?: "未知应用"
}

private fun looksLikePackageOrHost(value: String): Boolean {
    val parts = value.split('.')
    return parts.size >= 3 && parts.all { part ->
        part.isNotBlank() && part.all { it.isLetterOrDigit() || it == '_' || it == '-' }
    }
}

private val GENERIC_PACKAGE_PARTS = setOf(
    "com", "cn", "org", "net", "android", "app", "apps", "mobile", "client", "release", "www",
)

private val KNOWN_PACKAGE_LABELS = mapOf(
    "com.tencent.mm" to "微信",
    "com.tencent.mobileqq" to "QQ",
    "tv.danmaku.bili" to "哔哩哔哩",
    "com.bilibili.app.in" to "哔哩哔哩",
    "com.ss.android.ugc.aweme" to "抖音",
    "com.dragon.read" to "番茄免费小说",
)
