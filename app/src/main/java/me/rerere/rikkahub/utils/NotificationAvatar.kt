package me.rerere.rikkahub.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.util.LruCache
import androidx.core.graphics.createBitmap
import me.rerere.rikkahub.data.model.Avatar
import java.io.File

/**
 * 通知大图标：优先使用助手自己的头像，保证通知里的发送者与聊天界面一致。
 * 只用本地数据渲染（表情或本地/内容 URI 图片）；远程地址不在这里下载，避免通知链路做网络请求。
 */
object NotificationAvatar {
    private const val SIZE_PX = 128
    private val cache = LruCache<String, Bitmap>(8)

    fun render(context: Context, avatar: Avatar?): Bitmap? {
        val key = when (avatar) {
            is Avatar.Emoji -> "emoji:" + avatar.content
            is Avatar.Image -> "image:" + avatar.url
            else -> return null
        }
        cache.get(key)?.let { return it }
        val bitmap = runCatching {
            when (avatar) {
                is Avatar.Emoji -> drawEmoji(avatar.content)
                is Avatar.Image -> loadImage(context, avatar.url)
            }
        }.getOrNull() ?: return null
        cache.put(key, bitmap)
        return bitmap
    }

    private fun drawEmoji(content: String): Bitmap? {
        val text = content.trim()
        if (text.isEmpty()) return null
        val bitmap = createBitmap(SIZE_PX, SIZE_PX)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = SIZE_PX * 0.78f
            textAlign = Paint.Align.CENTER
        }
        val bounds = Rect()
        paint.getTextBounds(text, 0, text.length, bounds)
        canvas.drawText(text, SIZE_PX / 2f, SIZE_PX / 2f - bounds.exactCenterY(), paint)
        return bitmap
    }

    private fun loadImage(context: Context, url: String): Bitmap? {
        val uri = Uri.parse(url)
        val stream = runCatching {
            when (uri.scheme) {
                "content", "android.resource" -> context.contentResolver.openInputStream(uri)
                "file" -> File(uri.path.orEmpty()).inputStream()
                null -> File(url).takeIf { it.isFile }?.inputStream()
                "http", "https" -> null
                else -> null
            }
        }.getOrNull() ?: return null
        return stream.use { input ->
            val options = android.graphics.BitmapFactory.Options().apply { inSampleSize = 2 }
            android.graphics.BitmapFactory.decodeStream(input, null, options)
                ?.let { Bitmap.createScaledBitmap(it, SIZE_PX, SIZE_PX, true) }
        }
    }
}
