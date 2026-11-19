package me.rerere.rikkahub.data.datastore

import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.QuotaQuery
import kotlin.uuid.Uuid

val DEFAULT_AUTO_MODEL_ID = Uuid.parse("b7055fb4-39f9-4042-a88a-0d80ed76cf08")

/** 李鱼走 Mate 网关，不走普通 OpenAI 接口；路径标记决定 ProviderManager 路由到网关实现 */
val MATE_PROVIDER_ID = Uuid.parse("2f0c4d5e-6a7b-4c8d-9e0f-1a2b3c4d5e6f")
val MATE_MODEL_ID = Uuid.parse("3a1b2c3d-4e5f-4a6b-8c9d-0e1f2a3b4c5d")

/** 内置 DeepSeek 提供商，快速入门页的迁移流程会直接跳到它 */
val DEEPSEEK_PROVIDER_ID = Uuid.parse("f099ad5b-ef03-446d-8e78-7e36787f780b")

/** 内置的李鱼网关提供商：地址与令牌在运行时从加密存储读取，不落设置库 */
val MATE_PROVIDER = ProviderSetting.OpenAI(
    id = MATE_PROVIDER_ID,
    name = "李鱼服务器",
    baseUrl = "",
    apiKey = "",
    builtIn = true,
    chatCompletionsPath = me.rerere.ai.provider.MATE_GATEWAY_CHAT_PATH,
    models = listOf(
        me.rerere.ai.provider.Model(
            modelId = "liyu",
            displayName = "李鱼",
            id = MATE_MODEL_ID,
        )
    ),
)

val DEFAULT_PROVIDERS = listOf(
    ProviderSetting.OpenAI(
        id = Uuid.parse("1eeea727-9ee5-4cae-93e6-6fb01a4d051e"),
        name = "OpenAI",
        baseUrl = "https://api.openai.com/v1",
        apiKey = "",
        builtIn = true
    ),
    ProviderSetting.Google(
        id = Uuid.parse("6ab18148-c138-4394-a46f-1cd8c8ceaa6d"),
        name = "Gemini",
        apiKey = "",
        enabled = true,
        builtIn = true
    ),
    ProviderSetting.OpenAI(
        id = Uuid.parse("56a94d29-c88b-41c5-8e09-38a7612d6cf8"),
        name = "硅基流动",
        baseUrl = "https://api.siliconflow.cn/v1",
        apiKey = "",
        builtIn = true,
    ),
    ProviderSetting.OpenAI(
        id = DEEPSEEK_PROVIDER_ID,
        name = "DeepSeek",
        baseUrl = "https://api.deepseek.com/v1",
        apiKey = "",
        builtIn = true,
        quotaQuery = QuotaQuery(
            enabled = true,
            url = "{{baseUrl}}/user/balance",
            resultPath = "balance_infos[0].total_balance"
        )
    ),
    ProviderSetting.OpenAI(
        id = Uuid.parse("d6c4d8c6-3f62-4ca9-a6f3-7ade6b15ecc3"),
        name = "月之暗面",
        baseUrl = "https://api.moonshot.cn/v1",
        apiKey = "",
        enabled = true,
        builtIn = true,
        quotaQuery = QuotaQuery(
            enabled = true,
            url = "{{baseUrl}}/users/me/balance",
            resultPath = "data.available_balance"
        )
    ),
    ProviderSetting.OpenAI(
        id = Uuid.parse("f76cae46-069a-4334-ab8e-224e4979e58c"),
        name = "阿里云百炼",
        baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
        apiKey = "",
        enabled = true,
        builtIn = true
    ),
    ProviderSetting.OpenAI(
        id = Uuid.parse("3dfd6f9b-f9d9-417f-80c1-ff8d77184191"),
        name = "火山引擎",
        baseUrl = "https://ark.cn-beijing.volces.com/api/v3",
        apiKey = "",
        enabled = true,
        builtIn = true
    ),
    ProviderSetting.OpenAI(
        id = Uuid.parse("3bc40dc1-b11a-46fa-863b-6306971223be"),
        name = "智谱AI开放平台",
        baseUrl = "https://open.bigmodel.cn/api/paas/v4",
        apiKey = "",
        enabled = true,
        builtIn = true
    ),
    ProviderSetting.OpenAI(
        id = Uuid.parse("f4f8870e-82d3-495b-9b64-d58e508b3b2c"),
        name = "阶跃星辰",
        baseUrl = "https://api.stepfun.com/v1",
        apiKey = "",
        enabled = true,
        builtIn = true
    ),
    ProviderSetting.OpenAI(
        id = Uuid.parse("ef5d149b-8e34-404b-818c-6ec242e5c3c5"),
        name = "腾讯Hunyuan",
        baseUrl = "https://tokenhub.tencentmaas.com/v1",
        apiKey = "",
        enabled = true,
        builtIn = true
    ),
    ProviderSetting.OpenAI(
        id = Uuid.parse("ff3cde7e-0f65-43d7-8fb2-6475c99f5990"),
        name = "xAI",
        baseUrl = "https://api.x.ai/v1",
        apiKey = "",
        enabled = true,
        builtIn = true,
        useResponseApi = true,
    ),
    ProviderSetting.Claude(
        id = Uuid.parse("b4deabea-20fb-4101-a74c-65679c7e4754"),
        name = "MiniMax",
        baseUrl = "https://api.minimaxi.com/anthropic/v1",
        apiKey = "",
        enabled = true,
        builtIn = true,
    ),
    ProviderSetting.OpenAI(
        id = Uuid.parse("a2bafe83-eaf8-47bf-a8c7-3dd82d89f637"),
        name = "MIMO",
        baseUrl = "https://api.xiaomimimo.com/v1",
        apiKey = "",
        enabled = true,
        builtIn = true,
    ),
)
