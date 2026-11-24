package me.rerere.workspace

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * 递归删除目录/文件，但**不跟随符号链接**。
 *
 * Kotlin 的 [File.deleteRecursively] 在递归时会跟随目录软链：工作区里的 `files/link -> /data/user/0/<pkg>/files`
 * 会让一次「删除子目录」把 app 私有数据整个删掉；rootfs 里预置的绝对软链同理。这里改用
 * [Files.walkFileTree]（不带 FOLLOW_LINKS）——软链只会作为一个普通文件被删掉，不会进入目标。
 *
 * @return 目标最终是否已不存在。
 */
fun deleteTreeNoFollow(target: File): Boolean {
    val path = target.toPath()
    if (!Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
        return true
    }
    return runCatching {
        Files.walkFileTree(
            path,
            emptySet(),
            Int.MAX_VALUE,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.deleteIfExists(file)
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                    // 删不掉的文件（权限/竞态）继续处理其它条目，最后用存在性判断结果。
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                    Files.deleteIfExists(dir)
                    return FileVisitResult.CONTINUE
                }
            }
        )
        !Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)
    }.getOrDefault(false)
}
