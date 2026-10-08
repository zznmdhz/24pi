package com.twentyfourpi.lifelog.export

/**
 * CSV 会被 Excel、Sheets 等表格软件自动解释。通知正文、地点名和应用标签都属于
 * 外部可控文本，危险首字符必须转成纯文本，避免导出文件触发表格公式。
 */
internal object CsvEscaper {
    fun cell(value: String): String {
        val flattened = value.replace("\r", " ").replace("\n", " ")
        val safe = if (flattened.firstOrNull() in FORMULA_PREFIXES) "'$flattened" else flattened
        return "\"${safe.replace("\"", "\"\"")}\""
    }

    private val FORMULA_PREFIXES = setOf('=', '+', '-', '@', '\t')
}
