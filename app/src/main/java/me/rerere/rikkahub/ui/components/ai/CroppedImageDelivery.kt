package me.rerere.rikkahub.ui.components.ai

import java.io.File

/** The cropped source belongs to delivery until its suspending consumer has finished copying it. */
internal suspend fun <T> deliverCroppedImage(
    file: File,
    write: suspend (File) -> Unit,
    consume: suspend (File) -> T,
): T = try {
    write(file)
    consume(file)
} finally {
    file.delete()
}
