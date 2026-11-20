package me.rerere.ai.provider

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.util.fetchBody
import me.rerere.ai.util.json
import me.rerere.ai.util.mergeCustomHeaders
import me.rerere.common.http.getByKey
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.math.BigDecimal
import java.math.RoundingMode

private val TEMPLATE_VARIABLE = Regex("""\{\{\s*(\w+)\s*\}\}""")

/** 替换模板里的 `{{name}}`，引用了不存在的变量时报错 */
fun renderQuotaTemplate(template: String, variable: (String) -> String?): String =
    TEMPLATE_VARIABLE.replace(template) { match ->
        val name = match.groupValues[1]
        variable(name) ?: throw IllegalArgumentException("Unknown variable {{$name}}")
    }

/** Only change the display value; never change the provider's quota or request. */
internal fun formatQuotaValue(rawValue: String, query: QuotaQuery): String {
    val amount = rawValue.toBigDecimalOrNull() ?: return rawValue
    val divisor = query.displayDivisor.toBigDecimalOrNull()
        ?.takeIf { it > BigDecimal.ZERO } ?: return rawValue
    val converted = amount.divide(divisor, 2, RoundingMode.HALF_UP).toPlainString()
    return query.currencySymbol.trim() + converted
}

/**
 * 执行 API Key 类提供商的自定义额度查询，没有开启时返回 null。
 *
 * 先放提供商级的自定义请求头，再写入查询配置的请求头，同名时以查询配置为准。
 */
internal suspend fun OkHttpClient.runQuotaQuery(
    setting: ProviderSetting.ApiKey,
    nextApiKey: () -> String,
): ProviderQuota.Balance? = withContext(Dispatchers.IO) {
    val query = setting.quotaQuery.takeIf { it.enabled } ?: return@withContext null
    // URL、请求头和请求体引用的是同一个 key
    val apiKey by lazy(nextApiKey)
    val render = { template: String ->
        renderQuotaTemplate(template) { name ->
            when (name) {
                QuotaQuery.VARIABLE_BASE_URL -> setting.baseUrl
                QuotaQuery.VARIABLE_API_KEY -> apiKey
                else -> null
            }
        }
    }
    val request = Request.Builder()
        .url(render(query.url.trim()))
        .headers(setting.mergeCustomHeaders())
        .apply {
            query.headers
                .filter { it.name.isNotBlank() }
                .forEach { header(it.name.trim(), render(it.value)) }
        }
        .apply {
            when (query.method) {
                QuotaQueryMethod.GET -> get()
                QuotaQueryMethod.POST -> post(render(query.body).toRequestBody("application/json".toMediaType()))
            }
        }
        .build()
    val value = json.parseToJsonElement(fetchBody(request)).jsonObject.getByKey(query.resultPath)
    ProviderQuota.Balance(formatQuotaValue(value, query))
}
