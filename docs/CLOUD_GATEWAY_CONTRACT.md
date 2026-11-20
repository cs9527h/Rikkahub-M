# 云网关联调协议

## 版本和部署顺序

已配对设备通过 Bearer 令牌请求 GET /capabilities；校验 protocol=3 和配对时保存的 instance_id。chat_versions 包含 4 才发送 chat_version=4。旧客户端继续使用 v3。先部署网关，运行合成数据测试，再升级客户端。

## 发送与上下文

POST /chat/stream 接收 request_id、message、attachments（本次上传的文件 ID）。v4 添加 conversation_id，使用 Android 已持久化的聊天 UUID。服务端按网关会话基值、认证设备 ID 和聊天 ID 派生稳定的 UUIDv5。旧设备可通过 APP_LEGACY_SESSION_DEVICE 显式保留原上游会话。旧本地记录不修改，旧云端共享历史不拷贝；同一聊天重开继续使用同一个映射。长期记忆的设备权限与临时上下文独立。

ordered_context_v1 为真的网关（生产 0.7.2 起）支持 context_messages，按发送顺序列出 role 和 content，role 取 system、user、assistant。生产实现是「规则桥」：网关把本轮规则按会话写入规则目录，AstrBot 插件在 on_llm_request 里把 system 规则追加到系统提示词、把 user/assistant 规则插到上下文最前；服务端仍保留自己的会话历史，不替换整段上下文，也绝不重复追加本轮用户消息。message 及 attachments 标记本轮输入和附件。设备指令受模型服务商规则约束。生产工程师须用虚构数据确认上游真正消费有序角色、只注入一次并保留当前附件。只有验收通过后才设置 ASTRBOT_ORDERED_CONTEXT_V1=1。默认关闭。没有该能力时，已启用且绑定规则的普通聊天必须在发送前提示。

v4 客户端发送 X-Mate-Chat-Version: 4，网关限制 JSON 为 1 MiB。上传 MP4 前检查 video_upload。网关上限 8,000,000 字节，生产工程师验证上游 video 组件接受真实上传 ID 后可开启 APP_ENABLE_VIDEO=true。

## 幂等与流式状态

request_id 标识一次生成。相同 ID 和相同输入返回已有记录；不同会话、上下文或正文复用 ID 返回 HTTP 409 request_id_conflict。another_request_running 表示该会话有未完成请求。不同会话可并发。revision_conflict 表示资源版本过期。SSE data 包含 id、state、type、累计 text。running 可继续，done 才表示完成。failed、uncertain、expired 要展示失败或未知。网络断开不代表服务端停止；恢复通过 GET /requests/{id} 查询，不创建新 ID。request_cancel 为真时，主动停止会提交 POST /chat/cancel，正文只含 request_id。cancelled 为终态。断开读取与取消请求分开处理；上游计费以服务商为准。

## 记忆与插件

GET /memory 接受 q、type、after、limit、deleted。GET /memory/item?id= 获取详情，GET /memory/audit 获取操作记录。POST /memory 的 action 支持 create、update、delete、restore。批量删除传 ids 和 confirm=true。更新用 expect_content 检测并发编辑，发生冲突重新获取详情。memory_browser、memory_read、memory_write 分别代表浏览、检索及写入能力；403、409、429、503 应分别处理。客户端只将成功响应作为修改成功的证据。

GET /capabilities 的 plugins 描述经运维登记的插件状态，shortcuts 提供可使用的命令。登记不等于本轮实际调用。诊断只记录能力、计数、状态和标准错误类型，禁止采集正文或令牌。

## 验收与回滚

用临时测试实例开启规则、关闭规则、切换两个会话、重开旧会话并断开 SSE，检查同一 request_id 的生成次数。核验只读、拒绝授权、数据库不可用、冲突、软删除和恢复。关闭上游适配能力后复测阻断提示，再回退网关。保留配对、原会话命名空间和数据库。生产验收前只使用测试构建。
