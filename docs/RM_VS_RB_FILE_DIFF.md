# RM / RB 全路径差异清单（文件级快照）

> 生成日期：2026-11-22。RM 指 [Rikkahub-M](https://github.com/cs9527h/Rikkahub-M)，RB 指 [上游 RikkaHub](https://github.com/rikkahub/rikkahub)。本页由 GitHub REST 两个仓库 `master` 分支的 **递归完整 Git 文件树**按「路径 + Git blob SHA」比较所得，非功能可用性判断、非运行时测试报告。

- RM：[`4a5896a651497f3dea27c754785e6d75939cc01f`](https://github.com/cs9527h/Rikkahub-M/commit/4a5896a651497f3dea27c754785e6d75939cc01f)，应用源码 0.4.5。
- RB：[`2435dbdf4690fe26d424b8d1ab778bcde9c6cd75`](https://github.com/rikkahub/rikkahub/commit/2435dbdf4690fe26d424b8d1ab778bcde9c6cd75)，应用源码 3.3.1。
- RM 文件 `1691`；RB 文件 `1601`；**相同路径且内容相同 `1472`**。
- **RM 新增 `106`、RM 删除（RB 存在）`16`、同路径内容不同 `113`；合计 `235` 个差异路径**。
- Git 文件树均报告 `truncated=false`；如两个分支之后继续提交，本页属于**冻结快照**，必须重新生成，不能声称自动实时更新。
- 下表仅代表相同路径/内容哈希的客观差异：**Git 相同不等于已在目标安装包可用；不同不等于功能缺失或更新。** 文件名不泄露个人账户、对话和密钥；不要将私有内容写入公共文档。

**阅读导航：** [功能与架构比较](RM_VS_RB.md) · [0.5.0 下一阶段实施计划](ROADMAP_0_5_0.md)

## 文件级统计

| 类别 | 差异路径数 |
|---|---:|
| 构建、自动化与工具 | 31 |
| 文档、视觉资源与语言 | 54 |
| AI 供应商、调用与提示词 | 35 |
| 备份、存储、迁移与长期记忆 | 62 |
| 界面、消息与交互 | 31 |
| 其他与底层 | 22 |
| **合计** | **235** |

## 构建、自动化与工具（31）

| 与 RB 相比 | 路径 | RM blob SHA（前 12 位） | RB blob SHA（前 12 位） |
|---|---|---|---|
| 两侧不同 | `.agents/skills/publish-release/SKILL.md` | `a6607daa842c` | `ac9cf00e8276` |
| RM 删除 | `.github/FUNDING.yml` | — | `f7c9c60df60c` |
| RM 新增 | `.github/ISSUE_TEMPLATE/bug-report-en.yml` | `21811717ed02` | — |
| 两侧不同 | `.github/ISSUE_TEMPLATE/bug-report.yml` | `8113a109036c` | `886e681df1f8` |
| RM 新增 | `.github/ISSUE_TEMPLATE/feature-request-en.yml` | `4415ef24a157` | — |
| 两侧不同 | `.github/ISSUE_TEMPLATE/feature-request.yml` | `8c774e5fb57b` | `6fb5695df40f` |
| RM 新增 | `.github/workflows/close-blank-issues.yml` | `3878eeb154ba` | — |
| RM 新增 | `.github/workflows/mate-v1-preview.yml` | `f6fa75fdd2a3` | — |
| RM 新增 | `.github/workflows/promote-043-stable.yml` | `b38615191c52` | — |
| 两侧不同 | `.github/workflows/release.yml` | `4bc16d9b774a` | `74822f76854e` |
| 两侧不同 | `ai/build.gradle.kts` | `172e089a8dbf` | `b9e390e7ced6` |
| 两侧不同 | `app/build.gradle.kts` | `3adb02eaf6aa` | `ce3f3484da74` |
| 两侧不同 | `gradle/libs.versions.toml` | `6c329229d855` | `d8121b11a5cf` |
| RM 新增 | `scripts/compare-installed-screens.sh` | `25f6ac92177e` | — |
| RM 新增 | `scripts/connected-test-evidence.gradle.kts` | `4a496ae6c604` | — |
| RM 新增 | `scripts/perf_compare.py` | `146d78f88b6e` | — |
| RM 新增 | `scripts/publish_update_mirror.sh` | `f7d3920fc450` | — |
| RM 新增 | `scripts/run-connected-verification.sh` | `e14b61c15376` | — |
| RM 新增 | `scripts/test_connected_verification.py` | `4073be703c3c` | — |
| RM 新增 | `scripts/test_perf_compare.py` | `5810bdc2f4a3` | — |
| RM 新增 | `scripts/test_release_acceptance.py` | `a1603f998685` | — |
| RM 新增 | `scripts/test_upgrade_data_contract.py` | `3cc4a6275a9e` | — |
| RM 新增 | `scripts/test_workflow_contracts.py` | `efb4cfdbf82c` | — |
| RM 新增 | `scripts/upgrade_data_contract.py` | `95c15322f15d` | — |
| RM 新增 | `scripts/verify_release_acceptance.py` | `70d484f37439` | — |
| RM 新增 | `tools/backup-contract-tests/.gitignore` | `84c048a73cc2` | — |
| RM 新增 | `tools/backup-contract-tests/build.gradle.kts` | `e878c940e584` | — |
| RM 新增 | `tools/backup-contract-tests/settings.gradle.kts` | `c49988bdcf09` | — |
| RM 新增 | `tools/update-mirror/out/manifest.json` | `d3658f052ee5` | — |
| RM 新增 | `tools/update-mirror/out/nginx-updates.conf` | `4ee4dab82249` | — |
| RM 新增 | `tools/update-mirror/out/Rikkahub-M-0.4.3.apk` | `c2261a7597b4` | — |

## 文档、视觉资源与语言（54）

| 与 RB 相比 | 路径 | RM blob SHA（前 12 位） | RB blob SHA（前 12 位） |
|---|---|---|---|
| 两侧不同 | `.gitignore` | `36f95e74604c` | `d2e682a1f247` |
| RM 新增 | `app/src/main/assets/icons/mate-cloud.svg` | `aea8e5c816f9` | — |
| RM 删除 | `app/src/main/assets/icons/sensenova-color.svg` | — | `17f1d82c5370` |
| RM 新增 | `app/src/main/res/drawable/ic_cloud_connection.xml` | `aaea403c0121` | — |
| 两侧不同 | `app/src/main/res/values-ar/strings.xml` | `954809e24c5a` | `d51a60320a54` |
| 两侧不同 | `app/src/main/res/values-de/strings.xml` | `b65ed172207e` | `8a89f79b7256` |
| 两侧不同 | `app/src/main/res/values-ja/strings.xml` | `faf4767f9049` | `44a845cfa8af` |
| 两侧不同 | `app/src/main/res/values-ko-rKR/strings.xml` | `980052a1eff4` | `11c58eca9304` |
| 两侧不同 | `app/src/main/res/values-ru/strings.xml` | `e91ab9d49406` | `53bc8ab3da8d` |
| RM 新增 | `app/src/main/res/values-zh-rTW/mode_rule_roles.xml` | `70f12e3a9904` | — |
| 两侧不同 | `app/src/main/res/values-zh-rTW/strings.xml` | `ea147b51df10` | `9145976c8538` |
| RM 新增 | `app/src/main/res/values-zh/mode_rule_roles.xml` | `0d99e9f8e2af` | — |
| 两侧不同 | `app/src/main/res/values-zh/strings.xml` | `2386791a212f` | `bf77bc1f5592` |
| RM 新增 | `app/src/main/res/values/mode_rule_roles.xml` | `fb838146c625` | — |
| 两侧不同 | `app/src/main/res/values/strings.xml` | `1573773ff4bf` | `0d62c6bb97a2` |
| RM 新增 | `CHANGELOG_MATE.md` | `d55ae831a0ee` | — |
| RM 新增 | `docs/CLOUD_GATEWAY_CONTRACT.md` | `960ea4b124dc` | — |
| RM 新增 | `docs/LEGACY_BUG_FIX_CHECKLIST.md` | `5fe19373e6f5` | — |
| RM 新增 | `docs/MATE_FORK_MIGRATION.md` | `362175a22ea9` | — |
| RM 新增 | `docs/MATE_USER_GUIDE.md` | `65743352ce72` | — |
| RM 新增 | `docs/MATE_V1_STATUS.md` | `a2258cc4ddec` | — |
| RM 新增 | `docs/MATE_V2_REFACTOR.md` | `a194e71a6ecd` | — |
| RM 新增 | `docs/NO_ADS_POLICY.md` | `a6c741f2954b` | — |
| RM 新增 | `docs/perf/baseline.json` | `8e184892a280` | — |
| RM 新增 | `docs/perf/README.md` | `df29cf73a813` | — |
| RM 新增 | `docs/QUALITY_LEDGER.md` | `8ea2124ca8b5` | — |
| RM 新增 | `docs/readme-cover.svg` | `75bacefae7ac` | — |
| RM 新增 | `docs/releases/0.3.0.md` | `97e1ba24d94b` | — |
| RM 新增 | `docs/releases/0.3.1.md` | `fbba87b98433` | — |
| RM 新增 | `docs/releases/0.3.2.md` | `92a2bbaa6541` | — |
| RM 新增 | `docs/releases/0.3.3-preview.md` | `5a7b991db394` | — |
| RM 新增 | `docs/releases/0.4.0.md` | `2e16e0ee297f` | — |
| RM 新增 | `docs/releases/0.4.1.md` | `8586f1ebe93a` | — |
| RM 新增 | `docs/releases/0.4.2.md` | `6968622d734a` | — |
| RM 新增 | `docs/releases/0.4.3-acceptance-checklist.md` | `82b5c9afb57f` | — |
| RM 新增 | `docs/releases/0.4.3.md` | `665006e1d84b` | — |
| RM 新增 | `docs/releases/0.4.4.md` | `79bd91a54ced` | — |
| RM 新增 | `docs/releases/0.4.5.md` | `a0bd83506df8` | — |
| RM 删除 | `docs/sponsors/api-mart.png` | — | `858daddbac01` |
| RM 删除 | `docs/sponsors/maru.png` | — | `460ae9ed10ae` |
| RM 删除 | `docs/sponsors/naapi.png` | — | `f65b7c997b0d` |
| RM 删除 | `docs/sponsors/sta1n.png` | — | `d889b7e83081` |
| RM 删除 | `docs/sponsors/suixiang.jpg` | — | `62730b05a240` |
| RM 删除 | `docs/sponsors/ztest.png` | — | `d1ea0df9e8d9` |
| RM 新增 | `docs/STANDALONE_BACKUP_ONLY.md` | `7ce3d4bfe5cc` | — |
| RM 新增 | `docs/verification/2026-11-21-041-full-verification.md` | `6176dd35cb4b` | — |
| RM 新增 | `docs/verification/2026-11-21-041-workflow-repair.md` | `805502afbf90` | — |
| RM 新增 | `docs/verification/2026-11-21-backup-hardening.md` | `bf13d1edbe42` | — |
| RM 新增 | `docs/verification/2026-11-21-release-gates.md` | `70de57038617` | — |
| RM 新增 | `docs/verification/2026-11-21-upgrade-checklist.md` | `982b79dd78f1` | — |
| RM 新增 | `README_EN.md` | `f4cde9305989` | — |
| 两侧不同 | `README_ZH_CN.md` | `bfe49eb2cbaa` | `121674306884` |
| 两侧不同 | `README_ZH_TW.md` | `b10214cf77e9` | `2cfe9d607953` |
| 两侧不同 | `README.md` | `c60f5c37677e` | `156f9959f3b2` |

## AI 供应商、调用与提示词（35）

| 与 RB 相比 | 路径 | RM blob SHA（前 12 位） | RB blob SHA（前 12 位） |
|---|---|---|---|
| 两侧不同 | `ai/src/main/java/me/rerere/ai/provider/Provider.kt` | `8d9b2f442eda` | `49b5c5a0d328` |
| 两侧不同 | `ai/src/main/java/me/rerere/ai/provider/ProviderQuota.kt` | `034b2df7963d` | `f9b31f7cb9d6` |
| 两侧不同 | `ai/src/main/java/me/rerere/ai/provider/providers/google/GoogleProvider.kt` | `e8dcbb1f78d9` | `1feabc544c3c` |
| 两侧不同 | `ai/src/main/java/me/rerere/ai/provider/providers/openai/ResponseAPI.kt` | `ec21462f857d` | `0e6f3e4abf5f` |
| 两侧不同 | `ai/src/main/java/me/rerere/ai/provider/QuotaQueryRunner.kt` | `fab5354049cd` | `7b2d8a3d7141` |
| 两侧不同 | `ai/src/main/java/me/rerere/ai/util/ErrorParser.kt` | `e51402b77cbe` | `3088a3fa9001` |
| 两侧不同 | `ai/src/test/java/me/rerere/ai/provider/QuotaQueryRunnerTest.kt` | `3c6ce30228fe` | `8f62383cbc21` |
| RM 新增 | `ai/src/test/java/me/rerere/ai/util/RateLimitEntitlementTest.kt` | `007d45a84989` | — |
| 两侧不同 | `app/src/main/java/me/rerere/rikkahub/data/ai/ChatRequestFactory.kt` | `85e5e934ad68` | `45df3c838996` |
| 两侧不同 | `app/src/main/java/me/rerere/rikkahub/data/ai/ConversationMetaGenerator.kt` | `5bde12168ef8` | `acd335070e29` |
| 两侧不同 | `app/src/main/java/me/rerere/rikkahub/data/ai/tools/local/LocalTools.kt` | `7fff651146c4` | `21021a42180d` |
| 两侧不同 | `app/src/main/java/me/rerere/rikkahub/data/ai/tools/local/PhoneUseTool.kt` | `bd9e088e56d2` | `95a15fa3d29d` |
| 两侧不同 | `app/src/main/java/me/rerere/rikkahub/data/ai/tools/MemoryTools.kt` | `c10e7d691cac` | `69ce0008e53d` |
| 两侧不同 | `app/src/main/java/me/rerere/rikkahub/data/ai/transformers/PromptInjectionTransformer.kt` | `6caee9ac3d17` | `3f24d0117f92` |
| 两侧不同 | `app/src/main/java/me/rerere/rikkahub/data/ai/transformers/Transformer.kt` | `1fe879153d7a` | `8e364f1e097f` |
| 两侧不同 | `app/src/main/java/me/rerere/rikkahub/data/datastore/DefaultProviders.kt` | `c83a787301e4` | `5953521e63b5` |
| RM 删除 | `app/src/main/java/me/rerere/rikkahub/data/datastore/RecommendedProviders.kt` | — | `8314efba4f1a` |
| 两侧不同 | `app/src/main/java/me/rerere/rikkahub/data/model/Assistant.kt` | `cf992aae2218` | `4bf116d25846` |
| 两侧不同 | `app/src/main/java/me/rerere/rikkahub/ui/components/ai/ProviderQuotaText.kt` | `8750934dfe75` | `089d027c37af` |
| 两侧不同 | `app/src/main/java/me/rerere/rikkahub/ui/pages/assistant/detail/AssistantDetailVM.kt` | `d6b151102f51` | `256070cd1bbf` |
| 两侧不同 | `app/src/main/java/me/rerere/rikkahub/ui/pages/assistant/detail/AssistantImporter.kt` | `f41e910485f1` | `7cae04f00741` |
| 两侧不同 | `app/src/main/java/me/rerere/rikkahub/ui/pages/assistant/detail/AssistantLocalToolPage.kt` | `22c87241f19f` | `0222d4b9ca7c` |
| 两侧不同 | `app/src/main/java/me/rerere/rikkahub/ui/pages/assistant/detail/AssistantMemoryPage.kt` | `46a6949945c9` | `4eb0284c94e4` |
| 两侧不同 | `app/src/main/java/me/rerere/rikkahub/ui/pages/assistant/detail/PropertyEditor.kt` | `5e5ec65441e9` | `9b8a70926f63` |
| RM 新增 | `app/src/main/java/me/rerere/rikkahub/ui/pages/backup/components/RestorePassphrasePrompt.kt` | `3509c2aa51df` | — |
| 两侧不同 | `app/src/main/java/me/rerere/rikkahub/ui/pages/extensions/PromptPage.kt` | `6b22fc4223de` | `bef6dec724cf` |
| 两侧不同 | `app/src/main/java/me/rerere/rikkahub/ui/pages/setting/components/ProviderConnectionTester.kt` | `a8e561922ee4` | `0ef58a2885f3` |
| 两侧不同 | `app/src/main/java/me/rerere/rikkahub/ui/pages/setting/components/QuotaQueryOption.kt` | `04016041c7df` | `fad6c494d92d` |
| 两侧不同 | `app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingMediaProviderDetailPage.kt` | `c7128a0d7c21` | `1d0bd519821b` |
| 两侧不同 | `app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingProviderDetailPage.kt` | `dee4bb386684` | `e7386aaeb37b` |
| 两侧不同 | `app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingProviderPage.kt` | `c47c9c6c082c` | `40018ef16224` |
| RM 新增 | `app/src/test/java/me/rerere/rikkahub/data/ai/LocalTitleTest.kt` | `0ace4510b0a5` | — |
| 两侧不同 | `app/src/test/java/me/rerere/rikkahub/data/ai/tools/PhoneUseToolTest.kt` | `0d69b825eb55` | `a23e05189629` |
| 两侧不同 | `app/src/test/java/me/rerere/rikkahub/data/ai/transformers/PromptInjectionTransformerTest.kt` | `10cc2d962ade` | `92e23fa5e64d` |
| RM 新增 | `app/src/test/java/me/rerere/rikkahub/service/ForegroundAssistantIdentityTest.kt` | `d94b4f124a5f` | — |

## 备份、存储、迁移与长期记忆（62）

| 与 RB 相比 | 路径 | RM blob SHA（前 12 位） | RB blob SHA（前 12 位） |
|---|---|---|---|
| RM 新增 | `app/src/androidTest/java/me/rerere/rikkahub/data/db/migrations/MemoryTrashMigrationTest.kt` | `5e7aaf03764a` | — |
| 两侧不同 | `app/src/androidTest/java/me/rerere/rikkahub/data/sync/BackupManagerTest.kt` | `bc3e1396bd02` | `a810e142bc01` |
| 两侧不同 | `app/src/androidTest/java/me/rerere/rikkahub/data/sync/DatabaseBackupTest.kt` | `1804b7bb1d58` | `37189471cad5` |
| 两侧不同 | `app/src/main/java/me/rerere/rikkahub/data/datastore/DefaultSettings.kt` | `a8a9efe708ee` | `e71331c21cae` |
| 两侧不同 | `app/src/main/java/me/rerere/rikkahub/data/datastore/PreferencesStore.kt` | `6bd2e2f83de7` | `caed8a6bd84e` |
| 两侧不同 | `app/src/main/java/me/rerere/rikkahub/data/datastore/Settings.kt` | `9c2b2ae7c59e` | `7139ffa026e2` |
| 两侧不同 | `app/src/main/java/me/rerere/rikkahub/data/datastore/SettingsFields.kt` | `a04ac0c61c8f` | `d30b10ee6751` |
| 两侧不同 | `app/src/main/java/me/rerere/rikkahub/data/datastore/SettingsNormalization.kt` | `923866743050` | `df057fe28cab` |
| 两侧不同 | `app/src/main/java/me/rerere/rikkahub/data/db/AppDatabase.kt` | `59de5a8da546` | `dc315120336a` |
| 两侧不同 | `app/src/main/java/me/rerere/rikkahub/data/db/AppDatabaseFactory.kt` | `bab69c7369af` | `bf911fb71ee2` |
| 两侧不同 | `app/src/main/java/me/rerere/rikkahub/data/db/dao/MemoryDAO.kt` | `14e4bdaba737` | `9491adc51673` |
| 两侧不同 | `app/src/main/java/me/rerere/rikkahub/data/db/entity/MemoryFileEntity.kt` | `31f7256bd5b5` | `6c265439a008` |
| RM 新增 | `app/src/main/java/me/rerere/rikkahub/data/db/migrations/Migration_34_35.kt` | `f352d0bc5d96` | — |
| 两侧不同 | `app/src/main/java/me/rerere/rikkahub/data/model/MemoryFile.kt` | `79d390200b54` | `5b2223c53b0d` |
| RM 新增 | `app/src/main/java/me/rerere/rikkahub/data/model/MemoryPaths.kt` | `c549994c2a33` | — |
| 两侧不同 | `app/src/main/java/me/rerere/rikkahub/data/repository/MemoryRepository.kt` | `2e6ff85c389e` | `5901051f3855` |
| RM 新增 | `app/src/main/java/me/rerere/rikkahub/data/sync/AutoBackupPolicy.kt` | `06dc70174011` | — |
| RM 新增 | `app/src/main/java/me/rerere/rikkahub/data/sync/BackupArchiveFormat.kt` | `3d30b5d7b558` | — |
| RM 新增 | `app/src/main/java/me/rerere/rikkahub/data/sync/BackupCrypto.kt` | `708847971fcc` | — |
| RM 新增 | `app/src/main/java/me/rerere/rikkahub/data/sync/BackupFileUris.kt` | `4e32ebea796b` | — |
| RM 新增 | `app/src/main/java/me/rerere/rikkahub/data/sync/BackupFingerprint.kt` | `e201467a4add` | — |
| RM 新增 | `app/src/main/java/me/rerere/rikkahub/data/sync/BackupKind.kt` | `1dfff70ad169` | — |
| RM 新增 | `app/src/main/java/me/rerere/rikkahub/data/sync/BackupListing.kt` | `99837d219f30` | — |
| 两侧不同 | `app/src/main/java/me/rerere/rikkahub/data/sync/BackupManager.kt` | `a19db0c26ad7` | `b38de138bf66` |
| RM 新增 | `app/src/main/java/me/rerere/rikkahub/data/sync/BackupManifest.kt` | `e583b3d43739` | — |
| RM 新增 | `app/src/main/java/me/rerere/rikkahub/data/sync/BackupPassphraseStore.kt` | `a7dd1c84303c` | — |
| RM 新增 | `app/src/main/java/me/rerere/rikkahub/data/sync/BackupRecoveryKey.kt` | `4d01d8d5221c` | — |
| RM 新增 | `app/src/main/java/me/rerere/rikkahub/data/sync/BackupRetention.kt` | `dc9363041c6f` | — |
| RM 新增 | `app/src/main/java/me/rerere/rikkahub/data/sync/BackupSpace.kt` | `d14c616ca35c` | — |
| 两侧不同 | `app/src/main/java/me/rerere/rikkahub/data/sync/DatabaseBackup.kt` | `3371cb87c7fe` | `af49817f8209` |
| RM 新增 | `app/src/main/java/me/rerere/rikkahub/data/sync/EncryptedBackupSession.kt` | `b4d61756ba5b` | — |
| RM 新增 | `app/src/main/java/me/rerere/rikkahub/data/sync/MemoryBackupCodec.kt` | `9cdfeadb14c9` | — |
| RM 新增 | `app/src/main/java/me/rerere/rikkahub/data/sync/PartialMemoryRestore.kt` | `0b4a68157ed5` | — |
| 两侧不同 | `app/src/main/java/me/rerere/rikkahub/data/sync/S3Sync.kt` | `cb033e001028` | `363bd26df765` |
| 两侧不同 | `app/src/main/java/me/rerere/rikkahub/data/sync/webdav/WebDavClient.kt` | `dd4d8825692b` | `05432dc5f32b` |
| 两侧不同 | `app/src/main/java/me/rerere/rikkahub/data/sync/webdav/WebDavSync.kt` | `6020d5162371` | `8dbdc4e03618` |
| RM 新增 | `app/src/main/java/me/rerere/rikkahub/mate/MateAutoBackupWorker.kt` | `22770ae52147` | — |
| 两侧不同 | `app/src/main/java/me/rerere/rikkahub/ui/pages/backup/BackupPage.kt` | `a0a7669f1dcd` | `9cf1c8e411b6` |
| 两侧不同 | `app/src/main/java/me/rerere/rikkahub/ui/pages/backup/BackupVM.kt` | `e59d3c5db86f` | `4bc762c1d904` |
| 两侧不同 | `app/src/main/java/me/rerere/rikkahub/ui/pages/backup/components/BackupComponents.kt` | `c9dcd569a454` | `d6dfc3e3590e` |
| 两侧不同 | `app/src/main/java/me/rerere/rikkahub/ui/pages/memory/MemoryFilePage.kt` | `934b3e74b354` | `c66e96fbdbee` |
| 两侧不同 | `app/src/test/java/me/rerere/rikkahub/data/datastore/SettingsFieldsTest.kt` | `4d01096d971f` | `2a796a55d3c6` |
| 两侧不同 | `app/src/test/java/me/rerere/rikkahub/data/datastore/SettingsStoreTest.kt` | `96bd6d845cba` | `6f461b0d6e6d` |
| RM 新增 | `app/src/test/java/me/rerere/rikkahub/data/datastore/StandaloneDefaultsTest.kt` | `994c6a1bad42` | — |
| 两侧不同 | `app/src/test/java/me/rerere/rikkahub/data/repository/FakeMemoryDAO.kt` | `adc0827b842b` | `e90b28d528b9` |
| 两侧不同 | `app/src/test/java/me/rerere/rikkahub/data/repository/MemoryRepositoryTest.kt` | `6690c613c9af` | `003195b6a519` |
| RM 新增 | `app/src/test/java/me/rerere/rikkahub/data/sync/AutoBackupPolicyTest.kt` | `02589ae32613` | — |
| RM 新增 | `app/src/test/java/me/rerere/rikkahub/data/sync/AutomaticBackupSettingsTest.kt` | `1c70f5f5d989` | — |
| RM 新增 | `app/src/test/java/me/rerere/rikkahub/data/sync/BackupArchiveFormatTest.kt` | `f3ec9ae3ef75` | — |
| RM 新增 | `app/src/test/java/me/rerere/rikkahub/data/sync/BackupCryptoTest.kt` | `475ae521ac52` | — |
| RM 新增 | `app/src/test/java/me/rerere/rikkahub/data/sync/BackupFileUrisTest.kt` | `194ee3b3479e` | — |
| RM 新增 | `app/src/test/java/me/rerere/rikkahub/data/sync/BackupFingerprintTest.kt` | `988c37491cec` | — |
| RM 新增 | `app/src/test/java/me/rerere/rikkahub/data/sync/BackupListingTest.kt` | `cf04329d655e` | — |
| RM 新增 | `app/src/test/java/me/rerere/rikkahub/data/sync/BackupManifestTest.kt` | `37569e738a3e` | — |
| RM 新增 | `app/src/test/java/me/rerere/rikkahub/data/sync/BackupRecoveryKeyTest.kt` | `209a6dbdcf21` | — |
| RM 新增 | `app/src/test/java/me/rerere/rikkahub/data/sync/BackupRetentionTest.kt` | `255232de871f` | — |
| RM 新增 | `app/src/test/java/me/rerere/rikkahub/data/sync/BackupSpaceTest.kt` | `7232eca1f23a` | — |
| RM 新增 | `app/src/test/java/me/rerere/rikkahub/data/sync/EncryptedBackupSessionTest.kt` | `a0f9941beff5` | — |
| 两侧不同 | `app/src/test/java/me/rerere/rikkahub/data/sync/importer/DeepSeekImporterTest.kt` | `f1a3cbda26c7` | `b5e552291519` |
| RM 新增 | `app/src/test/java/me/rerere/rikkahub/data/sync/MemoryBackupCodecTest.kt` | `e18e91b81a09` | — |
| RM 新增 | `app/src/test/java/me/rerere/rikkahub/data/sync/PortableSettingsTest.kt` | `955745cced44` | — |
| RM 新增 | `app/src/test/java/me/rerere/rikkahub/data/sync/webdav/WebDavCollectionTest.kt` | `9c741a5cd774` | — |

## 界面、消息与交互（31）

| 与 RB 相比 | 路径 | RM blob SHA（前 12 位） | RB blob SHA（前 12 位） |
|---|---|---|---|
| 两侧不同 | `app/src/github/java/me/rerere/rikkahub/phoneuse/PhoneUseAccessibilityService.kt` | `c06aef2bcd41` | `5c8369671968` |
| 两侧不同 | `app/src/main/java/me/rerere/rikkahub/phoneuse/PhoneController.kt` | `d4b69bcecd2e` | `268939937a2e` |
| 两侧不同 | `app/src/main/java/me/rerere/rikkahub/service/ChatGenerationForegroundService.kt` | `bcef1c967b4f` | `6e5ddbb75fcb` |
| 两侧不同 | `app/src/main/java/me/rerere/rikkahub/service/ChatNotificationManager.kt` | `d0621b5f8e87` | `ec52ff80f803` |
| 两侧不同 | `app/src/main/java/me/rerere/rikkahub/service/ChatService.kt` | `eb565a5c081a` | `ec7e8e3a4762` |
| 两侧不同 | `app/src/main/java/me/rerere/rikkahub/ui/activity/SafeModeActivity.kt` | `96572a73d1f6` | `267b9986543e` |
| 两侧不同 | `app/src/main/java/me/rerere/rikkahub/ui/components/ai/ModelList.kt` | `2fd453c677a7` | `28747708f76c` |
| 两侧不同 | `app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessageNerdLine.kt` | `a131739007ef` | `ebf0d3e63854` |
| 两侧不同 | `app/src/main/java/me/rerere/rikkahub/ui/components/richtext/Mermaid.kt` | `aa69d560fa0c` | `1646ffada591` |
| 两侧不同 | `app/src/main/java/me/rerere/rikkahub/ui/components/ui/AIIcon.kt` | `c8a913407289` | `4f9dbacc283f` |
| 两侧不同 | `app/src/main/java/me/rerere/rikkahub/ui/components/ui/TextArea.kt` | `bca50a82824e` | `d07d14e39508` |
| 两侧不同 | `app/src/main/java/me/rerere/rikkahub/ui/components/ui/UpdateCard.kt` | `c627dbc7e66e` | `eb6773c7c3b9` |
| 两侧不同 | `app/src/main/java/me/rerere/rikkahub/ui/hooks/ExportHooks.kt` | `2926db8675d2` | `1796bc5b0431` |
| RM 删除 | `app/src/main/java/me/rerere/rikkahub/ui/pages/backup/tabs/ImportExportTab.kt` | — | `951bd5e834ae` |
| RM 删除 | `app/src/main/java/me/rerere/rikkahub/ui/pages/backup/tabs/ReminderTab.kt` | — | `622b9950f25b` |
| RM 删除 | `app/src/main/java/me/rerere/rikkahub/ui/pages/backup/tabs/S3Tab.kt` | — | `65de445f3abb` |
| RM 删除 | `app/src/main/java/me/rerere/rikkahub/ui/pages/backup/tabs/WebDavTab.kt` | — | `9b69d1512199` |
| 两侧不同 | `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatDrawer.kt` | `0bef5382074a` | `f06e0f142dd2` |
| 两侧不同 | `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatList.kt` | `a84e37205779` | `005b8f1cace8` |
| 两侧不同 | `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatPage.kt` | `e0561bf5d18b` | `b7c9719957de` |
| 两侧不同 | `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ConversationExport.kt` | `2d430e6194fc` | `50be722b8d28` |
| 两侧不同 | `app/src/main/java/me/rerere/rikkahub/ui/pages/debug/DebugPage.kt` | `1e23c8771bcb` | `5fc02165f4d2` |
| 两侧不同 | `app/src/main/java/me/rerere/rikkahub/ui/pages/extensions/skills/SkillsPage.kt` | `bb40405e696d` | `6dc848e45751` |
| 两侧不同 | `app/src/main/java/me/rerere/rikkahub/ui/pages/imggen/ImgGenPage.kt` | `bcdacd798977` | `6d5bedb8c4c4` |
| 两侧不同 | `app/src/main/java/me/rerere/rikkahub/ui/pages/mediacreation/MediaCreationRecordItem.kt` | `3f4dbfd0f704` | `236d85ae0cec` |
| 两侧不同 | `app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingAboutPage.kt` | `eab22d8da57e` | `81a64ff4baee` |
| RM 删除 | `app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingDonatePage.kt` | — | `07d82c6f9421` |
| 两侧不同 | `app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingMcpDetailPage.kt` | `e2520467ecf8` | `75d5952ee37e` |
| 两侧不同 | `app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingMediaPage.kt` | `ffabcc10361e` | `ef45e889814c` |
| 两侧不同 | `app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingPage.kt` | `bb5dea0fe4bc` | `2552dca9eb0b` |
| 两侧不同 | `app/src/main/java/me/rerere/rikkahub/ui/pages/stats/StatsPage.kt` | `085c87b20b0f` | `8cf2a88ef511` |

## 其他与底层（22）

| 与 RB 相比 | 路径 | RM blob SHA（前 12 位） | RB blob SHA（前 12 位） |
|---|---|---|---|
| RM 新增 | `app/schemas/me.rerere.rikkahub.data.db.AppDatabase/35.json` | `1da3161ff43c` | — |
| 两侧不同 | `app/src/androidTest/java/me/rerere/rikkahub/ExampleInstrumentedTest.kt` | `2197f5c7630f` | `f0b816dabca7` |
| 两侧不同 | `app/src/main/AndroidManifest.xml` | `6aab96ea3534` | `763ede3fcf2e` |
| RM 新增 | `app/src/main/assets/updates.json` | `89f898d6a182` | — |
| 两侧不同 | `app/src/main/java/me/rerere/rikkahub/data/event/AppEvent.kt` | `2d25d6b7e1b9` | `40d882fe0dc1` |
| 两侧不同 | `app/src/main/java/me/rerere/rikkahub/data/export/ExportSerializer.kt` | `5e64543b00ec` | `d4e462ca27c6` |
| RM 删除 | `app/src/main/java/me/rerere/rikkahub/data/model/Sponsor.kt` | — | `e9f9041e4586` |
| RM 删除 | `app/src/main/java/me/rerere/rikkahub/data/remote/SponsorAPI.kt` | — | `2ae4e2f78b3f` |
| 两侧不同 | `app/src/main/java/me/rerere/rikkahub/di/AppModule.kt` | `49fb7e9a7562` | `34394f85d7e2` |
| 两侧不同 | `app/src/main/java/me/rerere/rikkahub/di/DataSourceModule.kt` | `d3f07e04c95c` | `fa41ab8a373f` |
| RM 新增 | `app/src/main/java/me/rerere/rikkahub/mate/MateChangelogPage.kt` | `92626f16a2e0` | — |
| 两侧不同 | `app/src/main/java/me/rerere/rikkahub/RikkaHubApp.kt` | `48b57780c739` | `5e7d7c6c7adc` |
| 两侧不同 | `app/src/main/java/me/rerere/rikkahub/RouteActivity.kt` | `29a3b89bd439` | `461f8b466988` |
| 两侧不同 | `app/src/main/java/me/rerere/rikkahub/utils/AIIconMatcher.kt` | `17c36f7d8051` | `81b1c5616e4c` |
| RM 新增 | `app/src/main/java/me/rerere/rikkahub/utils/NotificationAvatar.kt` | `48b87fe1d845` | — |
| 两侧不同 | `app/src/main/java/me/rerere/rikkahub/utils/NotificationUtil.kt` | `1a6fb13321af` | `db853a168a11` |
| 两侧不同 | `app/src/main/java/me/rerere/rikkahub/utils/UpdateChecker.kt` | `dfa16c21ff65` | `291268e15a90` |
| RM 新增 | `app/src/main/java/me/rerere/rikkahub/utils/UpdateInstaller.kt` | `dafcde917421` | — |
| RM 新增 | `app/src/main/java/me/rerere/rikkahub/utils/UpdateParsing.kt` | `e2b65d7b9458` | — |
| RM 新增 | `app/src/main/java/me/rerere/rikkahub/utils/Version.kt` | `7e299baedf49` | — |
| RM 新增 | `app/src/test/java/me/rerere/rikkahub/data/export/ModeInjectionFileTest.kt` | `ba61f991d4ef` | — |
| RM 新增 | `app/src/test/java/me/rerere/rikkahub/utils/UpdateParsingTest.kt` | `29df21611723` | — |

## 如何重新核对

1. 分别通过 GitHub API 的 `GET /repos/{owner}/{repo}/git/trees/master?recursive=1` 获取 RM 与 RB 当前完整文件树，确认两边 `truncated=false`。
2. 仅比较 `type=blob`，以路径集合分别求 RM 独有、RB 独有、同路径 SHA 不同；按模块归类。**勿把目录节点当文件，勿仅依据此表声称某功能在手机端可用**。
3. 功能行为以 [RM / RB 对比主文](RM_VS_RB.md) 的证据/验收状态为准；合入上游新提交前需人工检查冲突、数据兼容和许可。
4. 本次 RB 比 RM 多 10 个未合入提交，RM 相对共同基线有 320 个提交；该数字对应上方固定 SHA，不代表 320 个独立功能。
