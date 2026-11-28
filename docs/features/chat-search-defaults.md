# 新对话的客户端联网搜索默认值

## 用户反馈与根因

界面中的LOCAL“本地搜索”由客户端调用已配置的联网搜索服务，不是离线全文检索。用户反馈每个新对话都要重新开启。

原ChatService.updateChatSearch在会话已有ConversationConfig时只更新该会话并立即返回，未保存助手默认选择；之后新会话从助手的旧enableWebSearch重建，因此重新显示关闭。只修当前会话或只改UI remember状态都没有解决跨新对话/重启的来源问题。

## 本轮修复合同

- 记住会话真实所属助手的明确LOCAL/OFF选择；同助手后续新草稿继承，设置持久化后重启也有效。
- 既有已绑定会话继续以自己的ConversationConfig为准，不把新默认广播到其他历史会话。
- 多助手共享模型时，LOCAL/OFF不修改共享provider/model的Search开关；只派生当前草稿的模型视角，再由bindConfig拍快照。
- 旧JSON缺失新覆盖字段时保持原行为。模型内置BUILT_IN不新增跨模型/提供商强开；显式草稿内置搜索沿用原生命周期。
- 只更新最新Settings中的实际assistantId及目标字段，已删除助手不错误回退成当前助手；并发编辑名称/头像/参数不被整份旧快照覆盖。
- 网页设置入口同步新默认覆盖，避免仅修改旧Boolean却被新覆盖字段抵消。
- 1.1.7已发布字节保持不变，新修复单独验证，不伪称已存在于已发布安装包。

## 验证状态

实现已完成，新增17项回归并已实际执行通过：服务10项、模型新增5项、编辑合并新增2项；包含旧用例的三个目标测试类共22项全部通过（ChatSearchPreferencesTest 10、AssistantSearchDefaultTest 5、AssistantDetailUpdateTest 7）；既有ConversationConfigTest 13项也通过。全模块JVM合计1774项，0失败/0错误，1项既有PDF条件跳过；主源码及Android测试编译均成功。

覆盖LOCAL/OFF新草稿、旧会话隔离、共享模型另一助手、旧JSON字段缺失/roundtrip、空补丁与模式互斥、已删除助手、重复modelId保守不修改、真实初始化ConversationSession生产helper、挂起设置写入和最新值合并、网页SettingsStore入口及迟到编辑不还原偏好。显式BUILT_IN仅草稿且唯一目标model可沿用旧的模型开关行为；LOCAL/OFF及已有会话选择不写共享provider。

Lint实际执行exit0，0 errors/0 fatal、264 warnings、3 hints；既有Python契约101项通过，git diff --check通过。修复验证阶段未关闭规则、未改正式版版本号或发布工作流。用户随后明确要求发布更新，纳入1.1.8/65候选并继续执行原有发布门禁，额外要求DEX包含searchDefault。尚未声称用户手机已安装，已发布1.1.7文件未改变。
