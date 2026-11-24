package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.ui.unit.dp

/**
 * 宽屏（平板 / 折叠屏展开 / 横屏）下聊天内容的宽度上限。
 *
 * 不限制的话，1280dp 宽的平板上正文一行能排到 100 多个汉字，
 * 阅读时眼睛要横跨整屏；输入框和消息区也会一人一个宽度、对不齐。
 * 840dp 是 Material 的 expanded 断点，也是常见的正文行宽上限。
 */
internal val CHAT_CONTENT_MAX_WIDTH = 840.dp
