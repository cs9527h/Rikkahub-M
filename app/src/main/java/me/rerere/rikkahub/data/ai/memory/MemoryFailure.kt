package me.rerere.rikkahub.data.ai.memory

import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.serialization.SerializationException

/**
 * 整理失败发生在哪一步。
 *
 * 存进数据库的是 [id] 这个稳定字符串，不是 ordinal：以后加阶段、调顺序都不会把老记录读错。
 * 用户看到的失败原因必须能定位到阶段，否则“读取历史或调用模型失败”这句话对所有问题都一样，
 * 既没法自救，也没法反馈。
 */
internal enum class MemoryStage(val id: String, val label: String) {
    READING_HISTORY("reading_history", "读取历史"),
    MODEL_CONFIG("model_config", "检查模型配置"),
    PROMPT_BUILD("prompt_build", "构建请求"),
    MODEL_CALL("model_call", "调用模型"),
    RESPONSE_PARSE("response_parse", "解析模型返回"),
    DB_WRITE("db_write", "写入本地数据库"),
    WORKER_SCHEDULE("worker_schedule", "调度后台任务"),
    UNKNOWN("unknown", "未知阶段"),
    ;

    companion object {
        fun from(id: String?): MemoryStage =
            id?.let { value -> entries.firstOrNull { it.id == value } } ?: UNKNOWN
    }
}

/**
 * 这个阶段的失败是否可能已经产生了模型费用。
 * 只有真正发出过请求之后（调用、解析、写库）才累计批次尝试次数：
 * 配置类错误会把“批次”白白用掉，用户改好配置还得重跑。
 */
internal val MemoryStage.isBillable: Boolean
    get() = this == MemoryStage.MODEL_CALL ||
        this == MemoryStage.RESPONSE_PARSE ||
        this == MemoryStage.DB_WRITE

/** 稳定的错误码：阶段说明“在哪里”，错误码说明“为什么”。 */
internal object MemoryFailureCode {
    const val TIMEOUT = "timeout"
    const val NETWORK = "network"
    const val AUTH = "auth"
    const val QUOTA = "quota"
    const val CONTEXT = "context_too_long"
    const val REQUEST = "request_rejected"
    const val PARSE = "response_parse"
    const val DATABASE = "database"
    const val CONFIG = "config"
    const val CONSENT = "consent"
    const val SOURCE = "source_changed"
    const val HISTORY = "history_read"
    const val UNKNOWN = "unknown"
}

/**
 * 一次结构化的失败。
 *
 * [message] 是给用户看的一句话，[advice] 是可执行建议，[detail] 是可以复制给开发者的技术信息：
 * 只含阶段、错误码、异常类名、HTTP 状态码和截断/脱敏后的异常摘要，**不含聊天正文、候选正文、
 * API Key、token 或带查询串的完整 URL**。
 */
internal data class MemoryFailure(
    val stage: MemoryStage,
    val code: String,
    val message: String,
    val advice: String,
    val detail: String,
) {
    /** 写进任务行、toast 和历史列表的那句话。 */
    val userText: String get() = if (advice.isBlank()) message else "$message$advice"
}

/**
 * 文案的唯一来源：写入失败和展示历史都从这里取，避免“存的时候一套、看的时候另一套”。
 */
internal object MemoryFailureText {
    fun message(code: String): String = when (code) {
        MemoryFailureCode.TIMEOUT -> "模型没有在限定时间内响应。"
        MemoryFailureCode.NETWORK -> "调用模型时网络中断，或模型服务返回了服务端错误。"
        MemoryFailureCode.AUTH -> "模型服务拒绝了登录凭据（401/403）。"
        MemoryFailureCode.QUOTA -> "模型服务返回限流或额度不足（429）。"
        MemoryFailureCode.CONTEXT -> "这一批聊天内容超出了模型能接受的长度。"
        MemoryFailureCode.REQUEST -> "模型服务判定这次请求无效。"
        MemoryFailureCode.PARSE -> "模型返回的内容不是有效的候选 JSON。"
        MemoryFailureCode.DATABASE -> "把结果写入本地数据库时失败。"
        MemoryFailureCode.CONFIG -> "已确认的模型或提供商配置已经不可用。"
        MemoryFailureCode.CONSENT -> "这台设备上的历史提取授权已经失效。"
        MemoryFailureCode.SOURCE -> "这批来源会话已经被修改或删除。"
        MemoryFailureCode.HISTORY -> "读取历史时遇到无法解析的消息数据。"
        else -> "整理在中途失败。"
    }

    fun advice(code: String): String = when (code) {
        MemoryFailureCode.TIMEOUT,
        MemoryFailureCode.NETWORK,
        -> "点“从这一批继续”会从当前批次接着跑；已经完成的批次不会再次调用模型，也不会重复计费。"

        MemoryFailureCode.AUTH ->
            "到设置里检查这家提供商的 API Key 或账号状态，修好后点“从这一批继续”：进度和已提取的候选都还在。"

        MemoryFailureCode.QUOTA ->
            "等一段时间，或换成还有额度的模型再继续；短时间内反复重试通常不会恢复。"

        MemoryFailureCode.CONTEXT ->
            "换一个上下文更长的模型后重新开始整理；已经提取出来的候选不会丢。"

        MemoryFailureCode.REQUEST ->
            "检查模型名称、参数和账号权限，改好后再继续；已完成的批次不会重复调用。"

        MemoryFailureCode.PARSE ->
            "重试会重新调用这一批模型；同一批连续失败会自动跳过并记录在这里，不会无限扣费。"

        MemoryFailureCode.DATABASE ->
            "可能是存储空间不足或数据库被占用；腾出空间后点“从这一批继续”，已保存的记忆和候选不会被覆盖。"

        MemoryFailureCode.CONFIG ->
            "到设置里恢复这个模型或提供商，然后重新开始整理；候选和历史记录都还在。"

        MemoryFailureCode.CONSENT ->
            "重新确认提取范围后再继续，旧任务不会在没有授权的情况下发送聊天记录。"

        MemoryFailureCode.SOURCE ->
            "被改动的会话会被跳过，其他会话继续整理；不需要为它反复重试。"

        MemoryFailureCode.HISTORY ->
            "这段会话会被跳过，其他会话继续整理；已保存的记忆和候选不受影响。"

        else ->
            "点“从这一批继续”会从当前批次接着跑；如果一直失败，可以复制下面的诊断信息反馈。"
    }

    fun label(code: String): String = when (code) {
        MemoryFailureCode.TIMEOUT -> "超时"
        MemoryFailureCode.NETWORK -> "网络或服务端错误"
        MemoryFailureCode.AUTH -> "登录凭据被拒绝"
        MemoryFailureCode.QUOTA -> "限流或额度不足"
        MemoryFailureCode.CONTEXT -> "上下文超长"
        MemoryFailureCode.REQUEST -> "请求被拒绝"
        MemoryFailureCode.PARSE -> "返回格式无效"
        MemoryFailureCode.DATABASE -> "写库失败"
        MemoryFailureCode.CONFIG -> "配置不可用"
        MemoryFailureCode.CONSENT -> "授权失效"
        MemoryFailureCode.SOURCE -> "来源已变更"
        MemoryFailureCode.HISTORY -> "历史数据无法解析"
        else -> "未知错误"
    }

    /** 历史列表/详情里合起来展示的那一行。 */
    fun failedLine(stage: MemoryStage, code: String): String =
        "在「${stage.label}」阶段失败：${message(code)}"
}

/**
 * 脱敏：异常信息里可能混着接口地址、请求头、Key，甚至回显的正文片段。
 * 只保留“类名 + 截断摘要 + 状态码”这类可诊断信息。
 */
internal fun sanitizeFailureDetail(text: String?, limit: Int = 200): String {
    if (text.isNullOrBlank()) return ""
    var value = text
    // Bearer / Authorization
    value = value.replace(Regex("(?i)\\b(bearer)\\s+[A-Za-z0-9._~+/=\\-]{4,}"), "$1 ***")
    // sk-xxx / sk-proj-xxx 之类的密钥
    value = value.replace(Regex("(?i)\\bsk-[A-Za-z0-9_\\-]{4,}"), "sk-***")
    // key=value 形式的凭据
    value = value.replace(
        Regex(
            "(?i)(api[_-]?key|access[_-]?token|refresh[_-]?token|secret|password|authorization)" +
                "(\\s*[:=]\\s*)[^\\s\"',;}]{4,}"
        ),
        "$1$2***",
    )
    // URL 只保留 scheme + host + path，去掉查询串、片段和 userinfo
    value = value.replace(Regex("(?i)\\bhttps?://[^\\s\"'<>]+")) { match ->
        val raw = match.value
        val head = raw.substringBefore('?').substringBefore('#')
        val masked = head.replace(Regex("//[^/]*@"), "//***@")
        if (masked.length <= 80) masked else masked.take(79) + "…"
    }
    // 压成一行，避免把长正文（例如模型回显）原样存进数据库
    value = value.replace(Regex("\\s+"), " ").trim()
    return if (value.length <= limit) value else value.take(limit) + "…"
}

/** 从异常链里取 HTTP 状态码；拿不到就是 null，绝不猜。 */
internal fun httpStatusOf(error: Throwable): Int? {
    var current: Throwable? = error
    var guard = 0
    while (current != null && guard < 8) {
        Regex("\\b([45]\\d{2})\\b").find(current.message.orEmpty())?.let { match ->
            return match.groupValues[1].toIntOrNull()
        }
        current = current.cause?.takeIf { it !== current }
        guard++
    }
    return null
}

private fun Throwable.rootCause(): Throwable {
    var current: Throwable = this
    var guard = 0
    while (guard < 8) {
        val next = current.cause ?: return current
        if (next === current) return current
        current = next
        guard++
    }
    return current
}

/** 数据库异常的类名判断走字符串，JVM 单测里不需要 Android 运行时。 */
private fun Throwable.isDatabaseFailure(): Boolean {
    val name = javaClass.name
    return name.contains("SQLite", ignoreCase = true) ||
        name.startsWith("android.database.") ||
        name.startsWith("androidx.sqlite.")
}

/** 把任意异常映射成“阶段 + 错误码 + 用户文案 + 脱敏诊断”。 */
internal fun memoryFailure(stage: MemoryStage, error: Throwable): MemoryFailure {
    val root = error.rootCause()
    val status = httpStatusOf(error)
    val summary = sanitizeFailureDetail(root.message)
    val contextHint = root.message.orEmpty().lowercase()
    val code = when {
        error is TimeoutCancellationException ||
            root is SocketTimeoutException ||
            root is InterruptedIOException -> MemoryFailureCode.TIMEOUT

        status == 401 || status == 403 -> MemoryFailureCode.AUTH
        status == 429 -> MemoryFailureCode.QUOTA
        status == 413 || (status == 400 && contextHint.contains("context")) ||
            (status == 400 && contextHint.contains("maximum")) ||
            contextHint.contains("context length") ||
            contextHint.contains("too many tokens") -> MemoryFailureCode.CONTEXT

        status != null && status >= 500 -> MemoryFailureCode.NETWORK
        status == 400 || status == 404 || status == 422 -> MemoryFailureCode.REQUEST
        root.isDatabaseFailure() -> MemoryFailureCode.DATABASE
        root is SerializationException -> MemoryFailureCode.PARSE
        root is IOException -> MemoryFailureCode.NETWORK
        stage == MemoryStage.RESPONSE_PARSE -> MemoryFailureCode.PARSE
        stage == MemoryStage.MODEL_CONFIG -> MemoryFailureCode.CONFIG
        stage == MemoryStage.PROMPT_BUILD -> MemoryFailureCode.REQUEST
        stage == MemoryStage.DB_WRITE -> MemoryFailureCode.DATABASE
        else -> MemoryFailureCode.UNKNOWN
    }
    val detail = buildString {
        append("阶段=").append(stage.id)
        append(" 错误码=").append(code)
        append(" 异常=").append(root.javaClass.name)
        append(" 状态码=").append(status ?: "-")
        if (summary.isNotEmpty()) append(" 摘要=").append(summary)
    }
    return MemoryFailure(
        stage = stage,
        code = code,
        message = MemoryFailureText.message(code),
        advice = MemoryFailureText.advice(code),
        detail = detail,
    )
}
