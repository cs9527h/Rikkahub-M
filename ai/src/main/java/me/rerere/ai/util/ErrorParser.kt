package me.rerere.ai.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

class HttpException(
    message: String
) : RuntimeException(message)

/**
 * 某些账号（例如只有订阅、没有 API 计费额度）在 429 里返回 `Requests per Minute (actual/limit): 0/0`。
 * 这既不是网络故障也不是“稍后重试就好”，客户端必须明确说出来，避免用户反复重试或误以为要充值。
 */
private val ZERO_RATE_LIMIT = Regex("""Requests per Minute[^\n]*: *\d+ */ *0(?!\d)""")

fun rateLimitEntitlementMessage(code: Int, body: String?): String? {
    if (code != 429 || body.isNullOrBlank()) return null
    if (!ZERO_RATE_LIMIT.containsMatchIn(body)) return null
    return "该账号在此接口的请求额度为 0（RPM 0/0）：登录凭据没有这个通道的调用权限。" +
        "请改用对应的 API Key 计费方式，或等待服务方开放该订阅通道；重复重试不会恢复。"
}

fun JsonElement.parseErrorDetail(): HttpException {
    return when (this) {
        is JsonObject -> {
            // 尝试获取常见的错误字段
            val errorFields = listOf("error", "detail", "message", "description")

            // 查找第一个存在的错误字段
            val foundField = errorFields.firstOrNull { this[it] != null }

            if (foundField != null) {
                // 递归解析找到的字段值
                this[foundField]!!.parseErrorDetail()
            } else {
                // 如果没有找到任何错误字段，序列化整个对象
                HttpException(Json.encodeToString(JsonElement.serializer(), this))
            }
        }

        is JsonArray -> {
            if (this.isEmpty()) {
                HttpException("Unknown error: Empty JSON array")
            } else {
                // 递归解析数组的第一个元素
                this.first().parseErrorDetail()
            }
        }

        is JsonPrimitive -> {
            // 对于基本类型，直接使用其内容
            HttpException(this.jsonPrimitive.content)
        }

        else -> {
            // 其他情况，序列化整个元素
            HttpException(Json.encodeToString(JsonElement.serializer(), this))
        }
    }
}
