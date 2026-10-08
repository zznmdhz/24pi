package com.twentyfourpi.lifelog.collector

import android.app.Notification

data class StoredNotificationContent(val title: String?, val body: String?) {
    val isEmpty: Boolean get() = title.isNullOrBlank() && body.isNullOrBlank()
}

/** 只提取用户明确同意保存的可见文本，不读取联系人、图片、Intent 或消息附件。 */
object NotificationContentExtractor {
    fun extract(notification: Notification): StoredNotificationContent {
        val extras = notification.extras
        val title = sanitize(
            extras.getCharSequence(Notification.EXTRA_TITLE_BIG)
                ?: extras.getCharSequence(Notification.EXTRA_TITLE),
            200,
        )
        // InboxStyle 等通知会把多行可见文本放在 EXTRA_TEXT_LINES；只取第一项会把
        // 通知详情截成一句。这里仍只读取通知栏已经展示的文本，不读取附件、联系人或 Intent。
        val body = combineVisibleBody(
            buildList {
                add(extras.getCharSequence(Notification.EXTRA_BIG_TEXT))
                add(extras.getCharSequence(Notification.EXTRA_TEXT))
                extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.forEach(::add)
                add(extras.getCharSequence(Notification.EXTRA_SUB_TEXT))
                add(extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT))
            },
            title = title,
        )
        return StoredNotificationContent(title, body)
    }

    internal fun combineVisibleBody(values: List<CharSequence?>, title: String?): String? {
        val parts = linkedSetOf<String>()
        values.forEach { value ->
            sanitize(value, 2_000)?.takeIf { it != title }?.let(parts::add)
        }
        return sanitize(parts.joinToString(" · "), 4_000)
    }

    fun sanitize(value: CharSequence?, maxLength: Int): String? = value?.toString()
        ?.replace(Regex("[\\r\\n\\t]+"), " ")
        ?.replace(Regex(" {2,}"), " ")
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?.take(maxLength)
}
