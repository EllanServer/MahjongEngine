# 官方规则包

三个规则仓独立维护并通过同一 `mahjong-rule-spi`/TCK 接入：

- `EllanServer/riichi-mahjong-java`
- `EllanServer/mahjong-mcr-java`
- `EllanServer/sichuan-mahjong-java`

规则包负责完整比赛，不只是计分：牌墙、发牌、摸牌、合法动作、反应优先级、计分支付、局次推进和比赛结束都必须封装在 provider 的不可变 `RuleState` 中。

核心只接受固定 ID：`riichi`、`mcr`、`sichuan`。首版不承诺第三方规则包兼容。

每个版本发布两个彼此隔离、但由同一签名坐标绑定的产物：

- `<id>-rule-pack-<version>.jar`：只含规则代码、`ServiceLoader` 与静态规则 descriptor；不得含 `assets/`、CraftEngine 配置、图片或音频；
- `<id>-resource-pack-<version>.zip`：有效载荷只含由 CraftEngine 管理的该玩法声音及类似表现资源、CE bundle 清单和归属声明；不得含 `.class` 或服务注册，也不得让插件另建资源加载路径。声音命名空间必须随版本隔离，以便新旧对局并存。

发布包要求：

- Java 21 fat JAR，排除 SPI；
- 非 `SNAPSHOT` 语义版本；
- `ServiceLoader` provider 与完整 descriptor；
- JAR 与资源 ZIP 各自的 SHA-256/长度、状态 schema、核心/SPI 兼容范围；
- GitHub Release 中的 registry candidate 元数据，并由中央签名 registry 引用；
- 官方 Ed25519 私钥只存在于发布环境；
- 通过规则 TCK、规则准确性金标与快照重放测试。

三个仓的 `2.0` 分支均包含 Release 工作流：只有 `build.gradle`、规则 descriptor、资源 descriptor 与 `vX.Y.Z` tag 完全一致且不是 `SNAPSHOT` 时，才会在同一个 GitHub Release 发布纯规则 JAR、独立资源 ZIP 和 `registry-entry.json`。规则 JAR 的生产运行时保持纯 Java、零第三方依赖并明确排除 SPI；日麻不再携带 Kotlin、反射桥或 native 后端。

核心仓的 `Publish signed rule registry` 工作流会收集三个仓所有稳定 Release，重新下载并核对每个 JAR，以及新格式条目中的资源 ZIP 的大小/SHA-256，再用发布环境中的 Ed25519 PKCS#8 私钥签署原始 payload。格式 2 保留对历史“仅 JAR”条目的读取兼容，但所有新版本都必须发布双产物。需要：

- repository variable `MAHJONG_RULE_PACK_PUBLIC_KEY_BASE64`；
- Actions secret `MAHJONG_RULE_PACK_ED25519_PRIVATE_KEY_PKCS8_BASE64`；
- 每次使用新的 `rule-registry-vYYYY.MM.DD.N` tag。

运行时允许 GitHub Release 资产从 `github.com` 正常跳转到 GitHub HTTPS 对象存储，但不允许 HTTPS 降级到 HTTP。registry 原始 payload 必须先通过内置 Ed25519 公钥验证；规则 JAR 与资源 ZIP 都必须同时满足签名条目中的 URL、长度和 SHA-256，重定向不替代任何完整性校验。

## 运行时替换

安装、验证、激活、停用和回滚都可以在服务器运行中完成：

- `/mahjong rules install <id> [version]` 下载并原子安装；
- `/mahjong rules swap <id> <version>` 让新开局立即使用该版本，进行中的牌局继续跑原版本直到结束；
- `/mahjong rules deactivate <id>` 停止分配新局；
- `/mahjong rules rollback <id>` 回到上一个坐标；
- `/mahjong rules activate <id> <version>` 仍是「下次重启生效」的保守路径。

被取代的版本在最后一局结束后自动卸载，并校验 classloader 已被回收。因此规则包不应使用 ThreadLocal、注册 JDBC driver 或 MBean，也不得在 JAR 中打包 `top.ellan.mahjong.spi` 之外的核心类——这三类做法都会让 classloader 无法回收，加载期即被拒绝。

GitHub 已发布三个官方 [`v2.0.1`](https://github.com/EllanServer/riichi-mahjong-java/releases/tag/v2.0.1) 规则包（[MCR](https://github.com/EllanServer/mahjong-mcr-java/releases/tag/v2.0.1)、[四川](https://github.com/EllanServer/sichuan-mahjong-java/releases/tag/v2.0.1)）和中央 [`rule-registry-v2026.08.10.1`](https://github.com/EllanServer/MahjongEngine/releases/tag/rule-registry-v2026.08.10.1)。发布流水线已逐字节验证五个稳定版本条目的大小与 SHA-256，并完成 Ed25519 签名/公钥反验。规则包发布完成不等于核心 2.0 已发布稳定版；核心正式 Release、Paper/Folia/CE 实服矩阵和长期观察窗仍分别验收。
