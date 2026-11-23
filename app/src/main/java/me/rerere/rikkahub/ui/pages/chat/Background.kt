package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.ui.theme.LocalDarkMode

@Composable
fun AssistantBackground(setting: Settings, modifier: Modifier) {
    val assistant = setting.getCurrentAssistant()
    if (assistant.useGradientBackground) {
        MeshGradientBackground(modifier = modifier)
        return
    }
    if (assistant.background != null) {
        val backgroundOpacity = assistant.backgroundOpacity.coerceIn(0f, 1f)
        val dark = LocalDarkMode.current
        // 遮罩强度跟着壁纸透明度走：壁纸越淡，遮罩越轻（原来固定 0.20/0.45，
        // 把透明度调低之后仍有一层常量白雾，看起来"没那么淡"）。
        val scrimAlpha = (if (dark) DARK_MAX_SCRIM else LIGHT_MAX_SCRIM) * backgroundOpacity
        // 中性 scrim：浅色用主题 surface 而不是纯白，暗色用黑——暗色下只压暗、不提亮。
        val scrimColor = if (dark) Color.Black else MaterialTheme.colorScheme.surface

        Box(modifier = modifier) {
            AsyncImage(
                model = assistant.background,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(backgroundOpacity)
            )

            if (scrimAlpha > 0f) {
                // 顶部轻、底部略重：标题区域要透出壁纸，消息区保证可读
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    scrimColor.copy(alpha = scrimAlpha * 0.45f),
                                    scrimColor.copy(alpha = scrimAlpha),
                                )
                            )
                        )
                )
            }
        }
        return
    }
    // 没有壁纸、也没开动态渐变：仍然给一层跟随主题的静态柔光。
    // 原来这里什么都不画，浅色主题下整屏就是 colorScheme.surface 的死白，
    // 顶栏、消息区、输入框全糊成一片（截图实测中部 100% 像素亮度 ≥248）。
    SoftBackground(modifier = modifier)
}

/** 壁纸拉满时的最大遮罩强度：浅色保留壁纸辨识度，暗色压暗到正文可读。 */
private const val LIGHT_MAX_SCRIM = 0.28f
private const val DARK_MAX_SCRIM = 0.42f
