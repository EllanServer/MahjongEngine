# 官方规则包

三个规则仓独立维护并通过同一 `mahjong-rule-spi`/TCK 接入：

- `EllanServer/riichi-mahjong-java`
- `EllanServer/mahjong-mcr-java`
- `EllanServer/sichuan-mahjong-java`

规则包负责完整比赛，不只是计分：牌墙、发牌、摸牌、合法动作、反应优先级、计分支付、局次推进和比赛结束都必须封装在 provider 的不可变 `RuleState` 中。

核心只接受固定 ID：`riichi`、`mcr`、`sichuan`。首版不承诺第三方规则包兼容。

发布包要求：

- Java 21 fat JAR，排除 SPI；
- 非 `SNAPSHOT` 语义版本；
- `ServiceLoader` provider 与完整 descriptor；
- JAR SHA-256、状态 schema、核心/SPI 兼容范围；
- GitHub Release 中的 registry candidate 元数据，并由中央签名 registry 引用；
- 官方 Ed25519 私钥只存在于发布环境；
- 通过规则 TCK、规则准确性金标与快照重放测试。

三个仓的 `2.0` 分支均包含 Release 工作流：只有 `build.gradle`、静态 descriptor 与 `vX.Y.Z` tag 完全一致且不是 `SNAPSHOT` 时才会发布 JAR 和 `registry-entry.json`。三个 `v2.0.1` 产物的生产运行时均为纯 Java、零第三方依赖并明确排除 SPI；日麻不再携带 Kotlin、反射桥或 native 后端。

核心仓的 `Publish signed rule registry` 工作流会收集三个仓所有稳定 Release，重新下载并核对每个 JAR 的大小/SHA-256，再用发布环境中的 Ed25519 PKCS#8 私钥签署原始 payload。需要：

- repository variable `MAHJONG_RULE_PACK_PUBLIC_KEY_BASE64`；
- Actions secret `MAHJONG_RULE_PACK_ED25519_PRIVATE_KEY_PKCS8_BASE64`；
- 每次使用新的 `rule-registry-vYYYY.MM.DD.N` tag。

运行时允许 GitHub Release 资产从 `github.com` 正常跳转到 GitHub HTTPS 对象存储，但不允许 HTTPS 降级到 HTTP。registry 原始 payload 必须先通过内置 Ed25519 公钥验证，规则 JAR 还必须同时满足签名条目中的 URL、长度和 SHA-256，重定向不替代任何完整性校验。

GitHub 已发布三个官方 [`v2.0.1`](https://github.com/EllanServer/riichi-mahjong-java/releases/tag/v2.0.1) 规则包（[MCR](https://github.com/EllanServer/mahjong-mcr-java/releases/tag/v2.0.1)、[四川](https://github.com/EllanServer/sichuan-mahjong-java/releases/tag/v2.0.1)）和中央 [`rule-registry-v2026.08.10.1`](https://github.com/EllanServer/MahjongEngine/releases/tag/rule-registry-v2026.08.10.1)。发布流水线已逐字节验证五个稳定版本条目的大小与 SHA-256，并完成 Ed25519 签名/公钥反验。规则包发布完成不等于核心 2.0 已发布稳定版；核心正式 Release、Paper/Folia/CE 实服矩阵和长期观察窗仍分别验收。
