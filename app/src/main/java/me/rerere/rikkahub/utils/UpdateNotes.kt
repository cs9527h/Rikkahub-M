package me.rerere.rikkahub.utils

/**
 * 更新日志的解析与净化，纯 Kotlin、无 Android 依赖，便于单元测试。
 *
 * 目标格式：每个版本最多「新增 / 优化 / 修复 / 其他」四类，空类隐藏，
 * 每条只写这个版本真正改动的用户可感知内容；发布策略、CI、JVM 测试、
 * Lint、README 同步、SHA-256 等开发者细节不再进入应用内展示。
 */
data class NoteSection(val title: String, val items: List<String>)

object UpdateNotes {

    val CATEGORY_ORDER = listOf("新增", "优化", "修复", "其他")

    /** GitHub Release 正文里属于「开发过程」而不是「用户可感知变化」的行。 */
    private val technicalMarkers = listOf(
        "GitHub Actions",
        "Actions",
        "workflow",
        "CI ",
        "CI，",
        "CI 收敛",
        "Jenkins",
        "JVM",
        "单元测试",
        "测试说明",
        "Android Lint",
        "Lint",
        "Lint 通过",
        "gradle",
        "Gradle",
        "README 同步",
        "README",
        "发布策略",
        "发版流程",
        "SHA-256",
        "SHA256",
        "apksigner",
        "签名证书校验",
        "versionCode 校验",
        "性能基线",
        "baseline.json",
        "构建产物",
        "编译说明",
        "依赖升级脚本",
        "待办",
        "TODO",
        "开发计划",
        "roadmap",
        "本次未完成",
        "未验证",
    )

    private val heading = Regex("""^\s*#{2,4}\s*(新增|优化|修复|其他)\s*$""")
    private val boldHeading = Regex("""^\s*\*\*(新增|优化|修复|其他)\*\*\s*[：:]?\s*$""")
    private val plainHeading = Regex("""^\s*(新增|优化|修复|其他)\s*[：:]\s*$""")
    private val bullet = Regex("""^\s*(?:[-*•]|\d+[.、])\s+(.*\S)\s*$""")

    fun isTechnical(line: String): Boolean {
        val text = line.trim()
        if (text.isEmpty()) return false
        return technicalMarkers.any { marker -> text.contains(marker, ignoreCase = true) }
    }

    /**
     * 把 markdown 日志拆成四类。识别 `## 新增`、`**新增**`、`新增：` 三种写法；
     * 没有分类标题时，按顺序把要点归入「其他」，保证仍能显示。
     */
    fun parse(body: String): List<NoteSection> {
        val items = linkedMapOf<String, MutableList<String>>()
        var current: String? = null
        var loose: MutableList<String>? = null

        body.lineSequence().forEach { raw ->
            val line = raw.trimEnd()
            val match = heading.find(line) ?: boldHeading.find(line) ?: plainHeading.find(line)
            if (match != null) {
                current = match.groupValues[1]
                items.getOrPut(current) { mutableListOf() }
                return@forEach
            }
            if (line.startsWith("#")) {
                current = null
                return@forEach
            }
            val bulletText = bullet.find(line)?.groupValues?.get(1)?.trim()
                ?: line.trim().takeIf { it.isNotEmpty() && !it.startsWith(">") }
                ?: return@forEach
            if (isTechnical(bulletText)) return@forEach
            val bucket = current
            if (bucket != null) {
                items.getOrPut(bucket) { mutableListOf() }.add(bulletText)
            } else {
                val target = loose ?: mutableListOf<String>().also { loose = it }
                target.add(bulletText)
            }
        }

        val sections = items.mapNotNull { (title, list) ->
            val cleaned = list.map { it.removePrefix("- ").trim() }.filter { it.isNotEmpty() && !isTechnical(it) }
            if (cleaned.isEmpty()) null else NoteSection(title, cleaned)
        }
        val looseItems = loose
        if (sections.isEmpty() && !looseItems.isNullOrEmpty()) {
            val cleaned = looseItems.filter { !isTechnical(it) }
            if (cleaned.isNotEmpty()) return listOf(NoteSection("其他", cleaned.take(8)))
        }
        return sections.sortedBy { section ->
            CATEGORY_ORDER.indexOf(section.title).let { if (it < 0) CATEGORY_ORDER.size else it }
        }
    }

    /** 净化远程正文：只保留四类结构；若远程全是技术正文，则返回空串而不是整段展示。 */
    fun userFacing(body: String): String {
        val sections = parse(body)
        if (sections.isEmpty()) return ""
        return sections.joinToString("\n\n") { section ->
            buildString {
                append("## ").append(section.title).append('\n')
                section.items.forEach { append("- ").append(it).append('\n') }
            }.trimEnd()
        }
    }

    /** 弹窗摘要：优先用手写 summary，否则取正文前几条要点，最多 [limit] 条。 */
    fun summary(body: String, limit: Int = 3): String {
        val items = parse(body).flatMap { it.items }
        return items.take(limit).joinToString("；") { it.trimEnd('。', '；') } + if (items.isEmpty()) "" else "。"
    }
}
