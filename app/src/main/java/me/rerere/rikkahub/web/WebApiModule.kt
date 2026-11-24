package me.rerere.rikkahub.web

import android.content.Context
import android.util.Log
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.AuthenticationFailedCause
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FolderRepository
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.web.dto.ErrorResponse
import me.rerere.rikkahub.web.dto.WebAuthTokenRequest
import me.rerere.rikkahub.web.dto.WebAuthTokenResponse
import me.rerere.rikkahub.web.routes.aiIconRoutes
import me.rerere.rikkahub.web.routes.assetsRoutes
import me.rerere.rikkahub.web.routes.conversationRoutes
import me.rerere.rikkahub.web.routes.eventsRoutes
import me.rerere.rikkahub.web.routes.filesRoutes
import me.rerere.rikkahub.web.routes.folderRoutes
import me.rerere.rikkahub.web.routes.settingsRoutes
import java.security.MessageDigest

private const val WEB_JWT_ISSUER = "rikkahub-web"
private const val WEB_JWT_AUDIENCE = "rikkahub-web-client"
private const val WEB_JWT_SUBJECT = "web-access"
private const val WEB_JWT_TTL_MILLIS = 30L * 24 * 60 * 60 * 1000
private const val WEB_ACCESS_TOKEN_QUERY_KEY = "access_token"
private const val WEB_AUTH_PROVIDER = "auth-jwt"
private const val TAG = "WebApi"

/** 同一来源地址在窗口期内最多允许的失败口令次数。 */
private const val AUTH_MAX_FAILURES = 5
private const val AUTH_FAILURE_WINDOW_MILLIS = 60_000L

private val authAttemptLimiter = AuthAttemptLimiter()

/**
 * Configure Web API for the Ktor application.
 * This should be called from app module when starting the web server.
 *
 * Example usage:
 * ```
 * startWebServer(port = 8080) {
 *     configureWebApi(context, chatService, conversationRepo, settingsStore, filesManager)
 * }
 * ```
 */
fun Application.configureWebApi(
    context: Context,
    chatService: ChatService,
    conversationRepo: ConversationRepository,
    folderRepo: FolderRepository,
    settingsStore: SettingsStore,
    filesManager: FilesManager
) {
    // 鉴权开关不在这里快照：用户可能在服务运行期间才打开 JWT 或改密码，
    // 因此 provider 每次请求都重新读 settingsStore（见下面的 authenticate 块）。
    install(ContentNegotiation) {
        json(JsonInstant)
    }

    install(StatusPages) {
        status(HttpStatusCode.NotFound) { call, status ->
            call.respond(status, ErrorResponse("Not Found", status.value))
        }
        exception<ApiException> { call, cause ->
            call.respond(cause.status, ErrorResponse(cause.message, cause.status.value))
        }
        exception<Throwable> { call, cause ->
            // 未预期的异常只进 logcat：原样回显 message 会把内部绝对路径、SQL 与底层库细节交给客户端。
            Log.e(TAG, "Unhandled web api error: ${call.request.local.uri}", cause)
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse("Internal server error", 500)
            )
        }
    }

    install(Authentication) {
        provider(WEB_AUTH_PROVIDER) {
            authenticate { context ->
                val settings = settingsStore.settingsFlow.value
                // 运行期关闭 JWT 时保持放行（与旧行为一致，用户显式选择了无口令局域网模式）。
                if (!settings.webServerJwtEnabled) {
                    context.principal(WEB_AUTH_PROVIDER, UserIdPrincipal(WEB_JWT_SUBJECT))
                    return@authenticate
                }
                // Dynamically read the current password on each request so that
                // tokens signed after a password change are validated correctly.
                val currentPassword = settings.webServerAccessPassword
                val token = extractAccessToken(
                    authorizationHeader = context.call.request.headers[HttpHeaders.Authorization],
                    queryToken = context.call.request.queryParameters[WEB_ACCESS_TOKEN_QUERY_KEY]
                )
                // Keep protected routes closed when jwt is enabled but password is missing.
                if (token != null && currentPassword.isNotBlank() && verifyWebJwt(token, currentPassword)) {
                    context.principal(WEB_AUTH_PROVIDER, UserIdPrincipal(WEB_JWT_SUBJECT))
                    return@authenticate
                }
                val cause = if (token == null) {
                    AuthenticationFailedCause.NoCredentials
                } else {
                    AuthenticationFailedCause.InvalidCredentials
                }
                context.challenge(WEB_AUTH_PROVIDER, cause) { challenge, call ->
                    if (currentPassword.isBlank()) {
                        call.respond(
                            HttpStatusCode.Forbidden,
                            ErrorResponse("Access password is not configured", HttpStatusCode.Forbidden.value)
                        )
                    } else {
                        call.respond(
                            HttpStatusCode.Unauthorized,
                            ErrorResponse("Unauthorized", HttpStatusCode.Unauthorized.value)
                        )
                    }
                    challenge.complete()
                }
            }
        }
    }

    routing {
        route("/api") {
            post("/auth/token") {
                val settings = settingsStore.settingsFlow.value
                if (!settings.webServerJwtEnabled) {
                    throw BadRequestException("JWT auth is disabled")
                }

                val accessPassword = settings.webServerAccessPassword
                if (accessPassword.isBlank()) {
                    throw BadRequestException("Access password is not configured")
                }

                // 口令接口是唯一可匿名访问的写路径，按来源地址限速，避免局域网内被离线字典爆破。
                val clientKey = call.request.local.remoteHost
                if (authAttemptLimiter.isBlocked(clientKey)) {
                    call.respond(
                        HttpStatusCode.TooManyRequests,
                        ErrorResponse("Too many failed attempts, retry later", HttpStatusCode.TooManyRequests.value)
                    )
                    return@post
                }

                val request = call.receive<WebAuthTokenRequest>()
                if (!secureEquals(request.password, accessPassword)) {
                    authAttemptLimiter.recordFailure(clientKey)
                    throw UnauthorizedException("Invalid password")
                }
                authAttemptLimiter.clear(clientKey)

                val (token, expiresAt) = createWebJwt(accessPassword)
                call.respond(
                    HttpStatusCode.OK,
                    WebAuthTokenResponse(
                        token = token,
                        expiresAt = expiresAt
                    )
                )
            }

            aiIconRoutes(context)

            authenticate(WEB_AUTH_PROVIDER) {
                conversationRoutes(chatService, conversationRepo, folderRepo, settingsStore)
                folderRoutes(chatService, folderRepo, settingsStore)
                eventsRoutes(chatService, conversationRepo, folderRepo, settingsStore)
                settingsRoutes(settingsStore)
                filesRoutes(filesManager, context)
                assetsRoutes(context)
            }
        }
    }
}

private fun createWebJwt(secret: String): Pair<String, Long> {
    val now = System.currentTimeMillis()
    val expiresAt = now + WEB_JWT_TTL_MILLIS
    val token = WebJwt.sign(
        secret = secret,
        issuer = WEB_JWT_ISSUER,
        audience = WEB_JWT_AUDIENCE,
        subject = WEB_JWT_SUBJECT,
        issuedAtMillis = now,
        expiresAtMillis = expiresAt,
    )
    return token to expiresAt
}

private fun verifyWebJwt(token: String, secret: String): Boolean {
    return WebJwt.verify(
        token = token,
        secret = secret,
        issuer = WEB_JWT_ISSUER,
        audience = WEB_JWT_AUDIENCE,
        subject = WEB_JWT_SUBJECT,
    )
}

private fun extractBearerToken(authorizationHeader: String?): String? {
    if (authorizationHeader.isNullOrBlank()) return null
    val prefix = "Bearer "
    if (!authorizationHeader.startsWith(prefix, ignoreCase = true)) return null
    return authorizationHeader.substring(prefix.length).trim().takeIf { it.isNotEmpty() }
}

private fun extractAccessToken(authorizationHeader: String?, queryToken: String?): String? {
    return extractBearerToken(authorizationHeader)
        ?: queryToken?.trim()?.takeIf { it.isNotEmpty() }
}

private fun secureEquals(left: String, right: String): Boolean {
    return MessageDigest.isEqual(left.toByteArray(Charsets.UTF_8), right.toByteArray(Charsets.UTF_8))
}

/**
 * 按来源地址统计口令失败次数。只保留窗口期内的记录，定时清理，避免长期运行时表无限增长。
 */
internal class AuthAttemptLimiter(
    private val maxFailures: Int = AUTH_MAX_FAILURES,
    private val windowMillis: Long = AUTH_FAILURE_WINDOW_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val failures = mutableMapOf<String, ArrayDeque<Long>>()

    @Synchronized
    fun isBlocked(key: String): Boolean {
        val entries = prune(key) ?: return false
        return entries.size >= maxFailures
    }

    @Synchronized
    fun recordFailure(key: String) {
        val entries = prune(key) ?: ArrayDeque<Long>().also { failures[key] = it }
        entries.addLast(clock())
    }

    @Synchronized
    fun clear(key: String) {
        failures.remove(key)
    }

    private fun prune(key: String): ArrayDeque<Long>? {
        val entries = failures[key] ?: return null
        val now = clock()
        while (entries.isNotEmpty() && now - entries.first() > windowMillis) {
            entries.removeFirst()
        }
        if (entries.isEmpty()) {
            failures.remove(key)
            return null
        }
        return entries
    }
}
