package me.rerere.rikkahub.mate

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.utils.plus

@Composable
fun MateChangelogPage() {
    Scaffold(topBar = {
        TopAppBar(title = { Text("Mate · 更新日志") }, navigationIcon = { BackButton() })
    }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = padding + PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text("当前源码版本 · " + BuildConfig.VERSION_NAME,
                    style = MaterialTheme.typography.titleLarge)
                Text("V1 预览功能；以 GitHub Actions 编译和真机验收结果为准。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                Text("2026.11.19 · 第一版整合", style = MaterialTheme.typography.titleMedium)
                Text(
                    "• RikkaHub 原生聊天及动画继续保留\n" +
                    "• 独立李鱼聊天、流式响应和断线查询\n" +
                    "• 本机历史、分页、搜索和消息删除\n" +
                    "• AstrBot 配对、扫码、设备管理\n" +
                    "• 服务器状态、人格与记忆只读入口\n" +
                    "• 完整去除赞助广告和推广内容\n" +
                    "• 新的 Mate 介绍、关于和帮助说明",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            item {
                Text("仍需完成", style = MaterialTheme.typography.titleMedium)
                Text(
                    "旧 Mate 无损数据导入、服务器记忆读写、人格自然成长、" +
                        "多设备同步和真实设备性能验收，均不能因为已有界面就视为完成。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            item {
                Text("版权和更新", style = MaterialTheme.typography.titleMedium)
                Text(
                    "本项目是 RikkaHub 的非官方 AGPL-3.0 Fork。上游版权与许可证保留。" +
                        " 正式版本须来自 Rikkahub-M 自有 Release，不接受其他项目的自动升级。",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}
