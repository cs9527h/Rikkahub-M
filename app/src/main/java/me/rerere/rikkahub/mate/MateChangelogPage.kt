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
        TopAppBar(title = { Text("Rikkahub-M 更新日志") }, navigationIcon = { BackButton() })
    }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = padding + PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text("当前源码版本 · " + BuildConfig.VERSION_NAME,
                    style = MaterialTheme.typography.titleLarge)
            }
            item {
                Text("测试构建", style = MaterialTheme.typography.titleMedium)
                Text("支持 Markdown 和 TXT 模式规则。配对兼容网关后，云端按聊天隔离；记忆中心可读取服务器详情。")
            }
            item {
                Text("0.3.2", style = MaterialTheme.typography.titleMedium)
                Text(
                    "• 通知显示助手名称与头像，不再显示连接类型；取消或失败不再推送完成通知\n" +
                    "• 同一会话的完成通知会去重，内容为空时不再显示省略号\n" +
                    "• 设置与引导文案精简，并加入每版性能基线记录",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            item {
                Text("0.3.1", style = MaterialTheme.typography.titleMedium)
                Text(
                    "• 记忆中心先核对服务器真实能力：未接入记忆库、未配置浏览器、只读模式各有独立提示\n" +
                    "• 读取失败不再被误判成「未开启记忆编辑」，404/401/403/503 都有中文说明\n" +
                    "• 云服务器图标改为云加连接点，去掉上传箭头，深浅色下统一配色与尺寸",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            item {
                Text("2026.11.20 · 0.3.0", style = MaterialTheme.typography.titleMedium)
                Text("统一云服务器图标；文件和图片逐个上传；校验流式回复；恢复新备份后需重新授权连接。")
            }
            item {
                Text("2026.11.20 · 0.2.4", style = MaterialTheme.typography.titleMedium)
                Text(
                    "• 新增「记忆中心」：直接查看、搜索、编辑、删除、恢复服务器长期记忆，不再需要先点用户\n" +
                    "• 删除为软删除并可恢复；编辑会带原文校验，避免另一台设备已改时被覆盖\n" +
                    "• 服务器未开启记忆写入时，页面只读并说明原因，不显示假成功",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            item {
                Text("2026.11.20 · 0.2.3", style = MaterialTheme.typography.titleMedium)
                Text(
                    "• 附件上传改为按网关协议发送原始字节（不再 multipart），上传接口与网关 media.py 对齐\n" +
                    "• 只允许网关支持的类型：JPEG/PNG/GIF/WebP、MP4/MP3/WAV/OGG 语音、PDF/TXT/CSV/MD/DOCX/XLSX\n" +
                    "• 单附件上限 8MB，单条消息最多 4 个附件；服务器未开启附件/文件功能时直接说明原因",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            item {
                Text("2026.11.20 · 0.2.2", style = MaterialTheme.typography.titleMedium)
                Text(
                    "• 删除旧的独立 Mate 聊天页与私有聊天库，聊天只保留原生一套\n" +
                    "• 旧库 mate_liyu_v1.db 先归档到 files/legacy-mate-backup 再清理，不影响原生会话\n" +
                    "• 云服务器聊天支持发送图片、文件、语音：先上传 /media 换取附件 ID，再随消息提交；上传失败会明确报错，不再静默丢弃",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            item {
                Text("2026.11.20 · 0.2.1", style = MaterialTheme.typography.titleMedium)
                Text(
                    "• 云服务器没有真实 Token 数据时只显示耗时，不再出现「Token 未提供」\n" +
                    "• 云服务器改用统一云图标，聊天与模型列表不再出现首字母 L\n" +
                    "• 侧边栏底部设置按钮左移；关于页入口改名「更新日志」\n" +
                    "• 新装用户 WebDAV 默认路径改为 Rikkahub-M_backups；设置项改名「云服务器」\n" +
                    "• 本版只发布一个通用安装包，可直接覆盖 0.2.0 升级",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            item {
                Text("2026.11.19 · 0.2 服务端状态与界面整理", style = MaterialTheme.typography.titleMedium)
                Text(
                    "• 精简侧栏、设置、关于和连接页面，整理中文显示名称\n" +
                    "• 云服务器状态及 DeepSeek 余额接入顶部，缺少数据时明确标注未知\n" +
                    "• 连接检测和二维码失败提供更具体的错误分类\n" +
                    "• 新增可选的打开及退出时云端完整备份，复用 WebDAV / S3\n" +
                    "• 网关未提供 Token 时仍显示消息实际耗时，自动备份与状态联动仍需实机验收",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            item {
                Text("2026.11.19 · 0.1.3 助手身份与标题修复", style = MaterialTheme.typography.titleMedium)
                Text(
                    "• AI 发言者统一显示助手「李鱼」和它的头像，不再显示模型名「Mate 网关」\n" +
                    "• 顶部只保留会话标题与助手名，模型和供应商名称移出\n" +
                    "• 快速模型失效时不再报错，网关模式改用本地标题，避免污染服务器对话",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            item {
                Text("2026.11.19 · 0.1.2 修复默认模型解析", style = MaterialTheme.typography.titleMedium)
                Text(
                    "• 内置 Mate 服务端进入默认服务商列表，李鱼助手能真正解析到模型\n" +
                    "• 升级用户缺失内置服务商时自动补回，不覆盖用户自己的配置\n" +
                    "• 应用名统一 Rikkahub-M，入口文案按命名规则整理",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            item {
                Text("2026.11.19 · 0.1.1 服务端接入原生聊天", style = MaterialTheme.typography.titleMedium)
                Text(
                    "• Mate 网关成为内置模型后端，走原生聊天页面、会话库、搜索与删除\n" +
                    "• 全新安装只提供一个默认助手，打开即进聊天\n" +
                    "• 应用名改为 Mate，设置页合并为「连接与设备」单个入口",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            item {
                Text("2026.11.19 · 第一版整合", style = MaterialTheme.typography.titleMedium)
                Text(
                    "• RikkaHub 原生聊天及动画继续保留\n" +
                    "• 服务器聊天、流式响应和断线查询\n" +
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
