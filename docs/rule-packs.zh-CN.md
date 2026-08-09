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
- GitHub Release 中的签名 registry 元数据；
- 官方 Ed25519 私钥只存在于发布环境；
- 通过规则 TCK、规则准确性金标与快照重放测试。

三个仓的 `2.0` 分支均包含 Release 工作流：只有 `build.gradle`、静态 descriptor 与 `vX.Y.Z` tag 完全一致且不是 `SNAPSHOT` 时才会发布 JAR 和 `registry-entry.json`。日麻产物通过 Shadow 打入固定版本后端并明确排除 SPI；MCR/四川没有第三方运行依赖。

核心仓的 `Publish signed rule registry` 工作流会收集三个仓所有稳定 Release，重新下载并核对每个 JAR 的大小/SHA-256，再用发布环境中的 Ed25519 PKCS#8 私钥签署原始 payload。需要：

- repository variable `MAHJONG_RULE_PACK_PUBLIC_KEY_BASE64`；
- Actions secret `MAHJONG_RULE_PACK_ED25519_PRIVATE_KEY_PKCS8_BASE64`；
- 每次使用新的 `rule-registry-vYYYY.MM.DD.N` tag。

当前 GitHub 尚未配置上述变量/secret，也没有非 `SNAPSHOT` 的正式规则 Release，因此正式签名 registry 和完整实服验收仍是 2.0 发布阻断项；不能把“源码/CI 可编译”描述成“已可正式服上线”。
