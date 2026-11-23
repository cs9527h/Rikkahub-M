package me.rerere.rikkahub.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 源码级的「主线程不许有阻塞调用」守卫。
 *
 * 只靠人写注释挡不住回归：这里直接读 utils/Update*.kt，把注释和字符串字面量抹掉后
 * 粗解析出每个函数的函数体，凡是出现 OkHttp/文件/SharedPreferences 之类阻塞 API 的函数，
 * 要么函数体里显式 `withContext(Dispatchers.IO)`，要么在 [reachableOnlyFromIo] 里
 * 写清「只由 IO 段触达」并给出理由。新增裸阻塞调用会立刻失败。
 *
 * 这条测试的价值：0.8.2 的 NetworkOnMainThreadException 不是靠加一层 withContext 修好的，
 * 而是靠「谁能碰阻塞 API」这件事被机器盯着。
 */
class UpdateMainThreadSourceGuardTest {

    private val moduleRoot: File = listOf(
        File("src/main/java/me/rerere/rikkahub/utils"),
        File("app/src/main/java/me/rerere/rikkahub/utils"),
    ).firstOrNull { it.isDirectory }
        ?: error("找不到 app 模块源码目录（当前目录 ${File(".").absolutePath}）")

    private val files = listOf(
        "UpdateChecker.kt",
        "UpdateDownloadEngine.kt",
        "UpdateInstaller.kt",
        "UpdateDownloadService.kt",
    )

    /** 出现在这些函数体里就说明该函数会做真正的阻塞 IO。 */
    private val blockingMarkers = listOf(
        "newCall(",
        ".execute()",
        "body.string()",
        "readText()",
        "writeText(",
        "inputStream(",
        "FileOutputStream(",
        "listFiles()",
        "getPackageArchiveInfo(",
        "assets.open(",
    )

    /**
     * 不含 IO 包装、但只由 IO 段触达的函数，括号里写清调用链。
     * 加新条目必须同时确认调用链上没有任何主线程入口。
     */
    private val reachableOnlyFromIo = mapOf(
        "UpdateDownloadEngine.kt" to setOf(
            "downloadBlocking", // 只由 download() 的 withContext(IO) 进入
            "attemptSource", // downloadBlocking → attemptSource
            "transfer", // downloadBlocking → attemptSource → transfer
            "verifyFile", // attemptSource / transfer(416) 里的校验
            "sha256", // verifyFile
            "readMeta", // transfer / partialState，均由 IO 段调用
            "writeMeta", // transfer
        ),
        "UpdateInstaller.kt" to setOf(
            "verifyArchive", // 只由 downloadAndVerify 的 withContext(IO) 段进入
        ),
        "UpdateChecker.kt" to setOf(
            "pruneStaleDownloads", // 只由 init 里的 appScope.launch(Dispatchers.IO) 调用
        ),
    )

    @Test
    fun everyBlockingFunctionWrapsIoOrIsDocumentedAsIoOnly() {
        val violations = mutableListOf<String>()
        for (file in files) {
            val code = stripCommentsAndStrings(read(file))
            for (fn in functionsIn(code)) {
                val markers = blockingMarkers.filter { fn.body.contains(it) }
                if (markers.isEmpty()) continue
                val wrapsIo = fn.body.contains("Dispatchers.IO")
                val documented = reachableOnlyFromIo[file]?.contains(fn.name) == true
                if (!wrapsIo && !documented) violations += "$file#${fn.name} 含 $markers"
            }
        }
        assertTrue("阻塞调用既没包 IO、也不在允许清单：\n" + violations.joinToString("\n"), violations.isEmpty())
    }

    @Test
    fun downloadPreparationAndHttpBodyReadExplicitlySwitchToIo() {
        val code = stripCommentsAndStrings(read("UpdateChecker.kt"))
        val functions = functionsIn(code).associateBy { it.name }

        listOf("httpGet", "refresh", "loadCache", "saveCache", "bundledNotes").forEach { name ->
            val body = functions[name]?.body ?: fail("UpdateChecker.kt 里找不到 $name")
            assertTrue("$name 必须在 Dispatchers.IO 上执行", body.contains("withContext(Dispatchers.IO)"))
        }

        val start = functions["startDownload"]?.body ?: fail("UpdateChecker.kt 里找不到 startDownload")
        val ioIndex = start.indexOf("Dispatchers.IO")
        val backgroundIndex = start.indexOf("downloadInBackground(")
        assertTrue("startDownload 必须把下载整段放进 IO 段", ioIndex >= 0)
        assertTrue("startDownload 必须在 IO 段里调用 downloadInBackground", backgroundIndex > ioIndex)

        // await() 只把 enqueue 交给 OkHttp，协程恢复后仍回到调用方调度器读响应体 —— 真机 ONM 的成因之一
        assertTrue("httpGet 必须自己 execute() 并读正文", functions.getValue("httpGet").body.contains(".execute()"))
        assertFalse("更新检查不得再用 await()", code.contains("await()"))
        assertFalse("startDownload 不得直接做网络调用", start.contains("newCall("))
    }

    @Test
    fun foregroundServiceStaysBinderOnly() {
        val code = stripCommentsAndStrings(read("UpdateDownloadService.kt"))
        val offenders = functionsIn(code).filter { fn -> blockingMarkers.any { fn.body.contains(it) } }
        assertTrue(
            "前台服务只允许 binder/通知操作，下载必须留在 UpdateChecker 的 IO 段：${offenders.map { it.name }}",
            offenders.isEmpty(),
        )
    }

    @Test
    fun noBlockingBridgesInUpdateFeature() {
        for (file in files) {
            val code = stripCommentsAndStrings(read(file))
            assertFalse("$file 不得用 runBlocking 桥接阻塞调用", code.contains("runBlocking"))
        }
    }

    private fun read(name: String): String = File(moduleRoot, name).readText()

    private fun fail(message: String): Nothing = throw AssertionError(message)

    private data class Fn(val name: String, val body: String)

    /**
     * 粗解析函数体：先抹掉注释/字符串，再从 `fun name(` 起找到配对括号，
     * 取到函数体（或表达式体）的结束位置为止。
     *
     * 表达式体（`fun f() = expr`，没有大括号）必须在下一条声明前截断，
     * 否则会把下一个函数的函数体算成自己的，误报/漏报都会出现。
     */
    private fun functionsIn(code: String): List<Fn> {
        val result = mutableListOf<Fn>()
        val regex = Regex("""\bfun\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(""")
        val nextDeclaration = Regex(
            """\n[ \t]*(?:@[A-Za-z_.]+(?:\([^)\n]*\))?[ \t]*)*""" +
                """(?:(?:private|internal|public|protected|override|suspend|inline|operator|infix)[ \t]+)*""" +
                """fun[ \t]+[A-Za-z_]""",
        )
        var searchFrom = 0
        while (searchFrom < code.length) {
            val match = regex.find(code, searchFrom) ?: break
            val closeParen = matchingIndex(code, match.range.last, '(', ')')
            if (closeParen == null) {
                searchFrom = match.range.last + 1
                continue
            }
            val bodyOpen = code.indexOf('{', closeParen)
            val nextDecl = nextDeclaration.find(code, closeParen)
            if (bodyOpen < 0 || (nextDecl != null && nextDecl.range.first < bodyOpen)) {
                val end = nextDecl?.range?.first ?: code.length
                result += Fn(match.groupValues[1], code.substring(closeParen + 1, end))
                searchFrom = end
                continue
            }
            val bodyClose = matchingIndex(code, bodyOpen, '{', '}')
            if (bodyClose == null) {
                searchFrom = match.range.last + 1
                continue
            }
            result += Fn(match.groupValues[1], code.substring(closeParen + 1, bodyClose + 1))
            searchFrom = bodyClose + 1
        }
        return result
    }

    private fun matchingIndex(code: String, start: Int, open: Char, close: Char): Int? {
        var depth = 0
        for (i in start until code.length) {
            when (code[i]) {
                open -> depth++
                close -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return null
    }

    /** 注释和字符串字面量里的括号/关键字会干扰解析：全部替换成空格，保留换行。 */
    private fun stripCommentsAndStrings(source: String): String {
        val out = StringBuilder(source.length)
        var i = 0
        while (i < source.length) {
            val c = source[i]
            when {
                source.startsWith("\"\"\"", i) -> {
                    out.append("   ")
                    i += 3
                    while (i < source.length && !source.startsWith("\"\"\"", i)) {
                        out.append(if (source[i] == '\n') '\n' else ' ')
                        i++
                    }
                    if (i < source.length) {
                        out.append("   ")
                        i += 3
                    }
                }
                c == '"' -> {
                    out.append(' ')
                    i++
                    while (i < source.length) {
                        val d = source[i]
                        if (d == '\\') {
                            out.append("  ")
                            i += 2
                            continue
                        }
                        i++
                        if (d == '"') break
                        out.append(' ')
                    }
                }
                c == '/' && source.startsWith("//", i) -> {
                    while (i < source.length && source[i] != '\n') {
                        out.append(' ')
                        i++
                    }
                }
                c == '/' && source.startsWith("/*", i) -> {
                    out.append("  ")
                    i += 2
                    while (i < source.length && !source.startsWith("*/", i)) {
                        out.append(if (source[i] == '\n') '\n' else ' ')
                        i++
                    }
                    if (i < source.length) {
                        out.append("  ")
                        i += 2
                    }
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }
}
