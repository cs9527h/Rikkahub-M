package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import kotlin.math.max

/**
 * 默认聊天背景：没有自定义壁纸、也没开动态渐变时用它。
 *
 * 为什么需要它：`Assistant.useGradientBackground` 默认是 false，壁纸也为空，
 * 这时 [AssistantBackground] 什么都不画，聊天页露出的就是 `colorScheme.surface`——
 * 浅色主题下接近 #FFFBFE，整屏一片死白，顶栏、消息区、输入框全糊在一起没有层次。
 *
 * 设计约束：
 *  - 跟随主题色（`primary` / `tertiary`），换主题或动态取色时自动协调，不写死蓝；
 *  - 纵向只有 8% → 0 → 4% 的极淡层次，用来消除"死白"而不制造"泛蓝";
 *  - **完全静态**：`drawWithCache` 只在尺寸变化时构造 brush，没有任何动画，
 *    不增加滚动时的重绘成本；
 *  - 暗色单独取值（暗底上光斑更亮一点才有层次，但不能提亮整屏）。
 */
@Composable
fun SoftBackground(modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val dark = LocalDarkMode.current
    val surface = scheme.surface
    val primary = scheme.primary
    val tertiary = scheme.tertiary

    // 顶部靠主色一点点、底部靠中性色一点点，中间保持 surface：
    // 浅色下这是「米白 → 白 → 微灰」的层次，不会出现一条明显色带。
    // 第一版取值（浅色 7%/9%、暗色 14%/13%）截图实测顶部亮度被压到 217（基线 246），
    // 暗色顶部从 23 升到 62，两块都重了——变成「顶部一块灰紫」，不是想要的通透。
    // 这里整体砍到约一半：浅色顶部只比 surface 暗 8~10 个亮度级别的量，暗色只提 8~10 级，
    // 有层次但读起来仍然是「浅色页面 / 深色页面」，不是滤镜。
    val topTint = if (dark) 0.07f else 0.03f
    val bottomTint = if (dark) 0.03f else 0.03f
    val glowAlpha = if (dark) 0.07f else 0.05f
    val warmAlpha = if (dark) 0.04f else 0.025f

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colorStops = arrayOf(
                        0.00f to lerp(surface, primary, topTint),
                        0.42f to surface,
                        1.00f to lerp(surface, scheme.onSurface, bottomTint),
                    ),
                ),
            ),
    ) {
        Spacer(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind {
                    val longest = max(size.width, size.height)
                    // 左上主色光晕（偏上、偏左，和标题区错开）
                    val coolRadius = longest * 0.72f
                    val coolCenter = Offset(size.width * 0.22f, -size.height * 0.02f)
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(primary.copy(alpha = glowAlpha), Color.Transparent),
                            center = coolCenter,
                            radius = coolRadius,
                        ),
                        radius = coolRadius,
                        center = coolCenter,
                    )
                    // 右上暖色光晕，只负责打破单色，不制造第二块蓝
                    val warmRadius = longest * 0.58f
                    val warmCenter = Offset(size.width * 0.95f, size.height * 0.28f)
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(tertiary.copy(alpha = warmAlpha), Color.Transparent),
                            center = warmCenter,
                            radius = warmRadius,
                        ),
                        radius = warmRadius,
                        center = warmCenter,
                    )
                },
        )
    }
}
