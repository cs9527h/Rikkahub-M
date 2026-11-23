package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.runtime.Composable
import me.rerere.rikkahub.ui.pages.memory.MemoryCenterPage

/** 保留旧导航目标的兼容入口；设置和记忆管理使用唯一的记忆中心。 */
@Composable
fun AssistantMemoryPage(id: String) {
    MemoryCenterPage(assistantId = id)
}
