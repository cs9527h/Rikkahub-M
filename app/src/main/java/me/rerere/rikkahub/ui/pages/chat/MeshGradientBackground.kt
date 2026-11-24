package me.rerere.rikkahub.ui.pages.chat

import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import kotlin.math.cos
import kotlin.math.sin

/**
 * Gemini 风格动态渐变背景。
 *
 * 原理:
 *  1. 底层一个线性渐变(顶部偏蓝、底部柔和蓝灰)。
 *  2. 上面叠几个 radialGradient 光斑(中心有色 → 边缘透明),天生就是柔的。
 *  3. 光斑沿正弦/余弦轨迹缓慢漂移。
 *
 * 不依赖 Modifier.blur,全 API 级别可用,性能也更好。
 *
 * 性能约束(0.8.8):
 *  - 相位来自一个被节流的时钟(约 20fps),只在绘制阶段读取,不触发重组;
 *  - 光斑 Brush 在 [drawWithCache] 里按尺寸构造一次,逐帧只做 canvas translate + drawCircle,
 *    不再每帧 new 4 个 radialGradient / Offset / listOf;
 *  - 系统开启「移除动画」(ANIMATOR_DURATION_SCALE = 0) 时不启动任何动画,直接画一帧静态图。
 */
@Composable
fun MeshGradientBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val dark = LocalDarkMode.current
    val reduceMotion = rememberReduceMotion()

    val baseStops = if (dark) DarkBaseStops else LightBaseStops
    val blobs = if (dark) DarkBlobs else LightBlobs
    // 让 drawWithCache 的缓存块能感知配色切换（读 State 才会让它重建缓存）
    val currentBlobs = rememberUpdatedState(blobs)

    // 一个共享时钟（秒），光斑各自按自己的周期换算相位；关闭动画时停在 0（静态帧）
    val clock = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(reduceMotion) {
        if (reduceMotion) return@LaunchedEffect
        val startNanos = withFrameNanos { it }
        while (isActive) {
            withFrameNanos { now -> clock.floatValue = (now - startNanos) / 1_000_000_000f }
            // 20fps 足够表达缓慢漂移，比跟随屏幕刷新率省 3~6 倍绘制
            delay(FRAME_INTERVAL_MS)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(colorStops = baseStops)),
    ) {
        Spacer(
            modifier = Modifier
                .fillMaxSize()
                .drawWithCache {
                    // 尺寸变化时才重建 Brush：radialGradient 的着色器以自身坐标为准，
                    // 逐帧移动时用 canvas translate 把光斑"带"到目标位置，而不是重建 Brush。
                    val longest = maxOf(size.width, size.height)
                    val spotBrushes = currentBlobs.value.map { blob ->
                        val radius = longest * blob.radiusFactor
                        Brush.radialGradient(
                            colors = listOf(blob.color.copy(alpha = blob.alpha), Color.Transparent),
                            center = Offset(radius, radius),
                            radius = radius,
                        ) to radius
                    }
                    onDrawBehind {
                        val seconds = clock.floatValue
                        currentBlobs.value.forEachIndexed { index, blob ->
                            val (brush, radius) = spotBrushes[index]
                            val phase = seconds * (TWO_PI / blob.periodSeconds) + blob.phaseShift
                            val centerX = size.width * blob.anchorX + sin(phase) * size.width * blob.swayX
                            val centerY =
                                size.height * blob.anchorY + cos(phase * blob.rateY) * size.height * blob.swayY
                            withTransform({ translate(centerX - radius, centerY - radius) }) {
                                drawCircle(brush = brush, radius = radius, center = Offset(radius, radius))
                            }
                        }
                    }
                },
        )

        content()
    }
}

private const val TWO_PI = 6.2831855f

/** 每帧之间的最小间隔；1000/45≈22fps，肉眼看不出的漂移足够顺。 */
private const val FRAME_INTERVAL_MS = 45L

private class Blob(
    val color: Color,
    val alpha: Float,
    /** 半径占屏幕长边的比例 */
    val radiusFactor: Float,
    /** 漂移中心（相对宽高） */
    val anchorX: Float,
    val anchorY: Float,
    /** 漂移幅度（相对宽高） */
    val swayX: Float,
    val swayY: Float,
    /** 纵向周期倍率，让每个光斑的轨迹不是纯正弦 */
    val rateY: Float,
    /** 初始相位，静态帧（关闭动画）时也保持错落 */
    val phaseShift: Float,
    /** 一个完整周期的秒数 */
    val periodSeconds: Float,
)

// ---- 浅色：回到官方原版观感（顶部明确的淡蓝，向下渐隐）----
//
// 0.9.0 为了消除「整屏泛蓝」把顶部压到 #D7E7F7、光斑 alpha 降到 0.34，
// 结果与默认的静态柔光背景几乎看不出差别，用户反馈「开了动态壁纸和没开一样」——
// 这个开关必须一眼可见才有意义。
// 现在恢复官方（180c2b57）的顶部蓝 #AFD0F2 → #CBE0F6 与光斑浓度，
// 只把末段从纯白 #FFFFFF 收到 #F4F8FC，避免重新引入「下半屏刺眼纯白」。
private val LightBaseStops = arrayOf(
    0.00f to Color(0xFFAFD0F2),
    0.22f to Color(0xFFCBE0F6),
    0.45f to Color(0xFFEDF4FB),
    0.65f to Color(0xFFF1F7FD),
    1.00f to Color(0xFFF4F8FC),
)

// ---- 暗色：顶部深蓝 → 底部近黑，中间过渡保持蓝调，不发灰 ----
private val DarkBaseStops = arrayOf(
    0.00f to Color(0xFF18243C),
    0.28f to Color(0xFF131D31),
    0.55f to Color(0xFF0E1626),
    0.80f to Color(0xFF0A0F1B),
    1.00f to Color(0xFF070A12),
)

// 浅色光斑：低饱和 + 低浓度，只提供层次，不把画面推白；暖色保留但更淡
private val LightBlobs = listOf(
    Blob(
        color = Color(0xFF9EC5F0), alpha = 0.72f, radiusFactor = 0.36f,
        anchorX = 0.48f, anchorY = 0.08f, swayX = 0.38f, swayY = 0.18f,
        rateY = 1.15f, phaseShift = 0.90f, periodSeconds = 5.5f,
    ),
    Blob(
        color = Color(0xFFA8E6E0), alpha = 0.56f, radiusFactor = 0.28f,
        anchorX = 0.18f, anchorY = 0.24f, swayX = 0.30f, swayY = 0.20f,
        rateY = 1.00f, phaseShift = 2.27f, periodSeconds = 7.0f,
    ),
    Blob(
        color = Color(0xFFB6D7F2), alpha = 0.62f, radiusFactor = 0.30f,
        anchorX = 0.82f, anchorY = 0.12f, swayX = -0.34f, swayY = 0.18f,
        rateY = 0.90f, phaseShift = 4.08f, periodSeconds = 8.5f,
    ),
    Blob(
        color = Color(0xFFFFC8D2), alpha = 0.42f, radiusFactor = 0.26f,
        anchorX = 0.58f, anchorY = 0.34f, swayX = 0.28f, swayY = 0.16f,
        rateY = 1.10f, phaseShift = 5.50f, periodSeconds = 6.2f,
    ),
)

// 暗色光斑：沿用原有配色，浓度略降以免整屏发灰
private val DarkBlobs = listOf(
    Blob(
        color = Color(0xFF3E6FB0), alpha = 0.52f, radiusFactor = 0.36f,
        anchorX = 0.48f, anchorY = 0.08f, swayX = 0.38f, swayY = 0.18f,
        rateY = 1.15f, phaseShift = 0.90f, periodSeconds = 5.5f,
    ),
    Blob(
        color = Color(0xFF2E7D74), alpha = 0.40f, radiusFactor = 0.28f,
        anchorX = 0.18f, anchorY = 0.24f, swayX = 0.30f, swayY = 0.20f,
        rateY = 1.00f, phaseShift = 2.27f, periodSeconds = 7.0f,
    ),
    Blob(
        color = Color(0xFF4A6E96), alpha = 0.44f, radiusFactor = 0.30f,
        anchorX = 0.82f, anchorY = 0.12f, swayX = -0.34f, swayY = 0.18f,
        rateY = 0.90f, phaseShift = 4.08f, periodSeconds = 8.5f,
    ),
    Blob(
        color = Color(0xFF7C5F9E), alpha = 0.28f, radiusFactor = 0.26f,
        anchorX = 0.58f, anchorY = 0.34f, swayX = 0.28f, swayY = 0.16f,
        rateY = 1.10f, phaseShift = 5.50f, periodSeconds = 6.2f,
    ),
)

/**
 * 系统「移除动画」是否打开。
 *
 * 和 MemoryCenterPage / ChatPage 里已有的 reduceMotion 写法保持一致：读
 * Settings.Global.ANIMATOR_DURATION_SCALE，等于 0 视为用户要求移除动画。
 */
@Composable
private fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            AndroidSettings.Global.getFloat(
                context.contentResolver,
                AndroidSettings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) == 0f
        }.getOrDefault(false)
    }
}
